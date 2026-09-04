package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	remotev1 "github.com/FireflyTang/codex-remote-protocol/gen/go/codex/remote/v1"
	"google.golang.org/protobuf/encoding/protojson"
)

type watchChunkState struct {
	Last    uint64
	Unknown bool
}

var errWatchedCodexForgotten = errors.New("watched Codex was forgotten")

func watchEventNeedsHistoryRefresh(event pendingWatchEvent) bool {
	if event.Proto == nil {
		return false
	}
	update := event.Proto.GetTurnUpdated()
	return update != nil && terminalTurnStatus(turnStatusString(update.Status))
}

type watchHistorySession interface {
	ListHistory(context.Context, string) (conversationState, error)
}

func (c *Core) refreshWatchHistory(parent context.Context, sess watchHistorySession, watchRunID uint64, codexID string) {
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	history, err := sess.ListHistory(ctx, codexID)
	if err != nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.pendingWatchCurrentLocked(watchRunID, codexID) || c.state.Conversation == nil {
		return
	}
	merged := mergeConversationSnapshot(*c.state.Conversation, history)
	c.state.Conversation = &merged
	c.publishLocked()
}

func mergeCompleteness(left, right *conversationCompleteness) *conversationCompleteness {
	if left == nil {
		return right
	}
	if right == nil {
		return left
	}
	out := *left
	out.Truncated = left.Truncated || right.Truncated
	out.Incomplete = left.Incomplete || right.Incomplete
	if right.OriginalSizeBytes > out.OriginalSizeBytes {
		out.OriginalSizeBytes = right.OriginalSizeBytes
	}
	if right.Reason != "" && !strings.Contains(out.Reason, right.Reason) {
		if out.Reason != "" {
			out.Reason += "; "
		}
		out.Reason += right.Reason
	}
	return &out
}

func terminalTurnStatus(status string) bool {
	return status == "completed" || status == "failed" || status == "interrupted"
}

func terminalItemStatus(status string) bool {
	return status == "completed" || status == "failed" || status == "cancelled"
}

func preferLonger(left, right string) string {
	if len(right) >= len(left) {
		return right
	}
	return left
}

func mergeConversationItem(left, right conversationItem) conversationItem {
	if left.ItemID == "" {
		return right
	}
	rightStatus, rightCompleteness, rightProvenance := right.Status, right.Completeness, right.Provenance
	out := right
	if out.TurnID == "" {
		out.TurnID = left.TurnID
	}
	if out.Type == "unknown" && left.Type != "unknown" {
		out = left
	}
	if terminalItemStatus(left.Status) && !terminalItemStatus(rightStatus) {
		out.Status = left.Status
	} else if rightStatus != "" && rightStatus != "unspecified" {
		out.Status = rightStatus
	}
	out.Completeness = mergeCompleteness(left.Completeness, rightCompleteness)
	if rightProvenance != "" {
		out.Provenance = rightProvenance
	} else if out.Provenance == "" {
		out.Provenance = left.Provenance
	}
	if left.UserMessage != nil && out.UserMessage != nil {
		out.UserMessage.Text = preferLonger(left.UserMessage.Text, out.UserMessage.Text)
		if len(out.UserMessage.TextParts) < len(left.UserMessage.TextParts) {
			out.UserMessage.TextParts = append([]string{}, left.UserMessage.TextParts...)
		}
		if len(out.UserMessage.Parts) < len(left.UserMessage.Parts) {
			out.UserMessage.Parts = append([]conversationUserMessagePart{}, left.UserMessage.Parts...)
		} else if len(out.UserMessage.Parts) == len(left.UserMessage.Parts) {
			for i := range out.UserMessage.Parts {
				if out.UserMessage.Parts[i].Type == "" {
					out.UserMessage.Parts[i].Type = left.UserMessage.Parts[i].Type
				}
				if out.UserMessage.Parts[i].Text == "" {
					out.UserMessage.Parts[i].Text = left.UserMessage.Parts[i].Text
				}
				if out.UserMessage.Parts[i].Image == nil && left.UserMessage.Parts[i].Image != nil {
					out.UserMessage.Parts[i].Image = left.UserMessage.Parts[i].Image
				}
			}
		}
	}
	if left.AgentMessage != nil && out.AgentMessage != nil {
		out.AgentMessage.Text = preferLonger(left.AgentMessage.Text, out.AgentMessage.Text)
	}
	if left.ReasoningSummary != nil && out.ReasoningSummary != nil {
		out.ReasoningSummary.Text = preferLonger(left.ReasoningSummary.Text, out.ReasoningSummary.Text)
	}
	if left.Command != nil && out.Command != nil {
		out.Command.Output = preferLonger(left.Command.Output, out.Command.Output)
	}
	if left.Tool != nil && out.Tool != nil {
		out.Tool.Summary = preferLonger(left.Tool.Summary, out.Tool.Summary)
		out.Tool.ResultSummary = preferLonger(left.Tool.ResultSummary, out.Tool.ResultSummary)
	}
	if left.FileChange != nil && out.FileChange != nil {
		out.FileChange.UnifiedDiff = preferLonger(left.FileChange.UnifiedDiff, out.FileChange.UnifiedDiff)
	}
	return out
}

