package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    @Test
    fun decodesWorkspaceDirectoryFileAccessAndWriteResult() {
        val state = decodeCoreState(
            """
            {"workspace":{
              "supported":true,"codexId":"C","workspaceRoot":"/work/demo","loading":"none",
              "limits":{"maxTextFileBytes":262144,"maxInlineUploadBytes":10,"maxInlineDownloadBytes":20,"maxArchiveExpandedBytes":30,"maxArchiveEntryCount":40},
              "accessState":{"mutationStatus":"ALLOWED","activeAgentCount":4294967295,"quiescenceToken":"q1","observedAtUnixMs":9,"generation":2},
              "currentDirectory":{"relativeDirectory":"src","entries":[
                {"relativePath":"src/main.kt","name":"main.kt","kind":"regular_file","sizeBytes":12,"modifiedAtUnixMs":8,"revision":"r1","textViewable":true,"textEditable":true},
                {"relativePath":"src/assets","name":"assets","kind":"directory","textViewable":false,"textEditable":false}
              ]},
              "openFile":{"entry":{"relativePath":"src/main.kt","name":"main.kt","kind":"regular_file","revision":"r2","textViewable":true,"textEditable":true},"utf8Text":"fun main() {}"},
              "lastWrite":{"entry":{"relativePath":"src/main.kt","name":"main.kt","kind":"regular_file","revision":"r2","textViewable":true,"textEditable":true},"deduplicated":false}
            }}
            """.trimIndent(),
        )

        val workspace = state.workspace!!
        assertTrue(workspace.supported)
        assertEquals("/work/demo", workspace.workspaceRoot)
        assertEquals("ALLOWED", workspace.accessState.mutationStatus)
        assertEquals("q1", workspace.accessState.quiescenceToken)
        assertEquals(4294967295L, workspace.accessState.activeAgentCount)
        assertEquals(10L, workspace.limits.maxInlineUploadBytes)
        assertEquals(20L, workspace.limits.maxInlineDownloadBytes)
        assertEquals(30L, workspace.limits.maxArchiveExpandedBytes)
        assertEquals(40L, workspace.limits.maxArchiveEntryCount)
        assertEquals("src/main.kt", workspace.currentDirectory!!.entries.first().relativePath)
        assertEquals("fun main() {}", workspace.openFile!!.utf8Text)
        assertEquals("r2", workspace.lastWrite!!.entry.revision)
    }

    @Test
    fun workspaceCommandsUseExactContractPayloads() {
        assertEquals("get_workspace", workspaceGetCommand("C").getString("type"))
        assertEquals("", workspaceListCommand("C", "").getJSONObject("payload").getString("relativeDirectory"))
        assertEquals("src/a.txt", workspaceReadCommand("C", "src/a.txt").getJSONObject("payload").getString("relativePath"))

        val write = workspaceWriteCommand("C", "a.txt", "新内容", "r7", "q9")
        val payload = write.getJSONObject("payload")
        assertEquals("write_workspace_text_file", write.getString("type"))
        assertEquals("replace_only", payload.getString("condition"))
        assertEquals("r7", payload.getString("expectedRevision"))
        assertEquals("q9", payload.getString("expectedQuiescenceToken"))
        assertEquals("新内容", payload.getString("utf8Text"))
    }

    @Test
    fun saveRequiresEditableAllowedTokenAndRevision() {
        val entry = WorkspaceEntry(revision = "r1", textEditable = true)
        val allowed = WorkspaceAccessState(mutationStatus = "ALLOWED", quiescenceToken = "q1")
        assertTrue(canSaveWorkspaceFile(entry, allowed))
        assertFalse(canSaveWorkspaceFile(entry.copy(textEditable = false), allowed))
        assertFalse(canSaveWorkspaceFile(entry.copy(revision = ""), allowed))
        assertFalse(canSaveWorkspaceFile(entry, allowed.copy(mutationStatus = "BUSY")))
        assertFalse(canSaveWorkspaceFile(entry, allowed.copy(quiescenceToken = "")))
    }

    @Test
    fun mapsExpectedWorkspaceFailuresToChinese() {
        assertTrue(workspaceErrorDescription(WorkspaceError("workspace_revision_conflict", "raw")).contains("重新打开"))
        assertTrue(workspaceErrorDescription(WorkspaceError("workspace_busy", "raw")).contains("正在使用"))
        assertEquals("Host 不支持项目文件", workspaceErrorDescription(WorkspaceError("capability_not_supported", "raw")))
        assertTrue(workspaceErrorDescription(WorkspaceError("workspace_text_too_large", "raw")).contains("过大"))
        assertTrue(workspaceErrorDescription(WorkspaceError("workspace_entry_not_found", "raw")).contains("不存在"))
        assertTrue(workspaceErrorDescription(WorkspaceError("workspace_entry_type_unsupported", "raw")).contains("文本"))
    }
}
