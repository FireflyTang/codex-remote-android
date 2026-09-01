package mobilecore

import (
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"path"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"github.com/coder/websocket"
	"google.golang.org/protobuf/encoding/protojson"
)

type responseResult struct {
	response *remotev1.Response
	err      error
}

type pendingWatchReset struct {
	HeadEventSeq uint64
	Requests     []pendingRequest
}

type pendingWatchEvent struct {
	CodexID    string
	EventSeq   uint64
	HasUpdate  bool
	RequestID  string
	Actionable bool
	Request    pendingRequest
}

type pendingResponseResult struct {
	Type      string
	RequestID string
	TurnID    string
	ItemID    string
}

type protocolPendingWatch struct {
	client       *protocolClient
	codexID      string
	inbox        chan *remotev1.Event
	overflow     chan struct{}
	overflowOnce sync.Once
}

type frameConnection interface {
	Read(context.Context) (websocket.MessageType, []byte, error)
	Write(context.Context, websocket.MessageType, []byte) error
	CloseNow() error
}

type protocolClient struct {
	conn      frameConnection
	hello     *remotev1.ServerHello
	helloJSON []byte
	ctx       context.Context
	cancel    context.CancelFunc

	writeGate chan struct{}
	mu        sync.Mutex
	pending   map[string]chan responseResult
	eventSink *protocolPendingWatch
	closed    bool
	closeOnce sync.Once
	sequence  atomic.Uint64
}

func dialProtocol(ctx context.Context, cfg configPayload, dial func(context.Context, string, string) (net.Conn, error)) (*protocolClient, error) {
	conn, err := dialWebSocket(ctx, cfg.HostEndpoint, dial)
	if err != nil {
		return nil, err
	}
	hello := &remotev1.ClientHello{
		ClientId: cfg.ClientID, ClientRunId: cfg.ClientRunID,
		ClientName: cfg.ClientName, ClientVersion: cfg.ClientVersion,
		ProtocolVersion: &remotev1.ProtocolVersion{Major: 1, Minor: 1, Patch: 2},
	}
	if err := writeFrame(ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ClientHello{ClientHello: hello}}); err != nil {
		_ = conn.Close(websocket.StatusProtocolError, "ClientHello failed")
		return nil, fmt.Errorf("send ClientHello: %w", err)
	}
	typ, raw, err := conn.Read(ctx)
	if err != nil {
		_ = conn.Close(websocket.StatusProtocolError, "ServerHello failed")
		return nil, fmt.Errorf("read ServerHello: %w", err)
	}
	if typ != websocket.MessageText {
		_ = conn.Close(websocket.StatusProtocolError, "ServerHello must be text")
		return nil, fmt.Errorf("ServerHello must be a text frame")
	}
	frame := new(remotev1.Frame)
	if err := (protojson.UnmarshalOptions{DiscardUnknown: false}).Unmarshal(raw, frame); err != nil {
		_ = conn.Close(websocket.StatusProtocolError, "invalid ServerHello")
		return nil, fmt.Errorf("decode ServerHello: %w", err)
	}
	server := frame.GetServerHello()
	if err := validateServerHello(server); err != nil {
		_ = conn.Close(websocket.StatusProtocolError, "incompatible ServerHello")
		return nil, err
	}
	helloJSON, _ := (protojson.MarshalOptions{}).Marshal(server)
	runCtx, cancel := context.WithCancel(context.Background())
	c := &protocolClient{conn: conn, hello: server, helloJSON: helloJSON, ctx: runCtx, cancel: cancel, writeGate: newWriteGate(), pending: map[string]chan responseResult{}}
	go c.readLoop()
	return c, nil
}

func validateServerHello(h *remotev1.ServerHello) error {
	if h == nil || h.ConnectionId == "" || h.HostId == "" || h.HostRunId == "" || h.ProtocolVersion == nil || h.Runtime == nil || h.Capabilities == nil {
		return fmt.Errorf("first Host frame is not a complete ServerHello")
	}
	v := h.ProtocolVersion
	if v.Major != 1 || v.Minor != 1 || v.Patch != 2 {
		return fmt.Errorf("Host protocol is %d.%d.%d; require %s", v.Major, v.Minor, v.Patch, ProtocolVersion)
	}
	if h.HeartbeatIntervalMs == 0 || h.ConnectionTimeoutMs <= h.HeartbeatIntervalMs || h.MaxFrameBytes == 0 {
		return fmt.Errorf("ServerHello has invalid heartbeat, timeout, or frame limits")
	}
	return nil
}

func (c *protocolClient) ServerHelloJSON() []byte { return append([]byte(nil), c.helloJSON...) }

func (c *protocolClient) Fetch(ctx context.Context) ([]byte, []byte, error) {
	hostResp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_GetHost{GetHost: &remotev1.GetHostRequest{}}})
	if err != nil {
		return nil, nil, fmt.Errorf("GetHost: %w", err)
	}
	if hostResp.GetError() != nil {
		return nil, nil, hostError("GetHost", hostResp.GetError())
	}
	host := hostResp.GetGetHost()
	if host == nil {
		return nil, nil, fmt.Errorf("GetHost returned mismatched response")
	}
	codexes, err := c.ListCodexes(ctx)
	if err != nil {
		return nil, nil, err
	}
	hostJSON, err := (protojson.MarshalOptions{}).Marshal(host)
	if err != nil {
		return nil, nil, fmt.Errorf("marshal GetHost state: %w", err)
	}
	codexJSON, err := (protojson.MarshalOptions{}).Marshal(codexes)
	if err != nil {
		return nil, nil, fmt.Errorf("marshal ListCodexes state: %w", err)
	}
	return hostJSON, codexJSON, nil
}

const listPageSize = 100
const maxListPages = 10

func (c *protocolClient) ListCodexes(ctx context.Context) (*remotev1.ListCodexesResponse, error) {
	out := &remotev1.ListCodexesResponse{}
	pageToken, seenTokens := "", map[string]bool{}
	for pageNumber := 0; pageNumber < maxListPages; pageNumber++ {
		resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ListCodexes{ListCodexes: &remotev1.ListCodexesRequest{Page: &remotev1.PageRequest{PageSize: listPageSize, PageToken: pageToken}}}})
		if err != nil {
			return nil, fmt.Errorf("ListCodexes: %w", err)
		}
		if resp.GetError() != nil {
			return nil, hostError("ListCodexes", resp.GetError())
		}
		result := resp.GetListCodexes()
		if result == nil {
			return nil, fmt.Errorf("ListCodexes returned mismatched response")
		}
		out.Codexes = append(out.Codexes, result.Codexes...)
		next := result.GetPage().GetNextPageToken()
		if next == "" {
			return out, nil
		}
		if seenTokens[next] {
			return nil, fmt.Errorf("ListCodexes repeated page token")
		}
		seenTokens[next], pageToken = true, next
	}
	return out, nil
}

