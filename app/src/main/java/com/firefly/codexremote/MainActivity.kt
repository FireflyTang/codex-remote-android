package com.firefly.codexremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val documentIo = remember(context) { AndroidDocumentIo(context.contentResolver) }
            val scope = rememberCoroutineScope()
            var downloadTargetDocumentId by rememberSaveable { mutableStateOf("") }
            var downloadTargetPath by rememberSaveable { mutableStateOf("") }
            var downloadTargetCommandId by rememberSaveable { mutableStateOf("") }
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    viewModel.cancelWorkspaceTransfer()
                    downloadTargetDocumentId = ""
                    downloadTargetPath = ""
                    downloadTargetCommandId = ""
                } else {
                    val codexId = state.workspaceTransferCodexId
                    val directory = state.workspaceTransferDirectory
                    val kind = state.workspaceTransferUploadKind
                    if (viewModel.beginWorkspaceUploadRead(codexId, directory, kind)) scope.launch {
                        val workspace = state.core.workspace
                        val limit = workspace?.limits?.maxInlineUploadBytes ?: 0
                        try {
                            val documentId = uri.toString()
                            val selected = withContext(Dispatchers.IO) { readSelectedUpload(documentIo, documentId, limit) }
                            val metadata = selected.metadata
                            val bytes = selected.bytes
                            val destination = uploadDestinationPath(directory, metadata.displayName, kind)
                            if (viewModel.uploadWorkspaceEntry(codexId, directory, destination, kind, bytes) == null) {
                                viewModel.cancelWorkspaceTransfer("项目文件状态已变化，已取消本次上传")
                            }
                        } catch (error: TransferLimitException) {
                            viewModel.cancelWorkspaceTransfer("上传文件超过大小上限 ${formatByteLimit(error.limitBytes)}")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            viewModel.cancelWorkspaceTransfer("本地文件读取失败，请重新选择后重试")
                        }
                    }
                }
            }
            val onDocumentCreated: (Uri?) -> Unit = { uri ->
                if (uri == null) {
                    viewModel.cancelWorkspaceTransfer()
                    downloadTargetDocumentId = ""
                    downloadTargetPath = ""
                    downloadTargetCommandId = ""
                } else {
                    val codexId = state.workspaceTransferCodexId
                    val path = state.workspaceTransferPath
                    val entryKind = state.workspaceTransferEntryKind
                    val ready = state.workspaceDownloadReady?.takeIf {
                        it.relativePath == path && downloadResultMatches(it.result, path, entryKind)
                    }
                    val commandId = if (ready != null) {
                        ready.commandId.takeIf { viewModel.beginWorkspaceDownloadWrite(it, path) }
                    } else viewModel.downloadWorkspaceEntry(codexId, path, entryKind)
                    if (commandId != null) {
                        downloadTargetDocumentId = uri.toString()
                        downloadTargetPath = path
                        downloadTargetCommandId = commandId
                    } else viewModel.cancelWorkspaceTransfer("项目文件状态已变化，已取消本次下载")
                }
            }
            val createRegularDocument = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                onResult = onDocumentCreated,
            )
            val createZipDocument = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
                onResult = onDocumentCreated,
            )
            LaunchedEffect(state.workspaceDownloadReady, downloadTargetDocumentId, downloadTargetPath, downloadTargetCommandId) {
                val ready = state.workspaceDownloadReady ?: return@LaunchedEffect
                if (downloadTargetDocumentId.isBlank() || ready.commandId != downloadTargetCommandId ||
                    ready.relativePath != downloadTargetPath || !viewModel.beginWorkspaceDownloadWrite(ready.commandId, ready.relativePath)
                ) return@LaunchedEffect
                try {
                    val limit = state.core.workspace?.limits?.maxInlineDownloadBytes ?: 0
                    val bytes = withContext(Dispatchers.IO) { decodeDownloadBytes(ready.result, limit) }
                    withContext(Dispatchers.IO) { writeSelectedDownload(documentIo, downloadTargetDocumentId, bytes) }
                    viewModel.consumeWorkspaceDownload(ready.commandId)
                    downloadTargetDocumentId = ""
                    downloadTargetPath = ""
                    downloadTargetCommandId = ""
                } catch (error: TransferLimitException) {
                    viewModel.workspaceDownloadWriteFailed("下载内容超过大小上限 ${formatByteLimit(error.limitBytes)}，可重新选择保存位置后重试")
                    downloadTargetDocumentId = ""
                    downloadTargetPath = ""
                    downloadTargetCommandId = ""
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    viewModel.workspaceDownloadWriteFailed("本地保存失败，可重新选择保存位置后重试")
                    downloadTargetDocumentId = ""
                    downloadTargetPath = ""
                    downloadTargetCommandId = ""
                }
            }
            MaterialTheme {
                CodexRemoteScreen(
                    state, viewModel::setHostAddress, viewModel::connect, viewModel::refresh,
                    { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                    viewModel::openConversation, viewModel::closeConversation,
                    viewModel::setDraft, viewModel::sendMessage, viewModel::interruptTurn,
                    viewModel::openProjectDialog, viewModel::closeProjectDialog,
                    viewModel::setProjectPath, viewModel::listDirectories,
                    viewModel::listSessionCandidates, viewModel::createCodex,
                    viewModel::importSession, viewModel::renameCodex,
                    viewModel::unmanageCodex, viewModel::forgetCodex,
                    viewModel::showConversationPage, viewModel::showWorkspacePage,
                    viewModel::listWorkspaceEntries, viewModel::openWorkspaceFile,
                    viewModel::setWorkspaceEditorText, viewModel::saveWorkspaceFile,
                    viewModel::closeWorkspaceEditor,
                    { kind ->
                        if (viewModel.beginWorkspaceUploadPicker(kind)) openDocument.launch(uploadMimeTypes(kind))
                    },
                    { entry ->
                        if (viewModel.beginWorkspaceDownloadPicker(entry)) {
                            downloadTargetDocumentId = ""
                            downloadTargetPath = ""
                            downloadTargetCommandId = ""
                            val readyFilename = state.workspaceDownloadReady?.takeIf {
                                it.relativePath == entry.relativePath && downloadResultMatches(it.result, entry.relativePath, entry.kind)
                            }?.result?.filename.orEmpty()
                            val filename = downloadSuggestedFilename(entry, readyFilename)
                            if (entry.kind == "directory") createZipDocument.launch(filename) else createRegularDocument.launch(filename)
                        }
                    },
                    viewModel::respondApproval, viewModel::toggleUserInputOption,
                    viewModel::setUserInputFreeForm, viewModel::submitUserInput,
                )
            }
        }
    }
}