func rebuildTurnMessages(turn *conversationTurn) {
	turn.Messages = []conversationMessage{}
	for _, item := range turn.Items {
		switch item.Type {
		case "user_message":
			if item.UserMessage != nil {
				turn.Messages = append(turn.Messages, conversationMessage{ItemID: item.ItemID, Role: "user", Text: item.UserMessage.Text, Status: item.Status})
			}
		case "agent_message":
			if item.AgentMessage != nil {
				turn.Messages = append(turn.Messages, conversationMessage{ItemID: item.ItemID, Role: "assistant", Text: item.AgentMessage.Text, Status: item.Status})
			}
		}
	}
}

func mergeConversationItemsByID(items []conversationItem) []conversationItem {
	out := make([]conversationItem, 0, len(items))
	byID := make(map[string]int, len(items))
	for _, item := range items {
		if index, ok := byID[item.ItemID]; ok && item.ItemID != "" {
			out[index] = mergeConversationItem(out[index], item)
			continue
		}
		out = append(out, item)
		if item.ItemID != "" {
			byID[item.ItemID] = len(out) - 1
		}
	}
	return out
}

func mergeConversationTurn(left, right conversationTurn) conversationTurn {
	if len(right.Items) > 0 {
		right.Items = mergeConversationItemsByID(right.Items)
		rebuildTurnMessages(&right)
	}
	if left.TurnID == "" {
		return right
	}
	out := right
	if terminalTurnStatus(left.Status) && !terminalTurnStatus(out.Status) {
		out.Status = left.Status
	}
	if out.StartedAtUnixMS == 0 {
		out.StartedAtUnixMS = left.StartedAtUnixMS
	}
	if out.CompletedAtUnixMS == 0 {
		out.CompletedAtUnixMS = left.CompletedAtUnixMS
	}
	if out.Failure == "" {
		out.Failure = left.Failure
	}
	if out.Provenance == "" {
		out.Provenance = left.Provenance
	}
	if out.CausedByCommandID == "" {
		out.CausedByCommandID = left.CausedByCommandID
	}
	out.LiveFromStart = left.LiveFromStart || right.LiveFromStart
	out.Completeness = mergeCompleteness(left.Completeness, out.Completeness)
	byID := make(map[string]int, len(out.Items))
	for i := range out.Items {
		if out.Items[i].ItemID != "" {
			byID[out.Items[i].ItemID] = i
		}
	}
	for _, item := range mergeConversationItemsByID(left.Items) {
		if index, ok := byID[item.ItemID]; ok && item.ItemID != "" {
			out.Items[index] = mergeConversationItem(item, out.Items[index])
		} else {
			out.Items = append(out.Items, item)
			if item.ItemID != "" {
				byID[item.ItemID] = len(out.Items) - 1
			}
		}
	}
	if len(out.Items) > 0 {
		rebuildTurnMessages(&out)
	}
	return out
}

