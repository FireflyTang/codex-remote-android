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

    @Test
    fun separatesFencedCodeFromRichTextBlocks() {
        val blocks = renderMarkdownBlocks(
            """说明 **如下**：

```kotlin
val longValue = "abcdefghijklmnopqrstuvwxyz"
println(longValue)
```

完成。""",
        )

        assertEquals(3, blocks.size)
        assertEquals("说明 如下：", (blocks[0] as MarkdownUiBlock.RichText).text.text)
        assertEquals(
            "val longValue = \"abcdefghijklmnopqrstuvwxyz\"\nprintln(longValue)",
            (blocks[1] as MarkdownUiBlock.CodeBlock).code,
        )
        assertEquals("kotlin", (blocks[1] as MarkdownUiBlock.CodeBlock).info)
        assertEquals("完成。", (blocks[2] as MarkdownUiBlock.RichText).text.text)
    }

    @Test
    fun inlineCodeStaysInsideRichText() {
        val blocks = renderMarkdownBlocks("使用 `git status` 检查。")

        assertEquals(1, blocks.size)
        val text = (blocks.single() as MarkdownUiBlock.RichText).text
        assertEquals("使用 git status 检查。", text.text)
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }
}
