package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskContextUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thirdPageShowsActiveTaskDetailsAndDeduplicatesContext() {
        val longCwd = "/home/demo/这是一个用于验证长文本自动换行而不会截断内容的项目目录/src/main/kotlin"
        val repeatedChange = FileChange(longCwd + "/MainActivity.kt", "modified", "", "")
        val activeTurn = ConversationTurn(
            turnId = "TURN-1234567890",
            status = "running",
            failure = "",
            startedAtUnixMs = 100,
            completedAtUnixMs = 0,
            completeness = ItemCompleteness(incomplete = true),
            provenance = "PROVENANCE_KIND_LIVE_WIRE",
            items = listOf(
                ConversationItem(
                    itemId = "files-1",
                    turnId = "TURN-1234567890",
                    type = "file_change",
                    status = "completed",
                    completeness = ItemCompleteness(incomplete = true),
                    fileChange = FileChangeItem(listOf(repeatedChange, repeatedChange), ""),
                ),
            ),
            messages = emptyList(),
        )
        val state = AppUiState(
            openCodexId = "CODEX-LONG-ID",
            core = CoreState(
                phase = "ready",
                codexes = listOf(
                    CodexSummary(
                        id = "CODEX-LONG-ID",
                        title = "会话",
                        cwd = longCwd,
                        status = "WAITING_FOR_APPROVAL",
                        activeTurnId = activeTurn.turnId,
                    ),
                ),
                conversation = ConversationState(
                    codexId = "CODEX-LONG-ID",
                    activeTurnId = activeTurn.turnId,
                    running = true,
                    historyComplete = true,
                    turns = listOf(activeTurn),
                    pendingRequests = listOf(
                        PendingRequest(type = "approval", requestId = "REQUEST-1"),
                        PendingRequest(type = "user_input", requestId = "REQUEST-2"),
                        PendingRequest(type = "user_input", requestId = "resolved", resolved = true),
                    ),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = state,
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {}, onSend = {}, onStop = {},
                )
            }
        }

        compose.onNodeWithTag("show-task-context").performClick()
        compose.onNodeWithTag("task-context-page").assertIsDisplayed()
        compose.onNodeWithText(longCwd).assertIsDisplayed()
        compose.onNodeWithText("等待确认").assertIsDisplayed()
        compose.onNodeWithText("TURN-1234567890").assertIsDisplayed()
        compose.onNodeWithText("待审批 1 · 待输入 1").assertIsDisplayed()
        compose.onNodeWithTag("task-context-page").performTouchInput { swipeUp() }
        compose.onAllNodesWithText(longCwd + "/MainActivity.kt").assertCountEquals(1)
        compose.onAllNodesWithText("警告 · 内容不完整").assertCountEquals(1)
        compose.onAllNodesWithTag("task-context-identifier").assertCountEquals(3)
    }

    @Test
    fun emptyContextHasUsefulMessage() {
        compose.setContent {
            MaterialTheme { TaskContextScreen(AppUiState()) }
        }

        compose.onNodeWithText("暂无任务上下文").assertIsDisplayed()
        compose.onNodeWithText("打开一个 Codex 会话后，可在这里查看当前任务状态。").assertIsDisplayed()
    }

    @Test
    fun snapshotUsesOnlyActiveTurnAndFiltersDuplicateIdentifiers() {
        val oldChange = FileChange("old.txt", "modified", "", "")
        val currentChange = FileChange("current.txt", "added", "", "")
        val state = AppUiState(
            openCodexId = "C",
            core = CoreState(
                codexes = listOf(CodexSummary("C", "会话", "/work", "RUNNING", activeTurnId = "NEW")),
                conversation = ConversationState(
                    codexId = "C",
                    activeTurnId = "NEW",
                    turns = listOf(
                        turnWithChange("OLD", oldChange),
                        turnWithChange("NEW", currentChange),
                    ),
                    pendingRequests = listOf(
                        PendingRequest(type = "approval", requestId = "same"),
                        PendingRequest(type = "user_input", requestId = "same"),
                    ),
                ),
            ),
        )

        val snapshot = taskContextSnapshot(state)

        assertEquals(listOf(currentChange), snapshot.fileChanges)
        assertEquals(
            listOf(TaskContextIdentifier("Codex ID", "C"), TaskContextIdentifier("待处理请求 ID", "same")),
            snapshot.identifiers,
        )
    }

    private fun turnWithChange(turnId: String, change: FileChange) = ConversationTurn(
        turnId = turnId,
        status = "running",
        failure = "",
        startedAtUnixMs = 1,
        completedAtUnixMs = 0,
        items = listOf(
            ConversationItem(
                itemId = "item-$turnId",
                turnId = turnId,
                type = "file_change",
                status = "completed",
                fileChange = FileChangeItem(listOf(change), ""),
            ),
        ),
        messages = emptyList(),
    )
}