func continuousLiveTurnProjection(turn conversationTurn) bool {
	if !turn.LiveFromStart || (turn.Completeness != nil && (turn.Completeness.Incomplete || turn.Completeness.Truncated)) {
		return false
	}
	for _, item := range turn.Items {
		if item.Provenance != remotev1.ProvenanceKind_PROVENANCE_KIND_LIVE_WIRE.String() || (item.Completeness != nil && (item.Completeness.Incomplete || item.Completeness.Truncated)) {
			return false
		}
	}
	return true
}

func completeLiveTurnProjection(turn conversationTurn) bool {
	if !continuousLiveTurnProjection(turn) || !terminalTurnStatus(turn.Status) || len(turn.Items) == 0 {
		return false
	}
	for _, item := range turn.Items {
		if !terminalItemStatus(item.Status) {
			return false
		}
	}
	return true
}

func mergeContinuousLiveTurnProjection(live, history conversationTurn) conversationTurn {
	out := mergeConversationTurn(live, history)
	historyByID := make(map[string]conversationItem, len(history.Items))
	for _, item := range mergeConversationItemsByID(history.Items) {
		if item.ItemID != "" {
			historyByID[item.ItemID] = item
		}
	}
	out.Items = mergeConversationItemsByID(live.Items)
	for i := range out.Items {
		if historyItem, ok := historyByID[out.Items[i].ItemID]; ok {
			// Keep the realtime identity/provenance authoritative while allowing
			// a finalized history snapshot with the same protocol ID to fill data.
			out.Items[i] = mergeConversationItem(historyItem, out.Items[i])
		}
	}
	out.LiveFromStart = true
	rebuildTurnMessages(&out)
	return out
}

func mergeConversationTurnsByID(turns []conversationTurn) []conversationTurn {
	out := make([]conversationTurn, 0, len(turns))
	byID := make(map[string]int, len(turns))
	for _, turn := range turns {
		if len(turn.Items) > 0 {
			turn.Items = mergeConversationItemsByID(turn.Items)
			rebuildTurnMessages(&turn)
		}
		if index, ok := byID[turn.TurnID]; ok && turn.TurnID != "" {
			out[index] = mergeConversationTurn(out[index], turn)
			continue
		}
		out = append(out, turn)
		if turn.TurnID != "" {
			byID[turn.TurnID] = len(out) - 1
		}
	}
	return out
}

// mergeConversationSnapshot keeps ListHistory ordering while retaining a
// newer live suffix that history has not materialized yet.
func mergeConversationSnapshot(live, history conversationState) conversationState {
	out := history
	out.Turns = mergeConversationTurnsByID(out.Turns)
	byID := make(map[string]int, len(out.Turns))
	for i := range out.Turns {
		if out.Turns[i].TurnID != "" {
			byID[out.Turns[i].TurnID] = i
		}
	}
	for _, turn := range mergeConversationTurnsByID(live.Turns) {
		if index, ok := byID[turn.TurnID]; ok && turn.TurnID != "" {
			if completeLiveTurnProjection(turn) || (turn.Status == "running" && continuousLiveTurnProjection(turn)) {
				// ListHistory may expose a different ItemID projection for the same
				// stable TurnID. Preserve a continuous live identity lineage while it
				// is running so imported IDs cannot poison terminal reconciliation.
				out.Turns[index] = mergeContinuousLiveTurnProjection(turn, out.Turns[index])
			} else {
				out.Turns[index] = mergeConversationTurn(turn, out.Turns[index])
			}
		} else {
			out.Turns = append(out.Turns, turn)
			if turn.TurnID != "" {
				byID[turn.TurnID] = len(out.Turns) - 1
			}
		}
	}
	out.PendingRequests = append([]pendingRequest{}, live.PendingRequests...)
	out.PendingWatch = live.PendingWatch
	out.SuppressedActiveTurnIDs = copySuppressedActiveTurns(live.SuppressedActiveTurnIDs)
	for i := range out.Turns {
		if out.SuppressedActiveTurnIDs[out.Turns[i].TurnID] && out.Turns[i].Status == "running" {
			out.Turns[i].Status = "unspecified"
		}
	}
	setConversationActivity(&out)
	return out
}

