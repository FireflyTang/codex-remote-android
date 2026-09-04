package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logpolicy"
	"tailscale.com/types/key"
)

type fakePlatform struct {
	mu     sync.Mutex
	states []string
}

type notificationPlatform struct {
	started  chan string
	release  chan struct{}
	received chan string
	mu       sync.Mutex
	active   int
	max      int
}

func (p *notificationPlatform) InterfacesJSON() string         { return "[]" }
func (p *notificationPlatform) BindSocketToNetwork(int32) bool { return true }
func (p *notificationPlatform) OnState(raw string) {
	p.mu.Lock()
	p.active++
	if p.active > p.max {
		p.max = p.active
	}
	p.mu.Unlock()
	p.started <- raw
	<-p.release
	p.mu.Lock()
	p.active--
	p.mu.Unlock()
	p.received <- raw
}

func (p *fakePlatform) InterfacesJSON() string         { return "[]" }
func (p *fakePlatform) BindSocketToNetwork(int32) bool { return true }
func (p *fakePlatform) OnState(s string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.states = append(p.states, s)
}

type fakeStarter struct {
	session session
	snap    snapshot
	err     error
}

func (s fakeStarter) Start(_ context.Context, _ configPayload, progress func(string, string)) (session, snapshot, error) {
	progress("auth_required", "https://login.tailscale.com/fake")
	progress("connecting_host", "")
	return s.session, s.snap, s.err
}

type fakeSession struct {
	snap   snapshot
	closed bool
}

type workspaceFakeSession struct {
	fakeSession
	mu             sync.Mutex
	supported      bool
	supportErr     error
	limits         workspaceLimits
	getResults     []workspaceDescriptor
	getErrors      []error
	getCalls       int
	blockFirstGet  bool
	getStarted     chan struct{}
	getRelease     chan struct{}
	getStartOnce   sync.Once
	directory      workspaceDirectory
	listStarted    chan struct{}
	listRelease    chan struct{}
	listCanceled   chan struct{}
	listStartOnce  sync.Once
	listCancelOnce sync.Once
	openFile       workspaceOpenFile
	writeResult    workspaceWriteResult
	lastWrite      writeWorkspaceTextFilePayload
	writeCalls     int
	uploadResult   workspaceUploadResult
	uploadErr      error
	lastUpload     workspaceUploadRequest
	uploadCalls    int
	uploadStarted  chan struct{}
	uploadRelease  chan struct{}
	uploadOnce     sync.Once
	downloadResult workspaceDownloadResult
	downloadErr    error
	downloadCalls  int
}

func newWorkspaceFakeSession() *workspaceFakeSession {
	return &workspaceFakeSession{
		supported: true,
		limits: workspaceLimits{
			MaxTextFileBytes: 1024, MaxInlineUploadBytes: 2048, MaxInlineDownloadBytes: 4096,
			MaxArchiveExpandedBytes: 8192, MaxArchiveEntryCount: 10,
		},
		getResults: []workspaceDescriptor{
			{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}},
			{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q2", Generation: 2}},
			{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q3", Generation: 3}},
		},
		directory:   workspaceDirectory{RelativeDirectory: "", Entries: []workspaceEntry{{RelativePath: "a.txt", Name: "a.txt", Kind: "regular_file", Revision: "R1", TextViewable: true, TextEditable: true}}},
		openFile:    workspaceOpenFile{Entry: workspaceEntry{RelativePath: "a.txt", Name: "a.txt", Kind: "regular_file", Revision: "R1", TextViewable: true, TextEditable: true}, UTF8Text: "old"},
		writeResult: workspaceWriteResult{Entry: workspaceEntry{RelativePath: "a.txt", Name: "a.txt", Kind: "regular_file", Revision: "R2", TextViewable: true, TextEditable: true}},
	}
}

func (s *workspaceFakeSession) WorkspaceSupport() (workspaceLimits, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.limits, s.supported, s.supportErr
}

func (s *workspaceFakeSession) GetWorkspace(context.Context, string) (workspaceDescriptor, error) {
	s.mu.Lock()
	call := s.getCalls
	s.getCalls++
	result := s.getResults[len(s.getResults)-1]
	if call < len(s.getResults) {
		result = s.getResults[call]
	}
	var resultErr error
	if call < len(s.getErrors) {
		resultErr = s.getErrors[call]
	}
	block := s.blockFirstGet && call == 0
	started, release := s.getStarted, s.getRelease
	s.mu.Unlock()
	if block {
		if started != nil {
			s.getStartOnce.Do(func() { close(started) })
		}
		<-release
	}
	return result, resultErr
}

func (s *workspaceFakeSession) ListWorkspaceEntries(ctx context.Context, _, relativeDirectory string) (workspaceDirectory, error) {
	s.mu.Lock()
	directory := s.directory
	started, release, canceled := s.listStarted, s.listRelease, s.listCanceled
	s.mu.Unlock()
	directory.RelativeDirectory = relativeDirectory
	if started != nil {
		s.listStartOnce.Do(func() { close(started) })
	}
	if release != nil {
		select {
		case <-release:
		case <-ctx.Done():
			if canceled != nil {
				s.listCancelOnce.Do(func() { close(canceled) })
			}
			return workspaceDirectory{}, ctx.Err()
		}
	}
	return directory, nil
}

func (s *workspaceFakeSession) ReadWorkspaceTextFile(_ context.Context, _, _ string) (workspaceOpenFile, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.openFile, nil
}

func (s *workspaceFakeSession) WriteWorkspaceTextFile(_ context.Context, p writeWorkspaceTextFilePayload) (workspaceWriteResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.writeCalls++
	s.lastWrite = p
	return s.writeResult, nil
}

func (s *workspaceFakeSession) UploadWorkspaceEntry(ctx context.Context, p workspaceUploadRequest) (workspaceUploadResult, error) {
	s.mu.Lock()
	s.uploadCalls++
	s.lastUpload = p
	result, resultErr := s.uploadResult, s.uploadErr
	started, release := s.uploadStarted, s.uploadRelease
	s.mu.Unlock()
	if started != nil {
		s.uploadOnce.Do(func() { close(started) })
	}
	if release != nil {
		select {
		case <-ctx.Done():
			return workspaceUploadResult{}, ctx.Err()
		case <-release:
		}
	}
	return result, resultErr
}

func (s *workspaceFakeSession) DownloadWorkspaceEntry(context.Context, string, string) (workspaceDownloadResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.downloadCalls++
	return s.downloadResult, s.downloadErr
}

type workspaceConversationSession struct {
	*workspaceFakeSession
	historyStarted chan struct{}
	historyRelease chan struct{}
	historyOnce    sync.Once
}

func (s *workspaceConversationSession) ListHistory(ctx context.Context, codexID string) (conversationState, error) {
	s.historyOnce.Do(func() { close(s.historyStarted) })
	select {
	case <-s.historyRelease:
		return conversationState{}, errors.New("conversation poll failed")
	case <-ctx.Done():
		return conversationState{}, ctx.Err()
	}
}

type managementSession struct {
	fakeSession
	refreshes int
}

func (s *managementSession) Refresh(context.Context) (snapshot, error) {
	s.refreshes++
	return s.snap, nil
}
func (*managementSession) ListDirectories(_ context.Context, parent string) (directoryListing, error) {
	return directoryListing{ParentPath: parent, Directories: []directoryEntry{{Name: "child", Path: parent + "/child"}}}, nil
}
func (*managementSession) ListSessionCandidates(_ context.Context, cwd string) (sessionCandidatesState, error) {
	return sessionCandidatesState{NormalizedCwd: cwd, Sessions: []sessionCandidate{{SessionID: "S-1", Cwd: cwd, Title: "old", Preview: "preview", Source: "rollout", Availability: "SESSION_AVAILABILITY_AVAILABLE", ManagedCodexID: "CODEX-1"}}}, nil
}

type conversationFakeSession struct {
	mu               sync.Mutex
	histories        []conversationState
	listCalls        int
	startText        string
	startOptions     *turnOptionsPayload
	startCalls       int
	interruptTurnID  string
	interruptCalls   int
	interruptStarted chan struct{}
	interruptRelease chan struct{}
	interruptOnce    sync.Once
	blockHistory     bool
	historyStarted   chan struct{}
	historyCanceled  chan struct{}
	closeOnce        sync.Once
}

func (s *conversationFakeSession) Refresh(context.Context) (snapshot, error) { return snapshot{}, nil }
func (s *conversationFakeSession) ListHistory(ctx context.Context, codexID string) (conversationState, error) {
	s.mu.Lock()
	s.listCalls++
	if s.blockHistory {
		started, canceled := s.historyStarted, s.historyCanceled
		s.mu.Unlock()
		select {
		case <-started:
		default:
			close(started)
		}
		<-ctx.Done()
		s.closeOnce.Do(func() { close(canceled) })
		return conversationState{}, ctx.Err()
	}
	index := s.listCalls - 1
	if index >= len(s.histories) {
		index = len(s.histories) - 1
	}
	history := s.histories[index]
	s.mu.Unlock()
	history.CodexID = codexID
	return history, nil
}
func (s *conversationFakeSession) StartTurn(_ context.Context, _, _ string, text string, options *turnOptionsPayload) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.startCalls++
	s.startText, s.startOptions = text, options
	return "TURN-1", nil
}
func (s *conversationFakeSession) InterruptTurn(_ context.Context, _ string, turnID string) (string, error) {
	s.mu.Lock()
	s.interruptCalls++
	s.interruptTurnID = turnID
	started, release := s.interruptStarted, s.interruptRelease
	s.mu.Unlock()
	if started != nil {
		s.interruptOnce.Do(func() { close(started) })
	}
	if release != nil {
		<-release
	}
	return turnID, nil
}
func (*conversationFakeSession) ListDirectories(context.Context, string) (directoryListing, error) {
	return directoryListing{}, nil
}
func (*conversationFakeSession) ListSessionCandidates(context.Context, string) (sessionCandidatesState, error) {
	return sessionCandidatesState{}, nil
}
func (*conversationFakeSession) CreateCodex(context.Context, createCodexPayload) (string, error) {
	return "CODEX-NEW", nil
}
func (*conversationFakeSession) ImportSession(context.Context, importSessionPayload) (string, error) {
	return "CODEX-IMPORTED", nil
}
func (*conversationFakeSession) RenameCodex(context.Context, renameCodexPayload) error { return nil }
func (*conversationFakeSession) UnmanageCodex(context.Context, string) error           { return nil }
func (*conversationFakeSession) ForgetCodex(context.Context, string) error             { return nil }
func (s *conversationFakeSession) Close() error                                        { return nil }

