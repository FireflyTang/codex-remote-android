package com.firefly.codexremote

import org.json.JSONObject

data class CodexSummary(
    val id: String,
    val title: String,
    val cwd: String,
    val status: String,
    val managementState: String = "",
)

data class DirectoryEntry(val name: String = "", val path: String = "")
data class DirectoryListing(
    val parentPath: String = "",
    val directories: List<DirectoryEntry> = emptyList(),
)

data class SessionCandidate(
    val sessionId: String = "",
    val cwd: String = "",
    val title: String = "",
    val preview: String = "",
    val source: String = "",
    val availability: String = "",
    val managedCodexId: String = "",
)

data class SessionCandidates(
    val normalizedCwd: String = "",
    val sessions: List<SessionCandidate> = emptyList(),
)

data class WorkspaceLimits(
    val maxTextFileBytes: Long = 0,
    val maxInlineUploadBytes: Long = 0,
    val maxInlineDownloadBytes: Long = 0,
    val maxArchiveExpandedBytes: Long = 0,
    val maxArchiveEntryCount: Long = 0,
)

data class WorkspaceAccessState(
    val mutationStatus: String = "",
    val activeAgentCount: Long = 0,
    val quiescenceToken: String = "",
    val observedAtUnixMs: Long = 0,
    val generation: Long = 0,
)

data class WorkspaceEntry(
    val relativePath: String = "",
    val name: String = "",
    val kind: String = "",
    val sizeBytes: Long = 0,
    val modifiedAtUnixMs: Long = 0,
    val revision: String = "",
    val textViewable: Boolean = false,
    val textEditable: Boolean = false,
)

data class WorkspaceDirectory(
    val relativeDirectory: String = "",
    val entries: List<WorkspaceEntry> = emptyList(),
)

data class WorkspaceOpenFile(
    val entry: WorkspaceEntry = WorkspaceEntry(),
    val utf8Text: String = "",
)

data class WorkspaceLastWrite(
    val entry: WorkspaceEntry = WorkspaceEntry(),
    val deduplicated: Boolean = false,
)

data class WorkspaceUploadResult(
    val entry: WorkspaceEntry = WorkspaceEntry(),
    val deduplicated: Boolean = false,
)

data class WorkspaceDownloadResult(
    val entry: WorkspaceEntry = WorkspaceEntry(),
    val kind: String = "",
    val filename: String = "",
    val contentBase64: String = "",
)

data class WorkspaceError(val code: String = "", val message: String = "")

data class WorkspaceState(
    val supported: Boolean = false,
    val limits: WorkspaceLimits = WorkspaceLimits(),
    val codexId: String = "",
    val workspaceRoot: String = "",
    val accessState: WorkspaceAccessState = WorkspaceAccessState(),
    val currentDirectory: WorkspaceDirectory? = null,
    val openFile: WorkspaceOpenFile? = null,
    val lastWrite: WorkspaceLastWrite? = null,
    val uploadResult: WorkspaceUploadResult? = null,
    val downloadResult: WorkspaceDownloadResult? = null,
    val loading: String = "none",
    val error: WorkspaceError? = null,
)

data class ConversationMessage(
    val itemId: String,
    val role: String,
    val text: String,
    val status: String,
)

data class ItemCompleteness(
    val truncated: Boolean = false,
    val incomplete: Boolean = false,
    val originalSizeBytes: Long = 0,
    val reason: String = "",
)

data class UserMessageItem(val textParts: List<String>, val text: String)
data class AgentMessageItem(val text: String)
data class ReasoningSummaryItem(val text: String)
data class PlanStep(val text: String, val status: String)
data class PlanItem(val steps: List<PlanStep>)
data class CommandItem(
    val argv: List<String>,
    val cwd: String,
    val output: String,
    val hasExitCode: Boolean,
    val exitCode: Int? = null,
)
data class ToolItem(val name: String, val summary: String, val resultSummary: String)
data class FileChange(
    val path: String,
    val kind: String,
    val oldPath: String,
    val newPath: String,
)
data class FileChangeItem(val changes: List<FileChange>, val unifiedDiff: String)

data class ConversationItem(
    val itemId: String,
    val turnId: String,
    val type: String,
    val status: String,
    val completeness: ItemCompleteness? = null,
    val userMessage: UserMessageItem? = null,
    val agentMessage: AgentMessageItem? = null,
    val reasoningSummary: ReasoningSummaryItem? = null,
    val plan: PlanItem? = null,
    val command: CommandItem? = null,
    val tool: ToolItem? = null,
    val fileChange: FileChangeItem? = null,
)

