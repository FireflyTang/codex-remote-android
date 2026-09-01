package com.firefly.codexremote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkspaceTransferTest {
    private class FakeDocumentIo(
        private val contents: MutableMap<String, ByteArray> = mutableMapOf(),
        private val names: Map<String, String> = emptyMap(),
    ) : DocumentIo {
        var writes = 0
        override fun metadata(documentId: String) = DocumentMetadata(names[documentId].orEmpty(), contents[documentId]?.size?.toLong())
        override fun readBounded(documentId: String, maxBytes: Long): ByteArray =
            contents.getValue(documentId).also { if (it.size > maxBytes) throw TransferLimitException(maxBytes) }
        override fun write(documentId: String, bytes: ByteArray) { writes++; contents[documentId] = bytes }
    }
    @Test
    fun decoderReadsTransferResultsAndLimits() {
        val state = decodeCoreState(
            """{"revision":1,"workspace":{"supported":true,"loading":"download","limits":{"maxInlineUploadBytes":12,"maxInlineDownloadBytes":34},"uploadResult":{"entry":{"relativePath":"src/a.txt","name":"a.txt","kind":"regular_file","sizeBytes":1},"deduplicated":true},"downloadResult":{"entry":{"relativePath":"src/a.txt","name":"a.txt","kind":"regular_file","sizeBytes":1},"kind":"regular_file","filename":"a.txt","contentBase64":"eA=="}}}""",
        )
        val workspace = state.workspace!!
        assertEquals(12L, workspace.limits.maxInlineUploadBytes)
        assertEquals(34L, workspace.limits.maxInlineDownloadBytes)
        assertTrue(workspace.uploadResult!!.deduplicated)
        assertEquals("src/a.txt", workspace.downloadResult!!.entry.relativePath)
        assertEquals("a.txt", workspace.downloadResult!!.filename)
    }

    @Test
    fun transferCommandsUseExactContractWithoutEditFields() {
        val upload = workspaceUploadCommand("C", "src/a.txt", "regular_file", "eA==", "q1")
        val payload = upload.getJSONObject("payload")
        assertEquals("upload_workspace_entry", upload.getString("type"))
        assertEquals("src/a.txt", payload.getString("destinationPath"))
        assertEquals("regular_file", payload.getString("kind"))
        assertEquals("eA==", payload.getString("contentBase64"))
        assertEquals("q1", payload.getString("expectedQuiescenceToken"))
        assertFalse(payload.has("condition"))
        assertFalse(payload.has("expectedRevision"))

        val download = workspaceDownloadCommand("C", "")
        assertEquals("download_workspace_entry", download.getString("type"))
        assertEquals("", download.getJSONObject("payload").getString("relativePath"))
    }

    @Test
    fun safeNamesAndDestinationsCannotEscapeCurrentDirectory() {
        assertEquals("evil_.txt", safeDocumentName("../evil?.txt"))
        assertEquals("src/vendor", uploadDestinationPath("/src/", "../vendor.ZIP", "zip_directory"))
        assertEquals("src/a.txt", uploadDestinationPath("src", "dir\\a.txt", "regular_file"))
        assertEquals("folder.zip", downloadSuggestedFilename(WorkspaceEntry("folder", "folder", "directory")))
    }

    @Test
    fun strictBase64SizeAndLimitChecks() {
        val bytes = "hello".toByteArray()
        val result = WorkspaceDownloadResult(
            WorkspaceEntry("a", "a", "regular_file", sizeBytes = bytes.size.toLong()),
            "regular_file", "a", Base64.getEncoder().encodeToString(bytes),
        )
        assertArrayEquals(bytes, decodeDownloadBytes(result, 5))
        assertTrue(runCatching { decodeDownloadBytes(result, 4) }.exceptionOrNull() is TransferLimitException)
        assertTrue(runCatching { decodeDownloadBytes(result.copy(contentBase64 = "%%%"), 5) }.exceptionOrNull() is LocalTransferException)
        assertTrue(runCatching { decodeDownloadBytes(result.copy(entry = result.entry.copy(sizeBytes = 9)), 10) }.exceptionOrNull() is LocalTransferException)
        val oversizedEncoded = result.copy(contentBase64 = "A".repeat(400))
        assertTrue(runCatching { decodeDownloadBytes(oversizedEncoded, 10) }.exceptionOrNull() is TransferLimitException)
        val directoryArchive = result.copy(
            entry = WorkspaceEntry("folder", "folder", "directory", sizeBytes = 0),
            kind = "zip_directory",
        )
        assertArrayEquals(bytes, decodeDownloadBytes(directoryArchive, 5))
    }

    @Test
    fun selectionCancellationDoesNotStartRpcSeam() {
        var calls = 0
        val result = afterDocumentSelected<String, String>(null) { calls++; "command" }
        assertNull(result)
        assertEquals(0, calls)
        assertEquals("command", afterDocumentSelected("uri") { calls++; "command" })
        assertEquals(1, calls)
    }

    @Test
    fun fakeDocumentSeamReadsBoundedBytesAndWritesWithoutLocalCopy() {
        val io = FakeDocumentIo(mutableMapOf("source" to "abc".toByteArray()), mapOf("source" to "a.txt"))
        val selected = readSelectedUpload(io, "source", 3)
        assertEquals("a.txt", selected.metadata.displayName)
        assertArrayEquals("abc".toByteArray(), selected.bytes)
        writeSelectedDownload(io, "target", selected.bytes)
        assertEquals(1, io.writes)
    }

    @Test
    fun boundContextAndLocalBusyRejectChangedOrDuplicateOperation() {
        val bound = AppUiState(
            workspaceLocalTransferStatus = "choosing_upload",
            workspaceTransferCodexId = "C1",
            workspaceTransferDirectory = "src",
            workspaceTransferUploadKind = "regular_file",
        )
        assertTrue(workspaceTransferContextMatches(bound, "C1", "src", "", "", "regular_file"))
        assertFalse(workspaceTransferContextMatches(bound, "C2", "src", "", "", "regular_file"))
        assertFalse(workspaceTransferIsIdle(bound))
        assertTrue(workspaceTransferIsIdle(clearWorkspaceTransferContext(bound)))
    }

    @Test
    fun createTargetPrecedesDownloadAndCancellationSkipsCommand() {
        val order = mutableListOf<String>()
        afterDocumentSelected<String, Unit>(null) { order += "rpc" }
        assertTrue(order.isEmpty())
        afterDocumentSelected("content://target") { order += "target"; order += "rpc" }
        assertEquals(listOf("target", "rpc"), order)
    }

    @Test
    fun readyRequiresCommandPathAndDefensiveKinds() {
        val file = WorkspaceEntry("src/a", "a", "regular_file", sizeBytes = 1)
        val result = WorkspaceDownloadResult(file, "regular_file", "a", "eA==")
        val ready = WorkspaceDownloadReady("cmd-1", "src/a", result)
        assertTrue(downloadReadyMatches(ready, "cmd-1", "src/a", "regular_file"))
        assertFalse(downloadReadyMatches(ready, "old", "src/a", "regular_file"))
        assertFalse(downloadReadyMatches(ready, "cmd-1", "src/b", "regular_file"))
        assertFalse(downloadResultMatches(result.copy(kind = "zip_directory"), "src/a", "regular_file"))
        assertFalse(downloadResultMatches(result.copy(entry = file.copy(kind = "directory")), "src/a", "regular_file"))
    }

    @Test
    fun failedWriteCanReselectTargetWithoutAnotherRpc() {
        var rpcCalls = 0
        val ready = WorkspaceDownloadReady(
            "cmd", "a", WorkspaceDownloadResult(WorkspaceEntry("a", "a", "regular_file", sizeBytes = 1), "regular_file", "a", "eA=="),
        )
        repeat(2) {
            afterDocumentSelected("target-$it") {
                if (!downloadReadyMatches(ready, "cmd", "a", "regular_file")) rpcCalls++
            }
        }
        assertEquals(0, rpcCalls)
    }

    @Test
    fun partialUploadRecoveryClearsFailureAndReportsRefreshedState() {
        val workspace = WorkspaceState(
            uploadResult = WorkspaceUploadResult(WorkspaceEntry("vendor", "vendor", "directory")),
            error = WorkspaceError("operation_failed", "raw"),
        )
        assertTrue(isPartialUploadFailure(workspace, "vendor"))
        assertEquals("" to "上传可能已完成，项目文件状态已刷新", uploadRecoveryMessage(true, true, "旧失败"))
        assertEquals("旧失败" to "", uploadRecoveryMessage(false, true, "旧失败"))
        val recovered = WorkspaceState(
            supported = true,
            loading = "none",
            limits = WorkspaceLimits(maxInlineUploadBytes = 1024),
            accessState = WorkspaceAccessState(mutationStatus = "ALLOWED", quiescenceToken = "new-token"),
        )
        assertTrue(canUploadWorkspace(recovered))
        assertEquals("1048577 B", formatByteLimit(1_048_577))
    }

    @Test
    fun uploadRequiresSupportedIdleAllowedTokenAndLimit() {
        val allowed = WorkspaceState(
            supported = true,
            loading = "none",
            limits = WorkspaceLimits(maxInlineUploadBytes = 1),
            accessState = WorkspaceAccessState(mutationStatus = "ALLOWED", quiescenceToken = "q"),
        )
        assertTrue(canUploadWorkspace(allowed))
        assertFalse(canUploadWorkspace(allowed.copy(loading = "upload")))
        assertFalse(canUploadWorkspace(allowed.copy(accessState = allowed.accessState.copy(quiescenceToken = ""))))
    }

    @Test
    fun transferErrorsNeverExposeHostEnglishDetails() {
        assertEquals(
            "文件传输失败，请重试",
            workspaceTransferErrorDescription(WorkspaceError("operation_failed", "sensitive host detail")),
        )
        assertEquals(
            "文件传输失败，请重试",
            workspaceTransferErrorDescription(WorkspaceError("future_code", "future raw detail")),
        )
    }
}
