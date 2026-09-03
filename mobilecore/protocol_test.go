package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"github.com/coder/websocket"
	"google.golang.org/protobuf/encoding/protojson"
)

type blockingWriteConnection struct {
	started chan struct{}
}

func (c *blockingWriteConnection) Read(ctx context.Context) (websocket.MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (c *blockingWriteConnection) Write(ctx context.Context, _ websocket.MessageType, _ []byte) error {
	select {
	case <-c.started:
	default:
		close(c.started)
	}
	<-ctx.Done()
	return ctx.Err()
}

func (*blockingWriteConnection) CloseNow() error { return nil }

func TestProtocolHandshakeGetHostListCodexesAndPing(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		frame := readTestFrame(t, ctx, conn)
		if frame.GetClientHello().GetProtocolVersion().GetPatch() != 2 {
			t.Errorf("bad ClientHello: %v", frame)
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: completeServerHello(2)}})
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Ping{Ping: &remotev1.Ping{Nonce: 9, SentAtUnixMs: 123}}})
		for responses := 0; responses < 2; {
			frame = readTestFrame(t, ctx, conn)
			if pong := frame.GetPong(); pong != nil {
				if pong.Nonce != 9 {
					t.Errorf("pong nonce=%d", pong.Nonce)
				}
				continue
			}
			req := frame.GetRequest()
			if req == nil {
				t.Errorf("expected request: %v", frame)
				return
			}
			var resp *remotev1.Response
			switch req.Request.(type) {
			case *remotev1.Request_GetHost:
				resp = &remotev1.Response{RequestId: req.RequestId, Result: &remotev1.Response_GetHost{GetHost: &remotev1.GetHostResponse{Host: &remotev1.HostInfo{HostId: "HOST-1"}, Capabilities: &remotev1.Capabilities{}}}}
			case *remotev1.Request_ListCodexes:
				resp = &remotev1.Response{RequestId: req.RequestId, Result: &remotev1.Response_ListCodexes{ListCodexes: &remotev1.ListCodexesResponse{}}}
			default:
				t.Errorf("unexpected request: %T", req.Request)
				return
			}
			writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: resp}})
			responses++
		}
	}))
	defer server.Close()
	addr := server.Listener.Addr().String()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := dialProtocol(ctx, configPayload{HostEndpoint: "fake-host", ClientID: "client", ClientRunID: "run", ClientName: "test", ClientVersion: "test"}, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	host, codexes, err := client.Fetch(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(host) == 0 || len(codexes) == 0 {
		t.Fatalf("empty snapshot host=%s codexes=%s", host, codexes)
	}
}

func TestValidateServerHelloRequiresExactPatch(t *testing.T) {
	if err := validateServerHello(completeServerHello(1)); err == nil {
		t.Fatal("expected version mismatch")
	}
}

func TestProtocolPendingWatchAndResponses(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: completeServerHello(2)}})

		watch := readTestFrame(t, ctx, conn).GetRequest()
		if got := watch.GetWatchCodex(); got.GetCodexId() != "CODEX-1" || got.AfterEventSeq != nil || got.GetAfterHostRunId() != "" {
			t.Errorf("WatchCodex=%+v", got)
			return
		}
		approval := pendingProtoApproval("A1", remotev1.ApprovalStatus_APPROVAL_STATUS_PENDING, remotev1.ApprovalDecision_APPROVAL_DECISION_UNSPECIFIED)
		userInput := pendingProtoUserInputMany("U1", false, 20)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: watch.RequestId, Result: &remotev1.Response_WatchCodex{WatchCodex: &remotev1.WatchCodexResponse{
			CodexId: "CODEX-1", Mode: remotev1.WatchMode_WATCH_MODE_RESET, HeadEventSeq: 5,
			ResetView: &remotev1.CurrentView{Codex: &remotev1.Codex{CodexId: "CODEX-1"}, HeadEventSeq: 5, PendingRequests: []*remotev1.PendingRequest{approval, userInput}},
		}}}}})
		// Events deliberately arrive before the caller has processed RESET.
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Event{Event: &remotev1.Event{CodexId: "CODEX-1", EventSeq: 6}}})
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Event{Event: &remotev1.Event{CodexId: "CODEX-1", EventSeq: 7, Event: &remotev1.Event_PendingRequestUpdated{PendingRequestUpdated: &remotev1.PendingRequestUpdated{Request: pendingProtoUserInputMany("U1", false, 20)}}}}})

		respondApproval := readTestFrame(t, ctx, conn).GetRequest()
		if got := respondApproval.GetRespondApproval(); got.GetCodexId() != "CODEX-1" || got.GetApprovalId() != "A1" || got.GetDecision() != remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW {
			t.Errorf("RespondApproval=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: respondApproval.RequestId, Result: &remotev1.Response_RespondApproval{RespondApproval: &remotev1.RespondApprovalResponse{Approval: pendingProtoApproval("A1", remotev1.ApprovalStatus_APPROVAL_STATUS_ALLOWED, remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW).GetApproval()}}}}})

		respondInput := readTestFrame(t, ctx, conn).GetRequest()
		if got := respondInput.GetRespondUserInput(); got.GetCodexId() != "CODEX-1" || got.GetUserInputRequestId() != "U1" || len(got.GetAnswers()) != 20 || got.GetAnswers()[19].GetQuestionId() != "Q20" || got.GetAnswers()[19].GetSelectedOptionIds()[0] != "O20" {
			t.Errorf("RespondUserInput=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: respondInput.RequestId, Result: &remotev1.Response_RespondUserInput{RespondUserInput: &remotev1.RespondUserInputResponse{Request: pendingProtoUserInputMany("U1", true, 20).GetUserInput()}}}}})

		unwatch := readTestFrame(t, ctx, conn).GetRequest()
		if got := unwatch.GetUnwatchCodex(); got.GetCodexId() != "CODEX-1" {
			t.Errorf("UnwatchCodex=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: unwatch.RequestId, Result: &remotev1.Response_UnwatchCodex{UnwatchCodex: &remotev1.UnwatchCodexResponse{CodexId: "CODEX-1"}}}}})
	}))
	defer server.Close()
	addr := server.Listener.Addr().String()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := dialProtocol(ctx, configPayload{HostEndpoint: "fake-host", ClientID: "client", ClientRunID: "run"}, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	reset, stream, err := client.WatchPending(ctx, "CODEX-1")
	if err != nil || reset.HeadEventSeq != 5 || len(reset.Requests) != 2 || reset.Requests[0].Approval == nil || reset.Requests[1].UserInput == nil || len(reset.Requests[1].UserInput.Questions) != 20 {
		t.Fatalf("WatchPending reset=%+v err=%v", reset, err)
	}
	first, err := stream.Next(ctx)
	if err != nil || first.EventSeq != 6 || first.HasUpdate {
		t.Fatalf("first event=%+v err=%v", first, err)
	}
	second, err := stream.Next(ctx)
	if err != nil || second.EventSeq != 7 || !second.HasUpdate || !second.Actionable || second.RequestID != "U1" || second.Request.UserInput == nil || len(second.Request.UserInput.Questions) != 20 {
		t.Fatalf("second event=%+v err=%v", second, err)
	}
	approvalResult, err := client.RespondApproval(ctx, "CODEX-1", "A1", "allow")
	if err != nil || approvalResult != (pendingResponseResult{Type: "approval", RequestID: "A1", TurnID: "TURN-1", ItemID: "ITEM-1"}) {
		t.Fatalf("RespondApproval=%+v err=%v", approvalResult, err)
	}
	answers := make([]pendingUserInputAnswer, 0, 20)
	for i := 1; i <= 20; i++ {
		answers = append(answers, pendingUserInputAnswer{QuestionID: fmt.Sprintf("Q%d", i), SelectedOptionIDs: []string{fmt.Sprintf("O%d", i)}})
	}
	inputResult, err := client.RespondUserInput(ctx, "CODEX-1", "U1", answers)
	if err != nil || inputResult != (pendingResponseResult{Type: "user_input", RequestID: "U1", TurnID: "TURN-2", ItemID: "ITEM-2"}) {
		t.Fatalf("RespondUserInput=%+v err=%v", inputResult, err)
	}
	if err := client.UnwatchPending(ctx, stream); err != nil {
		t.Fatal(err)
	}
}

func pendingProtoApproval(id string, status remotev1.ApprovalStatus, decision remotev1.ApprovalDecision) *remotev1.PendingRequest {
	return &remotev1.PendingRequest{Request: &remotev1.PendingRequest_Approval{Approval: &remotev1.Approval{
		ApprovalId: id, TurnId: "TURN-1", ItemId: "ITEM-1", Kind: "command", Status: status, Title: "Run", Explanation: "needed",
		Command: []string{"go", "test"}, AllowedDecisions: []remotev1.ApprovalDecision{remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW, remotev1.ApprovalDecision_APPROVAL_DECISION_DENY}, ResolvedDecision: decision,
	}}}
}

func pendingProtoUserInput(id string, resolved bool) *remotev1.PendingRequest {
	return pendingProtoUserInputMany(id, resolved, 1)
}

func pendingProtoUserInputMany(id string, resolved bool, count int) *remotev1.PendingRequest {
	questions := make([]*remotev1.UserInputQuestion, 0, count)
	for i := 1; i <= count; i++ {
		questionID, optionID := fmt.Sprintf("Q%d", i), fmt.Sprintf("O%d", i)
		questions = append(questions, &remotev1.UserInputQuestion{QuestionId: questionID, Header: "Choice", Prompt: "Pick", Options: []*remotev1.UserInputOption{{OptionId: optionID, Label: "One", Description: "first"}}})
	}
	return &remotev1.PendingRequest{Request: &remotev1.PendingRequest_UserInput{UserInput: &remotev1.UserInputRequestState{
		UserInputRequestId: id, TurnId: "TURN-2", ItemId: "ITEM-2", Resolved: resolved,
		Questions:    questions,
		Completeness: &remotev1.Completeness{Truncated: true, OriginalSizeBytes: 99, Reason: "limit"},
	}}}
}

func TestProtocolConversationHistoryPaginationStartAndInterrupt(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: completeServerHello(2)}})

		first := readTestFrame(t, ctx, conn).GetRequest()
		if got := first.GetListHistory(); got.GetCodexId() != "CODEX-1" || got.GetPage().GetPageToken() != "" {
			t.Errorf("first ListHistory=%+v", got)
			return
		}
		writeConversationResponse(t, ctx, conn, first.RequestId, &remotev1.HistoryPage{
			CodexId: "CODEX-1", Page: &remotev1.PageInfo{NextPageToken: "p2"},
			Turns: []*remotev1.TurnSnapshot{{TurnId: "TURN-OLD", Status: remotev1.TurnStatus_TURN_STATUS_COMPLETED, Items: []*remotev1.Item{
				{ItemId: "U1", Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Content: &remotev1.Item_UserMessage{UserMessage: &remotev1.UserMessageItem{Input: []*remotev1.UserInputPart{{Content: &remotev1.UserInputPart_Text{Text: &remotev1.TextInput{Text: "hello"}}}}}}},
			}}},
		})

		second := readTestFrame(t, ctx, conn).GetRequest()
		if got := second.GetListHistory(); got.GetPage().GetPageToken() != "p2" {
			t.Errorf("second page token=%q", got.GetPage().GetPageToken())
			return
		}
		writeConversationResponse(t, ctx, conn, second.RequestId, &remotev1.HistoryPage{
			CodexId: "CODEX-1", HistoryComplete: true,
			Turns: []*remotev1.TurnSnapshot{{TurnId: "TURN-1", Status: remotev1.TurnStatus_TURN_STATUS_RUNNING, Items: []*remotev1.Item{
				{ItemId: "A1", Status: remotev1.ItemStatus_ITEM_STATUS_RUNNING, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{Text: "working"}}},
				{ItemId: "P1", Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED, Content: &remotev1.Item_Plan{Plan: &remotev1.PlanItem{}}},
			}}},
		})

		start := readTestFrame(t, ctx, conn).GetRequest()
		startRequest := start.GetStartTurn()
		if startRequest.GetCodexId() != "CODEX-1" || startRequest.GetInput()[0].GetText().GetText() != "next" || startRequest.GetOptions().GetMode() != "plan" {
			t.Errorf("StartTurn=%+v", startRequest)
			return
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: start.RequestId, Result: &remotev1.Response_StartTurn{StartTurn: &remotev1.StartTurnResponse{TurnId: "TURN-2"}}}}})

		interrupt := readTestFrame(t, ctx, conn).GetRequest()
		interruptRequest := interrupt.GetInterruptTurn()
		if interruptRequest.GetCodexId() != "CODEX-1" || interruptRequest.GetTurnId() != "TURN-2" {
			t.Errorf("InterruptTurn=%+v", interruptRequest)
			return
		}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: interrupt.RequestId, Result: &remotev1.Response_InterruptTurn{InterruptTurn: &remotev1.InterruptTurnResponse{TurnId: "TURN-2"}}}}})
	}))
	defer server.Close()
	addr := server.Listener.Addr().String()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := dialProtocol(ctx, configPayload{HostEndpoint: "fake-host", ClientID: "client", ClientRunID: "run", ClientName: "test", ClientVersion: "test"}, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	history, err := client.ListHistory(ctx, "CODEX-1")
	if err != nil {
		t.Fatal(err)
	}
	if !history.HistoryComplete || history.ActiveTurnID != "TURN-1" || !history.Running || len(history.Turns) != 2 || len(history.Turns[1].Messages) != 1 || history.Turns[1].Messages[0].Role != "assistant" {
		t.Fatalf("history=%+v", history)
	}
	turnID, err := client.StartTurn(ctx, "CODEX-1", "next", &turnOptionsPayload{Mode: "plan"})
	if err != nil || turnID != "TURN-2" {
		t.Fatalf("StartTurn=(%q, %v)", turnID, err)
	}
	interrupted, err := client.InterruptTurn(ctx, "CODEX-1", turnID)
	if err != nil || interrupted != "TURN-2" {
		t.Fatalf("InterruptTurn=(%q, %v)", interrupted, err)
	}
}

