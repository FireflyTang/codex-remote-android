package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModernDarkUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun graphiteThemeIsApplied() {
        var background = Color.Unspecified
        var primary = Color.Unspecified
        compose.setContent {
            CodexRemoteTheme {
                background = MaterialTheme.colorScheme.background
                primary = MaterialTheme.colorScheme.primary
            }
        }
        compose.runOnIdle {
            assertEquals(CodexColors.Graphite, background)
            assertEquals(CodexColors.Indigo, primary)
        }
    }

    @Test
    fun homeUsesModernConnectionAndProjectHierarchy() {
        compose.setContent {
            CodexRemoteTheme {
                CodexRemoteScreen(
                    state = AppUiState(core = CoreState(phase = "ready", tailnetIPs = listOf("100.64.0.1"))),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenProject = {}, onOpenConversation = {}, onCloseConversation = {},
                    onDraftChanged = {}, onSend = {}, onStop = {},
                )
            }
        }

        compose.onNodeWithText("Tailnet 开发工作台").assertIsDisplayed()
        compose.onAllNodesWithText("已连接").assertCountEquals(2)
        compose.onNodeWithTag("open-project").assertIsDisplayed()
        compose.onNodeWithTag("export-diagnostics").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新会话").assertIsDisplayed()
    }

    @Test
    fun connectionErrorShowsReadableDetail() {
        compose.setContent {
            CodexRemoteTheme {
                CodexRemoteScreen(
                    state = AppUiState(core = CoreState(phase = "error", error = "Host protocol must be 1.0")),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenProject = {}, onOpenConversation = {}, onCloseConversation = {},
                    onDraftChanged = {}, onSend = {}, onStop = {},
                )
            }
        }

        compose.onNodeWithTag("connection-error-detail").assertIsDisplayed()
        compose.onNodeWithText("连接错误详情").assertIsDisplayed()
        compose.onNodeWithText("Host protocol must be 1.0").assertIsDisplayed()
    }

    @Test
    fun conversationKeepsNavigationTimelineAndComposerActions() {
        compose.setContent {
            CodexRemoteTheme {
                CodexRemoteScreen(
                    state = modernConversationState(),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenProject = {}, onOpenConversation = {}, onCloseConversation = {},
                    onDraftChanged = {}, onSend = {}, onStop = {},
                )
            }
        }

        compose.onNodeWithTag("show-conversation").assertIsDisplayed()
        compose.onNodeWithTag("show-conversation").assertIsSelected()
        compose.onNodeWithTag("show-workspace").assertIsDisplayed()
        val history = compose.onNodeWithTag("conversation-history-list")
        history.performScrollToNode(hasText("检查项目并修复问题"))
        compose.onNodeWithText("检查项目并修复问题").assertIsDisplayed()
        history.performScrollToNode(hasText("已完成修改。", substring = true))
        compose.onNodeWithText("已完成修改。", substring = true).assertIsDisplayed()
        history.performScrollToNode(hasContentDescription("展开思考过程"))
        compose.onNodeWithContentDescription("展开思考过程").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed()
        compose.onNodeWithContentDescription("导出诊断日志").assertIsDisplayed()
    }

    @Test
    fun longChineseProcessContentKeepsActionAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                CodexRemoteTheme {
                    TimelineItem(
                        ConversationItem(
                            itemId = "long-command",
                            turnId = "T",
                            type = "command",
                            status = "completed",
                            command = CommandItem(
                                listOf("执行一个非常非常长的中文命令以验证字体放大后不会覆盖展开按钮"),
                                "/home/demo/一个很长的中文项目目录",
                                "完成",
                                true,
                                0,
                            ),
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("展开命令").assertIsDisplayed()
    }
}

internal fun modernConversationState() = AppUiState(
    core = CoreState(
        phase = "ready",
        codexes = listOf(CodexSummary("C-1", "示例代码", "/home/demo/project", "IDLE")),
        selectedCodexId = "C-1",
        conversation = ConversationState(
            codexId = "C-1",
            historyComplete = true,
            turns = listOf(
                ConversationTurn(
                    turnId = "T-1",
                    status = "completed",
                    failure = "",
                    startedAtUnixMs = 1_787_849_260_000,
                    completedAtUnixMs = 1_787_849_320_000,
                    items = listOf(
                        ConversationItem(
                            itemId = "user", turnId = "T-1", type = "user_message", status = "completed",
                            userMessage = UserMessageItem(listOf("检查项目并修复问题"), "检查项目并修复问题"),
                        ),
                        ConversationItem(
                            itemId = "reasoning", turnId = "T-1", type = "reasoning_summary", status = "completed",
                            reasoningSummary = ReasoningSummaryItem("检查项目结构、运行测试并定位问题"),
                        ),
                        ConversationItem(
                            itemId = "command", turnId = "T-1", type = "command", status = "completed",
                            command = CommandItem(listOf("./gradlew", "test"), "/home/demo/project", "BUILD SUCCESSFUL", true, 0),
                        ),
                        ConversationItem(
                            itemId = "files", turnId = "T-1", type = "file_change", status = "completed",
                            fileChange = FileChangeItem(listOf(FileChange("MainActivity.kt", "modified", "", "")), "@@ -1 +1 @@"),
                        ),
                        ConversationItem(
                            itemId = "agent", turnId = "T-1", type = "agent_message", status = "completed",
                            agentMessage = AgentMessageItem(
                                """已完成修改。

- 修复了会话状态展示
- 保留了现有交互与测试接口
- 统一了项目文件和审批流程的视觉层级
- 调整了长文本和大字体下的布局表现

```kotlin
fun refreshSession() {
    repository.refresh()
    timeline.keepVisibleItems()
    workspace.preserveSelection()
}
```""",
                            ),
                        ),
                    ),
                    messages = emptyList(),
                ),
            ),
        ),
    ),
    openCodexId = "C-1",
    draft = "",
)