@Composable
fun CodexRemoteScreen(
    state: AppUiState,
    onHostAddressChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAuth: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onCloseConversation: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenProject: () -> Unit = {},
    onCloseProject: () -> Unit = {},
    onProjectPathChanged: (String) -> Unit = {},
    onListDirectories: (String) -> Unit = {},
    onListSessionCandidates: () -> Unit = {},
    onCreateCodex: () -> Unit = {},
    onImportSession: (String, String) -> Unit = { _, _ -> },
    onRenameCodex: (String, String) -> Unit = { _, _ -> },
    onUnmanageCodex: (String) -> Unit = {},
    onForgetCodex: (String) -> Unit = {},
    onShowConversation: () -> Unit = {},
    onShowWorkspace: () -> Unit = {},
    onListWorkspace: (String) -> Unit = {},
    onOpenWorkspaceFile: (WorkspaceEntry) -> Unit = {},
    onWorkspaceEditorChanged: (String) -> Unit = {},
    onSaveWorkspaceFile: () -> Unit = {},
    onCloseWorkspaceEditor: () -> Unit = {},
    onChooseWorkspaceUpload: (String) -> Unit = {},
    onChooseWorkspaceDownload: (WorkspaceEntry) -> Unit = {},
    onRespondApproval: (String, String) -> Unit = { _, _ -> },
    onToggleUserInputOption: (String, String, String) -> Unit = { _, _, _ -> },
    onUserInputFreeFormChanged: (String, String, String) -> Unit = { _, _, _ -> },
    onSubmitUserInput: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (state.openCodexId != null) {
            BackHandler(onBack = if (state.conversationPage == ConversationPage.WORKSPACE) onShowConversation else onCloseConversation)
            ConversationScreen(
                state.core.codexes.find { it.id == state.openCodexId }?.title.orEmpty().ifBlank { "Codex 会话" },
                state, onDraftChanged, onSend, onStop, onCloseConversation,
                onShowConversation, onShowWorkspace, onListWorkspace, onOpenWorkspaceFile,
                onWorkspaceEditorChanged, onSaveWorkspaceFile, onCloseWorkspaceEditor,
                onChooseWorkspaceUpload, onChooseWorkspaceDownload,
                onRespondApproval, onToggleUserInputOption, onUserInputFreeFormChanged,
                onSubmitUserInput,
            )
        } else {
            HomeScreen(
                state, onHostAddressChanged, onConnect, onRefresh, onOpenAuth, onOpenConversation,
                onOpenProject, onRenameCodex, onUnmanageCodex, onForgetCodex,
            )
            if (state.projectDialogOpen) {
                ProjectDialog(
                    state, onCloseProject, onProjectPathChanged, onListDirectories,
                    onListSessionCandidates, onCreateCodex, onImportSession, onOpenConversation,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    onHostAddressChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAuth: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenProject: () -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onUnmanageCodex: (String) -> Unit,
    onForgetCodex: (String) -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Codex Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("通过 Tailnet 连接你的 Codex Host", color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusCard(state.core)
        OutlinedTextField(
            state.hostAddress, onHostAddressChanged, Modifier.fillMaxWidth(),
            label = { Text("Host 地址") },
            supportingText = { Text(state.core.error.ifBlank { phaseDescription(state.core.phase) }) },
            isError = state.core.error.isNotBlank(), singleLine = true,
        )
        Button(
            onConnect, Modifier.fillMaxWidth(),
            enabled = state.hostAddress.isNotBlank() && state.core.phase !in BusyPhases,
        ) { Text(if (state.core.phase == "ready") "重新连接" else "连接") }
        if (state.core.authUrl.isNotBlank()) {
            Button({ onOpenAuth(state.core.authUrl) }, Modifier.fillMaxWidth()) { Text("打开 Tailscale 登录") }
        }
        Button(
            onOpenProject,
            Modifier.fillMaxWidth().testTag("open-project"),
            enabled = state.core.phase == "ready",
        ) { Text("打开项目") }
        CodexList(state.core, onRefresh, onOpenConversation, onRenameCodex, onUnmanageCodex, onForgetCodex)
    }
}

@Composable
private fun StatusCard(state: CoreState) {
    val ready = state.phase == "ready" || state.phase in ConversationPhases
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(10.dp), CircleShape, color = if (ready) Color(0xFF2E7D32) else Color(0xFF8A8A94), content = {})
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Tailnet 状态", fontWeight = FontWeight.Medium)
                Text(phaseDescription(state.phase), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.tailnetIPs.isNotEmpty()) Text(state.tailnetIPs.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun CodexList(
    core: CoreState,
    onRefresh: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onUnmanageCodex: (String) -> Unit,
    onForgetCodex: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Codex", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (core.phase == "ready") Button(onRefresh) { Text("刷新") }
        }
        if (core.codexes.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (core.phase == "ready") "Host 暂无 Codex" else "暂无 Codex 会话")
                    if (core.phase != "ready") Text("连接 Host 后将在这里显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else core.codexes.forEach { codex ->
            val title = codex.title.ifBlank { codex.id }
            var menuExpanded by remember(codex.id) { mutableStateOf(false) }
            var renameOpen by remember(codex.id) { mutableStateOf(false) }
            var renameTitle by remember(codex.id, codex.title) { mutableStateOf(title) }
            Card(
                Modifier.fillMaxWidth().testTag("codex-item-${codex.id}")
                    .semantics { contentDescription = "打开会话：$title" }
                    .clickable { onOpenConversation(codex.id) },
            ) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Box {
                            IconButton(
                                { menuExpanded = true },
                                Modifier.testTag("codex-menu-${codex.id}").semantics { contentDescription = "会话操作：$title" },
                            ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                            DropdownMenu(menuExpanded, { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = { menuExpanded = false; renameOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("休眠") },
                                    onClick = { menuExpanded = false; onUnmanageCodex(codex.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("忘记记录") },
                                    onClick = { menuExpanded = false; onForgetCodex(codex.id) },
                                )
                            }
                        }
                    }
                    if (codex.cwd.isNotBlank()) Text(codex.cwd, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    codexStatusDescription(codex.managementState, codex.status).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (renameOpen) {
                AlertDialog(
                    onDismissRequest = { renameOpen = false },
                    title = { Text("重命名会话") },
                    text = { OutlinedTextField(renameTitle, { renameTitle = it }, label = { Text("名称") }, singleLine = true) },
                    confirmButton = {
                        TextButton(
                            { onRenameCodex(codex.id, renameTitle); renameOpen = false },
                            enabled = renameTitle.isNotBlank(),
                        ) { Text("保存") }
                    },
                    dismissButton = { TextButton({ renameOpen = false }) { Text("取消") } },
                )
            }
        }
    }
}

@Composable
internal fun ProjectDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onPathChanged: (String) -> Unit,
    onListDirectories: (String) -> Unit,
    onListCandidates: () -> Unit,
    onCreate: () -> Unit,
    onImport: (String, String) -> Unit,
    onOpenManaged: (String) -> Unit,
) {
    val core = state.core
    val busy = core.phase in ProjectBusyPhases || state.pendingProjectCommandId.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开项目") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    state.projectPath, onPathChanged, Modifier.fillMaxWidth().testTag("project-path"),
                    label = { Text("当前路径") }, singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { parentProjectPath(state.projectPath)?.let(onListDirectories) },
                        enabled = !busy && parentProjectPath(state.projectPath) != null,
                    ) { Text("上一级") }
                    OutlinedButton(
                        { onListDirectories(state.projectPath) },
                        enabled = !busy && state.projectPath.isNotBlank(),
                    ) { Text("浏览此路径") }
                }
                if (core.phase == "loading_directories") LinearProgressIndicator(Modifier.fillMaxWidth())
                core.directoryListing?.directories.orEmpty().forEach { directory ->
                    TextButton(
                        { onListDirectories(directory.path) },
                        Modifier.fillMaxWidth().testTag("project-directory-${directory.name}"),
                    ) { Text("📁 ${directory.name.ifBlank { directory.path }}", Modifier.fillMaxWidth()) }
                }
                HorizontalDivider()
                Button(
                    onListCandidates,
                    Modifier.fillMaxWidth().testTag("list-session-candidates"),
                    enabled = !busy && state.projectPath.isNotBlank(),
                ) { Text("查看此目录下可导入会话") }
                if (core.phase == "loading_session_candidates") LinearProgressIndicator(Modifier.fillMaxWidth())
                core.sessionCandidates?.sessions.orEmpty().forEach { candidate ->
                    SessionCandidateRow(candidate, busy, onImport, onOpenManaged)
                }
                if (core.error.isNotBlank()) Text(core.error, color = MaterialTheme.colorScheme.error)
                Button(
                    onCreate,
                    Modifier.fillMaxWidth().testTag("create-codex"),
                    enabled = !busy && state.projectPath.isNotBlank(),
                ) { Text(if (core.phase == "creating_codex") "新建中…" else "新建此项目") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("关闭") } },
    )
}