type blockingRefreshSession struct {
	started     chan struct{}
	refreshDone chan struct{}
	closed      chan struct{}
	startOnce   sync.Once
	closeOnce   sync.Once
}

func (s *blockingRefreshSession) Refresh(ctx context.Context) (snapshot, error) {
	s.startOnce.Do(func() { close(s.started) })
	<-ctx.Done()
	close(s.refreshDone)
	return snapshot{}, ctx.Err()
}

func (s *blockingRefreshSession) ListHistory(ctx context.Context, _ string) (conversationState, error) {
	<-ctx.Done()
	return conversationState{}, ctx.Err()
}
func (s *blockingRefreshSession) StartTurn(context.Context, string, string, string, *turnOptionsPayload) (string, error) {
	return "", nil
}
func (s *blockingRefreshSession) InterruptTurn(context.Context, string, string) (string, error) {
	return "", nil
}
func (*blockingRefreshSession) ListDirectories(context.Context, string) (directoryListing, error) {
	return directoryListing{}, nil
}
func (*blockingRefreshSession) ListSessionCandidates(context.Context, string) (sessionCandidatesState, error) {
	return sessionCandidatesState{}, nil
}
func (*blockingRefreshSession) CreateCodex(context.Context, createCodexPayload) (string, error) {
	return "", nil
}
func (*blockingRefreshSession) ImportSession(context.Context, importSessionPayload) (string, error) {
	return "", nil
}
func (*blockingRefreshSession) RenameCodex(context.Context, renameCodexPayload) error { return nil }
func (*blockingRefreshSession) UnmanageCodex(context.Context, string) error           { return nil }
func (*blockingRefreshSession) ForgetCodex(context.Context, string) error             { return nil }

func (s *blockingRefreshSession) Close() error {
	s.closeOnce.Do(func() { close(s.closed) })
	return nil
}

type interleavedRefreshSession struct {
	fakeSession
	started   chan struct{}
	release   chan struct{}
	startOnce sync.Once
	result    snapshot
	err       error
}

func (s *interleavedRefreshSession) Refresh(ctx context.Context) (snapshot, error) {
	s.startOnce.Do(func() { close(s.started) })
	select {
	case <-s.release:
		return s.result, s.err
	case <-ctx.Done():
		return snapshot{}, ctx.Err()
	}
}

func (s *fakeSession) Refresh(context.Context) (snapshot, error) { return s.snap, nil }
func (s *fakeSession) ListHistory(_ context.Context, codexID string) (conversationState, error) {
	return conversationState{CodexID: codexID, HistoryComplete: true, Turns: []conversationTurn{}}, nil
}
func (s *fakeSession) StartTurn(context.Context, string, string, string, *turnOptionsPayload) (string, error) {
	return "TURN-1", nil
}
func (s *fakeSession) InterruptTurn(context.Context, string, string) (string, error) {
	return "TURN-1", nil
}
func (*fakeSession) ListDirectories(context.Context, string) (directoryListing, error) {
	return directoryListing{}, nil
}
func (*fakeSession) ListSessionCandidates(context.Context, string) (sessionCandidatesState, error) {
	return sessionCandidatesState{}, nil
}
func (*fakeSession) CreateCodex(context.Context, createCodexPayload) (string, error) {
	return "CODEX-NEW", nil
}
func (*fakeSession) ImportSession(context.Context, importSessionPayload) (string, error) {
	return "CODEX-IMPORTED", nil
}
func (*fakeSession) RenameCodex(context.Context, renameCodexPayload) error { return nil }
func (*fakeSession) UnmanageCodex(context.Context, string) error           { return nil }
func (*fakeSession) ForgetCodex(context.Context, string) error             { return nil }
func (s *fakeSession) Close() error                                        { s.closed = true; return nil }

func decodeState(t *testing.T, raw string) state {
	t.Helper()
	var got state
	if err := json.Unmarshal([]byte(raw), &got); err != nil {
		t.Fatalf("decode state: %v\n%s", err, raw)
	}
	return got
}

func TestCoreConfigureStartRefreshStop(t *testing.T) {
	p := new(fakePlatform)
	sess := &fakeSession{snap: snapshot{Host: json.RawMessage(`{"host":{"hostId":"HOST-2"}}`), Codexes: json.RawMessage(`{"codexes":[]}`)}}
	c := NewCore(p)
	c.starter = fakeStarter{session: sess, snap: snapshot{TailnetIPs: []string{"100.64.0.2"}, ServerHello: json.RawMessage(`{"hostId":"HOST-1"}`), Host: json.RawMessage(`{"host":{"hostId":"HOST-1"}}`), Codexes: json.RawMessage(`{"codexes":[]}`)}}

	configured := decodeState(t, c.Dispatch(`{"version":1,"id":"c1","type":"configure","payload":{"hostname":"codex-remote-android","stateDir":"/tmp/state","hostEndpoint":"codex-remote-linux","clientId":"client-1","clientRunId":"run-1"}}`))
	if configured.Phase != "configured" {
		t.Fatalf("phase=%q", configured.Phase)
	}
	started := decodeState(t, c.Dispatch(`{"version":1,"id":"s1","type":"start"}`))
	if started.Phase != "starting_tailnet" {
		t.Fatalf("phase=%q", started.Phase)
	}
	waitPhase(t, c, "ready")
	ready := decodeState(t, c.State())
	if ready.Protocol.WireVersion != "1.1.2" || len(ready.TailnetIPs) != 1 {
		t.Fatalf("unexpected ready state: %+v", ready)
	}

	decodeState(t, c.Dispatch(`{"version":1,"id":"r1","type":"refresh"}`))
	waitPhase(t, c, "ready")
	stopped := decodeState(t, c.Dispatch(`{"version":1,"id":"x1","type":"stop"}`))
	if stopped.Phase != "stopped" || !sess.closed {
		t.Fatalf("stop state=%+v closed=%v", stopped, sess.closed)
	}
}

