package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"github.com/coder/websocket"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

type snapshot struct {
	TailnetIPs  []string
	ServerHello json.RawMessage
	Host        json.RawMessage
	Codexes     json.RawMessage
}

type productionStarter struct{ platform Platform }

const (
	hostDialMaxAttempts    = 4
	hostDialAttemptTimeout = 15 * time.Second
	hostDialRetryDelay     = 500 * time.Millisecond
)

// hostDialError marks failures that happened before a WebSocket connection was
// established. A status of zero means that no valid HTTP response was received;
// that may be transient while a freshly started Tailnet's peer map settles.
type hostDialError struct {
	status int
	err    error
}

func (e *hostDialError) Error() string {
	return fmt.Sprintf("dial Host WebSocket (HTTP %d): %v", e.status, e.err)
}

func (e *hostDialError) Unwrap() error { return e.err }

type liveSession struct {
	tailnet         *tsnet.Server
	cfg             configPayload
	mu              sync.RWMutex
	reconnectMu     sync.Mutex
	client          *protocolClient
	redial          func(context.Context) (*protocolClient, error)
	ips             []string
	releaseInjector func()
	closeOnce       sync.Once
	closeErr        error
}

var authURLPattern = regexp.MustCompile(`https://login\.tailscale\.com/[^\s]+`)

func (s productionStarter) Start(ctx context.Context, cfg configPayload, progress func(string, string)) (session, snapshot, error) {
	if s.platform == nil {
		return nil, snapshot{}, fmt.Errorf("Android platform hooks are required")
	}
	if err := installPlatformHooks(s.platform); err != nil {
		return nil, snapshot{}, fmt.Errorf("install Android network hooks: %w", err)
	}
	if err := os.MkdirAll(filepath.Clean(cfg.StateDir), 0o700); err != nil {
		return nil, snapshot{}, fmt.Errorf("create tailnet state directory: %w", err)
	}
	if err := configureTailscaleLogsDir(cfg.StateDir); err != nil {
		return nil, snapshot{}, err
	}
	server := &tsnet.Server{
		Hostname: cfg.Hostname,
		Dir:      filepath.Clean(cfg.StateDir),
		AuthKey:  cfg.AuthKey,
		// tsnet documents interactive login URLs on UserLogf. Logf is the
		// verbose backend logger and does not satisfy this user-visible contract.
		UserLogf: authProgressLogf(progress),
	}
	if err := server.Start(); err != nil {
		return nil, snapshot{}, fmt.Errorf("start userspace tailnet: %w", err)
	}
	sys := server.Sys()
	if sys == nil {
		return nil, snapshot{}, fmt.Errorf("userspace tailnet did not expose its system")
	}
	monitor, ok := sys.NetMon.GetOK()
	if !ok || monitor == nil {
		return nil, snapshot{}, fmt.Errorf("userspace tailnet did not expose its network monitor")
	}
	releaseInjector := installNetworkEventInjector(monitor)
	cleanup := true
	defer func() {
		if cleanup {
			releaseInjector()
			_ = server.Close()
		}
	}()
	status, err := server.Up(ctx)
	if err != nil {
		return nil, snapshot{}, fmt.Errorf("bring userspace tailnet up: %w", err)
	}
	progress("connecting_host", "")
	client, err := retryInitialHostDial(ctx, hostDialAttemptTimeout, func(attemptCtx context.Context) (*protocolClient, error) {
		return dialProtocol(attemptCtx, cfg, server.Dial)
	}, waitHostDialRetry)
	if err != nil {
		return nil, snapshot{}, fmt.Errorf("%w; %s", err, tailnetEndpointDiagnostic(ctx, server, status, cfg.HostEndpoint))
	}
	ls := &liveSession{tailnet: server, cfg: cfg, client: client, ips: statusIPs(status), releaseInjector: releaseInjector}
	ls.redial = func(redialCtx context.Context) (*protocolClient, error) {
		return dialProtocol(redialCtx, cfg, server.Dial)
	}
	snap, err := ls.Refresh(ctx)
	if err != nil {
		_ = client.Close()
		return nil, snapshot{}, err
	}
	cleanup = false
	return ls, snap, nil
}

