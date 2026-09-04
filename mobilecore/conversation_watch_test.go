package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"google.golang.org/protobuf/encoding/protojson"
)

type watchStart struct {
	reset pendingWatchReset
	watch *protocolPendingWatch
}

type resumeWatchSession struct {
	fakeSession
	starts  chan watchStart
	cursors chan *pendingWatchCursor
	mu      sync.Mutex
}

type disconnectedPollSession struct {
	fakeSession
	called chan struct{}
}

type delayedStartSession struct {
	fakeSession
	started chan struct{}
	release chan struct{}
}

type delayedStartErrorSession struct {
	fakeSession
	started    chan struct{}
	release    chan struct{}
	startErr   error
	pollCalled chan struct{}
}

func (s *delayedStartErrorSession) StartTurn(ctx context.Context, _, _, _ string, _ *turnOptionsPayload) (string, error) {
	close(s.started)
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case <-s.release:
		return "", s.startErr
	}
}

func (s *delayedStartErrorSession) ListHistory(ctx context.Context, _ string) (conversationState, error) {
	select {
	case s.pollCalled <- struct{}{}:
	default:
	}
	<-ctx.Done()
	return conversationState{}, ctx.Err()
}

func (s *delayedStartSession) StartTurn(ctx context.Context, _, _, _ string, _ *turnOptionsPayload) (string, error) {
	close(s.started)
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case <-s.release:
		return "T1", nil
	}
}

func (s *disconnectedPollSession) ListHistory(context.Context, string) (conversationState, error) {
	select {
	case s.called <- struct{}{}:
	default:
	}
	return conversationState{}, errHostConnectionClosed
}

func newResumeWatchSession() *resumeWatchSession {
	return &resumeWatchSession{starts: make(chan watchStart, 4), cursors: make(chan *pendingWatchCursor, 4)}
}

func (s *resumeWatchSession) WatchPending(ctx context.Context, _ string, cursor *pendingWatchCursor) (pendingWatchReset, *protocolPendingWatch, error) {
	var copyCursor *pendingWatchCursor
	if cursor != nil {
		value := *cursor
		copyCursor = &value
	}
	s.cursors <- copyCursor
	select {
	case <-ctx.Done():
		return pendingWatchReset{}, nil, ctx.Err()
	case start := <-s.starts:
		return start.reset, start.watch, nil
	}
}

func (s *resumeWatchSession) UnwatchPending(context.Context, *protocolPendingWatch) error { return nil }
func (s *resumeWatchSession) RespondApproval(context.Context, string, string, string) (pendingResponseResult, error) {
	return pendingResponseResult{}, nil
}
func (s *resumeWatchSession) RespondUserInput(context.Context, string, string, []pendingUserInputAnswer) (pendingResponseResult, error) {
	return pendingResponseResult{}, nil
}

func testProtocolWatch(ctx context.Context, codexID string) *protocolPendingWatch {
	return &protocolPendingWatch{client: &protocolClient{ctx: ctx}, codexID: codexID, inbox: make(chan *remotev1.Event, 16), overflow: make(chan struct{})}
}

func TestWatchReconnectUsesPairedCursorAndDoesNotDuplicateTurn(t *testing.T) {
	sess := newResumeWatchSession()
	firstCtx, closeFirst := context.WithCancel(context.Background())
	secondCtx, closeSecond := context.WithCancel(context.Background())
	t.Cleanup(closeSecond)
	first := testProtocolWatch(firstCtx, "C1")
	second := testProtocolWatch(secondCtx, "C1")
	sess.starts <- watchStart{reset: pendingWatchReset{Mode: "reset", HostRunID: "RUN", ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 5, ActiveTurn: &conversationTurn{TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}}, watch: first}
	sess.starts <- watchStart{reset: pendingWatchReset{Mode: "resumed", HostRunID: "RUN", HeadEventSeq: 5}, watch: second}

	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	t.Cleanup(func() { c.stop("cleanup") })
	c.Dispatch(`{"version":1,"id":"select","type":"select_codex","payload":{"codexId":"C1"}}`)
	if cursor := <-sess.cursors; cursor != nil {
		t.Fatalf("initial watch cursor=%+v, want nil", cursor)
	}
	waitState(t, c, func(state state) bool {
		return state.Conversation != nil && state.Conversation.PendingWatch.HeadEventSeq == 5
	})
	closeFirst()
	select {
	case cursor := <-sess.cursors:
		if cursor == nil || cursor.HostRunID != "RUN" || cursor.EventSeq != 5 {
			t.Fatalf("resume cursor=%+v", cursor)
		}
	case <-time.After(time.Second):
		t.Fatal("watch did not resubscribe")
	}
	second.inbox <- &remotev1.Event{CodexId: "C1", EventSeq: 6, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED}}}
	waitState(t, c, func(state state) bool {
		return state.Conversation.PendingWatch.HeadEventSeq == 6 && len(state.Conversation.Turns) == 1 && state.Conversation.Turns[0].Status == "completed"
	})
}

