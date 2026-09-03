package com.firefly.codexremote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser
import java.net.URI

private val markdownParser: Parser = Parser.builder()
    .extensions(
        listOf(
            AutolinkExtension.create(),
            StrikethroughExtension.create(),
        ),
    )
    .build()

@Composable
internal fun MarkdownBody(
    markdown: String,
    emptyText: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val text = markdown.ifBlank { emptyText }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val blocks = remember(text, codeBackground, linkColor, uriHandler) {
        renderMarkdownBlocks(text, codeBackground, linkColor) { url ->
            runCatching { uriHandler.openUri(url) }
        }
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownUiBlock.RichText -> SelectionContainer {
                    Text(block.text, style = style)
                }
                is MarkdownUiBlock.CodeBlock -> Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(9.dp),
                    color = codeBackground,
                ) {
                    SelectionContainer {
                        Text(
                            block.code,
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

internal sealed interface MarkdownUiBlock {
    data class RichText(val text: AnnotatedString) : MarkdownUiBlock
    data class CodeBlock(val code: String, val info: String) : MarkdownUiBlock
}

internal fun renderMarkdownBlocks(
    markdown: String,
    codeBackground: Color = Color(0xFFECE7DC),
    linkColor: Color = Color(0xFF0B57D0),
    onOpenLink: ((String) -> Unit)? = null,
): List<MarkdownUiBlock> = runCatching {
    val root = markdownParser.parse(markdown)
    buildList {
        var current = root.firstChild
        var richStart: Node? = null
        fun flushRich(stopBefore: Node?) {
            richStart?.let { start ->
                MarkdownComposer(codeBackground, linkColor, onOpenLink).composeRange(start, stopBefore)
                    .takeIf { it.isNotBlank() }
                    ?.let { add(MarkdownUiBlock.RichText(it)) }
            }
            richStart = null
        }
        while (current != null) {
            val next = current.next
            when (val node = current) {
                is FencedCodeBlock -> {
                    flushRich(node)
                    add(MarkdownUiBlock.CodeBlock(node.literal.trimEnd(), node.info.orEmpty()))
                }
                is IndentedCodeBlock -> {
                    flushRich(node)
                    add(MarkdownUiBlock.CodeBlock(node.literal.trimEnd(), ""))
                }
                else -> if (richStart == null) richStart = node
            }
            current = next
        }
        flushRich(null)
    }
}.getOrElse { listOf(MarkdownUiBlock.RichText(AnnotatedString(markdown))) }

internal fun renderMarkdown(
    markdown: String,
    codeBackground: Color = Color(0xFFECE7DC),
    linkColor: Color = Color(0xFF0B57D0),
    onOpenLink: ((String) -> Unit)? = null,
): AnnotatedString =
    runCatching { MarkdownComposer(codeBackground, linkColor, onOpenLink).compose(markdownParser.parse(markdown)) }
        .getOrElse { AnnotatedString(markdown) }

internal fun allowedExternalHttpUrl(destination: String): String? = runCatching {
    val uri = URI(destination)
    destination.takeIf {
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }
}.getOrNull()

private class MarkdownComposer(
    private val codeBackground: Color,
    private val linkColor: Color,
    private val onOpenLink: ((String) -> Unit)?,
) {
    private val builder = AnnotatedString.Builder()

    fun compose(root: Node): AnnotatedString {
        renderBlockSequence(root.firstChild, 0)
        return builder.toAnnotatedString()
    }

    fun composeRange(first: Node, stopBefore: Node?): AnnotatedString {
        renderBlockSequence(first, 0, stopBefore)
        return builder.toAnnotatedString()
    }

    private fun renderBlockSequence(node: Node?, listDepth: Int, stopBefore: Node? = null) {
        var current = node
        while (current != null && current !== stopBefore) {
            renderBlock(current, listDepth)
            current = current.next
            if (current != null && current !== stopBefore && !endsWith("\n\n")) {
                if (!endsWith('\n')) builder.append('\n')
                builder.append('\n')
            }
        }
    }

    private fun renderBlock(node: Node, listDepth: Int) {
        when (node) {
            is Paragraph -> renderInlineSequence(node.firstChild, listDepth)
            is Heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                renderInlineSequence(node.firstChild, listDepth)
            }
            is BulletList -> renderBulletList(node, listDepth)
            is OrderedList -> renderOrderedList(node, listDepth)
            is FencedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                builder.append(node.literal.trimEnd())
            }
            is IndentedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                builder.append(node.literal.trimEnd())
            }
            is BlockQuote -> {
                builder.append("> ")
                renderBlockSequence(node.firstChild, listDepth + 1)
            }
            is Document -> renderBlockSequence(node.firstChild, listDepth)
            else -> renderInlineSequence(node.firstChild, listDepth)
        }
    }

    private fun renderBulletList(node: BulletList, listDepth: Int) {
        var item = node.firstChild
        while (item != null) {
            if (item is ListItem) {
                builder.append("  ".repeat(listDepth))
                builder.append("• ")
                renderListItem(item, listDepth)
            }
            item = item.next
            if (item != null && !endsWith('\n')) builder.append('\n')
        }
    }

    private fun renderOrderedList(node: OrderedList, listDepth: Int) {
        var item = node.firstChild
        var number = node.startNumber
        while (item != null) {
            if (item is ListItem) {
                builder.append("  ".repeat(listDepth))
                builder.append(number.toString())
                builder.append(". ")
                renderListItem(item, listDepth)
                number += 1
            }
            item = item.next
            if (item != null && !endsWith('\n')) builder.append('\n')
        }
    }

    private fun renderListItem(item: ListItem, listDepth: Int) {
        var child = item.firstChild
        var first = true
        while (child != null) {
            if (!first && !endsWith('\n')) builder.append('\n')
            when (child) {
                is Paragraph -> renderInlineSequence(child.firstChild, listDepth + 1)
                is BulletList -> {
                    if (!endsWith('\n')) builder.append('\n')
                    renderBulletList(child, listDepth + 1)
                }
                is OrderedList -> {
                    if (!endsWith('\n')) builder.append('\n')
                    renderOrderedList(child, listDepth + 1)
                }
                else -> renderBlock(child, listDepth + 1)
            }
            first = false
            child = child.next
        }
    }

    private fun renderInlineSequence(node: Node?, listDepth: Int) {
        var current = node
        while (current != null) {
            val currentNode = current
            when (currentNode) {
                is MarkdownTextNode -> builder.append(currentNode.literal)
                is SoftLineBreak, is HardLineBreak -> builder.append('\n')
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    renderInlineSequence(currentNode.firstChild, listDepth)
                }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    renderInlineSequence(currentNode.firstChild, listDepth)
                }
                is Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                ) {
                    builder.append(currentNode.literal)
                }
                is Link -> renderLink(currentNode, listDepth)
                else -> renderInlineSequence(currentNode.firstChild, listDepth)
            }
            current = current.next
        }
    }

    private fun renderLink(node: Link, listDepth: Int) {
        val destination = allowedExternalHttpUrl(node.destination)
        val openLink = onOpenLink
        if (destination != null && openLink != null) {
            builder.pushLink(
                LinkAnnotation.Url(
                    destination,
                    linkInteractionListener = LinkInteractionListener {
                        runCatching { openLink(destination) }
                    },
                ),
            )
        }
        withStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            renderInlineSequence(node.firstChild, listDepth)
        }
        if (destination != null && openLink != null) builder.pop()
    }

    private fun endsWith(char: Char): Boolean =
        builder.length > 0 && builder.toString().last() == char

    private fun endsWith(text: String): Boolean =
        builder.length >= text.length && builder.toString().endsWith(text)

    private fun withStyle(style: SpanStyle, block: () -> Unit) {
        builder.pushStyle(style)
        block()
        builder.pop()
    }
}