func TestCoreSessionManagementCommandsRefreshAndSelection(t *testing.T) {
	c := NewCore(new(fakePlatform))
	sess := &managementSession{fakeSession: fakeSession{snap: snapshot{Codexes: json.RawMessage(`{"codexes":[{"codexId":"CODEX-NEW","managementState":"MANAGEMENT_STATE_MANAGED"}]}`)}}}
	c.session, c.state.Phase = sess, "ready"

	state := decodeState(t, c.Dispatch(`{"version":1,"id":"dirs","type":"list_directories","payload":{"parentPath":"/work"}}`))
	if state.Phase != "loading_directories" {
		t.Fatalf("directories phase=%q", state.Phase)
	}
	waitCommandPhase(t, c, "dirs", "ready")
	state = decodeState(t, c.State())
	if state.DirectoryListing == nil || state.DirectoryListing.ParentPath != "/work" || len(state.DirectoryListing.Directories) != 1 {
		t.Fatalf("directories=%+v", state.DirectoryListing)
	}

	c.Dispatch(`{"version":1,"id":"candidates","type":"list_session_candidates","payload":{"cwd":"/work"}}`)
	waitCommandPhase(t, c, "candidates", "ready")
	state = decodeState(t, c.State())
	if state.SessionCandidates == nil || state.SessionCandidates.NormalizedCwd != "/work" || state.SessionCandidates.Sessions[0].ManagedCodexID != "CODEX-1" {
		t.Fatalf("candidates=%+v", state.SessionCandidates)
	}

	c.Dispatch(`{"version":1,"id":"create","type":"create_codex","payload":{"cwd":"/new","createDirectoryIfMissing":true,"title":"New"}}`)
	waitCommandPhase(t, c, "create", "ready")
	state = decodeState(t, c.State())
	if state.SelectedCodexID != "CODEX-NEW" || sess.refreshes != 1 {
		t.Fatalf("create state=%+v refreshes=%d", state, sess.refreshes)
	}

	c.state.Conversation = &conversationState{CodexID: "CODEX-NEW", Turns: []conversationTurn{}}
	c.Dispatch(`{"version":1,"id":"forget","type":"forget_codex","payload":{"codexId":"CODEX-NEW"}}`)
	waitCommandPhase(t, c, "forget", "ready")
	state = decodeState(t, c.State())
	if state.SelectedCodexID != "" || state.Conversation != nil || sess.refreshes != 2 {
		t.Fatalf("forget state=%+v refreshes=%d", state, sess.refreshes)
	}
}

func TestCoreRejectsWrongVersionAndMissingConfig(t *testing.T) {
	c := NewCore(new(fakePlatform))
	got := decodeState(t, c.Dispatch(`{"version":2,"id":"bad","type":"start"}`))
	if got.Error == "" {
		t.Fatal("expected version error")
	}
	got = decodeState(t, c.Dispatch(`{"version":1,"id":"bad2","type":"start"}`))
	if got.Error == "" {
		t.Fatal("expected configure error")
	}
}

func TestStateNotifierSerializesAndDropsPostStopStaleRevision(t *testing.T) {
	p := &notificationPlatform{started: make(chan string, 2), release: make(chan struct{}, 2), received: make(chan string, 2)}
	n := &stateNotifier{platform: p}
	n.enqueue(stateNotification{revision: 1, json: `{"revision":1,"phase":"ready"}`})
	select {
	case <-p.started:
	case <-time.After(time.Second):
		t.Fatal("first notification did not start")
	}
	n.enqueue(stateNotification{revision: 2, json: `{"revision":2,"phase":"stopped"}`})
	select {
	case raw := <-p.started:
		t.Fatalf("second callback overlapped first: %s", raw)
	case <-time.After(20 * time.Millisecond):
	}
	p.release <- struct{}{}
	<-p.received
	select {
	case <-p.started:
	case <-time.After(time.Second):
		t.Fatal("second notification did not start")
	}
	p.release <- struct{}{}
	<-p.received
	p.mu.Lock()
	if p.max != 1 {
		t.Fatalf("max concurrent callbacks=%d", p.max)
	}
	p.mu.Unlock()

	// A delayed pre-stop notification must not be delivered after the stopped
	// revision has already crossed the Kotlin boundary.
	n.enqueue(stateNotification{revision: 1, json: `{"revision":1,"phase":"ready"}`})
	select {
	case raw := <-p.started:
		t.Fatalf("stale post-stop notification delivered: %s", raw)
	case <-time.After(20 * time.Millisecond):
	}
}

func TestAuthProgressLogfCapturesTsnetUserLog(t *testing.T) {
	var phase, authURL string
	logf := authProgressLogf(func(gotPhase, gotURL string) { phase, authURL = gotPhase, gotURL })
	logf("To authenticate, visit: %s", "https://login.tailscale.com/example")
	if phase != "auth_required" || authURL != "https://login.tailscale.com/example" {
		t.Fatalf("progress=(%q, %q)", phase, authURL)
	}
	logf("backend diagnostic without login URL")
	if authURL != "https://login.tailscale.com/example" {
		t.Fatalf("non-auth log changed URL to %q", authURL)
	}
}

func TestConfigureTailscaleLogsDirProvidesLogpolicyLocation(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "")
	want := filepath.Join(t.TempDir(), "state", "logs")
	if err := configureTailscaleLogsDir(filepath.Dir(want)); err != nil {
		t.Fatalf("configureTailscaleLogsDir: %v", err)
	}
	if got := os.Getenv("TS_LOGS_DIR"); got != want {
		t.Fatalf("TS_LOGS_DIR = %q, want %q", got, want)
	}
	if info, err := os.Stat(want); err != nil {
		t.Fatalf("stat configured log directory: %v", err)
	} else if !info.IsDir() {
		t.Fatalf("configured log path is not a directory: %q", want)
	}
	if got := logpolicy.LogsDir(t.Logf); got != want {
		t.Fatalf("logpolicy.LogsDir = %q, want %q", got, want)
	}
}

func TestEndpointPeerStatusMatchesIPAndMagicDNSName(t *testing.T) {
	var nodeKey key.NodePublic
	status := &ipnstate.Status{
		CurrentTailnet: &ipnstate.TailnetStatus{MagicDNSEnabled: true},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			nodeKey: {HostName: "codex-remote-linux", DNSName: "codex-remote-linux.example.ts.net.", Online: true, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.10")}},
		},
	}
	for _, endpoint := range []string{"ws://100.64.0.10/connect", "codex-remote-linux", "codex-remote-linux.example.ts.net"} {
		ip, name, known, online, magicDNS := endpointPeerStatus(status, endpoint)
		if ip.String() != "100.64.0.10" || name != "codex-remote-linux" || !known || !online || !magicDNS {
			t.Fatalf("endpointPeerStatus(%q) = (%v, %q, %v, %v, %v)", endpoint, ip, name, known, online, magicDNS)
		}
	}
}

