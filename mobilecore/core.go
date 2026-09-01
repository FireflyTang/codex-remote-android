// Package mobilecore is the narrow gomobile boundary for the Android app.
// Kotlin sends versioned JSON commands and receives versioned JSON state.
package mobilecore

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"path"
	"strings"
	"sync"
	"time"
)

const (
	APIVersion            = 1
	ProtocolVersion       = "1.1.2"
	WebSocketSubprotocol  = "codex-remote.v1.protojson"
	protocolModuleVersion = "v1.1.2"
)

// Platform is implemented by Kotlin. It contains only the two Android
// facilities that Go cannot obtain reliably inside the app sandbox, plus the
// state callback. It deliberately does not require VpnService.
type Platform interface {
	InterfacesJSON() string
	BindSocketToNetwork(fd int32) bool
	OnState(stateJSON string)
}

type command struct {
	Version int             `json:"version"`
	ID      string          `json:"id,omitempty"`
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type configPayload struct {
	Hostname      string `json:"hostname"`
	StateDir      string `json:"stateDir"`
	AuthKey       string `json:"authKey,omitempty"`
	HostEndpoint  string `json:"hostEndpoint"`
	ClientID      string `json:"clientId"`
	ClientRunID   string `json:"clientRunId"`
	ClientName    string `json:"clientName,omitempty"`
	ClientVersion string `json:"clientVersion,omitempty"`
	TimeoutMS     int64  `json:"timeoutMs,omitempty"`
}

type networkPayload struct {
	DefaultInterface string `json:"defaultInterface,omitempty"`
	DefaultGateway   string `json:"defaultGateway,omitempty"`
}

type selectCodexPayload struct {
	CodexID string `json:"codexId"`
}

type listDirectoriesPayload struct {
	ParentPath string `json:"parentPath"`
}
type listSessionCandidatesPayload struct {
	Cwd string `json:"cwd"`
}
type createCodexPayload struct {
	Cwd                      string `json:"cwd"`
	CreateDirectoryIfMissing bool   `json:"createDirectoryIfMissing"`
	Title                    string `json:"title,omitempty"`
}
type importSessionPayload struct {
	SessionID string `json:"sessionId"`
	Source    string `json:"source"`
}
type renameCodexPayload struct {
	CodexID string `json:"codexId"`
	Title   string `json:"title"`
}
type codexIDPayload struct {
	CodexID string `json:"codexId"`
}

type listWorkspaceEntriesPayload struct {
	CodexID           string `json:"codexId"`
	RelativeDirectory string `json:"relativeDirectory,omitempty"`
}

type workspacePathPayload struct {
	CodexID      string `json:"codexId"`
	RelativePath string `json:"relativePath"`
}

type writeWorkspaceTextFilePayload struct {
	CodexID                 string `json:"codexId"`
	RelativePath            string `json:"relativePath"`
	UTF8Text                string `json:"utf8Text"`
	Condition               string `json:"condition"`
	ExpectedRevision        string `json:"expectedRevision,omitempty"`
	ExpectedQuiescenceToken string `json:"expectedQuiescenceToken"`
}

type uploadWorkspaceEntryPayload struct {
	CodexID                 string `json:"codexId"`
	DestinationPath         string `json:"destinationPath"`
	Kind                    string `json:"kind"`
	ContentBase64           string `json:"contentBase64"`
	ExpectedQuiescenceToken string `json:"expectedQuiescenceToken"`
}

type workspaceUploadRequest struct {
	CodexID                 string
	DestinationPath         string
	Kind                    string
	Content                 []byte
	ExpectedQuiescenceToken string
}

type directoryEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}
type directoryListing struct {
	ParentPath  string           `json:"parentPath"`
	Directories []directoryEntry `json:"directories"`
}
type sessionCandidate struct {
	SessionID      string `json:"sessionId"`
	Cwd            string `json:"cwd"`
	Title          string `json:"title"`
	Preview        string `json:"preview"`
	Source         string `json:"source"`
	Availability   string `json:"availability"`
	ManagedCodexID string `json:"managedCodexId,omitempty"`
}
type sessionCandidatesState struct {
	NormalizedCwd string             `json:"normalizedCwd"`
	Sessions      []sessionCandidate `json:"sessions"`
}

type workspaceLimits struct {
	MaxTextFileBytes        uint64 `json:"maxTextFileBytes"`
	MaxInlineUploadBytes    uint64 `json:"maxInlineUploadBytes"`
	MaxInlineDownloadBytes  uint64 `json:"maxInlineDownloadBytes"`
	MaxArchiveExpandedBytes uint64 `json:"maxArchiveExpandedBytes"`
	MaxArchiveEntryCount    uint32 `json:"maxArchiveEntryCount"`
}

type workspaceAccessState struct {
	MutationStatus   string `json:"mutationStatus"`
	ActiveAgentCount uint32 `json:"activeAgentCount"`
	QuiescenceToken  string `json:"quiescenceToken"`
	ObservedAtUnixMS int64  `json:"observedAtUnixMs"`
	Generation       uint64 `json:"generation"`
}

type workspaceEntry struct {
	RelativePath     string `json:"relativePath"`
	Name             string `json:"name"`
	Kind             string `json:"kind"`
	SizeBytes        uint64 `json:"sizeBytes"`
	ModifiedAtUnixMS int64  `json:"modifiedAtUnixMs"`
	Revision         string `json:"revision"`
	TextViewable     bool   `json:"textViewable"`
	TextEditable     bool   `json:"textEditable"`
}

type workspaceDirectory struct {
	RelativeDirectory string           `json:"relativeDirectory"`
	Entries           []workspaceEntry `json:"entries"`
}

type workspaceOpenFile struct {
	Entry    workspaceEntry `json:"entry"`
	UTF8Text string         `json:"utf8Text"`
}

type workspaceWriteResult struct {
	Entry        workspaceEntry `json:"entry"`
	Deduplicated bool           `json:"deduplicated"`
}

type workspaceUploadResult struct {
	Entry        workspaceEntry `json:"entry"`
	Deduplicated bool           `json:"deduplicated"`
}

type workspaceDownloadResult struct {
	Entry         workspaceEntry `json:"entry"`
	Kind          string         `json:"kind"`
	Filename      string         `json:"filename"`
	ContentBase64 string         `json:"contentBase64"`
}

type workspaceStateError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type workspaceState struct {
	Supported        bool                     `json:"supported"`
	Limits           *workspaceLimits         `json:"limits,omitempty"`
	CodexID          string                   `json:"codexId"`
	WorkspaceRoot    string                   `json:"workspaceRoot"`
	AccessState      *workspaceAccessState    `json:"accessState,omitempty"`
	CurrentDirectory *workspaceDirectory      `json:"currentDirectory,omitempty"`
	OpenFile         *workspaceOpenFile       `json:"openFile,omitempty"`
	LastWrite        *workspaceWriteResult    `json:"lastWrite,omitempty"`
	UploadResult     *workspaceUploadResult   `json:"uploadResult,omitempty"`
	DownloadResult   *workspaceDownloadResult `json:"downloadResult,omitempty"`
	Loading          string                   `json:"loading"`
	Error            *workspaceStateError     `json:"error,omitempty"`
}

type workspaceDescriptor struct {
	WorkspaceRoot string
	AccessState   *workspaceAccessState
}

type workspaceOperationError struct {
	Code    string
	Message string
}

func (e *workspaceOperationError) Error() string { return e.Message }

func newWorkspaceOperationError(code, message string) error {
	return &workspaceOperationError{Code: code, Message: message}
}

func workspaceStateErrorFrom(err error) *workspaceStateError {
	if err == nil {
		return nil
	}
	var operationError *workspaceOperationError
	if errors.As(err, &operationError) {
		return &workspaceStateError{Code: operationError.Code, Message: operationError.Message}
	}
	return &workspaceStateError{Code: "operation_failed", Message: err.Error()}
}

type turnOptionsPayload struct {
	Model           string `json:"model,omitempty"`
	Mode            string `json:"mode,omitempty"`
	ApprovalPolicy  string `json:"approvalPolicy,omitempty"`
	ReasoningEffort string `json:"reasoningEffort,omitempty"`
}

type startTurnPayload struct {
	Text    string              `json:"text"`
	Options *turnOptionsPayload `json:"options,omitempty"`
}

type interruptTurnPayload struct {
	TurnID string `json:"turnId,omitempty"`
}

type respondApprovalPayload struct {
	ApprovalID string `json:"approvalId"`
	Decision   string `json:"decision"`
}

type pendingUserInputAnswer struct {
	QuestionID        string   `json:"questionId"`
	SelectedOptionIDs []string `json:"selectedOptionIds"`
	FreeFormText      string   `json:"freeFormText"`
}

type respondUserInputPayload struct {
	RequestID string                   `json:"requestId"`
	Answers   []pendingUserInputAnswer `json:"answers"`
}

type conversationMessage struct {
	ItemID string `json:"itemId"`
	Role   string `json:"role"`
	Text   string `json:"text"`
	Status string `json:"status"`
}

type pendingRequestError struct {
	CommandID string `json:"commandId,omitempty"`
	Code      string `json:"code"`
	Message   string `json:"message"`
}

type pendingApproval struct {
	Kind             string   `json:"kind"`
	Status           string   `json:"status"`
	Title            string   `json:"title"`
	Explanation      string   `json:"explanation"`
	Command          []string `json:"command"`
	AllowedDecisions []string `json:"allowedDecisions"`
}

type pendingUserInputOption struct {
	OptionID    string `json:"optionId"`
	Label       string `json:"label"`
	Description string `json:"description"`
}

