// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout rules on pages built by hand: characters with their place, size and weight. */
class PdfLayoutTest {
    /** One glyph per character, 0.5 em wide; a space is a blank glyph, as PDF writers draw it. */
    private fun line(text: String, x: Float, y: Float, size: Float = 10f, bold: Boolean = false): List<Glyph> {
        var cx = x
        return text.map { c ->
            val g = Glyph(c.toString(), cx, y, size * 0.5f, size, bold)
            cx += size * 0.5f
            g
        }
    }

    private fun page(vararg lines: List<Glyph>, imageArea: Float = 0f, pathOps: Int = 0) =
        PageInput(600f, 800f, lines.toList().flatten(), imageArea, pathOps)

    private fun texts(doc: DocText, page: Int = 0) = doc.pages[page].blocks.map { it.text }

    @Test
    fun twoColumnsReadLeftThenRight() {
        // Written row by row across the columns, as some PDF writers do.
        val glyphs = mutableListOf<List<Glyph>>()
        val left = listOf("Water trucking reached", "the northern camps this", "month after the floods.")
        val right = listOf("Mobile clinics visited", "eleven villages cut off", "by the rising river.")
        for (i in 0..2) {
            glyphs += line(left[i], 40f, 100f + 13 * i)
            glyphs += line(right[i], 320f, 100f + 13 * i)
        }
        val doc = PdfLayout.document(listOf(page(*glyphs.toTypedArray())))
        assertEquals(
            listOf(
                "Water trucking reached the northern camps this month after the floods.",
                "Mobile clinics visited eleven villages cut off by the rising river.",
            ),
            texts(doc),
        )
    }

