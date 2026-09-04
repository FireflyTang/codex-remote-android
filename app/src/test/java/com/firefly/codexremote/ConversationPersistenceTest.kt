package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationPersistenceTest {
    @Test
    fun rapidDraftUpdatesPersistOnlyTheLatestValue() {
        val pending = LatestCodexDraftWrite()

        pending.update("A", "o")
        pending.update("A", "ol")
        pending.update("A", "old")
        pending.update("A", "latest")

        assertEquals("A" to "latest", pending.take())
        assertNull(pending.take())
    }

    @Test
    fun clearingForgottenCodexDiscardsItsPendingWrite() {
        val pending = LatestCodexDraftWrite()
        pending.update("A", "do not restore")

        assertEquals(true, pending.discard("A"))
        assertNull(pending.take())
    }

    @Test
    fun forgetPersistenceClearsOnlyAfterMatchingHostSuccess() {
        val tracker = ForgetPersistenceTracker()
        tracker.track("forget-A", "A")

        assertNull(tracker.onCoreState(CoreState(commandId = "other", phase = "ready")))
        assertNull(tracker.onCoreState(CoreState(commandId = "forget-A", phase = "forgetting_codex")))
        assertEquals("A", tracker.onCoreState(CoreState(commandId = "forget-A", phase = "ready")))
        assertNull(tracker.onCoreState(CoreState(commandId = "forget-A", phase = "ready")))

        tracker.track("forget-B", "B")
        assertNull(
            tracker.onCoreState(
                CoreState(
                    commandId = "forget-B", phase = "error", error = "failed",
                    selectedCodexId = "B", conversation = ConversationState("B"),
                ),
            ),
        )
        assertNull(tracker.onCoreState(CoreState(commandId = "forget-B", phase = "ready")))
    }

    @Test
    fun selectedForgetFromHomeWithRefreshFailureStillClearsButUnselectedFailureDoesNot() {
        val selected = ForgetPersistenceTracker().apply { track("forget-A", "A", wasCoreSelected = true) }
        assertEquals(
            "A",
            selected.onCoreState(CoreState(commandId = "forget-A", phase = "error", error = "refresh failed")),
        )

        val unselected = ForgetPersistenceTracker().apply { track("forget-B", "B") }
        assertNull(
            unselected.onCoreState(CoreState(commandId = "forget-B", phase = "error", error = "forget failed")),
        )
    }

    @Test
    fun remoteForgottenClearsOpenOrHomePersistedSelectionOnlyFromAuthoritativeReadyList() {
        val current = AppUiState(openCodexId = "A")
        val containsOpen = listOf(CodexSummary("A", "A", "/work", "IDLE"))

        assertEquals("A", authoritativeForgottenCodex(current, null, CoreState(phase = "ready")))
        assertEquals("A", authoritativeForgottenCodex(AppUiState(), "A", CoreState(phase = "ready")))
        assertNull(
            authoritativeForgottenCodex(
                current,
                null,
                CoreState(phase = "ready", codexes = containsOpen),
            ),
        )
        assertNull(authoritativeForgottenCodex(current, null, CoreState(phase = "refreshing")))
        assertNull(authoritativeForgottenCodex(current, null, CoreState(phase = "error", error = "offline")))
        assertNull(authoritativeForgottenCodex(AppUiState(), null, CoreState(phase = "ready")))
    }

    @Test
    fun sendDraftResolvesOnlyForMatchingAcceptedOrFailedCommand() {
        val tracker = SendDraftTracker()
        tracker.track("send-A", "A", " original ", 4L)

        assertNull(tracker.onCoreState(CoreState(commandId = "other", phase = "error", error = "failed")))
        assertNull(tracker.onCoreState(CoreState(commandId = "send-A", phase = "starting_turn")))
        assertNull(
            tracker.onCoreState(
                CoreState(commandId = "send-A", phase = "ready", conversation = ConversationState("A")),
            ),
        )
        val accepted = tracker.onCoreState(
            CoreState(
                commandId = "send-A",
                phase = "ready",
                conversation = ConversationState("A", activeTurnId = "turn-1", running = true),
            ),
        )
        assertEquals(SendDraftResolution("A", " original ", 4L, accepted = true), accepted)
        assertNull(tracker.onCoreState(CoreState(commandId = "send-A", phase = "error", error = "late")))

        tracker.track("send-B", "B", "restore me", 7L)
        assertEquals(
            SendDraftResolution("B", "restore me", 7L, accepted = false),
            tracker.onCoreState(CoreState(commandId = "send-B", phase = "error", error = "rejected")),
        )
    }

    @Test
    fun newerDraftPreventsSentDraftResolutionFromOverwritingIt() {
        val oldResolution = SendDraftResolution("A", "sent", 2L, accepted = false)

        assertEquals(true, shouldApplySendDraftResolution(oldResolution, 2L))
        assertEquals(false, shouldApplySendDraftResolution(oldResolution, 3L))
    }

    @Test
    fun draftEncodingRoundTripsIndependentCodexValuesAndIgnoresInvalidData() {
        val drafts = mapOf("codex-B" to "second\nline", "codex-A" to "first")

        assertEquals(drafts, decodeCodexDrafts(encodeCodexDrafts(drafts)))
        assertEquals(emptyMap<String, String>(), decodeCodexDrafts("not-json"))
        assertEquals(emptyMap<String, String>(), decodeCodexDrafts(encodeCodexDrafts(mapOf("" to "x", "A" to ""))))
    }

    @Test
    fun switchingCodexReadsOnlyThatCodexDraft() {
        val drafts = mapOf("A" to "draft A", "B" to "draft B")

        assertEquals("draft A", draftForCodex(drafts, "A"))
        assertEquals("draft B", draftForCodex(drafts, "B"))
        assertEquals("", draftForCodex(drafts, "C"))
        assertEquals("", draftForCodex(drafts, null))
    }

    @Test
    fun sentDraftIsRemovedWithoutChangingAnotherCodex() {
        val state = PersistedCodexState("A", mapOf("A" to "sent", "B" to "keep"))

        assertEquals(
            PersistedCodexState("A", mapOf("B" to "keep")),
            state.withDraft("A", ""),
        )
    }

    @Test
    fun forgettingCodexClearsItsDraftAndSelectionOnly() {
        val state = PersistedCodexState("A", mapOf("A" to "remove", "B" to "keep"))

        assertEquals(
            PersistedCodexState("", mapOf("B" to "keep")),
            state.withoutCodex("A"),
        )
        assertEquals(
            PersistedCodexState("A", mapOf("A" to "remove")),
            state.withoutCodex("B"),
        )
    }

    @Test
    fun processRestoreWaitsForReadyKnownCodexAndRunsOnlyOnce() {
        val ready = AppUiState(
            core = CoreState(
                phase = "ready",
                codexes = listOf(CodexSummary("A", "A", "/work", "IDLE")),
            ),
        )

        assertEquals("A", persistedCodexToRestore(ready, "A", true, emptySet()))
        assertNull(persistedCodexToRestore(ready, "A", false, emptySet()))
        assertNull(persistedCodexToRestore(ready, "A", true, setOf("A")))
        assertNull(persistedCodexToRestore(ready.copy(openCodexId = "B"), "A", true, emptySet()))
        assertNull(persistedCodexToRestore(ready.copy(projectDialogOpen = true), "A", true, emptySet()))
        assertNull(persistedCodexToRestore(ready.copy(core = ready.core.copy(phase = "idle")), "A", true, emptySet()))
        assertNull(persistedCodexToRestore(ready, "missing", true, emptySet()))
    }
}
