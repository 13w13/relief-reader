// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.relais.app.data.FileLite
import io.relais.app.data.ReportEntity
import io.relais.app.data.SourceLite
import io.relais.app.pdf.BlockMarks
import io.relais.app.pdf.PdfBoxText
import io.relais.app.pdf.PdfLayout
import io.relais.app.pdf.PdfMode
import io.relais.app.pdf.PdfTextBottomBar
import io.relais.app.pdf.PdfTextContent
import io.relais.app.pdf.PdfTextPage
import io.relais.app.pdf.PdfTopBar
import io.relais.app.pdf.TextRow
import io.relais.app.pdf.textRows
import io.relais.app.ui.DocumentRow
import io.relais.app.ui.FollowedLine
import io.relais.app.ui.FollowingRoute
import io.relais.app.ui.FilterRow
import io.relais.app.ui.HeadlinePile
import io.relais.app.ui.TodayDateLine
import io.relais.app.ui.headlinesNote
import io.relais.app.ui.MainBottomBar
import io.relais.app.ui.PageFilter
import io.relais.app.ui.ReadingTypography
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.ScreenScaffold
import io.relais.app.ui.SectionHeader
import io.relais.app.ui.Space
import io.relais.app.ui.TodayRefresh
import io.relais.app.ui.TodayRoute
import io.relais.app.ui.TodayTitle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The three screens of the README and of the F-Droid listing, light and dark: Today with the
 * large headline and its photo, Following, and a PDF read as text with one sentence highlighted.
 *
 * Made-up sample data that reads like the real thing: fictional regions and organizations, no
 * text of a real report. The photo is the art director's drawing (test resources/readme), shown
 * by Coil's preview handler: no network. The PDF is the generated test sitrep, read by the real
 * extraction. Written to build/outputs/roborazzi/readme/screen-*.png; readme-shots.yml prints
 * them, and docs/readme/screens-*.webp assemble them (the art director's readme_triptyque.py).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
class ReadmeScreensTest {
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

    // --- The made-up week -----------------------------------------------------------------------

    private val zone = ZoneId.systemDefault()

    /** Today (or [daysAgo] days ago) at [hour]:[minute]: the kickers read "07:05" or "Yesterday". */
    private fun at(hour: Int, minute: Int, daysAgo: Long = 0): Long =
        LocalDate.now(zone).minusDays(daysAgo).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    // Made-up organizations (art director, 14/11): no name close to a real agency.
    private val nco = SourceLite(990101, "NCO", "Noravia Coordination Office")
    private val nwp = SourceLite(990102, "NWP", "Noravia Water Partners")
    private val nhn = SourceLite(990103, "NHN", "Noravia Health Network")
    private val kfm = SourceLite(990104, "KFM", "Kestrova Food Monitor")

    private fun report(
        id: Long,
        title: String,
        region: String,
        source: SourceLite,
        formatId: Long,
        formatName: String,
        created: Long,
        headline: Boolean = false,
        summary: String? = null,
        pdfBytes: Long? = null,
    ) = ReportEntity(
        id = id, title = title, headlineTitle = if (headline) title else null, headlineSummary = summary,
        imageUrl = if (headline) PHOTO_URL else null,
        imageCaption = if (headline) "Drawn aerial view of fields and a river. Sample image for screenshots." else null,
        imageCopyright = if (headline) "© Re.L sample image" else null,
        urlAlias = null, origin = null, dateCreated = created, dateOriginal = null, primaryCountryId = null,
        primaryCountryName = region, countries = emptyList(), sources = listOf(source), formatId = formatId,
        formatName = formatName, themes = emptyList(), languages = listOf("en"),
        files = pdfBytes?.let { listOf(FileLite(url = "https://relais.test/$id.pdf", size = it, mimetype = "application/pdf")) }.orEmpty(),
        bodyHtml = "", isHeadline = headline, fromRss = false, fetchedAt = created,
    )

    private val hero = report(
        1, "$REGION: Floods displace 8,000 people in two districts", REGION, nco, 10, "Situation Report",
        at(7, 40), headline = true,
        summary = "Families who left the river banks now live in two collective centres. " +
            "Water trucking reached 12,400 people in the northern camps.",
    )

    /** The last visit, at 06:20: what came after it has the blue dot, unless already read. */
    private val lastVisit get() = at(6, 20)

    // This week's headlines, newest first (the art director's sketch, morning: 2 new, 3 read).
    private val pile = listOf(
        hero,
        report(2, "$OTHER: Market prices rise after the October harvest", OTHER, kfm, 3, "Analysis", at(7, 5), headline = true,
            summary = "Maize costs a third more than in September in the three main markets."),
        report(3, "$REGION: Cholera prevention reaches two collective centres", REGION, nhn, 8, "News and Press Release", at(16, 20, daysAgo = 2), headline = true,
            summary = "Health teams vaccinated 3,100 people and opened two oral rehydration points."),
        report(4, "$REGION: Water points in the northern camps", REGION, nwp, 12, "Map", at(14, 0, daysAgo = 3), headline = true,
            summary = "Twenty-two water points work again after repairs. Four camps still rely on trucks."),
        report(5, "$OTHER: Food security outlook to February 2027", OTHER, kfm, 3, "Analysis", at(11, 30, daysAgo = 4), headline = true,
            summary = "Households in the south will need help before the lean season."),
        report(6, "$REGION: Flood response plan asks for funds to January 2027", REGION, nco, 4, "Appeal", at(10, 0, daysAgo = 5), headline = true,
            summary = "The plan covers shelter, water and health for 46,000 people."),
    )
    private val pileRead = setOf(3L, 4L, 6L)

    // New since the last visit from what is followed: the block under the pile.
    private val followNew = listOf(
        report(7, "$REGION: Shelter needs in Osmira and Tarvel, rapid assessment", REGION, nwp, 5, "Assessment", at(6, 50), pdfBytes = 880_000),
        report(8, "$REGION: Situation Report No. 7", REGION, nco, 10, "Situation Report", at(7, 30), pdfBytes = 1_180_000),
        report(9, "$OTHER: Rainfall and planting, November update", OTHER, kfm, 3, "Analysis", at(17, 10, daysAgo = 1)),
    )

    private val followingRows = listOf(
        report(11, "$REGION: Situation Report No. 7", REGION, nco, 10, "Situation Report", at(7, 30), pdfBytes = 1_180_000) to false,
        report(12, "$OTHER: Market prices after the October harvest", OTHER, kfm, 3, "Analysis", at(7, 5)) to false,
        report(13, "$REGION: Shelter needs in Osmira and Tarvel, rapid assessment", REGION, nwp, 5, "Assessment", at(6, 50), pdfBytes = 880_000) to false,
        report(14, "$REGION: Flood response plan, November 2026 to January 2027", REGION, nco, 4, "Appeal", at(6, 10), pdfBytes = 2_310_000) to false,
        report(15, "$REGION: Water points in the northern camps, October 2026", REGION, nwp, 12, "Map", at(15, 45, daysAgo = 1), pdfBytes = 640_000) to false,
        report(16, "$REGION: Cholera prevention in two collective centres", REGION, nhn, 8, "News and Press Release", at(11, 15, daysAgo = 1)) to false,
        report(17, "$OTHER: Food security outlook, November 2026 to February 2027", OTHER, kfm, 3, "Analysis", at(9, 0, daysAgo = 1), pdfBytes = 1_520_000) to false,
    )

    // --- How each screen is drawn ---------------------------------------------------------------

    private val photo by lazy {
        val bitmap = BitmapFactory.decodeFile(File("src/test/resources/readme/headline-sample.png").path)
        AsyncImagePreviewHandler { bitmap.asImage() }
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides photo) {
                RelaisTheme(darkTheme = dark) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/readme/$name.png")
    }

    /** A main tab: its screen, then the bottom bar with this tab selected. */
    @Composable
    private fun WithBottomBar(tab: Any, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            MainBottomBar(isSelected = { it == tab::class }, onTab = { _, _ -> })
        }
    }

    @Composable
    private fun Today() = WithBottomBar(TodayRoute) {
        ScreenScaffold(title = "Re.L", onSettings = {}, titleContent = { TodayTitle() }, actions = { TodayRefresh {} }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item { TodayDateLine(lastRefreshAt = at(7, 45)) }
                item { SectionHeader(stringResource(R.string.today_headlines), headlinesNote(pile, lastVisit)) }
                item {
                    HeadlinePile(
                        headlines = pile,
                        readIds = pileRead,
                        since = lastVisit,
                        pager = rememberPagerState { pile.size + 1 },
                        onOpen = {},
                        onOlder = {},
                    )
                }
                item { SectionHeader(stringResource(R.string.today_latest_follow)) }
                items(followNew) { DocumentRow(it, onClick = {}, isNew = true) }
            }
        }
    }

    @Composable
    private fun Following() = WithBottomBar(FollowingRoute) {
        ScreenScaffold(title = stringResource(R.string.following_title), onSettings = {}) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { FollowedLine(listOf(REGION, OTHER, nwp.shortname), onManage = {}) }
                item { FilterRow(PageFilter.FEEDS, PageFilter.ALL, onSelect = {}) }
                items(followingRows) { (row, read) -> DocumentRow(row, onClick = {}, isNew = row.dateCreated > lastVisit && !read, isRead = read) }
            }
        }
    }

    @Composable
    private fun PdfText() {
        val sentence = "Floods displaced about 8,000 people in Osmira and Tarvel districts."
        val marked = rows.filterIsInstance<TextRow.Body>().first { sentence in it.item.text }
        val start = marked.item.text.indexOf(sentence)
        Column(Modifier.fillMaxSize()) {
            val cs = MaterialTheme.colorScheme
            PdfTopBar("$REGION Situation Report No. 7", PdfMode.TEXT, true, {}, {}, {}, cs.surface, cs.onSurface)
            Box(Modifier.weight(1f)) {
                ReadingTypography(2) {
                    PdfTextContent(rows, rememberLazyListState(0), onOpenPdf = {}, marks = { p, b ->
                        if (p == marked.page && b == marked.block) BlockMarks(highlights = listOf(start until start + sentence.length)) else BlockMarks.NONE
                    })
                }
            }
            PdfTextBottomBar(0, pages.size, 5_731, onTextSize = {})
        }
    }

    @Test
    fun today() = shoot("screen-today", dark = false) { Today() }

    @Test
    fun todayDark() = shoot("screen-today-dark", dark = true) { Today() }

    @Test
    fun following() = shoot("screen-following", dark = false) { Following() }

    @Test
    fun followingDark() = shoot("screen-following-dark", dark = true) { Following() }

    @Test
    fun pdfText() = shoot("screen-pdf-text", dark = false) { PdfText() }

    @Test
    fun pdfTextDark() = shoot("screen-pdf-text-dark", dark = true) { PdfText() }

    private companion object {
        /** Made-up countries: the test PDF tells the story of [REGION] (art director, 14/11). */
        const val REGION = "Noravia"
        const val OTHER = "Kestrova"
        const val PHOTO_URL = "https://relais.test/readme/headline-sample.png"
    }
}