func TestStopCancelsBlockingRefreshWithoutWaitingForSessionLock(t *testing.T) {
	sess := &blockingRefreshSession{
		started: make(chan struct{}), refreshDone: make(chan struct{}), closed: make(chan struct{}),
	}
	c := NewCore(new(fakePlatform))
	c.mu.Lock()
	c.session = sess
	c.state.Phase = "ready"
	c.mu.Unlock()

	got := decodeState(t, c.Dispatch(`{"version":1,"id":"refresh","type":"refresh"}`))
	if got.Phase != "refreshing" {
		t.Fatalf("phase=%q", got.Phase)
	}
	select {
	case <-sess.started:
	case <-time.After(time.Second):
		t.Fatal("refresh did not start")
	}

	stopDone := make(chan string, 1)
	go func() { stopDone <- c.Dispatch(`{"version":1,"id":"stop","type":"stop"}`) }()
	select {
	case raw := <-stopDone:
		if stopped := decodeState(t, raw); stopped.Phase != "stopped" {
			t.Fatalf("phase=%q", stopped.Phase)
		}
	case <-time.After(time.Second):
		t.Fatal("stop waited for blocking Refresh")
	}
	select {
	case <-sess.refreshDone:
	case <-time.After(time.Second):
		t.Fatal("refresh context was not canceled")
	}
	select {
	case <-sess.closed:
	default:
		t.Fatal("session was not closed")
	}
	if final := decodeState(t, c.State()); final.Phase != "stopped" {
		t.Fatalf("stale refresh overwrote stop: phase=%q", final.Phase)
	}
}

func TestRefreshCompletionRestoresCommandIDAfterInterleavedNetworkChange(t *testing.T) {
	tests := []struct {
		name      string
		result    snapshot
		err       error
		wantPhase string
		wantIPs   []string
		wantHost  string
	}{
		{
			name:      "failure preserves previous network state",
			err:       errors.New("refresh unavailable"),
			wantPhase: "error",
			wantIPs:   []string{"100.64.0.1"},
			wantHost:  `{"host":{"hostId":"HOST-OLD"}}`,
		},
		{
			name: "success applies refreshed network state",
			result: snapshot{
				TailnetIPs: []string{"100.64.0.2"},
				Host:       json.RawMessage(`{"host":{"hostId":"HOST-NEW"}}`),
			},
			wantPhase: "ready",
			wantIPs:   []string{"100.64.0.2"},
			wantHost:  `{"host":{"hostId":"HOST-NEW"}}`,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sess := &interleavedRefreshSession{
				started: make(chan struct{}),
				release: make(chan struct{}),
				result:  tt.result,
				err:     tt.err,
			}
			c := NewCore(new(fakePlatform))
			c.mu.Lock()
			c.session = sess
			c.state.Phase = "ready"
			c.state.Endpoint = "ws://host/connect"
			c.state.TailnetIPs = []string{"100.64.0.1"}
			c.state.Host = json.RawMessage(`{"host":{"hostId":"HOST-OLD"}}`)
			c.mu.Unlock()

			refreshing := decodeState(t, c.Dispatch(`{"version":1,"id":"refresh","type":"refresh"}`))
			select {
			case <-sess.started:
			case <-time.After(time.Second):
				t.Fatal("refresh did not start")
			}
			networkChanged := decodeState(t, c.Dispatch(`{"version":1,"id":"network","type":"network_changed","payload":{"defaultInterface":"wlan0","defaultGateway":"192.168.1.1"}}`))
			if networkChanged.CommandID != "network" || networkChanged.Phase != "refreshing" || networkChanged.Revision <= refreshing.Revision {
				t.Fatalf("interleaved network state=%+v, refreshing revision=%d", networkChanged, refreshing.Revision)
			}

			close(sess.release)
			waitCommandPhase(t, c, "refresh", tt.wantPhase)
			c.mu.Lock()
			final := c.state
			c.mu.Unlock()
			if final.Revision <= networkChanged.Revision {
				t.Fatalf("final revision=%d, want greater than network revision=%d", final.Revision, networkChanged.Revision)
			}
			if final.Endpoint != "ws://host/connect" || !slices.Equal(final.TailnetIPs, tt.wantIPs) || string(final.Host) != tt.wantHost {
				t.Fatalf("final network state endpoint=%q ips=%v host=%s", final.Endpoint, final.TailnetIPs, final.Host)
			}
			if tt.err != nil && final.Error != tt.err.Error() {
				t.Fatalf("final error=%q, want %q", final.Error, tt.err.Error())
			}
		})
	}
}

func TestConversationSelectStartPollTerminalAndInterrupt(t *testing.T) {
	sess := &conversationFakeSession{histories: []conversationState{
		{HistoryComplete: true, Turns: []conversationTurn{}},
		{HistoryComplete: true, ActiveTurnID: "TURN-1", Running: true, Turns: []conversationTurn{{TurnID: "TURN-1", Status: "running", Messages: []conversationMessage{{ItemID: "I1", Role: "user", Text: "hello", Status: "completed"}}}}},
		{HistoryComplete: true, Turns: []conversationTurn{{TurnID: "TURN-1", Status: "completed", StartedAtUnixMS: 1, CompletedAtUnixMS: 2, Messages: []conversationMessage{{ItemID: "I2", Role: "assistant", Text: "hi", Status: "completed"}}}}},
		{HistoryComplete: true, Turns: []conversationTurn{{TurnID: "TURN-1", Status: "interrupted", Messages: []conversationMessage{}}}},
	}}
	c := NewCore(new(fakePlatform))
	c.mu.Lock()
	c.session, c.state.Phase = sess, "ready"
	c.mu.Unlock()

	selected := decodeState(t, c.Dispatch(`{"version":1,"id":"select","type":"select_codex","payload":{"codexId":"CODEX-1"}}`))
	if selected.Phase != "loading_conversation" || selected.SelectedCodexID != "CODEX-1" {
		t.Fatalf("select state=%+v", selected)
	}
	waitPhase(t, c, "ready")

	started := decodeState(t, c.Dispatch(`{"version":1,"id":"turn","type":"start_turn","payload":{"text":"hello","options":{"model":"gpt-test","mode":"plan","approvalPolicy":"never","reasoningEffort":"high"}}}`))
	if started.Phase != "starting_turn" {
		t.Fatalf("start phase=%q", started.Phase)
	}
	waitConversationStatus(t, c, "TURN-1", "running")
	if got := decodeState(t, c.Dispatch(`{"version":1,"id":"turn-2","type":"start_turn","payload":{"text":"again"}}`)); got.Error == "" {
		t.Fatal("second start_turn was not rejected")
	}
	if got := decodeState(t, c.Dispatch(`{"version":1,"id":"refresh-running","type":"refresh_conversation","payload":{}}`)); got.Error == "" {
		t.Fatal("refresh_conversation while running was not rejected")
	}
	if got := decodeState(t, c.Dispatch(`{"version":1,"id":"select-running","type":"select_codex","payload":{"codexId":"CODEX-2"}}`)); got.Error == "" {
		t.Fatal("select_codex while running was not rejected")
	}
	waitConversationStatus(t, c, "TURN-1", "completed")
	sess.mu.Lock()
	if sess.startCalls != 1 || sess.startText != "hello" || sess.startOptions == nil || sess.startOptions.Mode != "plan" || sess.startOptions.ReasoningEffort != "high" {
		t.Fatalf("start calls=%d payload text=%q options=%+v", sess.startCalls, sess.startText, sess.startOptions)
	}
	sess.mu.Unlock()

	refreshed := decodeState(t, c.Dispatch(`{"version":1,"id":"refresh-conversation","type":"refresh_conversation","payload":{}}`))
	if refreshed.Phase != "loading_conversation" {
		t.Fatalf("refresh conversation phase=%q", refreshed.Phase)
	}
	waitPhase(t, c, "ready")

	c.mu.Lock()
	c.state.Conversation.ActiveTurnID, c.state.Conversation.Running = "TURN-1", true
	c.mu.Unlock()
	c.Dispatch(`{"version":1,"id":"interrupt","type":"interrupt_turn","payload":{}}`)
	waitInterrupt(t, sess, "TURN-1")
	waitConversationStatus(t, c, "TURN-1", "interrupted")
	sess.mu.Lock()
	if sess.interruptTurnID != "TURN-1" {
		t.Fatalf("interrupt turn=%q", sess.interruptTurnID)
	}
	sess.mu.Unlock()
}