type pendingUserInputQuestion struct {
	QuestionID     string                   `json:"questionId"`
	Header         string                   `json:"header"`
	Prompt         string                   `json:"prompt"`
	Options        []pendingUserInputOption `json:"options"`
	AllowsMultiple bool                     `json:"allowsMultiple"`
	AllowsFreeForm bool                     `json:"allowsFreeForm"`
}

type pendingUserInput struct {
	Resolved     bool                       `json:"resolved"`
	Questions    []pendingUserInputQuestion `json:"questions"`
	Completeness *conversationCompleteness  `json:"completeness,omitempty"`
}

type pendingRequest struct {
	Type      string               `json:"type"`
	RequestID string               `json:"requestId"`
	TurnID    string               `json:"turnId"`
	ItemID    string               `json:"itemId"`
	InFlight  bool                 `json:"inFlight"`
	Error     *pendingRequestError `json:"error,omitempty"`
	Approval  *pendingApproval     `json:"approval,omitempty"`
	UserInput *pendingUserInput    `json:"userInput,omitempty"`
}

type pendingWatchState struct {
	State        string               `json:"state"`
	HeadEventSeq uint64               `json:"headEventSeq"`
	Error        *pendingRequestError `json:"error,omitempty"`
}

type pendingLocalState struct {
	InFlight bool
	Error    *pendingRequestError
}

type conversationCompleteness struct {
	Truncated         bool   `json:"truncated"`
	Incomplete        bool   `json:"incomplete"`
	OriginalSizeBytes uint64 `json:"originalSizeBytes"`
	Reason            string `json:"reason"`
}

type conversationUserMessage struct {
	TextParts []string `json:"textParts"`
	Text      string   `json:"text"`
}

type conversationAgentMessage struct {
	Text string `json:"text"`
}

type conversationReasoningSummary struct {
	Text string `json:"text"`
}

type conversationPlanStep struct {
	Text   string `json:"text"`
	Status string `json:"status"`
}

type conversationPlan struct {
	Steps []conversationPlanStep `json:"steps"`
}

type conversationCommandItem struct {
	Argv        []string `json:"argv"`
	Cwd         string   `json:"cwd"`
	Output      string   `json:"output"`
	HasExitCode bool     `json:"hasExitCode"`
	ExitCode    *int32   `json:"exitCode,omitempty"`
}

type conversationTool struct {
	Name          string `json:"name"`
	Summary       string `json:"summary"`
	ResultSummary string `json:"resultSummary"`
}

type conversationFileChangeEntry struct {
	Path    string `json:"path"`
	Kind    string `json:"kind"`
	OldPath string `json:"oldPath"`
	NewPath string `json:"newPath"`
}

type conversationFileChange struct {
	Changes     []conversationFileChangeEntry `json:"changes"`
	UnifiedDiff string                        `json:"unifiedDiff"`
}

// conversationItem is deliberately an app-owned tagged DTO rather than
// protobuf JSON. Exactly one typed payload is populated for known item types;
// unknown items carry only the common fields.
type conversationItem struct {
	ItemID           string                        `json:"itemId"`
	TurnID           string                        `json:"turnId,omitempty"`
	Type             string                        `json:"type"`
	Status           string                        `json:"status"`
	Completeness     *conversationCompleteness     `json:"completeness,omitempty"`
	UserMessage      *conversationUserMessage      `json:"userMessage,omitempty"`
	AgentMessage     *conversationAgentMessage     `json:"agentMessage,omitempty"`
	ReasoningSummary *conversationReasoningSummary `json:"reasoningSummary,omitempty"`
	Plan             *conversationPlan             `json:"plan,omitempty"`
	Command          *conversationCommandItem      `json:"command,omitempty"`
	Tool             *conversationTool             `json:"tool,omitempty"`
	FileChange       *conversationFileChange       `json:"fileChange,omitempty"`
}

type conversationTurn struct {
	TurnID            string                `json:"turnId"`
	Status            string                `json:"status"`
	StartedAtUnixMS   int64                 `json:"startedAtUnixMs"`
	CompletedAtUnixMS int64                 `json:"completedAtUnixMs"`
	Failure           string                `json:"failure,omitempty"`
	Items             []conversationItem    `json:"items"`
	Messages          []conversationMessage `json:"messages"`
}

type conversationState struct {
	CodexID         string             `json:"codexId"`
	ActiveTurnID    string             `json:"activeTurnId,omitempty"`
	Running         bool               `json:"running"`
	HistoryComplete bool               `json:"historyComplete"`
	Turns           []conversationTurn `json:"turns"`
	PendingRequests []pendingRequest   `json:"pendingRequests"`
	PendingWatch    pendingWatchState  `json:"pendingWatch"`
}

type protocolState struct {
	WireVersion   string `json:"wireVersion"`
	Subprotocol   string `json:"subprotocol"`
	ModuleVersion string `json:"moduleVersion"`
}

type state struct {
	Version           int                     `json:"version"`
	Revision          uint64                  `json:"revision"`
	CommandID         string                  `json:"commandId,omitempty"`
	Phase             string                  `json:"phase"`
	AuthURL           string                  `json:"authUrl,omitempty"`
	Error             string                  `json:"error,omitempty"`
	Endpoint          string                  `json:"endpoint,omitempty"`
	TailnetIPs        []string                `json:"tailnetIps,omitempty"`
	ServerHello       json.RawMessage         `json:"serverHello,omitempty"`
	Host              json.RawMessage         `json:"host,omitempty"`
	Codexes           json.RawMessage         `json:"codexes,omitempty"`
	DirectoryListing  *directoryListing       `json:"directoryListing,omitempty"`
	SessionCandidates *sessionCandidatesState `json:"sessionCandidates,omitempty"`
	SelectedCodexID   string                  `json:"selectedCodexId,omitempty"`
	Conversation      *conversationState      `json:"conversation,omitempty"`
	Workspace         *workspaceState         `json:"workspace,omitempty"`
	Protocol          protocolState           `json:"protocol"`
}

type session interface {
	Refresh(context.Context) (snapshot, error)
	ListHistory(context.Context, string) (conversationState, error)
	StartTurn(context.Context, string, string, *turnOptionsPayload) (string, error)
	InterruptTurn(context.Context, string, string) (string, error)
	ListDirectories(context.Context, string) (directoryListing, error)
	ListSessionCandidates(context.Context, string) (sessionCandidatesState, error)
	CreateCodex(context.Context, createCodexPayload) (string, error)
	ImportSession(context.Context, importSessionPayload) (string, error)
	RenameCodex(context.Context, renameCodexPayload) error
	UnmanageCodex(context.Context, string) error
	ForgetCodex(context.Context, string) error
	Close() error
}

type starter interface {
	Start(context.Context, configPayload, func(phase, authURL string)) (session, snapshot, error)
}

type workspaceSession interface {
	WorkspaceSupport() (workspaceLimits, bool, error)
	GetWorkspace(context.Context, string) (workspaceDescriptor, error)
	ListWorkspaceEntries(context.Context, string, string) (workspaceDirectory, error)
	ReadWorkspaceTextFile(context.Context, string, string) (workspaceOpenFile, error)
	WriteWorkspaceTextFile(context.Context, writeWorkspaceTextFilePayload) (workspaceWriteResult, error)
	UploadWorkspaceEntry(context.Context, workspaceUploadRequest) (workspaceUploadResult, error)
	DownloadWorkspaceEntry(context.Context, string, string) (workspaceDownloadResult, error)
}

type pendingSession interface {
	WatchPending(context.Context, string) (pendingWatchReset, *protocolPendingWatch, error)
	UnwatchPending(context.Context, *protocolPendingWatch) error
	RespondApproval(context.Context, string, string, string) (pendingResponseResult, error)
	RespondUserInput(context.Context, string, string, []pendingUserInputAnswer) (pendingResponseResult, error)
}

type stateNotification struct {
	revision uint64
	json     string
}

// stateNotifier keeps Kotlin callbacks serialized without holding Core.mu.
// The revision gate also makes explicitly stale work harmless if notifications
// are ever enqueued out of order by a future producer.
type stateNotifier struct {
	mu            sync.Mutex
	platform      Platform
	queue         []stateNotification
	running       bool
	lastDelivered uint64
}

func (n *stateNotifier) enqueue(notification stateNotification) {
	if n == nil || n.platform == nil {
		return
	}
	n.mu.Lock()
	n.queue = append(n.queue, notification)
	if n.running {
		n.mu.Unlock()
		return
	}
	n.running = true
	n.mu.Unlock()
	go n.run()
}

func (n *stateNotifier) run() {
	for {
		n.mu.Lock()
		if len(n.queue) == 0 {
			n.running = false
			n.mu.Unlock()
			return
		}
		notification := n.queue[0]
		n.queue = n.queue[1:]
		if notification.revision <= n.lastDelivered {
			n.mu.Unlock()
			continue
		}
		n.lastDelivered = notification.revision
		n.mu.Unlock()
		n.platform.OnState(notification.json)
	}
}

// Core is safe for calls from Android lifecycle and UI threads.
type Core struct {
	mu                      sync.Mutex
	platform                Platform
	starter                 starter
	config                  *configPayload
	state                   state
	cancel                  context.CancelFunc
	pollCancel              context.CancelFunc
	session                 session
	runID                   uint64
	conversationRunID       uint64
	conversationPollTimeout time.Duration
	interruptTurnID         string
	workspaceCancel         context.CancelFunc
	workspaceRunID          uint64
	workspaceUploadInFlight bool
	pendingWatchCancel      context.CancelFunc
	pendingWatchDone        chan struct{}
	pendingWatchRunID       uint64
	pendingResponseCancels  map[string]context.CancelFunc
	pendingRequestLocal     map[string]pendingLocalState
	notifier                *stateNotifier
}

