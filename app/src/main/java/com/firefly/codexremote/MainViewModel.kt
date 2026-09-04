package com.firefly.codexremote

import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firefly.codexremote.mobilecore.Core
import com.firefly.codexremote.mobilecore.Mobilecore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class AppUiState(
    val hostAddress: String = DefaultHostAddress,
    val core: CoreState = CoreState(),
    val openCodexId: String? = null,
    val draft: String = "",
    val optimisticUserMessages: List<OptimisticUserMessage> = emptyList(),
    val stoppingTurn: Boolean = false,
    val lastProjectPath: String = "",
    val projectDialogOpen: Boolean = false,
    val projectPath: String = "",
    val pendingDirectoryCommandId: String = "",
    val pendingSessionCandidatesCommandId: String = "",
    val requestedSessionCandidatesPath: String = "",
    val projectSessionCandidates: SessionCandidates? = null,
    val pendingProjectCommandId: String = "",
    val pendingProjectCommandStage: String = "",
    val pendingProjectAction: String = "",
    val pendingProjectCodexId: String = "",
    val missingDirectoryConfirmationPath: String = "",
    val projectError: String = "",
    val conversationPage: ConversationPage = ConversationPage.CONVERSATION,
    val workspaceEditorOpen: Boolean = false,
    val workspaceEditorText: String = "",
    val pendingWorkspaceGetCommandId: String = "",
    val pendingWorkspaceRefreshDirectory: String = "",
    val pendingWorkspaceReadCommandId: String = "",
    val pendingWorkspaceFilePath: String = "",
    val pendingWorkspaceUploadCommandId: String = "",
    val pendingWorkspaceUploadPath: String = "",
    val pendingWorkspaceDownloadCommandId: String = "",
    val pendingWorkspaceDownloadPath: String = "",
    val workspaceDownloadReady: WorkspaceDownloadReady? = null,
    val workspaceTransferError: String = "",
    val workspaceTransferNotice: String = "",
    val workspaceLocalTransferStatus: String = "none",
    val workspaceTransferCodexId: String = "",
    val workspaceTransferDirectory: String = "",
    val workspaceTransferPath: String = "",
    val workspaceTransferEntryKind: String = "",
    val workspaceTransferUploadKind: String = "",
    val workspaceUploadRecoveryPartial: Boolean = false,
    val userInputDrafts: Map<String, Map<String, UserInputAnswerDraft>> = emptyMap(),
    val submittingRequestIds: Set<String> = emptySet(),
    val diagnosticMessage: String = "",
    val diagnosticFailed: Boolean = false,
    val foregroundRecoveryInProgress: Boolean = false,
    val foregroundRecoveryError: String = "",
)

data class OptimisticUserMessage(
    val commandId: String,
    val codexId: String,
    val text: String,
    val acceptedTurnId: String = "",
    val createdAtUnixMs: Long = 0,
)

data class WorkspaceDownloadReady(
    val commandId: String,
    val relativePath: String,
    val result: WorkspaceDownloadResult,
)

data class UserInputAnswerDraft(
    val selectedOptionIds: Set<String> = emptySet(),
    val freeFormText: String = "",
)

