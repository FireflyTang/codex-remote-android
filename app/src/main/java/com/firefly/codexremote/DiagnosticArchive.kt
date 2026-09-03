package com.firefly.codexremote

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val DiagnosticSourceTailBytes = 2 * 1024 * 1024
internal const val DiagnosticAppLogMaxBytes = 2 * 1024 * 1024
internal const val DiagnosticTailscaleLogFileLimit = 2
internal const val DiagnosticArchiveRetention = 5

internal object DiagnosticRedactor {
    private val tailscaleLoginUrl = Regex("(?i)https://login\\.tailscale\\.com/a/[^\\s\\\"'<>]+")
    private val tailscaleKey = Regex("(?i)tskey-[a-z0-9_-]+")
    private val authorization = Regex("(?i)(authorization\\s*:\\s*(?:basic|bearer)\\s+)[^\\s,\\\"']+")
    private val bearer = Regex("(?i)(bearer\\s+)[a-z0-9._~+\\-/=]+")
    private val doubleQuotedSecret = Regex(
        "(?i)([\\\"']?(?:auth[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|pwd)[\\\"']?\\s*[:=]\\s*)\\\"[^\\\"]*\\\"",
    )
    private val singleQuotedSecret = Regex(
        "(?i)([\\\"']?(?:auth[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|pwd)[\\\"']?\\s*[:=]\\s*)'[^']*'",
    )
    private val namedSecret = Regex(
        "(?i)((?:[\\\"']?(?:auth[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|pwd)[\\\"']?\\s*[:=]\\s*[\\\"']?))([^\\s,&\\\"'}]+)",
    )

    fun redact(value: String): String = value
        .replace(tailscaleLoginUrl, "[REDACTED_TAILSCALE_LOGIN_URL]")
        .replace(tailscaleKey, "[REDACTED]")
        .replace(authorization, "$1[REDACTED]")
        .replace(bearer, "$1[REDACTED]")
        .replace(doubleQuotedSecret, "$1\"[REDACTED]\"")
        .replace(singleQuotedSecret, "$1'[REDACTED]'")
        .replace(namedSecret, "$1[REDACTED]")
}

internal fun tailBytes(bytes: ByteArray, maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    return if (bytes.size <= maxBytes) bytes else bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
}

internal fun readTail(file: File, maxBytes: Int): ByteArray {
    if (maxBytes <= 0 || !file.isFile) return ByteArray(0)
    RandomAccessFile(file, "r").use { input ->
        val byteCount = minOf(input.length(), maxBytes.toLong()).toInt()
        val result = ByteArray(byteCount)
        input.seek(input.length() - byteCount)
        input.readFully(result)
        return result
    }
}

internal fun eligibleTailscaleLogFiles(files: List<File>): List<File> = files
    .filter { file ->
        val name = file.name.lowercase()
        file.isFile && name.startsWith("tailscaled.log") && (name.endsWith(".txt") || name.endsWith(".log"))
    }
    .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
    .take(DiagnosticTailscaleLogFileLimit)
    .sortedBy { it.name }

internal data class DiagnosticArchiveEntry(
    val name: String,
    val bytes: ByteArray,
)

internal data class DiagnosticArchiveFiles(
    val output: File,
    val temporary: File,
)

internal fun diagnosticArchiveFiles(directory: File, nowMillis: Long, uniqueId: String): DiagnosticArchiveFiles {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(nowMillis))
    val safeId = uniqueId.filter { it.isLetterOrDigit() || it == '-' }.take(12).ifBlank { "unique" }
    val output = File(directory, "codex-remote-diagnostics-$timestamp-$safeId.zip")
    return DiagnosticArchiveFiles(output, File(directory, ".${output.name}.$safeId.tmp"))
}

internal fun writeDiagnosticZip(output: File, entries: List<DiagnosticArchiveEntry>) {
    output.parentFile?.mkdirs()
    ZipOutputStream(output.outputStream().buffered()).use { zip ->
        entries.forEach { entry ->
            require(entry.name.isNotBlank() && !entry.name.startsWith('/') && ".." !in entry.name.split('/'))
            zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
            ByteArrayInputStream(entry.bytes).copyTo(zip)
            zip.closeEntry()
        }
    }
}

internal fun writeDiagnosticZipAtomically(
    output: File,
    temporary: File,
    entries: List<DiagnosticArchiveEntry>,
) {
    require(output.absoluteFile.parentFile == temporary.absoluteFile.parentFile)
    try {
        writeDiagnosticZip(temporary, entries)
        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), output.toPath())
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

internal fun cleanupOldDiagnosticArchives(
    directory: File,
    keep: Int = DiagnosticArchiveRetention,
    protectedFiles: Set<File> = emptySet(),
) {
    if (keep < 0) return
    val protectedPaths = protectedFiles.mapTo(mutableSetOf()) { it.absolutePath }
    directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("codex-remote-diagnostics-") && it.name.endsWith(".zip") }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        .drop(keep)
        .filterNot { it.absolutePath in protectedPaths }
        .forEach { it.delete() }
}

internal fun diagnosticTextEntry(name: String, text: String, maxBytes: Int = DiagnosticSourceTailBytes) =
    DiagnosticArchiveEntry(
        name,
        tailBytes(DiagnosticRedactor.redact(text).toByteArray(Charsets.UTF_8), maxBytes),
    )

internal fun diagnosticFileEntry(name: String, file: File, maxBytes: Int = DiagnosticSourceTailBytes) =
    diagnosticTextEntry(name, String(readTail(file, maxBytes + 4096), Charsets.UTF_8), maxBytes)
