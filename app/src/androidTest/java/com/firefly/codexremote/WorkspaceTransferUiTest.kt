package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkspaceTransferUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val file = WorkspaceEntry("src/a.bin", "a.bin", "regular_file", sizeBytes = 4)

    private fun workspace(loading: String = "none") = WorkspaceState(
        supported = true,
        loading = loading,
        limits = WorkspaceLimits(maxInlineUploadBytes = 1024, maxInlineDownloadBytes = 2048),
        accessState = WorkspaceAccessState(mutationStatus = "ALLOWED", quiescenceToken = "q"),
        currentDirectory = WorkspaceDirectory("src", listOf(file)),
    )

    @Test
    fun transferButtonsAreEnabledOnlyWhenContractAllowsThem() {
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace())),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }
        compose.onNodeWithTag("workspace-upload-file").assertIsEnabled()
        compose.onNodeWithTag("workspace-upload-zip").assertIsEnabled()
        compose.onNodeWithTag("workspace-download-src/a.bin").assertIsEnabled()
        compose.onNodeWithTag("workspace-transfer-limits").assertIsDisplayed()
    }

    @Test
    fun busyWorkspaceDisablesUploadAndDownload() {
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace("upload"))),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }
        compose.onNodeWithTag("workspace-upload-file").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-download-src/a.bin").assertIsNotEnabled()
    }

    @Test
    fun localErrorIsVisibleAndDownloadCanBeSelectedAgain() {
        var selections = 0
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(
                        core = CoreState(workspace = workspace()),
                        workspaceTransferError = "本地保存失败：请重试；可重新选择保存位置",
                        workspaceDownloadReady = WorkspaceDownloadReady(
                            "cmd", file.relativePath,
                            WorkspaceDownloadResult(file, "regular_file", "a.bin", "eA=="),
                        ),
                    ),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                    onChooseDownload = { selections++ },
                )
            }
        }
        compose.onNodeWithTag("workspace-transfer-error").assertIsDisplayed()
        compose.onNodeWithTag("workspace-download-src/a.bin").performClick().performClick()
        assertEquals(2, selections)
    }

    @Test
    fun localPickerBusyImmediatelyDisablesTransfersAndShowsFixedChineseStatus() {
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(
                        core = CoreState(workspace = workspace()),
                        workspaceLocalTransferStatus = "choosing_download",
                    ),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }
        compose.onNodeWithTag("workspace-upload-file").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-download-src/a.bin").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-local-transfer-status").assertIsDisplayed()
    }
}
