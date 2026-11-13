// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * From characters placed on a page to text in reading order: lines, columns, paragraphs,
 * headings, tables. Pure Kotlin, without Android or PdfBox, so it runs in plain unit tests.
 * The PdfBox adapter (PdfBoxText.kt) fills [PageInput].
 */

/** One character on a page, in points, origin at the top left. [y] is the baseline. */
data class Glyph(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val size: Float,
    val bold: Boolean = false,
)

/** What the PDF reader found on one page. */
class PageInput(
    val width: Float,
    val height: Float,
    val glyphs: List<Glyph>,
    /** Area covered by images, in square points. */
    val imageArea: Float = 0f,
    /** Path operators (lines, rectangles, curves): many of them mean a chart, a map or ruled table. */
    val pathOps: Int = 0,
)

enum class BlockKind { HEADING, PARAGRAPH, TABLE }

data class TextBlock(val kind: BlockKind, val text: String)

/** What the text view sends back to the PDF for: a table, or a figure (chart, map, photo). */
enum class Figure { TABLE, FIGURE }

data class PageText(val blocks: List<TextBlock>, val figure: Figure?) {
    /** Readable characters on the page. */
    val chars: Int get() = blocks.sumOf { it.text.length }
}

data class DocText(val pages: List<PageText>, val hasText: Boolean)

object PdfLayout {
    /** A page counts as text when it has at least this many characters. */
    const val TEXT_PAGE_CHARS = 80

    /** Lays out every page, then drops the running headers and footers. */
    fun document(inputs: List<PageInput>): DocText {
        val body = bodySize(inputs)
        val lines = inputs.map { page -> segments(page) }
        val repeated = repeatedMargins(inputs, lines)
        val pages = inputs.mapIndexed { index, input ->
            val kept = lines[index].filterNot { seg -> isMargin(seg, input, repeated) }
            layoutPage(input, kept, body)
        }
        val textPages = pages.count { it.chars >= TEXT_PAGE_CHARS }
        val total = pages.sumOf { it.chars }
        val hasText = total >= 300 && textPages >= max(1, ceil(pages.size * 0.25).toInt())
        return DocText(pages, hasText)
    }

    /** The body size: the most common font size, counted in characters, to half a point. */
    internal fun bodySize(inputs: List<PageInput>): Float {
        val counts = HashMap<Int, Int>()
        for (page in inputs) for (g in page.glyphs) {
            if (g.text.isBlank()) continue
            val key = (g.size * 2).toInt()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key?.div(2f) ?: 10f
    }

    // --- Lines -------------------------------------------------------------------------------

    /**
     * A run of characters on one baseline, without a wide gap inside. A table found on the page
     * is also kept as one Seg, its box, with its rows in [table].
     */
    internal class Seg(
        val x0: Float,
        var x1: Float,
        val y: Float,
        size: Float,
        val text: StringBuilder,
        var boldChars: Int,
        var chars: Int,
        val table: List<List<Seg>>? = null,
        top: Float? = null,
        bottom: Float? = null,
    ) {
        /**
         * The size of the first letter or digit. A bullet drawn bigger or smaller than its
         * text does not count: it made the first line of each list item a heading.
         */
        var size: Float = size
        /** False while the Seg holds only a bullet or punctuation, before its first letter. */
        var sized: Boolean = true
        private val fixedTop = top
        private val fixedBottom = bottom
        val top: Float get() = fixedTop ?: (y - size * 0.8f)
        val bottom: Float get() = fixedBottom ?: (y + size * 0.25f)
        override fun toString() = "Seg(%.0f-%.0f @%.0f %.1fpt \"%s\")".format(x0, x1, y, size, text)
    }

    private val LIGATURES = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st",
    )