func TestWatchEventsBuildLiveTurnAndMergeHistoryWithoutDuplicates(t *testing.T) {
	c := NewCore(new(fakePlatform))
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{CodexID: "C1", HistoryComplete: true, Turns: []conversationTurn{{TurnID: "OLD", Status: "completed", Items: []conversationItem{}, Messages: []conversationMessage{}}}, PendingRequests: []pendingRequest{}}
	c.watchChunks = map[string]watchChunkState{}

	events := []*remotev1.Event{
		{CodexId: "C1", EventSeq: 1, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_RUNNING, StartedAtUnixMs: 10}}},
		{CodexId: "C1", EventSeq: 2, Event: &remotev1.Event_ItemStarted{ItemStarted: &remotev1.ItemStarted{Item: &remotev1.Item{ItemId: "I1", TurnId: "T1", Status: remotev1.ItemStatus_ITEM_STATUS_RUNNING, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{}}}}}},
		{CodexId: "C1", EventSeq: 3, Event: &remotev1.Event_ItemDelta{ItemDelta: &remotev1.ItemDelta{TurnId: "T1", ItemId: "I1", ChunkSeq: 1, Delta: &remotev1.ItemDelta_Text{Text: "hello"}}}},
		{CodexId: "C1", EventSeq: 4, Event: &remotev1.Event_ItemCompleted{ItemCompleted: &remotev1.ItemCompleted{Item: &remotev1.Item{ItemId: "I1", TurnId: "T1", Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{Text: "hello"}}}}}},
		{CodexId: "C1", EventSeq: 5, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED, CompletedAtUnixMs: 20}}},
	}
	for _, raw := range events {
		projected, err := pendingWatchEventFromProto(raw)
		if err != nil {
			t.Fatal(err)
		}
		if err := c.applyWatchEventLocked(projected); err != nil {
			t.Fatal(err)
		}
	}
	history := conversationState{CodexID: "C1", HistoryComplete: true, Turns: []conversationTurn{{TurnID: "OLD", Status: "completed", Items: []conversationItem{}, Messages: []conversationMessage{}}, {TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}}}
	merged := mergeConversationSnapshot(*c.state.Conversation, history)
	if len(merged.Turns) != 2 || merged.Running || merged.ActiveTurnID != "" || merged.Turns[1].Status != "completed" || len(merged.Turns[1].Items) != 1 || merged.Turns[1].Items[0].AgentMessage.Text != "hello" {
		t.Fatalf("merged conversation=%+v", merged)
	}
}