@Composable
private fun SessionCandidateRow(
    candidate: SessionCandidate,
    busy: Boolean,
    onImport: (String, String) -> Unit,
    onOpenManaged: (String) -> Unit,
) {
    val resumable = candidate.availability.uppercase().endsWith("RESUMABLE") ||
        candidate.availability.uppercase().endsWith("AVAILABLE")
    val managed = candidate.availability.uppercase().endsWith("ALREADY_MANAGED") || candidate.managedCodexId.isNotBlank()
    val action = when {
        managed -> { -> if (candidate.managedCodexId.isNotBlank()) onOpenManaged(candidate.managedCodexId) }
        resumable -> { -> onImport(candidate.sessionId, candidate.source) }
        else -> null
    }
    Card(
        Modifier.fillMaxWidth().then(if (action != null && !busy) Modifier.clickable(onClick = action) else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(candidate.title.ifBlank { candidate.sessionId.ifBlank { "未命名会话" } }, fontWeight = FontWeight.Medium)
            if (candidate.preview.isNotBlank()) Text(candidate.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(sessionAvailabilityDescription(candidate.availability, managed), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConversationScreen(
    title: String,
    state: AppUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onShowConversation: () -> Unit,
    onShowWorkspace: () -> Unit,
    onListWorkspace: (String) -> Unit,
    onOpenWorkspaceFile: (WorkspaceEntry) -> Unit,
    onWorkspaceEditorChanged: (String) -> Unit,
    onSaveWorkspaceFile: () -> Unit,
    onCloseWorkspaceEditor: () -> Unit,
    onChooseWorkspaceUpload: (String) -> Unit,
    onChooseWorkspaceDownload: (WorkspaceEntry) -> Unit,
    onRespondApproval: (String, String) -> Unit,
    onToggleUserInputOption: (String, String, String) -> Unit,
    onUserInputFreeFormChanged: (String, String, String) -> Unit,
    onSubmitUserInput: (String) -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val core = state.core
    val conversation = activeConversation(state)
    Column(
        Modifier.fillMaxSize().imePadding()
            .pointerInput(state.conversationPage, onBack) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        when {
                            state.conversationPage == ConversationPage.CONVERSATION && drag < -180f -> onShowWorkspace()
                            state.conversationPage == ConversationPage.CONVERSATION && drag > 180f -> onBack()
                            state.conversationPage == ConversationPage.WORKSPACE && drag > 180f -> onShowConversation()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.conversationPage == ConversationPage.CONVERSATION) {
                TextButton(
                    onBack, Modifier.testTag("conversation-back").semantics { contentDescription = "返回会话列表" },
                ) { Text("返回") }
            } else {
                TextButton(
                    onShowConversation,
                    Modifier.testTag("workspace-back").semantics { contentDescription = "返回会话" },
                ) { Text("返回会话") }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.conversationPage == ConversationPage.CONVERSATION) {
                Button(onShowConversation, Modifier.weight(1f).testTag("show-conversation")) { Text("会话") }
                OutlinedButton(onShowWorkspace, Modifier.weight(1f).testTag("show-workspace")) { Text("项目文件") }
            } else {
                OutlinedButton(onShowConversation, Modifier.weight(1f).testTag("show-conversation")) { Text("会话") }
                Button(onShowWorkspace, Modifier.weight(1f).testTag("show-workspace")) { Text("项目文件") }
            }
        }
        if (state.conversationPage == ConversationPage.CONVERSATION) {
            Column(
                Modifier.weight(1f).fillMaxWidth().testTag("conversation-screen")
                    .semantics { contentDescription = "会话页面" },
            ) {
                if (core.error.isNotBlank()) Text(core.error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                if (conversation != null && !conversation.historyComplete) {
                    Text(
                        "历史记录可能不完整",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("history-incomplete"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ConversationHistory(core, Modifier.weight(1f).fillMaxWidth())
                PendingRequestsPanel(
                    conversation = conversation,
                    drafts = state.userInputDrafts,
                    onRespondApproval = onRespondApproval,
                    onToggleOption = onToggleUserInputOption,
                    onFreeFormChanged = onUserInputFreeFormChanged,
                    onSubmitUserInput = onSubmitUserInput,
                    submittingRequestIds = state.submittingRequestIds,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        state.draft, onDraftChanged, Modifier.weight(1f).semantics { contentDescription = "消息输入框" },
                        placeholder = { Text("输入消息") }, minLines = 1, maxLines = 5,
                        enabled = conversation?.running != true,
                    )
                    if (conversation?.running == true) {
                        Button(
                            onStop,
                            Modifier.testTag("conversation-stop").semantics { contentDescription = "停止任务" },
                            enabled = !state.stoppingTurn,
                        ) { Text(if (state.stoppingTurn) "停止中" else "停止") }
                    } else {
                        Button(
                            onSend,
                            Modifier.testTag("conversation-send").semantics { contentDescription = "发送消息" },
                            enabled = state.draft.isNotBlank() && conversation != null && core.phase !in BusyPhases,
                        ) { Text("发送") }
                    }
                }
            }
        } else {
            WorkspaceScreen(
                state,
                Modifier.weight(1f).fillMaxWidth(),
                onListWorkspace,
                onOpenWorkspaceFile,
                onWorkspaceEditorChanged,
                onSaveWorkspaceFile,
                onCloseWorkspaceEditor,
                onChooseWorkspaceUpload,
                onChooseWorkspaceDownload,
            )
        }
    }
}

@Composable
internal fun PendingRequestsPanel(
    conversation: ConversationState?,
    drafts: Map<String, Map<String, UserInputAnswerDraft>>,
    onRespondApproval: (String, String) -> Unit,
    onToggleOption: (String, String, String) -> Unit,
    onFreeFormChanged: (String, String, String) -> Unit,
    onSubmitUserInput: (String) -> Unit,
    submittingRequestIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val requests = conversation?.pendingRequests.orEmpty()
    val watchError = conversation?.pendingWatch?.error
    if (requests.isEmpty() && watchError == null) return
    Column(
        modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("pending-requests"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        watchError?.let {
            Text(
                "等待处理请求时出错：${pendingErrorDescription(it)}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("pending-watch-error"),
            )
        }
        requests.forEach { request ->
            when (request.type) {
                "approval" -> ApprovalRequestCard(
                    request,
                    request.requestId in submittingRequestIds,
                    onRespondApproval,
                )
                "user_input" -> UserInputRequestCard(
                    request,
                    drafts[request.requestId].orEmpty(),
                    request.requestId in submittingRequestIds,
                    onToggleOption,
                    onFreeFormChanged,
                    onSubmitUserInput,
                )
            }
        }
    }
}

@Composable
private fun ApprovalRequestCard(
    request: PendingRequest,
    localSubmitting: Boolean,
    onRespond: (String, String) -> Unit,
) {
    val submitting = request.inFlight || localSubmitting
    Card(Modifier.fillMaxWidth().testTag("approval-${request.requestId}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.title.ifBlank { "需要批准" }, fontWeight = FontWeight.SemiBold)
            if (request.explanation.isNotBlank()) Text(request.explanation)
            if (request.command.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        request.command.joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            request.error?.let {
                Text(
                    "提交失败：${pendingErrorDescription(it)}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pending-error-${request.requestId}"),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                request.allowedDecisions.distinct().forEach { decision ->
                    val label = approvalDecisionLabel(decision) ?: return@forEach
                    OutlinedButton(
                        { onRespond(request.requestId, decision) },
                        enabled = !submitting,
                        modifier = Modifier.weight(1f).testTag("approval-decision-${request.requestId}-$decision"),
                    ) { Text(if (submitting) "处理中…" else label) }
                }
            }
        }
    }
}

@Composable
private fun UserInputRequestCard(
    request: PendingRequest,
    drafts: Map<String, UserInputAnswerDraft>,
    localSubmitting: Boolean,
    onToggleOption: (String, String, String) -> Unit,
    onFreeFormChanged: (String, String, String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val submitting = request.inFlight || localSubmitting
    Card(Modifier.fillMaxWidth().testTag("user-input-${request.requestId}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Codex 需要你的回答", fontWeight = FontWeight.SemiBold)
            request.questions.forEach { question ->
                val answer = drafts[question.questionId] ?: UserInputAnswerDraft()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelLarge)
                    Text(question.prompt)
                    question.options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !submitting) {
                                onToggleOption(request.requestId, question.questionId, option.optionId)
                            }.testTag("option-${request.requestId}-${question.questionId}-${option.optionId}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (question.allowsMultiple) {
                                Checkbox(
                                    checked = option.optionId in answer.selectedOptionIds,
                                    onCheckedChange = null,
                                    enabled = !submitting,
                                )
                            } else {
                                RadioButton(
                                    selected = option.optionId in answer.selectedOptionIds,
                                    onClick = null,
                                    enabled = !submitting,
                                )
                            }
                            Column {
                                Text(option.label)
                                if (option.description.isNotBlank()) {
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (question.allowsFreeForm) {
                        OutlinedTextField(
                            value = answer.freeFormText,
                            onValueChange = { onFreeFormChanged(request.requestId, question.questionId, it) },
                            modifier = Modifier.fillMaxWidth()
                                .testTag("freeform-${request.requestId}-${question.questionId}"),
                            label = { Text("补充回答") },
                            enabled = !submitting,
                            minLines = 1,
                            maxLines = 4,
                        )
                    }
                }
            }
            request.completeness?.takeIf { it.truncated || it.incomplete }?.let {
                Text("问题内容可能不完整", color = MaterialTheme.colorScheme.error)
            }
            request.error?.let {
                Text(
                    "提交失败：${pendingErrorDescription(it)}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pending-error-${request.requestId}"),
                )
            }
            Button(
                { onSubmit(request.requestId) },
                enabled = !submitting && areUserInputAnswersComplete(request, drafts),
                modifier = Modifier.fillMaxWidth().testTag("submit-user-input-${request.requestId}"),
            ) { Text(if (submitting) "提交中…" else "提交回答") }
        }
    }
}

internal fun approvalDecisionLabel(decision: String): String? = when (decision) {
    "allow" -> "允许"
    "allow_for_session" -> "本会话允许"
    "deny" -> "拒绝"
    else -> null
}

@Composable
internal fun WorkspaceScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onListWorkspace: (String) -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onEditorChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCloseEditor: () -> Unit,
    onChooseUpload: (String) -> Unit = {},
    onChooseDownload: (WorkspaceEntry) -> Unit = {},
) {
    val workspace = state.core.workspace
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("project-files-page")
            .semantics { contentDescription = "项目文件页面" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            workspace == null || workspace.loading == "workspace" -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取项目文件信息…")
                workspaceLocalTransferStatusDescription(state.workspaceLocalTransferStatus).takeIf { it.isNotBlank() }?.let {
                    Text(it, modifier = Modifier.testTag("workspace-local-transfer-status"), color = MaterialTheme.colorScheme.primary)
                }
            }
            !workspace.supported -> Text("Host 不支持项目文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val directory = workspace.currentDirectory
                val relativeDirectory = directory?.relativeDirectory.orEmpty()
                val localTransferBusy = state.workspaceLocalTransferStatus != "none"
                Text(
                    workspace.workspaceRoot + if (relativeDirectory.isBlank()) "" else "/$relativeDirectory",
                    Modifier.testTag("workspace-path"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkspaceBreadcrumb(relativeDirectory, workspace.loading == "none", onListWorkspace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val uploadEnabled = canUploadWorkspace(workspace) && !localTransferBusy
                    OutlinedButton(
                        { onChooseUpload("regular_file") },
                        enabled = uploadEnabled,
                        modifier = Modifier.testTag("workspace-upload-file"),
                    ) { Text("上传文件") }
                    OutlinedButton(
                        { onChooseUpload("zip_directory") },
                        enabled = uploadEnabled,
                        modifier = Modifier.testTag("workspace-upload-zip"),
                    ) { Text("上传 ZIP") }
                }
                Text(
                    "上传上限 ${formatByteLimit(workspace.limits.maxInlineUploadBytes)} · 下载上限 ${formatByteLimit(workspace.limits.maxInlineDownloadBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("workspace-transfer-limits"),
                )
                workspaceLocalTransferStatusDescription(state.workspaceLocalTransferStatus).takeIf { it.isNotBlank() }?.let {
                    Text(it, modifier = Modifier.testTag("workspace-local-transfer-status"), color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { onListWorkspace(parentWorkspacePath(relativeDirectory)) },
                        enabled = relativeDirectory.isNotBlank() && workspace.loading == "none",
                        modifier = Modifier.testTag("workspace-parent"),
                    ) { Text("上一级") }
                    if (workspace.loading == "entries") Text("正在加载…", Modifier.align(Alignment.CenterVertically))
                    if (workspace.loading == "upload") Text("正在上传…", Modifier.align(Alignment.CenterVertically))
                    if (workspace.loading == "download") Text("正在下载…", Modifier.align(Alignment.CenterVertically))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(directory?.entries.orEmpty(), key = { _, entry -> entry.relativePath }) { _, entry ->
                        WorkspaceEntryRow(
                            entry, workspace.loading == "none", localTransferBusy,
                            onListWorkspace, onOpenFile, onChooseDownload, workspace,
                        )
                    }
                }
                workspaceErrorDescription(workspace.error).takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace-error"))
                }
                state.workspaceTransferError.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace-transfer-error"))
                }
                state.workspaceTransferNotice.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("workspace-transfer-notice"))
                }
            }
        }
    }
    if (state.workspaceEditorOpen && workspace?.openFile != null) {
        WorkspaceEditorDialog(state, workspace, onEditorChanged, onSave, onCloseEditor)
    }
}

@Composable
private fun WorkspaceBreadcrumb(path: String, enabled: Boolean, onListWorkspace: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("workspace-breadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton({ onListWorkspace("") }, enabled = enabled) { Text("根目录") }
        var accumulated = ""
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
            accumulated = if (accumulated.isBlank()) segment else "$accumulated/$segment"
            val target = accumulated
            Text("/")
            TextButton({ onListWorkspace(target) }, enabled = enabled) { Text(segment) }
        }
    }
}

@Composable
private fun WorkspaceEntryRow(
    entry: WorkspaceEntry,
    idle: Boolean,
    localTransferBusy: Boolean,
    onListWorkspace: (String) -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onChooseDownload: (WorkspaceEntry) -> Unit,
    workspace: WorkspaceState,
) {
    val directory = entry.kind == "directory"
    val openable = directory || (entry.kind == "regular_file" && entry.textViewable)
    val description = when {
        directory -> "目录"
        entry.kind != "regular_file" -> "不支持的文件类型"
        !entry.textViewable -> "过大或非文本，无法打开"
        !entry.textEditable -> "只读文本"
        else -> "可编辑文本"
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { if (directory) onListWorkspace(entry.relativePath) else onOpenFile(entry) },
                enabled = idle && openable,
                modifier = Modifier.weight(1f).testTag("workspace-entry-${entry.relativePath}"),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (directory) "📁 " else "📄 ") + entry.name.ifBlank { entry.relativePath })
                    Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(
                { onChooseDownload(entry) },
                enabled = idle && !localTransferBusy && canStartDownload(workspace) && entry.kind in setOf("regular_file", "directory"),
                modifier = Modifier.testTag("workspace-download-${entry.relativePath}"),
            ) { Text("下载") }
        }
    }
}

