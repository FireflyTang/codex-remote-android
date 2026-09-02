package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectPathTest {
    private val codexes = listOf(
        CodexSummary("first", "First", "/first", "IDLE"),
        CodexSummary("open", "Open", "/open", "IDLE"),
        CodexSummary("selected", "Selected", "/selected", "IDLE"),
    )

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
    fun managedOpenPersistsSelectedCodexCwdOnlyAfterMatchingSuccess() {
        val current = AppUiState(
            projectDialogOpen = true,
            projectPath = "/browsed",
            pendingProjectCommandId = "select-1",
        )
        val success = CoreState(
            commandId = "select-1", phase = "ready", selectedCodexId = "managed",
            codexes = listOf(CodexSummary("managed", "Managed", "/managed/cwd", "IDLE")),
        )

        assertEquals(ProjectCommandOutcome.SUCCESS, projectCommandOutcome(current, success))
        assertEquals("/managed/cwd", successfulProjectPath(current, success))
        assertEquals(
            ProjectCommandOutcome.NONE,
            projectCommandOutcome(current, success.copy(commandId = "other")),
        )
        assertEquals(
            ProjectCommandOutcome.FAILURE,
            projectCommandOutcome(current, success.copy(phase = "error", error = "failed")),
        )
    }
}
