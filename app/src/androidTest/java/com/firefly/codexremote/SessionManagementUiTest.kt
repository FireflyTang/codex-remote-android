package com.firefly.codexremote

import android.accessibilityservice.AccessibilityService
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertTextContains
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionManagementUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun projectDialogShowsPathBrowsingCandidatesAndCreateActions() {
        var importedSession = ""
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(
                            phase = "ready",
                            directoryListing = DirectoryListing("/work", listOf(DirectoryEntry("demo", "/work/demo"))),
                        ),
                        projectDialogOpen = true,
                        projectPath = "/work",
                        projectSessionCandidates = SessionCandidates(
                            "/work",
                            listOf(SessionCandidate("S-1", "/work", "旧会话", "预览", "rollout", "RESUMABLE")),
                        ),
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {},
                    onListCandidates = {}, onCreate = {},
                    onImport = { sessionId, _ -> importedSession = sessionId }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("project-path").assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithText("上一级").assertIsDisplayed()
        compose.onNodeWithText("查看此目录下可导入会话").assertIsDisplayed()
        compose.onNodeWithText("可继续，点击导入").assertIsDisplayed()
        compose.onNodeWithText("新建此项目").assertIsDisplayed()
        compose.onAllNodesWithText("demo").assertCountEquals(0)
        compose.onNodeWithText("返回目录浏览").performClick()
        compose.onNodeWithText("demo").assertIsDisplayed()
        compose.onAllNodesWithText("旧会话").assertCountEquals(0)
        compose.onNodeWithTag("list-session-candidates").performClick()
        compose.onNodeWithText("旧会话").performClick()
        compose.runOnIdle { assertEquals("S-1", importedSession) }
    }

    @Test
    fun longDirectoryListKeepsPrimaryActionsVisibleAndClickable() {
        var listCandidatesCount = 0
        var createCount = 0
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(
                            phase = "ready",
                            directoryListing = DirectoryListing(
                                "/work",
                                (1..60).map { DirectoryEntry("directory-$it", "/work/directory-$it") },
                            ),
                        ),
                        projectDialogOpen = true,
                        projectPath = "/work",
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {},
                    onListCandidates = { listCandidatesCount++ }, onCreate = { createCount++ },
                    onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("list-session-candidates").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回目录浏览").performClick()
        compose.onNodeWithTag("create-codex").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, listCandidatesCount)
            assertEquals(1, createCount)
        }
    }

    @Test
    fun directoryRowsRejectClicksWhileBusyAndWorkWhenIdle() {
        var selectedPath = ""
        val busy = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(
                            phase = if (busy.value) "loading_directories" else "ready",
                            directoryListing = DirectoryListing(
                                "/work",
                                listOf(DirectoryEntry("demo", "/work/demo")),
                            ),
                        ),
                        projectDialogOpen = true,
                        projectPath = "/work",
                        pendingDirectoryCommandId = if (busy.value) "directory" else "",
                    ),
                    onDismiss = {}, onPathChanged = {},
                    onListDirectories = { selectedPath = it }, onListCandidates = {},
                    onCreate = {}, onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("project-directory-demo").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals("", selectedPath) }
        compose.runOnUiThread { busy.value = false }
        compose.onNodeWithTag("project-directory-demo").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("/work/demo", selectedPath) }
    }

    @Test
    fun missingDirectoryConfirmationShowsPathAndHasExplicitActions() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(
                        core = CoreState(phase = "ready"),
                        projectDialogOpen = true,
                        projectPath = "/work/new",
                        missingDirectoryConfirmationPath = "/work/new",
                    ),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {}, onSend = {}, onStop = {},
                    onConfirmCreateMissingDirectory = { confirmed++ },
                    onCancelCreateMissingDirectory = { cancelled++ },
                )
            }
        }

        compose.onNodeWithText("目录不存在：\n/work/new\n\n是否创建该目录并新建项目？").assertIsDisplayed()
        compose.onNodeWithTag("confirm-create-directory-action").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(0, cancelled)
        }
    }

    @Test
    fun emptyAndUnavailableCandidateResultsAreExplicit() {
        val candidates = mutableStateOf(
            SessionCandidates(
                "/work",
                listOf(
                    SessionCandidate(
                        "S-live", "/work", "其他客户端会话", "", "rollout", "POSSIBLY_LIVE_ELSEWHERE",
                        warnings = listOf(ProtocolWarning(code = "HISTORY_IMPORT_INCOMPLETE")),
                    ),
                    SessionCandidate("S-dead", "/work", "不可继续会话", "", "rollout", "NOT_RESUMABLE"),
                    SessionCandidate("S-new", "/work", "未来状态会话", "", "rollout", "FUTURE_VALUE"),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(phase = "ready"),
                        projectDialogOpen = true,
                        projectPath = "/work",
                        projectSessionCandidates = candidates.value,
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {}, onListCandidates = {},
                    onCreate = {}, onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithText("可能正在其他客户端使用，暂不可导入").assertIsDisplayed()
        compose.onNodeWithText("历史记录导入不完整").assertIsDisplayed()
        compose.onNodeWithText("无法继续此会话").assertIsDisplayed()
        compose.onNodeWithText("未知状态，暂不可导入").assertIsDisplayed()
        compose.runOnUiThread { candidates.value = SessionCandidates("/work", emptyList()) }
        compose.onNodeWithTag("empty-session-candidates").assertIsDisplayed()
    }

    @Test
    fun sleepingCodexFiltersContradictoryExpiryNotice() {
        compose.setContent {
            MaterialTheme {
                CodexList(
                    CoreState(
                        phase = "ready",
                        codexes = listOf(
                            CodexSummary(
                                "sleep", "休眠会话", "/work", "UNAVAILABLE", managementState = "UNMANAGED",
                                warnings = listOf(
                                    ProtocolWarning(code = "MANAGEMENT_EXPIRING_SOON"),
                                    ProtocolWarning(code = "RUNTIME_RESTARTED"),
                                ),
                            ),
                        ),
                    ),
                    {}, {}, { _, _ -> }, {}, {},
                )
            }
        }

        compose.onNodeWithText("休眠").assertIsDisplayed()
        compose.onAllNodesWithText("此会话即将休眠").assertCountEquals(0)
        compose.onNodeWithText("Codex 运行时已重启").assertIsDisplayed()
    }

    @Test
    fun longCandidateListCanScrollAndOpenAnOffscreenCandidate() {
        var importedSession = ""
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(phase = "ready"),
                        projectDialogOpen = true,
                        projectPath = "/work",
                        projectSessionCandidates = SessionCandidates(
                            "/work",
                            (1..60).map {
                                SessionCandidate("S-$it", "/work", "会话-$it", "", "rollout", "RESUMABLE")
                            },
                        ),
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {}, onListCandidates = {},
                    onCreate = {}, onImport = { sessionId, _ -> importedSession = sessionId }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("project-results").performScrollToNode(hasText("会话-60"))
        compose.onNodeWithText("会话-60").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("S-60", importedSession) }
    }

    @Test
    fun resolvedRootFallbackIsReachableInProjectPathField() {
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(phase = "ready"),
                        projectDialogOpen = true,
                        projectPath = initialProjectPath(AppUiState()),
                    ),
                    onDismiss = {}, onPathChanged = {}, onListDirectories = {},
                    onListCandidates = {}, onCreate = {}, onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithTag("project-path").assertTextContains("/")
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

    @Test
    fun busyProjectDialogRejectsSystemDismiss() {
        var dismissCount = 0
        compose.setContent {
            MaterialTheme {
                ProjectDialog(
                    state = AppUiState(
                        core = CoreState(phase = "importing_session"),
                        projectDialogOpen = true,
                        projectPath = "/work",
                        pendingProjectCommandId = "import",
                        pendingProjectCommandStage = ProjectStageMutation,
                    ),
                    onDismiss = { dismissCount++ },
                    onPathChanged = {}, onListDirectories = {}, onListCandidates = {},
                    onCreate = {}, onImport = { _, _ -> }, onOpenManaged = {},
                )
            }
        }

        compose.onNodeWithText("关闭").assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.runOnIdle { assertEquals(0, dismissCount) }
    }

    @Test
    fun foregroundRecoveryDoesNotPresentStaleReadyState() {
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(
                        hostAddress = "ws://host/connect",
                        core = CoreState(phase = "ready", codexes = listOf(CodexSummary("C", "旧状态", "/work", "IDLE"))),
                        foregroundRecoveryInProgress = true,
                    ),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {}, onSend = {}, onStop = {},
                )
            }
        }

        compose.onAllNodesWithText("正在恢复连接").assertCountEquals(2)
        compose.onNodeWithTag("open-project").assertIsNotEnabled()
    }
}