func TestProtocolSessionManagementMappingsAndPagination(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: completeServerHello(2)}})
		for i := 0; i < 10; i++ {
			req := readTestFrame(t, ctx, conn).GetRequest()
			response := &remotev1.Response{RequestId: req.RequestId}
			switch i {
			case 0:
				if got := req.GetListDirectories(); got.GetParentPath() != "/work" || got.GetPage().GetPageToken() != "" {
					t.Errorf("directories page one=%+v", got)
				}
				response.Result = &remotev1.Response_ListDirectories{ListDirectories: &remotev1.ListDirectoriesResponse{ParentPath: "/work", Directories: []*remotev1.DirectoryEntry{{Name: "one", Path: "/work/one"}}, Page: &remotev1.PageInfo{NextPageToken: "d2"}}}
			case 1:
				if got := req.GetListDirectories(); got.GetPage().GetPageToken() != "d2" {
					t.Errorf("directories page two=%+v", got)
				}
				response.Result = &remotev1.Response_ListDirectories{ListDirectories: &remotev1.ListDirectoriesResponse{ParentPath: "/work", Directories: []*remotev1.DirectoryEntry{{Name: "two", Path: "/work/two"}}}}
			case 2:
				if got := req.GetListSessionCandidates(); got.GetCwd() != "/work" || got.GetPage().GetPageToken() != "" {
					t.Errorf("candidates page one=%+v", got)
				}
				response.Result = &remotev1.Response_ListSessionCandidates{ListSessionCandidates: &remotev1.ListSessionCandidatesResponse{NormalizedCwd: "/work", Sessions: []*remotev1.SessionCandidate{{
					SessionId: "S-1", Cwd: "/work", Title: "old", Preview: "p", Source: "rollout",
					CreatedAtUnixMs: 11, UpdatedAtUnixMs: 22,
					Availability: remotev1.SessionAvailability_SESSION_AVAILABILITY_RESUMABLE, ManagedCodexId: "C-1",
					Warnings:     []*remotev1.Warning{{Code: remotev1.WarningCode_WARNING_CODE_HISTORY_IMPORT_INCOMPLETE, Message: "partial", Metadata: map[string]string{"ignored": "large-or-private"}}},
					Completeness: &remotev1.Completeness{Truncated: true, Incomplete: true, OriginalSizeBytes: 123, Reason: "bounded"},
				}}, Page: &remotev1.PageInfo{NextPageToken: "s2"}}}
			case 3:
				if got := req.GetListSessionCandidates(); got.GetPage().GetPageToken() != "s2" {
					t.Errorf("candidates page two=%+v", got)
				}
				response.Result = &remotev1.Response_ListSessionCandidates{ListSessionCandidates: &remotev1.ListSessionCandidatesResponse{NormalizedCwd: "/work", Sessions: []*remotev1.SessionCandidate{{SessionId: "S-2", Cwd: "/work", Source: "rollout"}}}}
			case 4:
				if got := req.GetCreateCodex(); got.GetCwd() != "/new" || !got.GetCreateDirectoryIfMissing() || got.GetTitle() != "New" {
					t.Errorf("create=%+v", got)
				}
				response.Result = &remotev1.Response_CreateCodex{CreateCodex: &remotev1.CreateCodexResponse{Codex: &remotev1.Codex{CodexId: "C-NEW"}}}
			case 5:
				if got := req.GetImportSession(); got.GetSessionId() != "S-1" || got.GetSource() != "rollout" {
					t.Errorf("import=%+v", got)
				}
				response.Result = &remotev1.Response_ImportSession{ImportSession: &remotev1.ImportSessionResponse{Codex: &remotev1.Codex{CodexId: "C-IMPORTED"}}}
			case 6:
				if got := req.GetRenameCodex(); got.GetCodexId() != "C-1" || got.GetTitle() != "Renamed" {
					t.Errorf("rename=%+v", got)
				}
				response.Result = &remotev1.Response_RenameCodex{RenameCodex: &remotev1.RenameCodexResponse{Codex: &remotev1.Codex{CodexId: "C-1"}}}
			case 7:
				if got := req.GetUnmanageCodex(); got.GetCodexId() != "C-1" {
					t.Errorf("unmanage=%+v", got)
				}
				response.Result = &remotev1.Response_UnmanageCodex{UnmanageCodex: &remotev1.UnmanageCodexResponse{Codex: &remotev1.Codex{CodexId: "C-1"}}}
			case 8:
				if got := req.GetForgetCodex(); got.GetCodexId() != "C-1" {
					t.Errorf("forget=%+v", got)
				}
				response.Result = &remotev1.Response_ForgetCodex{ForgetCodex: &remotev1.ForgetCodexResponse{CodexId: "C-1"}}
			case 9:
				if got := req.GetListCodexes(); got.GetPage().GetPageToken() != "" {
					t.Errorf("codexes=%+v", got)
				}
				response.Result = &remotev1.Response_ListCodexes{ListCodexes: &remotev1.ListCodexesResponse{Codexes: []*remotev1.Codex{{CodexId: "C-1", ManagementState: remotev1.ManagementState_MANAGEMENT_STATE_MANAGED}}}}
			}
			writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: response}})
		}
	}))
	defer server.Close()
	client := testProtocolClient(t, server)
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	directories, err := client.ListDirectories(ctx, "/work")
	if err != nil || len(directories.Directories) != 2 {
		t.Fatalf("directories=%+v err=%v", directories, err)
	}
	candidates, err := client.ListSessionCandidates(ctx, "/work")
	if err != nil || len(candidates.Sessions) != 2 || candidates.Sessions[0].ManagedCodexID != "C-1" {
		t.Fatalf("candidates=%+v err=%v", candidates, err)
	}
	firstCandidate := candidates.Sessions[0]
	if firstCandidate.CreatedAtUnixMS != 11 || firstCandidate.UpdatedAtUnixMS != 22 || len(firstCandidate.Warnings) != 1 || firstCandidate.Warnings[0].Code != "WARNING_CODE_HISTORY_IMPORT_INCOMPLETE" || firstCandidate.Completeness == nil || !firstCandidate.Completeness.Truncated || !firstCandidate.Completeness.Incomplete {
		t.Fatalf("candidate honesty projection=%+v", firstCandidate)
	}
	rawCandidate, err := json.Marshal(firstCandidate)
	if err != nil {
		t.Fatal(err)
	}
	if text := string(rawCandidate); !strings.Contains(text, `"originalSizeBytes":123`) || strings.Contains(text, "large-or-private") || strings.Contains(text, "metadata") {
		t.Fatalf("candidate honesty JSON=%s", text)
	}
	if id, err := client.CreateCodex(ctx, createCodexPayload{Cwd: "/new", CreateDirectoryIfMissing: true, Title: "New"}); err != nil || id != "C-NEW" {
		t.Fatalf("create=(%q,%v)", id, err)
	}
	if id, err := client.ImportSession(ctx, importSessionPayload{SessionID: "S-1", Source: "rollout"}); err != nil || id != "C-IMPORTED" {
		t.Fatalf("import=(%q,%v)", id, err)
	}
	if err := client.RenameCodex(ctx, renameCodexPayload{CodexID: "C-1", Title: "Renamed"}); err != nil {
		t.Fatal(err)
	}
	if err := client.UnmanageCodex(ctx, "C-1"); err != nil {
		t.Fatal(err)
	}
	if err := client.ForgetCodex(ctx, "C-1"); err != nil {
		t.Fatal(err)
	}
	codexes, err := client.ListCodexes(ctx)
	if err != nil || len(codexes.Codexes) != 1 || codexes.Codexes[0].ManagementState != remotev1.ManagementState_MANAGEMENT_STATE_MANAGED {
		t.Fatalf("codexes=%+v err=%v", codexes, err)
	}
}