data class ConversationTurn(
    val turnId: String,
    val status: String,
    val failure: String,
    val startedAtUnixMs: Long,
    val completedAtUnixMs: Long,
    val items: List<ConversationItem>,
    val messages: List<ConversationMessage>,
)

data class PendingError(
    val commandId: String = "",
    val code: String = "",
    val message: String = "",
)

data class PendingWatch(
    val state: String = "loading",
    val headEventSeq: Long = 0,
    val error: PendingError? = null,
)

data class UserInputOption(
    val optionId: String = "",
    val label: String = "",
    val description: String = "",
)

data class UserInputQuestion(
    val questionId: String = "",
    val header: String = "",
    val prompt: String = "",
    val options: List<UserInputOption> = emptyList(),
    val allowsMultiple: Boolean = false,
    val allowsFreeForm: Boolean = false,
)

data class PendingRequest(
    val type: String = "",
    val requestId: String = "",
    val turnId: String = "",
    val itemId: String = "",
    val inFlight: Boolean = false,
    val error: PendingError? = null,
    val kind: String = "",
    val status: String = "",
    val title: String = "",
    val explanation: String = "",
    val command: List<String> = emptyList(),
    val allowedDecisions: List<String> = emptyList(),
    val resolved: Boolean = false,
    val questions: List<UserInputQuestion> = emptyList(),
    val completeness: ItemCompleteness? = null,
)

sealed interface ConversationTimelineEntry {
    data class Item(val item: ConversationItem) : ConversationTimelineEntry
    data class TurnFailure(val turnId: String, val failure: String) : ConversationTimelineEntry
}

data class ConversationState(
    val codexId: String,
    val activeTurnId: String = "",
    val running: Boolean = false,
    val historyComplete: Boolean = false,
    val turns: List<ConversationTurn> = emptyList(),
    val pendingRequests: List<PendingRequest> = emptyList(),
    val pendingWatch: PendingWatch = PendingWatch(),
) {
    val items: List<ConversationItem>
        get() = turns.flatMap { it.items }

    val messages: List<ConversationMessage>
        get() = turns.flatMap { it.messages }

    val timelineItems: List<ConversationItem>
        get() = timelineEntries.mapNotNull { (it as? ConversationTimelineEntry.Item)?.item }

    val timelineEntries: List<ConversationTimelineEntry>
        get() {
            val useTypedItems = items.isNotEmpty()
            return buildList {
                turns.forEach { turn ->
                    val turnItems = if (useTypedItems) {
                        turn.items
                    } else {
                        turn.messages.map { it.asTimelineItem(turn.turnId) }
                    }
                    turnItems.forEach { add(ConversationTimelineEntry.Item(it)) }
                    if (turn.status == "failed" && turn.failure.isNotBlank()) {
                        add(ConversationTimelineEntry.TurnFailure(turn.turnId, turn.failure))
                    }
                }
            }
        }
}

private fun ConversationMessage.asTimelineItem(turnId: String) = ConversationItem(
    itemId = itemId,
    turnId = turnId,
    type = if (role == "user") "user_message" else "agent_message",
    status = status,
    userMessage = if (role == "user") UserMessageItem(listOf(text), text) else null,
    agentMessage = if (role != "user") AgentMessageItem(text) else null,
)

data class CoreState(
    val revision: Long = 0,
    val commandId: String = "",
    val phase: String = "idle",
    val authUrl: String = "",
    val error: String = "",
    val tailnetIPs: List<String> = emptyList(),
    val codexes: List<CodexSummary> = emptyList(),
    val directoryListing: DirectoryListing? = null,
    val sessionCandidates: SessionCandidates? = null,
    val selectedCodexId: String = "",
    val conversation: ConversationState? = null,
    val workspace: WorkspaceState? = null,
)

