package com.firefly.codexremote

import android.content.ContentResolver
import android.database.Cursor
import android.provider.OpenableColumns
import java.util.Base64

data class DocumentMetadata(val displayName: String = "", val sizeBytes: Long? = null)
data class SelectedDocument(val metadata: DocumentMetadata, val bytes: ByteArray)

interface DocumentIo {
    fun metadata(documentId: String): DocumentMetadata
    fun readBounded(documentId: String, maxBytes: Long): ByteArray
    fun write(documentId: String, bytes: ByteArray)
}

class AndroidDocumentIo(private val resolver: ContentResolver) : DocumentIo {
    override fun metadata(documentId: String): DocumentMetadata {
        val uri = android.net.Uri.parse(documentId)
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val activeCursor = cursor ?: return DocumentMetadata()
                val nameIndex = activeCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = activeCursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentMetadata(
                    displayName = if (nameIndex >= 0) activeCursor.getString(nameIndex).orEmpty() else "",
                    sizeBytes = if (sizeIndex >= 0 && !activeCursor.isNull(sizeIndex)) activeCursor.getLong(sizeIndex) else null,
                )
            } else DocumentMetadata()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LocalTransferException("无法读取所选文档信息", error)
        } finally {
            cursor?.close()
        }
    }

    override fun readBounded(documentId: String, maxBytes: Long): ByteArray {
        val uri = android.net.Uri.parse(documentId)
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val knownSize = metadata(documentId).sizeBytes
        if (knownSize != null && knownSize > maxBytes) throw TransferLimitException(maxBytes)
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw TransferLimitException(maxBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw LocalTransferException("无法打开所选文档")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: TransferLimitException) {
            throw error
        } catch (error: LocalTransferException) {
            throw error
        } catch (error: Exception) {
            throw LocalTransferException("读取所选文档失败", error)
        }
    }

    override fun write(documentId: String, bytes: ByteArray) {
        val uri = android.net.Uri.parse(documentId)
        try {
            resolver.openOutputStream(uri, "rwt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw LocalTransferException("无法打开保存目标")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: LocalTransferException) {
            throw error
        } catch (error: Exception) {
            throw LocalTransferException("写入保存目标失败", error)
        }
    }
}

class LocalTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TransferLimitException(val limitBytes: Long) : Exception("document exceeds $limitBytes bytes")

internal fun safeDocumentName(raw: String, fallback: String = "file"): String {
    val basename = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = basename.map { character ->
        when {
            character.code < 32 || character == '\u007f' -> '_'
            character in setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') -> '_'
            else -> character
        }
    }.joinToString("").trim().trim('.').take(180)
    return cleaned.ifBlank { fallback }
}

internal fun uploadDestinationPath(currentDirectory: String, displayName: String, kind: String): String {
    val safe = safeDocumentName(displayName, if (kind == "zip_directory") "archive.zip" else "file")
    val child = if (kind == "zip_directory") {
        safe.removeSuffixIgnoreCase(".zip").trim().trim('.').ifBlank { "archive" }
    } else safe
    return listOf(currentDirectory.trim('/'), child).filter { it.isNotBlank() }.joinToString("/")
}

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

internal fun downloadSuggestedFilename(entry: WorkspaceEntry, resultFilename: String = ""): String {
    val fallback = entry.name.ifBlank { entry.relativePath.substringAfterLast('/').ifBlank { "workspace" } }
    val name = resultFilename.ifBlank {
        if (entry.kind == "directory") "$fallback.zip" else fallback
    }
    return safeDocumentName(name, if (entry.kind == "directory") "workspace.zip" else "file")
}

internal fun workspacePathJoin(directory: String, child: String): String =
    listOf(directory.trim('/'), child.trim('/')).filter { it.isNotBlank() }.joinToString("/")