enum class ConversationPage { CONVERSATION, WORKSPACE }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val hostPreferences = HostPreferences(application)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val diagnosticLog = AppDiagnosticLog(File(application.filesDir, "diagnostics/app.log"))
    private val coreStateDiagnosticRecorder = CoreStateDiagnosticRecorder(diagnosticLog)

    private val core: Core = Mobilecore.newCore(
        AndroidPlatformAdapter(application, ::acceptCoreState),
    )
    // Core publishes every dispatch through its serialized notifier. Consuming the synchronous
    // return here as a second channel can overtake an already queued operation completion.
    private val networkMonitor = AndroidNetworkMonitor(application) { command ->
        core.dispatch(command)
    }
    private val clientId = "android-" + (
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        )
    private val clientVersion = effectiveClientVersion(
        runCatching {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).versionName
        }.getOrNull(),
    )
    private val coreErrorLogger = CoreErrorLogDeduplicator()
    private val foregroundResumeGate = ForegroundResumeGate()
    private val foregroundRecoveryTracker = ForegroundRecoveryTracker()
    private val projectOperationSingleFlight = ProjectOperationSingleFlight()
    private val diagnosticActionTracker = DiagnosticActionTracker()
    private val codexDrafts = ConcurrentHashMap<String, String>()
    private val codexDraftVersions = ConcurrentHashMap<String, Long>()
    private val attemptedPersistedSelections = ConcurrentHashMap.newKeySet<String>()
    private val forgetPersistenceTracker = ForgetPersistenceTracker()
    private val sendDraftTracker = SendDraftTracker()
    private val codexPreferenceWrites = Channel<CodexPreferenceWrite>(Channel.UNLIMITED)
    private val latestDraftWrite = LatestCodexDraftWrite()
    private var draftPersistenceJob: Job? = null
    @Volatile private var persistedSelectedCodexId: String? = null
    @Volatile private var codexStateLoaded = false

    init {
        diagnosticLog.append("app.started", "version=$clientVersion")
        viewModelScope.launch {
            for (write in codexPreferenceWrites) {
                when (write) {
                    is CodexPreferenceWrite.Draft -> hostPreferences.setCodexDraft(write.codexId, write.draft)
                    is CodexPreferenceWrite.Selected -> hostPreferences.setSelectedCodexId(write.codexId)
                    is CodexPreferenceWrite.Forget -> hostPreferences.forgetCodex(write.codexId)
                }
            }
        }
        viewModelScope.launch {
            hostPreferences.hostAddress.collect { saved ->
                _uiState.update { it.copy(hostAddress = saved) }
            }
        }
        viewModelScope.launch {
            hostPreferences.lastProjectPath.collect { saved ->
                _uiState.update { it.copy(lastProjectPath = saved) }
            }
        }
        viewModelScope.launch {
            val saved = hostPreferences.codexState.first()
            saved.drafts.forEach { (codexId, draft) -> codexDrafts.putIfAbsent(codexId, draft) }
            val currentOpenCodexId = uiState.value.openCodexId
            persistedSelectedCodexId = currentOpenCodexId ?: saved.selectedCodexId.takeIf { it.isNotBlank() }
            codexStateLoaded = true
            _uiState.update { current ->
                val openCodexId = current.openCodexId
                if (openCodexId == null || current.draft.isNotEmpty()) current
                else current.copy(draft = draftForCodex(codexDrafts, openCodexId))
            }
            val forgottenCodexId = authoritativeForgottenCodex(
                uiState.value,
                persistedSelectedCodexId,
                uiState.value.core,
            )
            if (forgottenCodexId != null) clearForgottenCodex(forgottenCodexId)
            else restorePersistedConversationIfReady()
        }
        acceptCoreState(core.state())
        networkMonitor.start()
    }

    fun setHostAddress(value: String) {
        _uiState.update { it.copy(hostAddress = value) }
        viewModelScope.launch { hostPreferences.setHostAddress(value) }
    }

    fun connect() = connectInternal(foregroundRecovery = false)

    fun onActivityStarted(nowElapsedRealtimeMs: Long) {
        if (!foregroundResumeGate.onStarted(nowElapsedRealtimeMs)) return
        when (foregroundRecoveryAction(uiState.value)) {
            ForegroundRecoveryAction.REFRESH -> refreshForegroundConnection()
            ForegroundRecoveryAction.CONNECT -> connectInternal(foregroundRecovery = true)
            ForegroundRecoveryAction.NONE -> Unit
        }
    }

    fun onActivityStopped(nowElapsedRealtimeMs: Long, changingConfigurations: Boolean) {
        foregroundResumeGate.onStopped(nowElapsedRealtimeMs, changingConfigurations)
    }

    private fun connectInternal(foregroundRecovery: Boolean, continueForegroundRecovery: Boolean = false) {
        val startingState = uiState.value
        val endpoint = startingState.hostAddress.trim()
        if (endpoint.isEmpty()) return
        if (foregroundRecovery) {
            _uiState.update(::foregroundRecoveryStartingState)
        } else {
            _uiState.update { it.copy(foregroundRecoveryError = "") }
        }
        diagnosticLog.append("core.command", "type=connect")
        viewModelScope.launch(Dispatchers.IO) {
            hostPreferences.setHostAddress(endpoint)
            val payload = JSONObject()
                .put("hostname", "codex-remote-android")
                .put("stateDir", File(getApplication<Application>().filesDir, "tailnet").absolutePath)
                .put("hostEndpoint", endpoint)
                .put("clientId", clientId)
                .put("clientRunId", UUID.randomUUID().toString())
                .put("clientName", "codex-remote-android")
                .put("clientVersion", clientVersion)
            val commands = connectCommands(payload)
            if (foregroundRecovery) {
                val startCommandId = commands.last().getString("id")
                if (continueForegroundRecovery) {
                    foregroundRecoveryTracker.trackReconnect(startCommandId)
                } else {
                    foregroundRecoveryTracker.beginReconnect(
                        recoveryCommandId = startCommandId,
                        originalCodexId = startingState.openCodexId?.takeIf { it.isNotBlank() },
                    )
                }
            }
            commands.forEach { command ->
                dispatchTracked(command, "connection")
            }
        }
    }

    private fun refreshForegroundConnection() {
        val startingState = uiState.value
        val command = coreCommand("refresh")
        foregroundRecoveryTracker.beginRefresh(
            recoveryCommandId = command.getString("id"),
            originalCodexId = startingState.openCodexId?.takeIf { it.isNotBlank() },
        )
        _uiState.update(::foregroundRecoveryStartingState)
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(command, "foreground.refresh")
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { dispatch("refresh") }
    }

    fun reportDiagnosticExport(success: Boolean, message: String) {
        diagnosticLog.append(if (success) "diagnostics.exported" else "diagnostics.export.failed", message)
        _uiState.update { it.copy(diagnosticMessage = message, diagnosticFailed = !success) }
    }

    fun openProjectDialog() {
        val path = initialProjectPath(uiState.value)
        _uiState.update {
            it.copy(
                projectDialogOpen = true,
                projectPath = path,
                pendingDirectoryCommandId = "",
                pendingSessionCandidatesCommandId = "",
                requestedSessionCandidatesPath = "",
                projectSessionCandidates = null,
                pendingProjectCommandId = "",
                pendingProjectCommandStage = "",
                pendingProjectAction = "",
                pendingProjectCodexId = "",
                missingDirectoryConfirmationPath = "",
                projectError = "",
            )
        }
        listDirectories(path)
    }

    fun closeProjectDialog() {
        projectOperationSingleFlight.release()
        _uiState.update {
            it.copy(
                projectDialogOpen = false,
                pendingDirectoryCommandId = "",
                pendingSessionCandidatesCommandId = "",
                requestedSessionCandidatesPath = "",
                projectSessionCandidates = null,
                pendingProjectCommandId = "",
                pendingProjectCommandStage = "",
                pendingProjectAction = "",
                pendingProjectCodexId = "",
                missingDirectoryConfirmationPath = "",
                projectError = "",
            )
        }
    }

    fun setProjectPath(value: String) {
        _uiState.update {
            it.copy(
                projectPath = value,
                pendingDirectoryCommandId = "",
                pendingSessionCandidatesCommandId = "",
                requestedSessionCandidatesPath = "",
                projectSessionCandidates = null,
                missingDirectoryConfirmationPath = "",
                projectError = "",
            )
        }
    }

    fun listDirectories(path: String = uiState.value.projectPath) {
        val requestedPath = path.trim()
        if (requestedPath.isEmpty()) return
        val command = coreCommand("list_directories", JSONObject().put("parentPath", requestedPath))
        _uiState.update {
            it.copy(
                projectPath = requestedPath,
                pendingDirectoryCommandId = command.getString("id"),
                pendingSessionCandidatesCommandId = "",
                requestedSessionCandidatesPath = "",
                projectSessionCandidates = null,
                projectError = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(command, "project.list_directories")
        }
    }

    fun listSessionCandidates() {
        val path = uiState.value.projectPath.trim()
        if (path.isEmpty()) return
        val command = coreCommand("list_session_candidates", JSONObject().put("cwd", path))
        _uiState.update {
            it.copy(
                pendingSessionCandidatesCommandId = command.getString("id"),
                requestedSessionCandidatesPath = path,
                projectSessionCandidates = null,
                projectError = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(command, "project.list_sessions")
        }
    }

    fun createCodex() {
        val path = uiState.value.projectPath.trim()
        if (path.isEmpty()) return
        dispatchProjectOperation(
            createCodexCommand(path, createDirectoryIfMissing = false),
        )
    }

    fun confirmCreateMissingDirectory() {
        val path = uiState.value.missingDirectoryConfirmationPath.trim()
        if (path.isEmpty() || path != uiState.value.projectPath.trim()) return
        _uiState.update { it.copy(missingDirectoryConfirmationPath = "", projectError = "") }
        dispatchProjectOperation(
            createCodexCommand(path, createDirectoryIfMissing = true),
        )
    }

    fun cancelCreateMissingDirectory() {
        val recoverCore = AtomicBoolean(false)
        _uiState.update { current ->
            resolveMissingDirectoryCancel(current).let { decision ->
                recoverCore.set(decision.refreshCore)
                decision.nextState
            }
        }
        if (recoverCore.get()) {
            viewModelScope.launch(Dispatchers.IO) { dispatch("refresh") }
        }
    }

    fun importSession(sessionId: String, source: String) {
        if (sessionId.isBlank() || source.isBlank()) return
        dispatchProjectOperation(
            "import_session",
            JSONObject().put("sessionId", sessionId).put("source", source),
        )
    }

    fun renameCodex(codexId: String, title: String) {
        if (codexId.isBlank() || title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatch("rename_codex", JSONObject().put("codexId", codexId).put("title", title.trim()))
        }
    }

    fun unmanageCodex(codexId: String) = dispatchCodexAction("unmanage_codex", codexId)

    fun forgetCodex(codexId: String) {
        val normalizedId = codexId.trim()
        if (normalizedId.isEmpty()) return
        val command = coreCommand("forget_codex", JSONObject().put("codexId", normalizedId))
        val coreState = uiState.value.core
        forgetPersistenceTracker.track(
            commandId = command.getString("id"),
            codexId = normalizedId,
            wasCoreSelected = coreState.selectedCodexId == normalizedId ||
                coreState.conversation?.codexId == normalizedId,
        )
        viewModelScope.launch(Dispatchers.IO) { dispatchTracked(command, "session.forget") }
    }

    fun openConversation(codexId: String) {
        if (codexId.isBlank()) return
        flushPendingDraftWrite()
        val openingFromProjectDialog = uiState.value.projectDialogOpen
        if (openingFromProjectDialog && !projectOperationSingleFlight.tryAcquire()) return
        val managedProjectCommand = if (openingFromProjectDialog) {
            coreCommand("select_codex", JSONObject().put("codexId", codexId))
        } else null
        _uiState.update {
            it.copy(
                openCodexId = if (managedProjectCommand == null) codexId else it.openCodexId,
                draft = if (managedProjectCommand == null) draftForCodex(codexDrafts, codexId) else it.draft,
                pendingProjectCommandId = managedProjectCommand?.getString("id") ?: it.pendingProjectCommandId,
                pendingProjectCommandStage = if (managedProjectCommand != null) ProjectStageSelection else it.pendingProjectCommandStage,
                pendingProjectAction = if (managedProjectCommand != null) "select_codex" else it.pendingProjectAction,
                pendingProjectCodexId = if (managedProjectCommand != null) codexId else it.pendingProjectCodexId,
                projectError = if (managedProjectCommand != null) "" else it.projectError,
                stoppingTurn = false,
                conversationPage = ConversationPage.CONVERSATION,
                workspaceEditorOpen = false,
                workspaceEditorText = "",
                pendingWorkspaceGetCommandId = "",
                pendingWorkspaceRefreshDirectory = "",
                pendingWorkspaceReadCommandId = "",
                pendingWorkspaceFilePath = "",
                pendingWorkspaceUploadCommandId = "",
                pendingWorkspaceUploadPath = "",
                pendingWorkspaceDownloadCommandId = "",
                pendingWorkspaceDownloadPath = "",
                workspaceDownloadReady = null,
                workspaceTransferError = "",
                workspaceTransferNotice = "",
                workspaceLocalTransferStatus = "none",
                workspaceTransferCodexId = "",
                workspaceTransferDirectory = "",
                workspaceTransferPath = "",
                workspaceTransferEntryKind = "",
                workspaceTransferUploadKind = "",
                workspaceUploadRecoveryPartial = false,
                userInputDrafts = emptyMap(),
                submittingRequestIds = emptySet(),
            )
        }
        if (managedProjectCommand == null) rememberSelectedCodex(codexId)
        viewModelScope.launch(Dispatchers.IO) {
            if (managedProjectCommand != null) dispatchTracked(managedProjectCommand, "project.selection")
            else dispatch("select_codex", JSONObject().put("codexId", codexId))
        }
    }

    fun closeConversation() {
        flushPendingDraftWrite()
        _uiState.update {
            it.copy(
                openCodexId = null,
                draft = "",
                stoppingTurn = false,
                conversationPage = ConversationPage.CONVERSATION,
                workspaceEditorOpen = false,
                workspaceEditorText = "",
                pendingWorkspaceGetCommandId = "",
                pendingWorkspaceRefreshDirectory = "",
                pendingWorkspaceReadCommandId = "",
                pendingWorkspaceFilePath = "",
                pendingWorkspaceUploadCommandId = "",
                pendingWorkspaceUploadPath = "",
                pendingWorkspaceDownloadCommandId = "",
                pendingWorkspaceDownloadPath = "",
                workspaceDownloadReady = null,
                workspaceTransferError = "",
                workspaceTransferNotice = "",
                workspaceLocalTransferStatus = "none",
                workspaceTransferCodexId = "",
                workspaceTransferDirectory = "",
                workspaceTransferPath = "",
                workspaceTransferEntryKind = "",
                workspaceTransferUploadKind = "",
                workspaceUploadRecoveryPartial = false,
                userInputDrafts = emptyMap(),
                submittingRequestIds = emptySet(),
            )
        }
    }

    fun showConversationPage() {
        _uiState.update {
            it.copy(
                conversationPage = ConversationPage.CONVERSATION,
                workspaceEditorOpen = false,
                pendingWorkspaceGetCommandId = "",
                pendingWorkspaceReadCommandId = "",
                pendingWorkspaceFilePath = "",
            )
        }
    }

    fun showWorkspacePage() {
        val codexId = uiState.value.openCodexId ?: return
        val command = workspaceGetCommand(codexId)
        _uiState.update {
            it.copy(
                conversationPage = ConversationPage.WORKSPACE,
                pendingWorkspaceGetCommandId = command.getString("id"),
                pendingWorkspaceRefreshDirectory = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatchWorkspace(command)
        }
    }

    fun listWorkspaceEntries(relativeDirectory: String) {
        val state = uiState.value
        val workspace = state.core.workspace
        val codexId = state.openCodexId ?: return
        if (workspace?.supported != true || workspace.codexId != codexId) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatchWorkspace(workspaceListCommand(codexId, relativeDirectory))
        }
    }

    fun openWorkspaceFile(entry: WorkspaceEntry) {
        val state = uiState.value
        val workspace = state.core.workspace
        val codexId = state.openCodexId ?: return
        if (workspace?.supported != true || workspace.codexId != codexId ||
            entry.kind != "regular_file" || !entry.textViewable
        ) return
        val command = workspaceReadCommand(codexId, entry.relativePath)
        _uiState.update {
            it.copy(
                pendingWorkspaceFilePath = entry.relativePath,
                pendingWorkspaceReadCommandId = command.getString("id"),
                workspaceEditorOpen = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatchWorkspace(command)
        }
    }

    fun setWorkspaceEditorText(value: String) {
        _uiState.update { it.copy(workspaceEditorText = value) }
    }

    fun closeWorkspaceEditor() {
        _uiState.update { it.copy(workspaceEditorOpen = false) }
    }

    fun saveWorkspaceFile() {
        val state = uiState.value
        val workspace = state.core.workspace ?: return
        val codexId = state.openCodexId ?: return
        val entry = workspace.openFile?.entry ?: return
        if (workspace.codexId != codexId || !canSaveWorkspaceFile(entry, workspace.accessState)) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatchWorkspace(
                workspaceWriteCommand(
                    codexId = codexId,
                    relativePath = entry.relativePath,
                    utf8Text = state.workspaceEditorText,
                    expectedRevision = entry.revision,
                    expectedQuiescenceToken = workspace.accessState.quiescenceToken,
                ),
            )
        }
    }

    fun beginWorkspaceUploadPicker(kind: String): Boolean {
        var accepted = false
        _uiState.update { current ->
            accepted = false
            val workspace = current.core.workspace
            val directory = workspace?.currentDirectory?.relativeDirectory.orEmpty()
            if (!workspaceTransferIsIdle(current) || workspace?.codexId != current.openCodexId ||
                !canUploadWorkspace(workspace) || kind !in setOf("regular_file", "zip_directory")
            ) current else {
                accepted = true
                current.copy(
                    workspaceLocalTransferStatus = "choosing_upload",
                    workspaceTransferCodexId = workspace?.codexId.orEmpty(),
                    workspaceTransferDirectory = directory,
                    workspaceTransferUploadKind = kind,
                    workspaceTransferPath = "",
                    workspaceTransferEntryKind = "",
                    workspaceTransferError = "",
                    workspaceTransferNotice = "",
                )
            }
        }
        return accepted
    }

    fun beginWorkspaceUploadRead(codexId: String, directory: String, kind: String): Boolean {
        var accepted = false
        _uiState.update { current ->
            accepted = false
            val workspace = current.core.workspace
            if (current.workspaceLocalTransferStatus != "choosing_upload" ||
                !workspaceTransferContextMatches(current, codexId, directory, "", "", kind) ||
                workspace?.codexId != codexId || current.openCodexId != codexId ||
                workspace.currentDirectory?.relativeDirectory.orEmpty() != directory
            ) current else {
                accepted = true
                current.copy(workspaceLocalTransferStatus = "reading_upload")
            }
        }
        if (!accepted) cancelWorkspaceTransfer("项目文件位置已变化，已取消本次上传")
        return accepted
    }

    fun uploadWorkspaceEntry(
        codexId: String,
        directory: String,
        destinationPath: String,
        kind: String,
        bytes: ByteArray,
    ): String? {
        var command: JSONObject? = null
        _uiState.update { current ->
            command = null
            val workspace = current.core.workspace
            if (current.workspaceLocalTransferStatus != "reading_upload" ||
                !workspaceTransferContextMatches(current, codexId, directory, "", "", kind) ||
                workspace?.codexId != codexId || current.openCodexId != codexId ||
                workspace.currentDirectory?.relativeDirectory.orEmpty() != directory || !canUploadWorkspace(workspace) ||
                destinationPath.isBlank() || bytes.size.toLong() > workspace.limits.maxInlineUploadBytes ||
                current.pendingWorkspaceUploadCommandId.isNotBlank() || current.pendingWorkspaceDownloadCommandId.isNotBlank()
            ) current else {
                val next = workspaceUploadCommand(
                    codexId, destinationPath, kind, Base64.getEncoder().encodeToString(bytes),
                    workspace.accessState.quiescenceToken,
                )
                command = next
                current.copy(
                    pendingWorkspaceUploadCommandId = next.getString("id"),
                    pendingWorkspaceUploadPath = destinationPath,
                    workspaceTransferPath = destinationPath,
                    workspaceLocalTransferStatus = "uploading",
                    workspaceTransferError = "",
                )
            }
        }
        val accepted = command ?: run {
            cancelWorkspaceTransfer("项目文件状态已变化，已取消本次上传")
            return null
        }
        viewModelScope.launch(Dispatchers.IO) { dispatchWorkspace(accepted) }
        return accepted.getString("id")
    }

    fun beginWorkspaceDownloadPicker(entry: WorkspaceEntry): Boolean {
        var accepted = false
        _uiState.update { current ->
            accepted = false
            val workspace = current.core.workspace
            if (!workspaceTransferIsIdle(current) || workspace?.codexId != current.openCodexId ||
                !canStartDownload(workspace) || expectedDownloadResultKind(entry.kind) == null || entry.relativePath.isBlank()
            ) current else {
                accepted = true
                current.copy(
                    workspaceLocalTransferStatus = "choosing_download",
                    workspaceTransferCodexId = workspace?.codexId.orEmpty(),
                    workspaceTransferDirectory = workspace?.currentDirectory?.relativeDirectory.orEmpty(),
                    workspaceTransferPath = entry.relativePath,
                    workspaceTransferEntryKind = entry.kind,
                    workspaceTransferUploadKind = "",
                    workspaceTransferError = "",
                    workspaceTransferNotice = "",
                )
            }
        }
        return accepted
    }

    fun downloadWorkspaceEntry(codexId: String, relativePath: String, entryKind: String): String? {
        var command: JSONObject? = null
        _uiState.update { current ->
            command = null
            val workspace = current.core.workspace
            if (current.workspaceLocalTransferStatus != "choosing_download" ||
                !workspaceTransferContextMatches(current, codexId, current.workspaceTransferDirectory, relativePath, entryKind, "") ||
                workspace?.codexId != codexId || current.openCodexId != codexId || !canStartDownload(workspace) ||
                current.pendingWorkspaceUploadCommandId.isNotBlank() || current.pendingWorkspaceDownloadCommandId.isNotBlank()
            ) current else {
                val next = workspaceDownloadCommand(codexId, relativePath)
                command = next
                current.copy(
                    pendingWorkspaceDownloadCommandId = next.getString("id"),
                    pendingWorkspaceDownloadPath = relativePath,
                    workspaceDownloadReady = null,
                    workspaceLocalTransferStatus = "downloading",
                    workspaceTransferError = "",
                )
            }
        }
        val accepted = command ?: run {
            cancelWorkspaceTransfer("项目文件状态已变化，已取消本次下载")
            return null
        }
        viewModelScope.launch(Dispatchers.IO) { dispatchWorkspace(accepted) }
        return accepted.getString("id")
    }

    fun beginWorkspaceDownloadWrite(commandId: String, relativePath: String): Boolean {
        var accepted = false
        _uiState.update { current ->
            accepted = false
            val ready = current.workspaceDownloadReady
            val validStatus = current.workspaceLocalTransferStatus in setOf("downloading", "choosing_download", "writing_download")
            if (!validStatus || ready?.commandId != commandId || ready.relativePath != relativePath ||
                current.openCodexId != current.workspaceTransferCodexId ||
                current.core.workspace?.codexId != current.workspaceTransferCodexId ||
                !downloadResultMatches(ready.result, relativePath, current.workspaceTransferEntryKind)
            ) current else {
                accepted = true
                current.copy(workspaceLocalTransferStatus = "writing_download")
            }
        }
        return accepted
    }

    fun workspaceDownloadWriteFailed(message: String) {
        _uiState.update { current -> clearWorkspaceTransferContext(current).copy(workspaceTransferError = message) }
    }

    fun cancelWorkspaceTransfer(message: String = "") {
        _uiState.update { current ->
            if (current.workspaceLocalTransferStatus in setOf("uploading", "downloading", "recovering_upload")) current
            else clearWorkspaceTransferContext(current).copy(workspaceTransferError = message)
        }
    }

    fun consumeWorkspaceDownload(commandId: String) {
        _uiState.update { current ->
            if (current.workspaceDownloadReady?.commandId == commandId) {
                clearWorkspaceTransferContext(current).copy(workspaceDownloadReady = null, workspaceTransferError = "")
            } else current
        }
    }

    fun setWorkspaceTransferError(message: String) {
        _uiState.update { it.copy(workspaceTransferError = message) }
    }

    fun clearWorkspaceTransferError() {
        _uiState.update { it.copy(workspaceTransferError = "") }
    }

    fun setDraft(value: String) {
        val codexId = uiState.value.openCodexId ?: return
        codexDraftVersions.compute(codexId) { _, version -> (version ?: 0L) + 1L }
        if (value.isEmpty()) codexDrafts.remove(codexId) else codexDrafts[codexId] = value
        _uiState.update { current ->
            if (current.openCodexId == codexId) current.copy(draft = value) else current
        }
        latestDraftWrite.update(codexId, value)
        draftPersistenceJob?.cancel()
        draftPersistenceJob = viewModelScope.launch {
            delay(DraftPersistenceDebounceMs)
            draftPersistenceJob = null
            enqueuePendingDraftWrite()
        }
    }

    fun sendMessage() {
        val state = uiState.value
        val text = state.draft.trim()
        if (!canDispatchMessage(state, text)) return
        val conversation = state.core.conversation ?: return
        val codexId = state.openCodexId ?: return
        val command = coreCommand("start_turn", JSONObject().put("text", text))
        flushPendingDraftWrite()
        sendDraftTracker.track(
            commandId = command.getString("id"),
            codexId = codexId,
            originalDraft = state.draft,
            draftVersion = codexDraftVersions[codexId] ?: 0L,
            knownTurnIds = conversation.turns.mapTo(mutableSetOf()) { it.turnId },
        )
        codexDrafts.remove(codexId)
        _uiState.update { current ->
            val optimisticMessage = OptimisticUserMessage(
                commandId = command.getString("id"),
                codexId = codexId,
                text = text,
                createdAtUnixMs = System.currentTimeMillis(),
            )
            current.withOptimisticUserMessage(optimisticMessage).copy(
                draft = "",
                stoppingTurn = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(command, "general")
        }
    }

    fun interruptTurn() {
        if (uiState.value.core.conversation?.running != true) return
        _uiState.update { it.copy(stoppingTurn = true) }
        viewModelScope.launch(Dispatchers.IO) { dispatch("interrupt_turn", JSONObject()) }
    }

    fun respondApproval(requestId: String, decision: String) {
        var accepted = false
        _uiState.update { current ->
            accepted = false
            val request = activeConversation(current)?.pendingRequests?.find {
                it.type == "approval" && it.requestId == requestId
            }
            if (request != null && canSubmitApproval(request, decision, current.submittingRequestIds)) {
                accepted = true
                current.copy(submittingRequestIds = current.submittingRequestIds + requestId)
            } else {
                current
            }
        }
        if (!accepted) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(respondApprovalCommand(requestId, decision), "pending.response")
        }
    }

    fun toggleUserInputOption(requestId: String, questionId: String, optionId: String) {
        _uiState.update { current ->
            val request = activeConversation(current)?.pendingRequests?.find {
                it.type == "user_input" && !it.resolved && it.requestId == requestId
            }
            val question = request?.questions?.find { it.questionId == questionId }
            if (request == null || request.inFlight || requestId in current.submittingRequestIds ||
                question == null || question.options.none { it.optionId == optionId }
            ) return@update current
            val requestDraft = current.userInputDrafts[requestId].orEmpty()
            val answer = requestDraft[questionId] ?: UserInputAnswerDraft()
            val selected = if (question.allowsMultiple) {
                if (optionId in answer.selectedOptionIds) answer.selectedOptionIds - optionId
                else answer.selectedOptionIds + optionId
            } else if (answer.selectedOptionIds == setOf(optionId)) {
                emptySet()
            } else {
                setOf(optionId)
            }
            current.copy(
                userInputDrafts = current.userInputDrafts +
                    (requestId to (requestDraft + (questionId to answer.copy(selectedOptionIds = selected)))),
            )
        }
    }

    fun setUserInputFreeForm(requestId: String, questionId: String, value: String) {
        _uiState.update { current ->
            val request = activeConversation(current)?.pendingRequests?.find {
                it.type == "user_input" && !it.resolved && it.requestId == requestId
            }
            val question = request?.questions?.find { it.questionId == questionId }
            if (request == null || request.inFlight || requestId in current.submittingRequestIds ||
                question?.allowsFreeForm != true
            ) return@update current
            val requestDraft = current.userInputDrafts[requestId].orEmpty()
            val answer = requestDraft[questionId] ?: UserInputAnswerDraft()
            current.copy(
                userInputDrafts = current.userInputDrafts +
                    (requestId to (requestDraft + (questionId to answer.copy(freeFormText = value)))),
            )
        }
    }

    fun submitUserInput(requestId: String) {
        var acceptedRequest: PendingRequest? = null
        var acceptedDrafts: Map<String, UserInputAnswerDraft> = emptyMap()
        _uiState.update { current ->
            acceptedRequest = null
            acceptedDrafts = emptyMap()
            val request = activeConversation(current)?.pendingRequests?.find {
                it.type == "user_input" && !it.resolved && it.requestId == requestId
            }
            val drafts = current.userInputDrafts[requestId].orEmpty()
            if (request != null && canSubmitUserInput(request, drafts, current.submittingRequestIds)) {
                acceptedRequest = request
                acceptedDrafts = drafts
                current.copy(submittingRequestIds = current.submittingRequestIds + requestId)
            } else {
                current
            }
        }
        val request = acceptedRequest ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dispatchTracked(respondUserInputCommand(request, acceptedDrafts), "pending.response")
        }
    }

    private fun dispatch(type: String, payload: JSONObject? = null) {
        val command = coreCommand(type, payload)
        dispatchTracked(command, "general")
    }

    private fun dispatchWorkspace(command: JSONObject) {
        dispatchTracked(command, "workspace")
    }

    private fun dispatchTracked(command: JSONObject, stage: String) {
        if (stage.startsWith("project") || stage == "workspace" || stage == "pending.response") {
            diagnosticActionTracker.register(command, stage)
        }
        diagnosticLog.append("core.action", diagnosticCommandDetails(command, stage))
        acceptCoreState(core.dispatch(command.toString()))
    }

    private fun dispatchProjectOperation(command: JSONObject) {
        if (!projectOperationSingleFlight.tryAcquire()) return
        val commandId = command.getString("id")
        val type = command.getString("type")
        _uiState.update {
            it.copy(
                pendingProjectCommandId = commandId,
                pendingProjectCommandStage = ProjectStageMutation,
                pendingProjectAction = type,
                pendingProjectCodexId = "",
                projectError = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) { dispatchTracked(command, "project.mutation") }
    }

    private fun dispatchProjectOperation(type: String, payload: JSONObject) =
        dispatchProjectOperation(coreCommand(type, payload))

    private fun dispatchCodexAction(type: String, codexId: String) {
        if (codexId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatch(type, JSONObject().put("codexId", codexId))
        }
    }

    private fun rememberProjectPath(path: String) {
        _uiState.update { it.copy(lastProjectPath = path) }
        viewModelScope.launch { hostPreferences.setLastProjectPath(path) }
    }

    private fun rememberSelectedCodex(codexId: String) {
        persistedSelectedCodexId = codexId
        attemptedPersistedSelections += codexId
        codexPreferenceWrites.trySend(CodexPreferenceWrite.Selected(codexId))
    }

    private fun flushPendingDraftWrite() {
        draftPersistenceJob?.cancel()
        draftPersistenceJob = null
        enqueuePendingDraftWrite()
    }

    private fun enqueuePendingDraftWrite() {
        latestDraftWrite.take()?.let { (codexId, draft) ->
            codexPreferenceWrites.trySend(CodexPreferenceWrite.Draft(codexId, draft))
        }
    }

    private fun discardPendingDraftWrite(codexId: String) {
        if (latestDraftWrite.discard(codexId)) {
            draftPersistenceJob?.cancel()
            draftPersistenceJob = null
        }
    }

    private fun clearForgottenCodex(codexId: String) {
        discardPendingDraftWrite(codexId)
        sendDraftTracker.forgetCodex(codexId)
        codexDrafts.remove(codexId)
        codexDraftVersions.remove(codexId)
        if (persistedSelectedCodexId == codexId) persistedSelectedCodexId = null
        _uiState.update { current ->
            val remaining = current.optimisticUserMessages.filterNot { it.codexId == codexId }
            current.copy(
                core = projectOptimisticUserMessages(current.core, remaining),
                optimisticUserMessages = remaining,
            )
        }
        if (uiState.value.openCodexId == codexId) closeConversation()
        codexPreferenceWrites.trySend(CodexPreferenceWrite.Forget(codexId))
    }

    private fun resolveSentDraft(resolution: SendDraftResolution) {
        val latestVersion = codexDraftVersions[resolution.codexId] ?: 0L
        val persistedDraft = draftPersistenceAfterSendResolution(resolution, latestVersion) ?: return
        if (resolution.accepted) {
            codexDrafts.remove(resolution.codexId)
        } else {
            codexDrafts[resolution.codexId] = resolution.originalDraft
            _uiState.update { current ->
                current.copy(
                    draft = restoredDraftAfterSendFailure(
                        currentDraft = current.draft,
                        openCodexId = current.openCodexId,
                        resolution = resolution,
                        latestDraftVersion = latestVersion,
                    ),
                )
            }
        }
        codexPreferenceWrites.trySend(CodexPreferenceWrite.Draft(resolution.codexId, persistedDraft))
    }

    private fun restorePersistedConversationIfReady() {
        val codexId = persistedCodexToRestore(
            uiState.value,
            persistedSelectedCodexId,
            codexStateLoaded,
            attemptedPersistedSelections,
        ) ?: return
        if (!attemptedPersistedSelections.add(codexId)) return
        _uiState.update { current ->
            if (current.openCodexId == null) {
                current.copy(openCodexId = codexId, draft = draftForCodex(codexDrafts, codexId))
            } else current
        }
        viewModelScope.launch(Dispatchers.IO) {
            dispatch("select_codex", JSONObject().put("codexId", codexId))
        }
    }

    private fun acceptCoreState(raw: String) {
        val decoded = runCatching { decodeCoreState(raw) }.getOrElse { error ->
            CoreState(phase = "error", error = "无法解析 MobileCore 状态：${error.message}")
        }
        val revisionEligible = shouldAcceptCoreRevision(_uiState.value.core.revision, decoded.revision)
        val foregroundResolution = if (revisionEligible) {
            foregroundRecoveryTracker.onCoreState(
                decoded,
                reconnectAvailable = _uiState.value.hostAddress.isNotBlank(),
            )
        } else {
            ForegroundRecoveryResolution.None
        }
        val selectForegroundCodex = (foregroundResolution as? ForegroundRecoveryResolution.SelectCodex)?.let {
            coreCommand("select_codex", JSONObject().put("codexId", it.codexId)).also { command ->
                foregroundRecoveryTracker.trackSelection(command.getString("id"))
            }
        }
        val reconnectForeground = foregroundResolution is ForegroundRecoveryResolution.Reconnect
        val forgottenCodexId = if (revisionEligible) {
            forgetPersistenceTracker.onCoreState(decoded)
                ?: authoritativeForgottenCodex(_uiState.value, persistedSelectedCodexId, decoded)
        } else null
        val sentDraftResolution = if (revisionEligible) sendDraftTracker.onCoreState(decoded) else null
        if (revisionEligible) {
            coreStateDiagnosticRecorder.record(decoded)
            diagnosticActionTracker.consumeTerminal(decoded, _uiState.value)?.let { tracked ->
                diagnosticLog.append("core.action.result", diagnosticResultDetails(decoded, tracked, _uiState.value))
            }
        }
        var listWorkspaceAfterGet: Pair<String, String>? = null
        var refreshWorkspaceDirectory: Pair<String, String>? = null
        var recoverWorkspaceAfterUploadFailure: Pair<String, String>? = null
        var projectPathToRemember: String? = null
        var selectedCodexToRemember: String? = null
        var selectProjectAfterMutation: JSONObject? = null
        var projectFlowFinished = false
        _uiState.update { current ->
            projectPathToRemember = null
            projectFlowFinished = false
            if (!shouldAcceptCoreRevision(current.core.revision, decoded.revision)) {
                current
            } else {
                val projectResolution = projectCommandResolution(current, decoded)
                val completedProject = projectResolution.outcome == ProjectCommandOutcome.SUCCESS
                val confirmMissingDirectory = shouldConfirmMissingDirectory(current, decoded, projectResolution)
                val failedProject = projectResolution.outcome == ProjectCommandOutcome.FAILURE && !confirmMissingDirectory
                projectFlowFinished = completedProject || failedProject || confirmMissingDirectory
                val selectRequired = projectResolution.outcome == ProjectCommandOutcome.SELECT_REQUIRED
                val selectionCommand = if (selectRequired) {
                    coreCommand("select_codex", JSONObject().put("codexId", projectResolution.codexId))
                        .also { selectProjectAfterMutation = it }
                } else null
                if (completedProject) {
                    projectPathToRemember = successfulProjectPath(current, decoded)
                    selectedCodexToRemember = projectResolution.codexId
                }
                val directoryResolution = resolveDirectoryResult(
                    current.projectPath, current.pendingDirectoryCommandId, decoded,
                )
                val directoryCompleted = current.pendingDirectoryCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingDirectoryCommandId &&
                    (decoded.phase == "ready" || decoded.error.isNotBlank())
                val candidatesCompleted = current.pendingSessionCandidatesCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingSessionCandidatesCommandId &&
                    current.projectPath.trim() == current.requestedSessionCandidatesPath &&
                    (decoded.phase == "ready" || decoded.error.isNotBlank())
                val normalizedCandidatePath = decoded.sessionCandidates?.normalizedCwd.orEmpty().trim()
                val candidatesSucceeded = candidatesCompleted && decoded.error.isBlank() &&
                    decoded.sessionCandidates != null && normalizedCandidatePath.isNotBlank()
                val returnedWorkspace = decoded.workspace
                val getCompleted = current.pendingWorkspaceGetCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingWorkspaceGetCommandId &&
                    returnedWorkspace != null && returnedWorkspace.loading != "workspace"
                val uploadRecoveryCompleted = getCompleted && current.workspaceLocalTransferStatus == "recovering_upload"
                val uploadRecoverySucceeded = uploadRecoveryCompleted && returnedWorkspace?.error == null
                val recoveryPresentation = uploadRecoveryMessage(
                    current.workspaceUploadRecoveryPartial, uploadRecoverySucceeded, current.workspaceTransferError,
                )
                if (getCompleted && returnedWorkspace.supported && returnedWorkspace.error == null && returnedWorkspace.codexId.isNotBlank()) {
                    listWorkspaceAfterGet = returnedWorkspace.codexId to current.pendingWorkspaceRefreshDirectory
                }
                val pendingPath = current.pendingWorkspaceFilePath
                val readMatches = current.pendingWorkspaceReadCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingWorkspaceReadCommandId
                val fileCompleted = readMatches && pendingPath.isNotBlank() && returnedWorkspace?.loading == "none" &&
                    returnedWorkspace.error == null &&
                    returnedWorkspace.openFile?.entry?.relativePath == pendingPath
                val fileFailed = readMatches && pendingPath.isNotBlank() && returnedWorkspace?.loading == "none" &&
                    returnedWorkspace.error != null
                val uploadMatches = current.pendingWorkspaceUploadCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingWorkspaceUploadCommandId && returnedWorkspace?.loading == "none"
                val uploadSucceeded = uploadMatches && returnedWorkspace?.error == null &&
                    uploadResultMatches(
                        returnedWorkspace?.uploadResult, current.pendingWorkspaceUploadPath, current.workspaceTransferUploadKind,
                    )
                val uploadFailed = uploadMatches && !uploadSucceeded
                val partialUpload = uploadFailed && isPartialUploadFailure(returnedWorkspace, current.pendingWorkspaceUploadPath)
                if (uploadSucceeded) {
                    val directory = returnedWorkspace?.currentDirectory?.relativeDirectory.orEmpty()
                    refreshWorkspaceDirectory = returnedWorkspace?.codexId?.takeIf { it.isNotBlank() }?.let { it to directory }
                }
                if (uploadFailed) {
                    val codexId = returnedWorkspace?.codexId?.ifBlank { current.openCodexId.orEmpty() }.orEmpty()
                    if (codexId.isNotBlank()) {
                        recoverWorkspaceAfterUploadFailure = codexId to current.core.workspace?.currentDirectory?.relativeDirectory.orEmpty()
                    }
                }
                val downloadMatches = current.pendingWorkspaceDownloadCommandId.isNotBlank() &&
                    decoded.commandId == current.pendingWorkspaceDownloadCommandId && returnedWorkspace?.loading == "none"
                val downloadResult = returnedWorkspace?.downloadResult
                val downloadSucceeded = downloadMatches && returnedWorkspace?.error == null &&
                    downloadResult != null && downloadResultMatches(
                        downloadResult, current.pendingWorkspaceDownloadPath, current.workspaceTransferEntryKind,
                    )
                val downloadFailed = downloadMatches && !downloadSucceeded
                val decodedActiveConversation = decoded.conversation?.takeIf {
                    it.codexId == current.openCodexId
                }
                val recoveryFailed = foregroundResolution as? ForegroundRecoveryResolution.Failed
                val recoveryFinished = foregroundResolution is ForegroundRecoveryResolution.Finished || recoveryFailed != null
                val nextOpenCodexId = when {
                    recoveryFailed != null -> null
                    foregroundResolution is ForegroundRecoveryResolution.Finished &&
                        foregroundResolution.codexId != null -> foregroundResolution.codexId
                    completedProject -> projectResolution.codexId
                    else -> current.openCodexId
                }
                val optimisticUserMessages = reconcileOptimisticUserMessages(
                    current.optimisticUserMessages,
                    decoded,
                    sentDraftResolution,
                )
                var next = current.copy(
                    core = projectOptimisticUserMessages(decoded, optimisticUserMessages),
                    optimisticUserMessages = optimisticUserMessages,
                    userInputDrafts = decodedActiveConversation?.let { conversation ->
                        current.userInputDrafts.filterKeys { requestId ->
                            conversation.pendingRequests.any {
                                it.type == "user_input" && !it.resolved && it.requestId == requestId
                            }
                        }
                    } ?: current.userInputDrafts,
                    submittingRequestIds = decodedActiveConversation?.let { conversation ->
                        current.submittingRequestIds.filterTo(mutableSetOf()) { requestId ->
                            val request = conversation.pendingRequests.find { it.requestId == requestId }
                            request != null && !request.inFlight && request.error == null
                        }
                    } ?: current.submittingRequestIds,
                    projectDialogOpen = if (completedProject) false else current.projectDialogOpen,
                    projectPath = when {
                        candidatesSucceeded && normalizedCandidatePath.isNotBlank() -> normalizedCandidatePath
                        else -> directoryResolution.path
                    },
                    pendingDirectoryCommandId = directoryResolution.pendingCommandId,
                    pendingSessionCandidatesCommandId = if (candidatesCompleted) "" else current.pendingSessionCandidatesCommandId,
                    requestedSessionCandidatesPath = if (candidatesCompleted) "" else current.requestedSessionCandidatesPath,
                    projectSessionCandidates = when {
                        candidatesSucceeded -> decoded.sessionCandidates
                        candidatesCompleted -> null
                        else -> current.projectSessionCandidates
                    },
                    pendingProjectCommandId = when {
                        selectionCommand != null -> selectionCommand.getString("id")
                        completedProject || failedProject || confirmMissingDirectory -> ""
                        else -> current.pendingProjectCommandId
                    },
                    pendingProjectCommandStage = when {
                        selectionCommand != null -> ProjectStageSelection
                        completedProject || failedProject || confirmMissingDirectory -> ""
                        else -> current.pendingProjectCommandStage
                    },
                    pendingProjectAction = when {
                        completedProject || failedProject || confirmMissingDirectory -> ""
                        else -> current.pendingProjectAction
                    },
                    pendingProjectCodexId = when {
                        selectionCommand != null -> projectResolution.codexId
                        completedProject || failedProject || confirmMissingDirectory -> ""
                        else -> current.pendingProjectCodexId
                    },
                    missingDirectoryConfirmationPath = when {
                        confirmMissingDirectory -> current.projectPath.trim()
                        completedProject -> ""
                        else -> current.missingDirectoryConfirmationPath
                    },
                    projectError = when {
                        confirmMissingDirectory -> ""
                        failedProject || (directoryCompleted && decoded.error.isNotBlank()) ||
                            (candidatesCompleted && !candidatesSucceeded) ->
                            decoded.error.ifBlank { "Host 返回的项目结果不完整，请重试" }
                        selectRequired || completedProject || candidatesSucceeded || directoryCompleted -> ""
                        else -> current.projectError
                    },
                    stoppingTurn = current.stoppingTurn && decoded.conversation?.running == true && decoded.error.isBlank(),
                    pendingWorkspaceGetCommandId = if (getCompleted) "" else current.pendingWorkspaceGetCommandId,
                    pendingWorkspaceRefreshDirectory = if (getCompleted) "" else current.pendingWorkspaceRefreshDirectory,
                    pendingWorkspaceReadCommandId = if (fileCompleted || fileFailed) "" else current.pendingWorkspaceReadCommandId,
                    pendingWorkspaceFilePath = if (fileCompleted || fileFailed) "" else pendingPath,
                    workspaceEditorText = if (fileCompleted) returnedWorkspace?.openFile?.utf8Text.orEmpty() else current.workspaceEditorText,
                    pendingWorkspaceUploadCommandId = if (uploadSucceeded || uploadFailed) "" else current.pendingWorkspaceUploadCommandId,
                    pendingWorkspaceUploadPath = if (uploadSucceeded || uploadFailed) "" else current.pendingWorkspaceUploadPath,
                    pendingWorkspaceDownloadCommandId = if (downloadSucceeded || downloadFailed) "" else current.pendingWorkspaceDownloadCommandId,
                    pendingWorkspaceDownloadPath = if (downloadSucceeded || downloadFailed) "" else current.pendingWorkspaceDownloadPath,
                    workspaceDownloadReady = if (downloadSucceeded) {
                        WorkspaceDownloadReady(decoded.commandId, downloadResult.entry.relativePath, downloadResult)
                    } else current.workspaceDownloadReady,
                    workspaceTransferError = when {
                        uploadRecoveryCompleted -> recoveryPresentation.first
                        uploadFailed && !partialUpload -> workspaceTransferErrorDescription(returnedWorkspace?.error)
                            .ifBlank { "Host 返回的上传结果不匹配，请重试" }
                        downloadFailed -> workspaceTransferErrorDescription(returnedWorkspace?.error)
                            .ifBlank { "Host 返回的下载结果不匹配，请重试" }
                        uploadSucceeded || downloadSucceeded -> ""
                        else -> current.workspaceTransferError
                    },
                    workspaceTransferNotice = when {
                        uploadRecoveryCompleted -> recoveryPresentation.second
                        else -> current.workspaceTransferNotice
                    },
                    workspaceLocalTransferStatus = when {
                        uploadFailed -> "recovering_upload"
                        else -> current.workspaceLocalTransferStatus
                    },
                    workspaceUploadRecoveryPartial = when {
                        uploadFailed -> partialUpload
                        uploadRecoveryCompleted || uploadSucceeded -> false
                        else -> current.workspaceUploadRecoveryPartial
                    },
                    foregroundRecoveryInProgress = when {
                        recoveryFinished -> false
                        else -> current.foregroundRecoveryInProgress
                    },
                    foregroundRecoveryError = when {
                        recoveryFailed != null -> recoveryFailed.message
                        foregroundResolution is ForegroundRecoveryResolution.Finished -> ""
                        else -> current.foregroundRecoveryError
                    },
                    openCodexId = nextOpenCodexId,
                    draft = when {
                        nextOpenCodexId == null -> ""
                        nextOpenCodexId != current.openCodexId -> draftForCodex(codexDrafts, nextOpenCodexId)
                        else -> current.draft
                    },
                    conversationPage = if (recoveryFinished) ConversationPage.CONVERSATION else current.conversationPage,
                    workspaceEditorOpen = when {
                        recoveryFailed != null -> false
                        fileCompleted -> true
                        else -> current.workspaceEditorOpen
                    },
                )
                if (uploadSucceeded || downloadFailed || uploadRecoveryCompleted) next = clearWorkspaceTransferContext(next)
                next
            }
        }
        coreErrorLogger.newLogMessage(_uiState.value.core)?.let { message ->
            Log.e(CoreLogTag, message)
        }
        projectPathToRemember?.let(::rememberProjectPath)
        selectedCodexToRemember?.let(::rememberSelectedCodex)
        sentDraftResolution?.let(::resolveSentDraft)
        forgottenCodexId?.let(::clearForgottenCodex)
        restorePersistedConversationIfReady()
        if (projectFlowFinished) projectOperationSingleFlight.release()
        selectProjectAfterMutation?.let { command ->
            viewModelScope.launch(Dispatchers.IO) {
                dispatchTracked(command, "project.selection")
            }
        }
        selectForegroundCodex?.let { command ->
            viewModelScope.launch(Dispatchers.IO) {
                dispatchTracked(command, "foreground.selection")
            }
        }
        if (reconnectForeground) {
            connectInternal(foregroundRecovery = true, continueForegroundRecovery = true)
        }
        listWorkspaceAfterGet?.let { (codexId, directory) ->
            viewModelScope.launch(Dispatchers.IO) {
                val workspace = uiState.value.core.workspace
                if (workspace?.supported == true && workspace.codexId == codexId) {
                    dispatchWorkspace(workspaceListCommand(codexId, directory))
                }
            }
        }
        refreshWorkspaceDirectory?.let { (codexId, directory) ->
            viewModelScope.launch(Dispatchers.IO) {
                val workspace = uiState.value.core.workspace
                if (workspace?.supported == true && workspace.codexId == codexId) {
                    dispatchWorkspace(workspaceListCommand(codexId, directory))
                }
            }
        }
        recoverWorkspaceAfterUploadFailure?.let { (codexId, directory) ->
            val command = workspaceGetCommand(codexId)
            _uiState.update {
                it.copy(
                    pendingWorkspaceGetCommandId = command.getString("id"),
                    pendingWorkspaceRefreshDirectory = directory,
                )
            }
            viewModelScope.launch(Dispatchers.IO) { dispatchWorkspace(command) }
        }
    }

    override fun onCleared() {
        flushPendingDraftWrite()
        codexPreferenceWrites.close()
        networkMonitor.close()
        core.close()
        super.onCleared()
    }
}

internal const val CoreLogTag = "CodexRemote"
private const val DraftPersistenceDebounceMs = 250L

private sealed interface CodexPreferenceWrite {
    data class Draft(val codexId: String, val draft: String) : CodexPreferenceWrite
    data class Selected(val codexId: String) : CodexPreferenceWrite
    data class Forget(val codexId: String) : CodexPreferenceWrite
}

internal class LatestCodexDraftWrite {
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun update(codexId: String, draft: String) {
        pending = codexId to draft
    }

    @Synchronized
    fun take(): Pair<String, String>? = pending.also { pending = null }

    @Synchronized
    fun discard(codexId: String): Boolean {
        if (pending?.first != codexId) return false
        pending = null
        return true
    }
}

internal class ForgetPersistenceTracker {
    private data class PendingForget(val codexId: String, val wasCoreSelected: Boolean)

    private val pending = ConcurrentHashMap<String, PendingForget>()

    fun track(commandId: String, codexId: String, wasCoreSelected: Boolean = false) {
        pending[commandId] = PendingForget(codexId, wasCoreSelected)
    }

    fun onCoreState(state: CoreState): String? {
        if (state.phase != "ready" && state.error.isBlank()) return null
        val tracked = pending.remove(state.commandId) ?: return null
        val refreshFailedAfterSelectedForget = state.error.isNotBlank() && tracked.wasCoreSelected &&
            state.selectedCodexId.isBlank() && state.conversation == null
        return tracked.codexId.takeIf {
            (state.phase == "ready" && state.error.isBlank()) || refreshFailedAfterSelectedForget
        }
    }
}

internal data class SendDraftResolution(
    val commandId: String,
    val codexId: String,
    val originalDraft: String,
    val draftVersion: Long,
    val accepted: Boolean,
    val acceptedTurnId: String = "",
)

internal class SendDraftTracker {
    private data class PendingSend(
        val codexId: String,
        val originalDraft: String,
        val draftVersion: Long,
        val knownTurnIds: Set<String>,
    )

    private val pending = ConcurrentHashMap<String, PendingSend>()

    fun track(
        commandId: String,
        codexId: String,
        originalDraft: String,
        draftVersion: Long,
        knownTurnIds: Set<String> = emptySet(),
    ) {
        pending[commandId] = PendingSend(codexId, originalDraft, draftVersion, knownTurnIds)
    }

    fun onCoreState(state: CoreState): SendDraftResolution? {
        val current = pending[state.commandId]
        val conversation = state.conversation
        val caused = conversation?.turns.orEmpty().mapNotNull { turn ->
            val commandId = turn.causedByCommandId
            val tracked = pending[commandId]
            if (commandId.isNotBlank() && tracked != null && conversation?.codexId == tracked.codexId &&
                turn.turnId.isNotBlank() && turn.turnId !in tracked.knownTurnIds
            ) Triple(commandId, tracked, turn.turnId) else null
        }
        fun acceptCausal(match: Triple<String, PendingSend, String>): SendDraftResolution? {
            val (commandId, tracked, turnId) = match
            if (!pending.remove(commandId, tracked)) return null
            return SendDraftResolution(
                commandId = commandId,
                codexId = tracked.codexId,
                originalDraft = tracked.originalDraft,
                draftVersion = tracked.draftVersion,
                accepted = true,
                acceptedTurnId = turnId,
            )
        }
        caused.firstOrNull { it.first == state.commandId }?.let { return acceptCausal(it) }
        if (current != null && definitiveStartTurnRejection(state.error)) {
            if (!pending.remove(state.commandId, current)) return null
            return SendDraftResolution(
                commandId = state.commandId,
                codexId = current.codexId,
                originalDraft = current.originalDraft,
                draftVersion = current.draftVersion,
                accepted = false,
            )
        }
        caused.firstOrNull()?.let { return acceptCausal(it) }

        if (current != null && (state.error.isNotBlank() || state.phase == "error")) {
            return null
        }
        if (current == null) return null

        val selectedConversation = conversation?.takeIf { it.codexId == current.codexId }
        val newTurns = selectedConversation?.turns.orEmpty().filter {
            it.turnId.isNotBlank() && it.turnId !in current.knownTurnIds
        }
        val evidencedTurnId = newTurns.firstOrNull { turn ->
            turn.items.any { it.type == "user_message" } || turn.messages.any { it.role == "user" }
        }?.turnId
        val activeTurnId = selectedConversation?.activeTurnId.orEmpty().takeIf {
            selectedConversation?.running == true && it.isNotBlank() && it !in current.knownTurnIds
        }
        val lateResponseTurnId = newTurns.lastOrNull()?.turnId.takeIf {
            state.phase == "ready" && state.error.isBlank()
        }
        val acceptedTurnId = activeTurnId ?: evidencedTurnId ?: lateResponseTurnId.orEmpty()
        val accepted = acceptedTurnId.isNotBlank()
        if (!accepted) return null
        if (!pending.remove(state.commandId, current)) return null
        return SendDraftResolution(
            commandId = state.commandId,
            codexId = current.codexId,
            originalDraft = current.originalDraft,
            draftVersion = current.draftVersion,
            accepted = true,
            acceptedTurnId = acceptedTurnId,
        )
    }

    fun forgetCodex(codexId: String) {
        pending.entries.removeIf { it.value.codexId == codexId }
    }
}

private fun definitiveStartTurnRejection(error: String): Boolean {
    val normalized = error.trim()
    return normalized in setOf(
        "a turn is already running",
        "select_codex is required before start_turn",
        "start_turn text is required",
    ) || normalized.startsWith("StartTurn Host error ERROR_CODE_")
}

internal fun reconcileOptimisticUserMessages(
    messages: List<OptimisticUserMessage>,
    core: CoreState,
    resolution: SendDraftResolution?,
): List<OptimisticUserMessage> {
    val resolved = messages.mapNotNull { message ->
        if (resolution?.commandId != message.commandId) {
            message
        } else if (!resolution.accepted) {
            null
        } else {
            message.copy(acceptedTurnId = resolution.acceptedTurnId)
        }
    }
    return resolved.filterNot { message -> coreHasAcceptedUserMessage(core, message) }
}

internal fun AppUiState.withOptimisticUserMessage(message: OptimisticUserMessage): AppUiState {
    val optimistic = optimisticUserMessages.filterNot { it.commandId == message.commandId } + message
    return copy(
        core = projectOptimisticUserMessages(core, optimistic),
        optimisticUserMessages = optimistic,
    )
}

internal fun canDispatchMessage(state: AppUiState, text: String): Boolean {
    val codexId = state.openCodexId ?: return false
    val conversation = state.core.conversation ?: return false
    return text.isNotBlank() && conversation.codexId == codexId && !conversation.running &&
        state.optimisticUserMessages.none { it.codexId == codexId && it.acceptedTurnId.isBlank() }
}

internal fun projectOptimisticUserMessages(
    core: CoreState,
    messages: List<OptimisticUserMessage>,
): CoreState {
    val conversation = core.conversation ?: return core
    val cleanedTurns = conversation.turns.mapNotNull { turn ->
        val items = turn.items.filterNot { it.itemId.startsWith(OptimisticUserItemPrefix) }
        val legacyMessages = turn.messages.filterNot { it.itemId.startsWith(OptimisticUserItemPrefix) }
        if (turn.turnId.startsWith(OptimisticTurnPrefix) && items.isEmpty() && legacyMessages.isEmpty()) null
        else turn.copy(items = items, messages = legacyMessages)
    }
    val relevant = messages.filter { it.codexId == conversation.codexId }
    if (relevant.isEmpty()) return core.copy(conversation = conversation.copy(turns = cleanedTurns))

    val useTypedItems = cleanedTurns.any { it.items.isNotEmpty() }
    val projectedTurns = cleanedTurns.toMutableList()
    relevant.forEach { message ->
        val turnId = message.acceptedTurnId.ifBlank { OptimisticTurnPrefix + message.commandId }
        val itemId = OptimisticUserItemPrefix + message.commandId
        val item = ConversationItem(
            itemId = itemId,
            turnId = turnId,
            type = "user_message",
            status = "started",
            userMessage = UserMessageItem(listOf(message.text), message.text),
            provenance = "PROVENANCE_KIND_LIVE_WIRE",
        )
        val legacyMessage = ConversationMessage(itemId, "user", message.text, "started")
        val existingTurnIndex = projectedTurns.indexOfFirst { it.turnId == turnId }
        if (existingTurnIndex >= 0) {
            val turn = projectedTurns[existingTurnIndex]
            projectedTurns[existingTurnIndex] = if (useTypedItems) {
                turn.copy(items = turn.items + item)
            } else {
                turn.copy(messages = turn.messages + legacyMessage)
            }
        } else {
            projectedTurns += ConversationTurn(
                turnId = turnId,
                status = "running",
                failure = "",
                startedAtUnixMs = message.createdAtUnixMs,
                completedAtUnixMs = 0,
                items = if (useTypedItems) listOf(item) else emptyList(),
                messages = if (useTypedItems) emptyList() else listOf(legacyMessage),
                provenance = "PROVENANCE_KIND_LIVE_WIRE",
            )
        }
    }
    val hasUnresolvedSend = relevant.any { it.acceptedTurnId.isBlank() }
    val displayPhase = if (hasUnresolvedSend && core.phase == "ready" && !conversation.running) {
        "starting_turn"
    } else core.phase
    return core.copy(
        phase = displayPhase,
        conversation = conversation.copy(turns = projectedTurns),
    )
}

private fun coreHasAcceptedUserMessage(core: CoreState, message: OptimisticUserMessage): Boolean {
    if (message.acceptedTurnId.isBlank()) return false
    val conversation = core.conversation?.takeIf { it.codexId == message.codexId } ?: return false
    val turn = conversation.turns.firstOrNull { it.turnId == message.acceptedTurnId } ?: return false
    return turn.items.any { it.type == "user_message" && !it.itemId.startsWith(OptimisticUserItemPrefix) } ||
        turn.messages.any { it.role == "user" && !it.itemId.startsWith(OptimisticUserItemPrefix) }
}

private const val OptimisticTurnPrefix = "optimistic-turn-"
private const val OptimisticUserItemPrefix = "optimistic-user-"

internal fun draftForCodex(drafts: Map<String, String>, codexId: String?): String =
    codexId?.let(drafts::get).orEmpty()

internal fun persistedCodexToRestore(
    state: AppUiState,
    persistedCodexId: String?,
    preferencesLoaded: Boolean,
    attemptedCodexIds: Set<String>,
): String? {
    val codexId = persistedCodexId?.takeIf { it.isNotBlank() } ?: return null
    return codexId.takeIf {
        preferencesLoaded && state.openCodexId == null && !state.projectDialogOpen &&
            state.core.phase == "ready" && state.core.codexes.any { codex -> codex.id == codexId } &&
            codexId !in attemptedCodexIds
    }
}

internal fun authoritativeForgottenCodex(
    current: AppUiState,
    persistedSelectedCodexId: String?,
    decoded: CoreState,
): String? {
    val expectedCodexId = current.openCodexId ?: persistedSelectedCodexId ?: return null
    return expectedCodexId.takeIf {
        decoded.phase == "ready" && decoded.codexes.none { codex -> codex.id == expectedCodexId }
    }
}

internal fun shouldApplySendDraftResolution(resolution: SendDraftResolution, latestDraftVersion: Long): Boolean =
    resolution.draftVersion == latestDraftVersion

internal fun draftPersistenceAfterSendResolution(
    resolution: SendDraftResolution,
    latestDraftVersion: Long,
): String? = if (shouldApplySendDraftResolution(resolution, latestDraftVersion)) {
    if (resolution.accepted) "" else resolution.originalDraft
} else null

internal fun restoredDraftAfterSendFailure(
    currentDraft: String,
    openCodexId: String?,
    resolution: SendDraftResolution,
    latestDraftVersion: Long,
): String = if (
    !resolution.accepted && openCodexId == resolution.codexId && currentDraft.isEmpty() &&
    shouldApplySendDraftResolution(resolution, latestDraftVersion)
) resolution.originalDraft else currentDraft

internal fun effectiveClientVersion(installedVersionName: String?): String =
    installedVersionName?.trim().orEmpty().ifBlank { "unknown" }

internal class CoreErrorLogDeduplicator {
    private var wasErrorPhase = false
    private var lastErrorText = ""

    @Synchronized
    fun newLogMessage(state: CoreState): String? {
        val error = state.error.trim()
        val isErrorPhase = state.phase == "error"
        val enteredErrorPhase = isErrorPhase && !wasErrorPhase
        val errorTextChanged = error != lastErrorText
        wasErrorPhase = isErrorPhase
        lastErrorText = error
        if (!enteredErrorPhase && !(errorTextChanged && error.isNotBlank())) return null
        return if (error.isBlank()) {
            "MobileCore entered error phase without details"
        } else {
            "MobileCore error: ${redactCoreErrorForLog(error)}"
        }
    }
}

internal fun redactCoreErrorForLog(error: String): String {
    val withoutTailscaleKeys = error.replace(Regex("(?i)tskey-[a-z0-9_-]+"), "[REDACTED]")
    return withoutTailscaleKeys.replace(
        Regex("(?i)(auth[_-]?key[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'}]+"),
        "$1[REDACTED]",
    )
}

internal fun diagnosticCommandDetails(command: JSONObject, stage: String): String {
    val payload = command.optJSONObject("payload")
    val fields = mutableListOf(
        "commandId=${diagnosticField(command.optString("id"))}",
        "action=${diagnosticField(command.optString("type"))}",
        "stage=${diagnosticField(stage)}",
    )
    listOf(
        "cwd", "parentPath", "relativeDirectory", "relativePath", "destinationPath",
        "codexId", "sessionId", "approvalId", "requestId",
    ).forEach { key ->
        payload?.optString(key)?.takeIf { it.isNotBlank() }?.let { fields += "$key=${diagnosticField(it)}" }
    }
    if (payload?.has("createDirectoryIfMissing") == true) {
        fields += "createDirectoryIfMissing=${payload.optBoolean("createDirectoryIfMissing")}"
    }
    val byteCount = when (command.optString("type")) {
        "write_workspace_text_file" -> payload?.optString("utf8Text")?.toByteArray(Charsets.UTF_8)?.size
        "upload_workspace_entry" -> payload?.optString("contentBase64")?.let(::base64DecodedByteCount)
        else -> null
    }
    if (byteCount != null) fields += "byteCount=$byteCount"
    return fields.joinToString("; ")
}

internal data class DiagnosticTrackedAction(
    val commandId: String,
    val action: String,
    val stage: String,
    val requestId: String = "",
)

internal class DiagnosticActionTracker {
    private val actions = ConcurrentHashMap<String, DiagnosticTrackedAction>()

    fun register(command: JSONObject, stage: String) {
        val commandId = command.optString("id")
        if (commandId.isBlank()) return
        val payload = command.optJSONObject("payload")
        actions[commandId] = DiagnosticTrackedAction(
            commandId = commandId,
            action = command.optString("type"),
            stage = stage,
            requestId = payload?.optString("requestId").orEmpty()
                .ifBlank { payload?.optString("approvalId").orEmpty() },
        )
    }

    fun consumeTerminal(state: CoreState, current: AppUiState): DiagnosticTrackedAction? {
        val tracked = actions[state.commandId] ?: return null
        if (!isDiagnosticActionTerminal(state, current, tracked)) return null
        return if (actions.remove(state.commandId, tracked)) tracked else null
    }
}

internal fun isDiagnosticActionTerminal(
    state: CoreState,
    current: AppUiState,
    tracked: DiagnosticTrackedAction,
): Boolean = when (tracked.stage) {
    "workspace" -> state.workspace?.loading == "none"
    "pending.response" -> {
        if (state.error.isNotBlank()) true else {
            val request = state.conversation?.pendingRequests?.find { it.requestId == tracked.requestId }
            state.conversation != null && (request == null || request.resolved || request.error != null)
        }
    }
    "project.list_directories" ->
        current.pendingDirectoryCommandId == tracked.commandId &&
            (state.error.isNotBlank() || (state.phase == "ready" && state.directoryListing != null))
    "project.list_sessions" ->
        current.pendingSessionCandidatesCommandId == tracked.commandId &&
            (state.error.isNotBlank() || (state.phase == "ready" && state.sessionCandidates != null))
    "project.mutation", "project.selection" ->
        projectCommandResolution(current, state).outcome in setOf(
            ProjectCommandOutcome.SELECT_REQUIRED,
            ProjectCommandOutcome.SUCCESS,
            ProjectCommandOutcome.FAILURE,
        )
    else -> false
}

internal fun diagnosticResultDetails(
    state: CoreState,
    tracked: DiagnosticTrackedAction,
    current: AppUiState = AppUiState(),
): String = buildList {
    add("commandId=${diagnosticField(state.commandId)}")
    add("action=${diagnosticField(tracked.action)}")
    add("result=${diagnosticActionOutcome(state, tracked, current)}")
    add("revision=${state.revision}")
    val resultByteCount = when (tracked.action) {
        "read_workspace_text_file" -> state.workspace?.openFile?.entry?.sizeBytes
        "write_workspace_text_file" -> state.workspace?.lastWrite?.entry?.sizeBytes
        "upload_workspace_entry" -> state.workspace?.uploadResult?.entry?.sizeBytes
        "download_workspace_entry" -> state.workspace?.downloadResult?.entry?.sizeBytes
        else -> null
    }
    if (resultByteCount != null) add("byteCount=$resultByteCount")
    if (state.error.isNotBlank()) {
        add("error=${diagnosticField(DiagnosticRedactor.redact(redactCoreErrorForLog(state.error)))}")
    }
    state.workspace?.error?.let { error ->
        add("errorCode=${diagnosticField(error.code)}")
        if (error.message.isNotBlank()) add("error=${diagnosticField(DiagnosticRedactor.redact(error.message))}")
    }
    if (tracked.stage == "pending.response") {
        state.conversation?.pendingRequests?.find { it.requestId == tracked.requestId }?.error?.let { error ->
            add("errorCode=${diagnosticField(error.code)}")
            if (error.message.isNotBlank()) add("error=${diagnosticField(DiagnosticRedactor.redact(error.message))}")
        }
    }
}.joinToString("; ")

internal fun diagnosticActionOutcome(
    state: CoreState,
    tracked: DiagnosticTrackedAction,
    current: AppUiState,
): String = when (tracked.stage) {
    "workspace" -> if (state.workspace?.error != null || state.error.isNotBlank()) "error" else "success"
    "pending.response" -> {
        val requestError = state.conversation?.pendingRequests
            ?.find { it.requestId == tracked.requestId }?.error
        if (requestError != null || state.error.isNotBlank()) "error" else "success"
    }
    "project.list_directories", "project.list_sessions" ->
        if (state.error.isNotBlank()) "error" else "success"
    "project.mutation", "project.selection" ->
        if (projectCommandResolution(current, state).outcome == ProjectCommandOutcome.FAILURE) "error" else "success"
    else -> if (state.phase == "error" || state.error.isNotBlank()) "error" else "success"
}

private fun diagnosticField(value: String): String =
    value.replace(Regex("[\\r\\n;]+"), " ").trim().take(512)

private fun base64DecodedByteCount(value: String): Int {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return 0
    val padding = trimmed.takeLast(2).count { it == '=' }
    return (trimmed.length * 3 / 4 - padding).coerceAtLeast(0)
}

internal fun initialProjectPath(state: AppUiState): String {
    normalizeStoredProjectPath(state.lastProjectPath).takeIf { it.isNotBlank() }?.let { return it }
    state.core.codexes.firstOrNull { it.id == state.openCodexId }?.cwd?.trim()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    state.core.codexes.firstOrNull { it.id == state.core.selectedCodexId }?.cwd?.trim()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    return state.core.codexes.firstNotNullOfOrNull { codex -> codex.cwd.trim().takeIf { it.isNotBlank() } } ?: "/"
}

internal fun successfulProjectPath(current: AppUiState, decoded: CoreState): String? {
    val selectedCwd = decoded.codexes.firstOrNull { it.id == decoded.selectedCodexId }?.cwd.orEmpty()
    return projectPathToPersist(true, selectedCwd, current.projectPath)
}

internal const val ProjectStageMutation = "mutation"
internal const val ProjectStageSelection = "selection"

internal fun isMissingDirectoryError(error: String): Boolean =
    error.contains("directory does not exist", ignoreCase = true)

internal data class MissingDirectoryCancelDecision(
    val nextState: AppUiState,
    val refreshCore: Boolean,
)

internal fun resolveMissingDirectoryCancel(state: AppUiState): MissingDirectoryCancelDecision =
    MissingDirectoryCancelDecision(
        nextState = state.copy(
            missingDirectoryConfirmationPath = "",
            projectError = "",
        ),
        refreshCore = state.missingDirectoryConfirmationPath.isNotBlank() &&
        state.missingDirectoryConfirmationPath == state.projectPath.trim() &&
        state.core.phase == "error" &&
            isMissingDirectoryError(state.core.error),
    )

internal fun shouldConfirmMissingDirectory(
    current: AppUiState,
    decoded: CoreState,
    resolution: ProjectCommandResolution = projectCommandResolution(current, decoded),
): Boolean = resolution.outcome == ProjectCommandOutcome.FAILURE &&
    current.pendingProjectCommandStage == ProjectStageMutation &&
    current.pendingProjectAction == "create_codex" &&
    decoded.commandId == current.pendingProjectCommandId &&
    isMissingDirectoryError(decoded.error)

internal enum class ProjectCommandOutcome { NONE, SELECT_REQUIRED, SUCCESS, FAILURE }

internal data class ProjectCommandResolution(
    val outcome: ProjectCommandOutcome,
    val codexId: String = "",
)

internal fun projectCommandResolution(current: AppUiState, decoded: CoreState): ProjectCommandResolution {
    val matches = current.projectDialogOpen && current.pendingProjectCommandId.isNotBlank() &&
        decoded.commandId == current.pendingProjectCommandId
    if (!matches) return ProjectCommandResolution(ProjectCommandOutcome.NONE)
    if (decoded.error.isNotBlank()) return ProjectCommandResolution(ProjectCommandOutcome.FAILURE)
    return when {
        current.pendingProjectCommandStage == ProjectStageMutation &&
            decoded.phase == "ready" && decoded.selectedCodexId.isNotBlank() ->
            ProjectCommandResolution(ProjectCommandOutcome.SELECT_REQUIRED, decoded.selectedCodexId)
        current.pendingProjectCommandStage == ProjectStageSelection &&
            decoded.phase == "ready" && current.pendingProjectCodexId.isNotBlank() &&
            decoded.selectedCodexId == current.pendingProjectCodexId &&
            decoded.conversation?.codexId == current.pendingProjectCodexId ->
            ProjectCommandResolution(ProjectCommandOutcome.SUCCESS, current.pendingProjectCodexId)
        else -> ProjectCommandResolution(ProjectCommandOutcome.NONE)
    }
}

internal fun projectPathToPersist(
    successfulProjectCommand: Boolean,
    selectedCwd: String,
    requestedPath: String,
): String? = if (!successfulProjectCommand) null else
    sequenceOf(selectedCwd, requestedPath)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && it != LegacyDefaultProjectPath }