fun decodeCoreState(raw: String): CoreState {
    val root = JSONObject(raw)
    val addresses = root.optJSONArray("tailnetIps")
    val codexArray = root.optJSONObject("codexes")?.optJSONArray("codexes")
    val directoryListing = root.optJSONObject("directoryListing")?.let { listing ->
        val directories = listing.optJSONArray("directories")
        DirectoryListing(
            parentPath = listing.optString("parentPath"),
            directories = buildList {
                if (directories != null) repeat(directories.length()) { index ->
                    val directory = directories.optJSONObject(index) ?: return@repeat
                    add(DirectoryEntry(directory.optString("name"), directory.optString("path")))
                }
            },
        )
    }
    val sessionCandidates = root.optJSONObject("sessionCandidates")?.let { candidates ->
        val sessions = candidates.optJSONArray("sessions")
        SessionCandidates(
            normalizedCwd = candidates.optString("normalizedCwd"),
            sessions = buildList {
                if (sessions != null) repeat(sessions.length()) { index ->
                    val session = sessions.optJSONObject(index) ?: return@repeat
                    add(
                        SessionCandidate(
                            sessionId = session.optString("sessionId"),
                            cwd = session.optString("cwd"),
                            title = session.optString("title"),
                            preview = session.optString("preview"),
                            source = session.optString("source"),
                            availability = session.optString("availability"),
                            managedCodexId = session.optString("managedCodexId"),
                        ),
                    )
                }
            },
        )
    }
    val conversation = root.optJSONObject("conversation")?.let { value ->
        val turns = value.optJSONArray("turns")
        val pendingRequests = value.optJSONArray("pendingRequests")
        ConversationState(
            codexId = value.optString("codexId"),
            activeTurnId = value.optString("activeTurnId"),
            running = value.optBoolean("running"),
            historyComplete = value.optBoolean("historyComplete"),
            turns = buildList {
                if (turns != null) repeat(turns.length()) { turnIndex ->
                    val turn = turns.optJSONObject(turnIndex) ?: return@repeat
                    val messages = turn.optJSONArray("messages")
                    val items = turn.optJSONArray("items")
                    add(
                        ConversationTurn(
                            turnId = turn.optString("turnId"),
                            status = turn.optString("status", "unspecified"),
                            failure = turn.optString("failure"),
                            startedAtUnixMs = turn.optLong("startedAtUnixMs"),
                            completedAtUnixMs = turn.optLong("completedAtUnixMs"),
                            items = buildList {
                                if (items != null) repeat(items.length()) { itemIndex ->
                                    val item = items.optJSONObject(itemIndex) ?: return@repeat
                                    add(decodeConversationItem(item))
                                }
                            },
                            messages = buildList {
                                if (messages != null) repeat(messages.length()) { messageIndex ->
                                    val message = messages.optJSONObject(messageIndex) ?: return@repeat
                                    add(
                                        ConversationMessage(
                                            itemId = message.optString("itemId"),
                                            role = message.optString("role"),
                                            text = message.optString("text"),
                                            status = message.optString("status", "unspecified"),
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                }
            },
            pendingRequests = buildList {
                if (pendingRequests != null) repeat(pendingRequests.length()) { index ->
                    pendingRequests.optJSONObject(index)?.let { add(decodePendingRequest(it)) }
                }
            },
            pendingWatch = value.optJSONObject("pendingWatch")?.let { watch ->
                PendingWatch(
                    state = watch.optString("state", "loading"),
                    headEventSeq = watch.optLong("headEventSeq"),
                    error = watch.optJSONObject("error")?.let(::decodePendingError),
                )
            } ?: PendingWatch(),
        )
    }
    val workspace = root.optJSONObject("workspace")?.let(::decodeWorkspace)
    return CoreState(
        revision = root.optLong("revision"),
        commandId = root.optString("commandId"),
        phase = root.optString("phase", "idle"),
        authUrl = root.optString("authUrl"),
        error = root.optString("error"),
        tailnetIPs = buildList {
            if (addresses != null) {
                repeat(addresses.length()) { index -> add(addresses.optString(index)) }
            }
        },
        codexes = buildList {
            if (codexArray != null) {
                repeat(codexArray.length()) { index ->
                    val item = codexArray.optJSONObject(index) ?: return@repeat
                    add(
                        CodexSummary(
                            id = item.optString("codexId"),
                            title = item.optString("title"),
                            cwd = item.optString("cwd"),
                            status = item.optString("status"),
                            managementState = item.optString("managementState"),
                        ),
                    )
                }
            }
        },
        directoryListing = directoryListing,
        sessionCandidates = sessionCandidates,
        selectedCodexId = root.optString("selectedCodexId"),
        conversation = conversation,
        workspace = workspace,
    )
}

private fun decodePendingError(value: JSONObject) = PendingError(
    commandId = value.optString("commandId"),
    code = value.optString("code"),
    message = value.optString("message"),
)

private fun decodePendingRequest(value: JSONObject): PendingRequest {
    val approval = value.optJSONObject("approval") ?: JSONObject()
    val userInput = value.optJSONObject("userInput") ?: JSONObject()
    val command = approval.optJSONArray("command")
    val decisions = approval.optJSONArray("allowedDecisions")
    val questions = userInput.optJSONArray("questions")
    val completeness = userInput.optJSONObject("completeness")
    return PendingRequest(
        type = value.optString("type"),
        requestId = value.optString("requestId"),
        turnId = value.optString("turnId"),
        itemId = value.optString("itemId"),
        inFlight = value.optBoolean("inFlight"),
        error = value.optJSONObject("error")?.let(::decodePendingError),
        kind = approval.optString("kind"),
        status = approval.optString("status"),
        title = approval.optString("title"),
        explanation = approval.optString("explanation"),
        command = buildList {
            if (command != null) repeat(command.length()) { add(command.optString(it)) }
        },
        allowedDecisions = buildList {
            if (decisions != null) repeat(decisions.length()) { add(decisions.optString(it)) }
        },
        resolved = userInput.optBoolean("resolved"),
        questions = buildList {
            if (questions != null) repeat(questions.length()) { index ->
                val question = questions.optJSONObject(index) ?: return@repeat
                val options = question.optJSONArray("options")
                add(
                    UserInputQuestion(
                        questionId = question.optString("questionId"),
                        header = question.optString("header"),
                        prompt = question.optString("prompt"),
                        options = buildList {
                            if (options != null) repeat(options.length()) { optionIndex ->
                                val option = options.optJSONObject(optionIndex) ?: return@repeat
                                add(
                                    UserInputOption(
                                        optionId = option.optString("optionId"),
                                        label = option.optString("label"),
                                        description = option.optString("description"),
                                    ),
                                )
                            }
                        },
                        allowsMultiple = question.optBoolean("allowsMultiple"),
                        allowsFreeForm = question.optBoolean("allowsFreeForm"),
                    ),
                )
            }
        },
        completeness = completeness?.let {
            ItemCompleteness(
                truncated = it.optBoolean("truncated"),
                incomplete = it.optBoolean("incomplete"),
                originalSizeBytes = it.optLong("originalSizeBytes"),
                reason = it.optString("reason"),
            )
        },
    )
}

private fun decodeWorkspace(value: JSONObject): WorkspaceState {
    val limits = value.optJSONObject("limits")
    val access = value.optJSONObject("accessState")
    return WorkspaceState(
        supported = value.optBoolean("supported"),
        limits = WorkspaceLimits(
            maxTextFileBytes = limits?.optLong("maxTextFileBytes") ?: 0,
            maxInlineUploadBytes = limits?.optLong("maxInlineUploadBytes") ?: 0,
            maxInlineDownloadBytes = limits?.optLong("maxInlineDownloadBytes") ?: 0,
            maxArchiveExpandedBytes = limits?.optLong("maxArchiveExpandedBytes") ?: 0,
            maxArchiveEntryCount = limits?.optLong("maxArchiveEntryCount") ?: 0,
        ),
        codexId = value.optString("codexId"),
        workspaceRoot = value.optString("workspaceRoot"),
        accessState = WorkspaceAccessState(
            mutationStatus = access?.optString("mutationStatus").orEmpty(),
            activeAgentCount = access?.optLong("activeAgentCount") ?: 0,
            quiescenceToken = access?.optString("quiescenceToken").orEmpty(),
            observedAtUnixMs = access?.optLong("observedAtUnixMs") ?: 0,
            generation = access?.optLong("generation") ?: 0,
        ),
        currentDirectory = value.optJSONObject("currentDirectory")?.let { directory ->
            WorkspaceDirectory(
                relativeDirectory = directory.optString("relativeDirectory"),
                entries = decodeWorkspaceEntries(directory.optJSONArray("entries")),
            )
        },
        openFile = value.optJSONObject("openFile")?.let { file ->
            WorkspaceOpenFile(
                entry = decodeWorkspaceEntry(file.optJSONObject("entry") ?: JSONObject()),
                utf8Text = file.optString("utf8Text"),
            )
        },
        lastWrite = value.optJSONObject("lastWrite")?.let { write ->
            WorkspaceLastWrite(
                entry = decodeWorkspaceEntry(write.optJSONObject("entry") ?: JSONObject()),
                deduplicated = write.optBoolean("deduplicated"),
            )
        },
        uploadResult = value.optJSONObject("uploadResult")?.let { upload ->
            WorkspaceUploadResult(
                entry = decodeWorkspaceEntry(upload.optJSONObject("entry") ?: JSONObject()),
                deduplicated = upload.optBoolean("deduplicated"),
            )
        },
        downloadResult = value.optJSONObject("downloadResult")?.let { download ->
            WorkspaceDownloadResult(
                entry = decodeWorkspaceEntry(download.optJSONObject("entry") ?: JSONObject()),
                kind = download.optString("kind"),
                filename = download.optString("filename"),
                contentBase64 = download.optString("contentBase64"),
            )
        },
        loading = value.optString("loading", "none"),
        error = value.optJSONObject("error")?.let { WorkspaceError(it.optString("code"), it.optString("message")) },
    )
}

private fun decodeWorkspaceEntries(values: org.json.JSONArray?) = buildList {
    if (values != null) repeat(values.length()) { index ->
        values.optJSONObject(index)?.let { add(decodeWorkspaceEntry(it)) }
    }
}

private fun decodeWorkspaceEntry(value: JSONObject) = WorkspaceEntry(
    relativePath = value.optString("relativePath"),
    name = value.optString("name"),
    kind = value.optString("kind"),
    sizeBytes = value.optLong("sizeBytes"),
    modifiedAtUnixMs = value.optLong("modifiedAtUnixMs"),
    revision = value.optString("revision"),
    textViewable = value.optBoolean("textViewable"),
    textEditable = value.optBoolean("textEditable"),
)

private fun decodeConversationItem(item: JSONObject): ConversationItem {
    val completeness = item.optJSONObject("completeness")?.let {
        ItemCompleteness(
            truncated = it.optBoolean("truncated"),
            incomplete = it.optBoolean("incomplete"),
            originalSizeBytes = it.optLong("originalSizeBytes"),
            reason = it.optString("reason"),
        )
    }
    val user = item.optJSONObject("userMessage")?.let {
        val parts = it.optJSONArray("textParts")
        UserMessageItem(
            textParts = buildList {
                if (parts != null) repeat(parts.length()) { index -> add(parts.optString(index)) }
            },
            text = it.optString("text"),
        )
    }
    val command = item.optJSONObject("command")?.let {
        val argv = it.optJSONArray("argv")
        val hasExitCode = it.optBoolean("hasExitCode")
        CommandItem(
            argv = buildList {
                if (argv != null) repeat(argv.length()) { index -> add(argv.optString(index)) }
            },
            cwd = it.optString("cwd"),
            output = it.optString("output"),
            hasExitCode = hasExitCode,
            exitCode = if (hasExitCode && it.has("exitCode")) it.optInt("exitCode") else null,
        )
    }
    val plan = item.optJSONObject("plan")?.let {
        val steps = it.optJSONArray("steps")
        PlanItem(
            buildList {
                if (steps != null) repeat(steps.length()) { index ->
                    val step = steps.optJSONObject(index) ?: return@repeat
                    add(PlanStep(step.optString("text"), step.optString("status")))
                }
            },
        )
    }
    val fileChange = item.optJSONObject("fileChange")?.let {
        val changes = it.optJSONArray("changes")
        FileChangeItem(
            changes = buildList {
                if (changes != null) repeat(changes.length()) { index ->
                    val change = changes.optJSONObject(index) ?: return@repeat
                    add(
                        FileChange(
                            path = change.optString("path"),
                            kind = change.optString("kind", "unspecified"),
                            oldPath = change.optString("oldPath"),
                            newPath = change.optString("newPath"),
                        ),
                    )
                }
            },
            unifiedDiff = it.optString("unifiedDiff"),
        )
    }
    return ConversationItem(
        itemId = item.optString("itemId"),
        turnId = item.optString("turnId"),
        type = item.optString("type", "unknown"),
        status = item.optString("status", "unspecified"),
        completeness = completeness,
        userMessage = user,
        agentMessage = item.optJSONObject("agentMessage")?.let { AgentMessageItem(it.optString("text")) },
        reasoningSummary = item.optJSONObject("reasoningSummary")?.let { ReasoningSummaryItem(it.optString("text")) },
        plan = plan,
        command = command,
        tool = item.optJSONObject("tool")?.let {
            ToolItem(it.optString("name"), it.optString("summary"), it.optString("resultSummary"))
        },
        fileChange = fileChange,
    )
}