func TestConversationPollTimeoutPublishesErrorAndClearsRunning(t *testing.T) {
	sess := &conversationFakeSession{blockHistory: true, historyStarted: make(chan struct{}), historyCanceled: make(chan struct{})}
	c := NewCore(new(fakePlatform))
	c.conversationPollTimeout = 20 * time.Millisecond
	c.mu.Lock()
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "CODEX-1"
	c.state.Conversation = &conversationState{CodexID: "CODEX-1", Turns: []conversationTurn{}}
	c.mu.Unlock()
	c.Dispatch(`{"version":1,"id":"turn","type":"start_turn","payload":{"text":"hello"}}`)
	waitPhase(t, c, "error")
	got := decodeState(t, c.State())
	if got.Error == "" || got.Conversation == nil || got.Conversation.Running || got.Conversation.ActiveTurnID != "" {
		t.Fatalf("timeout state=%+v", got)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pollCancel != nil {
		t.Fatal("timeout left pollCancel installed")
	}
}

func TestDuplicateInterruptIsRejectedWithoutSecondRPC(t *testing.T) {
	sess := &conversationFakeSession{
		histories:        []conversationState{{HistoryComplete: true, Turns: []conversationTurn{{TurnID: "TURN-1", Status: "interrupted", Messages: []conversationMessage{}}}}},
		interruptStarted: make(chan struct{}),
		interruptRelease: make(chan struct{}),
	}
	c := NewCore(new(fakePlatform))
	c.mu.Lock()
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "CODEX-1"
	c.state.Conversation = &conversationState{CodexID: "CODEX-1", ActiveTurnID: "TURN-1", Running: true, Turns: []conversationTurn{{TurnID: "TURN-1", Status: "running", Messages: []conversationMessage{}}}}
	c.mu.Unlock()

	first := decodeState(t, c.Dispatch(`{"version":1,"id":"interrupt-1","type":"interrupt_turn","payload":{}}`))
	if first.Error != "" {
		t.Fatalf("first interrupt error=%q", first.Error)
	}
	select {
	case <-sess.interruptStarted:
	case <-time.After(time.Second):
		t.Fatal("first InterruptTurn RPC did not start")
	}
	second := decodeState(t, c.Dispatch(`{"version":1,"id":"interrupt-2","type":"interrupt_turn","payload":{"turnId":"TURN-1"}}`))
	if second.Error == "" {
		t.Fatal("duplicate interrupt was not rejected")
	}
	sess.mu.Lock()
	if sess.interruptCalls != 1 {
		t.Fatalf("InterruptTurn RPC calls=%d", sess.interruptCalls)
	}
	sess.mu.Unlock()
	close(sess.interruptRelease)
	waitConversationStatus(t, c, "TURN-1", "interrupted")
}

func TestStopCancelsConversationPoll(t *testing.T) {
	sess := &conversationFakeSession{blockHistory: true, historyStarted: make(chan struct{}), historyCanceled: make(chan struct{})}
	c := NewCore(new(fakePlatform))
	c.mu.Lock()
	c.session, c.state.Phase = sess, "ready"
	c.state.SelectedCodexID = "CODEX-1"
	c.state.Conversation = &conversationState{CodexID: "CODEX-1", Turns: []conversationTurn{}}
	c.mu.Unlock()
	c.Dispatch(`{"version":1,"id":"turn","type":"start_turn","payload":{"text":"hello"}}`)
	select {
	case <-sess.historyStarted:
	case <-time.After(time.Second):
		t.Fatal("conversation poll did not start")
	}
	if stopped := decodeState(t, c.Dispatch(`{"version":1,"id":"stop","type":"stop"}`)); stopped.Phase != "stopped" {
		t.Fatalf("phase=%q", stopped.Phase)
	}
	select {
	case <-sess.historyCanceled:
	case <-time.After(time.Second):
		t.Fatal("stop did not cancel conversation poll")
	}
}

func TestCoreWorkspaceTextFlowAndWriteUpdatesOpenFile(t *testing.T) {
	sess := newWorkspaceFakeSession()
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"

	started := decodeState(t, c.Dispatch(`{"version":1,"id":"get","type":"get_workspace","payload":{"codexId":"CODEX-1"}}`))
	if started.Workspace == nil || started.Workspace.Loading != "workspace" || !started.Workspace.Supported || started.Workspace.Limits.MaxTextFileBytes != 1024 {
		t.Fatalf("get start=%+v", started.Workspace)
	}
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.WorkspaceRoot == "/work"
	})

	listing := decodeState(t, c.Dispatch(`{"version":1,"id":"list","type":"list_workspace_entries","payload":{"codexId":"CODEX-1"}}`))
	if listing.Workspace.Loading != "entries" || listing.Workspace.CurrentDirectory == nil || listing.Workspace.CurrentDirectory.RelativeDirectory != "" || listing.Workspace.CurrentDirectory.Entries == nil {
		t.Fatalf("list start=%+v", listing.Workspace)
	}
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && len(workspace.CurrentDirectory.Entries) == 1
	})

	reading := decodeState(t, c.Dispatch(`{"version":1,"id":"read","type":"read_workspace_text_file","payload":{"codexId":"CODEX-1","relativePath":"a.txt"}}`))
	if reading.Workspace.Loading != "file" || reading.Workspace.OpenFile != nil {
		t.Fatalf("read start=%+v", reading.Workspace)
	}
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.OpenFile != nil && workspace.OpenFile.UTF8Text == "old"
	})

	writing := decodeState(t, c.Dispatch(`{"version":1,"id":"write","type":"write_workspace_text_file","payload":{"codexId":"CODEX-1","relativePath":"a.txt","utf8Text":"new","condition":"replace_only","expectedRevision":"R1","expectedQuiescenceToken":"Q1"}}`))
	if writing.Workspace.Loading != "write" || writing.Workspace.OpenFile == nil || writing.Workspace.OpenFile.UTF8Text != "old" {
		t.Fatalf("write start did not preserve open file: %+v", writing.Workspace)
	}
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.LastWrite != nil && workspace.OpenFile != nil && workspace.OpenFile.Entry.Revision == "R2" && workspace.OpenFile.UTF8Text == "new" && workspace.AccessState != nil && workspace.AccessState.QuiescenceToken == "Q2" && workspace.AccessState.Generation == 2
	})
	sess.mu.Lock()
	lastWrite := sess.lastWrite
	getCalls := sess.getCalls
	sess.writeResult = workspaceWriteResult{Entry: workspaceEntry{RelativePath: "a.txt", Name: "a.txt", Kind: "regular_file", Revision: "R3", TextViewable: true, TextEditable: true}}
	sess.mu.Unlock()
	if lastWrite.ExpectedRevision != "R1" || lastWrite.ExpectedQuiescenceToken != "Q1" || lastWrite.Condition != "replace_only" || getCalls != 2 {
		t.Fatalf("write payload=%+v", lastWrite)
	}
	c.Dispatch(`{"version":1,"id":"write-2","type":"write_workspace_text_file","payload":{"codexId":"CODEX-1","relativePath":"a.txt","utf8Text":"newer","condition":"replace_only","expectedRevision":"R2","expectedQuiescenceToken":"Q2"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.OpenFile != nil && workspace.OpenFile.Entry.Revision == "R3" && workspace.OpenFile.UTF8Text == "newer" && workspace.AccessState != nil && workspace.AccessState.QuiescenceToken == "Q3" && workspace.AccessState.Generation == 3 && workspace.Error == nil
	})
	sess.mu.Lock()
	lastWrite, getCalls = sess.lastWrite, sess.getCalls
	sess.mu.Unlock()
	if lastWrite.ExpectedQuiescenceToken != "Q2" || lastWrite.ExpectedRevision != "R2" || getCalls != 3 {
		t.Fatalf("second write did not use refreshed state: payload=%+v getCalls=%d", lastWrite, getCalls)
	}
}

func TestCoreWorkspaceUploadDownloadAndContinuousMutation(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.getResults = []workspaceDescriptor{
		{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q2", Generation: 2}},
		{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q3", Generation: 3}},
	}
	sess.uploadResult = workspaceUploadResult{Entry: workspaceEntry{RelativePath: "bin/data.bin", Name: "data.bin", Kind: "regular_file", Revision: "U1"}}
	sess.downloadResult = workspaceDownloadResult{Entry: workspaceEntry{RelativePath: "dir", Name: "dir", Kind: "directory"}, Kind: "zip_directory", Filename: "dir.zip", ContentBase64: "emlw"}
	c := NewCore(nil)
	c.session, c.state.Phase = sess, "ready"
	c.state.Conversation = &conversationState{CodexID: "C", Turns: []conversationTurn{{TurnID: "T"}}}
	c.state.Workspace = &workspaceState{
		Supported: true, Limits: &sess.limits, CodexID: "C", WorkspaceRoot: "/work", Loading: "none",
		AccessState:      &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1},
		CurrentDirectory: &workspaceDirectory{RelativeDirectory: "", Entries: []workspaceEntry{{RelativePath: "keep", Kind: "regular_file"}}},
		OpenFile:         &workspaceOpenFile{Entry: workspaceEntry{RelativePath: "keep", Kind: "regular_file"}, UTF8Text: "keep"},
		UploadResult:     &workspaceUploadResult{Entry: workspaceEntry{RelativePath: "old", Kind: "regular_file"}},
	}

	uploading := decodeState(t, c.Dispatch(`{"version":1,"id":"upload-1","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"bin/data.bin","kind":"regular_file","contentBase64":"YWJj","expectedQuiescenceToken":"Q1"}}`))
	if uploading.Workspace.UploadResult != nil || uploading.Workspace.CurrentDirectory == nil || uploading.Workspace.OpenFile == nil {
		t.Fatalf("upload start result isolation=%+v", uploading.Workspace)
	}
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.UploadResult != nil && workspace.AccessState != nil && workspace.AccessState.QuiescenceToken == "Q2"
	})
	sess.mu.Lock()
	firstUpload := sess.lastUpload
	sess.uploadResult = workspaceUploadResult{Entry: workspaceEntry{RelativePath: "bin/data.bin", Name: "data.bin", Kind: "regular_file", Revision: "U2"}, Deduplicated: true}
	sess.mu.Unlock()
	if string(firstUpload.Content) != "abc" || firstUpload.ExpectedQuiescenceToken != "Q1" {
		t.Fatalf("first upload=%+v", firstUpload)
	}

	c.Dispatch(`{"version":1,"id":"upload-2","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"bin/data.bin","kind":"regular_file","contentBase64":"YWJj","expectedQuiescenceToken":"Q2"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.UploadResult != nil && workspace.UploadResult.Entry.Revision == "U2" && workspace.AccessState != nil && workspace.AccessState.QuiescenceToken == "Q3"
	})
	c.Dispatch(`{"version":1,"id":"download","type":"download_workspace_entry","payload":{"codexId":"C","relativePath":"dir"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.DownloadResult != nil
	})
	got := decodeState(t, c.State())
	if got.Workspace.DownloadResult.Kind != "zip_directory" || got.Workspace.DownloadResult.ContentBase64 != "emlw" || got.Workspace.CurrentDirectory == nil || got.Workspace.OpenFile == nil || got.Conversation == nil {
		t.Fatalf("upload/download overwrote unrelated state: workspace=%+v conversation=%+v", got.Workspace, got.Conversation)
	}
	sess.mu.Lock()
	if sess.uploadCalls != 2 || sess.getCalls != 2 || sess.downloadCalls != 1 || sess.lastUpload.ExpectedQuiescenceToken != "Q2" {
		t.Fatalf("calls upload/get/download=(%d,%d,%d), last=%+v", sess.uploadCalls, sess.getCalls, sess.downloadCalls, sess.lastUpload)
	}
	sess.mu.Unlock()
}

