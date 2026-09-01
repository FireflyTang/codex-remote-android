package com.firefly.codexremote

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun rendersBasicMarkdownWithoutLiteralMarkers() {
        val rendered = renderMarkdown("## 标题\n\n这是 **重点**、*强调* 和 `代码`。")

        assertEquals("标题\n\n这是 重点、强调 和 代码。", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun rendersListsAsReadablePlainText() {
        val rendered = renderMarkdown("- 第一项\n- 第二项")

        assertEquals("• 第一项\n• 第二项", rendered.text)
    }
}