func TestProtocolSessionManagementMismatchAndHostError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: completeServerHello(2)}})
		first := readTestFrame(t, ctx, conn).GetRequest()
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: first.RequestId, Result: &remotev1.Response_ListCodexes{ListCodexes: &remotev1.ListCodexesResponse{}}}}})
		second := readTestFrame(t, ctx, conn).GetRequest()
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: second.RequestId, Result: &remotev1.Response_Error{Error: &remotev1.Error{Code: remotev1.ErrorCode_ERROR_CODE_INVALID_REQUEST, Message: "bad cwd"}}}}})
	}))
	defer server.Close()
	client := testProtocolClient(t, server)
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := client.ListDirectories(ctx, "/work"); err == nil || !strings.Contains(err.Error(), "mismatched response") {
		t.Fatalf("mismatch error=%v", err)
	}
	if _, err := client.ListSessionCandidates(ctx, "/work"); err == nil || !strings.Contains(err.Error(), "Host error") {
		t.Fatalf("Host error=%v", err)
	}
}

func TestProtocolWorkspaceTextFlowAndFullPagination(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: workspaceServerHello()}})

		get := readTestFrame(t, ctx, conn).GetRequest()
		if got := get.GetGetWorkspace(); got.GetCodexId() != "CODEX-1" {
			t.Errorf("GetWorkspace=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(get.RequestId, &remotev1.Response_GetWorkspace{GetWorkspace: &remotev1.GetWorkspaceResponse{
			CodexId: "CODEX-1", WorkspaceRoot: "/work", AccessState: &remotev1.WorkspaceAccessState{
				MutationStatus: remotev1.WorkspaceMutationStatus_WORKSPACE_MUTATION_STATUS_ALLOWED, QuiescenceToken: "Q1", Generation: 7,
			},
		}}))

		first := readTestFrame(t, ctx, conn).GetRequest()
		if got := first.GetListWorkspaceEntries(); got.GetCodexId() != "CODEX-1" || got.GetRelativeDirectory() != "" || got.GetPage().GetPageToken() != "" || got.GetPage().GetPageSize() != 2 {
			t.Errorf("ListWorkspaceEntries page1=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(first.RequestId, &remotev1.Response_ListWorkspaceEntries{ListWorkspaceEntries: &remotev1.ListWorkspaceEntriesResponse{
			CodexId: "CODEX-1", Entries: []*remotev1.WorkspaceEntry{
				{RelativePath: "a.txt", Name: "a.txt", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, Revision: "R1", TextViewable: true, TextEditable: true},
				{RelativePath: "dir", Name: "dir", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY},
			}, Page: &remotev1.PageInfo{NextPageToken: "p2"},
		}}))
		second := readTestFrame(t, ctx, conn).GetRequest()
		if got := second.GetListWorkspaceEntries(); got.GetCodexId() != "CODEX-1" || got.GetRelativeDirectory() != "" || got.GetPage().GetPageToken() != "p2" {
			t.Errorf("ListWorkspaceEntries page2=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(second.RequestId, &remotev1.Response_ListWorkspaceEntries{ListWorkspaceEntries: &remotev1.ListWorkspaceEntriesResponse{
			CodexId: "CODEX-1", Entries: []*remotev1.WorkspaceEntry{
				{RelativePath: "link", Name: "link", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_SYMBOLIC_LINK},
				{RelativePath: "other", Name: "other", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_OTHER},
				{RelativePath: "unknown", Name: "unknown"},
			},
		}}))

		read := readTestFrame(t, ctx, conn).GetRequest()
		if got := read.GetReadWorkspaceTextFile(); got.GetCodexId() != "CODEX-1" || got.GetRelativePath() != "a.txt" {
			t.Errorf("ReadWorkspaceTextFile=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(read.RequestId, &remotev1.Response_ReadWorkspaceTextFile{ReadWorkspaceTextFile: &remotev1.ReadWorkspaceTextFileResponse{
			Entry: &remotev1.WorkspaceEntry{RelativePath: "a.txt", Name: "a.txt", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, Revision: "R1", TextViewable: true, TextEditable: true}, Utf8Text: "old",
		}}))

		write := readTestFrame(t, ctx, conn).GetRequest()
		if got := write.GetWriteWorkspaceTextFile(); got.GetCodexId() != "CODEX-1" || got.GetRelativePath() != "a.txt" || got.GetUtf8Text() != "new" || got.GetExpectedRevision() != "R1" || got.GetExpectedQuiescenceToken() != "Q1" || got.GetCondition() != remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_REPLACE_ONLY {
			t.Errorf("WriteWorkspaceTextFile=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(write.RequestId, &remotev1.Response_WriteWorkspaceTextFile{WriteWorkspaceTextFile: &remotev1.WriteWorkspaceTextFileResponse{
			Entry: &remotev1.WorkspaceEntry{RelativePath: "a.txt", Name: "a.txt", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, Revision: "R2", TextViewable: true, TextEditable: true}, Deduplicated: true,
		}}))

		upload := readTestFrame(t, ctx, conn).GetRequest()
		if got := upload.GetUploadWorkspaceEntry(); got.GetCodexId() != "CODEX-1" || got.GetDestinationPath() != "bin/data.bin" || got.GetKind() != remotev1.WorkspaceUploadKind_WORKSPACE_UPLOAD_KIND_REGULAR_FILE || string(got.GetContent()) != "abc" || got.GetExpectedQuiescenceToken() != "Q2" {
			t.Errorf("UploadWorkspaceEntry=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(upload.RequestId, &remotev1.Response_UploadWorkspaceEntry{UploadWorkspaceEntry: &remotev1.UploadWorkspaceEntryResponse{
			Entry: &remotev1.WorkspaceEntry{RelativePath: "bin/data.bin", Name: "data.bin", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, Revision: "RU"}, Deduplicated: true,
		}}))

		download := readTestFrame(t, ctx, conn).GetRequest()
		if got := download.GetDownloadWorkspaceEntry(); got.GetCodexId() != "CODEX-1" || got.GetRelativePath() != "dir" {
			t.Errorf("DownloadWorkspaceEntry=%+v", got)
			return
		}
		writeTestFrame(t, ctx, conn, workspaceResponse(download.RequestId, &remotev1.Response_DownloadWorkspaceEntry{DownloadWorkspaceEntry: &remotev1.DownloadWorkspaceEntryResponse{
			Entry: &remotev1.WorkspaceEntry{RelativePath: "dir", Name: "dir", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY}, Kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY, Filename: "dir.zip", Content: []byte("zip"),
		}}))
	}))
	defer server.Close()
	client := testProtocolClient(t, server)
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	limits, supported, err := client.WorkspaceSupport()
	if err != nil || !supported || limits.MaxTextFileBytes != 1024 || limits.MaxArchiveEntryCount != 5 {
		t.Fatalf("WorkspaceSupport=(%+v,%t,%v)", limits, supported, err)
	}
	descriptor, err := client.GetWorkspace(ctx, "CODEX-1")
	if err != nil || descriptor.WorkspaceRoot != "/work" || descriptor.AccessState.MutationStatus != "allowed" || descriptor.AccessState.Generation != 7 {
		t.Fatalf("GetWorkspace=(%+v,%v)", descriptor, err)
	}
	directory, err := client.ListWorkspaceEntries(ctx, "CODEX-1", "")
	if err != nil || len(directory.Entries) != 5 {
		t.Fatalf("ListWorkspaceEntries=(%+v,%v)", directory, err)
	}
	if got := []string{directory.Entries[0].Kind, directory.Entries[1].Kind, directory.Entries[2].Kind, directory.Entries[3].Kind, directory.Entries[4].Kind}; !reflect.DeepEqual(got, []string{"regular_file", "directory", "symbolic_link", "other", "unspecified"}) {
		t.Fatalf("entry kinds=%v", got)
	}
	openFile, err := client.ReadWorkspaceTextFile(ctx, "CODEX-1", "a.txt")
	if err != nil || openFile.UTF8Text != "old" || openFile.Entry.Revision != "R1" {
		t.Fatalf("ReadWorkspaceTextFile=(%+v,%v)", openFile, err)
	}
	writeResult, err := client.WriteWorkspaceTextFile(ctx, writeWorkspaceTextFilePayload{CodexID: "CODEX-1", RelativePath: "a.txt", UTF8Text: "new", Condition: "replace_only", ExpectedRevision: "R1", ExpectedQuiescenceToken: "Q1"})
	if err != nil || !writeResult.Deduplicated || writeResult.Entry.Revision != "R2" {
		t.Fatalf("WriteWorkspaceTextFile=(%+v,%v)", writeResult, err)
	}
	uploadResult, err := client.UploadWorkspaceEntry(ctx, workspaceUploadRequest{CodexID: "CODEX-1", DestinationPath: "bin/data.bin", Kind: "regular_file", Content: []byte("abc"), ExpectedQuiescenceToken: "Q2"})
	if err != nil || !uploadResult.Deduplicated || uploadResult.Entry.Revision != "RU" {
		t.Fatalf("UploadWorkspaceEntry=(%+v,%v)", uploadResult, err)
	}
	downloadResult, err := client.DownloadWorkspaceEntry(ctx, "CODEX-1", "dir")
	if err != nil || downloadResult.Kind != "zip_directory" || downloadResult.Filename != "dir.zip" || downloadResult.ContentBase64 != "emlw" {
		t.Fatalf("DownloadWorkspaceEntry=(%+v,%v)", downloadResult, err)
	}
}

