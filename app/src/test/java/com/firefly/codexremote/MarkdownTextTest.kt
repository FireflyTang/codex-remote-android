package com.firefly.codexremote

import androidx.compose.ui.text.LinkAnnotation
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

    @Test
    fun httpLinkCarriesClickableTargetAndInvokesHandler() {
        val opened = mutableListOf<String>()
        val rendered = renderMarkdown("访问 [官网](https://example.com/docs)") { opened += it }

        val link = rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/docs", link.url)

        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("https://example.com/docs"), opened)
    }

    @Test
    fun unsupportedAndLocalTargetsStayPlainText() {
        val rendered = renderMarkdown(
            "[邮件](mailto:user@example.com) [文件](/home/kylin1993/large.txt) [脚本](javascript:alert(1))",
        ) { error("must not open") }

        assertEquals("邮件 文件 脚本", rendered.text)
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
    }

    @Test
    fun fencedAndInlineCodeDoNotCreateClickableLinks() {
        val blocks = renderMarkdownBlocks(
            """`https://inline.example`

```text
https://block.example
```""",
        ) { error("must not open") }

        val richText = (blocks.first() as MarkdownUiBlock.RichText).text
        assertTrue(richText.getLinkAnnotations(0, richText.length).isEmpty())
        assertEquals("https://block.example", (blocks.last() as MarkdownUiBlock.CodeBlock).code)
    }

    @Test
    fun throwingOpenHandlerIsContained() {
        val rendered = renderMarkdown("[官网](http://example.com)") { error("no activity") }
        val link = rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Url

        link.linkInteractionListener!!.onClick(link)
    }

    @Test
    fun onlyAbsoluteHttpAndHttpsUrlsAreAllowed() {
        assertEquals("HTTPS://example.com/path", allowedExternalHttpUrl("HTTPS://example.com/path"))
        assertEquals(null, allowedExternalHttpUrl("https:///missing-host"))
        assertEquals(null, allowedExternalHttpUrl("relative/path"))
        assertEquals(null, allowedExternalHttpUrl("file:///home/user/file.txt"))
    }
}
