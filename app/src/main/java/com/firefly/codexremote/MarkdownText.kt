package com.firefly.codexremote

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    val rendered = remember(text) { renderMarkdown(text) }
    SelectionContainer {
        Text(rendered, style = style)
    }
}

internal fun renderMarkdown(markdown: String): AnnotatedString =
    runCatching { MarkdownComposer().compose(markdownParser.parse(markdown)) }
        .getOrElse { AnnotatedString(markdown) }

private class MarkdownComposer {
    private val builder = AnnotatedString.Builder()

    fun compose(root: Node): AnnotatedString {
        renderBlockSequence(root.firstChild, 0)
        return builder.toAnnotatedString()
    }

    private fun renderBlockSequence(node: Node?, listDepth: Int) {
        var current = node
        while (current != null) {
            renderBlock(current, listDepth)
            current = current.next
            if (current != null && !endsWith("\n\n")) {
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
            is FencedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                builder.append(node.literal.trimEnd())
            }
            is IndentedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
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
                        background = Color(0xFFECE7DC),
                    ),
                ) {
                    builder.append(currentNode.literal)
                }
                is Link -> withStyle(
                    SpanStyle(
                        color = Color(0xFF0B57D0),
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    renderInlineSequence(currentNode.firstChild, listDepth)
                }
                else -> renderInlineSequence(currentNode.firstChild, listDepth)
            }
            current = current.next
        }
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
