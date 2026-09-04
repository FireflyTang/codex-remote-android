package mobilecore

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
)

type pendingFakeSession struct {
	fakeSession
	mu sync.Mutex

	resets       chan pendingWatchReset
	watches      chan *protocolPendingWatch
	unwatchCalls int

	approvalStarted chan struct{}
	approvalRelease chan struct{}
	approvalOnce    sync.Once
	approvalCalls   int
	approvalResult  pendingResponseResult
	approvalErr     error

	userInputCalls  int
	userInputResult pendingResponseResult
	userInputErr    error
}

func newPendingFakeSession() *pendingFakeSession {
	return &pendingFakeSession{
		resets:  make(chan pendingWatchReset, 8),
		watches: make(chan *protocolPendingWatch, 8),
	}
}

func (s *pendingFakeSession) WatchPending(ctx context.Context, codexID string, _ *pendingWatchCursor) (pendingWatchReset, *protocolPendingWatch, error) {
	select {
	case <-ctx.Done():
		return pendingWatchReset{}, nil, ctx.Err()
	case reset := <-s.resets:
		watch := &protocolPendingWatch{
			client:   &protocolClient{ctx: context.Background()},
			codexID:  codexID,
			inbox:    make(chan *remotev1.Event, 64),
			overflow: make(chan struct{}),
		}
		s.watches <- watch
		return reset, watch, nil
	}
}

func (s *pendingFakeSession) UnwatchPending(context.Context, *protocolPendingWatch) error {
	s.mu.Lock()
	s.unwatchCalls++
	s.mu.Unlock()
	return nil
}

func (s *pendingFakeSession) RespondApproval(ctx context.Context, _, _, _ string) (pendingResponseResult, error) {
	s.mu.Lock()
	s.approvalCalls++
	started, release := s.approvalStarted, s.approvalRelease
	result, err := s.approvalResult, s.approvalErr
	s.mu.Unlock()
	if started != nil {
		s.approvalOnce.Do(func() { close(started) })
	}
	if release != nil {
		select {
		case <-ctx.Done():
			return pendingResponseResult{}, ctx.Err()
		case <-release:
		}
	}
	return result, err
}

func (s *pendingFakeSession) RespondUserInput(context.Context, string, string, []pendingUserInputAnswer) (pendingResponseResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.userInputCalls++
	return s.userInputResult, s.userInputErr
}

func approvalPendingRequest(id string) pendingRequest {
	return pendingRequest{
		Type: "approval", RequestID: id, TurnID: "TURN-1", ItemID: "ITEM-1",
		Approval: &pendingApproval{Kind: "command", Status: "pending", Title: "Run", Command: []string{"go", "test"}, AllowedDecisions: []string{"allow", "deny"}},
	}
}

func userInputPendingRequest(id string) pendingRequest {
	return pendingRequest{
		Type: "user_input", RequestID: id, TurnID: "TURN-2", ItemID: "ITEM-2",
		UserInput: &pendingUserInput{Questions: []pendingUserInputQuestion{{
			QuestionID: "Q1", Header: "Choice", Prompt: "Pick one",
			Options: []pendingUserInputOption{{OptionID: "O1", Label: "One"}, {OptionID: "O2", Label: "Two"}},
		}}},
	}
}