internal data class DirectoryPathResolution(val path: String, val pendingCommandId: String)

internal fun resolveDirectoryResult(
    currentPath: String,
    pendingCommandId: String,
    decoded: CoreState,
): DirectoryPathResolution {
    val completed = pendingCommandId.isNotBlank() && decoded.commandId == pendingCommandId &&
        (decoded.phase == "ready" || decoded.error.isNotBlank())
    if (!completed) return DirectoryPathResolution(currentPath, pendingCommandId)
    val hostPath = decoded.directoryListing?.parentPath
        ?.takeIf { decoded.error.isBlank() && it.isNotBlank() }
    return DirectoryPathResolution(hostPath ?: currentPath, "")
}

internal fun activeConversation(state: AppUiState): ConversationState? =
    state.core.conversation?.takeIf { it.codexId == state.openCodexId }

internal class ProjectOperationSingleFlight {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}

internal const val ForegroundBackgroundThresholdMs = 10_000L

internal class ForegroundResumeGate(
    private val thresholdMs: Long = ForegroundBackgroundThresholdMs,
) {
    private var hasStarted = false
    private var backgroundedAtMs: Long? = null

    @Synchronized
    fun onStarted(nowMs: Long): Boolean {
        if (!hasStarted) {
            hasStarted = true
            return false
        }
        val stoppedAt = backgroundedAtMs ?: return false
        backgroundedAtMs = null
        return nowMs >= stoppedAt && nowMs - stoppedAt >= thresholdMs
    }

    @Synchronized
    fun onStopped(nowMs: Long, changingConfigurations: Boolean) {
        if (hasStarted && !changingConfigurations) backgroundedAtMs = nowMs
    }
}