func copySuppressedActiveTurns(source map[string]bool) map[string]bool {
	if len(source) == 0 {
		return nil
	}
	out := make(map[string]bool, len(source))
	for turnID, suppressed := range source {
		if suppressed {
			out[turnID] = true
		}
	}
	return out
}

func (c *Core) suppressRunningTurnLocked(turnID string) {
	if turnID == "" {
		return
	}
	if c.state.Conversation.SuppressedActiveTurnIDs == nil {
		c.state.Conversation.SuppressedActiveTurnIDs = map[string]bool{}
	}
	c.state.Conversation.SuppressedActiveTurnIDs[turnID] = true
	for i := range c.state.Conversation.Turns {
		if c.state.Conversation.Turns[i].TurnID == turnID && c.state.Conversation.Turns[i].Status == "running" {
			c.state.Conversation.Turns[i].Status = "unspecified"
		}
	}
}

func (c *Core) finishConversationPollFromWatchLocked(turnID string) {
	if c.pollCancel == nil {
		return
	}
	activeTurnID := ""
	if c.state.Conversation != nil {
		activeTurnID = c.state.Conversation.ActiveTurnID
	}
	if c.conversationPollTurnID != turnID && !(c.conversationPollTurnID == "" && activeTurnID == turnID) {
		return
	}
	c.pollCancel()
	c.pollCancel = nil
	c.conversationPollTurnID = ""
	c.conversationStartCommandID = ""
	if c.interruptTurnID == turnID {
		c.interruptTurnID = ""
	}
}

func (c *Core) applyWatchResetLocked(start pendingWatchReset) {
	if start.Codex != nil {
		c.state.Codexes = updateCodexList(c.state.Codexes, start.Codex, false)
	}
	if start.Workspace != nil && c.state.Workspace != nil && c.state.Workspace.CodexID == c.state.SelectedCodexID && (c.state.Workspace.AccessState == nil || start.Workspace.Generation >= c.state.Workspace.AccessState.Generation) {
		c.state.Workspace.AccessState = start.Workspace
	}
	if start.ActiveTurn == nil {
		oldActiveTurnID := c.state.Conversation.ActiveTurnID
		for i := range c.state.Conversation.Turns {
			if c.state.Conversation.Turns[i].Status == "running" {
				c.suppressRunningTurnLocked(c.state.Conversation.Turns[i].TurnID)
			}
		}
		if c.conversationPollTurnID != "" {
			c.suppressRunningTurnLocked(c.conversationPollTurnID)
			c.finishConversationPollFromWatchLocked(c.conversationPollTurnID)
		} else {
			c.finishConversationPollFromWatchLocked(oldActiveTurnID)
		}
		c.state.Conversation.ActiveTurnID = ""
		c.state.Conversation.Running = false
		c.watchChunks = map[string]watchChunkState{}
		return
	}
	for i := range c.state.Conversation.Turns {
		if c.state.Conversation.Turns[i].Status == "running" && c.state.Conversation.Turns[i].TurnID != start.ActiveTurn.TurnID {
			c.suppressRunningTurnLocked(c.state.Conversation.Turns[i].TurnID)
		}
	}
	if c.state.Conversation.SuppressedActiveTurnIDs != nil {
		delete(c.state.Conversation.SuppressedActiveTurnIDs, start.ActiveTurn.TurnID)
	}
	if c.conversationPollTurnID != "" && c.conversationPollTurnID != start.ActiveTurn.TurnID {
		c.finishConversationPollFromWatchLocked(c.conversationPollTurnID)
	}
	activeTurn := c.upsertWatchTurnLocked(*start.ActiveTurn)
	// A RESET snapshot may start in the middle of a turn. Even if every item
	// currently present is LIVE_WIRE, only history can prove whether earlier
	// items are missing.
	activeTurn.LiveFromStart = false
	c.state.Conversation.ActiveTurnID = start.ActiveTurn.TurnID
	c.state.Conversation.Running = start.ActiveTurn.Status == "running"
	c.watchChunks = map[string]watchChunkState{}
	for _, item := range start.ActiveTurn.Items {
		c.watchChunks[start.ActiveTurn.TurnID+"\x00"+item.ItemID] = watchChunkState{Unknown: true}
	}
}

