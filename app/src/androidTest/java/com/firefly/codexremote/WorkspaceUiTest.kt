package com.firefly.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unsupportedHostShowsHonestMessageAndNoEntries() {
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    state = AppUiState(core = CoreState(workspace = WorkspaceState(supported = false))),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }

        compose.onNodeWithText("Host 不支持项目文件").assertIsDisplayed()
    }

    @Test
    fun directoryDrillsDownAndNonTextFileIsDisabled() {
        var openedDirectory = ""
        val workspace = WorkspaceState(
            supported = true,
            workspaceRoot = "/work",
            currentDirectory = WorkspaceDirectory(
                entries = listOf(
                    WorkspaceEntry("src", "src", "directory"),
                    WorkspaceEntry("huge.bin", "huge.bin", "regular_file", textViewable = false),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace)),
                    onListWorkspace = { openedDirectory = it }, onOpenFile = {},
                    onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }

        compose.onNodeWithTag("workspace-entry-src").performClick()
        assertEquals("src", openedDirectory)
        compose.onNodeWithText("过大或非文本，无法打开").assertIsDisplayed()
    }

    @Test
    fun editorHasLineNumbersAndDisablesSaveWhenWorkspaceIsBusy() {
        val entry = WorkspaceEntry("a.txt", "a.txt", "regular_file", revision = "r1", textViewable = true, textEditable = true)
        val workspace = WorkspaceState(
            supported = true,
            accessState = WorkspaceAccessState(mutationStatus = "BUSY", quiescenceToken = "q"),
            currentDirectory = WorkspaceDirectory(),
            openFile = WorkspaceOpenFile(entry, "one\ntwo"),
        )
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace), workspaceEditorOpen = true, workspaceEditorText = "one\ntwo"),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }

        compose.onNodeWithText("1\n2").assertIsDisplayed()
        compose.onNodeWithTag("workspace-editor").assertIsDisplayed()
        compose.onNodeWithTag("workspace-save").assertIsNotEnabled()
        compose.onNodeWithText("Codex 正在使用项目文件，暂时不能保存").assertIsDisplayed()
    }

    @Test
    fun editorEnablesSaveWithAllowedTokenAndRevision() {
        var saves = 0
        val entry = WorkspaceEntry("a.txt", "a.txt", "regular_file", revision = "r1", textViewable = true, textEditable = true)
        val workspace = WorkspaceState(
            supported = true,
            accessState = WorkspaceAccessState(mutationStatus = "allowed", quiescenceToken = "q"),
            currentDirectory = WorkspaceDirectory(),
            openFile = WorkspaceOpenFile(entry, "one"),
        )
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace), workspaceEditorOpen = true, workspaceEditorText = "one"),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = { saves++ }, onCloseEditor = {},
                )
            }
        }

        compose.onNodeWithTag("workspace-save").assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun longEditorScrollKeepsLineNumbersAndTextLockedTogether() {
        val content = (1..140).joinToString("\n") { "line $it" }
        val entry = WorkspaceEntry(
            "long.txt", "long.txt", "regular_file", sizeBytes = content.length.toLong(),
            revision = "r1", textViewable = true, textEditable = true,
        )
        val workspace = WorkspaceState(
            supported = true,
            accessState = WorkspaceAccessState(mutationStatus = "allowed", quiescenceToken = "q"),
            currentDirectory = WorkspaceDirectory(),
            openFile = WorkspaceOpenFile(entry, content),
        )
        compose.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    AppUiState(core = CoreState(workspace = workspace), workspaceEditorOpen = true, workspaceEditorText = content),
                    onListWorkspace = {}, onOpenFile = {}, onEditorChanged = {}, onSave = {}, onCloseEditor = {},
                )
            }
        }

        val lineNumbers = compose.onNodeWithTag("workspace-line-numbers")
        val editor = compose.onNodeWithTag("workspace-editor")
        val beforeNumbers = lineNumbers.fetchSemanticsNode().boundsInRoot.top
        val beforeEditor = editor.fetchSemanticsNode().boundsInRoot.top
        repeat(8) { compose.onNodeWithTag("workspace-editor-scroll").performTouchInput { swipeUp() } }
        val numberNode = lineNumbers.fetchSemanticsNode()
        val editorNode = editor.fetchSemanticsNode()
        val renderedNumbers = numberNode.config[SemanticsProperties.Text].joinToString { it.text }
        val renderedText = editorNode.config[SemanticsProperties.EditableText].text

        assertEquals(beforeNumbers - numberNode.boundsInRoot.top, beforeEditor - editorNode.boundsInRoot.top, 1f)
        assertTrue(numberNode.boundsInRoot.top < beforeNumbers)
        assertTrue(renderedNumbers.contains("140"))
        assertTrue(renderedText.contains("line 140"))
    }

    @Test
    fun conversationAndWorkspaceButtonsKeepExpectedBackSemantics() {
        var showWorkspace = 0
        val core = CoreState(
            codexes = listOf(CodexSummary("C", "会话", "/work", "IDLE")),
            conversation = ConversationState("C", historyComplete = true),
            workspace = WorkspaceState(supported = true, codexId = "C", currentDirectory = WorkspaceDirectory()),
        )
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(core = core, openCodexId = "C"),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {}, onSend = {}, onStop = {},
                    onShowWorkspace = { showWorkspace++ },
                )
            }
        }

        compose.onNodeWithTag("conversation-back").assertIsDisplayed()
        compose.onNodeWithTag("show-workspace").performClick()
        assertEquals(1, showWorkspace)
    }

    @Test
    fun horizontalGesturesSwitchPagesAndConversationRightSwipeCloses() {
        val page = mutableStateOf(ConversationPage.CONVERSATION)
        var closes = 0
        val core = CoreState(
            codexes = listOf(CodexSummary("C", "会话", "/work", "IDLE")),
            conversation = ConversationState("C", historyComplete = true),
            workspace = WorkspaceState(supported = true, codexId = "C", currentDirectory = WorkspaceDirectory()),
        )
        compose.setContent {
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(core = core, openCodexId = "C", conversationPage = page.value),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = { closes++ }, onDraftChanged = {}, onSend = {}, onStop = {},
                    onShowConversation = { page.value = ConversationPage.CONVERSATION },
                    onShowWorkspace = { page.value = ConversationPage.WORKSPACE },
                )
            }
        }

        compose.onNodeWithTag("conversation-screen").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("project-files-page").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.onNodeWithTag("conversation-screen").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, closes) }
    }
}