func (c *protocolClient) ListDirectories(ctx context.Context, parentPath string) (directoryListing, error) {
	out := directoryListing{Directories: []directoryEntry{}}
	pageToken, seenTokens := "", map[string]bool{}
	for pageNumber := 0; pageNumber < maxListPages; pageNumber++ {
		resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ListDirectories{ListDirectories: &remotev1.ListDirectoriesRequest{ParentPath: parentPath, Page: &remotev1.PageRequest{PageSize: listPageSize, PageToken: pageToken}}}})
		if err != nil {
			return directoryListing{}, fmt.Errorf("ListDirectories: %w", err)
		}
		if resp.GetError() != nil {
			return directoryListing{}, hostError("ListDirectories", resp.GetError())
		}
		result := resp.GetListDirectories()
		if result == nil || (out.ParentPath != "" && out.ParentPath != result.ParentPath) {
			return directoryListing{}, fmt.Errorf("ListDirectories returned mismatched response")
		}
		out.ParentPath = result.ParentPath
		for _, directory := range result.Directories {
			if directory != nil {
				out.Directories = append(out.Directories, directoryEntry{Name: directory.Name, Path: directory.Path})
			}
		}
		next := result.GetPage().GetNextPageToken()
		if next == "" {
			return out, nil
		}
		if seenTokens[next] {
			return directoryListing{}, fmt.Errorf("ListDirectories repeated page token")
		}
		seenTokens[next], pageToken = true, next
	}
	return out, nil
}

func (c *protocolClient) ListSessionCandidates(ctx context.Context, cwd string) (sessionCandidatesState, error) {
	out := sessionCandidatesState{Sessions: []sessionCandidate{}}
	pageToken, seenTokens := "", map[string]bool{}
	for pageNumber := 0; pageNumber < maxListPages; pageNumber++ {
		resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ListSessionCandidates{ListSessionCandidates: &remotev1.ListSessionCandidatesRequest{Cwd: cwd, Page: &remotev1.PageRequest{PageSize: listPageSize, PageToken: pageToken}}}})
		if err != nil {
			return sessionCandidatesState{}, fmt.Errorf("ListSessionCandidates: %w", err)
		}
		if resp.GetError() != nil {
			return sessionCandidatesState{}, hostError("ListSessionCandidates", resp.GetError())
		}
		result := resp.GetListSessionCandidates()
		if result == nil || (out.NormalizedCwd != "" && out.NormalizedCwd != result.NormalizedCwd) {
			return sessionCandidatesState{}, fmt.Errorf("ListSessionCandidates returned mismatched response")
		}
		out.NormalizedCwd = result.NormalizedCwd
		for _, candidate := range result.Sessions {
			if candidate != nil {
				out.Sessions = append(out.Sessions, sessionCandidate{SessionID: candidate.SessionId, Cwd: candidate.Cwd, Title: candidate.Title, Preview: candidate.Preview, Source: candidate.Source, Availability: candidate.Availability.String(), ManagedCodexID: candidate.ManagedCodexId})
			}
		}
		next := result.GetPage().GetNextPageToken()
		if next == "" {
			return out, nil
		}
		if seenTokens[next] {
			return sessionCandidatesState{}, fmt.Errorf("ListSessionCandidates repeated page token")
		}
		seenTokens[next], pageToken = true, next
	}
	return out, nil
}

func (c *protocolClient) CreateCodex(ctx context.Context, p createCodexPayload) (string, error) {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_CreateCodex{CreateCodex: &remotev1.CreateCodexRequest{Cwd: p.Cwd, CreateDirectoryIfMissing: p.CreateDirectoryIfMissing, Title: p.Title}}})
	if err != nil {
		return "", fmt.Errorf("CreateCodex: %w", err)
	}
	if resp.GetError() != nil {
		return "", hostError("CreateCodex", resp.GetError())
	}
	if result := resp.GetCreateCodex(); result == nil || result.Codex == nil || result.Codex.CodexId == "" {
		return "", fmt.Errorf("CreateCodex returned mismatched response")
	} else {
		return result.Codex.CodexId, nil
	}
}

func (c *protocolClient) ImportSession(ctx context.Context, p importSessionPayload) (string, error) {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ImportSession{ImportSession: &remotev1.ImportSessionRequest{SessionId: p.SessionID, Source: p.Source}}})
	if err != nil {
		return "", fmt.Errorf("ImportSession: %w", err)
	}
	if resp.GetError() != nil {
		return "", hostError("ImportSession", resp.GetError())
	}
	if result := resp.GetImportSession(); result == nil || result.Codex == nil || result.Codex.CodexId == "" {
		return "", fmt.Errorf("ImportSession returned mismatched response")
	} else {
		return result.Codex.CodexId, nil
	}
}

func (c *protocolClient) RenameCodex(ctx context.Context, p renameCodexPayload) error {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_RenameCodex{RenameCodex: &remotev1.RenameCodexRequest{CodexId: p.CodexID, Title: p.Title}}})
	if err != nil {
		return fmt.Errorf("RenameCodex: %w", err)
	}
	if resp.GetError() != nil {
		return hostError("RenameCodex", resp.GetError())
	}
	if result := resp.GetRenameCodex(); result == nil || result.Codex == nil || result.Codex.CodexId != p.CodexID {
		return fmt.Errorf("RenameCodex returned mismatched response")
	}
	return nil
}

func (c *protocolClient) UnmanageCodex(ctx context.Context, codexID string) error {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_UnmanageCodex{UnmanageCodex: &remotev1.UnmanageCodexRequest{CodexId: codexID}}})
	if err != nil {
		return fmt.Errorf("UnmanageCodex: %w", err)
	}
	if resp.GetError() != nil {
		return hostError("UnmanageCodex", resp.GetError())
	}
	if result := resp.GetUnmanageCodex(); result == nil || result.Codex == nil || result.Codex.CodexId != codexID {
		return fmt.Errorf("UnmanageCodex returned mismatched response")
	}
	return nil
}

func (c *protocolClient) ForgetCodex(ctx context.Context, codexID string) error {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ForgetCodex{ForgetCodex: &remotev1.ForgetCodexRequest{CodexId: codexID}}})
	if err != nil {
		return fmt.Errorf("ForgetCodex: %w", err)
	}
	if resp.GetError() != nil {
		return hostError("ForgetCodex", resp.GetError())
	}
	if result := resp.GetForgetCodex(); result == nil || result.CodexId != codexID {
		return fmt.Errorf("ForgetCodex returned mismatched response")
	}
	return nil
}

func (c *protocolClient) WorkspaceSupport() (workspaceLimits, bool, error) {
	workspace := c.hello.GetCapabilities().GetWorkspace()
	if workspace == nil {
		return workspaceLimits{}, false, nil
	}
	limits := workspaceLimits{
		MaxTextFileBytes:        workspace.MaxTextFileBytes,
		MaxInlineUploadBytes:    workspace.MaxInlineUploadBytes,
		MaxInlineDownloadBytes:  workspace.MaxInlineDownloadBytes,
		MaxArchiveExpandedBytes: workspace.MaxArchiveExpandedBytes,
		MaxArchiveEntryCount:    workspace.MaxArchiveEntryCount,
	}
	if limits.MaxTextFileBytes == 0 || limits.MaxInlineUploadBytes == 0 || limits.MaxInlineDownloadBytes == 0 || limits.MaxArchiveExpandedBytes == 0 || limits.MaxArchiveEntryCount == 0 {
		return workspaceLimits{}, false, newWorkspaceOperationError("capability_not_supported", "Host advertised invalid zero workspace limits")
	}
	return limits, true, nil
}

