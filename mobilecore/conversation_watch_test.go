package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
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

func (s *delayedStartErrorSession) StartTurn(ctx context.Context, _, _ string, _ *turnOptionsPayload) (string, error) {
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

func (s *delayedStartSession) StartTurn(ctx context.Context, _, _ string, _ *turnOptionsPayload) (string, error) {
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
