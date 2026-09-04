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
    fun readyRefreshesWithoutReconnectAndIdleConnectsOnlyWithSavedEndpoint() {
        assertEquals(
            ForegroundRecoveryAction.REFRESH,
            foregroundRecoveryAction(AppUiState(hostAddress = "ws://host/connect", core = CoreState(phase = "ready"))),
        )
        assertEquals(
            ForegroundRecoveryAction.CONNECT,
            foregroundRecoveryAction(AppUiState(hostAddress = "ws://host/connect", core = CoreState(phase = "idle"))),
        )
        assertEquals(
            ForegroundRecoveryAction.REFRESH,
            foregroundRecoveryAction(AppUiState(hostAddress = " ", core = CoreState(phase = "ready"))),
        )
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(AppUiState(hostAddress = " ", core = CoreState(phase = "idle"))),
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
    fun homeRefreshFinishesWhenRefreshIsReady() {
        val tracker = ForegroundRecoveryTracker()
        tracker.beginRefresh("refresh", null)

        assertEquals(
            ForegroundRecoveryResolution.Finished(null),
            tracker.onCoreState(CoreState(commandId = "refresh", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "refresh", phase = "ready")),
        )
    }

    @Test
    fun openConversationRefreshSelectsOnceAndWaitsForMatchingHistory() {
        val tracker = ForegroundRecoveryTracker()
        tracker.beginRefresh("refresh", "A")

        assertEquals(
            ForegroundRecoveryResolution.SelectCodex("A"),
            tracker.onCoreState(CoreState(commandId = "refresh", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "refresh", phase = "ready")),
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
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("A")),
            ),
        )
    }

    @Test
    fun selectionFailuresRequireManualReconnectWithoutFallback() {
        val missing = ForegroundRecoveryTracker()
        missing.beginRefresh("refresh", "A")
        missing.onCoreState(CoreState(commandId = "refresh", phase = "ready"))
        missing.trackSelection("select")
        assertEquals(
            ForegroundRecoveryResolution.Failed("连接已失效，请手动重连"),
            missing.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("B")),
            ),
        )

        val failed = ForegroundRecoveryTracker()
        failed.beginRefresh("refresh", "A")
        failed.onCoreState(CoreState(commandId = "refresh", phase = "ready"))
        failed.trackSelection("select")
        assertEquals(
            ForegroundRecoveryResolution.Failed("连接已失效，请手动重连"),
            failed.onCoreState(CoreState(commandId = "select", phase = "error", error = "A 不存在")),
        )
    }

    @Test
    fun unrelatedAndDuplicateRefreshNotificationsDoNotRequestAnotherSelection() {
        val tracker = ForegroundRecoveryTracker()
        tracker.beginRefresh("refresh", "A")

        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(revision = 41, commandId = "old-refresh", phase = "ready")),
        )

        assertEquals(
            ForegroundRecoveryResolution.SelectCodex("A"),
            tracker.onCoreState(CoreState(revision = 43, commandId = "refresh", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(revision = 43, commandId = "refresh", phase = "ready")),
        )
    }

    @Test
    fun timedOutRefreshReconnectsOnceThenSelectsOriginalConversationOnce() {
        val tracker = ForegroundRecoveryTracker()
        tracker.beginRefresh("refresh", "A")

        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(revision = 41, commandId = "old-refresh", phase = "error", error = "old")),
        )
        assertEquals(
            ForegroundRecoveryResolution.Reconnect,
            tracker.onCoreState(
                CoreState(
                    revision = 43,
                    commandId = "refresh",
                    phase = "error",
                    error = "GetHost: context deadline exceeded",
                ),
            ),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(
                CoreState(
                    revision = 43,
                    commandId = "refresh",
                    phase = "error",
                    error = "GetHost: context deadline exceeded",
                ),
            ),
        )
        tracker.trackReconnect("start")
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "refresh", phase = "error", error = "late refresh failure")),
        )
        assertEquals(
            ForegroundRecoveryResolution.SelectCodex("A"),
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "start", phase = "ready")),
        )
        tracker.trackSelection("select")
        assertEquals(
            ForegroundRecoveryResolution.Finished("A"),
            tracker.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("A")),
            ),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(
                CoreState(commandId = "select", phase = "ready", conversation = ConversationState("A")),
            ),
        )
    }

    @Test
    fun reconnectFailureStopsWithoutASecondFallback() {
        val tracker = ForegroundRecoveryTracker()
        tracker.beginRefresh("refresh", "A")
        assertEquals(
            ForegroundRecoveryResolution.Reconnect,
            tracker.onCoreState(
                CoreState(commandId = "refresh", phase = "error", error = "arbitrary refresh failure"),
            ),
        )
        tracker.trackReconnect("start")

        assertEquals(
            ForegroundRecoveryResolution.Failed("连接已失效，请手动重连"),
            tracker.onCoreState(CoreState(commandId = "start", phase = "error", error = "Host connection closed")),
        )
        assertEquals(
            ForegroundRecoveryResolution.None,
            tracker.onCoreState(CoreState(commandId = "start", phase = "error", error = "Host connection closed")),
        )
    }

    @Test
    fun anyForegroundRefreshFailureReconnectsOnceWhenEndpointIsAvailable() {
        listOf("GetHost: context deadline exceeded", "unexpected refresh response").forEach { error ->
            val tracker = ForegroundRecoveryTracker()
            tracker.beginRefresh("refresh", "A")
            assertEquals(
                ForegroundRecoveryResolution.Reconnect,
                tracker.onCoreState(CoreState(commandId = "refresh", phase = "error", error = error)),
            )
            assertEquals(
                ForegroundRecoveryResolution.None,
                tracker.onCoreState(CoreState(commandId = "refresh", phase = "error", error = error)),
            )
        }
    }

    @Test
    fun failedRefreshWithoutEndpointNeverFallbacks() {
        listOf("GetHost: context deadline exceeded", "unexpected refresh response").forEach { error ->
            val tracker = ForegroundRecoveryTracker()
            tracker.beginRefresh("refresh", "A")
            assertEquals(
                ForegroundRecoveryResolution.Failed("连接已失效，请手动重连"),
                tracker.onCoreState(
                    CoreState(commandId = "refresh", phase = "error", error = error),
                    reconnectAvailable = false,
                ),
            )
        }
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