    @Test
    fun titleAcrossThePageComesBeforeTheColumns() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    line("Northland: Situation Report No. 7", 40f, 60f, size = 18f, bold = true),
                    line("Left column first line", 40f, 110f),
                    line("Right column first line", 320f, 110f),
                    line("left column second line", 40f, 123f),
                    line("right column second line", 320f, 123f),
                ),
            ),
        )
        val blocks = doc.pages[0].blocks
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals("Northland: Situation Report No. 7", blocks[0].text)
        assertEquals("Left column first line left column second line", blocks[1].text)
        assertEquals("Right column first line right column second line", blocks[2].text)
    }

    @Test
    fun runningHeadersAndPageNumbersAreDropped() {
        val pages = (1..3).map { n ->
            page(
                line("Northland Situation Report | $n", 40f, 30f, size = 8f),
                line("Body text of page $n goes here.", 40f, 300f),
                line("Page $n of 3", 270f, 780f, size = 8f),
            )
        }
        val doc = PdfLayout.document(pages)
        for (n in 0..2) assertEquals(listOf("Body text of page ${n + 1} goes here."), texts(doc, n))
    }

    @Test
    fun aLonePageNumberIsDroppedEvenOnOnePage() {
        val doc = PdfLayout.document(listOf(page(line("Statement of the day.", 40f, 300f), line("1", 300f, 780f))))
        assertEquals(listOf("Statement of the day."), texts(doc))
    }

    @Test
    fun hyphensAtLineEndsAreJoined() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    line("the humani-", 40f, 100f),
                    line("tarian response to COVID-", 40f, 113f),
                    line("19 continues.", 40f, 126f),
                ),
            ),
        )
        assertEquals(listOf("the humanitarian response to COVID-19 continues."), texts(doc))
    }

    @Test
    fun boldHeadingThenTextAreTwoBlocks() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    line("Health", 40f, 100f, bold = true),
                    line("Mobile clinics visited eleven villages that had been cut off by the floods.", 40f, 116f),
                    line("Partners repaired two hand pumps and one borehole.", 40f, 129f),
                ),
            ),
        )
        val blocks = doc.pages[0].blocks
        assertEquals(2, blocks.size)
        assertEquals(TextBlock(BlockKind.HEADING, "Health"), blocks[0])
        assertEquals(BlockKind.PARAGRAPH, blocks[1].kind)
    }

    @Test
    fun aBoldPhraseOpeningAListItemStaysInIt() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    line("• Some 255 people are staying in two shelters", 40f, 100f, bold = true),
                    line("  in the district, and two more shelters are open.", 40f, 113f),
                    line("• Roads remain closed.", 40f, 126f),
                ),
            ),
        )
        assertEquals(
            listOf("• Some 255 people are staying in two shelters in the district, and two more shelters are open.", "• Roads remain closed."),
            texts(doc),
        )
        assertTrue(doc.pages[0].blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test
    fun aBulletBiggerThanItsTextMakesNoHeading() {
        // A "▪" drawn at 15 pt before 10 pt text: in v0.2.0 the first line of each item became
        // a heading and the rest of the item a paragraph (a UNHCR flash update, on the phone).
        fun item(y: Float, first: String, next: String) = listOf(
            listOf(Glyph("▪", 40f, y, 5f, 15f)) + line(" $first", 45f, y),
            line(next, 52f, y + 13),
        )
        val doc = PdfLayout.document(
            listOf(
                page(
                    *(
                        item(100f, "Security in the north has steadily worsened since", "late August, pushing families towards the river towns.") +
                            item(140f, "Few basic services, poor site", "management and crowding put people at risk.")
                        ).toTypedArray(),
                ),
            ),
        )
        assertEquals(
            listOf(
                "▪ Security in the north has steadily worsened since late August, pushing families towards the river towns.",
                "▪ Few basic services, poor site management and crowding put people at risk.",
            ),
            texts(doc),
        )
        assertTrue(doc.pages[0].blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test
    fun aLineCutMidSentenceIsNeverAHeading() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    // A list item whose whole first line is bigger.
                    line("■ Water trucking reached the camps on the eastern bank", 40f, 100f, size = 12.5f),
                    line("of the river after the bridge reopened.", 40f, 114f),
                    // A full line in bold, then the rest of its sentence.
                    line("Mobile clinics in the northern districts were closed again", 40f, 150f, size = 12f, bold = true),
                    line("because of the fighting, and staff moved to the river towns.", 40f, 164f),
                ),
            ),
        )
        assertEquals(
            listOf(
                "■ Water trucking reached the camps on the eastern bank of the river after the bridge reopened.",
                "Mobile clinics in the northern districts were closed again because of the fighting, and staff moved to the river towns.",
            ),
            texts(doc),
        )
        assertTrue(doc.pages[0].blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test
    fun aShortTitleAboveALowercaseLineStaysATitle() {
        val doc = PdfLayout.document(
            listOf(
                page(
                    line("Key figures", 40f, 100f, size = 14f, bold = true),
                    line("as of 30 October, in the three northern districts", 40f, 118f),
                    line("Some 12,000 people have fled their homes since August.", 40f, 131f),
                ),
            ),
        )
        assertEquals(TextBlock(BlockKind.HEADING, "Key figures"), doc.pages[0].blocks.first())
    }

    @Test
    fun bulletsSetTightOrWithAZeroWidthSpaceOpenListItems() {
        val tight = listOf(Glyph("●", 40f, 100f, 5f, 10f)) + line("Roads remain closed in the east", 45.5f, 100f)
        val zeroWidth = listOf(Glyph("●", 40f, 113f, 5f, 10f), Glyph("\u200B", 45f, 113f, 0f, 10f)) + line("Markets reopened.", 45.5f, 113f)
        val doc = PdfLayout.document(listOf(page(tight, zeroWidth)))
        assertEquals(listOf("● Roads remain closed in the east", "● Markets reopened."), texts(doc))
    }

    @Test
    fun aGridOfShortCellsIsOneTable() {
        val rows = listOf(
            listOf("District", "Displaced", "Water", "Health"),
            listOf("Eastvale", "4,100", "3,200", "1,150"),
            listOf("Riverside", "2,300", "1,900", "800"),
            listOf("Total", "6,400", "5,100", "1,950"),
        )
        val glyphs = rows.flatMapIndexed { r, cells -> cells.mapIndexed { c, cell -> line(cell, 40f + 130 * c, 200f + 18 * r, size = 9f) } }
        val doc = PdfLayout.document(listOf(page(line("Table 1: People reached", 40f, 170f, bold = true), *glyphs.toTypedArray())))
        val blocks = doc.pages[0].blocks
        assertEquals(BlockKind.TABLE, blocks.last().kind)
        assertEquals("District · Displaced · Water · Health", blocks.last().text.lines().first())
        assertEquals(4, blocks.last().text.lines().size)
        assertEquals(Figure.TABLE, doc.pages[0].figure)
    }

    @Test
    fun aPageOfImagesHasNoTextAndSendsToThePdf() {
        val doc = PdfLayout.document(listOf(page(imageArea = 300_000f), page(imageArea = 300_000f)))
        assertFalse(doc.hasText)
        assertEquals(Figure.FIGURE, doc.pages[0].figure)
    }

    @Test
    fun aTextPageHasNoFigure() {
        val sentence = "Partners call for funding to keep water services running through the dry season."
        val lines = (0 until 30).map { line(sentence, 40f, 100f + 13 * it) }
        val doc = PdfLayout.document(listOf(page(*lines.toTypedArray())))
        assertTrue(doc.hasText)
        assertNull(doc.pages[0].figure)
    }

    @Test
    fun spacesComeFromBlankGlyphsAndFromGaps() {
        // "tight words": the PDF draws a space glyph narrower than the gap rule would need.
        val tight = listOf(
            Glyph("a", 40f, 100f, 5f, 10f), Glyph(" ", 45f, 100f, 0.5f, 10f), Glyph("b", 45.6f, 100f, 5f, 10f),
            // "c" after a gap of 3 points, without a space glyph.
            Glyph("c", 53.6f, 100f, 5f, 10f),
        )
        val doc = PdfLayout.document(listOf(PageInput(600f, 800f, tight)))
        assertEquals("a b c", doc.pages[0].blocks.single().text)
    }
}
