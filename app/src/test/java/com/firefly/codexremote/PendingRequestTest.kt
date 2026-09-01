package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRequestTest {
    @Test
    fun activeConversationRequiresOpenCodexMatch() {
        val conversation = ConversationState("A")
        assertEquals(
            conversation,
            activeConversation(AppUiState(openCodexId = "A", core = CoreState(conversation = conversation))),
        )
        assertEquals(
            null,
            activeConversation(AppUiState(openCodexId = "B", core = CoreState(conversation = conversation))),
        )
    }

    @Test
    fun decodesPendingApprovalUserInputWatchAndErrors() {
        val state = decodeCoreState(
            """
            {"conversation":{"codexId":"C","pendingWatch":{"state":"watching","headEventSeq":42},"pendingRequests":[
              {"type":"approval","requestId":"A","turnId":"T","itemId":"I","inFlight":false,
               "approval":{"kind":"command","status":"pending","title":"运行命令","explanation":"需要执行",
                 "command":["git","status"],"allowedDecisions":["allow","deny"]}},
              {"type":"user_input","requestId":"U","turnId":"T","itemId":"Q","inFlight":false,
               "userInput":{"resolved":false,
                 "questions":[{"questionId":"q1","header":"方式","prompt":"请选择","allowsMultiple":true,
                   "allowsFreeForm":true,"options":[{"optionId":"o1","label":"第一项","description":"说明"}]}],
                 "completeness":{"truncated":true,"incomplete":true,"originalSizeBytes":99,"reason":"limit"}},
               "error":{"commandId":"cmd","code":"invalid_answer","message":"bad"}}
            ]}}
            """.trimIndent(),
        )

        val conversation = state.conversation!!
        assertEquals("watching", conversation.pendingWatch.state)
        assertEquals(42L, conversation.pendingWatch.headEventSeq)
        val approval = conversation.pendingRequests[0]
        assertEquals("command", approval.kind)
        assertEquals("pending", approval.status)
        assertEquals("运行命令", approval.title)
        assertEquals(listOf("git", "status"), approval.command)
        assertEquals(listOf("allow", "deny"), approval.allowedDecisions)
        val input = conversation.pendingRequests[1]
        assertFalse(input.resolved)
        assertTrue(input.questions.single().allowsMultiple)
        assertTrue(input.questions.single().allowsFreeForm)
        assertEquals("o1", input.questions.single().options.single().optionId)
        assertTrue(input.completeness!!.truncated)
        assertEquals("invalid_answer", input.error!!.code)
    }

    @Test
    fun approvalCommandUsesExactPayload() {
        val command = respondApprovalCommand("approval-1", "allow_for_session")
        assertEquals("respond_approval", command.getString("type"))
        val payload = command.getJSONObject("payload")
        assertEquals("approval-1", payload.getString("approvalId"))
        assertEquals("allow_for_session", payload.getString("decision"))
        val request = PendingRequest(
            type = "approval", status = "pending", allowedDecisions = listOf("allow", "deny"),
        )
        assertTrue(canRespondApproval(request, "allow"))
        assertFalse(canRespondApproval(request, "allow_for_session"))
        assertFalse(canRespondApproval(request.copy(inFlight = true), "allow"))
        assertFalse(canRespondApproval(request.copy(status = ""), "allow"))
        assertTrue(canSubmitApproval(request.copy(requestId = "A"), "allow", emptySet()))
        assertFalse(canSubmitApproval(request.copy(requestId = "A"), "allow", setOf("A")))
    }

    @Test
    fun userInputValidationHonorsSingleMultipleAndFreeForm() {
        val single = UserInputQuestion("single", options = listOf(UserInputOption("a"), UserInputOption("b")))
        val multiple = UserInputQuestion(
            "multiple",
            options = listOf(UserInputOption("x"), UserInputOption("y")),
            allowsMultiple = true,
        )
        val free = UserInputQuestion("free", allowsFreeForm = true)

        assertTrue(isUserInputAnswerValid(single, UserInputAnswerDraft(setOf("a"))))
        assertFalse(isUserInputAnswerValid(single, UserInputAnswerDraft(setOf("a", "b"))))
        assertTrue(isUserInputAnswerValid(multiple, UserInputAnswerDraft(setOf("x", "y"))))
        assertFalse(isUserInputAnswerValid(free, UserInputAnswerDraft(freeFormText = "  ")))
        assertTrue(isUserInputAnswerValid(free, UserInputAnswerDraft(freeFormText = "说明")))
    }

    @Test
    fun userInputPayloadContainsExactlyOneAnswerPerQuestion() {
        val request = PendingRequest(
            type = "user_input",
            requestId = "request-1",
            questions = listOf(
                UserInputQuestion("q1", options = listOf(UserInputOption("a"))),
                UserInputQuestion("q2", allowsFreeForm = true),
            ),
        )
        val drafts = mapOf(
            "q1" to UserInputAnswerDraft(setOf("a", "unknown"), "discarded"),
            "q2" to UserInputAnswerDraft(freeFormText = "文字"),
        )
        assertTrue(areUserInputAnswersComplete(request, drafts))
        assertTrue(canSubmitUserInput(request, drafts, emptySet()))
        assertFalse(canSubmitUserInput(request, drafts, setOf("request-1")))

        val command = respondUserInputCommand(request, drafts)
        assertEquals("respond_user_input", command.getString("type"))
        val payload = command.getJSONObject("payload")
        assertEquals("request-1", payload.getString("requestId"))
        val answers = payload.getJSONArray("answers")
        assertEquals(2, answers.length())
        assertEquals("q1", answers.getJSONObject(0).getString("questionId"))
        assertEquals(listOf("a"), listOf(answers.getJSONObject(0).getJSONArray("selectedOptionIds").getString(0)))
        assertEquals("", answers.getJSONObject(0).getString("freeFormText"))
        assertEquals("文字", answers.getJSONObject(1).getString("freeFormText"))
        assertEquals(0, answers.getJSONObject(1).getJSONArray("selectedOptionIds").length())
    }

    @Test
    fun pendingErrorsStayChineseAndRecognizeResolvedRequests() {
        assertTrue(pendingErrorDescription(PendingError(code = "approval_already_resolved")).contains("已处理"))
        assertTrue(pendingErrorDescription(PendingError(code = "user_input_already_resolved")).contains("已处理"))
        assertEquals("提交失败，请重试", pendingErrorDescription(PendingError(code = "operation_failed", message = "host failed")))
        assertEquals("提交失败，请重试", pendingErrorDescription(PendingError(code = "unknown", message = "english")))
    }
}