@Composable
private fun WorkspaceEditorDialog(
    state: AppUiState,
    workspace: WorkspaceState,
    onEditorChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val entry = workspace.openFile?.entry ?: return
    val saveAllowed = canSaveWorkspaceFile(entry, workspace.accessState) && workspace.loading != "write"
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("workspace-editor-dialog"),
        title = { Text(entry.name.ifBlank { entry.relativePath }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        state.workspaceEditorText.lines().indices.joinToString("\n") { (it + 1).toString() },
                        Modifier.padding(top = 16.dp, end = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        state.workspaceEditorText,
                        onEditorChanged,
                        Modifier.weight(1f).fillMaxHeight().testTag("workspace-editor"),
                        enabled = entry.textEditable,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!saveAllowed) Text(workspaceSaveUnavailableReason(entry, workspace.accessState), style = MaterialTheme.typography.labelSmall)
                workspaceErrorDescription(workspace.error).takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onSave, enabled = saveAllowed, modifier = Modifier.testTag("workspace-save")) {
                Text(if (workspace.loading == "write") "保存中…" else "保存")
            }
        },
        dismissButton = { TextButton(onClose) { Text("取消") } },
    )
}

internal fun parentWorkspacePath(path: String): String = path.trim('/').substringBeforeLast('/', "")

