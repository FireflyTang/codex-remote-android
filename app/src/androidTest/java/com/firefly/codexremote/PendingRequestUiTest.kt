package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PendingRequestUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun staleConversationPendingCardIsHiddenAfterSwitchingCodex() {
        val stale = ConversationState(
            codexId = "A",
            pendingRequests = listOf(
                PendingRequest(
                    type = "approval", requestId = "stale", status = "pending",
                    allowedDecisions = listOf("allow"),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(openCodexId = "B", core = CoreState(conversation = stale)),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {},
                    onSend = {}, onStop = {},
                )
            }
        }

        compose.onAllNodesWithTag("approval-stale").assertCountEquals(0)
        compose.onAllNodesWithTag("pending-requests").assertCountEquals(0)
    }

    @Test
    fun approvalOnlyShowsHostAllowedDecisions() {
        var response = ""
        val request = PendingRequest(
            type = "approval", requestId = "A", status = "pending", title = "运行命令", explanation = "说明",
            command = listOf("git", "status"), allowedDecisions = listOf("allow", "deny"),
        )
        setPanel(listOf(request), onApproval = { _, decision -> response = decision })

        compose.onNodeWithText("运行命令").assertIsDisplayed()
        compose.onNodeWithText("git status").assertIsDisplayed()
        compose.onNodeWithTag("approval-decision-A-allow").performClick()
        assertEquals("allow", response)
        compose.onAllNodesWithTag("approval-decision-A-allow_for_session").assertCountEquals(0)
    }

    @Test
    fun approvalInFlightDisablesButtonsAndFailureCanRetry() {
        val inFlight = PendingRequest(
            type = "approval", requestId = "busy", status = "pending", inFlight = true,
            allowedDecisions = listOf("allow"),
        )
        val failed = PendingRequest(
            type = "approval", requestId = "failed", status = "pending", error = PendingError(code = "busy"),
            allowedDecisions = listOf("deny"),
        )
        var retries = 0
        setPanel(listOf(inFlight, failed), onApproval = { _, _ -> retries++ })

        compose.onNodeWithTag("approval-decision-busy-allow").assertIsNotEnabled()
        compose.onNodeWithTag("pending-error-failed").assertIsDisplayed()
        compose.onNodeWithTag("approval-decision-failed-deny").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun localSubmittingDisablesBeforeCoreMarksInFlight() {
        val request = PendingRequest(
            type = "approval", requestId = "local", status = "pending",
            allowedDecisions = listOf("allow"),
        )
        compose.setContent {
            MaterialTheme {
                PendingRequestsPanel(
                    ConversationState("C", pendingRequests = listOf(request)),
                    drafts = emptyMap(),
                    onRespondApproval = { _, _ -> },
                    onToggleOption = { _, _, _ -> },
                    onFreeFormChanged = { _, _, _ -> },
                    onSubmitUserInput = {},
                    submittingRequestIds = setOf("local"),
                )
            }
        }

        compose.onNodeWithTag("approval-decision-local-allow").assertIsNotEnabled()
        compose.onNodeWithText("处理中…").assertIsDisplayed()
    }

    @Test
    fun singleMultipleAndFreeFormRequireEveryQuestionBeforeSubmit() {
        val request = PendingRequest(
            type = "user_input", requestId = "U",
            questions = listOf(
                UserInputQuestion("single", "单选", "选一个", listOf(UserInputOption("a", "甲"), UserInputOption("b", "乙"))),
                UserInputQuestion(
                    "multi", "多选", "可选多个", listOf(UserInputOption("x", "X"), UserInputOption("y", "Y")),
                    allowsMultiple = true,
                ),
                UserInputQuestion("free", "补充", "请输入", allowsFreeForm = true),
            ),
        )
        val drafts = mutableStateOf<Map<String, Map<String, UserInputAnswerDraft>>>(emptyMap())
        var submits = 0
        compose.setContent {
            MaterialTheme {
                PendingRequestsPanel(
                    ConversationState("C", pendingRequests = listOf(request)), drafts.value,
                    onRespondApproval = { _, _ -> },
                    onToggleOption = { requestId, questionId, optionId ->
                        val requestDraft = drafts.value[requestId].orEmpty()
                        val old = requestDraft[questionId] ?: UserInputAnswerDraft()
                        val question = request.questions.first { it.questionId == questionId }
                        val selected = if (question.allowsMultiple) old.selectedOptionIds + optionId else setOf(optionId)
                        drafts.value = drafts.value + (requestId to (requestDraft + (questionId to old.copy(selectedOptionIds = selected))))
                    },
                    onFreeFormChanged = { requestId, questionId, text ->
                        val requestDraft = drafts.value[requestId].orEmpty()
                        val old = requestDraft[questionId] ?: UserInputAnswerDraft()
                        drafts.value = drafts.value + (requestId to (requestDraft + (questionId to old.copy(freeFormText = text))))
                    },
                    onSubmitUserInput = { submits++ },
                )
            }
        }

        val submit = compose.onNodeWithTag("submit-user-input-U")
        submit.assertIsNotEnabled()
        compose.onNodeWithTag("option-U-single-a").performClick()
        submit.assertIsNotEnabled()
        compose.onNodeWithTag("option-U-multi-x").performScrollTo().performClick()
        compose.onNodeWithTag("option-U-multi-y").performScrollTo().performClick()
        submit.assertIsNotEnabled()
        compose.onNodeWithTag("freeform-U-free").performScrollTo().performTextInput("补充文字")
        submit.performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submits)
    }

    private fun setPanel(
        requests: List<PendingRequest>,
        onApproval: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme {
                PendingRequestsPanel(
                    ConversationState("C", pendingRequests = requests), emptyMap(), onApproval,
                    onToggleOption = { _, _, _ -> }, onFreeFormChanged = { _, _, _ -> }, onSubmitUserInput = {},
                )
            }
        }
    }
}