func (c *protocolClient) GetWorkspace(ctx context.Context, codexID string) (workspaceDescriptor, error) {
	if _, supported, err := c.WorkspaceSupport(); err != nil {
		return workspaceDescriptor{}, err
	} else if !supported {
		return workspaceDescriptor{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_GetWorkspace{GetWorkspace: &remotev1.GetWorkspaceRequest{CodexId: codexID}}})
	if err != nil {
		return workspaceDescriptor{}, fmt.Errorf("GetWorkspace: %w", err)
	}
	if resp.GetError() != nil {
		return workspaceDescriptor{}, workspaceHostError("GetWorkspace", resp.GetError())
	}
	result := resp.GetGetWorkspace()
	if result == nil || result.CodexId != codexID || result.AccessState == nil {
		return workspaceDescriptor{}, newWorkspaceOperationError("operation_failed", "GetWorkspace returned mismatched response")
	}
	return workspaceDescriptor{WorkspaceRoot: result.WorkspaceRoot, AccessState: workspaceAccessStateFromProto(result.AccessState)}, nil
}

func (c *protocolClient) ListWorkspaceEntries(ctx context.Context, codexID, relativeDirectory string) (workspaceDirectory, error) {
	if _, supported, err := c.WorkspaceSupport(); err != nil {
		return workspaceDirectory{}, err
	} else if !supported {
		return workspaceDirectory{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	out := workspaceDirectory{RelativeDirectory: relativeDirectory, Entries: []workspaceEntry{}}
	pageToken, seenTokens := "", map[string]bool{}
	pageSize := uint32(listPageSize)
	if advertised := c.hello.GetCapabilities().GetMaxPageSize(); advertised > 0 && advertised < pageSize {
		pageSize = advertised
	}
	for {
		resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ListWorkspaceEntries{ListWorkspaceEntries: &remotev1.ListWorkspaceEntriesRequest{
			CodexId: codexID, RelativeDirectory: relativeDirectory, Page: &remotev1.PageRequest{PageSize: pageSize, PageToken: pageToken},
		}}})
		if err != nil {
			return workspaceDirectory{}, fmt.Errorf("ListWorkspaceEntries: %w", err)
		}
		if resp.GetError() != nil {
			return workspaceDirectory{}, workspaceHostError("ListWorkspaceEntries", resp.GetError())
		}
		result := resp.GetListWorkspaceEntries()
		if result == nil || result.CodexId != codexID || result.RelativeDirectory != relativeDirectory {
			return workspaceDirectory{}, newWorkspaceOperationError("operation_failed", "ListWorkspaceEntries returned mismatched codexId or relativeDirectory")
		}
		for _, entry := range result.Entries {
			if entry == nil {
				return workspaceDirectory{}, newWorkspaceOperationError("operation_failed", "ListWorkspaceEntries returned a nil entry")
			}
			out.Entries = append(out.Entries, workspaceEntryFromProto(entry))
		}
		next := result.GetPage().GetNextPageToken()
		if next == "" {
			return out, nil
		}
		if seenTokens[next] {
			return workspaceDirectory{}, newWorkspaceOperationError("operation_failed", "ListWorkspaceEntries repeated page token")
		}
		seenTokens[next], pageToken = true, next
	}
}

func (c *protocolClient) ReadWorkspaceTextFile(ctx context.Context, codexID, relativePath string) (workspaceOpenFile, error) {
	limits, supported, err := c.WorkspaceSupport()
	if err != nil {
		return workspaceOpenFile{}, err
	}
	if !supported {
		return workspaceOpenFile{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ReadWorkspaceTextFile{ReadWorkspaceTextFile: &remotev1.ReadWorkspaceTextFileRequest{CodexId: codexID, RelativePath: relativePath}}})
	if err != nil {
		return workspaceOpenFile{}, fmt.Errorf("ReadWorkspaceTextFile: %w", err)
	}
	if resp.GetError() != nil {
		return workspaceOpenFile{}, workspaceHostError("ReadWorkspaceTextFile", resp.GetError())
	}
	result := resp.GetReadWorkspaceTextFile()
	if result == nil || result.Entry == nil || result.Entry.RelativePath != relativePath || result.Entry.Kind != remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE || !result.Entry.TextViewable {
		return workspaceOpenFile{}, newWorkspaceOperationError("operation_failed", "ReadWorkspaceTextFile returned mismatched entry")
	}
	if uint64(len([]byte(result.Utf8Text))) > limits.MaxTextFileBytes {
		return workspaceOpenFile{}, newWorkspaceOperationError("workspace_text_too_large", "ReadWorkspaceTextFile response exceeds maxTextFileBytes")
	}
	return workspaceOpenFile{Entry: workspaceEntryFromProto(result.Entry), UTF8Text: result.Utf8Text}, nil
}

