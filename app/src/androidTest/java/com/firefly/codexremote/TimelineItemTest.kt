package com.firefly.codexremote

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                ConversationHistory(CoreState(conversation = conversation), "C", Modifier.fillMaxSize())
            }
        }

        val firstTop = compose.onNodeWithText("第一轮正文").fetchSemanticsNode().boundsInRoot.top
        val failureTop = compose.onNodeWithText("第一轮失败").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("第二轮正文").fetchSemanticsNode().boundsInRoot.top
        assert(firstTop < failureTop) { "失败卡应紧跟第一轮正文" }
        assert(failureTop < secondTop) { "失败卡不应被追加到第二轮之后" }
    }

    @Test
    fun conversationHistoryNeverRendersAnotherCodexWhileSelectionLoads() {
        val oldConversation = ConversationState(
            codexId = "A",
            historyComplete = true,
            turns = listOf(
                ConversationTurn(
                    "old-turn", "completed", "", 1, 2,
                    emptyList(),
                    listOf(ConversationMessage("old-message", "assistant", "A 的旧消息", "completed")),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(
                    CoreState(phase = "loading_conversation", conversation = oldConversation),
                    "B",
                    Modifier.fillMaxSize(),
                )
            }
        }

        compose.onAllNodesWithText("A 的旧消息").assertCountEquals(0)
        compose.onNodeWithText("正在加载历史记录…").assertIsDisplayed()
    }

    @Test
    fun conversationHistoryScrollsToNewMessagesAndKeepsThemVisibleAfterRecomposition() {
        fun message(id: String, turnId: String, role: String, text: String) = ConversationItem(
            itemId = id,
            turnId = turnId,
            type = if (role == "user") "user_message" else "agent_message",
            status = "completed",
            userMessage = if (role == "user") UserMessageItem(listOf(text), text) else null,
            agentMessage = if (role == "assistant") AgentMessageItem(text) else null,
        )
        val oldItems = (1..24).map { index ->
            message("old-$index", "old-turn", "assistant", "旧消息 $index")
        }
        val oldTurn = ConversationTurn("old-turn", "completed", "", 1, 2, oldItems, emptyList())
        var core by mutableStateOf(
            CoreState(
                revision = 1,
                conversation = ConversationState("C", historyComplete = true, turns = listOf(oldTurn)),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(core, "C", Modifier.fillMaxSize())
            }
        }

        compose.onNodeWithText("旧消息 24").assertIsDisplayed()
        val newTurn = ConversationTurn(
            "new-turn",
            "completed",
            "",
            3,
            4,
            listOf(
                message("new-user", "new-turn", "user", "刚发送的问题"),
                message("new-agent", "new-turn", "assistant", "最新回复"),
            ),
            emptyList(),
        )
        compose.runOnIdle {
            core = CoreState(
                revision = 2,
                conversation = ConversationState("C", historyComplete = true, turns = listOf(oldTurn, newTurn)),
            )
        }

        compose.onNodeWithText("刚发送的问题").assertIsDisplayed()
        compose.onNodeWithText("最新回复").assertIsDisplayed()
        compose.runOnIdle { core = core.copy(revision = 3) }
        compose.onNodeWithText("刚发送的问题").assertIsDisplayed()
        compose.onNodeWithText("最新回复").assertIsDisplayed()
    }

    @Test
    fun importedHistoryProvenanceIsShownOncePerTurn() {
        val items = listOf("one", "two").mapIndexed { index, id ->
            ConversationItem(
                itemId = id,
                turnId = "T",
                type = "agent_message",
                status = "completed",
                agentMessage = AgentMessageItem(id),
                provenance = if (index == 0) {
                    "PROVENANCE_KIND_IMPORTED_HISTORY"
                } else {
                    "PROVENANCE_KIND_HOST_SYNTHESIZED"
                },
            )
        }
        val conversation = ConversationState(
            codexId = "C",
            historyComplete = true,
            turns = listOf(
                ConversationTurn(
                    "T", "completed", "", 1, 2, items, emptyList(),
                    provenance = "PROVENANCE_KIND_IMPORTED_HISTORY",
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(
                    CoreState(
                        codexes = listOf(CodexSummary("C", "导入会话", "/work", "IDLE", importedAtUnixMs = 10)),
                        conversation = conversation,
                    ),
                    "C",
                    Modifier.fillMaxSize(),
                )
            }
        }

        compose.onAllNodesWithText("此轮来自导入的历史记录").assertCountEquals(1)
        compose.onAllNodesWithText("来自导入的历史记录").assertCountEquals(0)
        compose.onAllNodesWithText("由 Host 重建").assertCountEquals(0)
    }

    @Test
    fun importedHistoryNoticeStopsAtImportBoundaryForNewRealtimeTurn() {
        fun message(id: String, turnId: String, text: String, provenance: String) = ConversationItem(
            itemId = id,
            turnId = turnId,
            type = "agent_message",
            status = "completed",
            agentMessage = AgentMessageItem(text),
            provenance = provenance,
        )
        val imported = "PROVENANCE_KIND_IMPORTED_HISTORY"
        val historyTurn = ConversationTurn(
            "history",
            "completed",
            "",
            50,
            60,
            listOf(
                message("history-1", "history", "历史一", imported),
                message("history-2", "history", "历史二", imported),
            ),
            emptyList(),
            provenance = imported,
        )
        val newTurnReloadedFromHistory = ConversationTurn(
            "new",
            "completed",
            "",
            150,
            160,
            listOf(message("new-1", "new", "刚刚的新回复", imported)),
            emptyList(),
            provenance = imported,
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(
                    CoreState(
                        codexes = listOf(
                            CodexSummary("C", "导入会话", "/work", "IDLE", importedAtUnixMs = 100),
                        ),
                        conversation = ConversationState(
                            codexId = "C",
                            historyComplete = true,
                            turns = listOf(historyTurn, newTurnReloadedFromHistory),
                        ),
                    ),
                    "C",
                    Modifier.fillMaxSize(),
                )
            }
        }

        compose.onAllNodesWithText("此轮来自导入的历史记录").assertCountEquals(1)
        compose.onNodeWithText("刚刚的新回复").assertIsDisplayed()
    }

    @Test
    fun collapsedProcessGroupStillShowsCompletenessNotice() {
        val command = ConversationItem(
            itemId = "command-incomplete",
            turnId = "T",
            type = "command",
            status = "completed",
            completeness = ItemCompleteness(truncated = true),
            command = CommandItem(listOf("make", "test"), "/work", "hidden output", true, 0),
        )
        val conversation = ConversationState(
            codexId = "C",
            historyComplete = true,
            turns = listOf(ConversationTurn("T", "completed", "", 1, 2, listOf(command), emptyList())),
        )
        compose.setContent {
            MaterialTheme {
                ConversationHistory(CoreState(conversation = conversation), "C", Modifier.fillMaxSize())
            }
        }

        compose.onNodeWithText("内容已截断").assertIsDisplayed()
        compose.onAllNodesWithText("hidden output").assertCountEquals(0)
    }
}
