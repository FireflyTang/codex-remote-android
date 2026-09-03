package com.firefly.codexremote

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AppDiagnosticLog(
    private val file: File,
    private val maxBytes: Int = DiagnosticAppLogMaxBytes,
) {
    @Synchronized
    fun append(event: String, details: String = "") {
        file.parentFile?.mkdirs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val safeEvent = event.replace(Regex("[\\r\\n]+"), " ").trim()
        val safeDetails = DiagnosticRedactor.redact(details).replace(Regex("[\\r\\n]+"), " ").trim()
        val line = buildString {
            append(timestamp).append(' ').append(safeEvent)
            if (safeDetails.isNotBlank()) append(' ').append(safeDetails)
            append('\n')
        }
        file.appendText(line, Charsets.UTF_8)
        if (file.length() > maxBytes) {
            val retained = readTail(file, maxBytes / 2)
            file.writeBytes("[older entries truncated]\n".toByteArray() + retained)
        }
    }

    fun sourceFile(): File = file
}

internal class CoreStateDiagnosticRecorder(private val log: AppDiagnosticLog) {
    private var lastPhase: String? = null
    private var lastCommandId: String? = null
    private var lastError: String? = null

    @Synchronized
    fun record(state: CoreState) {
        if (state.phase != lastPhase) {
            log.append("core.phase", "${lastPhase ?: "initial"} -> ${state.phase}; revision=${state.revision}")
            lastPhase = state.phase
        }
        if (state.commandId.isNotBlank() && state.commandId != lastCommandId) {
            log.append("core.command.result", "id=${state.commandId}; revision=${state.revision}; error=${state.error.ifBlank { "none" }}")
            lastCommandId = state.commandId
        }
        if (state.error.isNotBlank() && state.error != lastError) {
            log.append("core.error", state.error)
        }
        lastError = state.error
    }
}