func (c *protocolClient) WriteWorkspaceTextFile(ctx context.Context, p writeWorkspaceTextFilePayload) (workspaceWriteResult, error) {
	limits, supported, err := c.WorkspaceSupport()
	if err != nil {
		return workspaceWriteResult{}, err
	}
	if !supported {
		return workspaceWriteResult{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	if uint64(len([]byte(p.UTF8Text))) > limits.MaxTextFileBytes {
		return workspaceWriteResult{}, newWorkspaceOperationError("workspace_text_too_large", "workspace text exceeds maxTextFileBytes")
	}
	condition, err := workspaceWriteConditionFromString(p.Condition)
	if err != nil {
		return workspaceWriteResult{}, err
	}
	if p.ExpectedQuiescenceToken == "" || (condition == remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_CREATE_ONLY && p.ExpectedRevision != "") || (condition == remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_REPLACE_ONLY && p.ExpectedRevision == "") {
		return workspaceWriteResult{}, newWorkspaceOperationError("invalid_request", "invalid workspace write revision or quiescence precondition")
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_WriteWorkspaceTextFile{WriteWorkspaceTextFile: &remotev1.WriteWorkspaceTextFileRequest{
		CodexId: p.CodexID, RelativePath: p.RelativePath, Utf8Text: p.UTF8Text, ExpectedRevision: p.ExpectedRevision,
		ExpectedQuiescenceToken: p.ExpectedQuiescenceToken, Condition: condition,
	}}})
	if err != nil {
		return workspaceWriteResult{}, fmt.Errorf("WriteWorkspaceTextFile: %w", err)
	}
	if resp.GetError() != nil {
		return workspaceWriteResult{}, workspaceHostError("WriteWorkspaceTextFile", resp.GetError())
	}
	result := resp.GetWriteWorkspaceTextFile()
	if result == nil || result.Entry == nil || result.Entry.RelativePath != p.RelativePath || result.Entry.Kind != remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE {
		return workspaceWriteResult{}, newWorkspaceOperationError("operation_failed", "WriteWorkspaceTextFile returned mismatched entry")
	}
	return workspaceWriteResult{Entry: workspaceEntryFromProto(result.Entry), Deduplicated: result.Deduplicated}, nil
}

func (c *protocolClient) UploadWorkspaceEntry(ctx context.Context, p workspaceUploadRequest) (workspaceUploadResult, error) {
	limits, supported, err := c.WorkspaceSupport()
	if err != nil {
		return workspaceUploadResult{}, err
	}
	if !supported {
		return workspaceUploadResult{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	if err := validateWorkspaceRelativePath(p.DestinationPath, false); err != nil {
		return workspaceUploadResult{}, err
	}
	if uint64(len(p.Content)) > limits.MaxInlineUploadBytes {
		return workspaceUploadResult{}, newWorkspaceOperationError("workspace_upload_too_large", "workspace upload exceeds maxInlineUploadBytes")
	}
	kind, entryKind, err := workspaceUploadKindFromString(p.Kind)
	if err != nil {
		return workspaceUploadResult{}, err
	}
	if p.ExpectedQuiescenceToken == "" {
		return workspaceUploadResult{}, newWorkspaceOperationError("invalid_request", "expectedQuiescenceToken is required")
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_UploadWorkspaceEntry{UploadWorkspaceEntry: &remotev1.UploadWorkspaceEntryRequest{
		CodexId: p.CodexID, DestinationPath: p.DestinationPath, Kind: kind, Content: p.Content, ExpectedQuiescenceToken: p.ExpectedQuiescenceToken,
	}}})
	if err != nil {
		return workspaceUploadResult{}, fmt.Errorf("UploadWorkspaceEntry: %w", err)
	}
	if resp.GetError() != nil {
		return workspaceUploadResult{}, workspaceHostError("UploadWorkspaceEntry", resp.GetError())
	}
	result := resp.GetUploadWorkspaceEntry()
	if result == nil || result.Entry == nil || result.Entry.RelativePath != p.DestinationPath || result.Entry.Kind != entryKind {
		return workspaceUploadResult{}, newWorkspaceOperationError("operation_failed", "UploadWorkspaceEntry returned mismatched entry")
	}
	return workspaceUploadResult{Entry: workspaceEntryFromProto(result.Entry), Deduplicated: result.Deduplicated}, nil
}

func (c *protocolClient) DownloadWorkspaceEntry(ctx context.Context, codexID, relativePath string) (workspaceDownloadResult, error) {
	limits, supported, err := c.WorkspaceSupport()
	if err != nil {
		return workspaceDownloadResult{}, err
	}
	if !supported {
		return workspaceDownloadResult{}, newWorkspaceOperationError("capability_not_supported", "workspace capability is not supported")
	}
	if err := validateWorkspaceRelativePath(relativePath, true); err != nil {
		return workspaceDownloadResult{}, err
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_DownloadWorkspaceEntry{DownloadWorkspaceEntry: &remotev1.DownloadWorkspaceEntryRequest{CodexId: codexID, RelativePath: relativePath}}})
	if err != nil {
		return workspaceDownloadResult{}, fmt.Errorf("DownloadWorkspaceEntry: %w", err)
	}
	if resp.GetError() != nil {
		return workspaceDownloadResult{}, workspaceHostError("DownloadWorkspaceEntry", resp.GetError())
	}
	result := resp.GetDownloadWorkspaceEntry()
	if result == nil || result.Entry == nil || result.Entry.RelativePath != relativePath {
		return workspaceDownloadResult{}, newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned mismatched entry")
	}
	kind, err := validateWorkspaceDownloadAssociation(relativePath, result.Entry, result.Kind, result.Filename)
	if err != nil {
		return workspaceDownloadResult{}, err
	}
	if uint64(len(result.Content)) > limits.MaxInlineDownloadBytes {
		return workspaceDownloadResult{}, newWorkspaceOperationError("workspace_download_too_large", "DownloadWorkspaceEntry response exceeds maxInlineDownloadBytes")
	}
	return workspaceDownloadResult{Entry: workspaceEntryFromProto(result.Entry), Kind: kind, Filename: result.Filename, ContentBase64: base64.StdEncoding.EncodeToString(result.Content)}, nil
}

func validateWorkspaceDownloadAssociation(relativePath string, entry *remotev1.WorkspaceEntry, downloadKind remotev1.WorkspaceDownloadKind, filename string) (string, error) {
	if entry == nil || entry.RelativePath != relativePath {
		return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned mismatched entry")
	}
	kind, entryKind, err := workspaceDownloadKindString(downloadKind)
	if err != nil || entry.Kind != entryKind {
		return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned invalid kind")
	}
	if filename == "" || filename == "." || filename == ".." || strings.Contains(filename, "\\") || strings.ContainsRune(filename, '\x00') || path.Base(filename) != filename {
		return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned invalid filename")
	}
	if relativePath == "" {
		if entry.Name != "." || kind != "zip_directory" || len(filename) <= len(".zip") || !strings.HasSuffix(filename, ".zip") {
			return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned mismatched root download metadata")
		}
		stem := strings.TrimSuffix(filename, ".zip")
		if stem == "" || stem == "." || stem == ".." {
			return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned invalid root archive filename")
		}
		return kind, nil
	}
	expectedName := path.Base(relativePath)
	if entry.Name != expectedName {
		return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned mismatched entry name")
	}
	if (kind == "regular_file" && filename != entry.Name) || (kind == "zip_directory" && filename != entry.Name+".zip") {
		return "", newWorkspaceOperationError("operation_failed", "DownloadWorkspaceEntry returned mismatched filename")
	}
	return kind, nil
}

func (s *liveSession) WorkspaceSupport() (workspaceLimits, bool, error) {
	return s.client.WorkspaceSupport()
}

func (s *liveSession) GetWorkspace(ctx context.Context, codexID string) (workspaceDescriptor, error) {
	return s.client.GetWorkspace(ctx, codexID)
}

func (s *liveSession) ListWorkspaceEntries(ctx context.Context, codexID, relativeDirectory string) (workspaceDirectory, error) {
	return s.client.ListWorkspaceEntries(ctx, codexID, relativeDirectory)
}

func (s *liveSession) ReadWorkspaceTextFile(ctx context.Context, codexID, relativePath string) (workspaceOpenFile, error) {
	return s.client.ReadWorkspaceTextFile(ctx, codexID, relativePath)
}

func (s *liveSession) WriteWorkspaceTextFile(ctx context.Context, p writeWorkspaceTextFilePayload) (workspaceWriteResult, error) {
	return s.client.WriteWorkspaceTextFile(ctx, p)
}

func (s *liveSession) UploadWorkspaceEntry(ctx context.Context, p workspaceUploadRequest) (workspaceUploadResult, error) {
	return s.client.UploadWorkspaceEntry(ctx, p)
}

func (s *liveSession) DownloadWorkspaceEntry(ctx context.Context, codexID, relativePath string) (workspaceDownloadResult, error) {
	return s.client.DownloadWorkspaceEntry(ctx, codexID, relativePath)
}

func workspaceAccessStateFromProto(access *remotev1.WorkspaceAccessState) *workspaceAccessState {
	if access == nil {
		return nil
	}
	return &workspaceAccessState{
		MutationStatus: workspaceMutationStatusString(access.MutationStatus), ActiveAgentCount: access.ActiveAgentCount,
		QuiescenceToken: access.QuiescenceToken, ObservedAtUnixMS: access.ObservedAtUnixMs, Generation: access.Generation,
	}
}

func workspaceEntryFromProto(entry *remotev1.WorkspaceEntry) workspaceEntry {
	if entry == nil {
		return workspaceEntry{Kind: "unspecified"}
	}
	return workspaceEntry{
		RelativePath: entry.RelativePath, Name: entry.Name, Kind: workspaceEntryKindString(entry.Kind), SizeBytes: entry.SizeBytes,
		ModifiedAtUnixMS: entry.ModifiedAtUnixMs, Revision: entry.Revision, TextViewable: entry.TextViewable, TextEditable: entry.TextEditable,
	}
}

func workspaceEntryKindString(kind remotev1.WorkspaceEntryKind) string {
	switch kind {
	case remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE:
		return "regular_file"
	case remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY:
		return "directory"
	case remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_SYMBOLIC_LINK:
		return "symbolic_link"
	case remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_OTHER:
		return "other"
	default:
		return "unspecified"
	}
}

func workspaceMutationStatusString(status remotev1.WorkspaceMutationStatus) string {
	switch status {
	case remotev1.WorkspaceMutationStatus_WORKSPACE_MUTATION_STATUS_ALLOWED:
		return "allowed"
	case remotev1.WorkspaceMutationStatus_WORKSPACE_MUTATION_STATUS_BUSY:
		return "busy"
	default:
		return "unspecified"
	}
}

func workspaceWriteConditionFromString(condition string) (remotev1.WorkspaceWriteCondition, error) {
	switch condition {
	case "create_only":
		return remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_CREATE_ONLY, nil
	case "replace_only":
		return remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_REPLACE_ONLY, nil
	case "upsert":
		return remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_UPSERT, nil
	default:
		return remotev1.WorkspaceWriteCondition_WORKSPACE_WRITE_CONDITION_UNSPECIFIED, newWorkspaceOperationError("invalid_request", "condition must be create_only, replace_only, or upsert")
	}
}

func workspaceUploadKindFromString(kind string) (remotev1.WorkspaceUploadKind, remotev1.WorkspaceEntryKind, error) {
	switch kind {
	case "regular_file":
		return remotev1.WorkspaceUploadKind_WORKSPACE_UPLOAD_KIND_REGULAR_FILE, remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, nil
	case "zip_directory":
		return remotev1.WorkspaceUploadKind_WORKSPACE_UPLOAD_KIND_ZIP_DIRECTORY, remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY, nil
	default:
		return remotev1.WorkspaceUploadKind_WORKSPACE_UPLOAD_KIND_UNSPECIFIED, remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_UNSPECIFIED, newWorkspaceOperationError("invalid_request", "kind must be regular_file or zip_directory")
	}
}

func workspaceDownloadKindString(kind remotev1.WorkspaceDownloadKind) (string, remotev1.WorkspaceEntryKind, error) {
	switch kind {
	case remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_REGULAR_FILE:
		return "regular_file", remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_REGULAR_FILE, nil
	case remotev1.WorkspaceDownloadKind_WORKSPACE_DOWNLOAD_KIND_ZIP_DIRECTORY:
		return "zip_directory", remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_DIRECTORY, nil
	default:
		return "", remotev1.WorkspaceEntryKind_WORKSPACE_ENTRY_KIND_UNSPECIFIED, newWorkspaceOperationError("operation_failed", "invalid workspace download kind")
	}
}

func workspaceHostError(method string, hostErr *remotev1.Error) error {
	code := strings.ToLower(strings.TrimPrefix(hostErr.Code.String(), "ERROR_CODE_"))
	if code == "" || code == "unspecified" {
		code = "operation_failed"
	}
	return newWorkspaceOperationError(code, fmt.Sprintf("%s Host error: %s", method, hostErr.Message))
}

func (c *protocolClient) ListHistory(ctx context.Context, codexID string) (conversationState, error) {
	conversation := conversationState{CodexID: codexID, Turns: []conversationTurn{}}
	pageToken := ""
	seenTokens := map[string]bool{}
	for pageNumber := 0; pageNumber < maxListPages; pageNumber++ {
		resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_ListHistory{ListHistory: &remotev1.ListHistoryRequest{
			CodexId: codexID, Page: &remotev1.PageRequest{PageSize: 100, PageToken: pageToken},
		}}})
		if err != nil {
			return conversationState{}, fmt.Errorf("ListHistory: %w", err)
		}
		if resp.GetError() != nil {
			return conversationState{}, hostError("ListHistory", resp.GetError())
		}
		result := resp.GetListHistory()
		if result == nil || result.History == nil {
			return conversationState{}, fmt.Errorf("ListHistory returned mismatched response")
		}
		history := result.History
		if history.CodexId != "" && history.CodexId != codexID {
			return conversationState{}, fmt.Errorf("ListHistory returned codex %q, want %q", history.CodexId, codexID)
		}
		for _, turn := range history.Turns {
			conversation.Turns = append(conversation.Turns, conversationTurnFromProto(turn))
		}
		next := history.GetPage().GetNextPageToken()
		if next == "" {
			conversation.HistoryComplete = history.HistoryComplete
			setConversationActivity(&conversation)
			return conversation, nil
		}
		if seenTokens[next] {
			return conversationState{}, fmt.Errorf("ListHistory repeated page token")
		}
		seenTokens[next] = true
		pageToken = next
	}
	conversation.HistoryComplete = false
	setConversationActivity(&conversation)
	return conversation, nil
}