func TestProtocolWorkspaceRejectsMismatchesLoopAndHostError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: workspaceServerHello()}})

		get := readTestFrame(t, ctx, conn).GetRequest()
		writeTestFrame(t, ctx, conn, workspaceResponse(get.RequestId, &remotev1.Response_GetWorkspace{GetWorkspace: &remotev1.GetWorkspaceResponse{CodexId: "WRONG", AccessState: &remotev1.WorkspaceAccessState{}}}))
		for i := 0; i < 2; i++ {
			list := readTestFrame(t, ctx, conn).GetRequest()
			writeTestFrame(t, ctx, conn, workspaceResponse(list.RequestId, &remotev1.Response_ListWorkspaceEntries{ListWorkspaceEntries: &remotev1.ListWorkspaceEntriesResponse{CodexId: "CODEX-1", RelativeDirectory: "dir", Page: &remotev1.PageInfo{NextPageToken: "same"}}}))
		}
		read := readTestFrame(t, ctx, conn).GetRequest()
		writeTestFrame(t, ctx, conn, workspaceResponse(read.RequestId, &remotev1.Response_ReadWorkspaceTextFile{ReadWorkspaceTextFile: &remotev1.ReadWorkspaceTextFileResponse{Entry: &remotev1.WorkspaceEntry{RelativePath: "wrong", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, TextViewable: true}}}))
		write := readTestFrame(t, ctx, conn).GetRequest()
		writeTestFrame(t, ctx, conn, workspaceResponse(write.RequestId, &remotev1.Response_Error{Error: &remotev1.Error{Code: remotev1.ErrorCode_ERROR_CODE_WORKSPACE_BUSY, Message: "agent active"}}))
	}))
	defer server.Close()
	client := testProtocolClient(t, server)
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := client.GetWorkspace(ctx, "CODEX-1"); err == nil || !strings.Contains(err.Error(), "mismatched") {
		t.Fatalf("GetWorkspace mismatch=%v", err)
	}
	if _, err := client.ListWorkspaceEntries(ctx, "CODEX-1", "dir"); err == nil || !strings.Contains(err.Error(), "repeated page token") {
		t.Fatalf("ListWorkspaceEntries loop=%v", err)
	}
	if _, err := client.ReadWorkspaceTextFile(ctx, "CODEX-1", "a.txt"); err == nil || !strings.Contains(err.Error(), "mismatched") {
		t.Fatalf("ReadWorkspaceTextFile mismatch=%v", err)
	}
	_, err := client.WriteWorkspaceTextFile(ctx, writeWorkspaceTextFilePayload{CodexID: "CODEX-1", RelativePath: "a.txt", UTF8Text: "new", Condition: "upsert", ExpectedQuiescenceToken: "Q1"})
	var operationError *workspaceOperationError
	if !errors.As(err, &operationError) || operationError.Code != "workspace_busy" {
		t.Fatalf("WriteWorkspaceTextFile Host error=%T %v", err, err)
	}
}