func TestMergeConversationSnapshotPrefersCompleteLiveTurnWhenHistoryItemIDsDrift(t *testing.T) {
	liveUser := conversationItem{ItemID: "01a06b05-dd5d-7751-8ddc-032272c3c92e", TurnID: "01a06b05-d9b3-7ca2-a7e3-ad77a7d370d9", Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_LIVE_WIRE", UserMessage: &conversationUserMessage{TextParts: []string{"again"}, Text: "again"}}
	liveAgent := conversationItem{ItemID: "msg_0aeac14c76c8a7ac", TurnID: liveUser.TurnID, Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_LIVE_WIRE", AgentMessage: &conversationAgentMessage{Text: "answer"}}
	historyUser := conversationItem{ItemID: "item-17", TurnID: liveUser.TurnID, Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", UserMessage: &conversationUserMessage{TextParts: []string{"again"}, Text: "again"}}
	historyAgent := conversationItem{ItemID: "item-18", TurnID: liveUser.TurnID, Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", AgentMessage: &conversationAgentMessage{Text: "answer"}}
	live := conversationState{CodexID: "C1", Turns: []conversationTurn{{TurnID: liveUser.TurnID, Status: "completed", Items: []conversationItem{liveUser, liveAgent}, LiveFromStart: true}}}
	history := conversationState{CodexID: "C1", HistoryComplete: true, Turns: []conversationTurn{{TurnID: liveUser.TurnID, Status: "completed", Items: []conversationItem{historyUser, historyAgent}}}}

	merged := mergeConversationSnapshot(live, history)
	if len(merged.Turns) != 1 || len(merged.Turns[0].Items) != 2 || len(merged.Turns[0].Messages) != 2 {
		t.Fatalf("same stable turn was rendered from both live and history: %+v", merged.Turns)
	}
	if merged.Turns[0].Items[0].ItemID != liveUser.ItemID || merged.Turns[0].Items[1].ItemID != liveAgent.ItemID {
		t.Fatalf("live identities were replaced: %+v", merged.Turns[0].Items)
	}
}

func TestMergeConversationSnapshotIncompleteLiveTurnStillAcceptsHistoryItems(t *testing.T) {
	live := conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: "TURN-1", Status: "running", LiveFromStart: true,
		Completeness: &conversationCompleteness{Incomplete: true},
		Items:        []conversationItem{{ItemID: "LIVE-USER", TurnID: "TURN-1", Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_LIVE_WIRE", UserMessage: &conversationUserMessage{Text: "question"}}},
	}}}
	history := conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: "TURN-1", Status: "completed",
		Items: []conversationItem{{ItemID: "HISTORY-AGENT", TurnID: "TURN-1", Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", AgentMessage: &conversationAgentMessage{Text: "answer"}}},
	}}}

	merged := mergeConversationSnapshot(live, history)
	if len(merged.Turns) != 1 || len(merged.Turns[0].Items) != 2 {
		t.Fatalf("incomplete live turn did not accept history supplement: %+v", merged.Turns)
	}
}

func TestRunningPollCannotPoisonCompleteLiveTurnIdentityLineage(t *testing.T) {
	const turnID = "01a06b05-d9b3-7ca2-a7e3-ad77a7d370d9"
	liveUser := conversationItem{ItemID: "01a06b05-dd5d-7751-8ddc-032272c3c92e", TurnID: turnID, Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_LIVE_WIRE", UserMessage: &conversationUserMessage{Text: "again"}}
	liveAgent := conversationItem{ItemID: "msg_0aeac14c76c8a7ac", TurnID: turnID, Type: "agent_message", Status: "running", Provenance: "PROVENANCE_KIND_LIVE_WIRE", AgentMessage: &conversationAgentMessage{Text: "ans"}}
	history := conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: turnID, Status: "running",
		Items: []conversationItem{
			{ItemID: "item-17", TurnID: turnID, Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", UserMessage: &conversationUserMessage{Text: "again"}},
			{ItemID: "item-18", TurnID: turnID, Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", AgentMessage: &conversationAgentMessage{Text: "answer"}},
		},
	}}}
	c := NewCore(new(fakePlatform))
	c.state.Conversation = &conversationState{CodexID: "C1", ActiveTurnID: turnID, Running: true, Turns: []conversationTurn{{
		TurnID: turnID, Status: "running", LiveFromStart: true, Items: []conversationItem{liveUser, liveAgent},
	}}}

	merged := mergeConversationSnapshot(*c.state.Conversation, history)
	if len(merged.Turns) != 1 || len(merged.Turns[0].Items) != 2 {
		t.Fatalf("running poll injected imported identity aliases: %+v", merged.Turns)
	}
	c.state.Conversation = &merged
	for _, raw := range []*remotev1.Event{
		{CodexId: "C1", EventSeq: 2, Event: &remotev1.Event_ItemCompleted{ItemCompleted: &remotev1.ItemCompleted{Item: &remotev1.Item{ItemId: liveAgent.ItemID, TurnId: turnID, Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Provenance: remotev1.ProvenanceKind_PROVENANCE_KIND_LIVE_WIRE, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{Text: "answer"}}}}}},
		{CodexId: "C1", EventSeq: 3, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: turnID, Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED}}},
	} {
		event, err := pendingWatchEventFromProto(raw)
		if err != nil {
			t.Fatal(err)
		}
		if err := c.applyWatchEventLocked(event); err != nil {
			t.Fatal(err)
		}
	}
	history.Turns[0].Status = "completed"
	final := mergeConversationSnapshot(*c.state.Conversation, history)
	if len(final.Turns) != 1 || len(final.Turns[0].Items) != 2 || len(final.Turns[0].Messages) != 2 {
		t.Fatalf("terminal history handoff duplicated the live turn: %+v", final.Turns)
	}
	if final.Turns[0].Items[0].ItemID != liveUser.ItemID || final.Turns[0].Items[1].ItemID != liveAgent.ItemID {
		t.Fatalf("terminal history handoff replaced live identities: %+v", final.Turns[0].Items)
	}
}