// NewCore constructs a stopped core. Network activity starts only after a
// configure command followed by start.
func NewCore(platform Platform) *Core {
	c := &Core{platform: platform, starter: productionStarter{platform: platform}, conversationPollTimeout: 10 * time.Minute, pendingResponseCancels: map[string]context.CancelFunc{}, pendingRequestLocal: map[string]pendingLocalState{}, notifier: &stateNotifier{platform: platform}}
	c.state = state{Version: APIVersion, Phase: "idle", Protocol: protocolState{
		WireVersion: ProtocolVersion, Subprotocol: WebSocketSubprotocol, ModuleVersion: protocolModuleVersion,
	}}
	return c
}

// Dispatch applies one JSON command and returns the latest JSON state. start
// and refresh are asynchronous; subsequent state is delivered via OnState.
func (c *Core) Dispatch(commandJSON string) string {
	var cmd command
	if err := json.Unmarshal([]byte(commandJSON), &cmd); err != nil {
		return c.reject("", fmt.Errorf("invalid command JSON: %w", err))
	}
	if cmd.Version != APIVersion {
		return c.reject(cmd.ID, fmt.Errorf("unsupported command version %d", cmd.Version))
	}
	switch cmd.Type {
	case "configure":
		var cfg configPayload
		if err := json.Unmarshal(cmd.Payload, &cfg); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid configure payload: %w", err))
		}
		if err := validateConfig(&cfg); err != nil {
			return c.reject(cmd.ID, err)
		}
		c.mu.Lock()
		if c.cancel != nil || c.session != nil {
			c.mu.Unlock()
			return c.reject(cmd.ID, errors.New("stop the core before reconfiguring"))
		}
		c.config = &cfg
		c.state.CommandID, c.state.Phase, c.state.Error = cmd.ID, "configured", ""
		c.state.Endpoint = cfg.HostEndpoint
		out := c.publishLocked()
		c.mu.Unlock()
		return out
	case "start":
		return c.start(cmd.ID)
	case "refresh":
		return c.refresh(cmd.ID)
	case "list_directories":
		var p listDirectoriesPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid list_directories payload: %w", err))
		}
		if strings.TrimSpace(p.ParentPath) == "" {
			return c.reject(cmd.ID, errors.New("list_directories parentPath is required"))
		}
		return c.listDirectories(cmd.ID, p.ParentPath)
	case "list_session_candidates":
		var p listSessionCandidatesPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid list_session_candidates payload: %w", err))
		}
		if strings.TrimSpace(p.Cwd) == "" {
			return c.reject(cmd.ID, errors.New("list_session_candidates cwd is required"))
		}
		return c.listSessionCandidates(cmd.ID, p.Cwd)
	case "create_codex":
		var p createCodexPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid create_codex payload: %w", err))
		}
		if strings.TrimSpace(p.Cwd) == "" {
			return c.reject(cmd.ID, errors.New("create_codex cwd is required"))
		}
		return c.createCodex(cmd.ID, p)
	case "import_session":
		var p importSessionPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid import_session payload: %w", err))
		}
		if strings.TrimSpace(p.SessionID) == "" || strings.TrimSpace(p.Source) == "" {
			return c.reject(cmd.ID, errors.New("import_session sessionId and source are required"))
		}
		return c.importSession(cmd.ID, p)
	case "rename_codex":
		var p renameCodexPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid rename_codex payload: %w", err))
		}
		if strings.TrimSpace(p.CodexID) == "" || strings.TrimSpace(p.Title) == "" {
			return c.reject(cmd.ID, errors.New("rename_codex codexId and title are required"))
		}
		return c.codexOperation(cmd.ID, "renaming_codex", p.CodexID, false, func(ctx context.Context, sess session) (string, error) { return "", sess.RenameCodex(ctx, p) })
	case "unmanage_codex":
		var p codexIDPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid unmanage_codex payload: %w", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.reject(cmd.ID, errors.New("unmanage_codex codexId is required"))
		}
		return c.codexOperation(cmd.ID, "unmanaging_codex", p.CodexID, true, func(ctx context.Context, sess session) (string, error) { return "", sess.UnmanageCodex(ctx, p.CodexID) })
	case "forget_codex":
		var p codexIDPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid forget_codex payload: %w", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.reject(cmd.ID, errors.New("forget_codex codexId is required"))
		}
		return c.codexOperation(cmd.ID, "forgetting_codex", p.CodexID, true, func(ctx context.Context, sess session) (string, error) { return "", sess.ForgetCodex(ctx, p.CodexID) })
	case "get_workspace":
		var p codexIDPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid get_workspace payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "get_workspace codexId is required")
		}
		return c.getWorkspace(cmd.ID, p.CodexID)
	case "list_workspace_entries":
		var p listWorkspaceEntriesPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid list_workspace_entries payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "list_workspace_entries codexId is required")
		}
		return c.listWorkspaceEntries(cmd.ID, p)
	case "read_workspace_text_file":
		var p workspacePathPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid read_workspace_text_file payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" || p.RelativePath == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "read_workspace_text_file codexId and relativePath are required")
		}
		return c.readWorkspaceTextFile(cmd.ID, p)
	case "write_workspace_text_file":
		var p writeWorkspaceTextFilePayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid write_workspace_text_file payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" || p.RelativePath == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "write_workspace_text_file codexId and relativePath are required")
		}
		return c.writeWorkspaceTextFile(cmd.ID, p)
	case "upload_workspace_entry":
		var p uploadWorkspaceEntryPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid upload_workspace_entry payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "upload_workspace_entry codexId is required")
		}
		return c.uploadWorkspaceEntry(cmd.ID, p)
	case "download_workspace_entry":
		var p workspacePathPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.workspaceReject(cmd.ID, "", "invalid_request", fmt.Sprintf("invalid download_workspace_entry payload: %v", err))
		}
		if strings.TrimSpace(p.CodexID) == "" {
			return c.workspaceReject(cmd.ID, p.CodexID, "invalid_request", "download_workspace_entry codexId is required")
		}
		return c.downloadWorkspaceEntry(cmd.ID, p)
	case "select_codex":
		var p selectCodexPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid select_codex payload: %w", err))
		}
		return c.selectCodex(cmd.ID, p.CodexID)
	case "refresh_conversation":
		return c.refreshConversation(cmd.ID)
	case "start_turn":
		var p startTurnPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid start_turn payload: %w", err))
		}
		return c.startTurn(cmd.ID, p)
	case "interrupt_turn":
		var p interruptTurnPayload
		if len(cmd.Payload) > 0 {
			if err := json.Unmarshal(cmd.Payload, &p); err != nil {
				return c.reject(cmd.ID, fmt.Errorf("invalid interrupt_turn payload: %w", err))
			}
		}
		return c.interruptTurn(cmd.ID, p.TurnID)
	case "respond_approval":
		var p respondApprovalPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid respond_approval payload: %w", err))
		}
		return c.respondApproval(cmd.ID, p)
	case "respond_user_input":
		var p respondUserInputPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid respond_user_input payload: %w", err))
		}
		return c.respondUserInput(cmd.ID, p)
	case "network_changed":
		var p networkPayload
		if err := json.Unmarshal(cmd.Payload, &p); err != nil {
			return c.reject(cmd.ID, fmt.Errorf("invalid network_changed payload: %w", err))
		}
		platformNetworkChanged(p.DefaultInterface, p.DefaultGateway)
		return c.current(cmd.ID)
	case "stop":
		return c.stop(cmd.ID)
	case "get_state":
		return c.current(cmd.ID)
	default:
		return c.reject(cmd.ID, fmt.Errorf("unsupported command type %q", cmd.Type))
	}
}

// State returns the latest state without changing it.
func (c *Core) State() string { return c.current("") }

// Close is an idempotent lifecycle convenience equivalent to stop.
func (c *Core) Close() string { return c.stop("") }

func validateConfig(cfg *configPayload) error {
	if cfg.Hostname == "" || cfg.StateDir == "" || cfg.HostEndpoint == "" || cfg.ClientID == "" || cfg.ClientRunID == "" {
		return errors.New("hostname, stateDir, hostEndpoint, clientId, and clientRunId are required")
	}
	if cfg.ClientName == "" {
		cfg.ClientName = "codex-remote-android"
	}
	if cfg.ClientVersion == "" {
		cfg.ClientVersion = "dev"
	}
	if cfg.TimeoutMS == 0 {
		cfg.TimeoutMS = 120_000
	}
	if cfg.TimeoutMS < 1_000 || cfg.TimeoutMS > 600_000 {
		return errors.New("timeoutMs must be between 1000 and 600000")
	}
	return nil
}

func (c *Core) start(commandID string) string {
	c.mu.Lock()
	if c.config == nil {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("configure is required before start"))
	}
	if c.cancel != nil || c.session != nil {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("core is already running"))
	}
	cfg := *c.config
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(cfg.TimeoutMS)*time.Millisecond)
	c.cancel = cancel
	c.runID++
	runID := c.runID
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, "starting_tailnet", ""
	out := c.publishLocked()
	c.mu.Unlock()

	go func() {
		sess, snap, err := c.starter.Start(ctx, cfg, func(phase, authURL string) {
			c.updateProgress(runID, commandID, phase, authURL)
		})
		c.finishStart(runID, commandID, sess, snap, err)
	}()
	return out
}

