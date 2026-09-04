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
        assertEquals(
            SendDraftResolution("send-A", "A", " original ", 4L, accepted = true, acceptedTurnId = "turn-1"),
            accepted,
        )
        assertNull(tracker.onCoreState(CoreState(commandId = "send-A", phase = "error", error = "late")))

        tracker.track("send-B", "B", "restore me", 7L)
        assertEquals(
            SendDraftResolution("send-B", "B", "restore me", 7L, accepted = false),
            tracker.onCoreState(
                CoreState(commandId = "send-B", phase = "error", error = "a turn is already running"),
            ),
        )
    }

    @Test
    fun newerDraftPreventsSentDraftResolutionFromOverwritingIt() {
        val oldResolution = SendDraftResolution("send-A", "A", "sent", 2L, accepted = false)

        assertEquals(true, shouldApplySendDraftResolution(oldResolution, 2L))
        assertEquals(false, shouldApplySendDraftResolution(oldResolution, 3L))
        assertEquals(
            "sent",
            restoredDraftAfterSendFailure("", "A", oldResolution, latestDraftVersion = 2L),
        )
        assertEquals(
            "new draft",
            restoredDraftAfterSendFailure("new draft", "A", oldResolution, latestDraftVersion = 3L),
        )
    }

    @Test
    fun optimisticUserMessageIsVisibleSynchronouslyInProjectedTimeline() {
        val state = AppUiState(
            openCodexId = "A",
            core = CoreState(phase = "ready", conversation = ConversationState("A")),
        )
        val optimistic = OptimisticUserMessage("send-A", "A", "hello", createdAtUnixMs = 42L)

        assertEquals(true, canDispatchMessage(state.copy(draft = "hello"), "hello"))
        val next = state.withOptimisticUserMessage(optimistic)

        assertEquals(listOf(optimistic), next.optimisticUserMessages)
        assertEquals("starting_turn", next.core.phase)
        assertEquals(false, canDispatchMessage(next.copy(draft = "double click"), "double click"))
        val item = next.core.conversation!!.timelineItems.single()
        assertEquals("optimistic-user-send-A", item.itemId)
        assertEquals("hello", item.userMessage?.text)
        assertEquals(1, next.withOptimisticUserMessage(optimistic).core.coreUserMessageCount())
    }

    @Test
    fun acceptedOverlayStaysUntilRealUserItemThenIsReplacedWithoutDuplicate() {
        val overlay = OptimisticUserMessage("send-A", "A", "hello", createdAtUnixMs = 42L)
        val acceptedCore = CoreState(
            commandId = "send-A",
            phase = "ready",
            conversation = ConversationState("A", activeTurnId = "turn-1", running = true),
        )
        val resolution = SendDraftResolution(
            "send-A", "A", "hello", 1L, accepted = true, acceptedTurnId = "turn-1",
        )

        val accepted = reconcileOptimisticUserMessages(listOf(overlay), acceptedCore, resolution)
        assertEquals("turn-1", accepted.single().acceptedTurnId)
        assertEquals(1, projectOptimisticUserMessages(acceptedCore, accepted).coreUserMessageCount())

        val realItem = ConversationItem(
            itemId = "real-user",
            turnId = "turn-1",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("hello"), "hello"),
        )
        val realCore = acceptedCore.copy(
            conversation = acceptedCore.conversation?.copy(
                turns = listOf(conversationTurn("turn-1", realItem)),
            ),
        )
        val replaced = reconcileOptimisticUserMessages(accepted, realCore, resolution = null)

        assertEquals(emptyList<OptimisticUserMessage>(), replaced)
        assertEquals(1, projectOptimisticUserMessages(realCore, replaced).coreUserMessageCount())
        assertEquals("real-user", projectOptimisticUserMessages(realCore, replaced).conversationItemIds().single())
    }

    @Test
    fun fastTurnWatchSequenceReplacesOverlayBeforeLateStartResponse() {
        val tracker = SendDraftTracker()
        tracker.track("send-fast", "A", "fast", 9L, knownTurnIds = setOf("old-turn"))
        var overlays = listOf(OptimisticUserMessage("send-fast", "A", "fast"))
        val oldTurn = conversationTurn("old-turn")

        val starting = CoreState(
            commandId = "send-fast",
            phase = "starting_turn",
            conversation = ConversationState("A", turns = listOf(oldTurn)),
        )
        assertNull(tracker.onCoreState(starting))
        assertEquals(1, projectOptimisticUserMessages(starting, overlays).coreUserMessageCount())

        val running = starting.copy(
            conversation = ConversationState(
                codexId = "A",
                activeTurnId = "fast-turn",
                running = true,
                turns = listOf(oldTurn, conversationTurn("fast-turn")),
            ),
        )
        val accepted = tracker.onCoreState(running)
        assertEquals("fast-turn", accepted?.acceptedTurnId)
        overlays = reconcileOptimisticUserMessages(overlays, running, accepted)
        assertEquals(1, projectOptimisticUserMessages(running, overlays).coreUserMessageCount())
        assertEquals("", draftPersistenceAfterSendResolution(accepted!!, latestDraftVersion = 9L))

        val realUser = ConversationItem(
            itemId = "real-fast-user",
            turnId = "fast-turn",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("fast"), "fast"),
        )
        val terminalBeforeResponse = starting.copy(
            conversation = ConversationState(
                codexId = "A",
                running = false,
                turns = listOf(oldTurn, conversationTurn("fast-turn", realUser).copy(status = "completed")),
            ),
        )
        overlays = reconcileOptimisticUserMessages(overlays, terminalBeforeResponse, resolution = null)
        val terminalDisplay = projectOptimisticUserMessages(terminalBeforeResponse, overlays)
        assertEquals(emptyList<OptimisticUserMessage>(), overlays)
        assertEquals(listOf("real-fast-user"), terminalDisplay.conversationItemIds())

        val lateResponse = terminalBeforeResponse.copy(phase = "ready")
        assertNull(tracker.onCoreState(lateResponse))
        assertEquals(listOf("real-fast-user"), projectOptimisticUserMessages(lateResponse, overlays).conversationItemIds())
    }

    @Test
    fun terminalUserEvidenceAcceptsAtomicallyWhenRunningCallbackWasCoalesced() {
        val tracker = SendDraftTracker()
        tracker.track("send-fast", "A", "same", 3L, knownTurnIds = setOf("old-turn"))
        val overlay = OptimisticUserMessage("send-fast", "A", "same")
        val realUser = ConversationItem(
            itemId = "real-user",
            turnId = "new-turn",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("same"), "same"),
        )
        val terminal = CoreState(
            commandId = "send-fast",
            phase = "starting_turn",
            conversation = ConversationState(
                codexId = "A",
                turns = listOf(conversationTurn("old-turn"), conversationTurn("new-turn", realUser)),
            ),
        )

        val accepted = tracker.onCoreState(terminal)
        val reconciled = reconcileOptimisticUserMessages(listOf(overlay), terminal, accepted)

        assertEquals(true, accepted?.accepted)
        assertEquals("new-turn", accepted?.acceptedTurnId)
        assertEquals("", draftPersistenceAfterSendResolution(accepted!!, latestDraftVersion = 3L))
        assertEquals(emptyList<OptimisticUserMessage>(), reconciled)
        assertEquals(listOf("real-user"), projectOptimisticUserMessages(terminal, reconciled).conversationItemIds())
    }

    @Test
    fun successfulResponsePrefersActiveTurnOverUnrelatedNewHistoryUserTurn() {
        val tracker = SendDraftTracker()
        tracker.track("send-active", "A", "sent", 3L, knownTurnIds = setOf("old-turn"))
        val historicalUser = ConversationItem(
            itemId = "history-user",
            turnId = "history-turn",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("older"), "older"),
        )

        val accepted = tracker.onCoreState(
            CoreState(
                commandId = "send-active",
                phase = "ready",
                conversation = ConversationState(
                    codexId = "A",
                    activeTurnId = "active-turn",
                    running = true,
                    turns = listOf(
                        conversationTurn("old-turn"),
                        conversationTurn("history-turn", historicalUser),
                        conversationTurn("active-turn"),
                    ),
                ),
            ),
        )

        assertEquals("active-turn", accepted?.acceptedTurnId)
    }

    @Test
    fun explicitSecondSendRejectionCannotClaimFirstSendsRealTurn() {
        val tracker = SendDraftTracker()
        tracker.track("send-1", "A", "first", 1L, knownTurnIds = setOf("old-turn"))
        tracker.track("send-2", "A", "second", 2L, knownTurnIds = setOf("old-turn"))
        val firstUser = ConversationItem(
            itemId = "real-first-user",
            turnId = "turn-1",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("first"), "first"),
        )
        val firstTurnVisible = ConversationState(
            codexId = "A",
            activeTurnId = "turn-1",
            running = true,
            turns = listOf(
                conversationTurn("old-turn"),
                conversationTurn("turn-1", firstUser).copy(causedByCommandId = "send-1"),
            ),
        )

        val secondRejected = tracker.onCoreState(
            CoreState(
                commandId = "send-2",
                phase = "error",
                error = "a turn is already running",
                conversation = firstTurnVisible,
            ),
        )

        assertEquals(false, secondRejected?.accepted)
        assertEquals("second", draftPersistenceAfterSendResolution(secondRejected!!, latestDraftVersion = 2L))
        val afterSecondRejected = reconcileOptimisticUserMessages(
            listOf(
                OptimisticUserMessage("send-1", "A", "first"),
                OptimisticUserMessage("send-2", "A", "second"),
            ),
            CoreState(conversation = firstTurnVisible),
            secondRejected,
        )
        assertEquals(listOf("send-1"), afterSecondRejected.map { it.commandId })

        val firstAccepted = tracker.onCoreState(
            CoreState(commandId = "watch-update", phase = "error", error = "offline", conversation = firstTurnVisible),
        )
        assertEquals("turn-1", firstAccepted?.acceptedTurnId)
        val remaining = reconcileOptimisticUserMessages(
            afterSecondRejected,
            CoreState(conversation = firstTurnVisible),
            firstAccepted,
        )
        assertEquals(emptyList<OptimisticUserMessage>(), remaining)
    }

    @Test
    fun hostBusinessErrorsAreDefinitiveFailuresAndRestoreTheirOwnDraft() {
        listOf(
            "StartTurn Host error ERROR_CODE_CONFLICT: turn active",
            "StartTurn Host error ERROR_CODE_NOT_FOUND: codex missing",
            "StartTurn Host error ERROR_CODE_INVALID_REQUEST: invalid options",
        ).forEachIndexed { index, error ->
            val commandId = "send-host-$index"
            val draft = "draft-$index"
            val tracker = SendDraftTracker()
            tracker.track(commandId, "A", draft, 4L)

            val resolution = tracker.onCoreState(
                CoreState(commandId = commandId, phase = "error", error = error),
            )

            assertEquals(false, resolution?.accepted)
            assertEquals(draft, draftPersistenceAfterSendResolution(resolution!!, latestDraftVersion = 4L))
        }
    }

    @Test
    fun exactCausalTurnOverridesHostErrorForTheSameCommand() {
        val tracker = SendDraftTracker()
        tracker.track("send-causal", "A", "sent", 5L, knownTurnIds = setOf("old-turn"))
        val causalTurn = conversationTurn("accepted-turn").copy(
            status = "completed",
            causedByCommandId = "send-causal",
        )

        val resolution = tracker.onCoreState(
            CoreState(
                commandId = "send-causal",
                phase = "error",
                error = "StartTurn Host error ERROR_CODE_INTERNAL: persist boundary failed",
                conversation = ConversationState(
                    codexId = "A",
                    turns = listOf(conversationTurn("old-turn"), causalTurn),
                ),
            ),
        )

        assertEquals(true, resolution?.accepted)
        assertEquals("accepted-turn", resolution?.acceptedTurnId)
        assertEquals("", draftPersistenceAfterSendResolution(resolution!!, latestDraftVersion = 5L))
    }

    @Test
    fun transportUnknownResolvesAcceptedAfterReconnectFindsCausalTurn() {
        val tracker = SendDraftTracker()
        tracker.track("send-2", "A", "second", 2L, knownTurnIds = setOf("old-turn"))
        val unrelatedUser = ConversationItem(
            itemId = "real-first-user",
            turnId = "turn-1",
            type = "user_message",
            status = "completed",
            userMessage = UserMessageItem(listOf("first"), "first"),
        )
        val unknown = CoreState(
            commandId = "send-2",
            phase = "error",
            error = "Host connection closed",
            conversation = ConversationState(
                codexId = "A",
                turns = listOf(conversationTurn("old-turn"), conversationTurn("turn-1", unrelatedUser)),
            ),
        )
        val overlay = OptimisticUserMessage("send-2", "A", "second")

        val resolution = tracker.onCoreState(unknown)

        assertNull(resolution)
        assertEquals(listOf(overlay), reconcileOptimisticUserMessages(listOf(overlay), unknown, resolution))

        val causalTurn = conversationTurn("turn-2", unrelatedUser.copy(itemId = "real-second", turnId = "turn-2"))
            .copy(causedByCommandId = "send-2")
        val reconnected = CoreState(
            commandId = "reconnect-1",
            phase = "ready",
            conversation = ConversationState(
                codexId = "A",
                turns = listOf(conversationTurn("old-turn"), causalTurn),
                pendingWatch = PendingWatch(state = "watching", headEventSeq = 12L),
            ),
        )

        val accepted = tracker.onCoreState(reconnected)

        assertEquals(true, accepted?.accepted)
        assertEquals("turn-2", accepted?.acceptedTurnId)
        assertEquals("", draftPersistenceAfterSendResolution(accepted!!, latestDraftVersion = 2L))
    }

    @Test
    fun watchingBeforeReplayDoesNotPrematurelyFailUnknownSend() {
        val tracker = SendDraftTracker()
        tracker.track("send-unknown", "A", "restore after reconnect", 6L, knownTurnIds = setOf("old-turn"))
        assertNull(
            tracker.onCoreState(
                CoreState(commandId = "send-unknown", phase = "error", error = "Host connection closed"),
            ),
        )

        val prematureWatching = tracker.onCoreState(
            CoreState(
                commandId = "reconnect-2",
                phase = "ready",
                conversation = ConversationState(
                    codexId = "A",
                    turns = listOf(conversationTurn("old-turn")),
                    pendingWatch = PendingWatch(state = "watching", headEventSeq = 20L),
                ),
            ),
        )

        assertNull(prematureWatching)

        val causalReplay = tracker.onCoreState(
            CoreState(
                commandId = "reconnect-2",
                phase = "ready",
                conversation = ConversationState(
                    codexId = "A",
                    turns = listOf(
                        conversationTurn("old-turn"),
                        conversationTurn("late-turn").copy(causedByCommandId = "send-unknown"),
                    ),
                    pendingWatch = PendingWatch(state = "watching", headEventSeq = 21L),
                ),
            ),
        )

        assertEquals(true, causalReplay?.accepted)
        assertEquals("late-turn", causalReplay?.acceptedTurnId)
    }

    @Test
    fun failedSendRemovesOnlyMatchingOverlayAndRestoresEligibleDraft() {
        val failed = SendDraftResolution("send-A", "A", "restore me", 5L, accepted = false)
        val messages = listOf(
            OptimisticUserMessage("send-A", "A", "same text"),
            OptimisticUserMessage("send-B", "A", "same text"),
        )

        val remaining = reconcileOptimisticUserMessages(
            messages,
            CoreState(commandId = "send-A", phase = "error", error = "a turn is already running"),
            failed,
        )

        assertEquals(listOf("send-B"), remaining.map { it.commandId })
        assertEquals("restore me", restoredDraftAfterSendFailure("", "A", failed, 5L))
        assertEquals("new input", restoredDraftAfterSendFailure("new input", "A", failed, 6L))
    }

    @Test
    fun optimisticMessagesAreProjectedOnlyIntoTheirOwnCodex() {
        val messages = listOf(
            OptimisticUserMessage("send-A", "A", "for A"),
            OptimisticUserMessage("send-B", "B", "for B"),
        )

        val shownA = projectOptimisticUserMessages(
            CoreState(conversation = ConversationState("A")),
            messages,
        )
        val shownB = projectOptimisticUserMessages(
            CoreState(conversation = ConversationState("B")),
            messages,
        )

        assertEquals(listOf("for A"), shownA.coreUserMessageTexts())
        assertEquals(listOf("for B"), shownB.coreUserMessageTexts())
    }

    @Test
    fun acceptedIncompleteTerminalOverlayDoesNotBlockNextSend() {
        val terminal = CoreState(
            phase = "ready",
            conversation = ConversationState(
                codexId = "A",
                running = false,
                turns = listOf(
                    conversationTurn("turn-1").copy(
                        status = "completed",
                        completeness = ItemCompleteness(incomplete = true, reason = "bounded"),
                    ),
                ),
            ),
        )
        val acceptedOverlay = OptimisticUserMessage(
            commandId = "send-1",
            codexId = "A",
            text = "preserved placeholder",
            acceptedTurnId = "turn-1",
        )
        val displayed = projectOptimisticUserMessages(terminal, listOf(acceptedOverlay))
        val state = AppUiState(
            core = displayed,
            openCodexId = "A",
            draft = "next",
            optimisticUserMessages = listOf(acceptedOverlay),
        )

        assertEquals("ready", displayed.phase)
        assertEquals(true, canDispatchMessage(state, "next"))
        assertEquals(listOf("optimistic-user-send-1"), displayed.conversationItemIds())
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

    private fun conversationTurn(turnId: String, vararg items: ConversationItem) = ConversationTurn(
        turnId = turnId,
        status = "running",
        failure = "",
        startedAtUnixMs = 1L,
        completedAtUnixMs = 0L,
        items = items.toList(),
        messages = emptyList(),
    )

    private fun CoreState.coreUserMessageCount(): Int = conversationItemIds().size

    private fun CoreState.coreUserMessageTexts(): List<String> = conversation?.timelineItems.orEmpty()
        .filter { it.type == "user_message" }
        .mapNotNull { it.userMessage?.text }

    private fun CoreState.conversationItemIds(): List<String> = conversation?.timelineItems.orEmpty()
        .filter { it.type == "user_message" }
        .map { it.itemId }
}
