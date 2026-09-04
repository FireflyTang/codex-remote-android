package com.firefly.codexremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        viewModel.onActivityStarted(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        viewModel.onActivityStopped(SystemClock.elapsedRealtime(), isChangingConfigurations)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val documentIo = remember(context) { AndroidDocumentIo(context.contentResolver) }
            val diagnosticExporter = remember(context) { DiagnosticExporter(context.applicationContext) }
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
            CodexRemoteTheme {
                CodexRemoteScreen(
                    state, viewModel::setHostAddress, viewModel::connect, viewModel::refresh,
                    { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                    viewModel::openConversation, viewModel::closeConversation,
                    viewModel::setDraft, viewModel::sendMessage, viewModel::interruptTurn,
                    viewModel::openProjectDialog, viewModel::closeProjectDialog,
                    viewModel::setProjectPath, viewModel::listDirectories,
                    viewModel::listSessionCandidates, viewModel::createCodex,
                    viewModel::confirmCreateMissingDirectory, viewModel::cancelCreateMissingDirectory,
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
                    {
                        scope.launch {
                            try {
                                val export = withContext(Dispatchers.IO) { diagnosticExporter.create() }
                                viewModel.reportDiagnosticExport(true, "诊断日志已生成，请选择分享方式")
                                context.startActivity(export.shareIntent)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                viewModel.reportDiagnosticExport(false, "诊断日志导出失败：${error.message ?: "未知错误"}")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (selected) CodexColors.Indigo else CodexColors.TextMuted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.width(48.dp).height(3.dp).clip(CircleShape).background(if (selected) CodexColors.Indigo else Color.Transparent))
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
    onConfirmCreateMissingDirectory: () -> Unit = {},
    onCancelCreateMissingDirectory: () -> Unit = {},
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
    onExportDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val displayState = when {
        state.foregroundRecoveryInProgress ->
            state.copy(core = state.core.copy(phase = "recovering_foreground", error = ""))
        state.foregroundRecoveryError.isNotBlank() ->
            state.copy(core = state.core.copy(error = state.foregroundRecoveryError))
        else -> state
    }
    Surface(
        modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        color = CodexColors.Graphite,
    ) {
        if (displayState.openCodexId != null) {
            BackHandler(onBack = if (displayState.conversationPage == ConversationPage.WORKSPACE) onShowConversation else onCloseConversation)
            ConversationScreen(
                displayState.core.codexes.find { it.id == displayState.openCodexId }?.title.orEmpty().ifBlank { "Codex 会话" },
                displayState, onDraftChanged, onSend, onStop, onCloseConversation,
                onShowConversation, onShowWorkspace, onListWorkspace, onOpenWorkspaceFile,
                onWorkspaceEditorChanged, onSaveWorkspaceFile, onCloseWorkspaceEditor,
                onChooseWorkspaceUpload, onChooseWorkspaceDownload,
                onRespondApproval, onToggleUserInputOption, onUserInputFreeFormChanged,
                onSubmitUserInput, onExportDiagnostics,
            )
        } else {
            HomeScreen(
                displayState, onHostAddressChanged, onConnect, onRefresh, onOpenAuth, onOpenConversation,
                onOpenProject, onRenameCodex, onUnmanageCodex, onForgetCodex,
                onExportDiagnostics,
            )
            if (displayState.projectDialogOpen) {
                ProjectDialog(
                    displayState, onCloseProject, onProjectPathChanged, onListDirectories,
                    onListSessionCandidates, onCreateCodex, onImportSession, onOpenConversation,
                )
            }
            if (displayState.missingDirectoryConfirmationPath.isNotBlank()) {
                AlertDialog(
                    onDismissRequest = onCancelCreateMissingDirectory,
                    modifier = Modifier.testTag("confirm-create-directory"),
                    title = { Text("创建目录？") },
                    text = {
                        Text("目录不存在：\n${displayState.missingDirectoryConfirmationPath}\n\n是否创建该目录并新建项目？")
                    },
                    confirmButton = {
                        TextButton(onConfirmCreateMissingDirectory, modifier = Modifier.testTag("confirm-create-directory-action")) {
                            Text("创建并继续")
                        }
                    },
                    dismissButton = {
                        TextButton(onCancelCreateMissingDirectory) { Text("取消") }
                    },
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
    onExportDiagnostics: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(42.dp), CircleShape, color = CodexColors.IndigoSoft) {
                Icon(Icons.Rounded.Terminal, null, Modifier.padding(9.dp), tint = CodexColors.Indigo)
            }
            Column {
                Text("Codex Remote", style = MaterialTheme.typography.headlineMedium)
                Text("Tailnet 开发工作台", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StatusCard(state.core)
        OutlinedTextField(
            state.hostAddress, onHostAddressChanged, Modifier.fillMaxWidth(),
            label = { Text("Host 地址") },
            leadingIcon = { Icon(Icons.Rounded.Dns, null) },
            supportingText = { Text(phaseDescription(state.core.phase)) },
            isError = state.core.error.isNotBlank(), singleLine = true,
        )
        if (state.core.phase == "error" && state.core.error.isNotBlank()) {
            Surface(
                Modifier.fillMaxWidth().testTag("connection-error-detail"),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                SelectionContainer {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "连接错误详情",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            state.core.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        Button(
            onConnect, Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = state.hostAddress.isNotBlank() && state.core.phase !in BusyPhases,
        ) {
            Icon(Icons.Rounded.Link, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.core.phase == "ready") "重新连接" else "连接")
        }
        OutlinedButton(
            onExportDiagnostics,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-diagnostics"),
        ) {
            Icon(Icons.Rounded.BugReport, null)
            Spacer(Modifier.width(8.dp))
            Text("导出诊断日志")
        }
        state.diagnosticMessage.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.testTag("diagnostic-message"),
                color = if (state.diagnosticFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        if (state.core.authUrl.isNotBlank()) {
            OutlinedButton({ onOpenAuth(state.core.authUrl) }, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Icon(Icons.Rounded.OpenInBrowser, null)
                Spacer(Modifier.width(8.dp))
                Text("打开 Tailscale 登录")
            }
        }
        FilledTonalButton(
            onOpenProject,
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open-project"),
            enabled = state.core.phase == "ready",
        ) {
            Icon(Icons.Rounded.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("打开项目")
        }
        CodexList(state.core, onRefresh, onOpenConversation, onRenameCodex, onUnmanageCodex, onForgetCodex)
    }
}

@Composable
private fun StatusCard(state: CoreState) {
    val ready = state.phase == "ready" || state.phase in ConversationPhases
    Surface(
        Modifier.fillMaxWidth().border(1.dp, CodexColors.Border, MaterialTheme.shapes.medium),
        color = CodexColors.Charcoal,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(38.dp), CircleShape, color = if (ready) Color(0xFF15372F) else CodexColors.Raised) {
                Icon(
                    if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                    null, Modifier.padding(9.dp), tint = if (ready) CodexColors.Green else CodexColors.TextMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tailnet", style = MaterialTheme.typography.titleMedium)
                Text(phaseDescription(state.phase), color = if (ready) CodexColors.Green else CodexColors.TextMuted)
                if (state.tailnetIPs.isNotEmpty()) Text(state.tailnetIPs.joinToString(), style = MaterialTheme.typography.bodySmall, color = CodexColors.TextMuted)
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
            Text("最近会话", style = MaterialTheme.typography.titleLarge)
            if (core.phase == "ready") IconButton(onRefresh, Modifier.semantics { contentDescription = "刷新会话" }) {
                Icon(Icons.Rounded.Refresh, "刷新")
            }
        }
        if (core.codexes.isEmpty()) {
            Surface(Modifier.fillMaxWidth().border(1.dp, CodexColors.Border, MaterialTheme.shapes.medium), color = CodexColors.Charcoal, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Forum, null, Modifier.size(32.dp), tint = CodexColors.TextMuted)
                    Spacer(Modifier.height(10.dp))
                    Text(if (core.phase == "ready") "Host 暂无 Codex" else "暂无 Codex 会话")
                    if (core.phase != "ready") Text("连接 Host 后将在这里显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else core.codexes.forEach { codex ->
            val title = codex.title.ifBlank { codex.id }
            var menuExpanded by remember(codex.id) { mutableStateOf(false) }
            var renameOpen by remember(codex.id) { mutableStateOf(false) }
            var renameTitle by remember(codex.id, codex.title) { mutableStateOf(title) }
            Surface(
                Modifier.fillMaxWidth().testTag("codex-item-${codex.id}")
                    .semantics { contentDescription = "打开会话：$title" }
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = core.phase == "ready") { onOpenConversation(codex.id) }
                    .border(1.dp, CodexColors.Border, MaterialTheme.shapes.medium),
                color = CodexColors.Charcoal,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(34.dp), CircleShape, color = CodexColors.IndigoSoft) {
                            Text("C", Modifier.wrapContentSize(Alignment.Center), color = CodexColors.Indigo, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            IconButton(
                                { menuExpanded = true },
                                Modifier.testTag("codex-menu-${codex.id}").semantics { contentDescription = "会话操作：$title" },
                            ) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                            DropdownMenu(menuExpanded, { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menuExpanded = false; renameOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("休眠") },
                                    leadingIcon = { Icon(Icons.Rounded.Bedtime, null) },
                                    onClick = { menuExpanded = false; onUnmanageCodex(codex.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("忘记记录") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                    onClick = { menuExpanded = false; onForgetCodex(codex.id) },
                                )
                            }
                        }
                    }
                    if (codex.cwd.isNotBlank()) Text(codex.cwd, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    codexStatusDescription(codex.managementState, codex.status).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                    }
                    codexUiProtocolNotices(codex).takeIf { it.isNotEmpty() }?.let {
                        ProtocolNoticeText(it, "codex-notice-${codex.id}")
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
    val candidates = visibleProjectSessionCandidates(state)
    var showingCandidates by rememberSaveable(state.projectDialogOpen) {
        mutableStateOf(candidates != null || state.pendingSessionCandidatesCommandId.isNotBlank())
    }
    val busy = state.pendingDirectoryCommandId.isNotBlank() ||
        state.pendingSessionCandidatesCommandId.isNotBlank() ||
        state.pendingProjectCommandId.isNotBlank()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val clearInputFocus = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val runProjectAction: (() -> Unit) -> Unit = { action ->
        clearInputFocus()
        action()
    }
    LaunchedEffect(candidates, state.pendingSessionCandidatesCommandId) {
        if (candidates != null || state.pendingSessionCandidatesCommandId.isNotBlank()) {
            showingCandidates = true
        }
    }
    val dismissOrHideInput = {
        when {
            imeVisible -> clearInputFocus()
            !busy -> onDismiss()
        }
    }
    LaunchedEffect(Unit) { clearInputFocus() }
    BackHandler(onBack = dismissOrHideInput)
    AlertDialog(
        onDismissRequest = dismissOrHideInput,
        modifier = Modifier.testTag("project-dialog").windowInsetsPadding(WindowInsets.ime),
        properties = DialogProperties(dismissOnBackPress = false),
        containerColor = CodexColors.Charcoal,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.FolderOpen, null, tint = CodexColors.Indigo)
                Text("打开项目")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 440.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    state.projectPath,
                    {
                        showingCandidates = false
                        onPathChanged(it)
                    },
                    Modifier.fillMaxWidth().testTag("project-path"),
                    label = { Text("当前路径") },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null) }, singleLine = true, enabled = !busy,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        {
                            parentProjectPath(state.projectPath)?.let { path ->
                                runProjectAction {
                                    showingCandidates = false
                                    onListDirectories(path)
                                }
                            }
                        },
                        enabled = !busy && parentProjectPath(state.projectPath) != null,
                    ) { Icon(Icons.Rounded.ArrowUpward, null); Spacer(Modifier.width(6.dp)); Text("上一级") }
                    OutlinedButton(
                        {
                            runProjectAction {
                                showingCandidates = false
                                onListDirectories(state.projectPath)
                            }
                        },
                        enabled = !busy && state.projectPath.isNotBlank(),
                    ) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(6.dp)); Text("浏览") }
                }
                Button(
                    {
                        runProjectAction {
                            showingCandidates = true
                            onListCandidates()
                        }
                    },
                    Modifier.fillMaxWidth().testTag("list-session-candidates"),
                    enabled = !busy && state.projectPath.isNotBlank(),
                ) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(8.dp)); Text("查看此目录下可导入会话") }
                Button(
                    { runProjectAction(onCreate) },
                    Modifier.fillMaxWidth().testTag("create-codex"),
                    enabled = !busy && state.projectPath.isNotBlank(),
                ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(if (core.phase == "creating_codex") "新建中…" else "新建此项目") }
                HorizontalDivider()
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).testTag("project-results"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showingCandidates) {
                        item {
                            TextButton(
                                {
                                    runProjectAction { showingCandidates = false }
                                },
                                Modifier.fillMaxWidth().testTag("return-directory-browser"),
                                enabled = !busy,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                                Spacer(Modifier.width(8.dp))
                                Text("返回目录浏览", Modifier.fillMaxWidth())
                            }
                        }
                        if (core.phase == "loading_session_candidates") {
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        if (core.phase != "loading_session_candidates" && candidates != null && candidates.sessions.isEmpty()) {
                            item {
                                Text(
                                    "此目录下没有可导入的会话",
                                    Modifier.fillMaxWidth().padding(vertical = 18.dp).testTag("empty-session-candidates"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        itemsIndexed(
                            candidates?.sessions.orEmpty(),
                            key = { _, candidate -> candidate.sessionId },
                        ) { _, candidate ->
                            SessionCandidateRow(
                                candidate,
                                busy,
                                { sessionId, source -> runProjectAction { onImport(sessionId, source) } },
                                { codexId -> runProjectAction { onOpenManaged(codexId) } },
                            )
                        }
                    } else {
                        if (core.phase == "loading_directories") {
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        itemsIndexed(
                            core.directoryListing?.directories.orEmpty(),
                            key = { _, directory -> directory.path },
                        ) { _, directory ->
                            TextButton(
                                {
                                    runProjectAction {
                                        showingCandidates = false
                                        onListDirectories(directory.path)
                                    }
                                },
                                Modifier.fillMaxWidth().testTag("project-directory-${directory.name}"),
                                enabled = !busy,
                            ) {
                                Icon(Icons.Rounded.Folder, null, tint = CodexColors.Indigo)
                                Spacer(Modifier.width(10.dp))
                                Text(directory.name.ifBlank { directory.path }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    state.projectError.takeIf { it.isNotBlank() }?.let { error ->
                        item {
                            Text(error, Modifier.testTag("project-error"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton({ runProjectAction(onDismiss) }, enabled = !busy) { Text("关闭") }
        },
    )
}

@Composable
private fun SessionCandidateRow(
    candidate: SessionCandidate,
    busy: Boolean,
    onImport: (String, String) -> Unit,
    onOpenManaged: (String) -> Unit,
) {
    val resumable = isResumableSessionAvailability(candidate.availability)
    val managed = isManagedSessionAvailability(candidate.availability) || candidate.managedCodexId.isNotBlank()
    val action = when {
        managed -> { -> if (candidate.managedCodexId.isNotBlank()) onOpenManaged(candidate.managedCodexId) }
        resumable -> { -> onImport(candidate.sessionId, candidate.source) }
        else -> null
    }
    Surface(
        Modifier.fillMaxWidth().border(1.dp, CodexColors.Border, MaterialTheme.shapes.small)
            .then(if (action != null && !busy) Modifier.clickable(onClick = action) else Modifier),
        color = CodexColors.Raised,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(candidate.title.ifBlank { candidate.sessionId.ifBlank { "未命名会话" } }, fontWeight = FontWeight.Medium)
            if (candidate.preview.isNotBlank()) Text(candidate.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(sessionAvailabilityDescription(candidate.availability, managed), style = MaterialTheme.typography.labelMedium)
            candidate.protocolNotices().takeIf { it.isNotEmpty() }?.let {
                ProtocolNoticeText(it, "session-notice-${candidate.sessionId}")
            }
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
    onExportDiagnostics: () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val core = state.core
    val conversation = activeConversation(state)
    Column(
        Modifier.fillMaxSize().imePadding().testTag("conversation-root")
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
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 74.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.conversationPage == ConversationPage.CONVERSATION) {
                IconButton(
                    onBack, Modifier.testTag("conversation-back").semantics { contentDescription = "返回会话列表" },
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话列表") }
            } else {
                IconButton(
                    onShowConversation,
                    Modifier.testTag("workspace-back").semantics { contentDescription = "返回会话" },
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话") }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val statusColor = when {
                        state.foregroundRecoveryInProgress -> CodexColors.Indigo
                        core.error.isBlank() -> CodexColors.Green
                        else -> CodexColors.Error
                    }
                    Surface(Modifier.size(7.dp), CircleShape, color = statusColor) {}
                    Text(
                        when {
                            state.foregroundRecoveryInProgress -> "正在恢复连接"
                            core.error.isBlank() -> "已连接"
                            else -> "连接异常"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            }
            IconButton(
                onExportDiagnostics,
                Modifier.testTag("export-diagnostics").semantics { contentDescription = "导出诊断日志" },
            ) { Icon(Icons.Rounded.BugReport, "导出诊断日志") }
        }
        state.diagnosticMessage.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("diagnostic-message"),
                color = if (state.diagnosticFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            ConversationTab("会话", state.conversationPage == ConversationPage.CONVERSATION, Modifier.weight(1f).testTag("show-conversation"), onShowConversation)
            ConversationTab("项目文件", state.conversationPage == ConversationPage.WORKSPACE, Modifier.weight(1f).testTag("show-workspace"), onShowWorkspace)
        }
        HorizontalDivider(color = CodexColors.Border)
        Row(
            Modifier.fillMaxWidth().height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (state.conversationPage == ConversationPage.CONVERSATION) 32.dp else 18.dp).height(4.dp).clip(CircleShape).background(if (state.conversationPage == ConversationPage.CONVERSATION) CodexColors.Indigo else CodexColors.Border))
            Box(Modifier.width(if (state.conversationPage == ConversationPage.WORKSPACE) 32.dp else 18.dp).height(4.dp).clip(CircleShape).background(if (state.conversationPage == ConversationPage.WORKSPACE) CodexColors.Indigo else CodexColors.Border))
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
                ConversationHistory(core, state.openCodexId, Modifier.weight(1f).fillMaxWidth())
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
                Surface(
                    Modifier.fillMaxWidth().testTag("conversation-composer")
                        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = CodexColors.Composer,
                    border = BorderStroke(1.dp, CodexColors.Border),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    BasicTextField(
                        value = state.draft,
                        onValueChange = onDraftChanged,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp)
                            .testTag("conversation-input").semantics { contentDescription = "消息输入框" },
                        enabled = conversation?.running != true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = CodexColors.Text),
                        cursorBrush = SolidColor(CodexColors.Indigo),
                        minLines = 1,
                        maxLines = 5,
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                                if (state.draft.isBlank()) Text("输入消息", color = CodexColors.TextMuted)
                                inner()
                            }
                        },
                    )
                    if (conversation?.running == true) {
                        FilledIconButton(
                            onStop, Modifier.size(50.dp).testTag("conversation-stop").semantics { contentDescription = "停止任务" },
                            enabled = !state.stoppingTurn,
                        ) { Icon(Icons.Rounded.Stop, if (state.stoppingTurn) "停止中" else "停止") }
                    } else {
                        FilledIconButton(
                            onSend, Modifier.size(50.dp).testTag("conversation-send").semantics { contentDescription = "发送消息" },
                            enabled = state.draft.isNotBlank() && conversation != null && core.phase !in BusyPhases,
                        ) { Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
                    }
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
    Surface(
        Modifier.fillMaxWidth().testTag("approval-${request.requestId}")
            .border(1.dp, CodexColors.Amber.copy(alpha = .55f), MaterialTheme.shapes.medium),
        color = CodexColors.Charcoal,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AdminPanelSettings, null, tint = CodexColors.Amber)
                Text(request.title.ifBlank { "需要批准" }, fontWeight = FontWeight.SemiBold)
            }
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
    Surface(
        Modifier.fillMaxWidth().testTag("user-input-${request.requestId}")
            .border(1.dp, CodexColors.Cyan.copy(alpha = .5f), MaterialTheme.shapes.medium),
        color = CodexColors.Charcoal,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.QuestionAnswer, null, tint = CodexColors.Cyan)
                Text("Codex 需要你的回答", fontWeight = FontWeight.SemiBold)
            }
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
        modifier.background(CodexColors.Graphite).padding(horizontal = 16.dp, vertical = 12.dp).testTag("project-files-page")
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
                    ) { Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("上传文件") }
                    OutlinedButton(
                        { onChooseUpload("zip_directory") },
                        enabled = uploadEnabled,
                        modifier = Modifier.testTag("workspace-upload-zip"),
                    ) { Icon(Icons.Rounded.FolderZip, null); Spacer(Modifier.width(6.dp)); Text("上传 ZIP") }
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
                    ) { Icon(Icons.Rounded.ArrowUpward, null); Spacer(Modifier.width(6.dp)); Text("上一级") }
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
    Surface(
        Modifier.fillMaxWidth().border(1.dp, CodexColors.Border, MaterialTheme.shapes.small),
        color = CodexColors.Charcoal,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { if (directory) onListWorkspace(entry.relativePath) else onOpenFile(entry) },
                enabled = idle && openable,
                modifier = Modifier.weight(1f).testTag("workspace-entry-${entry.relativePath}"),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (directory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = if (directory) CodexColors.Indigo else CodexColors.TextMuted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.fillMaxWidth()) {
                    Text(entry.name.ifBlank { entry.relativePath })
                    Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(
                { onChooseDownload(entry) },
                enabled = idle && !localTransferBusy && canStartDownload(workspace) && entry.kind in setOf("regular_file", "directory"),
                modifier = Modifier.testTag("workspace-download-${entry.relativePath}"),
            ) { Icon(Icons.Rounded.Download, "下载") }
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
    val editorScrollState = rememberScrollState()
    val lineNumbers = remember(state.workspaceEditorText) {
        state.workspaceEditorText.lines().indices.joinToString("\n") { (it + 1).toString() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("workspace-editor-dialog"),
        containerColor = CodexColors.Charcoal,
        title = { Text(entry.name.ifBlank { entry.relativePath }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().weight(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 12.dp).verticalScroll(editorScrollState)
                        .testTag("workspace-editor-scroll"),
                ) {
                    Text(
                        lineNumbers,
                        Modifier.padding(top = 12.dp, end = 8.dp).testTag("workspace-line-numbers"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = state.workspaceEditorText,
                        onValueChange = onEditorChanged,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("workspace-editor"),
                        enabled = entry.textEditable,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
internal fun ConversationHistory(core: CoreState, openCodexId: String?, modifier: Modifier = Modifier) {
    val conversation = core.conversation?.takeIf { it.codexId == openCodexId }
    val listState = rememberLazyListState()
    Box(
        modifier.testTag("conversation-history").semantics { contentDescription = "会话历史" },
    ) {
        when {
            openCodexId != null && conversation == null ->
                Text("正在加载历史记录…", Modifier.align(Alignment.Center))
            conversation?.timelineEntries.isNullOrEmpty() ->
                Text("还没有消息", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val turnsById = conversation!!.turns.associateBy { it.turnId }
                val displayEntries = groupTimelineEntries(conversation.timelineEntries)
                val importedAtUnixMs = core.codexes.firstOrNull { it.id == conversation.codexId }
                    ?.importedAtUnixMs ?: 0
                val newestEntryIndex = conversation.timelineEntries.lastIndex
                val newestEntryKey = conversation.timelineEntries.last().scrollAnchorKey(newestEntryIndex)
                LaunchedEffect(conversation.codexId, newestEntryKey, conversation.timelineEntries.size) {
                    listState.scrollToItem(0)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                ) {
                    itemsIndexed(
                        displayEntries.asReversed(),
                        key = { index, entry ->
                            when (entry) {
                                is TimelineDisplayEntry.Message -> entry.key
                                is TimelineDisplayEntry.ProcessGroup -> entry.key
                                is TimelineDisplayEntry.TurnFailure -> "failure-${entry.turnId}-$index"
                            }
                        },
                    ) { index, entry ->
                        val chronologicalIndex = displayEntries.lastIndex - index
                        val entryTurnId = entry.timelineTurnId()
                        val nextTurnId = displayEntries.getOrNull(chronologicalIndex + 1)?.timelineTurnId()
                        val connectBelow = entryTurnId.isNotBlank() && entryTurnId == nextTurnId
                        val previousTurnId = displayEntries.getOrNull(chronologicalIndex - 1)?.timelineTurnId()
                        val turn = turnsById[entryTurnId]
                        val notices = timelineUiProtocolNotices(
                            entry,
                            turn,
                            entryTurnId != previousTurnId,
                            importedAtUnixMs,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (notices.isNotEmpty()) ProtocolNoticeText(notices, "timeline-notice-${entry.key}")
                            when (entry) {
                                is TimelineDisplayEntry.Message -> {
                                    val timestamp = when (entry.item.type) {
                                        "agent_message" -> turn?.completedAtUnixMs?.takeIf { it > 0 }
                                            ?: turn?.startedAtUnixMs
                                        else -> turn?.startedAtUnixMs
                                    }?.takeIf { it > 0 }?.let(::formatTimelineTime).orEmpty()
                                    TimelineItem(entry.item, timestamp, connectBelow)
                                }
                                is TimelineDisplayEntry.ProcessGroup -> {
                                    val timestamp = turn?.startedAtUnixMs?.takeIf { it > 0 }
                                        ?.let(::formatTimelineTime).orEmpty()
                                    ProcessGroupCard(entry.items, timestamp, connectBelow)
                                }
                                is TimelineDisplayEntry.TurnFailure -> TurnFailureCard(entry.failure)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ConversationTimelineEntry.scrollAnchorKey(index: Int): String = when (this) {
    is ConversationTimelineEntry.Item -> item.itemId.ifBlank { "${item.turnId}-${item.type}-$index" }
    is ConversationTimelineEntry.TurnFailure -> "failure-$turnId-$index"
}

private fun TimelineDisplayEntry.timelineTurnId(): String = when (this) {
    is TimelineDisplayEntry.Message -> item.turnId
    is TimelineDisplayEntry.ProcessGroup -> turnId
    is TimelineDisplayEntry.TurnFailure -> turnId
}

@Composable
private fun ProtocolNoticeText(notices: List<String>, tag: String) {
    Text(
        notices.joinToString(" · "),
        Modifier.testTag(tag),
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelSmall,
    )
}

internal fun codexUiProtocolNotices(codex: CodexSummary): List<String> {
    val sleeping = codexStatusDescription(codex.managementState, codex.status) == "休眠"
    return codex.protocolNotices().filterNot { sleeping && it == "此会话即将休眠" }
}

internal fun timelineUiProtocolNotices(
    entry: TimelineDisplayEntry,
    turn: ConversationTurn?,
    firstEntryInTurn: Boolean,
    importedAtUnixMs: Long,
): List<String> {
    val turnNotices = if (firstEntryInTurn && turn != null) buildList {
        turn.completeness?.chineseNotice()?.let(::add)
        timelineTurnProvenanceNotice(turn, importedAtUnixMs)?.let(::add)
    } else emptyList()
    val itemNotices = when (entry) {
        is TimelineDisplayEntry.Message -> entry.item.protocolNotices()
        is TimelineDisplayEntry.ProcessGroup -> entry.items.flatMap { it.protocolNotices() }
        is TimelineDisplayEntry.TurnFailure -> emptyList()
    }.filterNot { notice ->
        (entry is TimelineDisplayEntry.Message && notice.startsWith("内容")) ||
            notice == "来自导入的历史记录" || notice == "由 Host 重建"
    }
    return (turnNotices + itemNotices).distinct()
}

internal fun timelineTurnProvenanceNotice(turn: ConversationTurn, importedAtUnixMs: Long): String? {
    val provenances = buildList {
        add(turn.provenance)
        turn.items.mapTo(this) { it.provenance }
    }
    if (provenances.any { it.equals("PROVENANCE_KIND_LIVE_WIRE", ignoreCase = true) }) return null
    val importedHistory = provenances.any {
        it.equals("PROVENANCE_KIND_IMPORTED_HISTORY", ignoreCase = true)
    }
    if (
        importedHistory && importedAtUnixMs > 0 && turn.startedAtUnixMs > 0 &&
        turn.startedAtUnixMs < importedAtUnixMs
    ) {
        return "此轮来自导入的历史记录"
    }
    if (provenances.any { it.equals("PROVENANCE_KIND_HOST_SYNTHESIZED", ignoreCase = true) }) {
        return "此轮由 Host 重建"
    }
    return null
}

internal sealed interface TimelineDisplayEntry {
    val key: String

    data class Message(val item: ConversationItem, override val key: String) : TimelineDisplayEntry
    data class ProcessGroup(
        val turnId: String,
        val items: List<ConversationItem>,
        override val key: String,
    ) : TimelineDisplayEntry
    data class TurnFailure(val turnId: String, val failure: String, override val key: String) : TimelineDisplayEntry
}

internal fun groupTimelineEntries(entries: List<ConversationTimelineEntry>): List<TimelineDisplayEntry> = buildList {
    var groupTurnId = ""
    var groupStartIndex = -1
    val groupItems = mutableListOf<ConversationItem>()

    fun flushGroup() {
        if (groupItems.isEmpty()) return
        val first = groupItems.first()
        val last = groupItems.last()
        add(
            TimelineDisplayEntry.ProcessGroup(
                turnId = groupTurnId,
                items = groupItems.toList(),
                key = "process-${groupTurnId.ifBlank { "no-turn" }}-$groupStartIndex-${first.itemId}-${last.itemId}",
            ),
        )
        groupItems.clear()
        groupTurnId = ""
        groupStartIndex = -1
    }

    entries.forEachIndexed { index, entry ->
        when (entry) {
            is ConversationTimelineEntry.Item -> {
                val item = entry.item
                val message = item.type == "user_message" || item.type == "agent_message"
                if (message) {
                    flushGroup()
                    add(
                        TimelineDisplayEntry.Message(
                            item,
                            item.itemId.ifBlank { "message-$index-${item.type}-${item.hashCode()}" },
                        ),
                    )
                } else if (item.turnId.isBlank()) {
                    flushGroup()
                    add(
                        TimelineDisplayEntry.ProcessGroup(
                            turnId = "",
                            items = listOf(item),
                            key = "process-no-turn-$index-${item.itemId.ifBlank { item.hashCode().toString() }}",
                        ),
                    )
                } else {
                    if (groupItems.isNotEmpty() && groupTurnId != item.turnId) flushGroup()
                    if (groupItems.isEmpty()) {
                        groupTurnId = item.turnId
                        groupStartIndex = index
                    }
                    groupItems += item
                }
            }
            is ConversationTimelineEntry.TurnFailure -> {
                flushGroup()
                add(TimelineDisplayEntry.TurnFailure(entry.turnId, entry.failure, "failure-${entry.turnId}-$index"))
            }
        }
    }
    flushGroup()
}

@Composable
internal fun TimelineItem(item: ConversationItem, timestamp: String = "", connectBelow: Boolean = false) {
    when (item.type) {
        "user_message" -> MessageBubble("你", item.userMessage?.text.orEmpty(), true, item, timestamp, connectBelow)
        "agent_message" -> MessageBubble("Codex", item.agentMessage?.text.orEmpty(), false, item, timestamp, false)
        else -> ProcessGroupCard(listOf(item), timestamp, connectBelow)
    }
}

@Composable
private fun MessageBubble(label: String, text: String, user: Boolean, item: ConversationItem, timestamp: String, connectBelow: Boolean) {
    TimelineRow(
        connectBelow = connectBelow,
        leading = {
            TimelineAvatar(if (user) "你" else "C", if (user) CodexColors.IndigoSoft else Color(0xFF392A6E), if (user) Color(0xFFBBC3FF) else Color(0xFFD6C7FF))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.status == "running") Text("生成中…", style = MaterialTheme.typography.labelSmall, color = CodexColors.Cyan)
                    if (item.status == "failed") Text("失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    if (timestamp.isNotBlank()) Text(timestamp, style = MaterialTheme.typography.labelSmall, color = CodexColors.TextMuted)
                }
            }
            if (user) {
                Text(text.ifBlank { "（空消息）" }, style = MaterialTheme.typography.bodyLarge, color = CodexColors.Text)
            } else {
                MarkdownBody(text, "（空消息）", MaterialTheme.typography.bodyLarge)
            }
            CompletenessNotice(item.completeness)
        }
    }
}

@Composable
private fun TimelineRow(
    connectBelow: Boolean,
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val connectorColor = CodexColors.Border
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds().drawBehind {
            if (connectBelow) {
                val x = 18.dp.toPx()
                drawLine(connectorColor, Offset(x, 18.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            }
        },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(36.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter)) { leading() }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(bottom = 18.dp)) { content() }
    }
}

@Composable
private fun TimelineAvatar(text: String, background: Color, foreground: Color) {
    Surface(Modifier.size(36.dp), CircleShape, color = background) {
        Text(text, Modifier.wrapContentSize(Alignment.Center), color = foreground, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProcessGroupCard(items: List<ConversationItem>, timestamp: String, connectBelow: Boolean) {
    if (items.isEmpty()) return
    TimelineRow(
        connectBelow = connectBelow,
        leading = {
            Surface(Modifier.size(36.dp), CircleShape, color = CodexColors.Raised) {
                Icon(Icons.Outlined.Terminal, null, Modifier.padding(8.dp), tint = CodexColors.TextMuted)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Codex 正在处理你的请求", style = MaterialTheme.typography.titleMedium)
                if (timestamp.isNotBlank()) Text(timestamp, style = MaterialTheme.typography.labelSmall, color = CodexColors.TextMuted)
            }
        Surface(
            Modifier.fillMaxWidth().border(1.dp, CodexColors.Border, MaterialTheme.shapes.small),
            color = CodexColors.Charcoal,
            shape = MaterialTheme.shapes.small,
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    ProcessGroupRow(item, "")
                    if (index != items.lastIndex) HorizontalDivider(color = CodexColors.Border)
                }
            }
        }
        }
    }
}

@Composable
private fun ProcessGroupRow(item: ConversationItem, timestamp: String) {
    val forceOpen = item.status == "running" || item.status == "failed"
    var manuallyExpanded by rememberSaveable(item.itemId, item.status) { mutableStateOf(false) }
    val expanded = forceOpen || manuallyExpanded
    val title = processTitle(item.type)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(processIcon(item.type), null, Modifier.size(20.dp), tint = statusColor(item.status))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    ProcessSummary(item)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = CodexColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    listOf(statusDescription(item.status), timestamp).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(item.status),
                )
                IconButton(
                    { if (!forceOpen) manuallyExpanded = !manuallyExpanded },
                    Modifier.size(48.dp).semantics { contentDescription = if (expanded) "收起$title" else "展开$title" },
                ) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (item.type) {
                        "reasoning_summary" -> MarkdownBody(item.reasoningSummary?.text.orEmpty(), "暂无思考摘要")
                        "command" -> CommandBody(item.command)
                        "tool" -> ToolBody(item.tool)
                        "plan" -> PlanBody(item)
                        "file_change" -> FileChangeBody(item)
                        else -> Text("暂不支持的过程记录", style = MaterialTheme.typography.bodySmall)
                    }
                    CompletenessNotice(item.completeness)
                }
            }
    }
}

private fun processTitle(type: String): String = when (type) {
    "reasoning_summary" -> "思考过程"
    "command" -> "命令"
    "tool" -> "工具调用"
    "plan" -> "计划"
    "file_change" -> "文件变更"
    else -> "过程记录"
}

private fun processIcon(type: String): ImageVector = when (type) {
    "reasoning_summary" -> Icons.Outlined.Search
    "command" -> Icons.Outlined.Terminal
    "tool" -> Icons.Outlined.Build
    "plan" -> Icons.Outlined.Checklist
    "file_change" -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.Notes
}

private fun ProcessSummary(item: ConversationItem): String? = when (item.type) {
    "command" -> item.command?.argv?.joinToString(" ")?.ifBlank { "命令内容为空" }
    "tool" -> item.tool?.name?.ifBlank { "未命名工具" }
    "reasoning_summary" -> if (item.status == "running") "正在思考" else null
    "plan" -> item.plan?.steps?.let { "${it.size} 个步骤" }
    "file_change" -> item.fileChange?.changes?.let { changes ->
        when (changes.size) {
            0 -> "暂无文件摘要"
            1 -> {
                val change = changes.first()
                "${fileKindDescription(change.kind)} ${change.path.ifBlank { change.newPath.ifBlank { change.oldPath } }}"
            }
            else -> "修改 ${changes.size} 个文件"
        }
    }
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
        Surface(color = CodexColors.Raised, shape = MaterialTheme.shapes.extraSmall) {
            Text(
                text.ifBlank { emptyText },
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PlanBody(item: ConversationItem) {
            val steps = item.plan?.steps.orEmpty()
            if (steps.isEmpty()) Text("暂无步骤", style = MaterialTheme.typography.bodySmall)
            steps.forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(planStepIcon(step.status), null, Modifier.size(18.dp), tint = statusColor(step.status))
                    Text(step.text.ifBlank { "未命名步骤" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(planStepDescription(step.status), style = MaterialTheme.typography.labelSmall)
                }
            }
}

private fun planStepIcon(status: String): ImageVector = when (status) {
    "completed" -> Icons.Rounded.CheckCircle
    "running", "in_progress" -> Icons.Rounded.PlayCircle
    "failed" -> Icons.Rounded.Cancel
    else -> Icons.Rounded.RadioButtonUnchecked
}

@Composable
private fun FileChangeBody(item: ConversationItem) {
    var diffExpanded by rememberSaveable(item.itemId) { mutableStateOf(false) }
    val fileChange = item.fileChange
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
}

@Composable
private fun TurnFailureCard(failure: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
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
    "completed" -> CodexColors.Green
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusDescription(status: String) = when (status) {
    "running" -> "进行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    else -> ""
}

private fun planStepDescription(status: String) = when (status) {
    "completed" -> "完成"
    "running", "in_progress" -> "进行中"
    "failed" -> "失败"
    else -> "待处理"
}

private fun formatTimelineTime(unixMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixMs))

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
private val BusyPhases = setOf("starting_tailnet", "auth_required", "connecting_host", "refreshing", "recovering_foreground") +
    ConversationPhases + ProjectBusyPhases + setOf("renaming_codex", "unmanaging_codex", "forgetting_codex")

private fun phaseDescription(phase: String) = when (phase) {
    "idle", "stopped" -> "等待启动"
    "configured" -> "配置完成"
    "starting_tailnet" -> "正在启动 Tailnet"
    "auth_required" -> "需要登录 Tailscale"
    "connecting_host" -> "正在连接 Host"
    "ready" -> "已连接"
    "refreshing" -> "正在刷新"
    "recovering_foreground" -> "正在恢复连接"
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

internal fun isResumableSessionAvailability(availability: String): Boolean =
    availability.uppercase() in setOf("RESUMABLE", "SESSION_AVAILABILITY_RESUMABLE")

internal fun isManagedSessionAvailability(availability: String): Boolean =
    availability.uppercase() in setOf("ALREADY_MANAGED", "SESSION_AVAILABILITY_ALREADY_MANAGED")

internal fun visibleProjectSessionCandidates(state: AppUiState): SessionCandidates? =
    state.projectSessionCandidates?.takeIf { candidates ->
        candidates.normalizedCwd.trim().trimEnd('/') == state.projectPath.trim().trimEnd('/')
    }

internal fun sessionAvailabilityDescription(availability: String, managed: Boolean = false) = when {
    managed || isManagedSessionAvailability(availability) -> "已管理，点击打开"
    isResumableSessionAvailability(availability) -> "可继续，点击导入"
    availability.isBlank() -> "状态未知，暂不可导入"
    else -> when (availability.uppercase().removePrefix("SESSION_AVAILABILITY_")) {
        "POSSIBLY_LIVE_ELSEWHERE" -> "可能正在其他客户端使用，暂不可导入"
        "NOT_RESUMABLE" -> "无法继续此会话"
        else -> "未知状态，暂不可导入"
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
    CodexRemoteTheme {
        CodexRemoteScreen(
            state = AppUiState(),
            onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
            onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = {}, onSend = {}, onStop = {},
        )
    }
}