func (c *Core) finishStart(runID uint64, commandID string, sess session, snap snapshot, err error) {
	c.mu.Lock()
	if c.runID != runID || c.cancel == nil {
		c.mu.Unlock()
		if sess != nil {
			_ = sess.Close()
		}
		return
	}
	c.cancel()
	c.cancel = nil
	if err != nil {
		c.state.Phase, c.state.Error, c.state.AuthURL = "error", err.Error(), ""
	} else {
		c.session = sess
		c.applySnapshotLocked(commandID, snap)
	}
	c.publishLocked()
	c.mu.Unlock()
}

func (c *Core) updateProgress(runID uint64, commandID, phase, authURL string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.runID != runID || c.cancel == nil {
		return
	}
	c.state.CommandID, c.state.Phase, c.state.AuthURL = commandID, phase, authURL
	c.publishLocked()
}

func (c *Core) refresh(commandID string) string {
	c.mu.Lock()
	if c.session == nil || c.cancel != nil {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("core is not ready"))
	}
	if c.pollCancel != nil || (c.state.Conversation != nil && c.state.Conversation.Running) {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("cannot change conversation while a turn is running"))
	}
	c.interruptTurnID = ""
	sess := c.session
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.cancel = cancel
	c.runID++
	runID := c.runID
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, "refreshing", ""
	out := c.publishLocked()
	c.mu.Unlock()
	go func() {
		snap, err := sess.Refresh(ctx)
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.runID != runID || c.cancel == nil {
			return
		}
		c.cancel()
		c.cancel = nil
		if err != nil {
			c.state.Phase, c.state.Error = "error", err.Error()
		} else {
			c.applySnapshotLocked(commandID, snap)
		}
		c.publishLocked()
	}()
	return out
}

func (c *Core) listDirectories(commandID, parentPath string) string {
	return c.listOperation(commandID, "loading_directories", func(ctx context.Context, sess session) (func(), error) {
		listing, err := sess.ListDirectories(ctx, parentPath)
		return func() { c.state.DirectoryListing = &listing }, err
	})
}

func (c *Core) listSessionCandidates(commandID, cwd string) string {
	return c.listOperation(commandID, "loading_session_candidates", func(ctx context.Context, sess session) (func(), error) {
		candidates, err := sess.ListSessionCandidates(ctx, cwd)
		return func() { c.state.SessionCandidates = &candidates }, err
	})
}

func (c *Core) listOperation(commandID, phase string, operation func(context.Context, session) (func(), error)) string {
	c.mu.Lock()
	if c.session == nil || c.cancel != nil {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("core is not ready"))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.cancel = cancel
	c.runID++
	runID, sess := c.runID, c.session
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, phase, ""
	out := c.publishLocked()
	c.mu.Unlock()
	go func() {
		apply, err := operation(ctx, sess)
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.runID != runID || c.cancel == nil {
			return
		}
		c.cancel()
		c.cancel = nil
		c.state.CommandID = commandID
		if err != nil {
			c.state.Phase, c.state.Error = "error", err.Error()
		} else {
			apply()
			c.state.Phase, c.state.Error = "ready", ""
		}
		c.publishLocked()
	}()
	return out
}

func (c *Core) createCodex(commandID string, p createCodexPayload) string {
	return c.codexOperation(commandID, "creating_codex", "", false, func(ctx context.Context, sess session) (string, error) { return sess.CreateCodex(ctx, p) })
}

func (c *Core) importSession(commandID string, p importSessionPayload) string {
	return c.codexOperation(commandID, "importing_session", "", false, func(ctx context.Context, sess session) (string, error) { return sess.ImportSession(ctx, p) })
}