func TestCausalRunningReplacesEarlierPollProjectionBeforeLiveHandoff(t *testing.T) {
	const (
		turnID    = "01a06b05-d9b3-7ca2-a7e3-ad77a7d370d9"
		commandID = "start-command"
	)
	history := conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: turnID, Status: "running", StartedAtUnixMS: 10,
		Items: []conversationItem{
			{ItemID: "item-17", TurnID: turnID, Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", UserMessage: &conversationUserMessage{Text: "again"}},
			{ItemID: "item-18", TurnID: turnID, Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", AgentMessage: &conversationAgentMessage{Text: "answer"}},
		},
	}}}
	c := NewCore(new(fakePlatform))
	c.state.Conversation = &history
	c.issueConversationStartCommandLocked(commandID)
	events := []*remotev1.Event{
		{CodexId: "C1", EventSeq: 1, CausedByRequestId: commandID, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: turnID, Status: remotev1.TurnStatus_TURN_STATUS_RUNNING, StartedAtUnixMs: 10}}},
		{CodexId: "C1", EventSeq: 2, Event: &remotev1.Event_ItemCompleted{ItemCompleted: &remotev1.ItemCompleted{Item: &remotev1.Item{ItemId: "live-user", TurnId: turnID, Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Provenance: remotev1.ProvenanceKind_PROVENANCE_KIND_LIVE_WIRE, Content: &remotev1.Item_UserMessage{UserMessage: &remotev1.UserMessageItem{Input: []*remotev1.UserInputPart{{Content: &remotev1.UserInputPart_Text{Text: &remotev1.TextInput{Text: "again"}}}}}}}}}},
		{CodexId: "C1", EventSeq: 3, Event: &remotev1.Event_ItemCompleted{ItemCompleted: &remotev1.ItemCompleted{Item: &remotev1.Item{ItemId: "live-agent", TurnId: turnID, Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Provenance: remotev1.ProvenanceKind_PROVENANCE_KIND_LIVE_WIRE, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{Text: "answer"}}}}}},
		{CodexId: "C1", EventSeq: 4, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: turnID, Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED, CompletedAtUnixMs: 20}}},
	}
	for index, raw := range events {
		event, err := pendingWatchEventFromProto(raw)
		if err != nil {
			t.Fatal(err)
		}
		if err := c.applyWatchEventLocked(event); err != nil {
			t.Fatal(err)
		}
		if index == 0 && (len(c.state.Conversation.Turns[0].Items) != 0 || c.state.Conversation.Turns[0].CausedByCommandID != commandID || !c.state.Conversation.Turns[0].LiveFromStart) {
			t.Fatalf("causal running did not isolate the live lineage: %+v", c.state.Conversation.Turns[0])
		}
	}
	history.Turns[0].Status = "completed"
	history.Turns[0].CompletedAtUnixMS = 20
	final := mergeConversationSnapshot(*c.state.Conversation, history)
	if len(final.Turns) != 1 || len(final.Turns[0].Items) != 2 || len(final.Turns[0].Messages) != 2 {
		t.Fatalf("response/poll/causal handoff duplicated the live turn: %+v", final.Turns)
	}
	if final.Turns[0].Items[0].ItemID != "live-user" || final.Turns[0].Items[1].ItemID != "live-agent" || final.Turns[0].CausedByCommandID != commandID {
		t.Fatalf("live identity or request cause was lost: %+v", final.Turns[0])
	}
	encoded, err := json.Marshal(final.Turns[0])
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), `"causedByCommandId":"start-command"`) {
		t.Fatalf("serialized turn omitted causal request: %s", encoded)
	}
}