func (c *protocolClient) StartTurn(ctx context.Context, codexID, text string, options *turnOptionsPayload) (string, error) {
	req := &remotev1.StartTurnRequest{
		CodexId: codexID,
		Input:   []*remotev1.UserInputPart{{Content: &remotev1.UserInputPart_Text{Text: &remotev1.TextInput{Text: text}}}},
	}
	if options != nil {
		req.Options = &remotev1.TurnOptions{Model: options.Model, Mode: options.Mode, ApprovalPolicy: options.ApprovalPolicy, ReasoningEffort: options.ReasoningEffort}
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_StartTurn{StartTurn: req}})
	if err != nil {
		return "", fmt.Errorf("StartTurn: %w", err)
	}
	if resp.GetError() != nil {
		return "", hostError("StartTurn", resp.GetError())
	}
	result := resp.GetStartTurn()
	if result == nil || result.TurnId == "" {
		return "", fmt.Errorf("StartTurn returned mismatched response")
	}
	return result.TurnId, nil
}

func (c *protocolClient) InterruptTurn(ctx context.Context, codexID, turnID string) (string, error) {
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_InterruptTurn{InterruptTurn: &remotev1.InterruptTurnRequest{CodexId: codexID, TurnId: turnID}}})
	if err != nil {
		return "", fmt.Errorf("InterruptTurn: %w", err)
	}
	if resp.GetError() != nil {
		return "", hostError("InterruptTurn", resp.GetError())
	}
	result := resp.GetInterruptTurn()
	if result == nil {
		return "", fmt.Errorf("InterruptTurn returned mismatched response")
	}
	return result.TurnId, nil
}

func (c *protocolClient) WatchPending(ctx context.Context, codexID string) (pendingWatchReset, *protocolPendingWatch, error) {
	watch := &protocolPendingWatch{client: c, codexID: codexID, inbox: make(chan *remotev1.Event, 64), overflow: make(chan struct{})}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return pendingWatchReset{}, nil, fmt.Errorf("Host connection is closed")
	}
	if c.eventSink != nil {
		c.mu.Unlock()
		return pendingWatchReset{}, nil, fmt.Errorf("a Codex watch is already active")
	}
	// Register before the RPC write: replay/live events may arrive immediately
	// after the response and must not race sink installation.
	c.eventSink = watch
	c.mu.Unlock()
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_WatchCodex{WatchCodex: &remotev1.WatchCodexRequest{CodexId: codexID}}})
	if err != nil {
		c.detachPendingWatch(watch)
		return pendingWatchReset{}, nil, fmt.Errorf("WatchCodex: %w", err)
	}
	if resp.GetError() != nil {
		c.detachPendingWatch(watch)
		return pendingWatchReset{}, nil, pendingHostError("WatchCodex", resp.GetError())
	}
	result := resp.GetWatchCodex()
	if result == nil || result.CodexId != codexID || result.Mode != remotev1.WatchMode_WATCH_MODE_RESET || result.ResetView == nil || result.ResetView.Codex == nil || result.ResetView.Codex.CodexId != codexID || result.HeadEventSeq != result.ResetView.HeadEventSeq {
		c.detachPendingWatch(watch)
		return pendingWatchReset{}, nil, fmt.Errorf("WatchCodex initial response is not a strictly associated RESET")
	}
	requests, err := pendingRequestsFromProto(result.ResetView.PendingRequests)
	if err != nil {
		c.detachPendingWatch(watch)
		return pendingWatchReset{}, nil, fmt.Errorf("WatchCodex RESET pending requests: %w", err)
	}
	return pendingWatchReset{HeadEventSeq: result.HeadEventSeq, Requests: requests}, watch, nil
}