func (c *Core) codexOperation(commandID, phase, affectedCodexID string, clearIfSelected bool, operation func(context.Context, session) (string, error)) string {
	c.mu.Lock()
	if c.session == nil || c.cancel != nil || c.pollCancel != nil || (c.state.Conversation != nil && c.state.Conversation.Running) {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("cannot change codex while a turn is running"))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.cancel = cancel
	c.runID++
	runID, sess := c.runID, c.session
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, phase, ""
	out := c.publishLocked()
	c.mu.Unlock()
	go func() {
		createdCodexID, err := operation(ctx, sess)
		mutationSucceeded := err == nil
		var snap snapshot
		if err == nil {
			snap, err = sess.Refresh(ctx)
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.runID != runID || c.cancel == nil {
			return
		}
		c.cancel()
		c.cancel = nil
		c.state.CommandID = commandID
		if mutationSucceeded && createdCodexID != "" {
			c.state.SelectedCodexID = createdCodexID
		}
		if mutationSucceeded && clearIfSelected && c.state.SelectedCodexID == affectedCodexID {
			c.cancelPendingLocked()
			c.pendingWatchRunID++
			c.state.SelectedCodexID, c.state.Conversation = "", nil
		}
		if err != nil {
			c.state.Phase, c.state.Error = "error", err.Error()
			c.publishLocked()
			return
		}
		c.applySnapshotLocked(commandID, snap)
		c.publishLocked()
	}()
	return out
}

func (c *Core) getWorkspace(commandID, codexID string) string {
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	if c.session == nil {
		out := c.workspaceRejectLocked(commandID, codexID, "operation_failed", "core is not ready")
		c.mu.Unlock()
		return out
	}
	workspaceSession, ok := c.session.(workspaceSession)
	if !ok {
		out := c.workspaceRejectLocked(commandID, codexID, "capability_not_supported", "workspace capability is not supported")
		c.mu.Unlock()
		return out
	}
	limits, supported, err := workspaceSession.WorkspaceSupport()
	if err != nil || !supported {
		if err == nil {
			err = newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
		}
		stateError := workspaceStateErrorFrom(err)
		c.state.Workspace = &workspaceState{CodexID: codexID, Loading: "none"}
		out := c.workspaceRejectLocked(commandID, codexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	if c.state.Workspace == nil || c.state.Workspace.CodexID != codexID {
		c.state.Workspace = &workspaceState{CodexID: codexID, Loading: "none"}
	}
	c.state.Workspace.Supported = true
	c.state.Workspace.Limits = &limits
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "workspace")
	c.mu.Unlock()

	go func() {
		descriptor, err := workspaceSession.GetWorkspace(ctx, codexID)
		c.finishWorkspaceOperation(workspaceRunID, commandID, err, func(workspace *workspaceState) error {
			if descriptor.AccessState == nil {
				return newWorkspaceOperationError("operation_failed", "GetWorkspace returned no access state")
			}
			if workspace.AccessState != nil && descriptor.AccessState.Generation < workspace.AccessState.Generation {
				return newWorkspaceOperationError("operation_failed", "GetWorkspace returned stale access-state generation")
			}
			workspace.WorkspaceRoot = descriptor.WorkspaceRoot
			workspace.AccessState = descriptor.AccessState
			return nil
		})
	}()
	return out
}

func (c *Core) listWorkspaceEntries(commandID string, p listWorkspaceEntriesPayload) string {
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	workspaceSession, err := c.workspaceSessionLocked(p.CodexID)
	if err != nil {
		stateError := workspaceStateErrorFrom(err)
		out := c.workspaceRejectLocked(commandID, p.CodexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	c.state.Workspace.CurrentDirectory = &workspaceDirectory{RelativeDirectory: p.RelativeDirectory, Entries: []workspaceEntry{}}
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "entries")
	c.mu.Unlock()

	go func() {
		directory, err := workspaceSession.ListWorkspaceEntries(ctx, p.CodexID, p.RelativeDirectory)
		c.finishWorkspaceOperation(workspaceRunID, commandID, err, func(workspace *workspaceState) error {
			workspace.CurrentDirectory = &directory
			return nil
		})
	}()
	return out
}

func (c *Core) readWorkspaceTextFile(commandID string, p workspacePathPayload) string {
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	workspaceSession, err := c.workspaceSessionLocked(p.CodexID)
	if err == nil {
		if entry := workspaceEntryByPath(c.state.Workspace, p.RelativePath); entry != nil && !entry.TextViewable {
			err = newWorkspaceOperationError("workspace_entry_type_unsupported", "workspace entry is not text-viewable")
		}
	}
	if err != nil {
		stateError := workspaceStateErrorFrom(err)
		out := c.workspaceRejectLocked(commandID, p.CodexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	c.state.Workspace.OpenFile = nil
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "file")
	c.mu.Unlock()

	go func() {
		openFile, err := workspaceSession.ReadWorkspaceTextFile(ctx, p.CodexID, p.RelativePath)
		c.finishWorkspaceOperation(workspaceRunID, commandID, err, func(workspace *workspaceState) error {
			workspace.OpenFile = &openFile
			return nil
		})
	}()
	return out
}

func (c *Core) writeWorkspaceTextFile(commandID string, p writeWorkspaceTextFilePayload) string {
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	workspaceSession, err := c.workspaceSessionLocked(p.CodexID)
	if err == nil {
		err = validateWorkspaceWriteLocked(c.state.Workspace, p)
	}
	if err != nil {
		stateError := workspaceStateErrorFrom(err)
		out := c.workspaceRejectLocked(commandID, p.CodexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	preWriteGeneration := c.state.Workspace.AccessState.Generation
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "write")
	c.mu.Unlock()

	go func() {
		writeResult, writeErr := workspaceSession.WriteWorkspaceTextFile(ctx, p)
		var descriptor workspaceDescriptor
		var refreshErr error
		if writeErr == nil {
			descriptor, refreshErr = workspaceSession.GetWorkspace(ctx, p.CodexID)
		}
		c.finishWorkspaceWrite(workspaceRunID, preWriteGeneration, commandID, p.UTF8Text, writeResult, descriptor, writeErr, refreshErr)
	}()
	return out
}

func (c *Core) uploadWorkspaceEntry(commandID string, p uploadWorkspaceEntryPayload) string {
	content, err := decodeStrictBase64(p.ContentBase64)
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	workspaceSession, sessionErr := c.workspaceSessionLocked(p.CodexID)
	if err == nil {
		err = sessionErr
	}
	if err == nil {
		err = validateWorkspaceUploadLocked(c.state.Workspace, p, content)
	}
	if err != nil {
		stateError := workspaceStateErrorFrom(err)
		out := c.workspaceRejectLocked(commandID, p.CodexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	preUploadGeneration := c.state.Workspace.AccessState.Generation
	preUploadToken := c.state.Workspace.AccessState.QuiescenceToken
	request := workspaceUploadRequest{CodexID: p.CodexID, DestinationPath: p.DestinationPath, Kind: p.Kind, Content: content, ExpectedQuiescenceToken: p.ExpectedQuiescenceToken}
	c.state.Workspace.UploadResult = nil
	c.workspaceUploadInFlight = true
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "upload")
	c.mu.Unlock()

	go func() {
		uploadResult, uploadErr := workspaceSession.UploadWorkspaceEntry(ctx, request)
		var descriptor workspaceDescriptor
		var refreshErr error
		if uploadErr == nil {
			descriptor, refreshErr = workspaceSession.GetWorkspace(ctx, p.CodexID)
		}
		c.finishWorkspaceUpload(workspaceRunID, preUploadGeneration, preUploadToken, commandID, uploadResult, descriptor, uploadErr, refreshErr)
	}()
	return out
}

func (c *Core) downloadWorkspaceEntry(commandID string, p workspacePathPayload) string {
	c.mu.Lock()
	if c.workspaceUploadInFlight {
		out := c.workspaceUploadBusyLocked(commandID)
		c.mu.Unlock()
		return out
	}
	workspaceSession, err := c.workspaceSessionLocked(p.CodexID)
	if err == nil {
		err = validateWorkspaceRelativePath(p.RelativePath, true)
	}
	if err != nil {
		stateError := workspaceStateErrorFrom(err)
		out := c.workspaceRejectLocked(commandID, p.CodexID, stateError.Code, stateError.Message)
		c.mu.Unlock()
		return out
	}
	c.state.Workspace.DownloadResult = nil
	out, ctx, workspaceRunID := c.beginWorkspaceLocked(commandID, "download")
	c.mu.Unlock()

	go func() {
		downloadResult, err := workspaceSession.DownloadWorkspaceEntry(ctx, p.CodexID, p.RelativePath)
		c.finishWorkspaceOperation(workspaceRunID, commandID, err, func(workspace *workspaceState) error {
			workspace.DownloadResult = &downloadResult
			return nil
		})
	}()
	return out
}

func (c *Core) workspaceSessionLocked(codexID string) (workspaceSession, error) {
	if c.session == nil {
		return nil, newWorkspaceOperationError("operation_failed", "core is not ready")
	}
	workspaceSession, ok := c.session.(workspaceSession)
	if !ok {
		return nil, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	workspace := c.state.Workspace
	if workspace == nil || workspace.CodexID != codexID || !workspace.Supported || workspace.Limits == nil {
		return nil, newWorkspaceOperationError("invalid_request", "get_workspace is required for this codexId")
	}
	return workspaceSession, nil
}

func (c *Core) beginWorkspaceLocked(commandID, loading string) (string, context.Context, uint64) {
	if c.workspaceCancel != nil {
		c.workspaceCancel()
	}
	c.workspaceRunID++
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.workspaceCancel = cancel
	c.state.CommandID = commandID
	c.state.Workspace.Loading, c.state.Workspace.Error = loading, nil
	out := c.publishLocked()
	return out, ctx, c.workspaceRunID
}

func (c *Core) finishWorkspaceOperation(workspaceRunID uint64, commandID string, operationErr error, apply func(*workspaceState) error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.session == nil || c.workspaceCancel == nil || c.workspaceRunID != workspaceRunID || c.state.Workspace == nil {
		return
	}
	c.workspaceCancel()
	c.workspaceCancel = nil
	c.state.CommandID = commandID
	c.state.Workspace.Loading = "none"
	if operationErr == nil {
		operationErr = apply(c.state.Workspace)
	}
	if operationErr != nil {
		stateError := workspaceStateErrorFrom(operationErr)
		c.state.Workspace.Error = stateError
	} else {
		c.state.Workspace.Error = nil
	}
	c.publishLocked()
}

func (c *Core) finishWorkspaceWrite(workspaceRunID, preWriteGeneration uint64, commandID, utf8Text string, writeResult workspaceWriteResult, descriptor workspaceDescriptor, writeErr, refreshErr error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.session == nil || c.workspaceCancel == nil || c.workspaceRunID != workspaceRunID || c.state.Workspace == nil {
		return
	}
	c.workspaceCancel()
	c.workspaceCancel = nil
	c.state.CommandID = commandID
	c.state.Workspace.Loading = "none"
	if writeErr != nil {
		c.state.Workspace.Error = workspaceStateErrorFrom(writeErr)
		c.publishLocked()
		return
	}

	// The mutation is already committed at this point, so expose its truthful
	// file result even if the required access-state refresh fails. Clearing the
	// access state prevents reuse of the pre-mutation quiescence token.
	c.state.Workspace.LastWrite = &writeResult
	c.state.Workspace.OpenFile = &workspaceOpenFile{Entry: writeResult.Entry, UTF8Text: utf8Text}
	if refreshErr == nil {
		if descriptor.AccessState == nil {
			refreshErr = errors.New("GetWorkspace returned no access state")
		} else if descriptor.AccessState.Generation <= preWriteGeneration {
			refreshErr = errors.New("GetWorkspace did not advance access-state generation after committed write")
		}
	}
	if refreshErr != nil {
		c.state.Workspace.AccessState = nil
		c.state.Workspace.Error = &workspaceStateError{
			Code:    "operation_failed",
			Message: fmt.Sprintf("workspace write committed but access-state refresh failed; call get_workspace: %v", refreshErr),
		}
		c.publishLocked()
		return
	}
	c.state.Workspace.WorkspaceRoot = descriptor.WorkspaceRoot
	c.state.Workspace.AccessState = descriptor.AccessState
	c.state.Workspace.Error = nil
	c.publishLocked()
}

func (c *Core) finishWorkspaceUpload(workspaceRunID, preUploadGeneration uint64, preUploadToken, commandID string, uploadResult workspaceUploadResult, descriptor workspaceDescriptor, uploadErr, refreshErr error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.session == nil || c.workspaceCancel == nil || c.workspaceRunID != workspaceRunID || c.state.Workspace == nil {
		return
	}
	c.workspaceCancel()
	c.workspaceCancel = nil
	c.workspaceUploadInFlight = false
	c.state.CommandID = commandID
	c.state.Workspace.Loading = "none"
	if uploadErr != nil {
		// The RPC error boundary cannot prove the mutation did not commit. Never
		// leave the pre-upload token available for a second mutation.
		c.state.Workspace.AccessState = nil
		c.state.Workspace.Error = workspaceStateErrorFrom(uploadErr)
		c.publishLocked()
		return
	}

	// Upload is already committed before the descriptor refresh. Preserve that
	// result truthfully, but invalidate the old mutation token if refresh fails.
	c.state.Workspace.UploadResult = &uploadResult
	if refreshErr == nil {
		if descriptor.AccessState == nil {
			refreshErr = errors.New("GetWorkspace returned no access state")
		} else if descriptor.AccessState.Generation <= preUploadGeneration {
			refreshErr = errors.New("GetWorkspace did not advance access-state generation after committed upload")
		} else if descriptor.AccessState.MutationStatus == "allowed" && (descriptor.AccessState.QuiescenceToken == "" || descriptor.AccessState.QuiescenceToken == preUploadToken) {
			refreshErr = errors.New("GetWorkspace did not rotate the allowed quiescence token after committed upload")
		} else if descriptor.AccessState.MutationStatus != "allowed" && descriptor.AccessState.QuiescenceToken == preUploadToken {
			refreshErr = errors.New("GetWorkspace returned the pre-upload quiescence token")
		}
	}
	if refreshErr != nil {
		c.state.Workspace.AccessState = nil
		c.state.Workspace.Error = &workspaceStateError{
			Code:    "operation_failed",
			Message: fmt.Sprintf("workspace upload committed but access-state refresh failed; call get_workspace: %v", refreshErr),
		}
		c.publishLocked()
		return
	}
	c.state.Workspace.WorkspaceRoot = descriptor.WorkspaceRoot
	c.state.Workspace.AccessState = descriptor.AccessState
	c.state.Workspace.Error = nil
	c.publishLocked()
}

func (c *Core) workspaceReject(commandID, codexID, code, message string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.workspaceRejectLocked(commandID, codexID, code, message)
}

func (c *Core) workspaceRejectLocked(commandID, codexID, code, message string) string {
	if c.workspaceUploadInFlight {
		return c.workspaceUploadBusyLocked(commandID)
	}
	if c.workspaceCancel != nil {
		c.workspaceCancel()
		c.workspaceCancel = nil
	}
	c.workspaceUploadInFlight = false
	c.workspaceRunID++
	if c.state.Workspace == nil || (codexID != "" && c.state.Workspace.CodexID != codexID) {
		c.state.Workspace = &workspaceState{CodexID: codexID, Loading: "none"}
	}
	c.state.CommandID = commandID
	c.state.Workspace.Loading = "none"
	c.state.Workspace.Error = &workspaceStateError{Code: code, Message: message}
	return c.publishLocked()
}

func (c *Core) workspaceUploadBusyLocked(commandID string) string {
	c.state.CommandID = commandID
	if c.state.Workspace != nil {
		c.state.Workspace.Error = &workspaceStateError{Code: "workspace_busy", Message: "workspace upload is in progress"}
	}
	return c.publishLocked()
}

func workspaceEntryByPath(workspace *workspaceState, relativePath string) *workspaceEntry {
	if workspace == nil {
		return nil
	}
	if workspace.OpenFile != nil && workspace.OpenFile.Entry.RelativePath == relativePath {
		return &workspace.OpenFile.Entry
	}
	if workspace.CurrentDirectory != nil {
		for i := range workspace.CurrentDirectory.Entries {
			if workspace.CurrentDirectory.Entries[i].RelativePath == relativePath {
				return &workspace.CurrentDirectory.Entries[i]
			}
		}
	}
	return nil
}

func validateWorkspaceWriteLocked(workspace *workspaceState, p writeWorkspaceTextFilePayload) error {
	if workspace == nil || workspace.Limits == nil {
		return newWorkspaceOperationError("capability_not_supported", "workspace limits are unavailable")
	}
	if uint64(len([]byte(p.UTF8Text))) > workspace.Limits.MaxTextFileBytes {
		return newWorkspaceOperationError("workspace_text_too_large", "workspace text exceeds maxTextFileBytes")
	}
	access := workspace.AccessState
	if access == nil || access.MutationStatus != "allowed" || access.QuiescenceToken == "" {
		return newWorkspaceOperationError("workspace_busy", "workspace mutations are not currently allowed")
	}
	if p.ExpectedQuiescenceToken == "" || p.ExpectedQuiescenceToken != access.QuiescenceToken {
		return newWorkspaceOperationError("invalid_request", "expectedQuiescenceToken must match the current access state")
	}
	switch p.Condition {
	case "create_only":
		if p.ExpectedRevision != "" {
			return newWorkspaceOperationError("invalid_request", "expectedRevision must be empty for create_only")
		}
	case "replace_only":
		if p.ExpectedRevision == "" {
			return newWorkspaceOperationError("invalid_request", "expectedRevision is required for replace_only")
		}
	case "upsert":
	default:
		return newWorkspaceOperationError("invalid_request", "condition must be create_only, replace_only, or upsert")
	}
	if entry := workspaceEntryByPath(workspace, p.RelativePath); entry != nil {
		if !entry.TextEditable {
			return newWorkspaceOperationError("workspace_entry_type_unsupported", "workspace entry is not text-editable")
		}
		if p.Condition == "create_only" {
			return newWorkspaceOperationError("invalid_request", "create_only target already exists")
		}
		if p.ExpectedRevision != entry.Revision {
			return newWorkspaceOperationError("workspace_revision_conflict", "expectedRevision does not match the current entry")
		}
	}
	return nil
}

func validateWorkspaceUploadLocked(workspace *workspaceState, p uploadWorkspaceEntryPayload, content []byte) error {
	if workspace == nil || workspace.Limits == nil {
		return newWorkspaceOperationError("capability_not_supported", "workspace limits are unavailable")
	}
	if err := validateWorkspaceRelativePath(p.DestinationPath, false); err != nil {
		return err
	}
	if p.Kind != "regular_file" && p.Kind != "zip_directory" {
		return newWorkspaceOperationError("invalid_request", "kind must be regular_file or zip_directory")
	}
	if uint64(len(content)) > workspace.Limits.MaxInlineUploadBytes {
		return newWorkspaceOperationError("workspace_upload_too_large", "workspace upload exceeds maxInlineUploadBytes")
	}
	access := workspace.AccessState
	if access == nil || access.MutationStatus != "allowed" || access.QuiescenceToken == "" {
		return newWorkspaceOperationError("workspace_busy", "workspace mutations are not currently allowed")
	}
	if p.ExpectedQuiescenceToken == "" || p.ExpectedQuiescenceToken != access.QuiescenceToken {
		return newWorkspaceOperationError("invalid_request", "expectedQuiescenceToken must match the current access state")
	}
	return nil
}

func validateWorkspaceRelativePath(relativePath string, allowRoot bool) error {
	if relativePath == "" {
		if allowRoot {
			return nil
		}
		return newWorkspaceOperationError("invalid_request", "workspace path is required")
	}
	if strings.Contains(relativePath, "\\") || strings.ContainsRune(relativePath, '\x00') || strings.HasPrefix(relativePath, "/") || relativePath == "." || path.Clean(relativePath) != relativePath {
		return newWorkspaceOperationError("invalid_request", "workspace path must be canonical and relative")
	}
	for _, segment := range strings.Split(relativePath, "/") {
		if segment == "." || segment == ".." {
			return newWorkspaceOperationError("invalid_request", "workspace path must be canonical and relative")
		}
	}
	return nil
}

func decodeStrictBase64(encoded string) ([]byte, error) {
	decoded, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil || base64.StdEncoding.EncodeToString(decoded) != encoded {
		return nil, newWorkspaceOperationError("invalid_request", "contentBase64 must be canonical standard base64")
	}
	return decoded, nil
}

func (c *Core) cancelPendingLocked() chan struct{} {
	done := c.pendingWatchDone
	if c.pendingWatchCancel != nil {
		c.pendingWatchCancel()
		c.pendingWatchCancel = nil
	}
	for requestID, cancel := range c.pendingResponseCancels {
		cancel()
		delete(c.pendingResponseCancels, requestID)
	}
	c.pendingRequestLocal = map[string]pendingLocalState{}
	return done
}

func (c *Core) mergePendingLocked(conversation *conversationState) {
	if conversation == nil || c.state.Conversation == nil || c.state.Conversation.CodexID != conversation.CodexID {
		return
	}
	conversation.PendingRequests = append([]pendingRequest{}, c.state.Conversation.PendingRequests...)
	conversation.PendingWatch = c.state.Conversation.PendingWatch
}

func (c *Core) runPendingWatch(ctx context.Context, sess pendingSession, watchRunID uint64, codexID string) {
	for {
		reset, watch, err := sess.WatchPending(ctx, codexID)
		if err != nil {
			if ctx.Err() == nil {
				c.pendingWatchFailed(watchRunID, codexID, "watch_failed", err.Error())
			}
			return
		}
		c.mu.Lock()
		if !c.pendingWatchCurrentLocked(watchRunID, codexID) {
			c.mu.Unlock()
			c.unwatchPending(sess, watch)
			return
		}
		for i := range reset.Requests {
			if local, ok := c.pendingRequestLocal[reset.Requests[i].RequestID]; ok {
				reset.Requests[i].InFlight, reset.Requests[i].Error = local.InFlight, local.Error
			}
		}
		c.state.Conversation.PendingRequests = reset.Requests
		c.state.Conversation.PendingWatch = pendingWatchState{State: "watching", HeadEventSeq: reset.HeadEventSeq}
		c.publishLocked()
		c.mu.Unlock()

		rebuild := false
		for {
			event, nextErr := watch.Next(ctx)
			if nextErr != nil {
				if ctx.Err() != nil {
					c.unwatchPending(sess, watch)
					return
				}
				code := "watch_failed"
				if strings.Contains(nextErr.Error(), "overflow") {
					code, rebuild = "watch_gap", true
				} else if strings.Contains(nextErr.Error(), "PendingRequest") || strings.Contains(nextErr.Error(), "pending request") || strings.Contains(nextErr.Error(), "user-input") || strings.Contains(nextErr.Error(), "approval") || strings.Contains(nextErr.Error(), "Event envelope") {
					code = "invalid_event"
				}
				c.pendingWatchFailed(watchRunID, codexID, code, nextErr.Error())
				break
			}
			c.mu.Lock()
			if !c.pendingWatchCurrentLocked(watchRunID, codexID) {
				c.mu.Unlock()
				c.unwatchPending(sess, watch)
				return
			}
			head := c.state.Conversation.PendingWatch.HeadEventSeq
			if event.CodexID != codexID {
				c.pendingWatchFailLocked("invalid_event", "Watch event codexId mismatch")
				c.mu.Unlock()
				break
			}
			if event.EventSeq <= head {
				c.pendingWatchFailLocked("watch_gap", fmt.Sprintf("Watch event sequence did not advance: got %d after %d", event.EventSeq, head))
				rebuild = true
				c.mu.Unlock()
				break
			}
			if event.EventSeq != head+1 {
				c.pendingWatchFailLocked("watch_gap", fmt.Sprintf("Watch event gap: got %d after %d", event.EventSeq, head))
				rebuild = true
				c.mu.Unlock()
				break
			}
			// Advance for every Event, not only PendingRequestUpdated.
			c.state.Conversation.PendingWatch.HeadEventSeq = event.EventSeq
			if event.HasUpdate {
				c.applyPendingUpdateLocked(event)
			}
			c.publishLocked()
			c.mu.Unlock()
		}
		c.unwatchPending(sess, watch)
		if !rebuild || ctx.Err() != nil {
			return
		}
		c.mu.Lock()
		if !c.pendingWatchCurrentLocked(watchRunID, codexID) {
			c.mu.Unlock()
			return
		}
		c.state.Conversation.PendingWatch = pendingWatchState{State: "loading"}
		c.publishLocked()
		c.mu.Unlock()
	}
}

func (c *Core) unwatchPending(sess pendingSession, watch *protocolPendingWatch) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = sess.UnwatchPending(ctx, watch)
}

func (c *Core) pendingWatchCurrentLocked(watchRunID uint64, codexID string) bool {
	return c.session != nil && c.pendingWatchRunID == watchRunID && c.state.SelectedCodexID == codexID && c.state.Conversation != nil && c.state.Conversation.CodexID == codexID
}

func (c *Core) pendingWatchFailed(watchRunID uint64, codexID, code, message string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.pendingWatchCurrentLocked(watchRunID, codexID) {
		return
	}
	c.pendingWatchFailLocked(code, message)
}

func (c *Core) pendingWatchFailLocked(code, message string) {
	c.state.Conversation.PendingRequests = []pendingRequest{}
	c.state.Conversation.PendingWatch = pendingWatchState{State: "error", Error: &pendingRequestError{Code: code, Message: message}}
	c.publishLocked()
}

func (c *Core) applyPendingUpdateLocked(event pendingWatchEvent) {
	index := c.pendingRequestIndexLocked(event.RequestID)
	if !event.Actionable {
		if index >= 0 {
			c.state.Conversation.PendingRequests = append(c.state.Conversation.PendingRequests[:index], c.state.Conversation.PendingRequests[index+1:]...)
		}
		if _, inFlight := c.pendingResponseCancels[event.RequestID]; !inFlight {
			delete(c.pendingRequestLocal, event.RequestID)
		}
		return
	}
	if local, ok := c.pendingRequestLocal[event.RequestID]; ok {
		event.Request.InFlight, event.Request.Error = local.InFlight, local.Error
	}
	if index >= 0 {
		c.state.Conversation.PendingRequests[index] = event.Request
		return
	}
	c.state.Conversation.PendingRequests = append(c.state.Conversation.PendingRequests, event.Request)
}

func (c *Core) pendingRequestIndexLocked(requestID string) int {
	if c.state.Conversation == nil {
		return -1
	}
	for i := range c.state.Conversation.PendingRequests {
		if c.state.Conversation.PendingRequests[i].RequestID == requestID {
			return i
		}
	}
	return -1
}

func (c *Core) selectCodex(commandID, codexID string) string {
	if codexID == "" {
		return c.reject(commandID, errors.New("codexId is required"))
	}
	c.mu.Lock()
	if c.session == nil || c.cancel != nil {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("core is not ready"))
	}
	if c.pollCancel != nil || (c.state.Conversation != nil && c.state.Conversation.Running) {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("cannot change conversation while a turn is running"))
	}
	c.interruptTurnID = ""
	c.conversationRunID++
	previousWatchDone := c.cancelPendingLocked()
	c.pendingWatchRunID++
	pendingWatchRunID := c.pendingWatchRunID
	watchCtx, watchCancel := context.WithCancel(context.Background())
	watchDone := make(chan struct{})
	c.pendingWatchCancel, c.pendingWatchDone = watchCancel, watchDone
	sess := c.session
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.cancel = cancel
	conversationRunID := c.conversationRunID
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, "loading_conversation", ""
	c.state.SelectedCodexID = codexID
	c.state.Conversation = &conversationState{CodexID: codexID, Turns: []conversationTurn{}, PendingRequests: []pendingRequest{}, PendingWatch: pendingWatchState{State: "loading"}}
	out := c.publishLocked()
	c.mu.Unlock()

	go c.fetchConversation(ctx, cancel, sess, conversationRunID, commandID, codexID)
	go func() {
		defer close(watchDone)
		if previousWatchDone != nil {
			select {
			case <-previousWatchDone:
			case <-watchCtx.Done():
				return
			}
		}
		pendingSession, ok := sess.(pendingSession)
		if !ok {
			c.pendingWatchFailed(pendingWatchRunID, codexID, "watch_failed", "session does not support pending-request Watch")
			return
		}
		c.runPendingWatch(watchCtx, pendingSession, pendingWatchRunID, codexID)
	}()
	return out
}

func (c *Core) refreshConversation(commandID string) string {
	c.mu.Lock()
	codexID := c.state.SelectedCodexID
	c.mu.Unlock()
	if codexID == "" {
		return c.reject(commandID, errors.New("select_codex is required before refresh_conversation"))
	}
	return c.selectCodex(commandID, codexID)
}

func (c *Core) fetchConversation(ctx context.Context, cancel context.CancelFunc, sess session, conversationRunID uint64, commandID, codexID string) {
	conversation, err := sess.ListHistory(ctx, codexID)
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conversationRunID != conversationRunID || c.cancel == nil {
		return
	}
	c.cancel()
	c.cancel = nil
	if err != nil {
		c.state.Phase, c.state.Error = "error", err.Error()
	} else {
		c.state.Phase, c.state.Error = "ready", ""
		c.mergePendingLocked(&conversation)
		c.state.Conversation = &conversation
	}
	c.state.CommandID = commandID
	c.publishLocked()
}

func (c *Core) startTurn(commandID string, p startTurnPayload) string {
	if strings.TrimSpace(p.Text) == "" {
		return c.reject(commandID, errors.New("start_turn text is required"))
	}
	c.mu.Lock()
	if c.session == nil || c.cancel != nil || c.state.SelectedCodexID == "" {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("select_codex is required before start_turn"))
	}
	if c.pollCancel != nil || (c.state.Conversation != nil && c.state.Conversation.Running) {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("a turn is already running"))
	}
	c.interruptTurnID = ""
	ctx, cancel := context.WithTimeout(context.Background(), c.conversationPollTimeout)
	c.pollCancel = cancel
	c.conversationRunID++
	conversationRunID := c.conversationRunID
	sess, codexID := c.session, c.state.SelectedCodexID
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, "starting_turn", ""
	out := c.publishLocked()
	c.mu.Unlock()

	go func() {
		callCtx, callCancel := context.WithTimeout(ctx, 30*time.Second)
		turnID, err := sess.StartTurn(callCtx, codexID, p.Text, p.Options)
		callCancel()
		if err != nil {
			c.finishConversationOperation(conversationRunID, commandID, err)
			return
		}
		c.mu.Lock()
		if !c.conversationCurrentLocked(conversationRunID) {
			c.mu.Unlock()
			return
		}
		if c.state.Conversation == nil {
			c.state.Conversation = &conversationState{CodexID: codexID, Turns: []conversationTurn{}}
		}
		c.state.Conversation.ActiveTurnID = turnID
		c.state.Conversation.Running = true
		c.state.Phase = "ready"
		c.publishLocked()
		c.mu.Unlock()
		c.pollConversation(ctx, sess, conversationRunID, commandID, codexID, turnID)
	}()
	return out
}

func (c *Core) interruptTurn(commandID, turnID string) string {
	c.mu.Lock()
	if c.session == nil || c.cancel != nil || c.state.SelectedCodexID == "" {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("select_codex is required before interrupt_turn"))
	}
	if turnID == "" && c.state.Conversation != nil {
		turnID = c.state.Conversation.ActiveTurnID
	}
	if turnID == "" {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("interrupt_turn requires turnId or an active turn"))
	}
	if c.interruptTurnID == turnID {
		c.mu.Unlock()
		return c.reject(commandID, errors.New("interrupt already requested for this turn"))
	}
	if c.pollCancel != nil {
		c.pollCancel()
	}
	ctx, cancel := context.WithTimeout(context.Background(), c.conversationPollTimeout)
	c.pollCancel = cancel
	c.conversationRunID++
	conversationRunID := c.conversationRunID
	c.interruptTurnID = turnID
	sess, codexID := c.session, c.state.SelectedCodexID
	c.state.CommandID, c.state.Error = commandID, ""
	out := c.publishLocked()
	c.mu.Unlock()

	go func() {
		callCtx, callCancel := context.WithTimeout(ctx, 30*time.Second)
		returnedTurnID, err := sess.InterruptTurn(callCtx, codexID, turnID)
		callCancel()
		if err != nil {
			c.finishConversationOperation(conversationRunID, commandID, err)
			return
		}
		if returnedTurnID != "" {
			turnID = returnedTurnID
		}
		c.mu.Lock()
		if c.conversationCurrentLocked(conversationRunID) {
			c.interruptTurnID = turnID
		}
		c.mu.Unlock()
		c.pollConversation(ctx, sess, conversationRunID, commandID, codexID, turnID)
	}()
	return out
}

