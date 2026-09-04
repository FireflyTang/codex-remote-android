package mobilecore

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type imageAttachmentFakeSession struct {
	*fakeSession
	uploadRequestID string
	uploadRequest   imageAttachmentUploadRequest
}

type mutableImageAttachmentSession struct {
	*imageAttachmentFakeSession
	mu           sync.Mutex
	capabilities imageAttachmentCapabilities
	supported    bool
	err          error
}

func (s *mutableImageAttachmentSession) ImageAttachmentSupport() (imageAttachmentCapabilities, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.capabilities, s.supported, s.err
}

func (s *mutableImageAttachmentSession) setCapabilities(capabilities imageAttachmentCapabilities, supported bool, err error) {
	s.mu.Lock()
	s.capabilities, s.supported, s.err = capabilities, supported, err
	s.mu.Unlock()
}

type blockingImageAttachmentSession struct {
	*imageAttachmentFakeSession
	uploadStarted    chan struct{}
	uploadRelease    chan struct{}
	uploadReturned   chan struct{}
	downloadStarted  chan struct{}
	downloadRelease  chan struct{}
	downloadReturned chan struct{}
}

func newBlockingImageAttachmentSession() *blockingImageAttachmentSession {
	return &blockingImageAttachmentSession{
		imageAttachmentFakeSession: &imageAttachmentFakeSession{fakeSession: &fakeSession{}},
		uploadStarted:              make(chan struct{}), uploadRelease: make(chan struct{}), uploadReturned: make(chan struct{}),
		downloadStarted: make(chan struct{}), downloadRelease: make(chan struct{}), downloadReturned: make(chan struct{}),
	}
}

func (s *blockingImageAttachmentSession) UploadImageAttachment(ctx context.Context, requestID string, request imageAttachmentUploadRequest) (imageAttachmentUploadResult, error) {
	close(s.uploadStarted)
	<-s.uploadRelease // Deliberately model a transport that returns after cancellation.
	defer close(s.uploadReturned)
	return s.imageAttachmentFakeSession.UploadImageAttachment(ctx, requestID, request)
}

func (s *blockingImageAttachmentSession) DownloadImageAttachment(ctx context.Context, requestID, codexID, attachmentID string) (imageAttachmentDownloadResult, error) {
	close(s.downloadStarted)
	<-s.downloadRelease // Deliberately model a transport that returns after cancellation.
	defer close(s.downloadReturned)
	return s.imageAttachmentFakeSession.DownloadImageAttachment(ctx, requestID, codexID, attachmentID)
}

func (s *imageAttachmentFakeSession) ImageAttachmentSupport() (imageAttachmentCapabilities, bool, error) {
	return imageAttachmentCapabilities{MaxUploadBytes: 1024, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMS: 60_000}, true, nil
}

func (s *imageAttachmentFakeSession) UploadImageAttachment(_ context.Context, requestID string, request imageAttachmentUploadRequest) (imageAttachmentUploadResult, error) {
	s.uploadRequestID, s.uploadRequest = requestID, request
	return imageAttachmentUploadResult{Attachment: imageAttachmentDescriptor{AttachmentID: "ATTACH-1", Filename: request.Filename, MimeType: request.MimeType, SizeBytes: uint64(len(request.Content)), SHA256: request.SHA256, WidthPixels: uint32Pointer(2), HeightPixels: uint32Pointer(3)}}, nil
}

func (*imageAttachmentFakeSession) DownloadImageAttachment(_ context.Context, _, _, attachmentID string) (imageAttachmentDownloadResult, error) {
	return imageAttachmentDownloadResult{Attachment: imageAttachmentDescriptor{AttachmentID: attachmentID, Filename: "photo.png", MimeType: "image/png", SizeBytes: 5, SHA256: "6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d", WidthPixels: uint32Pointer(2), HeightPixels: uint32Pointer(3)}, ContentBase64: "aW1hZ2U="}, nil
}

func uint32Pointer(value uint32) *uint32 { return &value }