func (c *protocolClient) UnwatchPending(ctx context.Context, watch *protocolPendingWatch) error {
	if watch == nil {
		return nil
	}
	c.detachPendingWatch(watch)
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_UnwatchCodex{UnwatchCodex: &remotev1.UnwatchCodexRequest{CodexId: watch.codexID}}})
	if err != nil {
		return fmt.Errorf("UnwatchCodex: %w", err)
	}
	if resp.GetError() != nil {
		return pendingHostError("UnwatchCodex", resp.GetError())
	}
	if result := resp.GetUnwatchCodex(); result == nil || result.CodexId != watch.codexID {
		return fmt.Errorf("UnwatchCodex returned mismatched response")
	}
	return nil
}

func (c *protocolClient) detachPendingWatch(watch *protocolPendingWatch) {
	c.mu.Lock()
	if c.eventSink == watch {
		c.eventSink = nil
	}
	c.mu.Unlock()
}

func (w *protocolPendingWatch) Next(ctx context.Context) (pendingWatchEvent, error) {
	select {
	case <-ctx.Done():
		return pendingWatchEvent{}, ctx.Err()
	case <-w.client.ctx.Done():
		return pendingWatchEvent{}, fmt.Errorf("Host connection closed")
	case <-w.overflow:
		return pendingWatchEvent{}, fmt.Errorf("watch event inbox overflow")
	case event := <-w.inbox:
		return pendingWatchEventFromProto(event)
	}
}

func (c *protocolClient) RespondApproval(ctx context.Context, codexID, approvalID, decision string) (pendingResponseResult, error) {
	decisionProto, err := approvalDecisionFromString(decision)
	if err != nil {
		return pendingResponseResult{}, err
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_RespondApproval{RespondApproval: &remotev1.RespondApprovalRequest{CodexId: codexID, ApprovalId: approvalID, Decision: decisionProto}}})
	if err != nil {
		return pendingResponseResult{}, fmt.Errorf("RespondApproval: %w", err)
	}
	if resp.GetError() != nil {
		return pendingResponseResult{}, pendingHostError("RespondApproval", resp.GetError())
	}
	result := resp.GetRespondApproval()
	if result == nil || result.Approval == nil || result.Approval.ApprovalId != approvalID || result.Approval.Status == remotev1.ApprovalStatus_APPROVAL_STATUS_PENDING || result.Approval.Status == remotev1.ApprovalStatus_APPROVAL_STATUS_UNSPECIFIED || result.Approval.ResolvedDecision != decisionProto {
		return pendingResponseResult{}, fmt.Errorf("RespondApproval returned mismatched or unresolved response")
	}
	return pendingResponseResult{Type: "approval", RequestID: approvalID, TurnID: result.Approval.TurnId, ItemID: result.Approval.ItemId}, nil
}

func (c *protocolClient) RespondUserInput(ctx context.Context, codexID, requestID string, answers []pendingUserInputAnswer) (pendingResponseResult, error) {
	protoAnswers := make([]*remotev1.UserInputAnswer, 0, len(answers))
	for _, answer := range answers {
		protoAnswers = append(protoAnswers, &remotev1.UserInputAnswer{QuestionId: answer.QuestionID, SelectedOptionIds: append([]string{}, answer.SelectedOptionIDs...), FreeFormText: answer.FreeFormText})
	}
	resp, err := c.call(ctx, &remotev1.Request{Request: &remotev1.Request_RespondUserInput{RespondUserInput: &remotev1.RespondUserInputRequest{CodexId: codexID, UserInputRequestId: requestID, Answers: protoAnswers}}})
	if err != nil {
		return pendingResponseResult{}, fmt.Errorf("RespondUserInput: %w", err)
	}
	if resp.GetError() != nil {
		return pendingResponseResult{}, pendingHostError("RespondUserInput", resp.GetError())
	}
	result := resp.GetRespondUserInput()
	if result == nil || result.Request == nil || result.Request.UserInputRequestId != requestID || !result.Request.Resolved {
		return pendingResponseResult{}, fmt.Errorf("RespondUserInput returned mismatched or unresolved response")
	}
	return pendingResponseResult{Type: "user_input", RequestID: requestID, TurnID: result.Request.TurnId, ItemID: result.Request.ItemId}, nil
}

func pendingRequestsFromProto(requests []*remotev1.PendingRequest) ([]pendingRequest, error) {
	out := []pendingRequest{}
	seen := map[string]bool{}
	for _, request := range requests {
		projected, actionable, err := pendingRequestFromProto(request)
		if err != nil {
			return nil, err
		}
		if !actionable {
			continue
		}
		if seen[projected.RequestID] {
			return nil, fmt.Errorf("duplicate pending request %q", projected.RequestID)
		}
		seen[projected.RequestID] = true
		out = append(out, projected)
	}
	return out, nil
}

func pendingWatchEventFromProto(event *remotev1.Event) (pendingWatchEvent, error) {
	if event == nil || event.CodexId == "" || event.EventSeq == 0 {
		return pendingWatchEvent{}, fmt.Errorf("invalid Event envelope")
	}
	out := pendingWatchEvent{CodexID: event.CodexId, EventSeq: event.EventSeq}
	updated := event.GetPendingRequestUpdated()
	if updated == nil {
		return out, nil
	}
	projected, actionable, err := pendingRequestFromProto(updated.Request)
	if err != nil {
		return pendingWatchEvent{}, err
	}
	out.HasUpdate, out.RequestID, out.Actionable, out.Request = true, projected.RequestID, actionable, projected
	return out, nil
}