func (c *Core) respondApproval(commandID string, p respondApprovalPayload) string {
	c.mu.Lock()
	if p.ApprovalID == "" {
		out := c.pendingRejectLocked(commandID, p.ApprovalID, "invalid_request", "respond_approval approvalId is required")
		c.mu.Unlock()
		return out
	}
	sess, ok := c.session.(pendingSession)
	index := c.pendingRequestIndexLocked(p.ApprovalID)
	if !ok || c.state.SelectedCodexID == "" || index < 0 || c.state.Conversation.PendingRequests[index].Type != "approval" {
		out := c.pendingRejectLocked(commandID, p.ApprovalID, "invalid_request", "approval is not currently pending for the selected Codex")
		c.mu.Unlock()
		return out
	}
	request := c.state.Conversation.PendingRequests[index]
	if request.InFlight {
		c.state.CommandID = commandID
		out := c.publishLocked()
		c.mu.Unlock()
		return out
	}
	allowed := false
	for _, decision := range request.Approval.AllowedDecisions {
		if decision == p.Decision {
			allowed = true
			break
		}
	}
	if !allowed {
		out := c.pendingRejectLocked(commandID, p.ApprovalID, "invalid_request", "decision is not allowed for this approval")
		c.mu.Unlock()
		return out
	}
	return c.beginPendingResponseLocked(commandID, request, func(ctx context.Context, codexID string) (pendingResponseResult, error) {
		return sess.RespondApproval(ctx, codexID, p.ApprovalID, p.Decision)
	})
}

