package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CoreStateTest {
    @Test
    fun decodesOfflineReadyStateAndListCodexes() {
        val state = decodeCoreState(
            """
            {
              "version": 1,
              "revision": 8,
              "phase": "ready",
              "tailnetIps": ["100.64.0.8"],
              "codexes": {
                "codexes": [{
                  "codexId": "CODEX-1",
                  "cwd": "/workspace/demo",
                  "title": "Demo 会话",
                  "status": "IDLE",
                  "managementState": "MANAGEMENT_STATE_EXPIRING_SOON"
                }]
              }
            }
            """.trimIndent(),
        )

        assertEquals("ready", state.phase)
        assertEquals(listOf("100.64.0.8"), state.tailnetIPs)
        assertEquals("CODEX-1", state.codexes.single().id)
        assertEquals("Demo 会话", state.codexes.single().title)
        assertEquals("MANAGEMENT_STATE_EXPIRING_SOON", state.codexes.single().managementState)
    }

    @Test
    fun decodesDirectoryListingSessionCandidatesAndCommandId() {
        val state = decodeCoreState(
            """
            {
              "revision": 9,
              "commandId": "browse-1",
              "directoryListing": {
                "parentPath": "/work",
                "directories": [{"name":"demo","path":"/work/demo"}, {}]
              },
              "sessionCandidates": {
                "normalizedCwd": "/work/demo",
                "sessions": [{
                  "sessionId":"S-1", "cwd":"/work/demo", "title":"旧会话",
                  "preview":"继续之前的工作", "source":"rollout",
                  "availability":"SESSION_AVAILABILITY_RESUMABLE", "managedCodexId":""
                }, {}]
              }
            }
            """.trimIndent(),
        )

        assertEquals("browse-1", state.commandId)
        assertEquals("/work", state.directoryListing!!.parentPath)
        assertEquals("/work/demo", state.directoryListing!!.directories.first().path)
        assertEquals("S-1", state.sessionCandidates!!.sessions.first().sessionId)
        assertEquals("", state.sessionCandidates!!.sessions.last().availability)
    }

    @Test
    fun newManagementFieldsRemainCompatibleWhenMissing() {
        val state = decodeCoreState(
            """{"codexes":{"codexes":[{"codexId":"C","title":"T","cwd":"/w","status":"IDLE"}]},"directoryListing":{},"sessionCandidates":{}}""",
        )

        assertEquals("", state.commandId)
        assertEquals("", state.codexes.single().managementState)
        assertTrue(state.directoryListing!!.directories.isEmpty())
        assertTrue(state.sessionCandidates!!.sessions.isEmpty())
    }

    @Test
    fun decodesProtocolHonestyFieldsAndBuildsChineseNotices() {
        val state = decodeCoreState(
            """
            {
              "codexes":{"codexes":[{
                "codexId":"C", "title":"T", "cwd":"/w", "status":"IDLE",
                "origin":"CODEX_ORIGIN_LOCAL_EXISTING", "activeTurnId":"T1",
                "createdAtUnixMs":10, "importedAtUnixMs":20, "lastActivityAtUnixMs":30,
                "managedUntilUnixMs":40,
                "warnings":[{"code":"WARNING_CODE_RUNTIME_RESTARTED","message":"raw","managedUntilUnixMs":41}]
              }]},
              "sessionCandidates":{"normalizedCwd":"/w","sessions":[{
                "sessionId":"S", "cwd":"/w", "createdAtUnixMs":50, "updatedAtUnixMs":60,
                "warnings":[{"code":"WARNING_CODE_HISTORY_IMPORT_INCOMPLETE","message":"raw"}],
                "completeness":{"truncated":true,"incomplete":true,"originalSizeBytes":700,"reason":"bounded"}
              }]},
              "conversation":{"codexId":"C","turns":[{
                "turnId":"T1", "status":"completed", "startedAtUnixMs":1, "completedAtUnixMs":2,
                "causedByCommandId":"start-1",
                "completeness":{"incomplete":true,"originalSizeBytes":800,"reason":"gap"},
                "provenance":"PROVENANCE_KIND_IMPORTED_HISTORY",
                "items":[{
                  "itemId":"I", "type":"agent_message", "status":"completed",
                  "provenance":"PROVENANCE_KIND_HOST_SYNTHESIZED",
                  "completeness":{"truncated":true,"originalSizeBytes":900},
                  "agentMessage":{"text":"partial"}
                }], "messages":[]
              }]}
            }
            """.trimIndent(),
        )

        val codex = state.codexes.single()
        assertEquals("CODEX_ORIGIN_LOCAL_EXISTING", codex.origin)
        assertEquals("T1", codex.activeTurnId)
        assertEquals(10, codex.createdAtUnixMs)
        assertEquals(20, codex.importedAtUnixMs)
        assertEquals(30, codex.lastActivityAtUnixMs)
        assertEquals(40, codex.managedUntilUnixMs)
        assertEquals(41, codex.warnings.single().managedUntilUnixMs)
        assertEquals(listOf("Codex 运行时已重启"), codex.protocolNotices())

        val candidate = state.sessionCandidates!!.sessions.single()
        assertEquals(50, candidate.createdAtUnixMs)
        assertEquals(60, candidate.updatedAtUnixMs)
        assertEquals(700, candidate.completeness!!.originalSizeBytes)
        assertEquals(listOf("内容已截断且不完整", "历史记录导入不完整"), candidate.protocolNotices())

        val turn = state.conversation!!.turns.single()
        assertEquals("start-1", turn.causedByCommandId)
        assertEquals(listOf("内容不完整", "此轮来自导入的历史记录"), turn.protocolNotices())
        assertEquals(listOf("内容已截断", "由 Host 重建"), turn.items.single().protocolNotices())
    }

    @Test
    fun unknownWarningFallsBackWithoutBreakingOlderPayloads() {
        val state = decodeCoreState(
            """{"codexes":{"codexes":[{"codexId":"C","warnings":[{"code":"WARNING_CODE_FUTURE","message":"未来提示"},{}]}]}}""",
        )

        assertEquals(listOf("未来提示", "服务端返回了一条提示"), state.codexes.single().protocolNotices())
        assertTrue(SessionCandidate().protocolNotices().isEmpty())
        assertEquals(null, ItemCompleteness().chineseNotice())
    }

    @Test
    fun managementStateTakesPriorityOverRuntimeStatus() {
        assertEquals("休眠", codexStatusDescription("MANAGEMENT_STATE_UNMANAGED", "RUNNING"))
        assertEquals("即将休眠", codexStatusDescription("EXPIRING_SOON", "IDLE"))
        assertEquals("运行中", codexStatusDescription("MANAGEMENT_STATE_MANAGED", "RUNNING"))
    }

    @Test
    fun decodesOfflineAuthenticationState() {
        val state = decodeCoreState(
            """{"revision":3,"phase":"auth_required","authUrl":"https://login.tailscale.com/fake"}""",
        )

        assertEquals("auth_required", state.phase)
        assertTrue(state.authUrl.endsWith("/fake"))
    }

    @Test
    fun reconnectStopsBeforeConfigureAndStart() {
        val payload = JSONObject().put("hostEndpoint", DefaultHostAddress)
        val commands = connectCommands(payload)

        assertEquals(listOf("stop", "configure", "start"), commands.map { it.getString("type") })
        assertEquals(DefaultHostAddress, commands[1].getJSONObject("payload").getString("hostEndpoint"))
    }

    @Test
    fun decodesConversationHistoryAndRunningTurn() {
        val state = decodeCoreState(
            """
            {
              "revision": 12,
              "phase": "polling_turn",
              "selectedCodexId": "CODEX-1",
              "conversation": {
                "codexId": "CODEX-1",
                "activeTurnId": "TURN-2",
                "running": true,
                "turns": [{
                  "turnId": "TURN-1",
                  "status": "completed",
                  "messages": [
                    {"itemId":"I-1","role":"user","text":"你好","status":"completed"},
                    {"itemId":"I-2","role":"assistant","text":"你好！","status":"completed"}
                  ]
                }]
              }
            }
            """.trimIndent(),
        )

        assertEquals("CODEX-1", state.selectedCodexId)
        assertTrue(state.conversation!!.running)
        assertEquals("TURN-2", state.conversation!!.activeTurnId)
        assertEquals(listOf("user", "assistant"), state.conversation!!.messages.map { it.role })
        assertEquals("你好！", state.conversation!!.messages.last().text)
    }

    @Test
    fun decodesTypedTimelineItemsWithoutDuplicatingCompatibilityMessages() {
        val state = decodeCoreState(
            """
            {
              "revision": 20,
              "conversation": {
                "codexId": "CODEX-1",
                "historyComplete": false,
                "turns": [{
                  "turnId": "TURN-1",
                  "status": "failed",
                  "failure": "命令失败",
                  "startedAtUnixMs": 1000,
                  "completedAtUnixMs": 2000,
                  "items": [
                    {"itemId":"U","turnId":"TURN-1","type":"user_message","status":"completed","userMessage":{"textParts":["第一段","第二段"],"text":"第一段\n第二段"}},
                    {"itemId":"A","type":"agent_message","status":"running","agentMessage":{"text":"处理中"}},
                    {"itemId":"R","type":"reasoning_summary","status":"completed","reasoningSummary":{"text":"先检查状态"}},
                    {"itemId":"P","type":"plan","status":"running","plan":{"steps":[{"text":"读取文件","status":"completed"},{"text":"修改代码","status":"in_progress"}]}},
                    {"itemId":"C0","type":"command","status":"completed","command":{"argv":["printf","ok"],"cwd":"/work","output":"ok","hasExitCode":true,"exitCode":0}},
                    {"itemId":"C1","type":"command","status":"running","command":{"argv":[],"cwd":"","output":"","hasExitCode":false}},
                    {"itemId":"T","type":"tool","status":"failed","tool":{"name":"读取","summary":"读取配置","resultSummary":"不存在"}},
                    {"itemId":"F","type":"file_change","status":"completed","completeness":{"truncated":true,"incomplete":false,"originalSizeBytes":42,"reason":"演示"},"fileChange":{"changes":[{"path":"app.kt","kind":"modified","oldPath":"","newPath":""}],"unifiedDiff":"@@ -1 +1 @@"}},
                    {"itemId":"X","type":"unknown","status":"unspecified"}
                  ],
                  "messages": [
                    {"itemId":"U","role":"user","text":"第一段\n第二段","status":"completed"},
                    {"itemId":"A","role":"assistant","text":"处理中","status":"running"}
                  ]
                }]
              }
            }
            """.trimIndent(),
        )

        val conversation = state.conversation!!
        assertEquals(9, conversation.items.size)
        assertEquals(9, conversation.timelineItems.size)
        assertTrue(!conversation.historyComplete)
        assertEquals(1000, conversation.turns.single().startedAtUnixMs)
        assertEquals(2000, conversation.turns.single().completedAtUnixMs)
        assertEquals(listOf("第一段", "第二段"), conversation.items[0].userMessage!!.textParts)
        assertEquals(0, conversation.items[4].command!!.exitCode)
        assertTrue(!conversation.items[5].command!!.hasExitCode)
        assertEquals(null, conversation.items[5].command!!.exitCode)
        assertEquals("读取配置", conversation.items[6].tool!!.summary)
        assertEquals("modified", conversation.items[7].fileChange!!.changes.single().kind)
        assertTrue(conversation.items[7].completeness!!.truncated)
        assertEquals("unknown", conversation.items[8].type)
    }

    @Test
    fun fallsBackToCompatibilityMessagesOnlyWhenTypedItemsAreAbsent() {
        val state = decodeCoreState(
            """{"conversation":{"codexId":"C","turns":[{"turnId":"T","status":"completed","messages":[{"itemId":"A","role":"assistant","text":"旧消息","status":"completed"}]}]}}""",
        )

        assertEquals(1, state.conversation!!.timelineItems.size)
        assertEquals("agent_message", state.conversation!!.timelineItems.single().type)
        assertEquals("旧消息", state.conversation!!.timelineItems.single().agentMessage!!.text)
    }

    @Test
    fun keepsFailedTurnCardNextToThatTurnsItems() {
        val state = decodeCoreState(
            """
            {"conversation":{"codexId":"C","historyComplete":true,"turns":[
              {"turnId":"T1","status":"failed","failure":"第一轮失败","items":[
                {"itemId":"A1","type":"agent_message","status":"completed","agentMessage":{"text":"第一轮正文"}}
              ],"messages":[]},
              {"turnId":"T2","status":"completed","failure":"","items":[
                {"itemId":"A2","type":"agent_message","status":"completed","agentMessage":{"text":"第二轮正文"}}
              ],"messages":[]}
            ]}}
            """.trimIndent(),
        )

        val entries = state.conversation!!.timelineEntries
        assertEquals(3, entries.size)
        assertEquals("A1", (entries[0] as ConversationTimelineEntry.Item).item.itemId)
        assertEquals("第一轮失败", (entries[1] as ConversationTimelineEntry.TurnFailure).failure)
        assertEquals("A2", (entries[2] as ConversationTimelineEntry.Item).item.itemId)
    }

    @Test
    fun createsConversationCommandsWithAgreedPayloads() {
        val select = coreCommand("select_codex", JSONObject().put("codexId", "CODEX-1"))
        val send = coreCommand("start_turn", JSONObject().put("text", "继续"))
        val stop = coreCommand("interrupt_turn", JSONObject())

        assertEquals("CODEX-1", select.getJSONObject("payload").getString("codexId"))
        assertEquals("继续", send.getJSONObject("payload").getString("text"))
        assertEquals(0, stop.getJSONObject("payload").length())
    }

    @Test
    fun networkChangeIncludesKnownRoute() {
        val command = networkChangedCommand("wlan0", "192.168.1.1")
        val payload = command.getJSONObject("payload")

        assertEquals("network_changed", command.getString("type"))
        assertEquals("wlan0", payload.getString("defaultInterface"))
        assertEquals("192.168.1.1", payload.getString("defaultGateway"))
    }

    @Test
    fun networkChangeStillDispatchesWhenRouteIsUnknown() {
        val payload = networkChangedCommand(null, null).getJSONObject("payload")

        assertEquals("", payload.getString("defaultInterface"))
        assertEquals("", payload.getString("defaultGateway"))
    }
}