internal enum class ForegroundRecoveryAction { NONE, REFRESH, CONNECT }

internal fun foregroundRecoveryStartingState(state: AppUiState): AppUiState = state.copy(
    foregroundRecoveryInProgress = true,
    foregroundRecoveryError = "",
    conversationPage = ConversationPage.CONVERSATION,
    workspaceEditorOpen = false,
)

internal sealed interface ForegroundRecoveryResolution {
    data object None : ForegroundRecoveryResolution
    data object Reconnect : ForegroundRecoveryResolution
    data class SelectCodex(val codexId: String) : ForegroundRecoveryResolution
    data class Finished(val codexId: String?) : ForegroundRecoveryResolution
    data class Failed(val message: String) : ForegroundRecoveryResolution
}

internal class ForegroundRecoveryTracker {
    private var originalCodexId: String? = null
    private var recoveryCommandId = ""
    private var selectionCommandId = ""
    private var recoveryIsRefresh = false
    private var waitingToTrackReconnect = false
    private var waitingToTrackSelection = false

    @Synchronized
    fun beginRefresh(recoveryCommandId: String, originalCodexId: String?) {
        begin(recoveryCommandId, originalCodexId, isRefresh = true)
    }

    @Synchronized
    fun beginReconnect(recoveryCommandId: String, originalCodexId: String?) {
        begin(recoveryCommandId, originalCodexId, isRefresh = false)
    }

