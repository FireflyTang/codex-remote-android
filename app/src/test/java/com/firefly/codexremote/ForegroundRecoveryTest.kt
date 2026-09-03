package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRecoveryTest {
    @Test
    fun firstStartShortBackgroundConfigurationChangeAndDuplicateStartDoNothing() {
        val gate = ForegroundResumeGate(thresholdMs = 10_000)

        assertFalse(gate.onStarted(1_000))
        assertFalse(gate.onStarted(2_000))
        gate.onStopped(3_000, changingConfigurations = false)
        assertFalse(gate.onStarted(12_999))
        gate.onStopped(20_000, changingConfigurations = true)
        assertFalse(gate.onStarted(40_000))
    }

    @Test
    fun thirtySecondAndTwoMinuteBackgroundsEachReconnectExactlyOnce() {
        listOf(30_000L, 120_000L).forEach { backgroundDuration ->
            val gate = ForegroundResumeGate(thresholdMs = 10_000)
            gate.onStarted(0)
            gate.onStopped(1_000, changingConfigurations = false)

            assertTrue(gate.onStarted(1_000 + backgroundDuration))
            assertFalse(gate.onStarted(2_000 + backgroundDuration))
        }
    }

    @Test
    fun readyAndIdleDirectlyReconnectOnlyWithSavedEndpoint() {
        assertEquals(
            ForegroundRecoveryAction.CONNECT,
            foregroundRecoveryAction(AppUiState(hostAddress = "ws://host/connect", core = CoreState(phase = "ready"))),
        )
        assertEquals(
            ForegroundRecoveryAction.CONNECT,
            foregroundRecoveryAction(AppUiState(hostAddress = "ws://host/connect", core = CoreState(phase = "idle"))),
        )
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(AppUiState(hostAddress = " ", core = CoreState(phase = "ready"))),
        )
        assertEquals(
            listOf("stop", "configure", "start"),
            connectCommands(org.json.JSONObject()).map { it.getString("type") },
        )
    }

    @Test
    fun runningAndPendingInteractionsAreNeverInterrupted() {
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                AppUiState(
                    hostAddress = "ws://host/connect",
                    core = CoreState(phase = "ready", conversation = ConversationState("C", running = true)),
                ),
            ),
        )
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                AppUiState(
                    hostAddress = "ws://host/connect",
                    core = CoreState(phase = "ready"),
                    foregroundRecoveryInProgress = true,
                ),
            ),
        )
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                AppUiState(
                    hostAddress = "ws://host/connect",
                    core = CoreState(
                        phase = "ready",
                        conversation = ConversationState(
                            "C",
                            pendingRequests = listOf(PendingRequest(type = "approval", requestId = "A")),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                AppUiState(
                    hostAddress = "ws://host/connect",
                    core = CoreState(
                        phase = "ready",
                        conversation = ConversationState(
                            "C",
                            pendingRequests = listOf(PendingRequest(type = "user_input", requestId = "U")),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun allPrimaryUiOperationsBlockForegroundReconnect() {
        val ready = AppUiState(hostAddress = "ws://host/connect", core = CoreState(phase = "ready"))
        val busyStates = listOf(
            ready.copy(stoppingTurn = true),
            ready.copy(pendingDirectoryCommandId = "directory"),
            ready.copy(pendingSessionCandidatesCommandId = "sessions"),
            ready.copy(pendingProjectCommandId = "project"),
            ready.copy(pendingWorkspaceGetCommandId = "get"),
            ready.copy(pendingWorkspaceReadCommandId = "read"),
            ready.copy(pendingWorkspaceUploadCommandId = "upload"),
            ready.copy(pendingWorkspaceDownloadCommandId = "download"),
            ready.copy(workspaceLocalTransferStatus = "copying"),
            ready.copy(submittingRequestIds = setOf("request")),
        )

        busyStates.forEach { state ->
            assertEquals(ForegroundRecoveryAction.NONE, foregroundRecoveryAction(state))
        }
    }

    @Test
    fun homeReconnectFinishesWhenStartIsReady() {
        val tracker = ForegroundRecoveryTracker()
        tracker.begin("start", null)

        assertEquals(
            ForegroundRecoveryResolution.Finished(null),
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
    }

    @Test
    fun openConversationReconnectSelectsAndWaitsForMatchingHistory() {
        val tracker = ForegroundRecoveryTracker()
        tracker.begin("start", "A")

        assertEquals(
            ForegroundRecoveryResolution.SelectCodex("A"),
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
        tracker.trackSelection("select")
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "select", phase = "loading_conversation")),
        )
        assertEquals(
            ForegroundRecoveryResolution.Finished("A"),
            tracker.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("A")),
            ),
        )
    }

    @Test
    fun missingConversationAndSelectionFailureReturnToHome() {
        val missing = ForegroundRecoveryTracker()
        missing.begin("start", "A")
        missing.onCoreState(CoreState(commandId = "start", phase = "ready"))
        missing.trackSelection("select")
        assertTrue(
            missing.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("B")),
            ) is ForegroundRecoveryResolution.Failed,
        )

        val failed = ForegroundRecoveryTracker()
        failed.begin("start", "A")
        failed.onCoreState(CoreState(commandId = "start", phase = "ready"))
        failed.trackSelection("select")
        assertEquals(
            ForegroundRecoveryResolution.Failed("A 不存在"),
            failed.onCoreState(CoreState(commandId = "select", phase = "error", error = "A 不存在")),
        )
    }

    @Test
    fun duplicateStartNotificationDoesNotRequestAnotherSelection() {
        val tracker = ForegroundRecoveryTracker()
        tracker.begin("start", "A")

        assertEquals(
            ForegroundRecoveryResolution.SelectCodex("A"),
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
    }

    @Test
    fun recoveryLeavesWorkspaceOnConversationPage() {
        val recovering = foregroundRecoveryStartingState(
            AppUiState(
                openCodexId = "A",
                conversationPage = ConversationPage.WORKSPACE,
                workspaceEditorOpen = true,
            ),
        )

        assertTrue(recovering.foregroundRecoveryInProgress)
        assertEquals("A", recovering.openCodexId)
        assertEquals(ConversationPage.CONVERSATION, recovering.conversationPage)
        assertFalse(recovering.workspaceEditorOpen)
    }

    @Test
    fun failedReconnectDoesNotLoopWithoutAnotherEligibleResume() {
        val gate = ForegroundResumeGate(thresholdMs = 10_000)
        assertFalse(gate.onStarted(0))
        gate.onStopped(1_000, changingConfigurations = false)
        assertTrue(gate.onStarted(31_000))
        assertFalse(gate.onStarted(32_000))
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                AppUiState(
                    hostAddress = "ws://host/connect",
                    core = CoreState(phase = "error", error = "connect failed"),
                ),
            ),
        )
    }

    @Test
    fun revisionGateRejectsLateCompletionAfterHigherRevision() {
        assertTrue(shouldAcceptCoreRevision(currentRevision = 41, incomingRevision = 42))
        assertFalse(shouldAcceptCoreRevision(currentRevision = 43, incomingRevision = 42))
    }
}
