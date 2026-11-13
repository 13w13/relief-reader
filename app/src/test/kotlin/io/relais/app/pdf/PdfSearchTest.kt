// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchTest {
    private val pages = listOf(
        PdfTextPage(0, listOf(TextBlock(BlockKind.HEADING, "Faits saillants"), TextBlock(BlockKind.PARAGRAPH, "4 238 personnes déplacées à Kenscoff.")), null),
        PdfTextPage(1, listOf(TextBlock(BlockKind.PARAGRAPH, "Les DÉPLACÉES reçoivent de l’eau. Déplacées encore.")), null),
    )

    @Test
    fun foldKeepsPositions() {
        val text = "Déplacées, l’ÉTÉ — Œuvres"
        assertEquals(text.length, PdfSearch.fold(text).length)
        assertEquals("deplacees, l'ete - œuvres", PdfSearch.fold(text))
    }

    @Test
    fun findIgnoresCaseAndAccents() {
        val matches = PdfSearch.find(pages, "deplacees")
        assertEquals(3, matches.size)
        assertEquals(TextMatch(0, 1, 16, 25), matches[0])
        assertEquals("déplacées", pages[0].blocks[1].text.substring(matches[0].start, matches[0].end))
        assertEquals(1, matches[1].page)
    }

    @Test
    fun typographicApostrophesMatchPlainOnes() {
        assertEquals(1, PdfSearch.find(pages, "de l'eau").size)
    }

    @Test
    fun oneLetterFindsNothing() {
        assertTrue(PdfSearch.find(pages, " e ").isEmpty())
    }

    @Test
    fun libraryQueriesSplitWordsLikeTheTokenizer() {
        assertEquals("d* eau*", PdfSearch.ftsQuery("d’eau"))
        assertEquals(null, PdfSearch.ftsQuery("  , "))
    }

    @Test
    fun rangesMarkEveryWordWithoutCaseOrAccents() {
        val text = "Les DÉPLACÉES et les déplacées de Kenscoff"
        assertEquals(listOf(4 until 13, 21 until 30), PdfSearch.ranges(text, listOf("deplacees")))
        assertTrue(PdfSearch.ranges(text, listOf("d")).isEmpty())
    }

    @Test
    fun libraryExtractComesFromTheParagraphOfTheWord() {
        // Blocks of a page are stored one per line: a heading must not run into the text.
        val page = "Generated for the Relais tests · 1 to 7 November 2026\nWater and sanitation\nWater trucking reached 4,200 people in Site A."
        assertEquals("Water trucking reached 4,200 people in Site A.", PdfSearch.paragraphWith(page, listOf("trucking")))
    }

    @Test
    fun snippetCutsAroundTheWord() {
        val text = "Intro words come first. " + "Filler ".repeat(20) + "The borehole of Eastvale camp now serves 3,200 people. " + "More ".repeat(30)
        val snippet = PdfSearch.snippet(text, listOf("borehole"))
        assertTrue(snippet, snippet.startsWith("…"))
        assertTrue(snippet, snippet.contains("The borehole of Eastvale camp"))
        assertTrue(snippet, snippet.endsWith("…"))
    }

    @Test
    fun blocksSurviveTheDatabase() {
        val blocks = listOf(TextBlock(BlockKind.HEADING, "Santé"), TextBlock(BlockKind.PARAGRAPH, "Texte \"cité\"."), TextBlock(BlockKind.TABLE, "A · B\n1 · 2"))
        assertEquals(blocks, PageBlocks.decode(PageBlocks.encode(blocks)))
        assertEquals(emptyList<TextBlock>(), PageBlocks.decode("not json"))
    }

    @Test
    fun previewSkipsTitlesAndShortLines() {
        val doc = DocText(
            listOf(
                PageText(
                    listOf(
                        TextBlock(BlockKind.HEADING, "UNISFA statement"),
                        TextBlock(BlockKind.PARAGRAPH, "Abyei, 21 October"),
                        TextBlock(BlockKind.PARAGRAPH, "• The force reiterates its strong condemnation of the attack on the market, which killed dozens of people."),
                    ),
                    null,
                ),
            ),
            hasText = true,
        )
        assertEquals("The force reiterates its strong condemnation of the attack on the market, which killed dozens of people.", PdfTextStore.preview(doc))
    }

    @Test
    fun aHighlightIsFoundAgainWhenTheBlocksChange() {
        val page = PdfTextPage(0, listOf(TextBlock(BlockKind.PARAGRAPH, "▪ Security has worsened since late August, pushing families out.")), null)
        // At its stored place.
        assertEquals(TextMatch(0, 0, 2, 10), page.locate(0, 2, 10, "Security"))
        // Made when the first line was a heading of its own: block 1, which is gone.
        assertEquals(TextMatch(0, 0, 43, 59), page.locate(1, 0, 16, "pushing families"))
        assertNull(page.locate(0, 0, 4, "Rain"))
    }

    @Test
    fun rowsPutTablesAndFiguresInPlace() {
        val rows = textRows(
            listOf(
                PdfTextPage(0, listOf(TextBlock(BlockKind.PARAGRAPH, "Intro"), TextBlock(BlockKind.TABLE, "a · b"), TextBlock(BlockKind.TABLE, "c · d")), Figure.TABLE),
                PdfTextPage(1, listOf(TextBlock(BlockKind.HEADING, "Chart")), Figure.FIGURE),
            ),
        )
        assertEquals(
            listOf(
                TextRow.Marker(0), TextRow.Body(0, 0, TextBlock(BlockKind.PARAGRAPH, "Intro")), TextRow.Link(0, Figure.TABLE),
                TextRow.Marker(1), TextRow.Body(1, 0, TextBlock(BlockKind.HEADING, "Chart")), TextRow.Link(1, Figure.FIGURE),
            ),
            rows,
        )
        // One page: no marker.
        assertTrue(textRows(listOf(PdfTextPage(0, listOf(TextBlock(BlockKind.PARAGRAPH, "Only")), null))).none { it is TextRow.Marker })
    }
}