    private fun begin(recoveryCommandId: String, originalCodexId: String?, isRefresh: Boolean) {
        this.recoveryCommandId = recoveryCommandId
        this.originalCodexId = originalCodexId
        recoveryIsRefresh = isRefresh
        selectionCommandId = ""
        waitingToTrackReconnect = false
        waitingToTrackSelection = false
    }

    @Synchronized
    fun trackReconnect(commandId: String) {
        if (waitingToTrackReconnect) {
            recoveryCommandId = commandId
            recoveryIsRefresh = false
            waitingToTrackReconnect = false
        }
    }

    @Synchronized
    fun trackSelection(commandId: String) {
        if (waitingToTrackSelection) {
            selectionCommandId = commandId
            waitingToTrackSelection = false
        }
    }

    @Synchronized
    fun onCoreState(state: CoreState, reconnectAvailable: Boolean = true): ForegroundRecoveryResolution {
        if (
            recoveryCommandId.isBlank() && selectionCommandId.isBlank() &&
            !waitingToTrackReconnect && !waitingToTrackSelection
        ) {
            return ForegroundRecoveryResolution.None
        }
        if (state.commandId == recoveryCommandId) {
            if (state.phase == "error" || state.error.isNotBlank()) {
                if (recoveryIsRefresh && reconnectAvailable) {
                    recoveryCommandId = ""
                    recoveryIsRefresh = false
                    waitingToTrackReconnect = true
                    return ForegroundRecoveryResolution.Reconnect
                }
                return fail()
            }
            if (state.phase != "ready") return ForegroundRecoveryResolution.None
            recoveryCommandId = ""
            val codexId = originalCodexId
            if (codexId == null) {
                clear()
                return ForegroundRecoveryResolution.Finished(null)
            }
            waitingToTrackSelection = true
            return ForegroundRecoveryResolution.SelectCodex(codexId)
        }
        if (state.commandId == selectionCommandId) {
            if (state.phase == "error" || state.error.isNotBlank()) {
                return fail()
            }
            if (state.phase != "ready") return ForegroundRecoveryResolution.None
            val expectedCodexId = originalCodexId
            return if (expectedCodexId != null && state.conversation?.codexId == expectedCodexId) {
                clear()
                ForegroundRecoveryResolution.Finished(expectedCodexId)
            } else {
                fail()
            }
        }
        return ForegroundRecoveryResolution.None
    }