func TestCoreImageAttachmentUploadDownloadJSONAndErrors(t *testing.T) {
	sess := &imageAttachmentFakeSession{fakeSession: &fakeSession{}}
	c := NewCore(new(fakePlatform))
	c.session = sess
	c.state.Phase = "ready"
	c.state.SelectedCodexID = "CODEX-1"
	c.setImageAttachmentCapabilitiesLocked(sess)

	uploading := decodeState(t, c.Dispatch(`{"version":1,"id":"UPLOAD-STABLE","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}`))
	if uploading.ImageAttachments == nil || uploading.ImageAttachments.Loading != "upload" {
		t.Fatalf("upload did not publish async loading state: %+v", uploading.ImageAttachments)
	}
	uploaded := waitImageAttachmentIdle(t, c, "UPLOAD-STABLE")
	if uploaded.Error != nil || uploaded.UploadResult == nil || uploaded.UploadResult.Attachment.AttachmentID != "ATTACH-1" || uploaded.UploadResult.Attachment.WidthPixels == nil || *uploaded.UploadResult.Attachment.WidthPixels != 2 {
		t.Fatalf("upload state=%+v", uploaded)
	}
	if sess.uploadRequestID != "UPLOAD-STABLE" || sess.uploadRequest.CodexID != "CODEX-1" || string(sess.uploadRequest.Content) != "image" {
		t.Fatalf("upload request lost request identity or bytes: id=%q request=%+v", sess.uploadRequestID, sess.uploadRequest)
	}

	downloading := decodeState(t, c.Dispatch(`{"version":1,"id":"DOWNLOAD-1","type":"download_image_attachment","payload":{"codexId":"CODEX-1","attachmentId":"ATTACH-1"}}`))
	if downloading.ImageAttachments.Loading != "download" {
		t.Fatalf("download did not publish async loading state: %+v", downloading.ImageAttachments)
	}
	downloaded := waitImageAttachmentIdle(t, c, "DOWNLOAD-1")
	if downloaded.Error != nil || downloaded.DownloadResult == nil || downloaded.DownloadResult.ContentBase64 != "aW1hZ2U=" || downloaded.DownloadResult.Attachment.AttachmentID != "ATTACH-1" {
		t.Fatalf("download state=%+v", downloaded)
	}

	rejected := decodeState(t, c.Dispatch(`{"version":1,"id":"BAD-HASH","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"0000000000000000000000000000000000000000000000000000000000000000"}}`))
	if rejected.ImageAttachments.Error == nil || rejected.ImageAttachments.Error.Code != "image_attachment_hash_mismatch" {
		t.Fatalf("bad hash error=%+v", rejected.ImageAttachments.Error)
	}
}

