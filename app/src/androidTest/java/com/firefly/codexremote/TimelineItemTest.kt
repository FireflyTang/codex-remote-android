package com.firefly.codexremote

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class TimelineItemTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedReasoningIsCollapsedUntilTapped() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "reasoning",
                        turnId = "turn",
                        type = "reasoning_summary",
                        status = "completed",
                        reasoningSummary = ReasoningSummaryItem("先检查状态"),
                    ),
                )
            }
        }

        compose.onAllNodesWithText("先检查状态").assertCountEquals(0)
        compose.onNodeWithContentDescription("展开思考过程").performClick()
        compose.onNodeWithText("先检查状态").assertIsDisplayed()
    }

    @Test
    fun failedCommandIsExpanded() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "command",
                        turnId = "turn",
                        type = "command",
                        status = "failed",
                        command = CommandItem(listOf("make", "test"), "/work", "测试失败", true, 1),
                    ),
                )
            }
        }
        compose.onNodeWithText("测试失败").assertIsDisplayed()
        compose.onNodeWithText("退出码：1").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起命令").assertIsDisplayed()
    }

    @Test
    fun agentMessageStaysVisible() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "agent",
                        turnId = "turn",
                        type = "agent_message",
                        status = "running",
                        agentMessage = AgentMessageItem("正在处理 **你的请求**"),
                    ),
                )
            }
        }
        compose.onNodeWithText("正在处理 你的请求").assertIsDisplayed()
        compose.onNodeWithText("生成中…").assertIsDisplayed()
    }

    @Test
    fun planStaysCompactAndVisible() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "plan",
                        turnId = "turn",
                        type = "plan",
                        status = "running",
                        plan = PlanItem(listOf(PlanStep("读取文件", "completed"), PlanStep("修改代码", "in_progress"))),
                    ),
                )
            }
        }
        compose.onNodeWithText("读取文件").assertIsDisplayed()
        compose.onNodeWithText("修改代码").assertIsDisplayed()
        compose.onAllNodesWithText("展开").assertCountEquals(0)
    }

    @Test
    fun fileSummaryPrecedesSecondLevelDiffExpansion() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "file",
                        turnId = "turn",
                        type = "file_change",
                        status = "completed",
                        fileChange = FileChangeItem(
                            listOf(FileChange("Main.kt", "modified", "", "")),
                            "@@ -1 +1 @@\n-old\n+new",
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("修改 Main.kt").assertIsDisplayed()
        compose.onAllNodesWithText("@@ -1 +1 @@\n-old\n+new").assertCountEquals(0)
        compose.onAllNodesWithText("查看差异").assertCountEquals(0)
        compose.onNodeWithContentDescription("展开文件变更").performClick()
        compose.onNodeWithText("查看差异").performClick()
        compose.onNodeWithText("@@ -1 +1 @@\n-old\n+new").assertIsDisplayed()
        compose.onNodeWithText("收起差异").assertIsDisplayed()
    }

    @Test
    fun runningAndFailedRowsCannotHideRequiredDetail() {
        val item = ConversationItem(
            itemId = "running-command",
            turnId = "turn",
            type = "command",
            status = "running",
            command = CommandItem(listOf("make", "check"), "/work", "仍在执行", false),
        )
        compose.setContent { CodexRemoteTheme { TimelineItem(item) } }

        compose.onNodeWithText("仍在执行").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起命令").performClick()
        compose.onNodeWithText("仍在执行").assertIsDisplayed()
    }

    @Test
    fun expandedReasoningRendersMarkdownText() {
        compose.setContent {
            MaterialTheme {
                TimelineItem(
                    ConversationItem(
                        itemId = "reasoning-md",
                        turnId = "turn",
                        type = "reasoning_summary",
                        status = "completed",
                        reasoningSummary = ReasoningSummaryItem("先看 **配置**"),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("展开思考过程").performClick()
        compose.onNodeWithText("先看 配置").assertIsDisplayed()
    }

    @Test
    fun incompleteHistoryShowsExplicitWarning() {
        val core = CoreState(
            codexes = listOf(CodexSummary("C", "测试会话", "/work", "IDLE")),
            conversation = ConversationState(codexId = "C", historyComplete = false),
        )
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    AppUiState(core = core, openCodexId = "C"),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("历史记录可能不完整").assertIsDisplayed()
    }

    @Test
    fun failedTurnCardStaysBetweenItsTurnAndTheNextTurn() {
        fun agent(id: String, text: String) = ConversationItem(
            itemId = id,
            turnId = id,
            type = "agent_message",
            status = "completed",
            agentMessage = AgentMessageItem(text),
        )
        val conversation = ConversationState(
            codexId = "C",
            historyComplete = true,
            turns = listOf(
                ConversationTurn("T1", "failed", "第一轮失败", 1, 2, listOf(agent("A1", "第一轮正文")), emptyList()),
                ConversationTurn("T2", "completed", "", 3, 4, listOf(agent("A2", "第二轮正文")), emptyList()),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(CoreState(conversation = conversation), Modifier.fillMaxSize())
            }
        }

        val firstTop = compose.onNodeWithText("第一轮正文").fetchSemanticsNode().boundsInRoot.top
        val failureTop = compose.onNodeWithText("第一轮失败").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("第二轮正文").fetchSemanticsNode().boundsInRoot.top
        assert(firstTop < failureTop) { "失败卡应紧跟第一轮正文" }
        assert(failureTop < secondTop) { "失败卡不应被追加到第二轮之后" }
    }
}