    private fun fail(): ForegroundRecoveryResolution.Failed {
        clear()
        return ForegroundRecoveryResolution.Failed("连接已失效，请手动重连")
    }

    private fun clear() {
        originalCodexId = null
        recoveryCommandId = ""
        selectionCommandId = ""
        recoveryIsRefresh = false
        waitingToTrackReconnect = false
        waitingToTrackSelection = false
    }
}

internal fun foregroundRecoveryAction(state: AppUiState): ForegroundRecoveryAction {
    if (foregroundRecoveryBlocked(state)) return ForegroundRecoveryAction.NONE
    return when (state.core.phase) {
        "ready" -> ForegroundRecoveryAction.REFRESH
        "idle", "stopped", "configured", "disconnected" ->
            if (state.hostAddress.isNotBlank()) ForegroundRecoveryAction.CONNECT else ForegroundRecoveryAction.NONE
        else -> ForegroundRecoveryAction.NONE
    }
}

private fun foregroundRecoveryBlocked(state: AppUiState): Boolean {
    val conversation = state.core.conversation
    val pendingInteraction = conversation?.pendingRequests.orEmpty().any { request ->
        !request.resolved && (request.type == "approval" || request.type == "user_input")
    }
    val pendingUiOperation = state.stoppingTurn || state.pendingDirectoryCommandId.isNotBlank() ||
        state.pendingSessionCandidatesCommandId.isNotBlank() || state.pendingProjectCommandId.isNotBlank() ||
        state.pendingWorkspaceGetCommandId.isNotBlank() || state.pendingWorkspaceReadCommandId.isNotBlank() ||
        state.pendingWorkspaceUploadCommandId.isNotBlank() || state.pendingWorkspaceDownloadCommandId.isNotBlank() ||
        state.workspaceLocalTransferStatus != "none" || state.submittingRequestIds.isNotEmpty()
    return state.foregroundRecoveryInProgress || conversation?.running == true || pendingInteraction || pendingUiOperation ||
        state.core.phase in ForegroundRecoveryBusyPhases
}