func TestMidTurnResetDoesNotClaimCompleteLiveProjection(t *testing.T) {
	c := NewCore(new(fakePlatform))
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{}}
	c.issueConversationStartCommandLocked("start-command")
	started, err := pendingWatchEventFromProto(&remotev1.Event{
		CodexId: "C1", EventSeq: 1, CausedByRequestId: "start-command",
		Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "TURN-1", Status: remotev1.TurnStatus_TURN_STATUS_RUNNING}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := c.applyWatchEventLocked(started); err != nil {
		t.Fatal(err)
	}
	if !c.state.Conversation.Turns[0].LiveFromStart {
		t.Fatal("caused running event did not establish a continuous live projection")
	}
	resetItem := conversationItem{ItemID: "LIVE-USER", TurnID: "TURN-1", Type: "user_message", Status: "completed", Provenance: "PROVENANCE_KIND_LIVE_WIRE", UserMessage: &conversationUserMessage{Text: "question"}}
	c.applyWatchResetLocked(pendingWatchReset{ActiveTurn: &conversationTurn{TurnID: "TURN-1", Status: "running", Items: []conversationItem{resetItem}}})
	turn := &c.state.Conversation.Turns[0]
	if turn.LiveFromStart {
		t.Fatal("mid-turn RESET was incorrectly treated as a complete live stream")
	}
	if turn.CausedByCommandID != "start-command" {
		t.Fatalf("RESET cleared causal request ID: %+v", turn)
	}
	turn.Status = "completed"
	history := conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: "TURN-1", Status: "completed",
		Items: []conversationItem{{ItemID: "HISTORY-AGENT", TurnID: "TURN-1", Type: "agent_message", Status: "completed", Provenance: "PROVENANCE_KIND_IMPORTED_HISTORY", AgentMessage: &conversationAgentMessage{Text: "answer"}}},
	}}}

	merged := mergeConversationSnapshot(*c.state.Conversation, history)
	if len(merged.Turns) != 1 || len(merged.Turns[0].Items) != 2 {
		t.Fatalf("mid-turn RESET did not accept history supplement: %+v", merged.Turns)
	}
}

func TestCausalRunningAfterTerminalIncompletePollRestartsIssuedLiveLineage(t *testing.T) {
	const (
		commandID = "start-command"
		turnID    = "T1"
	)
	sess := &delayedStartSession{started: make(chan struct{}), release: make(chan struct{})}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase, c.state.SelectedCodexID = sess, "ready", "C1"
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{}}
	c.startTurn(commandID, startTurnPayload{Text: "question"})
	select {
	case <-sess.started:
	case <-time.After(time.Second):
		t.Fatal("StartTurn RPC did not start")
	}
	// Model a terminal, incomplete ListHistory projection arriving before the
	// successful StartTurn response and causal watch event.
	c.mu.Lock()
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: turnID, Status: "completed", CompletedAtUnixMS: 20,
		Failure:      "stale failure",
		Provenance:   remotev1.ProvenanceKind_PROVENANCE_KIND_IMPORTED_HISTORY.String(),
		Completeness: &conversationCompleteness{Incomplete: true, Reason: "history lag"},
		Items: []conversationItem{{
			ItemID: "item-17", TurnID: turnID, Type: "user_message", Status: "completed",
			Provenance:  remotev1.ProvenanceKind_PROVENANCE_KIND_IMPORTED_HISTORY.String(),
			UserMessage: &conversationUserMessage{Text: "question"},
		}},
	}}}
	c.mu.Unlock()
	close(sess.release)
	waitState(t, c, func(state state) bool { return state.Phase == "ready" })
	c.mu.Lock()
	if c.conversationStartCommandID != "" {
		c.mu.Unlock()
		t.Fatalf("terminal response path retained current StartTurn command %q", c.conversationStartCommandID)
	}
	if _, ok := c.conversationStartCommandIDs[commandID]; !ok {
		c.mu.Unlock()
		t.Fatal("terminal response path forgot issued StartTurn before causal event")
	}
	event, err := pendingWatchEventFromProto(&remotev1.Event{
		CodexId: "C1", EventSeq: 1, CausedByRequestId: commandID,
		Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{
			TurnId: turnID, Status: remotev1.TurnStatus_TURN_STATUS_RUNNING, StartedAtUnixMs: 10,
		}},
	})
	if err != nil {
		c.mu.Unlock()
		t.Fatal(err)
	}
	if err := c.applyWatchEventLocked(event); err != nil {
		c.mu.Unlock()
		t.Fatal(err)
	}
	turn := c.state.Conversation.Turns[0]
	c.mu.Unlock()
	if turn.Status != "running" || turn.CompletedAtUnixMS != 0 || turn.Failure != "" || turn.Completeness != nil || len(turn.Items) != 0 {
		t.Fatalf("causal running retained terminal poll projection: %+v", turn)
	}
	if turn.CausedByCommandID != commandID || !continuousLiveTurnProjection(turn) {
		t.Fatalf("causal running did not establish continuous live lineage: %+v", turn)
	}
}