func TestProtocolWorkspaceCapabilityAndWriteLimitGates(t *testing.T) {
	client := &protocolClient{hello: completeServerHello(2)}
	if _, supported, err := client.WorkspaceSupport(); err != nil || supported {
		t.Fatalf("absent workspace=(%t,%v)", supported, err)
	}
	client.hello = workspaceServerHello()
	client.hello.Capabilities.Workspace.MaxTextFileBytes = 0
	if _, supported, err := client.WorkspaceSupport(); err == nil || supported {
		t.Fatalf("zero limit=(%t,%v)", supported, err)
	}
	client.hello = workspaceServerHello()
	client.hello.Capabilities.Workspace.MaxTextFileBytes = 2
	_, err := client.WriteWorkspaceTextFile(context.Background(), writeWorkspaceTextFilePayload{CodexID: "C", RelativePath: "a", UTF8Text: "three", Condition: "upsert", ExpectedQuiescenceToken: "Q"})
	var operationError *workspaceOperationError
	if !errors.As(err, &operationError) || operationError.Code != "workspace_text_too_large" {
		t.Fatalf("oversize error=%T %v", err, err)
	}
	client.hello.Capabilities.Workspace.MaxInlineUploadBytes = 2
	_, err = client.UploadWorkspaceEntry(context.Background(), workspaceUploadRequest{CodexID: "C", DestinationPath: "a", Kind: "regular_file", Content: []byte("three"), ExpectedQuiescenceToken: "Q"})
	if !errors.As(err, &operationError) || operationError.Code != "workspace_upload_too_large" {
		t.Fatalf("upload oversize error=%T %v", err, err)
	}
	if _, err = client.DownloadWorkspaceEntry(context.Background(), "C", "../outside"); !errors.As(err, &operationError) || operationError.Code != "invalid_request" {
		t.Fatalf("download path error=%T %v", err, err)
	}
	err = workspaceHostError("UploadWorkspaceEntry", &remotev1.Error{Code: remotev1.ErrorCode_ERROR_CODE_WORKSPACE_ARCHIVE_EXPANDED_TOO_LARGE, Message: "expanded"})
	if !errors.As(err, &operationError) || operationError.Code != "workspace_archive_expanded_too_large" {
		t.Fatalf("archive Host error=%T %v", err, err)
	}
}

