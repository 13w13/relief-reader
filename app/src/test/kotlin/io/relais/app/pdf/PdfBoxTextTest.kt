// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * PdfBox-Android and the layout on the generated test PDFs (tools/make_test_pdfs.py), the
 * same files the debug build seeds for the Maestro flow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PdfBoxTextTest {
    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun read(name: String): DocText {
        val started = System.nanoTime()
        val doc = PdfLayout.document(PdfBoxText.read(File("src/debug/assets/test-pdf/$name")).pages)
        println("$name: ${doc.pages.size} pages in ${(System.nanoTime() - started) / 1_000_000} ms")
        return doc
    }

    private fun DocText.texts(page: Int) = pages[page].blocks.map { it.text }

    @Test
    fun sitrepPagesHeadingsAndNoRunningHeader() {
        val doc = read("sitrep-two-columns.pdf")
        assertEquals(4, doc.pages.size)
        assertTrue(doc.hasText)
        assertEquals(TextBlock(BlockKind.HEADING, "Noravia: Situation Report No. 7"), doc.pages[0].blocks.first())
        val all = doc.pages.flatMap { it.blocks }.map { it.text }
        assertTrue(all.none { it.contains("Relais test document") || it.matches(Regex("Page \\d of 4")) })
        assertTrue(doc.texts(0).contains("HIGHLIGHTS"))
    }

    @Test
    fun columnsWrittenRowByRowReadInOrder() {
        // Page 2 writes its lines across both columns; the right column comes after the left.
        val texts = read("sitrep-two-columns.pdf").texts(1)
        val shelter = texts.indexOf("Shelter")
        val rains = texts.indexOfFirst { it.startsWith("Rains are expected to continue until the end of November.") }
        assertTrue("$texts", shelter in 0 until rains)
        assertTrue(texts.first().startsWith("School attendance rose after temporary learning spaces opened in three sites."))
    }

    @Test
    fun tableAndFigurePagesSendToThePdf() {
        val doc = read("sitrep-two-columns.pdf")
        val table = doc.pages[1].blocks.single { it.kind == BlockKind.TABLE }
        assertEquals("District · Displaced · Water · Health · Shelter", table.text.lines().first())
        assertTrue(table.text.contains("Osmira · 4,100 · 3,200 · 1,150 · 620"))
        assertEquals(Figure.TABLE, doc.pages[1].figure)
        assertEquals(Figure.FIGURE, doc.pages[2].figure)
        assertNull(doc.pages[3].figure)
    }

    @Test
    fun scanWithoutTextLayer() {
        val doc = read("scan-no-text.pdf")
        assertFalse(doc.hasText)
        assertEquals(2, doc.pages.size)
    }

    @Test
    fun frenchAccentsAndCutWords() {
        val doc = read("french-one-column.pdf")
        val texts = doc.texts(0)
        assertTrue(texts.any { it.contains("demandent un financement pour maintenir") })
        assertTrue(texts.contains("Œuvres et coopération"))
        assertNotNull(PdfTextStore.preview(doc))
    }

    @Test
    fun longDocumentHasEveryPage() {
        val doc = read("long-25-pages.pdf")
        assertEquals(25, doc.pages.size)
        assertEquals("Section 25: Water and sanitation", doc.pages[24].blocks.first { it.kind == BlockKind.HEADING && it.text.startsWith("Section") }.text)
    }

    @Test
    fun theProductTestSitrepReadsAsJourney7Expects() {
        // produit/passe-v0.2.md 7.1: the left column first, a table of 4 columns, "trucking" twice.
        val doc = read("test-sitrep.pdf")
        val page1 = doc.texts(0)
        assertEquals("TEST SITUATION REPORT No. 1", page1.first())
        val left = page1.indexOfFirst { it.startsWith("Water trucking reached 4,200 people in Site A.") }
        val right = page1.indexOfFirst { it.startsWith("Health partners opened a mobile clinic in Site B.") }
        assertTrue("$page1", left in 0 until right)
        assertEquals("Site · Households · People · Latrines", doc.pages[1].blocks.single { it.kind == BlockKind.TABLE }.text.lines().first())
        val pages = doc.pages.mapIndexed { i, p -> PdfTextPage(i, p.blocks, p.figure) }
        assertEquals(listOf(0, 2), PdfSearch.find(pages, "trucking").map { it.page })
        assertFalse(read("test-scan.pdf").hasText)
    }

    @Test
    fun searchFindsTheBoreholeOnThreePages() {
        val doc = read("sitrep-two-columns.pdf")
        val pages = doc.pages.mapIndexed { i, p -> PdfTextPage(i, p.blocks, p.figure) }
        val found = PdfSearch.find(pages, "borehole").map { it.page }.toSet()
        assertTrue("$found", found.containsAll(listOf(0, 1)))
    }

    @Test
    fun limitsStopTheReadingAndKeepThePagesReadWhole() {
        val file = File("src/debug/assets/test-pdf/long-25-pages.pdf")
        val whole = PdfBoxText.read(file)
        assertNull(whole.stop)
        assertEquals(25, whole.pages.size)
        assertEquals(25, whole.pageCount)

        val pages = PdfBoxText.read(file, PdfLimits(maxPages = 10))
        assertEquals(PdfStop.PAGES, pages.stop)
        assertEquals(10, pages.pages.size)
        assertEquals(25, pages.pageCount)

        // Out of time before the first page: nothing kept.
        val time = PdfBoxText.read(file, PdfLimits(maxMillis = 0))
        assertEquals(PdfStop.TIME, time.stop)
        assertTrue(time.pages.isEmpty())

        // Room for the characters of three pages and one more: the fourth page is dropped.
        val budget = whole.pages.take(3).sumOf { it.glyphs.size } + 1
        val memory = PdfBoxText.read(file, PdfLimits(maxGlyphs = budget))
        assertEquals(PdfStop.MEMORY, memory.stop)
        assertEquals(3, memory.pages.size)
        assertEquals(whole.pages[2].glyphs, memory.pages[2].glyphs)
    }
}
