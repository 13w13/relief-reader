// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import java.text.Normalizer

/** A page of the text view, as stored in pdftext.db. [index] starts at 0. */
data class PdfTextPage(val index: Int, val blocks: List<TextBlock>, val figure: Figure?)

/** Whether block [block] of this page holds [text] at [start]..[end]: a highlight made in this PDF. */
fun PdfTextPage.holds(block: Int, start: Int, end: Int, text: String): Boolean {
    val content = blocks.getOrNull(block)?.text ?: return false
    return start in 0..end && end <= content.length && content.substring(start, end) == text
}

/**
 * Where a highlight is on this page: at its stored place, or, when a newer extractor cut the
 * page into other blocks, at the first block that holds its text. Null when the page lacks it.
 */
fun PdfTextPage.locate(block: Int, start: Int, end: Int, text: String): TextMatch? {
    if (holds(block, start, end, text)) return TextMatch(index, block, start, end)
    if (text.isBlank()) return null
    blocks.forEachIndexed { b, it ->
        val at = it.text.indexOf(text)
        if (at >= 0) return TextMatch(index, b, at, at + text.length)
    }
    return null
}

/** One match of a search in the open PDF: page, block on that page, characters in the block. */
data class TextMatch(val page: Int, val block: Int, val start: Int, val end: Int)

/** Search inside the text of a PDF, without regard to case or accents. Pure Kotlin. */
object PdfSearch {
    private val folded = HashMap<Char, Char>()

    /** Lower case without accents, one character for one: positions stay the same. */
    fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = foldChar(text[i])
        return String(out)
    }

    private fun foldChar(c: Char): Char {
        if (c.code < 128) return c.lowercaseChar()
        return synchronized(folded) {
            folded.getOrPut(c) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                // Typographic apostrophes and dashes match their plain forms.
                when (base) {
                    '’', '‘' -> '\''
                    '–', '—' -> '-'
                    else -> base.lowercaseChar()
                }
            }
        }
    }

    /** Every match of [query] in the pages, in reading order. Blank or one-letter queries find nothing. */
    fun find(pages: List<PdfTextPage>, query: String): List<TextMatch> {
        val needle = fold(query.trim().replace(Regex("\\s+"), " "))
        if (needle.length < 2) return emptyList()
        val out = ArrayList<TextMatch>()
        for (page in pages) {
            page.blocks.forEachIndexed { b, block ->
                val hay = fold(block.text)
                var from = 0
                while (true) {
                    val at = hay.indexOf(needle, from)
                    if (at < 0) break
                    out += TextMatch(page.index, b, at, at + needle.length)
                    from = at + needle.length
                    if (out.size >= MAX_MATCHES) return out
                }
            }
        }
        return out
    }

    /** Words of a search typed in the Library, as FTS terms (prefix match on each word). */
    fun ftsQuery(text: String): String? {
        val terms = words(text)
        return if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
    }

    /** Words as the FTS tokenizer cuts them: "d’eau" is "d" and "eau". */
    fun words(text: String): List<String> = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** A short passage around the first word found, for a search result. */
    fun snippet(text: String, terms: List<String>, before: Int = 50, after: Int = 110): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val hay = fold(flat)
        val at = terms.map { fold(it) }.mapNotNull { t -> hay.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        var start = (at - before).coerceAtLeast(0)
        var end = (at + after).coerceAtMost(flat.length)
        // Cut on spaces, not inside words.
        if (start > 0) flat.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
        if (end < flat.length) flat.lastIndexOf(' ', end).takeIf { it > at }?.let { end = it }
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }

    /**
     * Where [terms] appear in [text], without regard to case or accents: the ranges to mark in
     * a search extract (theme-compose.md 10.10). Terms of one letter are not marked.
     */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        val hay = fold(text)
        val out = ArrayList<IntRange>()
        for (term in terms.map { fold(it.trim()) }.filter { it.length >= 2 }) {
            var from = 0
            while (true) {
                val at = hay.indexOf(term, from)
                if (at < 0) break
                out += at until at + term.length
                from = at + term.length
            }
        }
        return out.sortedBy { it.first }
    }

    /** The paragraph of a page's text (blocks joined by line breaks) where one of [terms] appears first. */
    fun paragraphWith(pageText: String, terms: List<String>): String {
        val folded = terms.map { fold(it) }.filter { it.isNotBlank() }
        return pageText.split('\n').firstOrNull { line -> val f = fold(line); folded.any { it in f } } ?: pageText
    }

    /** The passage around one match: about 50 characters before, 60 after (theme-compose.md 10.10). */
    fun snippetAt(text: String, start: Int, end: Int, before: Int = 50, after: Int = 60): String {
        var from = (start - before).coerceAtLeast(0)
        var to = (end + after).coerceAtMost(text.length)
        if (from > 0) text.indexOf(' ', from).takeIf { it in from until start }?.let { from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it >= end }?.let { to = it }
        val core = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "…" else "") + core + (if (to < text.length) "…" else "")
    }

    private const val MAX_MATCHES = 999
}
