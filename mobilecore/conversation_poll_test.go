package mobilecore

import (
	"context"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

type conversationPollRaceSession struct {
	fakeSession
	history conversationState
	started chan struct{}
	once    sync.Once
}

func (s *conversationPollRaceSession) ListHistory(_ context.Context, _ string) (conversationState, error) {
	s.once.Do(func() { close(s.started) })
	return s.history, nil
}

func TestNextConversationPollIntervalBacksOffAndResets(t *testing.T) {
	interval := conversationPollFastInterval

	interval = nextConversationPollInterval(interval, true)
	if interval != 500*time.Millisecond {
		t.Fatalf("initial/change interval = %v, want 500ms", interval)
	}
	interval = nextConversationPollInterval(interval, false)
	if interval != time.Second {
		t.Fatalf("first unchanged interval = %v, want 1s", interval)
	}
	interval = nextConversationPollInterval(interval, false)
	if interval != 2*time.Second {
		t.Fatalf("second unchanged interval = %v, want 2s", interval)
	}
	interval = nextConversationPollInterval(interval, false)
	if interval != conversationPollMaxInterval {
		t.Fatalf("capped interval = %v, want %v", interval, conversationPollMaxInterval)
	}
	interval = nextConversationPollInterval(interval, true)
	if interval != conversationPollFastInterval {
		t.Fatalf("changed interval = %v, want %v", interval, conversationPollFastInterval)
	}
}

func TestConversationPollFingerprintDetectsContentAndStatusChanges(t *testing.T) {
	base := conversationState{
		CodexID:      "CODEX-1",
		ActiveTurnID: "TURN-1",
		Running:      true,
		Turns: []conversationTurn{{
			TurnID: "TURN-1",
			Status: "running",
			Messages: []conversationMessage{{
				ItemID: "ITEM-1",
				Role:   "assistant",
				Text:   "first",
				Status: "in_progress",
			}},
		}},
	}
	unchanged := base
	withContent := base
	withContent.Turns = append([]conversationTurn(nil), base.Turns...)
	withContent.Turns[0].Messages = append([]conversationMessage(nil), base.Turns[0].Messages...)
	withContent.Turns[0].Messages[0].Text = "first second"
	completed := base
	completed.Turns = append([]conversationTurn(nil), base.Turns...)
	completed.Turns[0].Status = "completed"

	fingerprint := conversationPollFingerprint(base)
	if fingerprint != conversationPollFingerprint(unchanged) {
		t.Fatal("identical conversation produced a different fingerprint")
	}
	if fingerprint == conversationPollFingerprint(withContent) {
		t.Fatal("new message content did not change fingerprint")
	}
	if fingerprint == conversationPollFingerprint(completed) {
		t.Fatal("turn status did not change fingerprint")
	}
}

func TestConversationTurnTerminalStopsOnlyForTerminalTarget(t *testing.T) {
	for _, status := range []string{"completed", "failed", "interrupted"} {
		if !conversationTurnTerminal([]conversationTurn{{TurnID: "target", Status: status}}, "target") {
			t.Errorf("status %q was not terminal", status)
		}
	}
	if conversationTurnTerminal([]conversationTurn{{TurnID: "target", Status: "running"}}, "target") {
		t.Fatal("running target was terminal")
	}
	if conversationTurnTerminal([]conversationTurn{{TurnID: "other", Status: "completed"}}, "target") {
		t.Fatal("terminal status from another turn stopped target polling")
	}
}

func TestConversationPollFingerprintAndPendingUpdatesAreSynchronized(t *testing.T) {
	messages := make([]conversationMessage, 2_000)
	for i := range messages {
		messages[i] = conversationMessage{ItemID: "item", Role: "assistant", Text: strings.Repeat("x", 256), Status: "in_progress"}
	}
	sess := &conversationPollRaceSession{
		history: conversationState{
			CodexID:      "CODEX-1",
			ActiveTurnID: "TURN-1",
			Running:      true,
			Turns:        []conversationTurn{{TurnID: "TURN-1", Status: "running", Messages: messages}},
		},
		started: make(chan struct{}),
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := NewCore(nil)
	c.mu.Lock()
	c.session = sess
	c.pollCancel = cancel
	c.conversationRunID = 1
	c.state.SelectedCodexID = "CODEX-1"
	c.state.Conversation = &conversationState{CodexID: "CODEX-1"}
	c.mu.Unlock()

	done := make(chan struct{})
	go func() {
		defer close(done)
		c.pollConversation(ctx, sess, 1, "poll", "CODEX-1", "TURN-1")
	}()
	<-sess.started
	visibilityDeadline := time.Now().Add(time.Second)
	for {
		c.mu.Lock()
		published := c.state.Conversation != nil && len(c.state.Conversation.Turns) > 0
		c.mu.Unlock()
		if published {
			break
		}
		if time.Now().After(visibilityDeadline) {
			t.Fatal("polled conversation was not published")
		}
		runtime.Gosched()
	}
	for i := 0; i < 2_000; i++ {
		c.mu.Lock()
		if c.state.Conversation != nil {
			c.state.Conversation.PendingWatch.HeadEventSeq++
		}
		c.mu.Unlock()
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("poll did not stop after cancellation")
	}
}
