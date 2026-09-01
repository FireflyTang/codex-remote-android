package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SessionManagementUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun projectDialogShowsPathBrowsingCandidatesAndCreateActions() {
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(
                            phase = "ready",
                            directoryListing = DirectoryListing("/work", listOf(DirectoryEntry("demo", "/work/demo"))),
                            sessionCandidates = SessionCandidates(
                                "/work",
                                listOf(SessionCandidate("S-1", "/work", "旧会话", "预览", "rollout", "RESUMABLE")),
                            ),
                        ),
                        projectDialogOpen = true,
                        projectPath = "/work",
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {},
                    onListCandidates = {}, onCreate = {}, onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("project-path").assertIsDisplayed()
        compose.onNodeWithText("上一级").assertIsDisplayed()
        compose.onNodeWithText("📁 demo").assertIsDisplayed()
        compose.onNodeWithText("查看此目录下可导入会话").assertIsDisplayed()
        compose.onNodeWithText("可继续，点击导入").assertIsDisplayed()
        compose.onNodeWithText("新建此项目").assertIsDisplayed()
    }

    @Test
    fun overflowMenuShowsThreeSessionActions() {
        compose.setContent {
            MaterialTheme {
                CodexList(
                    CoreState(phase = "ready", codexes = listOf(CodexSummary("C-1", "演示", "/work", "IDLE"))),
                    {}, {}, { _, _ -> }, {}, {},
                )
            }
        }

        compose.onNodeWithTag("codex-menu-C-1").performClick()
        compose.onNodeWithText("重命名").assertIsDisplayed()
        compose.onNodeWithText("休眠").assertIsDisplayed()
        compose.onNodeWithText("忘记记录").assertIsDisplayed()
    }
}
