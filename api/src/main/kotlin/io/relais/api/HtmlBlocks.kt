// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/** A run of text with its style. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null,
)

/** One block of a report body, rendered natively by the app (no WebView). */
sealed interface Block {
    data class Paragraph(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class ListItem(val ordered: Boolean, val number: Int, val depth: Int, val spans: List<Span>) : Block
    data class Quote(val spans: List<Span>) : Block
    data class Table(val rows: List<List<String>>, val hasHeader: Boolean) : Block
    data class Image(val url: String, val alt: String?) : Block
    data object Rule : Block
}

/**
 * Turns the `body-html` of a report into blocks. ReliefWeb builds that HTML from Markdown
 * (CommonMark with tables): paragraphs, headings, lists, links, emphasis, quotes, tables,
 * images and rules. Anything else falls back to plain text.
 */
object HtmlBlocks {
    private val SAFELIST: Safelist = Safelist.relaxed()
        .addTags("hr")
        .removeTags("colgroup", "col")

    fun parse(html: String, baseUri: String = "https://reliefweb.int"): List<Block> {
        val clean = Jsoup.clean(html, baseUri, SAFELIST)
        val body = Jsoup.parseBodyFragment(clean, baseUri).body()
        val out = mutableListOf<Block>()
        val pending = mutableListOf<Span>()
        walkContainer(body, out, pending)
        flush(pending, out)
        return out
    }

    /** Text of the body, for full-text search in the Library. */
    fun plainText(html: String): String = Jsoup.parse(html).text()

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "table", "hr",
        "pre", "div", "section", "article", "figure", "figcaption", "header", "footer", "dl",
    )

    private fun walkContainer(container: Element, out: MutableList<Block>, pending: MutableList<Span>) {
        for (child in container.childNodes()) {
            if (child is Element && (child.tagName() in BLOCK_TAGS || child.tagName() == "img")) {
                flush(pending, out)
                block(child, out)
            } else {
                inline(child, Style(), pending)
            }
        }
    }

    private fun block(el: Element, out: MutableList<Block>) {
        when (val tag = el.tagName()) {
            "p", "pre", "figcaption", "dl" -> {
                // A paragraph may hold an image on its own line.
                val spans = mutableListOf<Span>()
                for (child in el.childNodes()) {
                    if (child is Element && child.tagName() == "img") {
                        flush(spans, out)
                        image(child)?.let(out::add)
                    } else {
                        inline(child, Style(), spans)
                    }
                }
                flush(spans, out)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val spans = spansOf(el)
                if (spans.isNotEmpty()) out += Block.Heading(tag.substring(1).toInt(), spans)
            }
            "ul", "ol" -> list(el, depth = 0, out = out)
            "blockquote" -> {
                val spans = mutableListOf<Span>()
                el.children().forEachIndexed { index, child ->
                    if (index > 0 && spans.isNotEmpty()) spans += Span("\n")
                    inline(child, Style(), spans)
                }
                if (el.children().isEmpty()) inline(el, Style(), spans)
                val merged = merge(spans)
                if (merged.isNotEmpty()) out += Block.Quote(merged)
            }
            "table" -> table(el)?.let(out::add)
            "hr" -> out += Block.Rule
            "img" -> image(el)?.let(out::add)
            else -> {
                val pending = mutableListOf<Span>()
                walkContainer(el, out, pending)
                flush(pending, out)
            }
        }
    }

    private fun list(el: Element, depth: Int, out: MutableList<Block>) {
        val ordered = el.tagName() == "ol"
        var number = el.attr("start").toIntOrNull() ?: 1
        for (li in el.children()) {
            if (li.tagName() != "li") continue
            val spans = mutableListOf<Span>()
            val nested = mutableListOf<Element>()
            for (child in li.childNodes()) {
                if (child is Element && (child.tagName() == "ul" || child.tagName() == "ol")) {
                    nested.add(child)
                } else {
                    if (child is Element && child.tagName() == "p" && spans.isNotEmpty()) spans += Span(" ")
                    inline(child, Style(), spans)
                }
            }
            val merged = merge(spans)
            if (merged.isNotEmpty()) out += Block.ListItem(ordered, number, depth, merged)
            number++
            nested.forEach { list(it, depth + 1, out) }
        }
    }

    private fun table(el: Element): Block.Table? {
        val rows = el.select("tr").map { tr ->
            tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { it.text().trim() }
        }.filter { row -> row.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return null
        val hasHeader = el.selectFirst("thead") != null ||
            el.selectFirst("tr")?.children()?.all { it.tagName() == "th" } == true
        return Block.Table(rows, hasHeader)
    }

    private fun image(el: Element): Block.Image? {
        val src = el.absUrl("src").ifBlank { el.attr("src") }
        if (src.isBlank() || !src.startsWith("http")) return null
        return Block.Image(src, el.attr("alt").takeIf { it.isNotBlank() })
    }

    private data class Style(val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

    private fun spansOf(el: Element): List<Span> {
        val spans = mutableListOf<Span>()
        inline(el, Style(), spans)
        return merge(spans)
    }

    private fun inline(node: Node, style: Style, out: MutableList<Span>) {
        when (node) {
            is TextNode -> {
                val text = node.wholeText.replace(WHITESPACE, " ")
                if (text.isNotEmpty()) out += Span(text, style.bold, style.italic, style.link)
            }
            is Element -> {
                val next = when (node.tagName()) {
                    "b", "strong" -> style.copy(bold = true)
                    "i", "em" -> style.copy(italic = true)
                    "a" -> style.copy(link = node.absUrl("href").ifBlank { node.attr("href") }.takeIf { it.isNotBlank() })
                    else -> style
                }
                when (node.tagName()) {
                    "br" -> out += Span("\n")
                    "img" -> Unit // images inside running text are dropped
                    else -> node.childNodes().forEach { inline(it, next, out) }
                }
            }
        }
    }

    private fun flush(pending: MutableList<Span>, out: MutableList<Block>) {
        val merged = merge(pending)
        if (merged.isNotEmpty()) out += Block.Paragraph(merged)
        pending.clear()
    }

    /** Joins neighbours with the same style and trims the ends of the paragraph. */
    private fun merge(spans: List<Span>): List<Span> {
        val result = mutableListOf<Span>()
        for (span in spans) {
            val last = result.lastOrNull()
            if (last != null && last.bold == span.bold && last.italic == span.italic && last.link == span.link) {
                result[result.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                result += span
            }
        }
        // Collapse double spaces created by joins, then trim the whole paragraph.
        val cleaned = result.map { it.copy(text = it.text.replace(DOUBLE_SPACE, " ").replace(SPACE_NEWLINE, "\n")) }
            .toMutableList()
        while (cleaned.isNotEmpty() && cleaned.first().text.isBlank()) cleaned.removeAt(0)
        while (cleaned.isNotEmpty() && cleaned.last().text.isBlank()) cleaned.removeAt(cleaned.lastIndex)
        if (cleaned.isEmpty()) return emptyList()
        cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
        cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
        return cleaned.filter { it.text.isNotEmpty() }
    }

    private val WHITESPACE = Regex("[ \\t\\r\\n\\u00a0]+")
    private val DOUBLE_SPACE = Regex(" {2,}")
    private val SPACE_NEWLINE = Regex(" ?\\n ?")
}