internal fun decodeDownloadBytes(result: WorkspaceDownloadResult, maxBytes: Long): ByteArray {
    if (maxBytes < 0) throw LocalTransferException("下载大小上限无效")
    val encodedLength = result.contentBase64.length.toLong()
    val maximumDecodedLength = ((encodedLength + 3L) / 4L) * 3L
    if (maximumDecodedLength - 2L > maxBytes) throw TransferLimitException(maxBytes)
    val bytes = try {
        Base64.getDecoder().decode(result.contentBase64)
    } catch (error: IllegalArgumentException) {
        throw LocalTransferException("Host 返回的下载内容不是有效 Base64", error)
    }
    if (bytes.size.toLong() > maxBytes) throw TransferLimitException(maxBytes)
    if (result.kind == "regular_file" && result.entry.sizeBytes >= 0 && bytes.size.toLong() != result.entry.sizeBytes) {
        throw LocalTransferException("Host 返回的下载大小与文件信息不一致")
    }
    return bytes
}

internal fun formatByteLimit(bytes: Long): String = if (bytes <= 0) "未提供" else "$bytes B"

internal fun canUploadWorkspace(workspace: WorkspaceState?): Boolean =
    workspace?.supported == true && workspace.loading == "none" &&
        workspace.accessState.mutationStatus.equals("allowed", ignoreCase = true) &&
        workspace.accessState.quiescenceToken.isNotBlank() &&
        workspace.limits.maxInlineUploadBytes > 0

internal fun canStartDownload(workspace: WorkspaceState?): Boolean =
    workspace?.supported == true && workspace.loading == "none" && workspace.limits.maxInlineDownloadBytes > 0

internal fun uploadMimeTypes(kind: String): Array<String> =
    if (kind == "zip_directory") arrayOf("application/zip", "application/x-zip-compressed") else arrayOf("*/*")

internal fun <Selection, Result> afterDocumentSelected(
    selection: Selection?,
    onSelected: (Selection) -> Result,
): Result? = selection?.let(onSelected)

internal fun readSelectedUpload(documentIo: DocumentIo, documentId: String, maxBytes: Long): SelectedDocument =
    SelectedDocument(documentIo.metadata(documentId), documentIo.readBounded(documentId, maxBytes))

internal fun writeSelectedDownload(documentIo: DocumentIo, documentId: String, bytes: ByteArray) {
    documentIo.write(documentId, bytes)
}

internal fun expectedDownloadResultKind(entryKind: String): String? = when (entryKind) {
    "regular_file" -> "regular_file"
    "directory" -> "zip_directory"
    else -> null
}

internal fun uploadResultMatches(result: WorkspaceUploadResult?, destinationPath: String, uploadKind: String): Boolean {
    val expectedEntryKind = if (uploadKind == "zip_directory") "directory" else "regular_file"
    return result?.entry?.relativePath == destinationPath && result.entry.kind == expectedEntryKind
}

internal fun downloadResultMatches(
    result: WorkspaceDownloadResult,
    relativePath: String,
    entryKind: String,
): Boolean = result.entry.relativePath == relativePath && result.entry.kind == entryKind &&
    result.kind == expectedDownloadResultKind(entryKind)

internal fun downloadReadyMatches(
    ready: WorkspaceDownloadReady?,
    commandId: String,
    relativePath: String,
    entryKind: String,
): Boolean = ready != null && ready.commandId == commandId && ready.relativePath == relativePath &&
    downloadResultMatches(ready.result, relativePath, entryKind)

internal fun uploadRecoveryMessage(partial: Boolean, succeeded: Boolean, previousError: String): Pair<String, String> = when {
    succeeded && partial -> "" to "上传可能已完成，项目文件状态已刷新"
    succeeded -> previousError to ""
    else -> "项目文件状态刷新失败，请稍后重试" to ""
}

internal fun isPartialUploadFailure(workspace: WorkspaceState?, destinationPath: String): Boolean =
    workspace?.error?.code.equals("operation_failed", ignoreCase = true) &&
        workspace?.uploadResult?.entry?.relativePath == destinationPath
