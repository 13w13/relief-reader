// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import io.relais.app.data.FileLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The PDF line of list rows: what the API or the file on the phone says, nothing made up. */
class PdfFactsTest {
    private val url = "https://example.org/test-report.pdf"

    @Test
    fun theApiPageCountComesFirst() {
        assertEquals(12L, PdfFacts.pages(FileLite(url, pages = 12), mapOf(url to 14)))
    }

    @Test
    fun withoutItThePagesOfTheFileOnThePhone() {
        assertEquals(14L, PdfFacts.pages(FileLite(url), mapOf(url to 14)))
    }

    @Test
    fun unknownOrZeroPagesAreNotShown() {
        assertNull(PdfFacts.pages(FileLite(url), emptyMap()))
        assertNull(PdfFacts.pages(FileLite(url, pages = 0), emptyMap()))
        assertNull(PdfFacts.pages(FileLite(url), mapOf("https://example.org/other.pdf" to 3)))
    }

    @Test
    fun theLineSaysWhatIsKnown() {
        assertEquals("PDF · 2.3 MB · 12 pages", PdfFacts.line("2.3 MB", "12 pages"))
        assertEquals("PDF · 450 KB", PdfFacts.line("450 KB", null))
        assertEquals("PDF · 1 page", PdfFacts.line(null, "1 page"))
        assertNull(PdfFacts.line(null, null))
    }
}
