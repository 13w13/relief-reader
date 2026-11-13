// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a reading stopped at a limit becomes: its status, its rows, the budget of characters. */
class PdfLimitsTest {
    private val mb = 1024L * 1024
    private val text = DocText(listOf(PageText(listOf(TextBlock(BlockKind.PARAGRAPH, "Water trucking reached the camps. ".repeat(12))), null)), hasText = true)
    private val noText = DocText(listOf(PageText(emptyList(), Figure.FIGURE)), hasText = false)

    private fun status(bytes: Long = mb, doc: DocText? = null, stop: PdfStop? = null, tooHeavy: Boolean = false) =
        PdfTextStore.statusOf(bytes, doc, stop, tooHeavy)

    @Test
    fun aFileOverTheSizeLimitIsNotRead() {
        assertEquals(PdfDocEntity.TOO_LARGE, status(bytes = PdfLimits.MAX_FILE_BYTES + 1))
        assertEquals(PdfDocEntity.TEXT, status(bytes = PdfLimits.MAX_FILE_BYTES, doc = text))
    }

    @Test
    fun nothingReadBeforeALimitIsTooLargeNotFailed() {
        assertEquals(PdfDocEntity.TOO_LARGE, status(stop = PdfStop.TIME))
        assertEquals(PdfDocEntity.TOO_LARGE, status(stop = PdfStop.MEMORY))
        assertEquals(PdfDocEntity.TOO_LARGE, status(tooHeavy = true))
        // A broken or encrypted file.
        assertEquals(PdfDocEntity.FAILED, status())
    }

    @Test
    fun pagesReadBeforeALimitKeepTheirText() {
        assertEquals(PdfDocEntity.TEXT, status(doc = text, stop = PdfStop.PAGES))
        assertEquals(PdfDocEntity.TEXT, status(doc = text, stop = PdfStop.TIME))
        assertEquals(PdfDocEntity.NO_TEXT, status(doc = noText))
        assertEquals(PdfDocEntity.NO_TEXT, status(doc = noText, stop = PdfStop.PAGES))
        // Stopped by time or memory before any text: the text may come further on.
        assertEquals(PdfDocEntity.TOO_LARGE, status(doc = noText, stop = PdfStop.MEMORY))
    }

    @Test
    fun theBudgetOfCharactersFollowsTheHeap() {
        assertEquals(200_000, PdfLimits.glyphBudget(32 * mb))
        assertEquals(838_860, PdfLimits.glyphBudget(256 * mb))
        assertEquals(2_000_000, PdfLimits.glyphBudget(8192 * mb))
    }

    @Test
    fun aTextStoppedEarlyEndsWithALineToTheNextPage() {
        val pages = (0..2).map { PdfTextPage(it, listOf(TextBlock(BlockKind.PARAGRAPH, "Page ${it + 1}.")), null) }
        assertEquals(TextRow.End(2, 612), textRows(pages, total = 612).last())
        assertTrue(textRows(pages).none { it is TextRow.End })
        assertTrue(textRows(pages, total = 3).none { it is TextRow.End })
    }
}
