// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.relais.api.ApiResponse
import io.relais.api.HtmlBlocks
import io.relais.api.RelaisJson
import io.relais.api.ReportFields
import io.relais.app.data.ReportEntity
import io.relais.app.data.toEntity
import io.relais.app.ui.BlockView
import io.relais.app.ui.DocumentRow
import io.relais.app.ui.FilterRow
import io.relais.app.ui.HeadlineNavBlock
import io.relais.app.ui.HeadlinePile
import io.relais.app.ui.PageFilter
import io.relais.app.ui.TodayDateLine
import io.relais.app.ui.headlinePlace
import io.relais.app.ui.headlinesNote
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.FilesSection
import io.relais.app.ui.FileLines
import io.relais.app.ui.GroupBy
import io.relais.app.ui.LibraryFacet
import io.relais.app.ui.LibraryFilterSheet
import io.relais.app.ui.LibraryState
import io.relais.app.ui.LibraryView
import io.relais.app.ui.SourceHeader
import io.relais.app.data.SourceInfo
import io.relais.app.ui.Photo
import io.relais.app.ui.ReportHeader
import io.relais.app.ui.SectionHeader
import io.relais.app.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the main building blocks in the a2 grammar, with real ReliefWeb data from
 * fixtures/. Written to app/build/outputs/roborazzi/ by `./gradlew :app:recordRoborazziDebug`
 * in CI. Photos do not load in tests (no network): their 16:9 frame stays neutral grey.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun response(name: String): ApiResponse<ReportFields> =
        RelaisJson.decodeFromString(ApiResponse.serializer(ReportFields.serializer()), File("../fixtures/$name").readText())

    private fun reports(name: String): List<ReportEntity> {
        val now = System.currentTimeMillis()
        return response(name).data.map { it.toEntity(existing = null, now = now, full = true) }
    }

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            RelaisTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Today since v0.4: the date, then the pile of headlines on [page] (the end card after the last). */
    @Composable
    private fun TodayPile(headlines: List<ReportEntity>, readIds: Set<Long>, page: Int) {
        val since = headlines[1].dateCreated
        LazyColumn {
            item { TodayDateLine(lastRefreshAt = 0) }
            item { SectionHeader("Headlines", headlinesNote(headlines, since)) }
            item {
                HeadlinePile(
                    headlines = headlines,
                    readIds = readIds,
                    since = since,
                    pager = rememberPagerState(initialPage = page) { headlines.size + 1 },
                    onOpen = {},
                    onOlder = {},
                )
            }
            item { SectionHeader("Latest from what you follow") }
            items(headlines.drop(2)) { DocumentRow(it, onClick = {}, isNew = true) }
        }
    }

    @Test
    fun today() {
        val headlines = reports("headlines.json").take(6)
        shoot("today") { TodayPile(headlines, readIds = setOf(headlines[0].id), page = 1) }
    }

    @Test
    fun todayDark() {
        val headlines = reports("headlines.json").take(6)
        shoot("today-dark", dark = true) { TodayPile(headlines, readIds = setOf(headlines[0].id), page = 1) }
    }

    /** After the sixth: the week's titles, then "Older headlines". */
    @Test
    fun todayPileEnd() {
        val headlines = reports("headlines.json").take(6)
        shoot("today-pile-end") { TodayPile(headlines, readIds = headlines.take(3).map { it.id }.toSet(), page = headlines.size) }
    }

    /** A week of more than 6 headlines: the end card lists the first 6, then "N more this week". */
    @Test
    fun todayPileEndMore() {
        val headlines = reports("headlines.json")
        // The first headline is new and not read: its line ends with the blue dot.
        shoot("today-pile-end-more") { TodayPile(headlines, readIds = setOf(headlines[1].id, headlines[2].id), page = headlines.size) }
    }

    /** The end of a report opened from the pile: "Headline 2 of 6", Next, Previous headline. */
    @Test
    fun headlineNav() {
        val headlines = reports("headlines.json").take(6)
        val place = headlinePlace(headlines, headlines[1].id, readIds = setOf(headlines[0].id, headlines[1].id))!!
        shoot("report-headline-nav") {
            Column {
                Text(
                    "Families who left the river banks now live in two collective centres.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Space.screen),
                )
                HeadlineNavBlock(place, onNext = {}, onPrevious = {}, onOlder = {})
                SectionHeader("Details")
            }
        }
    }

    @Test
    fun countryPage() {
        val response = response("sudan-reports.json")
        val now = System.currentTimeMillis()
        val docs = response.data.map { it.toEntity(null, now, full = true) }.take(8)
        // The dot marks what is new since the last visit: here, the last day.
        val since = docs.maxOf { it.dateCreated } - 24 * 3_600_000L
        shoot("country-sudan") {
            Column {
                Text("Sudan", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(Space.screen))
                FilterRow(PageFilter.FEEDS, PageFilter.ALL, onSelect = {})
                LazyColumn {
                    items(docs) { DocumentRow(it, onClick = {}, isNew = it.dateCreated > since, inCountry = true) }
                }
            }
        }
    }

    /** The head of an organization's page (v0.4): three lines, then "About ACAPS" folded. */
    @Test
    fun sourceHead() {
        val info = SourceInfo(
            id = 990101, shortname = "NCO", name = "Noravia Coordination Office", typeName = "Government",
            countryName = "Noravia", homepage = "https://nco.example.org/en", descriptionHtml = "<p>A made-up office for screenshots.</p>",
            perWeek = 2.2, latestAt = System.currentTimeMillis() - 2 * 86_400_000L, mainCountries = listOf("Noravia", "Kestrova"),
        )
        val docs = reports("sudan-reports.json").take(5)
        shoot("source-head") {
            LazyColumn {
                item { SourceHeader(info, "NCO") }
                item { FilterRow(PageFilter.FEEDS, PageFilter.ALL, onSelect = {}) }
                items(docs) { DocumentRow(it, onClick = {}) }
            }
        }
    }

    /** Library's filter sheet (v0.4): the facets of Saved, two countries on, then Group by. */
    @Test
    fun libraryFilterSheet() {
        val state = LibraryState(
            view = LibraryView.SAVED,
            filters = mapOf(LibraryFacet.COUNTRY to setOf("Noravia", "Kestrova")),
            groupBy = GroupBy.FORMAT,
        )
        shoot("library-filter-sheet") {
            Column { LibraryFilterSheet(state, onFacet = {}, onGroupBy = {}, onClear = {}) }
        }
    }

    @Test
    fun report() {
        val report = reports("report-full.json").first()
        val blocks = report.bodyHtml?.takeIf { it.isNotEmpty() }?.let { HtmlBlocks.parse(it) } ?: emptyList()
        shoot("report") {
            // The report screen in order: photo, header, then the file: a line under the dates
            // when the report has its text (v0.4), the card above the first page otherwise.
            LazyColumn {
                if (report.imageUrl != null) item { Photo(report) }
                item { ReportHeader(report) }
                if (report.files.isNotEmpty()) {
                    item {
                        if (blocks.isNotEmpty()) {
                            FileLines(report, downloads = emptyMap(), downloaded = emptyMap(), onRead = {}, onCancel = {})
                        } else {
                            FilesSection(report, downloads = emptyMap(), downloaded = emptyMap(), onRead = {}, onCancel = {})
                        }
                    }
                }
                items(blocks.take(12)) { BlockView(it) }
            }
        }
    }
}