func pendingRequestFromProto(request *remotev1.PendingRequest) (pendingRequest, bool, error) {
	if request == nil {
		return pendingRequest{}, false, fmt.Errorf("nil PendingRequest")
	}
	switch body := request.Request.(type) {
	case *remotev1.PendingRequest_Approval:
		approval := body.Approval
		if approval == nil || approval.ApprovalId == "" || approval.TurnId == "" || approval.ItemId == "" {
			return pendingRequest{}, false, fmt.Errorf("invalid approval pending request")
		}
		out := pendingRequest{Type: "approval", RequestID: approval.ApprovalId, TurnID: approval.TurnId, ItemID: approval.ItemId}
		if approval.Status != remotev1.ApprovalStatus_APPROVAL_STATUS_PENDING {
			return out, false, nil
		}
		decisions := make([]string, 0, len(approval.AllowedDecisions))
		seen := map[string]bool{}
		for _, decision := range approval.AllowedDecisions {
			value, err := approvalDecisionString(decision)
			if err != nil || seen[value] {
				return pendingRequest{}, false, fmt.Errorf("invalid approval allowed decision")
			}
			seen[value] = true
			decisions = append(decisions, value)
		}
		out.Approval = &pendingApproval{Kind: approval.Kind, Status: "pending", Title: approval.Title, Explanation: approval.Explanation, Command: append([]string{}, approval.Command...), AllowedDecisions: decisions}
		return out, true, nil
	case *remotev1.PendingRequest_UserInput:
		userInput := body.UserInput
		if userInput == nil || userInput.UserInputRequestId == "" || userInput.TurnId == "" || userInput.ItemId == "" {
			return pendingRequest{}, false, fmt.Errorf("invalid user-input pending request")
		}
		out := pendingRequest{Type: "user_input", RequestID: userInput.UserInputRequestId, TurnID: userInput.TurnId, ItemID: userInput.ItemId}
		if userInput.Resolved {
			return out, false, nil
		}
		if len(userInput.Questions) < 1 {
			return pendingRequest{}, false, fmt.Errorf("user-input request must contain at least one question")
		}
		questions := make([]pendingUserInputQuestion, 0, len(userInput.Questions))
		seenQuestions := map[string]bool{}
		for _, question := range userInput.Questions {
			if question == nil || question.QuestionId == "" || seenQuestions[question.QuestionId] {
				return pendingRequest{}, false, fmt.Errorf("invalid user-input question")
			}
			seenQuestions[question.QuestionId] = true
			options := make([]pendingUserInputOption, 0, len(question.Options))
			seenOptions := map[string]bool{}
			for _, option := range question.Options {
				if option == nil || option.OptionId == "" || seenOptions[option.OptionId] {
					return pendingRequest{}, false, fmt.Errorf("invalid user-input option")
				}
				seenOptions[option.OptionId] = true
				options = append(options, pendingUserInputOption{OptionID: option.OptionId, Label: option.Label, Description: option.Description})
			}
			questions = append(questions, pendingUserInputQuestion{QuestionID: question.QuestionId, Header: question.Header, Prompt: question.Prompt, Options: options, AllowsMultiple: question.AllowsMultiple, AllowsFreeForm: question.AllowsFreeForm})
		}
		out.UserInput = &pendingUserInput{Resolved: false, Questions: questions, Completeness: conversationCompletenessFromProto(userInput.Completeness)}
		return out, true, nil
	default:
		return pendingRequest{}, false, fmt.Errorf("unknown PendingRequest content")
	}
}

func conversationCompletenessFromProto(completeness *remotev1.Completeness) *conversationCompleteness {
	if completeness == nil {
		return nil
	}
	return &conversationCompleteness{Truncated: completeness.Truncated, Incomplete: completeness.Incomplete, OriginalSizeBytes: completeness.OriginalSizeBytes, Reason: completeness.Reason}
}

func approvalDecisionString(decision remotev1.ApprovalDecision) (string, error) {
	switch decision {
	case remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW:
		return "allow", nil
	case remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW_FOR_SESSION:
		return "allow_for_session", nil
	case remotev1.ApprovalDecision_APPROVAL_DECISION_DENY:
		return "deny", nil
	default:
		return "", fmt.Errorf("invalid approval decision")
	}
}

func approvalDecisionFromString(decision string) (remotev1.ApprovalDecision, error) {
	switch decision {
	case "allow":
		return remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW, nil
	case "allow_for_session":
		return remotev1.ApprovalDecision_APPROVAL_DECISION_ALLOW_FOR_SESSION, nil
	case "deny":
		return remotev1.ApprovalDecision_APPROVAL_DECISION_DENY, nil
	default:
		return remotev1.ApprovalDecision_APPROVAL_DECISION_UNSPECIFIED, fmt.Errorf("invalid approval decision")
	}
}

func pendingHostError(method string, hostErr *remotev1.Error) error {
	code := strings.ToLower(strings.TrimPrefix(hostErr.Code.String(), "ERROR_CODE_"))
	if code == "" || code == "unspecified" {
		code = "operation_failed"
	}
	return &pendingProtocolError{Code: code, Message: fmt.Sprintf("%s Host error: %s", method, hostErr.Message)}
}

type pendingProtocolError struct {
	Code    string
	Message string
}

func (e *pendingProtocolError) Error() string { return e.Message }

func conversationTurnFromProto(turn *remotev1.TurnSnapshot) conversationTurn {
	out := conversationTurn{Items: []conversationItem{}, Messages: []conversationMessage{}}
	if turn == nil {
		return out
	}
	out.TurnID = turn.TurnId
	out.Status = turnStatusString(turn.Status)
	out.StartedAtUnixMS = turn.StartedAtUnixMs
	out.CompletedAtUnixMS = turn.CompletedAtUnixMs
	if turn.Failure != nil {
		out.Failure = turn.Failure.Message
	}
	for _, item := range turn.Items {
		projected := conversationItemFromProto(item)
		out.Items = append(out.Items, projected)
		switch projected.Type {
		case "user_message":
			out.Messages = append(out.Messages, conversationMessage{ItemID: projected.ItemID, Role: "user", Text: projected.UserMessage.Text, Status: projected.Status})
		case "agent_message":
			out.Messages = append(out.Messages, conversationMessage{ItemID: projected.ItemID, Role: "assistant", Text: projected.AgentMessage.Text, Status: projected.Status})
		}
	}
	return out
}

func conversationItemFromProto(item *remotev1.Item) conversationItem {
	out := conversationItem{Type: "unknown", Status: "unspecified"}
	if item == nil {
		return out
	}
	out.ItemID = item.ItemId
	out.TurnID = item.TurnId
	out.Status = itemStatusString(item.Status)
	if completeness := item.Completeness; completeness != nil {
		out.Completeness = &conversationCompleteness{
			Truncated:         completeness.Truncated,
			Incomplete:        completeness.Incomplete,
			OriginalSizeBytes: completeness.OriginalSizeBytes,
			Reason:            completeness.Reason,
		}
	}
	switch content := item.Content.(type) {
	case *remotev1.Item_UserMessage:
		if content.UserMessage == nil {
			break
		}
		parts := []string{}
		for _, input := range content.UserMessage.Input {
			if text := input.GetText(); text != nil {
				parts = append(parts, text.Text)
			}
		}
		out.Type = "user_message"
		out.UserMessage = &conversationUserMessage{TextParts: parts, Text: strings.Join(parts, "\n")}
	case *remotev1.Item_AgentMessage:
		if content.AgentMessage == nil {
			break
		}
		out.Type = "agent_message"
		out.AgentMessage = &conversationAgentMessage{Text: content.AgentMessage.Text}
	case *remotev1.Item_ReasoningSummary:
		if content.ReasoningSummary == nil {
			break
		}
		out.Type = "reasoning_summary"
		out.ReasoningSummary = &conversationReasoningSummary{Text: content.ReasoningSummary.Text}
	case *remotev1.Item_Plan:
		if content.Plan == nil {
			break
		}
		steps := make([]conversationPlanStep, 0, len(content.Plan.Steps))
		for _, step := range content.Plan.Steps {
			if step == nil {
				steps = append(steps, conversationPlanStep{})
				continue
			}
			steps = append(steps, conversationPlanStep{Text: step.Text, Status: step.Status})
		}
		out.Type = "plan"
		out.Plan = &conversationPlan{Steps: steps}
	case *remotev1.Item_Command:
		if content.Command == nil {
			break
		}
		command := &conversationCommandItem{
			Argv:        append([]string{}, content.Command.Argv...),
			Cwd:         content.Command.Cwd,
			Output:      content.Command.Output,
			HasExitCode: content.Command.HasExitCode,
		}
		if content.Command.HasExitCode {
			exitCode := content.Command.ExitCode
			command.ExitCode = &exitCode
		}
		out.Type = "command"
		out.Command = command
	case *remotev1.Item_Tool:
		if content.Tool == nil {
			break
		}
		out.Type = "tool"
		out.Tool = &conversationTool{Name: content.Tool.ToolName, Summary: content.Tool.Summary, ResultSummary: content.Tool.ResultSummary}
	case *remotev1.Item_FileChange:
		if content.FileChange == nil {
			break
		}
		changes := make([]conversationFileChangeEntry, 0, len(content.FileChange.Changes))
		for _, change := range content.FileChange.Changes {
			if change == nil {
				changes = append(changes, conversationFileChangeEntry{Kind: "unspecified"})
				continue
			}
			changes = append(changes, conversationFileChangeEntry{
				Path: change.Path, Kind: fileChangeKindString(change.Kind), OldPath: change.OldPath, NewPath: change.NewPath,
			})
		}
		out.Type = "file_change"
		out.FileChange = &conversationFileChange{Changes: changes, UnifiedDiff: content.FileChange.UnifiedDiff}
	}
	return out
}

