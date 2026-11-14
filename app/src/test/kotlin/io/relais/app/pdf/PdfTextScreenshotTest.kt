// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.relais.app.R
import io.relais.app.ui.LocalRelaisColors
import io.relais.app.ui.ReadingTypography
import io.relais.app.ui.RelaisTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the text view of a PDF (theme-compose.md 5.11), with the text read from the
 * generated test sitrep by the real extraction. Written next to the other screenshots in
 * app/build/outputs/roborazzi/, under pdf-text-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PdfTextScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var pages: List<PdfTextPage>
    private lateinit var rows: List<TextRow>

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val doc = PdfLayout.document(PdfBoxText.read(File("src/debug/assets/test-pdf/sitrep-two-columns.pdf")).pages)
        pages = doc.pages.mapIndexed { i, p -> PdfTextPage(i, p.blocks, p.figure) }
        rows = textRows(pages)
    }

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            RelaisTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The text view as PdfViewerScreen lays it out: top bar, text, bottom bar. */
    @Composable
    private fun TextScreen(
        firstRow: Int = 0,
        size: Int = 2,
        page: Int = 0,
        search: String? = null,
        rows: List<TextRow> = this.rows,
        pageCount: Int = pages.size,
        // Last, for the tests that pass it as a trailing lambda.
        marks: (Int, Int) -> BlockMarks = { _, _ -> BlockMarks.NONE },
    ) {
        Column(Modifier.fillMaxSize()) {
            val cs = MaterialTheme.colorScheme
            PdfTopBar("Noravia Situation Report No. 7", PdfMode.TEXT, true, {}, {}, {}, cs.surface, cs.onSurface)
            if (search != null) {
                val matches = PdfSearch.find(pages, search)
                PdfSearchField(search, {}, matches.size, 0, {}, {}, {})
                val results = matches.map { m -> m to PdfSearch.snippetAt(pages[m.page].blocks[m.block].text, m.start, m.end) }
                PdfSearchResults(search, results, onOpen = {})
            }
            Box(Modifier.weight(1f)) {
                ReadingTypography(size) {
                    PdfTextContent(rows, rememberLazyListState(firstRow), onOpenPdf = {}, marks = marks)
                }
            }
            PdfTextBottomBar(page, pageCount, onTextSize = {})
        }
    }

    private fun indexOfMarker(page: Int) = rows.indexOfFirst { it is TextRow.Marker && it.page == page } + 1

    @Test
    fun textViewFirstPage() = shoot("pdf-text-1-first-page") { TextScreen() }

    @Test
    fun textViewTableOnPage2() = shoot("pdf-text-2-table-link") {
        // The end of page 2: the right column, then the line that sends to the table.
        val link = rows.indexOfFirst { it is TextRow.Link && it.page == 1 } + 1
        TextScreen(firstRow = (link - 3).coerceAtLeast(0), page = 1)
    }

    @Test
    fun textViewFigurePage3() = shoot("pdf-text-3-figure-link") { TextScreen(firstRow = indexOfMarker(2), page = 2) }

    @Test
    fun textViewDark() = shoot("pdf-text-4-dark", dark = true) { TextScreen() }

    @Test
    fun textViewLarger() = shoot("pdf-text-5-larger") { TextScreen(firstRow = indexOfMarker(1), size = 4, page = 1) }

    @Test
    fun textViewSearch() = shoot("pdf-text-6-search") {
        val matches = PdfSearch.find(pages, "borehole")
        val current = matches.first()
        val byBlock = matches.groupBy { it.page to it.block }
        val first = rows.indexOfFirst { it is TextRow.Body && it.page == current.page && it.block == current.block } + 1
        TextScreen(firstRow = (first - 1).coerceAtLeast(0), search = "borehole") { p, b ->
            byBlock[p to b]?.let { list ->
                BlockMarks(matches = list.map { it.start until it.end }, current = (current.start until current.end).takeIf { current.page == p && current.block == b })
            } ?: BlockMarks.NONE
        }
    }

    @Test
    fun textViewSearchNoResult() = shoot("pdf-text-6b-search-none") { TextScreen(search = "cholera") }

    @Test
    fun textViewHighlighted() = shoot("pdf-text-7-highlight") {
        val body = rows.first { it is TextRow.Body && it.item.kind == BlockKind.PARAGRAPH } as TextRow.Body
        TextScreen { p, b -> if (p == body.page && b == body.block) BlockMarks(highlights = listOf(0 until 60)) else BlockMarks.NONE }
    }

    private fun report(id: Long, title: String, org: String) = io.relais.app.data.ReportEntity(
        id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = System.currentTimeMillis() - 3_600_000L,
        dateOriginal = null, primaryCountryId = null, primaryCountryName = "Noravia", countries = emptyList(),
        sources = listOf(io.relais.app.data.SourceLite(1, org, org)), formatId = 10, formatName = "Situation Report",
        themes = emptyList(), languages = listOf("en"),
        files = listOf(io.relais.app.data.FileLite(url = "https://relais.test/$id.pdf", size = 5_755, mimetype = "application/pdf")),
        bodyHtml = "", isHeadline = false, fromRss = false, fetchedAt = 0,
    )

    @Test
    fun listRowWithPdfPreview() = shoot("pdf-text-10-list-preview") {
        val withPdf = report(1, "Noravia: Situation Report No. 7 (test document)", "OCHA")
        val without = report(2, "Noravia: Flash Update No. 3 (test document)", "OCHA")
        val preview = PdfTextStore.preview(DocText(pages.map { PageText(it.blocks, it.figure) }, true)).orEmpty()
        androidx.compose.runtime.CompositionLocalProvider(LocalPdfPreviews provides mapOf(1L to preview)) {
            Column {
                io.relais.app.ui.DocumentRow(withPdf, onClick = {})
                io.relais.app.ui.DocumentRow(without, onClick = {})
            }
        }
    }

    @Test
    fun libraryFoundInPdfs() = shoot("pdf-text-11-library-found-in-pdfs") {
        val doc = report(1, "Noravia: Situation Report No. 7 (test document)", "OCHA")
        val text = pages[0].blocks.first { it.text.contains("borehole") }.text
        val snippet = PdfSearch.snippet(text, listOf("borehole"), before = 50, after = 60)
        Column {
            io.relais.app.ui.SectionHeader(stringResource(R.string.library_found_in_pdfs), subtitle = "1")
            io.relais.app.ui.DocumentRow(doc, onClick = {}, extra = { PageSnippet(0, snippet, onClick = {}, terms = listOf("borehole")) })
        }
    }

    @Test
    fun textLoading() = shoot("pdf-text-8-loading") {
        Column(Modifier.fillMaxSize()) {
            val cs = MaterialTheme.colorScheme
            PdfTopBar("Noravia Situation Report No. 7", PdfMode.TEXT, false, {}, {}, null, cs.surface, cs.onSurface)
            PdfTextLoading(0.4f, onOpenPdf = {})
        }
    }

    @Test
    fun pdfViewBarsWithoutText() = shoot("pdf-text-9-pdf-bar-no-text") { BarsWithoutText(R.string.pdf_no_text) }

    @Test
    fun pdfViewBarsTooLarge() = shoot("pdf-text-13-pdf-bar-too-large") { BarsWithoutText(R.string.pdf_text_too_large) }

    @Test
    fun textViewStopsAtALimit() = shoot("pdf-text-12-text-stops") {
        // A PDF of 612 pages whose reading stopped after its last page here: the end of the text.
        val stopped = textRows(pages, total = 612)
        TextScreen(firstRow = (stopped.size - 3).coerceAtLeast(0), page = pages.size - 1, rows = stopped, pageCount = 612)
    }

    @Composable
    private fun BarsWithoutText(message: Int) {
        val extra = LocalRelaisColors.current
        Column(Modifier.fillMaxSize().background(extra.pdfBackground)) {
            // A scan: no switch and no search, as in the app (DA review, D9).
            PdfTopBar("Noravia Situation Report No. 7", PdfMode.PDF, false, {}, {}, null, extra.pdfBar, Color(0xFFF2F2F2))
            Text(
                stringResource(message),
                color = Color(0xFFF2F2F2),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(extra.pdfBar).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