private val ForegroundRecoveryBusyPhases = setOf(
    "starting_tailnet", "auth_required", "connecting_host", "refreshing",
    "loading_conversation", "starting_turn", "polling_turn", "interrupting_turn",
    "loading_directories", "loading_session_candidates", "creating_codex", "importing_session",
    "renaming_codex", "unmanaging_codex", "forgetting_codex",
)

internal fun shouldAcceptCoreRevision(currentRevision: Long, incomingRevision: Long): Boolean =
    incomingRevision >= currentRevision

internal fun connectCommands(configurePayload: JSONObject): List<JSONObject> = listOf(
    coreCommand("stop"),
    coreCommand("configure", configurePayload),
    coreCommand("start"),
)

internal fun coreCommand(type: String, payload: JSONObject? = null): JSONObject =
    JSONObject()
        .put("version", 1)
        .put("id", UUID.randomUUID().toString())
        .put("type", type)
        .apply { if (payload != null) put("payload", payload) }

internal fun createCodexCommand(cwd: String, createDirectoryIfMissing: Boolean): JSONObject =
    coreCommand(
        "create_codex",
        JSONObject().put("cwd", cwd).put("createDirectoryIfMissing", createDirectoryIfMissing),
    )

internal fun respondApprovalCommand(approvalId: String, decision: String) =
    coreCommand(
        "respond_approval",
        JSONObject().put("approvalId", approvalId).put("decision", decision),
    )