    /**
     * Characters to lines: rows by baseline, split where the gap is wide (a gutter, a table
     * cell). A space character drawn by the PDF always separates words; without one, a gap
     * wider than [SPACE_GAP] em does.
     */
    internal fun segments(page: PageInput): List<Seg> {
        val glyphs = page.glyphs
            // Zero-width characters some writers put after a bullet ("●" then U+200B).
            .map { g -> if (g.text.any { it in INVISIBLE }) g.copy(text = g.text.filterNot { it in INVISIBLE }) else g }
            .filter { it.text.isNotEmpty() && it.size > 0.5f && readable(it.text) }
            .sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x })
        if (glyphs.none { it.text.isNotBlank() }) return emptyList()
        // Rows: glyphs whose baselines are close.
        val rows = ArrayList<MutableList<Glyph>>()
        var current = ArrayList<Glyph>()
        var rowY = glyphs.first().y
        for (g in glyphs) {
            val tolerance = 0.3f * g.size
            if (current.isNotEmpty() && abs(g.y - rowY) > tolerance) {
                rows += current
                current = ArrayList()
            }
            if (current.isEmpty()) rowY = g.y
            current += g
        }
        if (current.isNotEmpty()) rows += current

        val out = ArrayList<Seg>()
        for (row in rows) {
            row.sortBy { it.x }
            var seg: Seg? = null
            var lastEnd = 0f
            var last: Glyph? = null
            var space = false
            for (g in row) {
                if (g.text.isBlank()) {
                    space = seg != null
                    lastEnd = max(lastEnd, g.x + g.width)
                    continue
                }
                // Fake bold: the same character printed twice at almost the same place.
                if (last != null && last.text == g.text && abs(last.x - g.x) < 0.6f && abs(last.y - g.y) < 0.6f) continue
                val text = LIGATURES[g.text] ?: g.text
                val gap = g.x - lastEnd
                val s = seg
                val letter = text.first().isLetterOrDigit()
                if (s == null || gap > SPLIT_GAP * max(g.size, s.size)) {
                    if (s != null) out += s
                    seg = Seg(g.x, g.x + g.width, g.y, g.size, StringBuilder(text), if (g.bold) text.length else 0, text.length)
                        .also { it.sized = letter }
                } else {
                    // A bullet set close to its text still gets a space after it: "▪ Text".
                    val afterMark = letter && !s.sized && s.text.length == 1 && s.text[0] in MARKS
                    if ((space || gap > SPACE_GAP * g.size || afterMark) && !s.text.endsWith(' ')) s.text.append(' ')
                    if (letter && !s.sized) {
                        s.size = g.size
                        s.sized = true
                    }
                    s.text.append(text)
                    s.x1 = max(s.x1, g.x + g.width)
                    s.chars += text.length
                    if (g.bold) s.boldChars += text.length
                }
                space = false
                lastEnd = max(lastEnd, g.x + g.width)
                last = g
            }
            seg?.let { out += it }
        }
        return out
    }

    /** Text that a font without a Unicode map turns into private-use or control characters is dropped. */
    private fun readable(text: String): Boolean = text.all { c ->
        c !in ''..'' && (c >= ' ' || c == '\t') && c != '�'
    }

    // --- Running headers and footers -------------------------------------------------------------

    private val DIGITS = Regex("\\d+")
    private val PAGE_NUMBER = Regex("(?i)^(page\\s*)?\\d{1,4}(\\s*(/|of|sur|de)\\s*\\d{1,4})?$")

    private fun marginKey(seg: Seg): String = DIGITS.replace(seg.text.toString().lowercase(), "#").replace(Regex("\\s+"), " ").trim()

    private fun inMargin(seg: Seg, page: PageInput): Boolean =
        seg.y < page.height * MARGIN_BAND || seg.top > page.height * (1 - MARGIN_BAND)

    /** Lines of the top and bottom bands repeated on many pages: running headers, footers, page numbers. */
    private fun repeatedMargins(inputs: List<PageInput>, lines: List<List<Seg>>): Set<String> {
        if (inputs.size < 2) return emptySet()
        val pagesByKey = HashMap<String, Int>()
        lines.forEachIndexed { index, segs ->
            segs.filter { inMargin(it, inputs[index]) }.map { marginKey(it) }.toSet().forEach { key ->
                pagesByKey[key] = (pagesByKey[key] ?: 0) + 1
            }
        }
        val needed = max(2, ceil(inputs.size * 0.4).toInt())
        return pagesByKey.filterValues { it >= needed }.keys
    }

    private fun isMargin(seg: Seg, page: PageInput, repeated: Set<String>): Boolean {
        if (!inMargin(seg, page)) return false
        val text = seg.text.toString().trim()
        return marginKey(seg) in repeated || PAGE_NUMBER.matches(text)
    }

    // --- Reading order (recursive XY cut) --------------------------------------------------------

    private fun layoutPage(input: PageInput, segs: List<Seg>, body: Float): PageText {
        val blocks = ArrayList<TextBlock>()
        val pending = ArrayList<List<Seg>>()
        fun flushText() {
            if (pending.isNotEmpty()) blocks += paragraphs(pending, body)
            pending.clear()
        }
        for (region in order(withTables(segs), 0)) {
            // A region that could not be cut further may hold a table and some text: top to bottom.
            val tables = region.segs.filter { it.table != null }
            if (tables.isEmpty()) {
                pending += rowsOf(region.segs)
                continue
            }
            val text = region.segs.filter { it.table == null }
            var rest = text
            for (t in tables.sortedBy { it.top }) {
                val (above, below) = rest.partition { it.y < t.top }
                if (above.isNotEmpty()) pending += rowsOf(above)
                flushText()
                blocks += TextBlock(BlockKind.TABLE, tableText(t.table!!))
                rest = below
            }
            if (rest.isNotEmpty()) pending += rowsOf(rest)
        }
        flushText()
        val chars = blocks.sumOf { it.text.length }
        val pageArea = max(1f, input.width * input.height)
        val imageShare = min(1f, input.imageArea / pageArea)
        val figure = when {
            blocks.any { it.kind == BlockKind.TABLE } -> Figure.TABLE
            imageShare >= 0.25f -> Figure.FIGURE
            chars < 600 && (imageShare >= 0.05f || input.pathOps >= 80) -> Figure.FIGURE
            input.pathOps >= 400 -> Figure.FIGURE
            else -> null
        }
        return PageText(blocks, figure)
    }

    /**
     * Finds tables by their rows: a row with three short cells or more, or with four numbers or
     * more, is a table row. Three table rows or more, with at most [TABLE_HOLE] other rows
     * between them (labels spread over two lines), make a table. Each table becomes one Seg
     * covering its box, so that the reading order places it as a whole and no column cut
     * goes through it.
     */
    internal fun withTables(segs: List<Seg>): List<Seg> {
        val rows = rowsOf(segs)
        val grid = rows.map { isTableRow(it) }
        val out = ArrayList<Seg>()
        val used = HashSet<Seg>()
        var i = 0
        while (i < rows.size) {
            if (!grid[i]) {
                i++
                continue
            }
            var j = i
            var count = 1
            var k = i + 1
            // Between two table rows, only short lines (labels on several lines) are allowed.
            while (k < rows.size && k - j <= TABLE_HOLE + 1) {
                if (grid[k]) {
                    if (rows[k].first().y - rows[j].first().y > 8 * rows[j].first().size) break
                    j = k
                    count++
                } else if (rows[k].any { it.text.length > CELL_CHARS + 10 }) {
                    break
                }
                k++
            }
            if (count >= 3) {
                val cells = (i..j).filter { grid[it] }.flatMap { rows[it] }
                val x0 = cells.minOf { it.x0 }
                val x1 = cells.maxOf { it.x1 }
                fun inside(s: Seg) = (s.x0 + s.x1) / 2 in x0..x1
                // A header row just above, inside the same box, belongs to the table.
                val start = if (i > 0 && rows[i - 1].size >= 2 && rows[i - 1].all { inside(it) && it.text.length <= CELL_CHARS * 2 }) i - 1 else i
                val top = rows[start].minOf { it.top }
                val bottom = rows[j].maxOf { it.bottom }
                // Every line inside the box: labels set over several lines between the rows.
                val members = segs.filter { it !in used && inside(it) && (it.top + it.bottom) / 2 in top..bottom }
                val tableRows = rowsOf(members)
                used += members
                val first = members.minBy { it.top }
                out += Seg(
                    x0, x1, first.y, first.size, StringBuilder(), 0, members.sumOf { it.chars },
                    table = tableRows, top = members.minOf { it.top }, bottom = members.maxOf { it.bottom },
                )
            }
            i = j + 1
        }
        if (used.isEmpty()) return segs
        return segs.filter { it !in used } + out
    }

    private val NUMBER = Regex("^[-+(]?[$€£%]?\\d[\\d,.  ]*%?\\)?$")

    private fun isTableRow(row: List<Seg>): Boolean {
        if (row.size >= 3 && row.count { it.text.length <= CELL_CHARS } >= 3) return true
        val tokens = row.flatMap { it.text.split(' ') }.filter { it.isNotBlank() }
        val numbers = tokens.count { NUMBER.matches(it) }
        return numbers >= 4 && numbers * 10 >= tokens.size * 6
    }

    internal class Region(val segs: List<Seg>)

    /** Regions in reading order: columns left to right, bands top to bottom. */
    internal fun order(segs: List<Seg>, depth: Int): List<Region> {
        if (segs.size <= 1 || depth > 60) return listOf(Region(segs))
        val columns = splitColumns(segs)
        if (columns != null) return columns.flatMap { order(it, depth + 1) }
        val bands = splitBands(segs) ?: return listOf(Region(segs))
        return bands.flatMap { order(it, depth + 1) }
    }

    /** Vertical gutters across the whole region, or null. A column of bullets or numbers alone is not one. */
    private fun splitColumns(segs: List<Seg>): List<List<Seg>>? {
        val median = medianSize(segs)
        val gutter = max(6f, GUTTER * median)
        val sorted = segs.sortedBy { it.x0 }
        val cuts = ArrayList<Float>()
        var reach = sorted.first().x1
        for (s in sorted.drop(1)) {
            if (s.x0 - reach >= gutter) cuts += (reach + s.x0) / 2
            reach = max(reach, s.x1)
        }
        if (cuts.isEmpty()) return null
        val parts = Array(cuts.size + 1) { ArrayList<Seg>() }
        for (s in segs) parts[cuts.count { it < s.x0 }] += s
        // Merge a part made only of list markers into the part on its right.
        val merged = ArrayList<MutableList<Seg>>()
        var pending: MutableList<Seg>? = null
        for (part in parts) {
            val p = pending
            val withPending = if (p != null) (p + part).toMutableList() else part
            if (part.all { it.table == null && it.text.trim().length <= 3 } && part !== parts.last()) {
                pending = withPending
            } else {
                merged += withPending
                pending = null
            }
        }
        pending?.let { if (merged.isEmpty()) merged += it else merged.last().addAll(it) }
        return if (merged.size > 1) merged else null
    }

    /** Splits at the widest horizontal gap, or returns null when the lines touch. */
    private fun splitBands(segs: List<Seg>): List<List<Seg>>? {
        val sorted = segs.sortedBy { it.top }
        var bestGap = 0f
        var bestCut = Float.NaN
        var reach = sorted.first().bottom
        for (s in sorted.drop(1)) {
            val gap = s.top - reach
            if (gap > bestGap) {
                bestGap = gap
                bestCut = (reach + s.top) / 2
            }
            reach = max(reach, s.bottom)
        }
        // Ordinary line spacing is not a cut: paragraphs are found later, from the gaps.
        if (bestCut.isNaN() || bestGap < BAND_GAP * medianSize(segs)) return null
        val (above, below) = segs.partition { it.top < bestCut }
        return if (above.isEmpty() || below.isEmpty()) null else listOf(above, below)
    }

    /** Segments grouped by row, top to bottom, each row left to right. */
    internal fun rowsOf(segs: List<Seg>): List<List<Seg>> {
        if (segs.isEmpty()) return emptyList()
        val sorted = segs.sortedWith(compareBy<Seg> { it.y }.thenBy { it.x0 })
        val rows = ArrayList<MutableList<Seg>>()
        for (s in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().y - s.y) <= 0.35f * min(row.first().size, s.size)) row += s else rows += mutableListOf(s)
        }
        rows.forEach { it.sortBy { s -> s.x0 } }
        return rows
    }

    private fun medianSize(segs: List<Seg>): Float {
        val sizes = segs.map { it.size }.sorted()
        return sizes[sizes.size / 2]
    }

    // --- Paragraphs and headings ----------------------------------------------------------------

    /** One visual line of a region: the segments of a row joined. */
    private class Line(val text: String, val x0: Float, val x1: Float, val y: Float, val size: Float, val bold: Boolean)

    /** A list item: a bullet, a dash, a number or a letter, then a space. U+F0B7 is the Symbol font bullet. */
    private val BULLET = Regex("^([${MARKS}\uF0B7·\\-–—*o]|\\d{1,2}[.)]|[a-z][.)])\\s")
    /** A list item opened by a bullet or a dash: never a heading, whatever its size. */
    private val SYMBOL_BULLET = Regex("^[${MARKS}\uF0B7·\\-–—*o]\\s")
    private val SENTENCE_END = Regex("[.!?:;”’\")]$")

    /**
     * Rows already in reading order to paragraphs and headings. A new paragraph starts at a
     * wider gap, a change of size, a list marker, or a change of weight that is not a bold
     * phrase inside a sentence.
     */
    internal fun paragraphs(rows: List<List<Seg>>, body: Float): List<TextBlock> {
        val lines = rows.mapNotNull { row ->
            // The size of most of the line: a big figure beside a bullet does not make it a heading.
            val size = row.maxBy { it.chars }.size
            val text = row.joinToString(" ") { it.text.toString().trim() }
            // A footnote mark or a superscript alone on its row.
            if (text.length <= 2 && size < 0.8f * body) return@mapNotNull null
            val chars = row.sumOf { it.chars }
            Line(text, row.first().x0, row.maxOf { it.x1 }, row.first().y, size, chars > 0 && row.sumOf { it.boldChars } * 10 >= chars * 9)
        }
        val blocks = ArrayList<TextBlock>()
        val text = StringBuilder()
        var first: Line? = null
        var previous: Line? = null
        var leading = 0f
        var lineCount = 0
        var right = 0f
        // The paragraph joins lines of two sizes or weights: it is not a heading.
        var mixed = false

        fun flush() {
            val start = first ?: return
            val content = text.toString().trim()
            if (content.isNotEmpty()) {
                val heading = !mixed && isHeading(content, start, lineCount, body)
                blocks += TextBlock(if (heading) BlockKind.HEADING else BlockKind.PARAGRAPH, content)
            }
            text.clear()
            first = null
            leading = 0f
            lineCount = 0
            right = 0f
            mixed = false
        }

        for (line in lines) {
            val prev = previous
            if (prev != null && first != null) {
                val gap = line.y - prev.y
                val edge = max(right, line.x1)
                val prevShort = prev.x1 < edge - 2.5f * prev.size
                val weight = when {
                    line.bold == prev.bold -> false
                    // A heading, then the text under it.
                    // (A bold phrase that opens a list item is not a heading.)
                    prev.bold -> lineCount <= 2 && prevShort && !BULLET.containsMatchIn(first!!.text)
                    // The text, then a heading: the line before ends a paragraph.
                    else -> !BULLET.containsMatchIn(first!!.text) && (prevShort || SENTENCE_END.containsMatchIn(prev.text))
                }
                val shift = abs(line.size - prev.size) > 0.6f || weight
                // A line cut in the middle of a sentence goes on when the next one starts in
                // lowercase, whatever its size or weight: half a sentence is never a heading.
                // Only in a list item or after a full line: a short title stays a title.
                val goesOn = shift && line.text.firstOrNull()?.isLowerCase() == true &&
                    !SENTENCE_END.containsMatchIn(prev.text) &&
                    (BULLET.containsMatchIn(first!!.text) || prev.text.length >= FULL_LINE)
                val breakHere = shift && !goesOn ||
                    gap > max(1.6f * prev.size, if (leading > 0) leading + 0.45f * prev.size else 0f) ||
                    gap < 0 ||
                    BULLET.containsMatchIn(line.text)
                if (!breakHere) {
                    if (goesOn) mixed = true
                    if (leading == 0f) leading = gap
                    join(text, line.text)
                    lineCount++
                    right = max(right, line.x1)
                    previous = line
                    continue
                }
                flush()
            }
            first = line
            text.append(line.text)
            lineCount = 1
            right = line.x1
            previous = line
        }
        flush()
        return blocks
    }

    /** Joins a line to the paragraph, removing the hyphen of a word cut at the end of a line. */
    internal fun join(text: StringBuilder, next: String) {
        val n = next.trimStart()
        if (n.isEmpty()) return
        val end = text.length
        if (end >= 2 && text[end - 1] == '\u00AD') {
            text.setLength(end - 1)
            text.append(n)
        } else if (end >= 2 && text[end - 1] == '-' && text[end - 2].isLowerCase() && n.first().isLowerCase()) {
            text.setLength(end - 1)
            text.append(n)
        } else if (end >= 2 && text[end - 1] == '-' && text[end - 2] != ' ') {
            // A hyphen that belongs to the word ("COVID-19", "IDP-" then "related").
            text.append(n)
        } else {
            if (!text.endsWith(' ')) text.append(' ')
            text.append(n)
        }
    }

    private fun isHeading(content: String, first: Line, lines: Int, body: Float): Boolean {
        if (content.length > 200 || lines > 3) return false
        if (SYMBOL_BULLET.containsMatchIn(content)) return false
        if (first.size >= body * HEADING_RATIO) return true
        if (BULLET.containsMatchIn(content)) return false
        val letters = content.count { it.isLetter() }
        val shortBold = first.bold && content.length <= 120 && lines <= 2 && !content.trimEnd().endsWith('.')
        val capitals = letters >= 3 && content.none { it.isLowerCase() } && content.length <= 80 && first.size >= body
        return shortBold || capitals && first.bold
    }

    private fun tableText(rows: List<List<Seg>>): String =
        rows.joinToString("\n") { row -> row.joinToString(" · ") { it.text.toString().trim() } }

    private const val SPACE_GAP = 0.17f
    private const val SPLIT_GAP = 0.9f
    private const val GUTTER = 0.8f
    private const val MARGIN_BAND = 0.085f
    private const val HEADING_RATIO = 1.18f
    private const val BAND_GAP = 0.5f
    private const val CELL_CHARS = 30
    private const val TABLE_HOLE = 6
    /** Characters of a line of text that runs the width of its column. */
    private const val FULL_LINE = 50

    /** Bullets drawn as symbols ("▪" is the one Word takes from Wingdings). */
    const val MARKS = "•▪■□●○◦‣⁃∙◆◇►▸➢➤✓✔❖"
    private const val INVISIBLE = "\u200B\u2060\uFEFF"
}