func (c *Core) respondUserInput(commandID string, p respondUserInputPayload) string {
	c.mu.Lock()
	if p.RequestID == "" {
		out := c.pendingRejectLocked(commandID, p.RequestID, "invalid_request", "respond_user_input requestId is required")
		c.mu.Unlock()
		return out
	}
	sess, ok := c.session.(pendingSession)
	index := c.pendingRequestIndexLocked(p.RequestID)
	if !ok || c.state.SelectedCodexID == "" || index < 0 || c.state.Conversation.PendingRequests[index].Type != "user_input" {
		out := c.pendingRejectLocked(commandID, p.RequestID, "invalid_request", "user-input request is not currently pending for the selected Codex")
		c.mu.Unlock()
		return out
	}
	request := c.state.Conversation.PendingRequests[index]
	if request.InFlight {
		c.state.CommandID = commandID
		out := c.publishLocked()
		c.mu.Unlock()
		return out
	}
	if err := validatePendingAnswers(request.UserInput, p.Answers); err != nil {
		out := c.pendingRejectLocked(commandID, p.RequestID, "invalid_request", err.Error())
		c.mu.Unlock()
		return out
	}
	return c.beginPendingResponseLocked(commandID, request, func(ctx context.Context, codexID string) (pendingResponseResult, error) {
		return sess.RespondUserInput(ctx, codexID, p.RequestID, p.Answers)
	})
}

