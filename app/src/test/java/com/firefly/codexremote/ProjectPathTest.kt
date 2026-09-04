package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectPathTest {
    private val codexes = listOf(
        CodexSummary("first", "First", "/first", "IDLE"),
        CodexSummary("open", "Open", "/open", "IDLE"),
        CodexSummary("selected", "Selected", "/selected", "IDLE"),
    )

    @Test
    fun projectMutationAndManagedSelectionShareOneAtomicSingleFlight() {
        val guard = ProjectOperationSingleFlight()
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        guard.release()
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun concurrentProjectEntrantsAllowExactlyOneRpcOwner() {
        val guard = ProjectOperationSingleFlight()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..16).map {
            pool.submit<Boolean> {
                start.await()
                guard.tryAcquire()
            }
        }
        start.countDown()
        val accepted = futures.count { it.get(2, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, accepted)
    }

    @Test
    fun initialPathUsesNonLegacySavedPathFirst() {
        val state = AppUiState(
            lastProjectPath = " /saved ", openCodexId = "open",
            core = CoreState(codexes = codexes, selectedCodexId = "selected"),
        )

        assertEquals("/saved", initialProjectPath(state))
    }

    @Test
    fun initialPathIgnoresLegacyThenUsesOpenSelectedAndFirstCwdInOrder() {
        val base = CoreState(codexes = codexes, selectedCodexId = "selected")

        assertEquals(
            "/open",
            initialProjectPath(AppUiState(lastProjectPath = LegacyDefaultProjectPath, openCodexId = "open", core = base)),
        )
        assertEquals(
            "/selected",
            initialProjectPath(AppUiState(lastProjectPath = LegacyDefaultProjectPath, core = base)),
        )
        assertEquals(
            "/first",
            initialProjectPath(AppUiState(core = base.copy(selectedCodexId = "missing"))),
        )
    }

    @Test
    fun initialPathFallsBackToRootWhenNoCwdExists() {
        assertEquals("/", initialProjectPath(AppUiState(core = CoreState(codexes = listOf(CodexSummary("C", "", " ", "IDLE"))))))
    }

    @Test
    fun storedLegacyPathMigratesToEmpty() {
        assertEquals("", normalizeStoredProjectPath(" /home/user "))
        assertEquals("/work", normalizeStoredProjectPath(" /work "))
    }

    @Test
    fun successfulBrowseAcceptsHostNormalizedParentWithoutPersistingIt() {
        val decoded = CoreState(
            commandId = "browse-1", phase = "ready",
            directoryListing = DirectoryListing(parentPath = "/canonical/work"),
        )

        assertEquals(
            DirectoryPathResolution("/canonical/work", ""),
            resolveDirectoryResult("/typed/../work", "browse-1", decoded),
        )
        assertNull(projectPathToPersist(false, "/canonical/work", "/typed/../work"))
    }

    @Test
    fun failedBrowseKeepsTypedPathAndDoesNotPersist() {
        val decoded = CoreState(commandId = "browse-1", phase = "error", error = "not found")

        assertEquals(
            DirectoryPathResolution("/missing", ""),
            resolveDirectoryResult("/missing", "browse-1", decoded),
        )
        assertNull(projectPathToPersist(false, "", "/missing"))
    }

    @Test
    fun onlySuccessfulProjectCommandProducesPersistedPath() {
        assertNull(projectPathToPersist(false, "/host/cwd", "/requested"))
        assertEquals("/host/cwd", projectPathToPersist(true, " /host/cwd ", "/requested"))
        assertEquals("/requested", projectPathToPersist(true, "", " /requested "))
        assertNull(projectPathToPersist(true, "", LegacyDefaultProjectPath))
    }

    @Test
    fun managedOpenPersistsSelectedCodexCwdOnlyAfterMatchingConversationSuccess() {
        val current = AppUiState(
            projectDialogOpen = true,
            projectPath = "/browsed",
            pendingProjectCommandId = "select-1",
            pendingProjectCommandStage = ProjectStageSelection,
            pendingProjectCodexId = "managed",
        )
        val success = CoreState(
            commandId = "select-1", phase = "ready", selectedCodexId = "managed",
            codexes = listOf(CodexSummary("managed", "Managed", "/managed/cwd", "IDLE")),
            conversation = ConversationState("managed", historyComplete = true),
        )

        assertEquals(ProjectCommandOutcome.SUCCESS, projectCommandResolution(current, success).outcome)
        assertEquals("/managed/cwd", successfulProjectPath(current, success))
        assertEquals(
            ProjectCommandOutcome.NONE,
            projectCommandResolution(current, success.copy(commandId = "other")).outcome,
        )
        assertEquals(
            ProjectCommandOutcome.FAILURE,
            projectCommandResolution(current, success.copy(phase = "error", error = "failed")).outcome,
        )
    }

    @Test
    fun importMutationRequiresSelectionThenWaitsForMatchingHistory() {
        val mutation = AppUiState(
            projectDialogOpen = true,
            pendingProjectCommandId = "import-B",
            pendingProjectCommandStage = ProjectStageMutation,
            openCodexId = null,
            core = CoreState(conversation = ConversationState("A", turns = listOf(oldTurn()))),
        )
        val imported = CoreState(
            commandId = "import-B",
            phase = "ready",
            selectedCodexId = "B",
            conversation = ConversationState("A", turns = listOf(oldTurn())),
        )
        assertEquals(
            ProjectCommandResolution(ProjectCommandOutcome.SELECT_REQUIRED, "B"),
            projectCommandResolution(mutation, imported),
        )

        val selecting = mutation.copy(
            pendingProjectCommandId = "select-B",
            pendingProjectCommandStage = ProjectStageSelection,
            pendingProjectCodexId = "B",
        )
        assertEquals(
            ProjectCommandOutcome.NONE,
            projectCommandResolution(
                selecting,
                CoreState(
                    commandId = "select-B",
                    phase = "loading_conversation",
                    selectedCodexId = "B",
                    conversation = ConversationState("B"),
                ),
            ).outcome,
        )
        assertEquals(
            ProjectCommandOutcome.NONE,
            projectCommandResolution(selecting, imported.copy(commandId = "select-B")).outcome,
        )
        assertEquals(
            ProjectCommandResolution(ProjectCommandOutcome.SUCCESS, "B"),
            projectCommandResolution(
                selecting,
                CoreState(
                    commandId = "select-B",
                    phase = "ready",
                    selectedCodexId = "B",
                    conversation = ConversationState("B", historyComplete = true),
                ),
            ),
        )
    }

    @Test
    fun firstImportEmptyHistoryAndSelectionFailureAreDeterministic() {
        val selecting = AppUiState(
            projectDialogOpen = true,
            pendingProjectCommandId = "select-new",
            pendingProjectCommandStage = ProjectStageSelection,
            pendingProjectCodexId = "new",
        )
        val emptyHistory = CoreState(
            commandId = "select-new",
            phase = "ready",
            selectedCodexId = "new",
            conversation = ConversationState("new", historyComplete = true, turns = emptyList()),
        )
        assertEquals(ProjectCommandOutcome.SUCCESS, projectCommandResolution(selecting, emptyHistory).outcome)
        assertEquals(
            ProjectCommandOutcome.FAILURE,
            projectCommandResolution(
                selecting,
                emptyHistory.copy(phase = "error", error = "select failed", conversation = null),
            ).outcome,
        )
    }

    @Test
    fun projectCandidatesAreHiddenImmediatelyWhenPathChanges() {
        val candidates = SessionCandidates(
            normalizedCwd = "/project/A",
            sessions = listOf(SessionCandidate(sessionId = "A", availability = "RESUMABLE")),
        )
        assertEquals(
            candidates,
            visibleProjectSessionCandidates(AppUiState(projectPath = "/project/A/", projectSessionCandidates = candidates)),
        )
        assertNull(
            visibleProjectSessionCandidates(AppUiState(projectPath = "/project/B", projectSessionCandidates = candidates)),
        )
    }

    @Test
    fun createRequiresExplicitRetryBeforeCreatingMissingDirectory() {
        val first = createCodexCommand("/work/new", createDirectoryIfMissing = false)
        val confirmed = createCodexCommand("/work/new", createDirectoryIfMissing = true)

        assertFalse(first.getJSONObject("payload").getBoolean("createDirectoryIfMissing"))
        assertTrue(confirmed.getJSONObject("payload").getBoolean("createDirectoryIfMissing"))
        assertTrue(isMissingDirectoryError("directory does not exist: /work/new"))
        assertFalse(isMissingDirectoryError("permission denied: /work/new"))

        val pending = AppUiState(
            projectDialogOpen = true,
            pendingProjectCommandId = "create-1",
            pendingProjectCommandStage = ProjectStageMutation,
            pendingProjectAction = "create_codex",
        )
        val missing = CoreState(
            commandId = "create-1",
            phase = "error",
            error = "directory does not exist: /work/new",
        )
        assertTrue(shouldConfirmMissingDirectory(pending, missing))
        assertFalse(shouldConfirmMissingDirectory(pending, missing.copy(commandId = "stale")))
        assertFalse(shouldConfirmMissingDirectory(pending.copy(pendingProjectAction = "import_session"), missing))
    }

    @Test
    fun cancellingMatchingMissingDirectoryConfirmationRequestsOneRefresh() {
        val state = AppUiState(
            projectDialogOpen = true,
            projectPath = "/work/new",
            missingDirectoryConfirmationPath = "/work/new",
            core = CoreState(
                phase = "error",
                error = "directory does not exist: /work/new",
            ),
        )

        val decision = resolveMissingDirectoryCancel(state)

        assertTrue(decision.refreshCore)
        assertEquals("", decision.nextState.missingDirectoryConfirmationPath)
        assertEquals("", decision.nextState.projectError)
    }

    @Test
    fun cancelDoesNotRefreshForOrdinaryErrorOrAbsentOrStaleConfirmation() {
        val matching = AppUiState(
            projectDialogOpen = true,
            projectPath = "/work/new",
            missingDirectoryConfirmationPath = "/work/new",
            core = CoreState(
                phase = "error",
                error = "directory does not exist: /work/new",
            ),
        )

        assertFalse(
            resolveMissingDirectoryCancel(
                matching.copy(core = matching.core.copy(error = "permission denied: /work/new")),
            ).refreshCore,
        )
        assertFalse(resolveMissingDirectoryCancel(matching.copy(missingDirectoryConfirmationPath = "")).refreshCore)
        assertFalse(resolveMissingDirectoryCancel(matching.copy(projectPath = "/work/other")).refreshCore)
        assertFalse(
            resolveMissingDirectoryCancel(matching.copy(core = matching.core.copy(phase = "ready", error = ""))).refreshCore,
        )
    }

    private fun oldTurn() = ConversationTurn(
        turnId = "old",
        status = "completed",
        failure = "",
        startedAtUnixMs = 0,
        completedAtUnixMs = 0,
        items = emptyList(),
        messages = listOf(ConversationMessage("old-message", "assistant", "A history", "completed")),
    )
}