func TestUnissuedCausalRunningCannotClaimContinuousLiveProjection(t *testing.T) {
	c := NewCore(new(fakePlatform))
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{{
		TurnID: "TURN-1", Status: "completed", CompletedAtUnixMS: 20,
		Completeness: &conversationCompleteness{Incomplete: true},
	}}}
	event, err := pendingWatchEventFromProto(&remotev1.Event{
		CodexId: "C1", EventSeq: 1, CausedByRequestId: "foreign-command",
		Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{
			TurnId: "TURN-1", Status: remotev1.TurnStatus_TURN_STATUS_RUNNING,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := c.applyWatchEventLocked(event); err != nil {
		t.Fatal(err)
	}
	turn := c.state.Conversation.Turns[0]
	if turn.LiveFromStart || turn.CausedByCommandID != "" || turn.Status != "completed" {
		t.Fatalf("unissued causal request was trusted: %+v", turn)
	}
}

func TestMergeConversationSnapshotPreservesRepeatedTextWithDistinctIdentities(t *testing.T) {
	question := func(turnID, itemID string) conversationTurn {
		return conversationTurn{TurnID: turnID, Status: "completed", Items: []conversationItem{{
			ItemID: itemID, TurnID: turnID, Type: "user_message", Status: "completed",
			UserMessage: &conversationUserMessage{TextParts: []string{"same question"}, Text: "same question"},
		}}}
	}
	history := conversationState{CodexID: "C1", Turns: []conversationTurn{question("TURN-1", "USER-1"), question("TURN-2", "USER-2")}}

	merged := mergeConversationSnapshot(conversationState{CodexID: "C1"}, history)
	if len(merged.Turns) != 2 || len(merged.Turns[0].Items) != 1 || len(merged.Turns[1].Items) != 1 {
		t.Fatalf("distinct repeated messages were incorrectly deduplicated: %+v", merged.Turns)
	}
}

func TestCodexUpdatedAndForgottenImmediatelyPatchListAndSelection(t *testing.T) {
	initial, err := marshalCodexesForApp(&remotev1.ListCodexesResponse{Codexes: []*remotev1.Codex{{CodexId: "C1", Title: "old"}, {CodexId: "C2", Title: "other"}}})
	if err != nil {
		t.Fatal(err)
	}
	c := NewCore(new(fakePlatform))
	c.state.Codexes = initial
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{CodexID: "C1"}
	updated := &remotev1.Event{CodexId: "C1", EventSeq: 1, Event: &remotev1.Event_CodexUpdated{CodexUpdated: &remotev1.CodexUpdated{Codex: &remotev1.Codex{CodexId: "C1", Title: "new", Status: remotev1.CodexStatus_CODEX_STATUS_RUNNING}}}}
	projected, _ := pendingWatchEventFromProto(updated)
	if err := c.applyWatchEventLocked(projected); err != nil {
		t.Fatal(err)
	}
	list := new(remotev1.ListCodexesResponse)
	if err := (protojson.UnmarshalOptions{}).Unmarshal(c.state.Codexes, list); err != nil || len(list.Codexes) != 2 || list.Codexes[0].Title != "new" {
		t.Fatalf("updated list=%+v err=%v", list, err)
	}
	forgotten := &remotev1.Event{CodexId: "C1", EventSeq: 2, Event: &remotev1.Event_CodexForgotten{CodexForgotten: &remotev1.CodexForgotten{}}}
	projected, _ = pendingWatchEventFromProto(forgotten)
	if err := c.applyWatchEventLocked(projected); err != errWatchedCodexForgotten {
		t.Fatalf("forget error=%v", err)
	}
	if c.state.SelectedCodexID != "" || c.state.Conversation != nil {
		t.Fatalf("forgotten selection remained: %+v", c.state)
	}
	var raw map[string]any
	if err := json.Unmarshal(c.state.Codexes, &raw); err != nil || len(raw["codexes"].([]any)) != 1 {
		t.Fatalf("forgotten list=%s err=%v", c.state.Codexes, err)
	}
}

func TestResetWithoutActiveTurnSuppressesStaleRunningHistory(t *testing.T) {
	c := NewCore(new(fakePlatform))
	pollCtx, pollCancel := context.WithCancel(context.Background())
	c.pollCancel = pollCancel
	c.conversationPollTurnID = "T1"
	c.state.Conversation = &conversationState{
		CodexID: "C1", ActiveTurnID: "T1", Running: true,
		Turns: []conversationTurn{{TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}},
	}
	c.applyWatchResetLocked(pendingWatchReset{Mode: "reset", HostRunID: "RUN", ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 9})
	if c.pollCancel != nil || c.state.Conversation.Running || c.state.Conversation.ActiveTurnID != "" || c.state.Conversation.Turns[0].Status == "running" {
		t.Fatalf("RESET did not authoritatively clear activity: %+v poll=%v", c.state.Conversation, c.pollCancel != nil)
	}
	select {
	case <-pollCtx.Done():
	default:
		t.Fatal("RESET did not cancel the obsolete conversation poll")
	}

	stale := conversationState{CodexID: "C1", Turns: []conversationTurn{{TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}}}
	merged := mergeConversationSnapshot(*c.state.Conversation, stale)
	merged = mergeConversationSnapshot(merged, stale)
	if merged.Running || merged.ActiveTurnID != "" || merged.Turns[0].Status == "running" {
		t.Fatalf("stale history revived RESET-cleared turn: %+v", merged)
	}
}

func TestTerminalWatchEventStopsDisconnectedPollAndRejectsLaggingHistory(t *testing.T) {
	sess := &disconnectedPollSession{called: make(chan struct{}, 1)}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	c.conversationRunID = 1
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{
		CodexID: "C1", ActiveTurnID: "T1", Running: true,
		Turns: []conversationTurn{{TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}},
	}
	pollCtx, pollCancel := context.WithCancel(context.Background())
	c.pollCancel = pollCancel
	c.conversationPollTurnID = "T1"
	done := make(chan struct{})
	go func() {
		defer close(done)
		c.pollConversation(pollCtx, sess, 1, "turn-command", "C1", "T1")
	}()
	select {
	case <-sess.called:
	case <-time.After(time.Second):
		t.Fatal("poll did not observe the simulated disconnect")
	}

	raw := &remotev1.Event{CodexId: "C1", EventSeq: 10, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED, CompletedAtUnixMs: 20}}}
	event, err := pendingWatchEventFromProto(raw)
	if err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	err = c.applyWatchEventLocked(event)
	c.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("terminal watch event did not stop disconnected poll")
	}
	if c.pollCancel != nil || c.conversationPollTurnID != "" || c.state.Conversation.Running || c.state.Conversation.Turns[0].Status != "completed" {
		t.Fatalf("terminal event left operation busy: %+v poll=%v pollTurn=%q", c.state.Conversation, c.pollCancel != nil, c.conversationPollTurnID)
	}
	lagging := conversationState{CodexID: "C1", Turns: []conversationTurn{{TurnID: "T1", Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}}}}
	merged := mergeConversationSnapshot(*c.state.Conversation, lagging)
	if merged.Running || merged.ActiveTurnID != "" || merged.Turns[0].Status != "completed" {
		t.Fatalf("lagging history rolled back realtime terminal state: %+v", merged)
	}
}