func (c *Core) beginPendingResponseLocked(commandID string, request pendingRequest, call func(context.Context, string) (pendingResponseResult, error)) string {
	index := c.pendingRequestIndexLocked(request.RequestID)
	c.state.Conversation.PendingRequests[index].InFlight = true
	c.state.Conversation.PendingRequests[index].Error = nil
	c.pendingRequestLocal[request.RequestID] = pendingLocalState{InFlight: true}
	c.state.CommandID = commandID
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	c.pendingResponseCancels[request.RequestID] = cancel
	watchRunID, codexID := c.pendingWatchRunID, c.state.SelectedCodexID
	out := c.publishLocked()
	c.mu.Unlock()
	go func() {
		result, err := call(ctx, codexID)
		cancel()
		c.finishPendingResponse(watchRunID, codexID, commandID, request, result, err)
	}()
	return out
}

func (c *Core) finishPendingResponse(watchRunID uint64, codexID, commandID string, original pendingRequest, result pendingResponseResult, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.pendingResponseCancels, original.RequestID)
	if !c.pendingWatchCurrentLocked(watchRunID, codexID) {
		return
	}
	if err == nil && (result.Type != original.Type || result.RequestID != original.RequestID || result.TurnID != original.TurnID || result.ItemID != original.ItemID) {
		err = errors.New("pending response returned mismatched request association")
	}
	c.state.CommandID = commandID
	index := c.pendingRequestIndexLocked(original.RequestID)
	if err == nil {
		delete(c.pendingRequestLocal, original.RequestID)
		if index >= 0 {
			c.state.Conversation.PendingRequests = append(c.state.Conversation.PendingRequests[:index], c.state.Conversation.PendingRequests[index+1:]...)
		}
		c.publishLocked()
		return
	}
	if index >= 0 {
		code := "operation_failed"
		var protocolError *pendingProtocolError
		if errors.As(err, &protocolError) {
			code = protocolError.Code
		}
		requestError := &pendingRequestError{CommandID: commandID, Code: code, Message: err.Error()}
		c.state.Conversation.PendingRequests[index].InFlight = false
		c.state.Conversation.PendingRequests[index].Error = requestError
		c.pendingRequestLocal[original.RequestID] = pendingLocalState{Error: requestError}
		c.publishLocked()
	} else {
		delete(c.pendingRequestLocal, original.RequestID)
	}
}

func (c *Core) pendingRejectLocked(commandID, requestID, code, message string) string {
	c.state.CommandID = commandID
	if index := c.pendingRequestIndexLocked(requestID); index >= 0 {
		requestError := &pendingRequestError{CommandID: commandID, Code: code, Message: message}
		c.state.Conversation.PendingRequests[index].Error = requestError
		c.pendingRequestLocal[requestID] = pendingLocalState{InFlight: c.state.Conversation.PendingRequests[index].InFlight, Error: requestError}
	}
	return c.publishLocked()
}

func validatePendingAnswers(userInput *pendingUserInput, answers []pendingUserInputAnswer) error {
	if userInput == nil || len(answers) != len(userInput.Questions) {
		return errors.New("answers must contain exactly one answer for every question")
	}
	questions := map[string]pendingUserInputQuestion{}
	for _, question := range userInput.Questions {
		questions[question.QuestionID] = question
	}
	seenQuestions := map[string]bool{}
	for _, answer := range answers {
		question, ok := questions[answer.QuestionID]
		if !ok || seenQuestions[answer.QuestionID] {
			return errors.New("answers contain an unknown or duplicate questionId")
		}
		seenQuestions[answer.QuestionID] = true
		if len(answer.SelectedOptionIDs) == 0 && strings.TrimSpace(answer.FreeFormText) == "" {
			return errors.New("each answer must select an option or provide freeFormText")
		}
		if !question.AllowsMultiple && len(answer.SelectedOptionIDs) > 1 {
			return errors.New("question does not allow multiple selected options")
		}
		if !question.AllowsFreeForm && answer.FreeFormText != "" {
			return errors.New("question does not allow freeFormText")
		}
		options := map[string]bool{}
		for _, option := range question.Options {
			options[option.OptionID] = true
		}
		seenOptions := map[string]bool{}
		for _, optionID := range answer.SelectedOptionIDs {
			if !options[optionID] || seenOptions[optionID] {
				return errors.New("answer contains an unknown or duplicate optionId")
			}
			seenOptions[optionID] = true
		}
	}
	return nil
}

func (c *Core) pollConversation(ctx context.Context, sess session, conversationRunID uint64, commandID, codexID, targetTurnID string) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		callCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		conversation, err := sess.ListHistory(callCtx, codexID)
		cancel()
		if err != nil {
			c.finishConversationOperation(conversationRunID, commandID, err)
			return
		}
		terminal := conversationTurnTerminal(conversation.Turns, targetTurnID)
		c.mu.Lock()
		if !c.conversationCurrentLocked(conversationRunID) {
			c.mu.Unlock()
			return
		}
		if !terminal && conversation.ActiveTurnID == "" {
			conversation.ActiveTurnID = targetTurnID
			conversation.Running = true
		}
		c.state.CommandID, c.state.Phase, c.state.Error = commandID, "ready", ""
		c.mergePendingLocked(&conversation)
		c.state.Conversation = &conversation
		c.publishLocked()
		if terminal {
			c.pollCancel()
			c.pollCancel = nil
			if c.interruptTurnID == targetTurnID {
				c.interruptTurnID = ""
			}
			c.mu.Unlock()
			return
		}
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			c.finishConversationOperation(conversationRunID, commandID, fmt.Errorf("conversation polling: %w", ctx.Err()))
			return
		case <-ticker.C:
		}
	}
}

func conversationTurnTerminal(turns []conversationTurn, target string) bool {
	for _, turn := range turns {
		if turn.TurnID == target {
			return turn.Status == "completed" || turn.Status == "failed" || turn.Status == "interrupted"
		}
	}
	return false
}

func (c *Core) conversationCurrentLocked(conversationRunID uint64) bool {
	return c.session != nil && c.conversationRunID == conversationRunID && c.pollCancel != nil
}

func (c *Core) finishConversationOperation(conversationRunID uint64, commandID string, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.conversationCurrentLocked(conversationRunID) {
		return
	}
	c.pollCancel()
	c.pollCancel = nil
	c.interruptTurnID = ""
	if c.state.Conversation != nil {
		c.state.Conversation.ActiveTurnID = ""
		c.state.Conversation.Running = false
	}
	c.state.CommandID, c.state.Phase, c.state.Error = commandID, "error", err.Error()
	c.publishLocked()
}

func (c *Core) stop(commandID string) string {
	c.mu.Lock()
	c.runID++
	c.cancelPendingLocked()
	c.pendingWatchRunID++
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
	if c.pollCancel != nil {
		c.pollCancel()
		c.pollCancel = nil
	}
	if c.workspaceCancel != nil {
		c.workspaceCancel()
		c.workspaceCancel = nil
	}
	c.workspaceUploadInFlight = false
	c.conversationRunID++
	c.workspaceRunID++
	c.interruptTurnID = ""
	sess := c.session
	c.session = nil
	c.state.CommandID, c.state.Phase, c.state.AuthURL = commandID, "stopped", ""
	c.state.ServerHello, c.state.Host, c.state.Codexes, c.state.TailnetIPs = nil, nil, nil, nil
	c.state.DirectoryListing, c.state.SessionCandidates = nil, nil
	c.state.SelectedCodexID, c.state.Conversation, c.state.Workspace = "", nil, nil
	out := c.publishLocked()
	c.mu.Unlock()
	if sess != nil {
		_ = sess.Close()
	}
	return out
}

func (c *Core) applySnapshotLocked(commandID string, snap snapshot) {
	c.state.CommandID, c.state.Phase, c.state.Error, c.state.AuthURL = commandID, "ready", "", ""
	c.state.TailnetIPs, c.state.ServerHello, c.state.Host, c.state.Codexes = snap.TailnetIPs, snap.ServerHello, snap.Host, snap.Codexes
}

func (c *Core) current(commandID string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.state.CommandID = commandID
	return c.publishLocked()
}

func (c *Core) reject(commandID string, err error) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.state.CommandID, c.state.Error = commandID, err.Error()
	return c.publishLocked()
}

func (c *Core) publishLocked() string {
	c.state.Revision++
	b, _ := json.Marshal(c.state)
	out := string(b)
	c.notifier.enqueue(stateNotification{revision: c.state.Revision, json: out})
	return out
}
