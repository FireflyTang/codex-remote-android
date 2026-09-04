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

func mergeConversationTurn(left, right conversationTurn) conversationTurn {
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
	out.Completeness = mergeCompleteness(left.Completeness, out.Completeness)
	byID := make(map[string]int, len(out.Items))
	for i := range out.Items {
		byID[out.Items[i].ItemID] = i
	}
	for _, item := range left.Items {
		if index, ok := byID[item.ItemID]; ok && item.ItemID != "" {
			out.Items[index] = mergeConversationItem(item, out.Items[index])
		} else {
			out.Items = append(out.Items, item)
		}
	}
	rebuildTurnMessages(&out)
	return out
}

// mergeConversationSnapshot keeps ListHistory ordering while retaining a
// newer live suffix that history has not materialized yet.
func mergeConversationSnapshot(live, history conversationState) conversationState {
	out := history
	byID := make(map[string]int, len(out.Turns))
	for i := range out.Turns {
		byID[out.Turns[i].TurnID] = i
	}
	for _, turn := range live.Turns {
		if index, ok := byID[turn.TurnID]; ok && turn.TurnID != "" {
			out.Turns[index] = mergeConversationTurn(turn, out.Turns[index])
		} else {
			out.Turns = append(out.Turns, turn)
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
	c.upsertWatchTurnLocked(*start.ActiveTurn)
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