func TestPendingWatchAdvancesAllEventsAndResetPreservesInFlight(t *testing.T) {
	sess := newPendingFakeSession()
	sess.approvalStarted = make(chan struct{})
	sess.approvalRelease = make(chan struct{})
	sess.approvalResult = pendingResponseResult{Type: "approval", RequestID: "A1", TurnID: "TURN-1", ItemID: "ITEM-1"}
	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 10, Requests: []pendingRequest{approvalPendingRequest("A1")}}

	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	t.Cleanup(func() { c.stop("cleanup") })
	c.Dispatch(`{"version":1,"id":"select","type":"select_codex","payload":{"codexId":"CODEX-1"}}`)
	watch := <-sess.watches
	waitState(t, c, func(state state) bool {
		return state.Conversation != nil && state.Conversation.PendingWatch.State == "watching" && len(state.Conversation.PendingRequests) == 1
	})

	c.Dispatch(`{"version":1,"id":"respond","type":"respond_approval","payload":{"approvalId":"A1","decision":"allow"}}`)
	select {
	case <-sess.approvalStarted:
	case <-time.After(time.Second):
		t.Fatal("approval response did not start")
	}
	waitState(t, c, func(state state) bool { return state.Conversation.PendingRequests[0].InFlight })

	// A true gap fails closed and rebuilds from RESET. The rebuilt request must
	// retain the local response state instead of becoming actionable twice.
	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 12, Requests: []pendingRequest{approvalPendingRequest("A1")}}
	watch.inbox <- &remotev1.Event{CodexId: "CODEX-1", EventSeq: 12}
	rebuilt := <-sess.watches
	waitState(t, c, func(state state) bool {
		return state.Conversation.PendingWatch.State == "watching" && state.Conversation.PendingWatch.HeadEventSeq == 12 && state.Conversation.PendingRequests[0].InFlight
	})

	// A resolved update may win the race with the response RPC. Completion is
	// still checked against the captured request association.
	rebuilt.inbox <- pendingApprovalEvent("CODEX-1", 13, approvalPendingRequest("A1"), true)
	waitState(t, c, func(state state) bool { return len(state.Conversation.PendingRequests) == 0 })
	close(sess.approvalRelease)
	waitState(t, c, func(state state) bool { return len(state.Conversation.PendingRequests) == 0 })

	// A non-pending Event still advances the sequence, so the following pending
	// update at the next sequence must not be classified as a gap.
	rebuilt.inbox <- &remotev1.Event{CodexId: "CODEX-1", EventSeq: 14}
	rebuilt.inbox <- pendingApprovalEvent("CODEX-1", 15, approvalPendingRequest("A2"), false)
	waitState(t, c, func(state state) bool {
		return state.Conversation.PendingWatch.State == "watching" && state.Conversation.PendingWatch.HeadEventSeq == 15 && pendingIndex(state.Conversation.PendingRequests, "A2") >= 0
	})
}

func TestPendingResponseFailureSurvivesResetAndUserInputValidation(t *testing.T) {
	sess := newPendingFakeSession()
	sess.approvalErr = &pendingProtocolError{Code: "workspace_busy", Message: "busy"}
	sess.userInputResult = pendingResponseResult{Type: "user_input", RequestID: "U1", TurnID: "TURN-2", ItemID: "ITEM-2"}
	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 20, Requests: []pendingRequest{approvalPendingRequest("A1"), userInputPendingRequest("U1")}}

	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"
	t.Cleanup(func() { c.stop("cleanup") })
	c.Dispatch(`{"version":1,"id":"select","type":"select_codex","payload":{"codexId":"CODEX-1"}}`)
	watch := <-sess.watches
	waitState(t, c, func(state state) bool {
		return state.Conversation != nil && state.Conversation.PendingWatch.State == "watching"
	})

	c.Dispatch(`{"version":1,"id":"bad","type":"respond_user_input","payload":{"requestId":"U1","answers":[{"questionId":"Q1","selectedOptionIds":["missing"],"freeFormText":""}]}}`)
	waitState(t, c, func(state state) bool {
		index := pendingIndex(state.Conversation.PendingRequests, "U1")
		return index >= 0 && state.Conversation.PendingRequests[index].Error != nil
	})
	sess.mu.Lock()
	if sess.userInputCalls != 0 {
		t.Fatalf("invalid answer sent to Host: calls=%d", sess.userInputCalls)
	}
	sess.mu.Unlock()

	c.Dispatch(`{"version":1,"id":"fail","type":"respond_approval","payload":{"approvalId":"A1","decision":"allow"}}`)
	waitState(t, c, func(state state) bool {
		index := pendingIndex(state.Conversation.PendingRequests, "A1")
		return index >= 0 && state.Conversation.PendingRequests[index].Error != nil && state.Conversation.PendingRequests[index].Error.Code == "workspace_busy"
	})

	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 22, Requests: []pendingRequest{approvalPendingRequest("A1"), userInputPendingRequest("U1")}}
	watch.inbox <- &remotev1.Event{CodexId: "CODEX-1", EventSeq: 22}
	rebuilt := <-sess.watches
	waitState(t, c, func(state state) bool {
		index := pendingIndex(state.Conversation.PendingRequests, "A1")
		return state.Conversation.PendingWatch.State == "watching" && index >= 0 && state.Conversation.PendingRequests[index].Error != nil && state.Conversation.PendingRequests[index].Error.CommandID == "fail"
	})

	// Sequence 23 has no pending payload but must advance the head. Sequence 24
	// can then add/update a request without creating a false gap.
	rebuilt.inbox <- &remotev1.Event{CodexId: "CODEX-1", EventSeq: 23}
	rebuilt.inbox <- pendingApprovalEvent("CODEX-1", 24, approvalPendingRequest("A2"), false)
	waitState(t, c, func(state state) bool {
		return state.Conversation.PendingWatch.State == "watching" && state.Conversation.PendingWatch.HeadEventSeq == 24 && pendingIndex(state.Conversation.PendingRequests, "A2") >= 0
	})

	c.Dispatch(`{"version":1,"id":"valid","type":"respond_user_input","payload":{"requestId":"U1","answers":[{"questionId":"Q1","selectedOptionIds":["O1"],"freeFormText":""}]}}`)
	waitState(t, c, func(state state) bool { return pendingIndex(state.Conversation.PendingRequests, "U1") < 0 })
	sess.mu.Lock()
	if sess.userInputCalls != 1 {
		t.Fatalf("valid user input calls=%d", sess.userInputCalls)
	}
	sess.mu.Unlock()
}