func retryInitialHostDial(
	ctx context.Context,
	attemptTimeout time.Duration,
	dial func(context.Context) (*protocolClient, error),
	wait func(context.Context, time.Duration) error,
) (*protocolClient, error) {
	var lastErr error
	for attempt := 1; attempt <= hostDialMaxAttempts; attempt++ {
		attemptCtx, cancelAttempt := context.WithTimeout(ctx, attemptTimeout)
		client, err := dial(attemptCtx)
		attemptErr := attemptCtx.Err()
		cancelAttempt()
		if err == nil {
			return client, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if !isRetryableInitialHostDial(err, attemptErr) {
			return nil, err
		}
		if attempt == hostDialMaxAttempts {
			break
		}
		if err := wait(ctx, hostDialRetryDelay); err != nil {
			return nil, err
		}
	}
	return nil, fmt.Errorf("Host WebSocket unavailable after %d attempts: %w", hostDialMaxAttempts, lastErr)
}

func isRetryableInitialHostDial(err, attemptErr error) bool {
	var dialErr *hostDialError
	if errors.As(err, &dialErr) && dialErr.status != 0 {
		return false
	}
	return isTransientInitialHostDial(err) ||
		(errors.Is(attemptErr, context.DeadlineExceeded) && errors.Is(err, context.DeadlineExceeded))
}

func isTransientInitialHostDial(err error) bool {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return false
	}
	var dialErr *hostDialError
	return errors.As(err, &dialErr) && dialErr.status == 0
}

func waitHostDialRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func tailnetEndpointDiagnostic(ctx context.Context, server *tsnet.Server, status *ipnstate.Status, endpoint string) string {
	target, peerName, peerKnown, peerOnline, magicDNS := endpointPeerStatus(status, endpoint)
	ping := "not_run"
	if target.IsValid() {
		ping = "failed"
		pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		if local, err := server.LocalClient(); err == nil {
			if result, err := local.Ping(pingCtx, target, tailcfg.PingTSMP); err == nil && result != nil && result.Err == "" {
				ping = "ok"
			}
		}
	}
	return fmt.Sprintf("tailnet diagnostic: magic_dns=%t peer_known=%t peer_online=%t peer=%s tsmp=%s", magicDNS, peerKnown, peerOnline, peerName, ping)
}