func fileChangeKindString(kind remotev1.FileChangeKind) string {
	switch kind {
	case remotev1.FileChangeKind_FILE_CHANGE_KIND_ADDED:
		return "added"
	case remotev1.FileChangeKind_FILE_CHANGE_KIND_MODIFIED:
		return "modified"
	case remotev1.FileChangeKind_FILE_CHANGE_KIND_DELETED:
		return "deleted"
	case remotev1.FileChangeKind_FILE_CHANGE_KIND_RENAMED:
		return "renamed"
	default:
		return "unspecified"
	}
}

func setConversationActivity(conversation *conversationState) {
	conversation.ActiveTurnID = ""
	conversation.Running = false
	for _, turn := range conversation.Turns {
		if turn.Status == "running" {
			conversation.ActiveTurnID = turn.TurnID
			conversation.Running = true
		}
	}
}

func turnStatusString(status remotev1.TurnStatus) string {
	switch status {
	case remotev1.TurnStatus_TURN_STATUS_RUNNING:
		return "running"
	case remotev1.TurnStatus_TURN_STATUS_COMPLETED:
		return "completed"
	case remotev1.TurnStatus_TURN_STATUS_FAILED:
		return "failed"
	case remotev1.TurnStatus_TURN_STATUS_INTERRUPTED:
		return "interrupted"
	default:
		return "unspecified"
	}
}

func itemStatusString(status remotev1.ItemStatus) string {
	switch status {
	case remotev1.ItemStatus_ITEM_STATUS_RUNNING:
		return "running"
	case remotev1.ItemStatus_ITEM_STATUS_COMPLETED:
		return "completed"
	case remotev1.ItemStatus_ITEM_STATUS_FAILED:
		return "failed"
	case remotev1.ItemStatus_ITEM_STATUS_CANCELLED:
		return "cancelled"
	default:
		return "unspecified"
	}
}

func hostError(method string, e *remotev1.Error) error {
	return fmt.Errorf("%s Host error %s: %s", method, e.Code.String(), e.Message)
}

func (c *protocolClient) call(ctx context.Context, req *remotev1.Request) (*remotev1.Response, error) {
	id := fmt.Sprintf("android-%d", c.sequence.Add(1))
	req.RequestId = id
	req.SentAtUnixMs = time.Now().UnixMilli()
	if deadline, ok := ctx.Deadline(); ok {
		req.DeadlineUnixMs = deadline.UnixMilli()
	}
	ch := make(chan responseResult, 1)
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, fmt.Errorf("Host connection is closed")
	}
	c.pending[id] = ch
	c.mu.Unlock()
	if err := c.write(ctx, &remotev1.Frame{Payload: &remotev1.Frame_Request{Request: req}}); err != nil {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, err
	}
	select {
	case result := <-ch:
		return result.response, result.err
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, ctx.Err()
	case <-c.ctx.Done():
		return nil, fmt.Errorf("Host connection closed")
	}
}

func (c *protocolClient) readLoop() {
	for {
		typ, raw, err := c.conn.Read(c.ctx)
		if err != nil {
			c.failAll(fmt.Errorf("read Host frame: %w", err))
			return
		}
		if typ != websocket.MessageText {
			c.failAll(fmt.Errorf("Host sent binary frame"))
			return
		}
		if c.hello.MaxFrameBytes > 0 && uint64(len(raw)) > c.hello.MaxFrameBytes {
			c.failAll(fmt.Errorf("Host frame exceeds max_frame_bytes"))
			return
		}
		frame := new(remotev1.Frame)
		if err := (protojson.UnmarshalOptions{DiscardUnknown: false}).Unmarshal(raw, frame); err != nil {
			c.failAll(fmt.Errorf("decode Host frame: %w", err))
			return
		}
		switch body := frame.Payload.(type) {
		case *remotev1.Frame_Response:
			id := body.Response.GetRequestId()
			c.mu.Lock()
			ch := c.pending[id]
			delete(c.pending, id)
			c.mu.Unlock()
			if ch != nil {
				ch <- responseResult{response: body.Response}
			}
		case *remotev1.Frame_Ping:
			ping := body.Ping
			_ = c.write(c.ctx, &remotev1.Frame{Payload: &remotev1.Frame_Pong{Pong: &remotev1.Pong{Nonce: ping.Nonce, PingSentAtUnixMs: ping.SentAtUnixMs, PongSentAtUnixMs: time.Now().UnixMilli()}}})
		case *remotev1.Frame_Event:
			c.mu.Lock()
			watch := c.eventSink
			if watch != nil {
				select {
				case watch.inbox <- body.Event:
				default:
					watch.overflowOnce.Do(func() { close(watch.overflow) })
				}
			}
			c.mu.Unlock()
		case *remotev1.Frame_Pong:
		case *remotev1.Frame_Close:
			c.failAll(fmt.Errorf("Host closed protocol: %s", body.Close.Message))
			return
		default:
			c.failAll(fmt.Errorf("unexpected Host frame after handshake"))
			return
		}
	}
}

func (c *protocolClient) write(ctx context.Context, frame *remotev1.Frame) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-c.ctx.Done():
		return fmt.Errorf("Host connection closed")
	case <-c.writeGate:
	}
	defer func() { c.writeGate <- struct{}{} }()
	writeCtx, cancel := context.WithCancel(ctx)
	stopClientCancel := context.AfterFunc(c.ctx, cancel)
	defer func() {
		stopClientCancel()
		cancel()
	}()
	return writeFrame(writeCtx, c.conn, frame)
}

func newWriteGate() chan struct{} {
	gate := make(chan struct{}, 1)
	gate <- struct{}{}
	return gate
}

func writeFrame(ctx context.Context, conn interface {
	Write(context.Context, websocket.MessageType, []byte) error
}, frame *remotev1.Frame) error {
	raw, err := (protojson.MarshalOptions{}).Marshal(frame)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, raw)
}

func (c *protocolClient) failAll(err error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	pending := c.pending
	c.pending = map[string]chan responseResult{}
	c.mu.Unlock()
	c.cancel()
	for _, ch := range pending {
		ch <- responseResult{err: err}
	}
}

func (c *protocolClient) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.failAll(fmt.Errorf("client closed"))
		_ = c.conn.CloseNow()
	})
	return err
}
