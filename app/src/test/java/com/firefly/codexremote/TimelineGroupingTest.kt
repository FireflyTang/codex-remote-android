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

    private fun item(id: String, turnId: String, type: String) = ConversationTimelineEntry.Item(
        ConversationItem(itemId = id, turnId = turnId, type = type, status = "completed"),
    )
}