internal fun workspaceSaveUnavailableReason(entry: WorkspaceEntry, access: WorkspaceAccessState): String = when {
    !entry.textEditable -> "该文件只读，不能保存"
    entry.revision.isBlank() -> "缺少文件版本，需重新打开"
    !access.mutationStatus.equals("allowed", ignoreCase = true) -> "Codex 正在使用项目文件，暂时不能保存"
    access.quiescenceToken.isBlank() -> "缺少写入令牌，暂时不能保存"
    else -> ""
}

@Composable
internal fun ConversationHistory(core: CoreState, modifier: Modifier = Modifier) {
    val conversation = core.conversation
    Box(
        modifier.testTag("conversation-history").semantics { contentDescription = "会话历史" },
    ) {
        when {
            core.phase == "loading_conversation" && conversation == null ->
                Text("正在加载历史记录…", Modifier.align(Alignment.Center))
            conversation?.timelineEntries.isNullOrEmpty() ->
                Text("还没有消息", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    conversation.timelineEntries.asReversed(),
                    key = { index, entry ->
                        when (entry) {
                            is ConversationTimelineEntry.Item -> entry.item.itemId.ifBlank {
                                "$index-${entry.item.type}-${entry.item.hashCode()}"
                            }
                            is ConversationTimelineEntry.TurnFailure -> "failure-${entry.turnId}-$index"
                        }
                    },
                ) { _, entry ->
                    when (entry) {
                        is ConversationTimelineEntry.Item -> TimelineItem(entry.item)
                        is ConversationTimelineEntry.TurnFailure -> TurnFailureCard(entry.failure)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimelineItem(item: ConversationItem) {
    when (item.type) {
        "user_message" -> MessageBubble("你", item.userMessage?.text.orEmpty(), true, item)
        "agent_message" -> MessageBubble("Codex", item.agentMessage?.text.orEmpty(), false, item)
        "plan" -> PlanCard(item)
        "file_change" -> FileChangeCard(item)
        else -> ProcessCard(item)
    }
}

@Composable
private fun MessageBubble(label: String, text: String, user: Boolean, item: ConversationItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            Modifier.fillMaxWidth(if (user) 0.86f else 0.94f),
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (item.status == "running") Text("生成中…", style = MaterialTheme.typography.labelSmall)
                    if (item.status == "failed") Text("失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                MarkdownBody(text, "（空消息）", MaterialTheme.typography.bodyLarge)
                CompletenessNotice(item.completeness)
            }
        }
    }
}

@Composable
private fun ProcessCard(item: ConversationItem) {
    val forceOpen = item.status == "running" || item.status == "failed"
    var expanded by rememberSaveable(item.itemId, item.status) { mutableStateOf(forceOpen) }
    val title = when (item.type) {
        "reasoning_summary" -> "思考过程"
        "command" -> "命令"
        "tool" -> "工具调用"
        else -> "过程记录"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    ProcessSummary(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
                Text(statusDescription(item.status), style = MaterialTheme.typography.labelSmall, color = statusColor(item.status))
                Spacer(Modifier.width(6.dp))
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                when (item.type) {
                    "reasoning_summary" -> MarkdownBody(item.reasoningSummary?.text.orEmpty(), "暂无思考摘要")
                    "command" -> CommandBody(item.command)
                    "tool" -> ToolBody(item.tool)
                    else -> Text("暂不支持的过程记录", style = MaterialTheme.typography.bodySmall)
                }
                CompletenessNotice(item.completeness)
            }
        }
    }
}

private fun ProcessSummary(item: ConversationItem): String? = when (item.type) {
    "command" -> item.command?.argv?.joinToString(" ")?.ifBlank { "命令内容为空" }
    "tool" -> item.tool?.name?.ifBlank { "未命名工具" }
    "reasoning_summary" -> if (item.status == "running") "正在思考" else null
    else -> null
}

@Composable
private fun CommandBody(command: CommandItem?) {
    if (command == null) return
    if (command.cwd.isNotBlank()) Text("目录：${command.cwd}", style = MaterialTheme.typography.bodySmall)
    SelectableBody(command.argv.joinToString(" "), "命令内容为空")
    if (command.output.isNotBlank()) SelectableBody(command.output, "") else Text("暂无输出", style = MaterialTheme.typography.bodySmall)
    if (command.hasExitCode) Text("退出码：${command.exitCode ?: "未知"}", style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ToolBody(tool: ToolItem?) {
    if (tool == null) return
    if (tool.summary.isNotBlank()) MarkdownBody(tool.summary, "", MaterialTheme.typography.bodyMedium)
    if (tool.resultSummary.isNotBlank()) MarkdownBody(tool.resultSummary, "")
    if (tool.summary.isBlank() && tool.resultSummary.isBlank()) Text("暂无工具结果", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SelectableBody(text: String, emptyText: String) {
    SelectionContainer {
        Text(text.ifBlank { emptyText }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlanCard(item: ConversationItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("计划", fontWeight = FontWeight.Medium)
                Text(statusDescription(item.status), style = MaterialTheme.typography.labelSmall, color = statusColor(item.status))
            }
            val steps = item.plan?.steps.orEmpty()
            if (steps.isEmpty()) Text("暂无步骤", style = MaterialTheme.typography.bodySmall)
            steps.forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(planStepMark(step.status), fontWeight = FontWeight.SemiBold)
                    Text(step.text.ifBlank { "未命名步骤" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(planStepDescription(step.status), style = MaterialTheme.typography.labelSmall)
                }
            }
            CompletenessNotice(item.completeness)
        }
    }
}

@Composable
private fun FileChangeCard(item: ConversationItem) {
    var diffExpanded by rememberSaveable(item.itemId) { mutableStateOf(false) }
    val fileChange = item.fileChange
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("文件变更", fontWeight = FontWeight.Medium)
                Text(statusDescription(item.status), style = MaterialTheme.typography.labelSmall, color = statusColor(item.status))
            }
            val changes = fileChange?.changes.orEmpty()
            if (changes.isEmpty()) Text("暂无文件摘要", style = MaterialTheme.typography.bodySmall)
            changes.forEach { change ->
                val path = when (change.kind) {
                    "renamed" -> "${change.oldPath.ifBlank { change.path }} → ${change.newPath.ifBlank { change.path }}"
                    else -> change.path.ifBlank { change.newPath.ifBlank { change.oldPath } }
                }
                Text("${fileKindDescription(change.kind)}  ${path.ifBlank { "未知文件" }}", style = MaterialTheme.typography.bodySmall)
            }
            if (!fileChange?.unifiedDiff.isNullOrBlank()) {
                TextButton({ diffExpanded = !diffExpanded }) { Text(if (diffExpanded) "收起差异" else "查看差异") }
                if (diffExpanded) SelectableBody(fileChange!!.unifiedDiff, "")
            }
            CompletenessNotice(item.completeness)
        }
    }
}

@Composable
private fun TurnFailureCard(failure: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("本轮执行失败", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
            SelectionContainer { Text(failure, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun CompletenessNotice(completeness: ItemCompleteness?) {
    if (completeness == null || (!completeness.truncated && !completeness.incomplete)) return
    val detail = buildList {
        if (completeness.truncated) add("内容已截断")
        if (completeness.incomplete) add("内容不完整")
        if (completeness.originalSizeBytes > 0) add("原始 ${completeness.originalSizeBytes} 字节")
        if (completeness.reason.isNotBlank()) add(completeness.reason)
    }.joinToString(" · ")
    Text(detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "failed" -> MaterialTheme.colorScheme.error
    "running" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusDescription(status: String) = when (status) {
    "running" -> "进行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    else -> ""
}

private fun planStepMark(status: String) = when (status) {
    "completed" -> "✓"
    "running", "in_progress" -> "●"
    "failed" -> "×"
    else -> "○"
}

private fun planStepDescription(status: String) = when (status) {
    "completed" -> "完成"
    "running", "in_progress" -> "进行中"
    "failed" -> "失败"
    else -> "待处理"
}

private fun fileKindDescription(kind: String) = when (kind) {
    "added" -> "新增"
    "modified" -> "修改"
    "deleted" -> "删除"
    "renamed" -> "重命名"
    else -> "变更"
}

private val ConversationPhases = setOf("loading_conversation", "starting_turn", "polling_turn", "interrupting_turn")
private val ProjectBusyPhases = setOf(
    "loading_directories", "loading_session_candidates", "creating_codex", "importing_session",
)
private val BusyPhases = setOf("starting_tailnet", "auth_required", "connecting_host", "refreshing") +
    ConversationPhases + ProjectBusyPhases + setOf("renaming_codex", "unmanaging_codex", "forgetting_codex")

private fun phaseDescription(phase: String) = when (phase) {
    "idle", "stopped" -> "等待启动"
    "configured" -> "配置完成"
    "starting_tailnet" -> "正在启动 Tailnet"
    "auth_required" -> "需要登录 Tailscale"
    "connecting_host" -> "正在连接 Host"
    "ready" -> "已连接"
    "refreshing" -> "正在刷新"
    "loading_conversation" -> "正在加载会话"
    "starting_turn" -> "正在发送"
    "polling_turn" -> "Codex 正在处理"
    "interrupting_turn" -> "正在停止"
    "error" -> "连接失败"
    else -> phase
}

internal fun codexStatusDescription(managementState: String, status: String) = when (managementState.uppercase()) {
    "UNMANAGED", "MANAGEMENT_STATE_UNMANAGED" -> "休眠"
    "EXPIRING_SOON", "MANAGEMENT_STATE_EXPIRING_SOON" -> "即将休眠"
    else -> runningStatusDescription(status)
}

private fun runningStatusDescription(status: String) = when (status.uppercase()) {
    "IDLE" -> "待命"
    "RUNNING" -> "运行中"
    "WAITING_FOR_APPROVAL" -> "等待确认"
    "WAITING_FOR_USER_INPUT" -> "等待输入"
    "INTERRUPTING" -> "正在停止"
    "UNAVAILABLE" -> "休眠"
    "ERROR" -> "异常"
    else -> status
}

internal fun sessionAvailabilityDescription(availability: String, managed: Boolean = false) = when {
    managed || availability.uppercase().endsWith("ALREADY_MANAGED") -> "已管理，点击打开"
    availability.uppercase().endsWith("RESUMABLE") || availability.uppercase().endsWith("AVAILABLE") -> "可继续，点击导入"
    availability.isBlank() -> "状态未知"
    else -> when {
        availability.uppercase().endsWith("MISSING") -> "会话不可用"
        availability.uppercase().endsWith("UNAVAILABLE") -> "暂不可用"
        else -> availability
    }
}

internal fun parentProjectPath(path: String): String? {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val separator = trimmed.lastIndexOf('/')
    return when {
        separator < 0 -> null
        separator == 0 -> if (trimmed == "/") null else "/"
        else -> trimmed.substring(0, separator)
    }
}

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewScreen() {
    MaterialTheme {
        CodexRemoteScreen(
            AppUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {},
        )
    }
}