internal fun canRespondApproval(request: PendingRequest, decision: String): Boolean =
    request.type == "approval" && request.status == "pending" &&
        !request.inFlight && decision in request.allowedDecisions

internal fun canSubmitApproval(
    request: PendingRequest,
    decision: String,
    submittingRequestIds: Set<String>,
): Boolean = request.requestId !in submittingRequestIds && canRespondApproval(request, decision)

internal fun respondUserInputCommand(
    request: PendingRequest,
    drafts: Map<String, UserInputAnswerDraft>,
): JSONObject = coreCommand(
    "respond_user_input",
    JSONObject()
        .put("requestId", request.requestId)
        .put(
            "answers",
            JSONArray().apply {
                request.questions.forEach { question ->
                    val answer = drafts[question.questionId] ?: UserInputAnswerDraft()
                    val validOptions = question.options.map { it.optionId }.toSet()
                    put(
                        JSONObject()
                            .put("questionId", question.questionId)
                            .put(
                                "selectedOptionIds",
                                JSONArray(
                                    answer.selectedOptionIds.filter { it in validOptions }.sorted(),
                                ),
                            )
                            .put("freeFormText", if (question.allowsFreeForm) answer.freeFormText else ""),
                    )
                }
            },
        ),
)

internal fun isUserInputAnswerValid(
    question: UserInputQuestion,
    answer: UserInputAnswerDraft,
): Boolean {
    val validOptions = question.options.map { it.optionId }.toSet()
    val selected = answer.selectedOptionIds.filter { it in validOptions }
    if (!question.allowsMultiple && selected.size > 1) return false
    return selected.isNotEmpty() || (question.allowsFreeForm && answer.freeFormText.isNotBlank())
}

internal fun areUserInputAnswersComplete(
    request: PendingRequest,
    drafts: Map<String, UserInputAnswerDraft>,
): Boolean = request.type == "user_input" && !request.resolved && request.questions.isNotEmpty() &&
    request.questions.all { isUserInputAnswerValid(it, drafts[it.questionId] ?: UserInputAnswerDraft()) }

internal fun canSubmitUserInput(
    request: PendingRequest,
    drafts: Map<String, UserInputAnswerDraft>,
    submittingRequestIds: Set<String>,
): Boolean = request.requestId !in submittingRequestIds && !request.inFlight &&
    areUserInputAnswersComplete(request, drafts)

internal fun pendingErrorDescription(error: PendingError?): String = when (error?.code?.lowercase()) {
    null, "" -> ""
    "not_found", "request_not_found", "already_resolved", "approval_already_resolved",
    "user_input_already_resolved" -> "请求已处理或不存在，请刷新后重试"
    "invalid_decision", "invalid_answer", "invalid_request" -> "提交内容无效，请检查后重试"
    "busy", "in_flight" -> "请求正在处理中，请稍候"
    "operation_failed" -> "提交失败，请重试"
    else -> "提交失败，请重试"
}

internal fun workspaceGetCommand(codexId: String) =
    coreCommand("get_workspace", JSONObject().put("codexId", codexId))

internal fun workspaceListCommand(codexId: String, relativeDirectory: String) =
    coreCommand(
        "list_workspace_entries",
        JSONObject().put("codexId", codexId).put("relativeDirectory", relativeDirectory),
    )

internal fun workspaceReadCommand(codexId: String, relativePath: String) =
    coreCommand(
        "read_workspace_text_file",
        JSONObject().put("codexId", codexId).put("relativePath", relativePath),
    )

internal fun workspaceWriteCommand(
    codexId: String,
    relativePath: String,
    utf8Text: String,
    expectedRevision: String,
    expectedQuiescenceToken: String,
) = coreCommand(
    "write_workspace_text_file",
    JSONObject()
        .put("codexId", codexId)
        .put("relativePath", relativePath)
        .put("utf8Text", utf8Text)
        .put("condition", "replace_only")
        .put("expectedRevision", expectedRevision)
        .put("expectedQuiescenceToken", expectedQuiescenceToken),
)

internal fun workspaceUploadCommand(
    codexId: String,
    destinationPath: String,
    kind: String,
    contentBase64: String,
    expectedQuiescenceToken: String,
) = coreCommand(
    "upload_workspace_entry",
    JSONObject()
        .put("codexId", codexId)
        .put("destinationPath", destinationPath)
        .put("kind", kind)
        .put("contentBase64", contentBase64)
        .put("expectedQuiescenceToken", expectedQuiescenceToken),
)

internal fun workspaceDownloadCommand(codexId: String, relativePath: String) = coreCommand(
    "download_workspace_entry",
    JSONObject().put("codexId", codexId).put("relativePath", relativePath),
)

internal fun canSaveWorkspaceFile(entry: WorkspaceEntry, access: WorkspaceAccessState): Boolean =
    entry.textEditable && entry.revision.isNotBlank() &&
        access.mutationStatus.equals("allowed", ignoreCase = true) && access.quiescenceToken.isNotBlank()

internal fun workspaceTransferIsIdle(state: AppUiState): Boolean =
    state.workspaceLocalTransferStatus == "none" && state.pendingWorkspaceUploadCommandId.isBlank() &&
        state.pendingWorkspaceDownloadCommandId.isBlank()

internal fun workspaceTransferContextMatches(
    state: AppUiState,
    codexId: String,
    directory: String,
    path: String,
    entryKind: String,
    uploadKind: String,
): Boolean = state.workspaceTransferCodexId == codexId && state.workspaceTransferDirectory == directory &&
    (path.isBlank() || state.workspaceTransferPath == path) &&
    (entryKind.isBlank() || state.workspaceTransferEntryKind == entryKind) &&
    (uploadKind.isBlank() || state.workspaceTransferUploadKind == uploadKind)

internal fun clearWorkspaceTransferContext(state: AppUiState): AppUiState = state.copy(
    workspaceLocalTransferStatus = "none",
    workspaceTransferCodexId = "",
    workspaceTransferDirectory = "",
    workspaceTransferPath = "",
    workspaceTransferEntryKind = "",
    workspaceTransferUploadKind = "",
    workspaceUploadRecoveryPartial = false,
)

internal fun workspaceLocalTransferStatusDescription(status: String): String = when (status) {
    "choosing_upload" -> "正在选择上传文件…"
    "reading_upload" -> "正在读取本地文件…"
    "uploading" -> "正在上传文件…"
    "recovering_upload" -> "正在刷新项目文件状态…"
    "choosing_download" -> "正在选择保存位置…"
    "downloading" -> "正在从 Host 下载…"
    "writing_download" -> "正在写入本地文档…"
    else -> ""
}

internal fun workspaceErrorDescription(error: WorkspaceError?): String = when (error?.code?.lowercase()) {
    null, "" -> ""
    "conflict", "revision_conflict", "workspace_revision_conflict", "precondition_failed" -> "文件已被其他操作修改，请重新打开后再保存"
    "busy", "workspace_busy", "not_quiescent" -> "Codex 正在使用项目文件，暂时无法保存"
    "unsupported", "not_supported", "capability_not_supported" -> "Host 不支持项目文件"
    "too_large", "file_too_large", "workspace_text_too_large" -> "文件过大，无法在手机端打开"
    "not_found", "workspace_entry_not_found" -> "文件或目录不存在"
    "not_text", "binary_file", "workspace_entry_type_unsupported" -> "该文件不是可查看或编辑的文本文件"
    "invalid_request" -> "项目文件请求无效"
    "operation_failed" -> "项目文件操作失败，请重试"
    "not_editable", "permission_denied" -> "该文件当前不可编辑"
    else -> "项目文件操作失败，请重试"
}

internal fun workspaceTransferErrorDescription(error: WorkspaceError?): String = when (error?.code?.lowercase()) {
    null, "" -> ""
    "workspace_busy" -> "Codex 正在使用项目文件，请稍后重试"
    "capability_not_supported" -> "Host 不支持文件传输"
    "path_invalid", "path_outside_root" -> "目标路径无效或超出项目目录"
    "entry_not_found" -> "要下载的文件或目录不存在"
    "entry_type_unsupported" -> "该文件类型不支持传输"
    "upload_too_large" -> "上传文件超过 Host 大小上限"
    "download_too_large" -> "下载内容超过 Host 大小上限"
    "archive_invalid" -> "所选 ZIP 压缩包无效"
    "archive_expanded_too_large" -> "ZIP 解压后的内容超过 Host 上限"
    "archive_too_many_entries" -> "ZIP 内文件数量超过 Host 上限"
    "invalid_request" -> "文件传输请求无效"
    "operation_failed" -> "文件传输失败，请重试"
    else -> "文件传输失败，请重试"
}