func (c *Core) upsertWatchTurnLocked(turn conversationTurn) *conversationTurn {
	for i := range c.state.Conversation.Turns {
		if c.state.Conversation.Turns[i].TurnID == turn.TurnID {
			c.state.Conversation.Turns[i] = mergeConversationTurn(c.state.Conversation.Turns[i], turn)
			return &c.state.Conversation.Turns[i]
		}
	}
	c.state.Conversation.Turns = append(c.state.Conversation.Turns, turn)
	return &c.state.Conversation.Turns[len(c.state.Conversation.Turns)-1]
}

func ensureWatchTurn(conversation *conversationState, turnID string) *conversationTurn {
	for i := range conversation.Turns {
		if conversation.Turns[i].TurnID == turnID {
			return &conversation.Turns[i]
		}
	}
	conversation.Turns = append(conversation.Turns, conversationTurn{TurnID: turnID, Status: "running", Items: []conversationItem{}, Messages: []conversationMessage{}})
	return &conversation.Turns[len(conversation.Turns)-1]
}

func (c *Core) issueConversationStartCommandLocked(commandID string) {
	c.conversationStartCommandID = commandID
	if c.conversationStartCommandIDs == nil {
		c.conversationStartCommandIDs = map[string]struct{}{}
	}
	c.conversationStartCommandIDs[commandID] = struct{}{}
}

func (c *Core) discardConversationStartCommandLocked(commandID string) {
	delete(c.conversationStartCommandIDs, commandID)
	if c.conversationStartCommandID == commandID {
		c.conversationStartCommandID = ""
	}
}

func (c *Core) clearConversationStartCommandsLocked() {
	c.conversationStartCommandID = ""
	c.conversationStartCommandIDs = nil
}

func (c *Core) consumeConversationStartCommandLocked(commandID string) bool {
	if commandID == "" {
		return false
	}
	if _, ok := c.conversationStartCommandIDs[commandID]; !ok {
		return false
	}
	c.discardConversationStartCommandLocked(commandID)
	return true
}

func startCausalLiveTurn(turn *conversationTurn, requestID string) {
	if requestID == "" {
		return
	}
	turn.CausedByCommandID = requestID
	turn.Status = "running"
	turn.CompletedAtUnixMS = 0
	turn.Failure = ""
	turn.Completeness = nil
	turn.Provenance = remotev1.ProvenanceKind_PROVENANCE_KIND_LIVE_WIRE.String()
	// StartTurn's causal running event precedes this turn's item stream. Polling
	// may already have inserted an imported projection with different ItemIDs;
	// remove only that projection so subsequent live identities start cleanly.
	liveItems := turn.Items[:0]
	for _, item := range turn.Items {
		if item.Provenance != remotev1.ProvenanceKind_PROVENANCE_KIND_IMPORTED_HISTORY.String() {
			liveItems = append(liveItems, item)
		}
	}
	turn.Items = liveItems
	rebuildTurnMessages(turn)
	turn.LiveFromStart = true
}