func TestCoreImageAttachmentUsesCurrentCapabilitiesForEveryOperationAndRefresh(t *testing.T) {
	sess := &mutableImageAttachmentSession{imageAttachmentFakeSession: &imageAttachmentFakeSession{fakeSession: &fakeSession{}}}
	sess.setCapabilities(imageAttachmentCapabilities{MaxUploadBytes: 1024, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMS: 60_000}, true, nil)
	c := NewCore(new(fakePlatform))
	c.session = sess
	c.state.Phase = "ready"
	c.state.SelectedCodexID = "CODEX-1"
	c.setImageAttachmentCapabilitiesLocked(sess)

	sess.setCapabilities(imageAttachmentCapabilities{MaxUploadBytes: 4, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMS: 30_000}, true, nil)
	tooLarge := decodeState(t, c.Dispatch(`{"version":1,"id":"LOWERED","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}`))
	if tooLarge.ImageAttachments.MaxUploadBytes != 4 || tooLarge.ImageAttachments.UnreferencedRetentionMS != 30_000 || tooLarge.ImageAttachments.Error == nil || tooLarge.ImageAttachments.Error.Code != "image_attachment_too_large" {
		t.Fatalf("lowered capability was not used: %+v", tooLarge.ImageAttachments)
	}

	sess.setCapabilities(imageAttachmentCapabilities{MaxUploadBytes: 1024, SupportedMimeTypes: []string{"image/jpeg"}, UnreferencedRetentionMS: 45_000}, true, nil)
	wrongMIME := decodeState(t, c.Dispatch(`{"version":1,"id":"MIME-CHANGED","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}`))
	if len(wrongMIME.ImageAttachments.SupportedMimeTypes) != 1 || wrongMIME.ImageAttachments.SupportedMimeTypes[0] != "image/jpeg" || wrongMIME.ImageAttachments.Error == nil || wrongMIME.ImageAttachments.Error.Code != "image_attachment_mime_type_unsupported" {
		t.Fatalf("changed MIME capability was not used: %+v", wrongMIME.ImageAttachments)
	}

	sess.setCapabilities(imageAttachmentCapabilities{}, false, errors.New("stale capability read"))
	c.Dispatch(`{"version":1,"id":"REFRESH-ERROR","type":"refresh"}`)
	waitCoreState(t, c, func(got state) bool { return got.CommandID == "REFRESH-ERROR" && got.Phase == "ready" })
	c.mu.Lock()
	refreshError := c.state.ImageAttachments.Error
	c.mu.Unlock()
	if refreshError == nil || refreshError.Message != "stale capability read" {
		t.Fatalf("refresh capability error was not published: %+v", refreshError)
	}

	sess.setCapabilities(imageAttachmentCapabilities{MaxUploadBytes: 2048, SupportedMimeTypes: []string{"image/png"}, UnreferencedRetentionMS: 90_000}, true, nil)
	c.Dispatch(`{"version":1,"id":"REFRESH-RECOVERED","type":"refresh"}`)
	waitCoreState(t, c, func(got state) bool { return got.CommandID == "REFRESH-RECOVERED" && got.Phase == "ready" })
	c.mu.Lock()
	recovered := *c.state.ImageAttachments
	c.mu.Unlock()
	if recovered.Error != nil || !recovered.Supported || recovered.MaxUploadBytes != 2048 || recovered.UnreferencedRetentionMS != 90_000 || len(recovered.SupportedMimeTypes) != 1 || recovered.SupportedMimeTypes[0] != "image/png" {
		t.Fatalf("recovered capability state is stale: %+v", recovered)
	}

	uploading := decodeState(t, c.Dispatch(`{"version":1,"id":"RAISED","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}`))
	if uploading.ImageAttachments.Loading != "upload" {
		t.Fatalf("raised capability did not admit upload: %+v", uploading.ImageAttachments)
	}
	if uploaded := waitImageAttachmentIdle(t, c, "RAISED"); uploaded.Error != nil || uploaded.UploadResult == nil {
		t.Fatalf("raised capability upload failed: %+v", uploaded)
	}
}

func TestValidImageMediaTypeStrictness(t *testing.T) {
	for _, valid := range []string{"image/png", "image/vnd.example+photo"} {
		if !validImageMediaType(valid) {
			t.Errorf("valid media type rejected: %q", valid)
		}
	}
	for _, invalid := range []string{"", "image/", " image/png", "image/png ", "IMAGE/PNG", "image/PNG", "image/png; charset=binary", "image/p ng", "text/png"} {
		if validImageMediaType(invalid) {
			t.Errorf("invalid media type accepted: %q", invalid)
		}
	}
}