func TestTerminalWatchEventBeforeStartResponseCannotRestartPolling(t *testing.T) {
	sess := &delayedStartSession{started: make(chan struct{}), release: make(chan struct{})}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{}}
	c.startTurn("start", startTurnPayload{Text: "fast"})
	select {
	case <-sess.started:
	case <-time.After(time.Second):
		t.Fatal("StartTurn RPC did not start")
	}
	raw := &remotev1.Event{CodexId: "C1", EventSeq: 1, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED}}}
	event, err := pendingWatchEventFromProto(raw)
	if err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	err = c.applyWatchEventLocked(event)
	c.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	close(sess.release)
	waitState(t, c, func(state state) bool {
		return state.Phase == "ready" && state.Conversation != nil && len(state.Conversation.Turns) == 1 && state.Conversation.Turns[0].Status == "completed"
	})
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pollCancel != nil || c.conversationPollTurnID != "" || c.state.Conversation.Running {
		t.Fatalf("late StartTurn response restarted completed turn: %+v poll=%v pollTurn=%q", c.state.Conversation, c.pollCancel != nil, c.conversationPollTurnID)
	}
}

func TestStartTurnUnknownOutcomeKeepsWatchConfirmedRunningTurn(t *testing.T) {
	sess := &delayedStartErrorSession{
		started: make(chan struct{}), release: make(chan struct{}), startErr: errHostConnectionClosed, pollCalled: make(chan struct{}, 1),
	}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{}}
	t.Cleanup(func() { c.stop("cleanup") })
	c.startTurn("start-unknown", startTurnPayload{Text: "run"})
	select {
	case <-sess.started:
	case <-time.After(time.Second):
		t.Fatal("StartTurn RPC did not start")
	}
	raw := &remotev1.Event{CodexId: "C1", EventSeq: 1, Event: &remotev1.Event_TurnUpdated{TurnUpdated: &remotev1.TurnUpdated{TurnId: "T1", Status: remotev1.TurnStatus_TURN_STATUS_RUNNING}}}
	event, err := pendingWatchEventFromProto(raw)
	if err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	err = c.applyWatchEventLocked(event)
	c.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	close(sess.release)
	select {
	case <-sess.pollCalled:
	case <-time.After(time.Second):
		t.Fatal("unknown StartTurn outcome did not establish history handoff poll")
	}
	c.mu.Lock()
	state := c.state
	pollInstalled := c.pollCancel != nil
	pollTurnID := c.conversationPollTurnID
	c.mu.Unlock()
	if state.Phase != "ready" || state.Error != "" || state.CommandID != "start-unknown" || state.Conversation == nil || !state.Conversation.Running || state.Conversation.ActiveTurnID != "T1" || len(state.Conversation.Turns) != 1 || state.Conversation.Turns[0].Status != "running" {
		t.Fatalf("live running state was rolled back: %+v", state)
	}
	if !pollInstalled || pollTurnID != "T1" {
		t.Fatalf("poll gate not retained: poll=%v pollTurn=%q", pollInstalled, pollTurnID)
	}
	rejected := decodeState(t, c.startTurn("duplicate", startTurnPayload{Text: "again"}))
	if rejected.Error != "a turn is already running" || rejected.Conversation == nil || !rejected.Conversation.Running || rejected.Conversation.ActiveTurnID != "T1" {
		t.Fatalf("second start was not rejected without rollback: %+v", rejected)
	}
}

func TestStartTurnErrorWithoutLiveRunningUsesFailurePath(t *testing.T) {
	sess := &delayedStartErrorSession{
		started: make(chan struct{}), release: make(chan struct{}), startErr: errors.New("definite rejection"), pollCalled: make(chan struct{}, 1),
	}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "C1"
	c.state.Conversation = &conversationState{CodexID: "C1", Turns: []conversationTurn{}}
	c.startTurn("start-failed", startTurnPayload{Text: "reject"})
	<-sess.started
	close(sess.release)
	waitState(t, c, func(state state) bool { return state.Phase == "error" && state.Error == "definite rejection" })
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pollCancel != nil || c.conversationPollTurnID != "" || c.state.Conversation.Running || c.state.Conversation.ActiveTurnID != "" {
		t.Fatalf("definite error did not clear operation: %+v poll=%v pollTurn=%q", c.state.Conversation, c.pollCancel != nil, c.conversationPollTurnID)
	}
	select {
	case <-sess.pollCalled:
		t.Fatal("definite error unexpectedly started history polling")
	default:
	}
}