func TestWorkspaceDownloadMetadataAssociation(t *testing.T) {
	tests := []struct {
		name         string
		relativePath string
		entry        *remotev1.WorkspaceEntry
		kind         remotev1.WorkspaceDownloadKind
		filename     string
		wantErr      bool
	}{
		{name: "regular", relativePath: "dir/a.txt", entry: &remotev1.WorkspaceEntry{RelativePath: "dir/a.txt", Name: "a.txt", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_REGULAR_FILE, filename: "a.txt"},
		{name: "regular wrong name", relativePath: "dir/a.txt", entry: &remotev1.WorkspaceEntry{RelativePath: "dir/a.txt", Name: "other.txt", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_REGULAR_FILE, filename: "other.txt", wantErr: true},
		{name: "zip", relativePath: "dir", entry: &remotev1.WorkspaceEntry{RelativePath: "dir", Name: "dir", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY, filename: "dir.zip"},
		{name: "zip wrong filename", relativePath: "dir", entry: &remotev1.WorkspaceEntry{RelativePath: "dir", Name: "dir", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY, filename: "other.zip", wantErr: true},
		{name: "root", relativePath: "", entry: &remotev1.WorkspaceEntry{RelativePath: "", Name: ".", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY, filename: "work.zip"},
		{name: "root missing suffix", relativePath: "", entry: &remotev1.WorkspaceEntry{RelativePath: "", Name: ".", Kind: remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY}, kind: remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY, filename: "work", wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := validateWorkspaceDownloadAssociation(test.relativePath, test.entry, test.kind, test.filename)
			if (err != nil) != test.wantErr {
				t.Fatalf("error=%v wantErr=%v", err, test.wantErr)
			}
		})
	}
}

func workspaceServerHello() *remotev1.ServerHello {
	hello := completeServerHello(2)
	hello.Capabilities.MaxPageSize = 2
	hello.Capabilities.Workspace = &remotev1.WorkspaceCapabilities{
		MaxTextFileBytes: 1024, MaxInlineUploadBytes: 2048, MaxInlineDownloadBytes: 4096,
		MaxArchiveExpandedBytes: 8192, MaxArchiveEntryCount: 5,
	}
	return hello
}

func workspaceResponse(requestID string, result any) *remotev1.Frame {
	response := &remotev1.Response{RequestId: requestID}
	switch result := result.(type) {
	case *remotev1.Response_GetWorkspace:
		response.Result = result
	case *remotev1.Response_ListWorkspaceEntries:
		response.Result = result
	case *remotev1.Response_ReadWorkspaceTextFile:
		response.Result = result
	case *remotev1.Response_WriteWorkspaceTextFile:
		response.Result = result
	case *remotev1.Response_UploadWorkspaceEntry:
		response.Result = result
	case *remotev1.Response_DownloadWorkspaceEntry:
		response.Result = result
	case *remotev1.Response_Error:
		response.Result = result
	default:
		panic("unsupported workspace response")
	}
	return &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: response}}
}

func testProtocolClient(t *testing.T, server *httptest.Server) *protocolClient {
	t.Helper()
	addr := server.Listener.Addr().String()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := dialProtocol(ctx, configPayload{HostEndpoint: "fake-host", ClientID: "client", ClientRunID: "run", ClientName: "test", ClientVersion: "test"}, dial)
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func TestConversationItemProjectionAndMessageCompatibility(t *testing.T) {
	turn := &remotev1.TurnSnapshot{
		TurnId:       "TURN-1",
		Completeness: &remotev1.Completeness{Incomplete: true, OriginalSizeBytes: 800, Reason: "history gap"},
		Provenance:   remotev1.ProvenanceKind_PROVENANCE_KIND_IMPORTED_HISTORY,
		Items: []*remotev1.Item{
			{
				ItemId: "U1", TurnId: "TURN-1", Status: remotev1.ItemStatus_ITEM_STATUS_COMPLETED,
				Completeness: &remotev1.Completeness{Truncated: true, Incomplete: true, OriginalSizeBytes: 42, Reason: "bounded"},
				Provenance:   remotev1.ProvenanceKind_PROVENANCE_KIND_HOST_SYNTHESIZED,
				Content: &remotev1.Item_UserMessage{UserMessage: &remotev1.UserMessageItem{Input: []*remotev1.UserInputPart{
					{Content: &remotev1.UserInputPart_Text{Text: &remotev1.TextInput{Text: "one"}}},
					nil,
					{Content: &remotev1.UserInputPart_Text{Text: &remotev1.TextInput{Text: "two"}}},
				}}},
			},
			{ItemId: "R1", Content: &remotev1.Item_ReasoningSummary{ReasoningSummary: &remotev1.ReasoningSummaryItem{Text: "why"}}},
			{ItemId: "A1", Status: remotev1.ItemStatus_ITEM_STATUS_RUNNING, Content: &remotev1.Item_AgentMessage{AgentMessage: &remotev1.AgentMessageItem{Text: "answer"}}},
			{ItemId: "P1", Content: &remotev1.Item_Plan{Plan: &remotev1.PlanItem{Steps: []*remotev1.PlanStep{{Text: "first", Status: "completed"}, nil, {Text: "next", Status: "in_progress"}}}}},
			{ItemId: "C1", Content: &remotev1.Item_Command{Command: &remotev1.CommandItem{Argv: []string{"sh", "-c", "true"}, Cwd: "/work", Output: "ok", HasExitCode: true, ExitCode: 0}}},
			{ItemId: "C2", Content: &remotev1.Item_Command{Command: &remotev1.CommandItem{Argv: nil, ExitCode: 9, HasExitCode: false}}},
			{ItemId: "T1", Content: &remotev1.Item_Tool{Tool: &remotev1.ToolItem{ToolName: "search", Summary: "looking", ResultSummary: "found"}}},
			{ItemId: "F1", Content: &remotev1.Item_FileChange{FileChange: &remotev1.FileChangeItem{
				Changes: []*remotev1.FileChange{
					{Path: "a", Kind: remotev1.FileChangeKind_FILE_CHANGE_KIND_ADDED},
					{Path: "b", Kind: remotev1.FileChangeKind_FILE_CHANGE_KIND_MODIFIED},
					{Path: "c", Kind: remotev1.FileChangeKind_FILE_CHANGE_KIND_DELETED},
					{Path: "d", Kind: remotev1.FileChangeKind_FILE_CHANGE_KIND_RENAMED, OldPath: "old", NewPath: "new"},
					{Path: "e", Kind: remotev1.FileChangeKind_FILE_CHANGE_KIND_UNSPECIFIED},
					nil,
				},
				UnifiedDiff: "@@ diff",
			}}},
			nil,
			{ItemId: "NIL", Content: &remotev1.Item_AgentMessage{}},
		}}

	got := conversationTurnFromProto(turn)
	if len(got.Items) != 10 {
		t.Fatalf("items=%d, want 10", len(got.Items))
	}
	if got.Completeness == nil || !got.Completeness.Incomplete || got.Completeness.OriginalSizeBytes != 800 || got.Provenance != "PROVENANCE_KIND_IMPORTED_HISTORY" {
		t.Errorf("turn honesty projection=%+v", got)
	}
	wantTypes := []string{"user_message", "reasoning_summary", "agent_message", "plan", "command", "command", "tool", "file_change", "unknown", "unknown"}
	for i, want := range wantTypes {
		if got.Items[i].Type != want {
			t.Errorf("item[%d].type=%q, want %q", i, got.Items[i].Type, want)
		}
	}
	user := got.Items[0]
	if !reflect.DeepEqual(user.UserMessage.TextParts, []string{"one", "two"}) || user.UserMessage.Text != "one\ntwo" {
		t.Errorf("userMessage=%+v", user.UserMessage)
	}
	if user.TurnID != "TURN-1" || user.Completeness == nil || !user.Completeness.Truncated || !user.Completeness.Incomplete || user.Completeness.OriginalSizeBytes != 42 || user.Completeness.Reason != "bounded" || user.Provenance != "PROVENANCE_KIND_HOST_SYNTHESIZED" {
		t.Errorf("user common projection=%+v", user)
	}
	if got.Items[1].ReasoningSummary.Text != "why" || got.Items[2].AgentMessage.Text != "answer" {
		t.Errorf("text payloads=%+v %+v", got.Items[1], got.Items[2])
	}
	if want := []conversationPlanStep{{Text: "first", Status: "completed"}, {}, {Text: "next", Status: "in_progress"}}; !reflect.DeepEqual(got.Items[3].Plan.Steps, want) {
		t.Errorf("plan steps=%+v, want %+v", got.Items[3].Plan.Steps, want)
	}
	if command := got.Items[4].Command; !reflect.DeepEqual(command.Argv, []string{"sh", "-c", "true"}) || command.Cwd != "/work" || command.Output != "ok" || !command.HasExitCode || command.ExitCode == nil || *command.ExitCode != 0 {
		t.Errorf("command with exit=%+v", command)
	}
	if command := got.Items[5].Command; command.Argv == nil || command.HasExitCode || command.ExitCode != nil {
		t.Errorf("command without exit=%+v", command)
	}
	if tool := got.Items[6].Tool; tool.Name != "search" || tool.Summary != "looking" || tool.ResultSummary != "found" {
		t.Errorf("tool=%+v", tool)
	}
	wantKinds := []string{"added", "modified", "deleted", "renamed", "unspecified", "unspecified"}
	fileChange := got.Items[7].FileChange
	if fileChange.UnifiedDiff != "@@ diff" || len(fileChange.Changes) != len(wantKinds) {
		t.Fatalf("fileChange=%+v", fileChange)
	}
	for i, want := range wantKinds {
		if fileChange.Changes[i].Kind != want {
			t.Errorf("change[%d].kind=%q, want %q", i, fileChange.Changes[i].Kind, want)
		}
	}
	if renamed := fileChange.Changes[3]; renamed.OldPath != "old" || renamed.NewPath != "new" {
		t.Errorf("renamed change=%+v", renamed)
	}
	wantMessages := []conversationMessage{
		{ItemID: "U1", Role: "user", Text: "one\ntwo", Status: "completed"},
		{ItemID: "A1", Role: "assistant", Text: "answer", Status: "running"},
	}
	if !reflect.DeepEqual(got.Messages, wantMessages) {
		t.Errorf("messages=%+v, want %+v", got.Messages, wantMessages)
	}
}

func TestConversationCommandExitCodeJSONPresence(t *testing.T) {
	turn := conversationTurnFromProto(&remotev1.TurnSnapshot{Items: []*remotev1.Item{
		{Content: &remotev1.Item_Command{Command: &remotev1.CommandItem{HasExitCode: true, ExitCode: 0}}},
		{Content: &remotev1.Item_Command{Command: &remotev1.CommandItem{HasExitCode: false, ExitCode: 17}}},
	}})
	raw, err := json.Marshal(turn)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	if strings.Count(text, `"exitCode":`) != 1 || !strings.Contains(text, `"hasExitCode":true,"exitCode":0`) || strings.Contains(text, `"hasExitCode":false,"exitCode"`) {
		t.Fatalf("exit-code presence JSON=%s", text)
	}
}

func TestCodexHonestyFieldsJSONOmitsArbitraryWarningMetadata(t *testing.T) {
	raw, err := marshalCodexesForApp(&remotev1.ListCodexesResponse{Codexes: []*remotev1.Codex{{
		CodexId: "C-1", ThreadId: "T-1", Cwd: "/work", Title: "demo",
		Origin: remotev1.CodexOrigin_CODEX_ORIGIN_LOCAL_EXISTING,
		Status: remotev1.CodexStatus_CODEX_STATUS_IDLE, ActiveTurnId: "TURN-1",
		CreatedAtUnixMs: 10, ImportedAtUnixMs: 20, LastActivityAtUnixMs: 30,
		ManagementState:    remotev1.ManagementState_MANAGEMENT_STATE_EXPIRING_SOON,
		ManagedUntilUnixMs: 40,
		Warnings: []*remotev1.Warning{{
			Code: remotev1.WarningCode_WARNING_CODE_MANAGEMENT_EXPIRING_SOON, Message: "soon",
			ManagedUntilUnixMs: 40, Metadata: map[string]string{"ignored": "large-or-private"},
		}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	for _, want := range []string{`"origin":"CODEX_ORIGIN_LOCAL_EXISTING"`, `"activeTurnId":"TURN-1"`, `"managedUntilUnixMs":"40"`, `"code":"WARNING_CODE_MANAGEMENT_EXPIRING_SOON"`} {
		if !strings.Contains(text, want) {
			t.Errorf("Codex JSON missing %s: %s", want, text)
		}
	}
	if strings.Contains(text, "large-or-private") || strings.Contains(text, "metadata") {
		t.Fatalf("Codex JSON leaked warning metadata: %s", text)
	}
}

func writeConversationResponse(t *testing.T, ctx context.Context, conn *websocket.Conn, requestID string, history *remotev1.HistoryPage) {
	t.Helper()
	writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: requestID, Result: &remotev1.Response_ListHistory{ListHistory: &remotev1.ListHistoryResponse{History: history}}}}})
}

func TestCallCancellationInterruptsBlockingWrite(t *testing.T) {
	runCtx, runCancel := context.WithCancel(context.Background())
	defer runCancel()
	conn := &blockingWriteConnection{started: make(chan struct{})}
	client := &protocolClient{conn: conn, ctx: runCtx, cancel: runCancel, writeGate: newWriteGate(), pending: map[string]chan responseResult{}}
	callCtx, callCancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, err := client.call(callCtx, &remotev1.Request{Request: &remotev1.Request_GetHost{GetHost: &remotev1.GetHostRequest{}}})
		result <- err
	}()
	select {
	case <-conn.started:
	case <-time.After(time.Second):
		t.Fatal("write did not start")
	}
	callCancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("call error=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("canceled call remained blocked in Write")
	}
}

func TestSecondCallCanCancelWhileFirstWriterHoldsGate(t *testing.T) {
	runCtx, runCancel := context.WithCancel(context.Background())
	defer runCancel()
	conn := &blockingWriteConnection{started: make(chan struct{})}
	client := &protocolClient{conn: conn, ctx: runCtx, cancel: runCancel, writeGate: newWriteGate(), pending: map[string]chan responseResult{}}
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	firstResult := make(chan error, 1)
	go func() {
		_, err := client.call(firstCtx, &remotev1.Request{Request: &remotev1.Request_GetHost{GetHost: &remotev1.GetHostRequest{}}})
		firstResult <- err
	}()
	select {
	case <-conn.started:
	case <-time.After(time.Second):
		t.Fatal("first writer did not acquire gate")
	}

	secondCtx, cancelSecond := context.WithCancel(context.Background())
	secondResult := make(chan error, 1)
	go func() {
		_, err := client.call(secondCtx, &remotev1.Request{Request: &remotev1.Request_GetHost{GetHost: &remotev1.GetHostRequest{}}})
		secondResult <- err
	}()
	cancelSecond()
	select {
	case err := <-secondResult:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("second call error=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("second call cancellation waited for write gate")
	}

	cancelFirst()
	select {
	case <-firstResult:
	case <-time.After(time.Second):
		t.Fatal("first writer did not release after cancellation")
	}
}

func TestCloseCancelsPongHoldingWriteGate(t *testing.T) {
	runCtx, runCancel := context.WithCancel(context.Background())
	conn := &blockingWriteConnection{started: make(chan struct{})}
	gate := newWriteGate()
	client := &protocolClient{conn: conn, ctx: runCtx, cancel: runCancel, writeGate: gate, pending: map[string]chan responseResult{}}
	result := make(chan error, 1)
	go func() {
		result <- client.write(client.ctx, &remotev1.Frame{Payload: &remotev1.Frame_Pong{Pong: &remotev1.Pong{Nonce: 1}}})
	}()
	select {
	case <-conn.started:
	case <-time.After(time.Second):
		t.Fatal("Pong did not acquire write gate")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("Pong write unexpectedly succeeded after close")
		}
	case <-time.After(time.Second):
		t.Fatal("Close left Pong holding write gate")
	}
}

func completeServerHello(patch uint32) *remotev1.ServerHello {
	return &remotev1.ServerHello{ConnectionId: "conn", HostId: "HOST-1", HostRunId: "run", ProtocolVersion: &remotev1.ProtocolVersion{Major: 1, Minor: 1, Patch: patch}, Runtime: &remotev1.RuntimeInfo{}, Capabilities: &remotev1.Capabilities{}, HeartbeatIntervalMs: 1000, ConnectionTimeoutMs: 3000, MaxFrameBytes: 1 << 20}
}

func readTestFrame(t *testing.T, ctx context.Context, conn *websocket.Conn) *remotev1.Frame {
	t.Helper()
	typ, raw, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("type=%v", typ)
	}
	frame := new(remotev1.Frame)
	if err := protojson.Unmarshal(raw, frame); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	return frame
}
func writeTestFrame(t *testing.T, ctx context.Context, conn *websocket.Conn, frame *remotev1.Frame) {
	t.Helper()
	raw, err := protojson.Marshal(frame)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, raw); err != nil {
		t.Fatalf("write: %v", err)
	}
}
