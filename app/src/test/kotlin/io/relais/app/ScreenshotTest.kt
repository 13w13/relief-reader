// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.relais.api.ApiResponse
import io.relais.api.CountryTab
import io.relais.api.HtmlBlocks
import io.relais.api.RelaisJson
import io.relais.api.ReportFields
import io.relais.app.data.ReportEntity
import io.relais.app.data.toEntity
import io.relais.app.ui.BlockView
import io.relais.app.ui.DocumentRow
import io.relais.app.ui.EndOfReading
import io.relais.app.ui.HeadlineHero
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.FilesSection
import io.relais.app.ui.Photo
import io.relais.app.ui.ReportHeader
import io.relais.app.ui.RubricTab
import io.relais.app.ui.matches
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

    @Composable
    private fun Tabs(labels: List<String>, counts: List<Int?> = emptyList()) {
        PrimaryScrollableTabRow(selectedTabIndex = 0, edgePadding = 8.dp) {
            labels.forEachIndexed { i, label -> RubricTab(label, active = i == 0, onClick = {}, count = counts.getOrNull(i), badge = true) }
        }
    }

    @Test
    fun today() {
        val headlines = reports("headlines.json").take(6)
        shoot("today") {
            Column {
                Tabs(listOf("All", "Situation reports", "Analysis", "Maps", "Appeals", "Sudan", "Lebanon"))
                LazyColumn {
                    item { Text("Saturday 7 November", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(Space.screen)) }
                    item { HeadlineHero(headlines.first(), isRead = false, onClick = {}) }
                    item { SectionHeader("Headlines", "Chosen by ReliefWeb editors") }
                    items(headlines.drop(1)) { DocumentRow(it, onClick = {}, isRead = it == headlines[2], title = it.displayTitle) }
                    item { EndOfReading(left = 4, total = 6, onAllHeadlines = {}) }
                }
            }
        }
    }

    @Test
    fun todayDark() {
        val headlines = reports("headlines.json").take(4)
        shoot("today-dark", dark = true) {
            LazyColumn {
                item { HeadlineHero(headlines.first(), isRead = false, onClick = {}) }
                items(headlines.drop(1)) { DocumentRow(it, onClick = {}, title = it.displayTitle) }
                item { EndOfReading(left = 0, total = 4, onAllHeadlines = {}) }
            }
        }
    }

    @Test
    fun countryPage() {
        val response = response("sudan-reports.json")
        val now = System.currentTimeMillis()
        val docs = response.data.map { it.toEntity(null, now, full = true) }.take(8)
        // Badges count what is new since the last visit (decision of 7/11): here, the last day.
        val since = docs.maxOf { it.dateCreated } - 24 * 3_600_000L
        val fresh = docs.filter { it.dateCreated > since }
        val counts = CountryTab.entries.map { tab -> fresh.count { tab.matches(it) }.takeIf { it > 0 } }
        shoot("country-sudan") {
            Column {
                Text("Sudan", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(Space.screen))
                Tabs(listOf("Key documents", "All", "Situation reports", "Analysis", "Maps", "News", "Appeals"), counts)
                LazyColumn {
                    items(docs) { DocumentRow(it, onClick = {}, isNew = it.dateCreated > since, inCountry = true) }
                }
            }
        }
    }

    @Test
    fun report() {
        val report = reports("report-full.json").first()
        val blocks = report.bodyHtml?.takeIf { it.isNotEmpty() }?.let { HtmlBlocks.parse(it) } ?: emptyList()
        shoot("report") {
            // The report screen in order: photo, header, files, then the text.
            LazyColumn {
                if (report.imageUrl != null) item { Photo(report) }
                item { ReportHeader(report) }
                if (report.files.isNotEmpty()) {
                    item { FilesSection(report, downloads = emptyMap(), downloaded = emptyMap(), onRead = {}, onCancel = {}) }
                }
                items(blocks.take(12)) { BlockView(it) }
            }
        }
    }
}
