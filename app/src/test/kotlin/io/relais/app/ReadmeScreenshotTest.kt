// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.relais.api.ApiResponse
import io.relais.api.Display
import io.relais.api.RelaisJson
import io.relais.api.ReportFields
import io.relais.app.data.toEntity
import io.relais.app.ui.AboutSection
import io.relais.app.ui.HeadlinePile
import io.relais.app.ui.headlinesNote
import io.relais.app.ui.pileOpening
import io.relais.app.ui.ConnectContent
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.ScreenScaffold
import io.relais.app.ui.SectionHeader
import io.relais.app.ui.Space
import io.relais.app.ui.TodayRefresh
import io.relais.app.ui.TodayTitle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * The screenshots of the public README (first launch review, 13/11), "Connect to ReliefWeb" and
 * Today, and Settings > About. Today reads fixtures/headlines.json: readme-shots.yml first
 * replaces the fixtures with made-up ones (tools/make_fixtures.py), so that no organization's
 * content is shown.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class ReadmeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/readme/$name.png")
    }

    @Test
    fun connect() = shoot("connect") {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectContent(onDone = {})
        }
    }

    /** Settings > About Re.L, with "Made with ♥", "Star it on GitHub" and "Open an issue" (v0.3.1). */
    @Test
    fun about() = shoot("about") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AboutSection(onLicences = {}, onWhatsNew = {})
        }
    }

    private fun week(): List<io.relais.app.data.ReportEntity> {
        val response = RelaisJson.decodeFromString(ApiResponse.serializer(ReportFields.serializer()), File("../fixtures/headlines.json").readText())
        val now = System.currentTimeMillis()
        return response.data.map { it.toEntity(existing = null, now = now, full = true) }.take(6)
    }

    /** Today since v0.4: the top bar with "UNOFFICIAL", the date, then the pile of headlines. */
    @Composable
    private fun Today(week: List<io.relais.app.data.ReportEntity>, since: Long, readIds: Set<Long>) {
        ScreenScaffold(title = "Re.L", onSettings = {}, titleContent = { TodayTitle() }, actions = { TodayRefresh {} }) { padding ->
            LazyColumn(Modifier.padding(padding)) {
                item {
                    Text(
                        Display.longDate(LocalDate.now()) + " · Updated 16:05",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                    )
                }
                item { SectionHeader(stringResource(R.string.today_headlines), headlinesNote(week, since)) }
                item {
                    HeadlinePile(
                        headlines = week,
                        readIds = readIds,
                        since = since,
                        pager = rememberPagerState(initialPage = pileOpening(week, readIds)) { week.size + 1 },
                        onOpen = {},
                        onOlder = {},
                    )
                }
            }
        }
    }

    /**
     * Today with no headline new since the last visit: "Nothing new since your last visit", and the
     * pile on the first headline not read.
     */
    @Test
    fun todayNoNewHeadline() {
        val week = week()
        shoot("today-no-new") { Today(week, since = System.currentTimeMillis(), readIds = week.take(2).map { it.id }.toSet()) }
    }

    @Test
    fun today() {
        val week = week()
        shoot("today") { Today(week, since = week[2].dateCreated, readIds = emptySet()) }
    }
}