func TestPendingWatchSwitchAndStopDiscardOldStream(t *testing.T) {
	sess := newPendingFakeSession()
	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 1, Requests: []pendingRequest{approvalPendingRequest("OLD")}}
	sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 8, Requests: []pendingRequest{approvalPendingRequest("NEW")}}
	c := NewCore(new(fakePlatform))
	c.session, c.state.Phase = sess, "ready"

	c.Dispatch(`{"version":1,"id":"one","type":"select_codex","payload":{"codexId":"CODEX-1"}}`)
	oldWatch := <-sess.watches
	waitState(t, c, func(state state) bool {
		return state.Phase == "ready" && state.Conversation != nil && state.Conversation.PendingWatch.State == "watching"
	})
	c.Dispatch(`{"version":1,"id":"two","type":"select_codex","payload":{"codexId":"CODEX-2"}}`)
	newWatch := <-sess.watches
	waitState(t, c, func(state state) bool {
		return state.SelectedCodexID == "CODEX-2" && state.Conversation != nil && state.Conversation.PendingWatch.HeadEventSeq == 8 && pendingIndex(state.Conversation.PendingRequests, "NEW") >= 0
	})

	oldWatch.inbox <- pendingApprovalEvent("CODEX-1", 2, approvalPendingRequest("STALE"), false)
	newWatch.inbox <- &remotev1.Event{CodexId: "CODEX-2", EventSeq: 9}
	waitState(t, c, func(state state) bool { return state.Conversation.PendingWatch.HeadEventSeq == 9 })
	state := decodeState(t, c.State())
	if pendingIndex(state.Conversation.PendingRequests, "STALE") >= 0 {
		t.Fatalf("old watch mutated switched conversation: %+v", state.Conversation.PendingRequests)
	}

	stopped := decodeState(t, c.stop("stop"))
	if stopped.Phase != "stopped" || stopped.Conversation != nil {
		t.Fatalf("stop state=%+v", stopped)
	}
	deadline := time.Now().Add(time.Second)
	for {
		sess.mu.Lock()
		calls := sess.unwatchCalls
		sess.mu.Unlock()
		if calls >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("unwatch calls=%d, want at least 2", calls)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestPendingWatchOldOrDuplicateSequenceIsIgnoredWithoutRebuild(t *testing.T) {
	for _, sequence := range []uint64{4, 5} {
		t.Run(fmt.Sprintf("sequence_%d", sequence), func(t *testing.T) {
			sess := newPendingFakeSession()
			sess.resets <- pendingWatchReset{ResetReason: remotev1.WatchResetReason_WATCH_RESET_REASON_INITIAL_WATCH, HeadEventSeq: 5, Requests: []pendingRequest{approvalPendingRequest("OLD")}}
			c := NewCore(new(fakePlatform))
			c.session, c.state.Phase = sess, "ready"
			t.Cleanup(func() { c.stop("cleanup") })

			c.Dispatch(`{"version":1,"id":"select","type":"select_codex","payload":{"codexId":"CODEX-1"}}`)
			watch := <-sess.watches
			waitState(t, c, func(state state) bool {
				return state.Conversation != nil && state.Conversation.PendingWatch.State == "watching" && state.Conversation.PendingWatch.HeadEventSeq == 5
			})
			watch.inbox <- &remotev1.Event{CodexId: "CODEX-1", EventSeq: sequence}
			time.Sleep(20 * time.Millisecond)
			state := decodeState(t, c.State())
			if state.Conversation.PendingWatch.HeadEventSeq != 5 || pendingIndex(state.Conversation.PendingRequests, "OLD") < 0 {
				t.Fatalf("duplicate changed watch state: %+v", state.Conversation)
			}
			select {
			case <-sess.watches:
				t.Fatal("duplicate unexpectedly rebuilt the watch")
			default:
			}
		})
	}
}

func pendingApprovalEvent(codexID string, seq uint64, request pendingRequest, resolved bool) *remotev1.Event {
	status := remotev1.ApprovalStatus_APPROVAL_STATUS_PENDING
	decision := remotev1.ApprovalDecision_APPROVAL_DECISION_UNSPECIFIED
	if resolved {
		status = remotev1.ApprovalStatus_APPROVAL_STATUS_ALLOWED
		decision = remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW
	}
	return &remotev1.Event{CodexId: codexID, EventSeq: seq, Event: &remotev1.Event_PendingRequestUpdated{PendingRequestUpdated: &remotev1.PendingRequestUpdated{Request: &remotev1.PendingRequest{Request: &remotev1.PendingRequest_Approval{Approval: &remotev1.Approval{
		ApprovalId: request.RequestID, TurnId: request.TurnID, ItemId: request.ItemID,
		Kind: request.Approval.Kind, Status: status, Title: request.Approval.Title,
		AllowedDecisions: []remotev1.ApprovalDecision{remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW}, ResolvedDecision: decision,
	}}}}}}
}

func pendingIndex(requests []pendingRequest, requestID string) int {
	for i := range requests {
		if requests[i].RequestID == requestID {
			return i
		}
	}
	return -1
}

func TestValidatePendingAnswersRules(t *testing.T) {
	request := userInputPendingRequest("U1")
	tests := []struct {
		name    string
		answers []pendingUserInputAnswer
		wantErr bool
	}{
		{name: "valid option", answers: []pendingUserInputAnswer{{QuestionID: "Q1", SelectedOptionIDs: []string{"O1"}}}},
		{name: "missing", wantErr: true},
		{name: "unknown option", answers: []pendingUserInputAnswer{{QuestionID: "Q1", SelectedOptionIDs: []string{"bad"}}}, wantErr: true},
		{name: "multiple rejected", answers: []pendingUserInputAnswer{{QuestionID: "Q1", SelectedOptionIDs: []string{"O1", "O2"}}}, wantErr: true},
		{name: "free form rejected", answers: []pendingUserInputAnswer{{QuestionID: "Q1", FreeFormText: "text"}}, wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validatePendingAnswers(request.UserInput, test.answers)
			if (err != nil) != test.wantErr {
				t.Fatalf("error=%v wantErr=%v", err, test.wantErr)
			}
		})
	}
}

func TestPendingProtocolErrorAndWatchJSONShape(t *testing.T) {
	watchJSON, err := json.Marshal(pendingWatchState{State: "error", Error: &pendingRequestError{Code: "watch_failed", Message: "down"}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(watchJSON), "commandId") {
		t.Fatalf("pendingWatch.error leaked commandId: %s", watchJSON)
	}
}
