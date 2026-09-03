package com.firefly.codexremote

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDiagnosticTest {
    @Test
    fun commandDiagnosticsIncludeSafeContextButNeverContent() {
        val command = workspaceWriteCommand("C-1", "src/a.txt", "top secret body", "r1", "q1")
        val details = diagnosticCommandDetails(command, "workspace")

        assertTrue(details.contains("commandId="))
        assertTrue(details.contains("action=write_workspace_text_file"))
        assertTrue(details.contains("stage=workspace"))
        assertTrue(details.contains("codexId=C-1"))
        assertTrue(details.contains("relativePath=src/a.txt"))
        assertTrue(details.contains("byteCount=15"))
        assertFalse(details.contains("top secret body"))
        assertFalse(details.contains("utf8Text"))
        assertFalse(details.contains("q1"))
    }

    @Test
    fun pendingDiagnosticsDoNotRecordFreeFormAnswers() {
        val command = coreCommand(
            "respond_user_input",
            JSONObject().put("requestId", "R-1").put("freeFormText", "private answer"),
        )
        val details = diagnosticCommandDetails(command, "pending.response")

        assertTrue(details.contains("requestId=R-1"))
        assertFalse(details.contains("private answer"))
        assertFalse(details.contains("freeFormText"))
    }

    @Test
    fun resultDiagnosticsContainOutcomeErrorAndByteCountWithoutFileContent() {
        val state = CoreState(
            commandId = "D-1",
            phase = "error",
            error = "download failed password=secret",
            workspace = WorkspaceState(
                downloadResult = WorkspaceDownloadResult(
                    entry = WorkspaceEntry(relativePath = "large.zip", sizeBytes = 4096),
                    contentBase64 = "cHJpdmF0ZQ==",
                ),
            ),
        )
        val details = diagnosticResultDetails(
            state,
            DiagnosticTrackedAction("D-1", "download_workspace_entry", "workspace"),
        )

        assertTrue(details.contains("action=download_workspace_entry"))
        assertTrue(details.contains("result=error"))
        assertTrue(details.contains("byteCount=4096"))
        assertTrue(details.contains("[REDACTED]"))
        assertFalse(details.contains("secret"))
        assertFalse(details.contains("cHJpdmF0ZQ"))
    }

    @Test
    fun workspaceActionIsConsumedOnlyWhenWorkspaceLoadingIsTerminal() {
        val command = workspaceWriteCommand("C", "a.txt", "body", "r1", "q1")
        val tracker = DiagnosticActionTracker()
        tracker.register(command, "workspace")
        val commandId = command.getString("id")

        assertNull(
            tracker.consumeTerminal(
                CoreState(commandId = commandId, phase = "ready", workspace = WorkspaceState(loading = "write")),
                AppUiState(),
            ),
        )
        assertEquals(
            "write_workspace_text_file",
            tracker.consumeTerminal(
                CoreState(commandId = commandId, phase = "ready", workspace = WorkspaceState(loading = "none")),
                AppUiState(),
            )?.action,
        )
        assertNull(
            tracker.consumeTerminal(
                CoreState(commandId = commandId, phase = "ready", workspace = WorkspaceState(loading = "none")),
                AppUiState(),
            ),
        )
    }

    @Test
    fun workspaceErrorIsCapturedOnlyAtLoadingNone() {
        val command = workspaceReadCommand("C", "a.txt")
        val tracker = DiagnosticActionTracker()
        tracker.register(command, "workspace")
        val commandId = command.getString("id")
        val error = WorkspaceError("read_failed", "permission denied")

        assertNull(
            tracker.consumeTerminal(
                CoreState(commandId = commandId, phase = "ready", workspace = WorkspaceState(loading = "read", error = error)),
                AppUiState(),
            ),
        )
        val terminal = CoreState(
            commandId = commandId,
            phase = "ready",
            workspace = WorkspaceState(loading = "none", error = error),
        )
        assertEquals("read_workspace_text_file", tracker.consumeTerminal(terminal, AppUiState())?.action)
        val details = diagnosticResultDetails(
            terminal,
            DiagnosticTrackedAction(commandId, "read_workspace_text_file", "workspace"),
        )
        assertTrue(details.contains("result=error"))
        assertFalse(details.contains("result=ready"))
        assertTrue(details.contains("errorCode=read_failed"))
    }

    @Test
    fun pendingAndProjectActionsIgnoreTheirFirstReadyFrame() {
        val pending = respondUserInputCommand(
            PendingRequest(type = "user_input", requestId = "R", questions = emptyList()),
            emptyMap(),
        )
        val pendingTracker = DiagnosticActionTracker()
        pendingTracker.register(pending, "pending.response")
        val pendingId = pending.getString("id")
        assertNull(
            pendingTracker.consumeTerminal(
                CoreState(
                    commandId = pendingId,
                    phase = "ready",
                    conversation = ConversationState(
                        "C",
                        pendingRequests = listOf(PendingRequest(type = "user_input", requestId = "R", inFlight = true)),
                    ),
                ),
                AppUiState(),
            ),
        )
        assertEquals(
            "respond_user_input",
            pendingTracker.consumeTerminal(
                CoreState(commandId = pendingId, phase = "ready", conversation = ConversationState("C")),
                AppUiState(),
            )?.action,
        )

        val project = createCodexCommand("/work", false)
        val projectId = project.getString("id")
        val projectState = AppUiState(
            projectDialogOpen = true,
            pendingProjectCommandId = projectId,
            pendingProjectCommandStage = ProjectStageMutation,
            pendingProjectAction = "create_codex",
        )
        val projectTracker = DiagnosticActionTracker()
        projectTracker.register(project, "project.mutation")
        assertNull(projectTracker.consumeTerminal(CoreState(commandId = projectId, phase = "ready"), projectState))
        assertEquals(
            "create_codex",
            projectTracker.consumeTerminal(
                CoreState(commandId = projectId, phase = "ready", selectedCodexId = "C"),
                projectState,
            )?.action,
        )
    }

    @Test
    fun pendingFailureUsesMatchingRequestErrorInsteadOfGlobalReadyPhase() {
        val tracked = DiagnosticTrackedAction("P-1", "respond_user_input", "pending.response", "R-1")
        val state = CoreState(
            commandId = "P-1",
            phase = "ready",
            conversation = ConversationState(
                "C",
                pendingRequests = listOf(
                    PendingRequest(
                        type = "user_input",
                        requestId = "R-1",
                        error = PendingError("P-1", "invalid_answer", "invalid password=private-value"),
                    ),
                ),
            ),
        )

        val details = diagnosticResultDetails(state, tracked)

        assertTrue(details.contains("result=error"))
        assertFalse(details.contains("result=ready"))
        assertTrue(details.contains("errorCode=invalid_answer"))
        assertTrue(details.contains("invalid password=[REDACTED]"))
        assertFalse(details.contains("private-value"))
        assertFalse(details.contains("freeFormText"))
    }
}
