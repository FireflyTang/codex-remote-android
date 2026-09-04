package com.firefly.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationPersistenceAndroidTest {
    @Test
    fun newPreferencesInstanceRestoresSelectedCodexAndIsolatedDrafts() = runBlocking {
        val preferences = HostPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val firstCodexId = "persistence-A-${UUID.randomUUID()}"
        val secondCodexId = "persistence-B-${UUID.randomUUID()}"
        val previousSelectedCodexId = preferences.codexState.first().selectedCodexId
        try {
            preferences.setSelectedCodexId(firstCodexId)
            preferences.setCodexDraft(firstCodexId, "draft A")
            preferences.setCodexDraft(secondCodexId, "draft B")

            val restored = HostPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
                .codexState.first()

            assertEquals(firstCodexId, restored.selectedCodexId)
            assertEquals("draft A", restored.drafts[firstCodexId])
            assertEquals("draft B", restored.drafts[secondCodexId])
        } finally {
            preferences.forgetCodex(firstCodexId)
            preferences.forgetCodex(secondCodexId)
            preferences.setSelectedCodexId(previousSelectedCodexId)
        }
    }
}
