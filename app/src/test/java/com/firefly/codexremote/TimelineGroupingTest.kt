package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGroupingTest {
    @Test
    fun groupsOnlyConsecutiveProcessItemsInTheSameTurn() {
        val entries = listOf(
            item("u1", "T1", "user_message"),
            item("r1", "T1", "reasoning_summary"),
            item("c1", "T1", "command"),
            item("a1", "T1", "agent_message"),
            item("p2", "T2", "plan"),
            ConversationTimelineEntry.TurnFailure("T2", "failed"),
        )

        val grouped = groupTimelineEntries(entries)

        assertEquals(5, grouped.size)
        assertTrue(grouped[0] is TimelineDisplayEntry.Message)
        assertEquals(listOf("r1", "c1"), (grouped[1] as TimelineDisplayEntry.ProcessGroup).items.map { it.itemId })
        assertTrue(grouped[2] is TimelineDisplayEntry.Message)
        assertEquals(listOf("p2"), (grouped[3] as TimelineDisplayEntry.ProcessGroup).items.map { it.itemId })
        assertTrue(grouped[4] is TimelineDisplayEntry.TurnFailure)
    }

    @Test
    fun unknownAndMissingTurnItemsRemainSafeAndOrdered() {
        val grouped = groupTimelineEntries(
            listOf(
                item("unknown", "T1", "future_process_type"),
                item("missing-1", "", "command"),
                item("missing-2", "", "tool"),
            ),
        )

        assertEquals(3, grouped.size)
        assertEquals("future_process_type", (grouped[0] as TimelineDisplayEntry.ProcessGroup).items.single().type)
        assertEquals("missing-1", (grouped[1] as TimelineDisplayEntry.ProcessGroup).items.single().itemId)
        assertEquals("missing-2", (grouped[2] as TimelineDisplayEntry.ProcessGroup).items.single().itemId)
    }

    @Test
    fun importedHistoryNoticeRequiresAProvenanceAndStrictImportBoundary() {
        val imported = "PROVENANCE_KIND_IMPORTED_HISTORY"
        val historical = turn("history", startedAt = 99, turnProvenance = imported, itemProvenance = imported)
        val sameMillisecond = turn("same", startedAt = 100, turnProvenance = imported, itemProvenance = imported)
        val newer = turn("new", startedAt = 101, turnProvenance = imported, itemProvenance = imported)

        assertEquals("此轮来自导入的历史记录", timelineTurnProvenanceNotice(historical, importedAtUnixMs = 100))
        assertEquals(null, timelineTurnProvenanceNotice(sameMillisecond, importedAtUnixMs = 100))
        assertEquals(null, timelineTurnProvenanceNotice(newer, importedAtUnixMs = 100))
        assertEquals(null, timelineTurnProvenanceNotice(historical, importedAtUnixMs = 0))
        assertEquals(null, timelineTurnProvenanceNotice(turn("unspecified", 99, "", ""), 100))
    }

    @Test
    fun explicitLiveWireSuppressesAnInheritedImportedTurnMarker() {
        val turn = turn(
            id = "live",
            startedAt = 99,
            turnProvenance = "PROVENANCE_KIND_IMPORTED_HISTORY",
            itemProvenance = "PROVENANCE_KIND_LIVE_WIRE",
        )

        assertEquals(null, timelineTurnProvenanceNotice(turn, importedAtUnixMs = 100))
    }

    @Test
    fun importedProvenanceNoticeIsOnlyShownOnTheFirstEntryInATurn() {
        val imported = "PROVENANCE_KIND_IMPORTED_HISTORY"
        val turn = turn("history", startedAt = 99, turnProvenance = imported, itemProvenance = imported)
        val first = TimelineDisplayEntry.Message(turn.items[0], "first")
        val second = TimelineDisplayEntry.Message(turn.items[1], "second")

        assertEquals(
            listOf("此轮来自导入的历史记录"),
            timelineUiProtocolNotices(first, turn, firstEntryInTurn = true, importedAtUnixMs = 100),
        )
        assertEquals(
            emptyList<String>(),
            timelineUiProtocolNotices(second, turn, firstEntryInTurn = false, importedAtUnixMs = 100),
        )
    }

    private fun item(id: String, turnId: String, type: String) = ConversationTimelineEntry.Item(
        ConversationItem(itemId = id, turnId = turnId, type = type, status = "completed"),
    )

    private fun turn(
        id: String,
        startedAt: Long,
        turnProvenance: String,
        itemProvenance: String,
    ) = ConversationTurn(
        turnId = id,
        status = "completed",
        failure = "",
        startedAtUnixMs = startedAt,
        completedAtUnixMs = startedAt + 1,
        items = listOf(
            ConversationItem("$id-1", id, "agent_message", "completed", provenance = itemProvenance),
            ConversationItem("$id-2", id, "agent_message", "completed", provenance = itemProvenance),
        ),
        messages = emptyList(),
        provenance = turnProvenance,
    )
}