func TestWorkspaceUploadRefreshFailurePreservesCommittedResult(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.uploadResult = workspaceUploadResult{Entry: workspaceEntry{RelativePath: "a.bin", Name: "a.bin", Kind: "regular_file", Revision: "U1"}}
	sess.getErrors = []error{errors.New("refresh unavailable")}
	c := NewCore(nil)
	c.session, c.state.Phase = sess, "ready"
	c.state.Workspace = &workspaceState{Supported: true, Limits: &sess.limits, CodexID: "C", WorkspaceRoot: "/work", Loading: "none", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}}
	c.Dispatch(`{"version":1,"id":"upload","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"a.bin","kind":"regular_file","contentBase64":"YQ==","expectedQuiescenceToken":"Q1"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
	got := decodeState(t, c.State()).Workspace
	if got.UploadResult == nil || got.UploadResult.Entry.Revision != "U1" || got.AccessState != nil || got.Error.Code != "operation_failed" || !strings.Contains(got.Error.Message, "upload committed") {
		t.Fatalf("partial upload state=%+v", got)
	}
}

func TestWorkspaceUploadRejectsUnrotatedTokenAndClearsUnknownCommitToken(t *testing.T) {
	t.Run("generation advanced without token rotation", func(t *testing.T) {
		sess := newWorkspaceFakeSession()
		sess.uploadResult = workspaceUploadResult{Entry: workspaceEntry{RelativePath: "a.bin", Name: "a.bin", Kind: "regular_file", Revision: "U1"}}
		sess.getResults = []workspaceDescriptor{{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 2}}}
		c := NewCore(nil)
		c.session, c.state.Phase = sess, "ready"
		c.state.Workspace = &workspaceState{Supported: true, Limits: &sess.limits, CodexID: "C", Loading: "none", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}}
		c.Dispatch(`{"version":1,"id":"upload","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"a.bin","kind":"regular_file","contentBase64":"YQ==","expectedQuiescenceToken":"Q1"}}`)
		waitWorkspace(t, c, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
		got := decodeState(t, c.State()).Workspace
		if got.UploadResult == nil || got.AccessState != nil || !strings.Contains(got.Error.Message, "did not rotate") {
			t.Fatalf("unrotated token accepted: %+v", got)
		}
	})

	t.Run("upload response error is commit unknown", func(t *testing.T) {
		sess := newWorkspaceFakeSession()
		sess.uploadErr = newWorkspaceOperationError("workspace_archive_invalid", "Host rejected archive")
		c := NewCore(nil)
		c.session, c.state.Phase = sess, "ready"
		c.state.Workspace = &workspaceState{Supported: true, Limits: &sess.limits, CodexID: "C", Loading: "none", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}}
		c.Dispatch(`{"version":1,"id":"upload","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"a.bin","kind":"regular_file","contentBase64":"YQ==","expectedQuiescenceToken":"Q1"}}`)
		waitWorkspace(t, c, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
		got := decodeState(t, c.State()).Workspace
		if got.AccessState != nil || got.Error.Code != "workspace_archive_invalid" || got.UploadResult != nil {
			t.Fatalf("commit-unknown upload retained old token: %+v", got)
		}
	})
}