func endpointPeerStatus(status *ipnstate.Status, endpoint string) (target netip.Addr, name string, known, online, magicDNS bool) {
	if status == nil {
		return netip.Addr{}, "unknown", false, false, false
	}
	if status.CurrentTailnet != nil {
		magicDNS = status.CurrentTailnet.MagicDNSEnabled
	}
	normalized, err := normalizeEndpoint(endpoint)
	if err != nil {
		return netip.Addr{}, "unknown", false, false, magicDNS
	}
	u, err := url.Parse(normalized)
	if err != nil {
		return netip.Addr{}, "unknown", false, false, magicDNS
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	wantedIP, hostIsIP := netip.ParseAddr(host)
	for _, peer := range status.Peer {
		if peer == nil {
			continue
		}
		match := strings.EqualFold(peer.HostName, host) || strings.EqualFold(strings.TrimSuffix(peer.DNSName, "."), host)
		if hostIsIP == nil {
			for _, ip := range peer.TailscaleIPs {
				if ip == wantedIP {
					match = true
					break
				}
			}
		}
		if !match {
			continue
		}
		name = peer.HostName
		if name == "" {
			name = "unnamed"
		}
		if hostIsIP == nil {
			target = wantedIP
		} else if len(peer.TailscaleIPs) > 0 {
			target = peer.TailscaleIPs[0]
		}
		return target, name, true, peer.Online, magicDNS
	}
	if hostIsIP == nil {
		target = wantedIP
	}
	return target, "unknown", false, false, magicDNS
}

// configureTailscaleLogsDir supplies the process-wide log directory expected
// by auxiliary LocalBackend loggers. tsnet uses Server.Dir for its own logger,
// but sockstatlog independently calls logpolicy.LogsDir. Android has no usable
// os.UserCacheDir and commonly runs with / as its working directory, so letting
// that lookup fall through can panic while trying to create a temporary dir.
func configureTailscaleLogsDir(stateDir string) error {
	logsDir := filepath.Join(filepath.Clean(stateDir), "logs")
	if err := os.MkdirAll(logsDir, 0o700); err != nil {
		return fmt.Errorf("create tailnet log directory: %w", err)
	}
	if err := os.Setenv("TS_LOGS_DIR", logsDir); err != nil {
		return fmt.Errorf("configure tailnet log directory: %w", err)
	}
	return nil
}

func authProgressLogf(progress func(string, string)) func(string, ...any) {
	return func(format string, args ...any) {
		if u := authURLPattern.FindString(fmt.Sprintf(format, args...)); u != "" {
			progress("auth_required", u)
		}
	}
}

func statusIPs(status *ipnstate.Status) []string {
	if status == nil {
		return nil
	}
	out := make([]string, 0, len(status.TailscaleIPs))
	for _, ip := range status.TailscaleIPs {
		out = append(out, ip.String())
	}
	return out
}

func (s *liveSession) Refresh(ctx context.Context) (snapshot, error) {
	client := s.currentClient()
	host, codexes, err := client.Fetch(ctx)
	if err != nil {
		return snapshot{}, err
	}
	return snapshot{TailnetIPs: append([]string(nil), s.ips...), ServerHello: client.ServerHelloJSON(), Host: host, Codexes: codexes}, nil
}

func (s *liveSession) currentClient() *protocolClient {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.client
}

func (s *liveSession) reconnectClient(ctx context.Context, failed *protocolClient) error {
	s.reconnectMu.Lock()
	defer s.reconnectMu.Unlock()
	if current := s.currentClient(); current != failed {
		return nil
	}
	for {
		attemptCtx, cancel := context.WithTimeout(ctx, hostDialAttemptTimeout)
		redial := s.redial
		if redial == nil {
			redial = func(redialCtx context.Context) (*protocolClient, error) {
				return dialProtocol(redialCtx, s.cfg, s.tailnet.Dial)
			}
		}
		client, err := redial(attemptCtx)
		attemptErr := attemptCtx.Err()
		cancel()
		if err == nil {
			s.mu.Lock()
			if s.client == failed {
				s.client = client
			} else {
				_ = client.Close()
			}
			s.mu.Unlock()
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if !isRetryableReconnectDial(err, attemptErr) {
			return err
		}
		if err := waitHostDialRetry(ctx, hostDialRetryDelay); err != nil {
			return err
		}
	}
}

func isRetryableReconnectDial(err, attemptErr error) bool {
	var dialErr *hostDialError
	if errors.As(err, &dialErr) && dialErr.status >= 500 {
		return true
	}
	return isRetryableInitialHostDial(err, attemptErr)
}

func (s *liveSession) ListHistory(ctx context.Context, codexID string) (conversationState, error) {
	return s.currentClient().ListHistory(ctx, codexID)
}

func (s *liveSession) StartTurn(ctx context.Context, commandID, codexID, text string, options *turnOptionsPayload) (string, error) {
	return s.currentClient().StartTurn(ctx, commandID, codexID, text, options)
}

func (s *liveSession) InterruptTurn(ctx context.Context, codexID, turnID string) (string, error) {
	return s.currentClient().InterruptTurn(ctx, codexID, turnID)
}

func (s *liveSession) WatchPending(ctx context.Context, codexID string, cursor *pendingWatchCursor) (pendingWatchReset, *protocolPendingWatch, error) {
	for {
		client := s.currentClient()
		start, watch, err := client.WatchPending(ctx, codexID, cursor)
		if err == nil {
			return start, watch, nil
		}
		if !errors.Is(err, errHostConnectionClosed) {
			return pendingWatchReset{}, nil, err
		}
		if err := s.reconnectClient(ctx, client); err != nil {
			return pendingWatchReset{}, nil, err
		}
	}
}

func (s *liveSession) UnwatchPending(ctx context.Context, watch *protocolPendingWatch) error {
	return watch.client.UnwatchPending(ctx, watch)
}

func (s *liveSession) RespondApproval(ctx context.Context, codexID, approvalID, decision string) (pendingResponseResult, error) {
	return s.currentClient().RespondApproval(ctx, codexID, approvalID, decision)
}

func (s *liveSession) RespondUserInput(ctx context.Context, codexID, requestID string, answers []pendingUserInputAnswer) (pendingResponseResult, error) {
	return s.currentClient().RespondUserInput(ctx, codexID, requestID, answers)
}

func (s *liveSession) ListDirectories(ctx context.Context, parentPath string) (directoryListing, error) {
	return s.currentClient().ListDirectories(ctx, parentPath)
}

func (s *liveSession) ListSessionCandidates(ctx context.Context, cwd string) (sessionCandidatesState, error) {
	return s.currentClient().ListSessionCandidates(ctx, cwd)
}

func (s *liveSession) CreateCodex(ctx context.Context, p createCodexPayload) (string, error) {
	return s.currentClient().CreateCodex(ctx, p)
}

func (s *liveSession) ImportSession(ctx context.Context, p importSessionPayload) (string, error) {
	return s.currentClient().ImportSession(ctx, p)
}

func (s *liveSession) RenameCodex(ctx context.Context, p renameCodexPayload) error {
	return s.currentClient().RenameCodex(ctx, p)
}

func (s *liveSession) UnmanageCodex(ctx context.Context, codexID string) error {
	return s.currentClient().UnmanageCodex(ctx, codexID)
}

func (s *liveSession) ForgetCodex(ctx context.Context, codexID string) error {
	return s.currentClient().ForgetCodex(ctx, codexID)
}

func (s *liveSession) Close() error {
	s.closeOnce.Do(func() {
		// Close the WebSocket first so an in-flight Refresh/write is interrupted
		// before shutting down the userspace Tailnet.
		_ = s.currentClient().Close()
		if s.releaseInjector != nil {
			s.releaseInjector()
		}
		s.closeErr = s.tailnet.Close()
	})
	return s.closeErr
}

func normalizeEndpoint(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", fmt.Errorf("Host endpoint is required")
	}
	if !strings.Contains(raw, "://") {
		raw = "ws://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("parse Host endpoint: %w", err)
	}
	if u.Scheme != "ws" || u.Hostname() == "" {
		return "", fmt.Errorf("Host endpoint must be a plain ws URL or Tailnet hostname")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return "", fmt.Errorf("Host endpoint must not contain credentials, query, or fragment")
	}
	if u.Port() == "" {
		u.Host = net.JoinHostPort(u.Hostname(), "80")
	}
	if u.Port() != "80" {
		return "", fmt.Errorf("Tailnet Host endpoint port must be 80")
	}
	if u.Path == "" || u.Path == "/" {
		u.Path = "/connect"
	}
	if u.Path != "/connect" {
		return "", fmt.Errorf("Host endpoint path must be /connect")
	}
	return u.String(), nil
}

func dialWebSocket(ctx context.Context, endpoint string, dial func(context.Context, string, string) (net.Conn, error)) (*websocket.Conn, error) {
	u, err := normalizeEndpoint(endpoint)
	if err != nil {
		return nil, err
	}
	hc := &http.Client{Transport: &http.Transport{DialContext: dial, ForceAttemptHTTP2: false, Proxy: nil}}
	conn, resp, err := websocket.Dial(ctx, u, &websocket.DialOptions{HTTPClient: hc, Subprotocols: []string{WebSocketSubprotocol}, CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
			if resp.Body != nil {
				_ = resp.Body.Close()
			}
		}
		return nil, &hostDialError{status: status, err: err}
	}
	if conn.Subprotocol() != WebSocketSubprotocol {
		_ = conn.Close(websocket.StatusProtocolError, "required subprotocol not negotiated")
		return nil, fmt.Errorf("Host negotiated WebSocket subprotocol %q", conn.Subprotocol())
	}
	conn.SetReadLimit(8 << 20)
	return conn, nil
}

var _ = remotev1.ProtocolVersion{}