func (c *Core) applyWatchEventLocked(event pendingWatchEvent) error {
	raw := event.Proto
	if raw == nil {
		return nil
	}
	switch body := raw.Event.(type) {
	case nil:
		// The envelope sequence still participates in replay even when this
		// client has no state projection for the payload.
		return nil
	case *remotev1.Event_CodexUpdated:
		if body.CodexUpdated == nil || body.CodexUpdated.Codex == nil || body.CodexUpdated.Codex.CodexId != raw.CodexId {
			return errors.New("invalid CodexUpdated event")
		}
		c.state.Codexes = updateCodexList(c.state.Codexes, body.CodexUpdated.Codex, false)
	case *remotev1.Event_CodexForgotten:
		c.state.Codexes = updateCodexList(c.state.Codexes, &remotev1.Codex{CodexId: raw.CodexId}, true)
		c.state.SelectedCodexID, c.state.Conversation = "", nil
		if c.state.Workspace != nil && c.state.Workspace.CodexID == raw.CodexId {
			c.state.Workspace = nil
		}
		return errWatchedCodexForgotten
	case *remotev1.Event_TurnUpdated:
		update := body.TurnUpdated
		if update == nil || update.TurnId == "" {
			return errors.New("invalid TurnUpdated event")
		}
		turn := ensureWatchTurn(c.state.Conversation, update.TurnId)
		status := turnStatusString(update.Status)
		if status != "unspecified" && !(terminalTurnStatus(turn.Status) && !terminalTurnStatus(status)) {
			turn.Status = status
		}
		if update.StartedAtUnixMs != 0 {
			turn.StartedAtUnixMS = update.StartedAtUnixMs
		}
		if update.CompletedAtUnixMs != 0 {
			turn.CompletedAtUnixMS = update.CompletedAtUnixMs
		}
		if update.Failure != nil {
			turn.Failure = update.Failure.Message
		}
		if status == "running" {
			if c.consumeConversationStartCommandLocked(raw.CausedByRequestId) {
				startCausalLiveTurn(turn, raw.CausedByRequestId)
			}
			if c.state.Conversation.SuppressedActiveTurnIDs != nil {
				delete(c.state.Conversation.SuppressedActiveTurnIDs, update.TurnId)
			}
			c.state.Conversation.ActiveTurnID, c.state.Conversation.Running = update.TurnId, true
		} else if terminalTurnStatus(status) {
			if c.state.Conversation.SuppressedActiveTurnIDs != nil {
				delete(c.state.Conversation.SuppressedActiveTurnIDs, update.TurnId)
			}
			c.finishConversationPollFromWatchLocked(update.TurnId)
			setConversationActivity(c.state.Conversation)
		}
	case *remotev1.Event_ItemStarted, *remotev1.Event_ItemUpdated, *remotev1.Event_ItemCompleted:
		var item *remotev1.Item
		switch update := body.(type) {
		case *remotev1.Event_ItemStarted:
			item = update.ItemStarted.GetItem()
		case *remotev1.Event_ItemUpdated:
			item = update.ItemUpdated.GetItem()
		case *remotev1.Event_ItemCompleted:
			item = update.ItemCompleted.GetItem()
		}
		if item == nil || item.ItemId == "" || item.TurnId == "" {
			return errors.New("invalid item snapshot event")
		}
		turn := ensureWatchTurn(c.state.Conversation, item.TurnId)
		projected := conversationItemFromProto(item)
		found := false
		for i := range turn.Items {
			if turn.Items[i].ItemID == projected.ItemID {
				turn.Items[i] = mergeConversationItem(turn.Items[i], projected)
				found = true
				break
			}
		}
		if !found {
			turn.Items = append(turn.Items, projected)
		}
		if _, ok := raw.Event.(*remotev1.Event_ItemStarted); ok {
			c.watchChunks[item.TurnId+"\x00"+item.ItemId] = watchChunkState{}
		}
		turn.Completeness = mergeCompleteness(turn.Completeness, projected.Completeness)
		rebuildTurnMessages(turn)
	case *remotev1.Event_ItemDelta:
		delta := body.ItemDelta
		if delta == nil || delta.TurnId == "" || delta.ItemId == "" || delta.ChunkSeq == 0 {
			return errors.New("invalid ItemDelta event")
		}
		key := delta.TurnId + "\x00" + delta.ItemId
		chunk, known := c.watchChunks[key]
		if known && !chunk.Unknown && delta.ChunkSeq <= chunk.Last {
			return nil
		}
		if (!known && delta.ChunkSeq != 1) || (known && !chunk.Unknown && delta.ChunkSeq != chunk.Last+1) {
			return fmt.Errorf("item chunk sequence gap at %d", delta.ChunkSeq)
		}
		turn := ensureWatchTurn(c.state.Conversation, delta.TurnId)
		var item *conversationItem
		for i := range turn.Items {
			if turn.Items[i].ItemID == delta.ItemId {
				item = &turn.Items[i]
				break
			}
		}
		if item == nil {
			turn.Items = append(turn.Items, conversationItem{ItemID: delta.ItemId, TurnID: delta.TurnId, Type: "agent_message", Status: "running", AgentMessage: &conversationAgentMessage{}})
			item = &turn.Items[len(turn.Items)-1]
		}
		switch value := delta.Delta.(type) {
		case *remotev1.ItemDelta_Text:
			switch item.Type {
			case "agent_message":
				item.AgentMessage.Text += value.Text
			case "reasoning_summary":
				item.ReasoningSummary.Text += value.Text
			case "command":
				item.Command.Output += value.Text
			case "file_change":
				item.FileChange.UnifiedDiff += value.Text
			default:
				return fmt.Errorf("text delta does not match item %s", item.ItemID)
			}
		case *remotev1.ItemDelta_CommandOutput:
			if item.Command == nil || value.CommandOutput == nil {
				return fmt.Errorf("command delta does not match item %s", item.ItemID)
			}
			item.Command.Output += value.CommandOutput.Text
		default:
			return errors.New("unsupported ItemDelta payload")
		}
		item.Completeness = mergeCompleteness(item.Completeness, conversationCompletenessFromProto(raw.Completeness))
		turn.Completeness = mergeCompleteness(turn.Completeness, conversationCompletenessFromProto(raw.Completeness))
		c.watchChunks[key] = watchChunkState{Last: delta.ChunkSeq}
		rebuildTurnMessages(turn)
	case *remotev1.Event_PendingRequestUpdated:
		// Projected and merged by applyPendingUpdateLocked so local in-flight
		// state survives replay and RESET.
	case *remotev1.Event_WarningRaised:
		warning := body.WarningRaised.GetWarning()
		if warning == nil {
			return errors.New("invalid WarningRaised event")
		}
		c.state.Codexes = appendCodexWarning(c.state.Codexes, raw.CodexId, warning)
	case *remotev1.Event_WorkspaceAccessStateUpdated:
		access := body.WorkspaceAccessStateUpdated.GetAccessState()
		if access == nil {
			return errors.New("invalid WorkspaceAccessStateUpdated event")
		}
		if c.state.Workspace != nil && c.state.Workspace.CodexID == raw.CodexId && (c.state.Workspace.AccessState == nil || access.Generation >= c.state.Workspace.AccessState.Generation) {
			c.state.Workspace.AccessState = workspaceAccessStateFromProto(access)
		}
	default:
		return errors.New("unsupported Event payload")
	}
	return nil
}