func TestWorkspaceUploadCannotBePreemptedByOtherWorkspaceCommands(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.uploadStarted, sess.uploadRelease = make(chan struct{}), make(chan struct{})
	sess.uploadResult = workspaceUploadResult{Entry: workspaceEntry{RelativePath: "a.bin", Name: "a.bin", Kind: "regular_file", Revision: "U1"}}
	sess.getResults = []workspaceDescriptor{{WorkspaceRoot: "/work", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q2", Generation: 2}}}
	c := NewCore(nil)
	c.session, c.state.Phase = sess, "ready"
	c.state.Workspace = &workspaceState{Supported: true, Limits: &sess.limits, CodexID: "C", Loading: "none", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}}
	c.Dispatch(`{"version":1,"id":"upload","type":"upload_workspace_entry","payload":{"codexId":"C","destinationPath":"a.bin","kind":"regular_file","contentBase64":"YQ==","expectedQuiescenceToken":"Q1"}}`)
	select {
	case <-sess.uploadStarted:
	case <-time.After(time.Second):
		t.Fatal("upload did not start")
	}
	for _, command := range []string{
		`{"version":1,"id":"download","type":"download_workspace_entry","payload":{"codexId":"C","relativePath":"a.bin"}}`,
		`{"version":1,"id":"list","type":"list_workspace_entries","payload":{"codexId":"C","relativeDirectory":""}}`,
	} {
		got := decodeState(t, c.Dispatch(command)).Workspace
		if got.Loading != "upload" || got.Error == nil || got.Error.Code != "workspace_busy" || got.AccessState == nil || got.AccessState.QuiescenceToken != "Q1" {
			t.Fatalf("workspace command preempted upload: %+v", got)
		}
	}
	sess.mu.Lock()
	if sess.downloadCalls != 0 || sess.listStarted != nil {
		t.Fatalf("preempting RPCs reached session: download=%d", sess.downloadCalls)
	}
	sess.mu.Unlock()
	close(sess.uploadRelease)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.UploadResult != nil && workspace.AccessState != nil && workspace.AccessState.QuiescenceToken == "Q2"
	})
}

func TestWorkspaceUploadLocalValidation(t *testing.T) {
	workspace := &workspaceState{Supported: true, Limits: &workspaceLimits{MaxInlineUploadBytes: 3}, AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q"}}
	valid := uploadWorkspaceEntryPayload{DestinationPath: "a.bin", Kind: "regular_file", ExpectedQuiescenceToken: "Q"}
	tests := []struct {
		name string
		edit func(*uploadWorkspaceEntryPayload)
		data []byte
		code string
	}{
		{name: "path", edit: func(p *uploadWorkspaceEntryPayload) { p.DestinationPath = "../a" }, data: []byte("a"), code: "invalid_request"},
		{name: "kind", edit: func(p *uploadWorkspaceEntryPayload) { p.Kind = "other" }, data: []byte("a"), code: "invalid_request"},
		{name: "limit", edit: func(*uploadWorkspaceEntryPayload) {}, data: []byte("four"), code: "workspace_upload_too_large"},
		{name: "token", edit: func(p *uploadWorkspaceEntryPayload) { p.ExpectedQuiescenceToken = "old" }, data: []byte("a"), code: "invalid_request"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			payload := valid
			test.edit(&payload)
			var operationError *workspaceOperationError
			if err := validateWorkspaceUploadLocked(workspace, payload, test.data); !errors.As(err, &operationError) || operationError.Code != test.code {
				t.Fatalf("error=%v code=%v, want %s", err, operationError, test.code)
			}
		})
	}
	for _, encoded := range []string{"YQ", "YQ==\n", "@@=="} {
		if _, err := decodeStrictBase64(encoded); err == nil {
			t.Fatalf("non-canonical base64 accepted: %q", encoded)
		}
	}
}

func TestWorkspaceWriteLocalPreconditions(t *testing.T) {
	base := func() *workspaceState {
		return &workspaceState{
			Supported: true, Limits: &workspaceLimits{MaxTextFileBytes: 4}, CodexID: "C",
			AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q"},
			OpenFile:    &workspaceOpenFile{Entry: workspaceEntry{RelativePath: "a", Kind: "regular_file", Revision: "R1", TextEditable: true}},
		}
	}
	valid := writeWorkspaceTextFilePayload{CodexID: "C", RelativePath: "a", UTF8Text: "text", Condition: "replace_only", ExpectedRevision: "R1", ExpectedQuiescenceToken: "Q"}
	tests := []struct {
		name string
		edit func(*workspaceState, *writeWorkspaceTextFilePayload)
		code string
	}{
		{name: "too large", edit: func(_ *workspaceState, p *writeWorkspaceTextFilePayload) { p.UTF8Text = "12345" }, code: "workspace_text_too_large"},
		{name: "busy", edit: func(w *workspaceState, _ *writeWorkspaceTextFilePayload) { w.AccessState.MutationStatus = "busy" }, code: "workspace_busy"},
		{name: "token mismatch", edit: func(_ *workspaceState, p *writeWorkspaceTextFilePayload) { p.ExpectedQuiescenceToken = "old" }, code: "invalid_request"},
		{name: "replace revision required", edit: func(_ *workspaceState, p *writeWorkspaceTextFilePayload) { p.ExpectedRevision = "" }, code: "invalid_request"},
		{name: "revision conflict", edit: func(_ *workspaceState, p *writeWorkspaceTextFilePayload) { p.ExpectedRevision = "OLD" }, code: "workspace_revision_conflict"},
		{name: "not editable", edit: func(w *workspaceState, _ *writeWorkspaceTextFilePayload) { w.OpenFile.Entry.TextEditable = false }, code: "workspace_entry_type_unsupported"},
		{name: "condition", edit: func(_ *workspaceState, p *writeWorkspaceTextFilePayload) { p.Condition = "unspecified" }, code: "invalid_request"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			workspace, payload := base(), valid
			test.edit(workspace, &payload)
			err := validateWorkspaceWriteLocked(workspace, payload)
			var operationError *workspaceOperationError
			if !errors.As(err, &operationError) || operationError.Code != test.code {
				t.Fatalf("error=%T %v, want code %q", err, err, test.code)
			}
		})
	}
}