func TestImageAttachmentLateCompletionCannotOverwriteConversationTransitions(t *testing.T) {
	tests := []struct {
		name         string
		operation    string
		transition   string
		transitionID string
		wantCodexID  string
		wantPhase    string
		wantStopped  bool
	}{
		{name: "select cancels upload", operation: "upload", transition: `{"version":1,"id":"SELECT-NEW","type":"select_codex","payload":{"codexId":"CODEX-2"}}`, transitionID: "SELECT-NEW", wantCodexID: "CODEX-2", wantPhase: "ready"},
		{name: "refresh cancels download", operation: "download", transition: `{"version":1,"id":"REFRESH","type":"refresh"}`, transitionID: "REFRESH", wantCodexID: "CODEX-1", wantPhase: "ready"},
		{name: "conversation refresh cancels upload", operation: "upload", transition: `{"version":1,"id":"REFRESH-CONVERSATION","type":"refresh_conversation"}`, transitionID: "REFRESH-CONVERSATION", wantCodexID: "CODEX-1", wantPhase: "ready"},
		{name: "stop cancels download", operation: "download", transition: `{"version":1,"id":"STOP","type":"stop"}`, transitionID: "STOP", wantPhase: "stopped", wantStopped: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			sess := newBlockingImageAttachmentSession()
			c := NewCore(new(fakePlatform))
			c.session = sess
			c.state.Phase = "ready"
			c.state.SelectedCodexID = "CODEX-1"
			c.state.Conversation = &conversationState{CodexID: "CODEX-1", Turns: []conversationTurn{}}
			c.setImageAttachmentCapabilitiesLocked(sess)
			c.state.ImageAttachments.UploadResult = &imageAttachmentUploadResult{Attachment: imageAttachmentDescriptor{AttachmentID: "STALE-UPLOAD"}}
			c.state.ImageAttachments.DownloadResult = &imageAttachmentDownloadResult{Attachment: imageAttachmentDescriptor{AttachmentID: "STALE-DOWNLOAD"}}

			var started, release, returned chan struct{}
			if tc.operation == "upload" {
				c.Dispatch(`{"version":1,"id":"BLOCKED-UPLOAD","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}`)
				started, release, returned = sess.uploadStarted, sess.uploadRelease, sess.uploadReturned
			} else {
				c.Dispatch(`{"version":1,"id":"BLOCKED-DOWNLOAD","type":"download_image_attachment","payload":{"codexId":"CODEX-1","attachmentId":"ATTACH-1"}}`)
				started, release, returned = sess.downloadStarted, sess.downloadRelease, sess.downloadReturned
			}
			select {
			case <-started:
			case <-time.After(time.Second):
				t.Fatal("image operation did not block")
			}

			c.Dispatch(tc.transition)
			waitCoreState(t, c, func(got state) bool {
				return got.CommandID == tc.transitionID && got.Phase == tc.wantPhase && (tc.wantStopped || got.SelectedCodexID == tc.wantCodexID)
			})
			close(release)
			select {
			case <-returned:
			case <-time.After(time.Second):
				t.Fatal("cancelled image operation did not return")
			}
			time.Sleep(10 * time.Millisecond)

			c.mu.Lock()
			got := c.state
			cancel := c.imageAttachmentCancel
			c.mu.Unlock()
			if got.CommandID != tc.transitionID || got.Phase != tc.wantPhase || cancel != nil {
				t.Fatalf("late completion rolled back transition: command=%q phase=%q cancel=%v", got.CommandID, got.Phase, cancel != nil)
			}
			if tc.wantStopped {
				if got.SelectedCodexID != "" || got.ImageAttachments != nil {
					t.Fatalf("late completion repopulated stopped state: selected=%q image=%+v", got.SelectedCodexID, got.ImageAttachments)
				}
				return
			}
			if got.SelectedCodexID != tc.wantCodexID || got.ImageAttachments == nil || got.ImageAttachments.CodexID != tc.wantCodexID || got.ImageAttachments.Loading != "none" || got.ImageAttachments.Error != nil || got.ImageAttachments.UploadResult != nil || got.ImageAttachments.DownloadResult != nil {
				t.Fatalf("late completion or stale result leaked across transition: selected=%q image=%+v", got.SelectedCodexID, got.ImageAttachments)
			}
			c.stop("cleanup")
		})
	}
}

func waitCoreState(t *testing.T, c *Core, predicate func(state) bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		got := c.state
		c.mu.Unlock()
		if predicate(got) {
			return
		}
		time.Sleep(time.Millisecond)
	}
	c.mu.Lock()
	got := c.state
	c.mu.Unlock()
	t.Fatalf("timed out waiting for core state: %+v", got)
}

func waitImageAttachmentIdle(t *testing.T, c *Core, commandID string) *imageAttachmentState {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		state := decodeState(t, c.State())
		if state.ImageAttachments != nil && state.ImageAttachments.Loading == "none" {
			return state.ImageAttachments
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for image attachment operation %s: %s", commandID, c.State())
	return nil
}
