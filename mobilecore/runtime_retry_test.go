package mobilecore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"github.com/coder/websocket"
)

func TestLiveSessionReconnectSwapsOnceForConcurrentWatchFailures(t *testing.T) {
	failed := new(protocolClient)
	replacement := new(protocolClient)
	var calls atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})
	session := &liveSession{client: failed, redial: func(context.Context) (*protocolClient, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		<-release
		return replacement, nil
	}}
	errs := make(chan error, 2)
	go func() { errs <- session.reconnectClient(context.Background(), failed) }()
	<-started
	go func() { errs <- session.reconnectClient(context.Background(), failed) }()
	close(release)
	for range 2 {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	if calls.Load() != 1 || session.currentClient() != replacement {
		t.Fatalf("redials=%d client=%p want=%p", calls.Load(), session.currentClient(), replacement)
	}
}

func TestLiveSessionImageUploadReconnectReusesRequestID(t *testing.T) {
	content := []byte("image")
	digest := "6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"
	seenRequestID := make(chan string, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{WebSocketSubprotocol}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := r.Context()
		_ = readTestFrame(t, ctx, conn)
		hello := completeServerHello(0)
		hello.Capabilities.ImageAttachments = &remotev1.ImageAttachmentCapabilities{Supported: true, MaxUploadBytes: 1024, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMs: 60_000}
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_ServerHello{ServerHello: hello}})
		request := readTestFrame(t, ctx, conn).GetRequest()
		seenRequestID <- request.GetRequestId()
		writeTestFrame(t, ctx, conn, &remotev1.Frame{Payload: &remotev1.Frame_Response{Response: &remotev1.Response{RequestId: request.RequestId, Result: &remotev1.Response_UploadImageAttachment{UploadImageAttachment: &remotev1.UploadImageAttachmentResponse{Attachment: &remotev1.ImageAttachment{AttachmentId: "ATTACH-1", Filename: "photo.png", MimeType: "image/png", SizeBytes: uint64(len(content)), Sha256: digest}}}}}})
	}))
	defer server.Close()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	replacement, err := dialProtocol(ctx, configPayload{HostEndpoint: "fake-host", ClientID: "client", ClientRunID: "run", ClientName: "test", ClientVersion: "test"}, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer replacement.Close()
	failed := &protocolClient{closed: true}
	session := &liveSession{client: failed, redial: func(context.Context) (*protocolClient, error) { return replacement, nil }}
	result, err := session.UploadImageAttachment(ctx, "UPLOAD-STABLE", imageAttachmentUploadRequest{CodexID: "CODEX-1", Filename: "photo.png", MimeType: "image/png", Content: content, SHA256: digest})
	if err != nil || result.Attachment.AttachmentID != "ATTACH-1" {
		t.Fatalf("upload after reconnect=%+v err=%v", result, err)
	}
	if got := <-seenRequestID; got != "UPLOAD-STABLE" {
		t.Fatalf("retry request ID=%q, want stable command ID", got)
	}
}

func TestLiveSessionImageAttachmentSupportUsesCurrentClientAfterReconnect(t *testing.T) {
	firstHello := completeServerHello(0)
	firstHello.Capabilities.ImageAttachments = &remotev1.ImageAttachmentCapabilities{Supported: true, MaxUploadBytes: 1024, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMs: 60_000}
	secondHello := completeServerHello(0)
	secondHello.Capabilities.ImageAttachments = &remotev1.ImageAttachmentCapabilities{Supported: true, MaxUploadBytes: 256, SupportedMimeTypes: []string{"image/jpeg"}, UnreferencedRetentionMs: 30_000}
	session := &liveSession{client: &protocolClient{hello: firstHello}}
	before, supported, err := session.ImageAttachmentSupport()
	if err != nil || !supported || before.MaxUploadBytes != 1024 || before.SupportedMimeTypes[0] != "image/png" {
		t.Fatalf("initial capabilities=%+v supported=%t err=%v", before, supported, err)
	}
	session.mu.Lock()
	session.client = &protocolClient{hello: secondHello}
	session.mu.Unlock()
	after, supported, err := session.ImageAttachmentSupport()
	if err != nil || !supported || after.MaxUploadBytes != 256 || after.SupportedMimeTypes[0] != "image/jpeg" || after.UnreferencedRetentionMS != 30_000 {
		t.Fatalf("reconnected capabilities=%+v supported=%t err=%v", after, supported, err)
	}
}

func TestReconnectDialRetriesServerUnavailableButNotProtocolMismatch(t *testing.T) {
	if !isRetryableReconnectDial(&hostDialError{status: 503, err: errors.New("unavailable")}, nil) {
		t.Fatal("503 should be retryable after an established connection")
	}
	if isRetryableReconnectDial(errors.New("Host protocol mismatch"), nil) {
		t.Fatal("protocol mismatch must not retry forever")
	}
}

func TestDialWebSocketClassifiesNoResponseAsTransientStatusZero(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan error, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			acceptErr = conn.Close()
		}
		accepted <- acceptErr
	}()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err = dialWebSocket(ctx, "fake-host", dial)
	if acceptErr := <-accepted; acceptErr != nil {
		t.Fatalf("controlled listener: %v", acceptErr)
	}
	var dialErr *hostDialError
	if !errors.As(err, &dialErr) || dialErr.status != 0 || !isTransientInitialHostDial(err) {
		t.Fatalf("error=%T %v status=%d transient=%t", err, err, dialErrStatus(err), isTransientInitialHostDial(err))
	}
}