func TestWorkspaceWriteRefreshFailureExposesCommittedFileAndClearsAccess(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.getErrors = []error{errors.New("refresh unavailable")}
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"
	c.state.Workspace = &workspaceState{
		Supported: true, Limits: &sess.limits, CodexID: "C", WorkspaceRoot: "/work", Loading: "none",
		AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1},
		OpenFile:    &workspaceOpenFile{Entry: workspaceEntry{RelativePath: "a.txt", Kind: "regular_file", Revision: "R1", TextEditable: true}, UTF8Text: "old"},
	}
	c.Dispatch(`{"version":1,"id":"write","type":"write_workspace_text_file","payload":{"codexId":"C","relativePath":"a.txt","utf8Text":"committed","condition":"replace_only","expectedRevision":"R1","expectedQuiescenceToken":"Q1"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
	got := decodeState(t, c.State()).Workspace
	if got.Error.Code != "operation_failed" || !strings.Contains(got.Error.Message, "write committed") || got.AccessState != nil || got.LastWrite == nil || got.LastWrite.Entry.Revision != "R2" || got.OpenFile == nil || got.OpenFile.UTF8Text != "committed" || got.OpenFile.Entry.Revision != "R2" {
		t.Fatalf("partial write state=%+v", got)
	}
	sess.mu.Lock()
	writeCalls, getCalls := sess.writeCalls, sess.getCalls
	sess.mu.Unlock()
	if writeCalls != 1 || getCalls != 1 {
		t.Fatalf("write/refresh calls=(%d,%d), want (1,1)", writeCalls, getCalls)
	}
	blocked := decodeState(t, c.Dispatch(`{"version":1,"id":"write-again","type":"write_workspace_text_file","payload":{"codexId":"C","relativePath":"a.txt","utf8Text":"again","condition":"replace_only","expectedRevision":"R2","expectedQuiescenceToken":"Q1"}}`))
	if blocked.Workspace.Error == nil || blocked.Workspace.Error.Code != "workspace_busy" {
		t.Fatalf("old token remained usable after partial write: %+v", blocked.Workspace)
	}
}

func TestWorkspaceWriteRejectsStaleRefreshedGeneration(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.getResults = []workspaceDescriptor{{WorkspaceRoot: "/stale", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "OLD", Generation: 5}}}
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"
	c.state.Workspace = &workspaceState{
		Supported: true, Limits: &sess.limits, CodexID: "C", WorkspaceRoot: "/current", Loading: "none",
		AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q5", Generation: 5},
		OpenFile:    &workspaceOpenFile{Entry: workspaceEntry{RelativePath: "a.txt", Kind: "regular_file", Revision: "R1", TextEditable: true}, UTF8Text: "old"},
	}
	c.Dispatch(`{"version":1,"id":"write","type":"write_workspace_text_file","payload":{"codexId":"C","relativePath":"a.txt","utf8Text":"committed","condition":"replace_only","expectedRevision":"R1","expectedQuiescenceToken":"Q5"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
	got := decodeState(t, c.State()).Workspace
	if got.Error.Code != "operation_failed" || !strings.Contains(got.Error.Message, "did not advance access-state generation") || got.AccessState != nil || got.WorkspaceRoot != "/current" || got.OpenFile == nil || got.OpenFile.Entry.Revision != "R2" {
		t.Fatalf("equal refreshed generation was not rejected: %+v", got)
	}
}

func TestWorkspaceNewRequestAndAccessGenerationRejectStaleResults(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.getResults = []workspaceDescriptor{
		{WorkspaceRoot: "/old-response", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1}},
		{WorkspaceRoot: "/new-response", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q2", Generation: 2}},
	}
	sess.blockFirstGet = true
	sess.getStarted, sess.getRelease = make(chan struct{}), make(chan struct{})
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"
	c.Dispatch(`{"version":1,"id":"first","type":"get_workspace","payload":{"codexId":"C"}}`)
	select {
	case <-sess.getStarted:
	case <-time.After(time.Second):
		t.Fatal("first GetWorkspace did not start")
	}
	c.Dispatch(`{"version":1,"id":"second","type":"get_workspace","payload":{"codexId":"C"}}`)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && workspace.WorkspaceRoot == "/new-response"
	})
	close(sess.getRelease)
	time.Sleep(10 * time.Millisecond)
	got := decodeState(t, c.State())
	if got.Workspace.WorkspaceRoot != "/new-response" || got.Workspace.AccessState.Generation != 2 {
		t.Fatalf("stale request overwrote state: %+v", got.Workspace)
	}

	sess2 := newWorkspaceFakeSession()
	sess2.getResults = []workspaceDescriptor{{WorkspaceRoot: "/stale", AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q8", Generation: 8}}}
	c2 := NewCore(nil)
	c2.session = sess2
	c2.state.Phase = "ready"
	c2.state.Workspace = &workspaceState{
		Supported: true, Limits: &sess2.limits, CodexID: "C", WorkspaceRoot: "/current", Loading: "none",
		AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q9", Generation: 9},
	}
	c2.Dispatch(`{"version":1,"id":"stale-generation","type":"get_workspace","payload":{"codexId":"C"}}`)
	waitWorkspace(t, c2, func(workspace *workspaceState) bool { return workspace.Loading == "none" && workspace.Error != nil })
	got = decodeState(t, c2.State())
	if got.Workspace.WorkspaceRoot != "/current" || got.Workspace.AccessState.Generation != 9 || got.Workspace.Error.Code != "operation_failed" {
		t.Fatalf("stale generation overwrote state: %+v", got.Workspace)
	}
}

func TestWorkspaceAndConversationPollRemainIndependent(t *testing.T) {
	workspaceFake := newWorkspaceFakeSession()
	workspaceFake.listStarted, workspaceFake.listRelease = make(chan struct{}), make(chan struct{})
	sess := &workspaceConversationSession{workspaceFakeSession: workspaceFake, historyStarted: make(chan struct{}), historyRelease: make(chan struct{})}
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"
	c.state.SelectedCodexID = "C"
	c.state.Conversation = &conversationState{CodexID: "C", Turns: []conversationTurn{}}
	c.state.Workspace = &workspaceState{
		Supported: true, Limits: &workspaceFake.limits, CodexID: "C", WorkspaceRoot: "/work", Loading: "none",
		AccessState: &workspaceAccessState{MutationStatus: "allowed", QuiescenceToken: "Q1", Generation: 1},
	}
	c.Dispatch(`{"version":1,"id":"turn","type":"start_turn","payload":{"text":"hello"}}`)
	select {
	case <-sess.historyStarted:
	case <-time.After(time.Second):
		t.Fatal("conversation poll did not start")
	}
	c.Dispatch(`{"version":1,"id":"workspace","type":"list_workspace_entries","payload":{"codexId":"C"}}`)
	select {
	case <-workspaceFake.listStarted:
	case <-time.After(time.Second):
		t.Fatal("workspace list did not start beside conversation poll")
	}
	close(sess.historyRelease)
	waitState(t, c, func(current state) bool { return strings.Contains(current.Error, "conversation poll failed") })
	close(workspaceFake.listRelease)
	waitWorkspace(t, c, func(workspace *workspaceState) bool {
		return workspace.Loading == "none" && len(workspace.CurrentDirectory.Entries) == 1
	})
	got := decodeState(t, c.State())
	if !strings.Contains(got.Error, "conversation poll failed") || got.Workspace.Error != nil || got.Conversation.Running {
		t.Fatalf("workspace/conversation states interfered: error=%q workspace=%+v conversation=%+v", got.Error, got.Workspace, got.Conversation)
	}
}

func TestStopCancelsWorkspaceAndPreventsLatePublish(t *testing.T) {
	sess := newWorkspaceFakeSession()
	sess.listStarted, sess.listRelease, sess.listCanceled = make(chan struct{}), make(chan struct{}), make(chan struct{})
	c := NewCore(nil)
	c.session = sess
	c.state.Phase = "ready"
	c.state.Workspace = &workspaceState{Supported: true, Limits: &sess.limits, CodexID: "C", Loading: "none"}
	c.Dispatch(`{"version":1,"id":"list","type":"list_workspace_entries","payload":{"codexId":"C"}}`)
	select {
	case <-sess.listStarted:
	case <-time.After(time.Second):
		t.Fatal("workspace list did not start")
	}
	stopped := decodeState(t, c.Dispatch(`{"version":1,"id":"stop","type":"stop"}`))
	if stopped.Workspace != nil || stopped.Phase != "stopped" {
		t.Fatalf("stop state=%+v", stopped)
	}
	select {
	case <-sess.listCanceled:
	case <-time.After(time.Second):
		t.Fatal("stop did not cancel workspace request")
	}
	time.Sleep(10 * time.Millisecond)
	if got := decodeState(t, c.State()); got.Workspace != nil {
		t.Fatalf("late workspace result republished after stop: %+v", got.Workspace)
	}
}

func waitWorkspace(t *testing.T, c *Core, condition func(*workspaceState) bool) {
	t.Helper()
	waitState(t, c, func(current state) bool { return current.Workspace != nil && condition(current.Workspace) })
}

func waitState(t *testing.T, c *Core, condition func(state) bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		current := decodeState(t, c.State())
		if condition(current) {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for state: %s", c.State())
}

func waitConversationStatus(t *testing.T, c *Core, turnID, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		got := decodeState(t, c.State())
		if got.Conversation != nil {
			for _, turn := range got.Conversation.Turns {
				if turn.TurnID == turnID && turn.Status == want {
					return
				}
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for turn %s status %s: %s", turnID, want, c.State())
}

func waitInterrupt(t *testing.T, sess *conversationFakeSession, want string) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		sess.mu.Lock()
		got := sess.interruptTurnID
		sess.mu.Unlock()
		if got == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for interrupt %q", want)
}

func waitPhase(t *testing.T, c *Core, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if got := decodeState(t, c.State()); got.Phase == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for phase %q: %s", want, c.State())
}

func waitCommandPhase(t *testing.T, c *Core, commandID, phase string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		got := c.state
		c.mu.Unlock()
		if got.CommandID == commandID && got.Phase == phase {
			return
		}
		time.Sleep(time.Millisecond)
	}
	c.mu.Lock()
	got := c.state
	c.mu.Unlock()
	t.Fatalf("command %q did not reach phase %q: %+v", commandID, phase, got)
}
