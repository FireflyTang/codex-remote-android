package com.firefly.codexremote

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firefly.codexremote.mobilecore.Core
import com.firefly.codexremote.mobilecore.Mobilecore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

data class AppUiState(
    val hostAddress: String = DefaultHostAddress,
    val core: CoreState = CoreState(),
    val openCodexId: String? = null,
    val draft: String = "",
    val stoppingTurn: Boolean = false,
    val lastProjectPath: String = "",
    val projectDialogOpen: Boolean = false,
    val projectPath: String = "",
    val pendingDirectoryCommandId: String = "",
    val pendingProjectCommandId: String = "",
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

    private val core: Core = Mobilecore.newCore(
        AndroidPlatformAdapter(application, ::acceptCoreState),
    )
    private val networkMonitor = AndroidNetworkMonitor(application) { command ->
        acceptCoreState(core.dispatch(command))
    }
    private val clientId = "android-" + (
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        )

    init {
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
        acceptCoreState(core.state())
        networkMonitor.start()
    }

    fun setHostAddress(value: String) {
        _uiState.update { it.copy(hostAddress = value) }
        viewModelScope.launch { hostPreferences.setHostAddress(value) }
    }

    fun connect() {
        val endpoint = uiState.value.hostAddress.trim()
        if (endpoint.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            hostPreferences.setHostAddress(endpoint)
            val payload = JSONObject()
                .put("hostname", "codex-remote-android")
                .put("stateDir", File(getApplication<Application>().filesDir, "tailnet").absolutePath)
                .put("hostEndpoint", endpoint)
                .put("clientId", clientId)
                .put("clientRunId", UUID.randomUUID().toString())
                .put("clientName", "codex-remote-android")
                .put("clientVersion", "0.1.1")
            connectCommands(payload).forEach { command ->
                acceptCoreState(core.dispatch(command.toString()))
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { dispatch("refresh") }
    }

    fun openProjectDialog() {
        val path = initialProjectPath(uiState.value)
        _uiState.update {
            it.copy(
                projectDialogOpen = true,
                projectPath = path,
                pendingDirectoryCommandId = "",
                pendingProjectCommandId = "",
            )
        }
        listDirectories(path)
    }

    fun closeProjectDialog() {
        _uiState.update {
            it.copy(projectDialogOpen = false, pendingDirectoryCommandId = "", pendingProjectCommandId = "")
        }
    }

    fun setProjectPath(value: String) {
        _uiState.update { it.copy(projectPath = value) }
    }

    fun listDirectories(path: String = uiState.value.projectPath) {
        val requestedPath = path.trim()
        if (requestedPath.isEmpty()) return
        val command = coreCommand("list_directories", JSONObject().put("parentPath", requestedPath))
        _uiState.update {
            it.copy(projectPath = requestedPath, pendingDirectoryCommandId = command.getString("id"))
        }
        viewModelScope.launch(Dispatchers.IO) {
            acceptCoreState(core.dispatch(command.toString()))
        }
    }

    fun listSessionCandidates() {
        val path = uiState.value.projectPath.trim()
        if (path.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dispatch("list_session_candidates", JSONObject().put("cwd", path))
        }
    }

    fun createCodex() {
        val path = uiState.value.projectPath.trim()
        if (path.isEmpty()) return
        dispatchProjectOperation(
            "create_codex",
            JSONObject().put("cwd", path).put("createDirectoryIfMissing", true),
        )
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

    fun forgetCodex(codexId: String) = dispatchCodexAction("forget_codex", codexId)

    fun openConversation(codexId: String) {
        val managedProjectCommand = if (uiState.value.projectDialogOpen) {
            coreCommand("select_codex", JSONObject().put("codexId", codexId))
        } else null
        _uiState.update {
            it.copy(
                openCodexId = if (managedProjectCommand == null) codexId else it.openCodexId,
                pendingProjectCommandId = managedProjectCommand?.getString("id") ?: it.pendingProjectCommandId,
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
        viewModelScope.launch(Dispatchers.IO) {
            if (managedProjectCommand != null) acceptCoreState(core.dispatch(managedProjectCommand.toString()))
            else dispatch("select_codex", JSONObject().put("codexId", codexId))
        }
    }

    fun closeConversation() {
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
        _uiState.update { it.copy(draft = value) }
    }

    fun sendMessage() {
        val state = uiState.value
        val text = state.draft.trim()
        val conversation = state.core.conversation
        if (text.isEmpty() || conversation?.running == true || conversation?.codexId != state.openCodexId) return
        _uiState.update { it.copy(draft = "", stoppingTurn = false) }
        viewModelScope.launch(Dispatchers.IO) {
            dispatch("start_turn", JSONObject().put("text", text))
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
            acceptCoreState(core.dispatch(respondApprovalCommand(requestId, decision).toString()))
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
            acceptCoreState(core.dispatch(respondUserInputCommand(request, acceptedDrafts).toString()))
        }
    }

    private fun dispatch(type: String, payload: JSONObject? = null) {
        val command = coreCommand(type, payload)
        acceptCoreState(core.dispatch(command.toString()))
    }

    private fun dispatchWorkspace(command: JSONObject) {
        acceptCoreState(core.dispatch(command.toString()))
    }

    private fun dispatchProjectOperation(type: String, payload: JSONObject) {
        val command = coreCommand(type, payload)
        val commandId = command.getString("id")
        _uiState.update { it.copy(pendingProjectCommandId = commandId) }
        viewModelScope.launch(Dispatchers.IO) { acceptCoreState(core.dispatch(command.toString())) }
    }

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

    private fun acceptCoreState(raw: String) {
        val decoded = runCatching { decodeCoreState(raw) }.getOrElse { error ->
            CoreState(phase = "error", error = "无法解析 MobileCore 状态：${error.message}")
        }
        var listWorkspaceAfterGet: Pair<String, String>? = null
        var refreshWorkspaceDirectory: Pair<String, String>? = null
        var recoverWorkspaceAfterUploadFailure: Pair<String, String>? = null
        var projectPathToRemember: String? = null
        _uiState.update { current ->
            projectPathToRemember = null
            if (decoded.revision < current.core.revision) {
                current
            } else {
                val projectCommandOutcome = projectCommandOutcome(current, decoded)
                val completedProject = projectCommandOutcome == ProjectCommandOutcome.SUCCESS
                val failedProject = projectCommandOutcome == ProjectCommandOutcome.FAILURE
                if (completedProject) {
                    projectPathToRemember = successfulProjectPath(current, decoded)
                }
                val directoryResolution = resolveDirectoryResult(
                    current.projectPath, current.pendingDirectoryCommandId, decoded,
                )
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
                var next = current.copy(
                    core = decoded,
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
                    projectPath = directoryResolution.path,
                    pendingDirectoryCommandId = directoryResolution.pendingCommandId,
                    pendingProjectCommandId = if (completedProject || failedProject) "" else current.pendingProjectCommandId,
                    openCodexId = if (completedProject) decoded.selectedCodexId else current.openCodexId,
                    stoppingTurn = current.stoppingTurn && decoded.conversation?.running == true && decoded.error.isBlank(),
                    pendingWorkspaceGetCommandId = if (getCompleted) "" else current.pendingWorkspaceGetCommandId,
                    pendingWorkspaceRefreshDirectory = if (getCompleted) "" else current.pendingWorkspaceRefreshDirectory,
                    pendingWorkspaceReadCommandId = if (fileCompleted || fileFailed) "" else current.pendingWorkspaceReadCommandId,
                    pendingWorkspaceFilePath = if (fileCompleted || fileFailed) "" else pendingPath,
                    workspaceEditorOpen = if (fileCompleted) true else current.workspaceEditorOpen,
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
                )
                if (uploadSucceeded || downloadFailed || uploadRecoveryCompleted) next = clearWorkspaceTransferContext(next)
                next
            }
        }
        projectPathToRemember?.let(::rememberProjectPath)
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
        networkMonitor.close()
        core.close()
        super.onCleared()
    }
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

internal enum class ProjectCommandOutcome { NONE, SUCCESS, FAILURE }

internal fun projectCommandOutcome(current: AppUiState, decoded: CoreState): ProjectCommandOutcome {
    val matches = current.projectDialogOpen && current.pendingProjectCommandId.isNotBlank() &&
        decoded.commandId == current.pendingProjectCommandId
    if (!matches) return ProjectCommandOutcome.NONE
    return when {
        decoded.phase == "ready" && decoded.selectedCodexId.isNotBlank() -> ProjectCommandOutcome.SUCCESS
        decoded.error.isNotBlank() -> ProjectCommandOutcome.FAILURE
        else -> ProjectCommandOutcome.NONE
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