func TestDialWebSocketClassifiesHTTP503AsNonTransient(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "not ready", http.StatusServiceUnavailable)
	}))
	defer server.Close()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err := dialWebSocket(ctx, "fake-host", dial)
	var dialErr *hostDialError
	if !errors.As(err, &dialErr) || dialErr.status != http.StatusServiceUnavailable || isTransientInitialHostDial(err) {
		t.Fatalf("error=%T %v status=%d transient=%t", err, err, dialErrStatus(err), isTransientInitialHostDial(err))
	}
}

func dialErrStatus(err error) int {
	var dialErr *hostDialError
	if errors.As(err, &dialErr) {
		return dialErr.status
	}
	return -1
}

func TestRetryInitialHostDialRetriesNoHTTPResponseThenSucceeds(t *testing.T) {
	want := new(protocolClient)
	attempts := 0
	waits := 0
	got, err := retryInitialHostDial(context.Background(), time.Second, func(context.Context) (*protocolClient, error) {
		attempts++
		if attempts < 3 {
			return nil, &hostDialError{status: 0, err: io.EOF}
		}
		return want, nil
	}, func(context.Context, time.Duration) error {
		waits++
		return nil
	})
	if err != nil || got != want {
		t.Fatalf("retry result=(%p, %v), want=(%p, nil)", got, err, want)
	}
	if attempts != 3 || waits != 2 {
		t.Fatalf("attempts=%d waits=%d, want 3 and 2", attempts, waits)
	}
}

func TestRetryInitialHostDialDoesNotRetryHTTPOrProtocolFailure(t *testing.T) {
	tests := []error{
		&hostDialError{status: 503, err: errors.New("unavailable")},
		errors.New("Host protocol is 1.0.0; require 1.1.2"),
	}
	for _, wantErr := range tests {
		t.Run(wantErr.Error(), func(t *testing.T) {
			attempts := 0
			_, err := retryInitialHostDial(context.Background(), time.Second, func(context.Context) (*protocolClient, error) {
				attempts++
				return nil, wantErr
			}, func(context.Context, time.Duration) error {
				t.Fatal("permanent failure unexpectedly waited for retry")
				return nil
			})
			if !errors.Is(err, wantErr) || attempts != 1 {
				t.Fatalf("error=%v attempts=%d, want original error and one attempt", err, attempts)
			}
		})
	}
}

func TestRetryInitialHostDialBoundsPersistentTransientFailure(t *testing.T) {
	attempts := 0
	waits := 0
	_, err := retryInitialHostDial(context.Background(), time.Second, func(context.Context) (*protocolClient, error) {
		attempts++
		return nil, &hostDialError{status: 0, err: io.EOF}
	}, func(context.Context, time.Duration) error {
		waits++
		return nil
	})
	if attempts != hostDialMaxAttempts || waits != hostDialMaxAttempts-1 {
		t.Fatalf("attempts=%d waits=%d", attempts, waits)
	}
	if err == nil || !strings.Contains(err.Error(), fmt.Sprintf("after %d attempts", hostDialMaxAttempts)) || !errors.Is(err, io.EOF) {
		t.Fatalf("bounded retry error=%v", err)
	}
}

func TestRetryInitialHostDialCancellationStopsDuringWait(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	attempts := 0
	_, err := retryInitialHostDial(ctx, time.Second, func(context.Context) (*protocolClient, error) {
		attempts++
		return nil, &hostDialError{status: 0, err: io.EOF}
	}, func(ctx context.Context, _ time.Duration) error {
		cancel()
		return ctx.Err()
	})
	if !errors.Is(err, context.Canceled) || attempts != 1 {
		t.Fatalf("error=%v attempts=%d, want canceled after one attempt", err, attempts)
	}
}

func TestRetryInitialHostDialHonorsExpiredOverallTimeout(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Nanosecond)
	defer cancel()
	<-ctx.Done()
	attempts := 0
	_, err := retryInitialHostDial(ctx, time.Second, func(context.Context) (*protocolClient, error) {
		attempts++
		return nil, &hostDialError{status: 0, err: io.EOF}
	}, func(context.Context, time.Duration) error {
		t.Fatal("expired context unexpectedly waited")
		return nil
	})
	if !errors.Is(err, context.DeadlineExceeded) || attempts != 1 {
		t.Fatalf("error=%v attempts=%d, want deadline after one attempt", err, attempts)
	}
}

func TestRetryInitialHostDialRetriesAttemptDeadlineThenSucceeds(t *testing.T) {
	want := new(protocolClient)
	attempts := 0
	waits := 0
	got, err := retryInitialHostDial(context.Background(), time.Millisecond, func(ctx context.Context) (*protocolClient, error) {
		attempts++
		if attempts == 1 {
			<-ctx.Done()
			return nil, fmt.Errorf("WebSocket attempt: %w", ctx.Err())
		}
		return want, nil
	}, func(context.Context, time.Duration) error {
		waits++
		return nil
	})
	if err != nil || got != want {
		t.Fatalf("retry result=(%p, %v), want=(%p, nil)", got, err, want)
	}
	if attempts != 2 || waits != 1 {
		t.Fatalf("attempts=%d waits=%d, want 2 and 1", attempts, waits)
	}
}

func TestRetryInitialHostDialParentCancellationStopsActiveAttempt(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	attempts := 0
	waits := 0
	_, err := retryInitialHostDial(ctx, time.Second, func(attemptCtx context.Context) (*protocolClient, error) {
		attempts++
		cancel()
		<-attemptCtx.Done()
		return nil, attemptCtx.Err()
	}, func(context.Context, time.Duration) error {
		waits++
		return nil
	})
	if !errors.Is(err, context.Canceled) || attempts != 1 || waits != 0 {
		t.Fatalf("error=%v attempts=%d waits=%d, want canceled after one active attempt", err, attempts, waits)
	}
}