func updateCodexList(raw json.RawMessage, codex *remotev1.Codex, remove bool) json.RawMessage {
	list := new(remotev1.ListCodexesResponse)
	if len(raw) > 0 && (protojson.UnmarshalOptions{DiscardUnknown: true}).Unmarshal(raw, list) != nil {
		return raw
	}
	index := -1
	for i := range list.Codexes {
		if list.Codexes[i].CodexId == codex.CodexId {
			index = i
			break
		}
	}
	if remove {
		if index >= 0 {
			list.Codexes = append(list.Codexes[:index], list.Codexes[index+1:]...)
		}
	} else if index >= 0 {
		list.Codexes[index] = codex
	} else {
		list.Codexes = append(list.Codexes, codex)
	}
	encoded, err := marshalCodexesForApp(list)
	if err != nil {
		return raw
	}
	return encoded
}

func appendCodexWarning(raw json.RawMessage, codexID string, warning *remotev1.Warning) json.RawMessage {
	list := new(remotev1.ListCodexesResponse)
	if len(raw) == 0 || (protojson.UnmarshalOptions{DiscardUnknown: true}).Unmarshal(raw, list) != nil {
		return raw
	}
	for _, codex := range list.Codexes {
		if codex.CodexId == codexID {
			codex.Warnings = append(codex.Warnings, warning)
			break
		}
	}
	encoded, err := marshalCodexesForApp(list)
	if err != nil {
		return raw
	}
	return encoded
}
