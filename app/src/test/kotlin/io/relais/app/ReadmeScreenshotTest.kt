// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.relais.api.ApiResponse
import io.relais.api.Display
import io.relais.api.RelaisJson
import io.relais.api.ReportFields
import io.relais.app.data.toEntity
import io.relais.app.ui.AboutSection
import io.relais.app.ui.ConnectContent
import io.relais.app.ui.DocumentRow
import io.relais.app.ui.HeadlineHero
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.RubricTab
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

    @Test
    fun today() {
        val response = RelaisJson.decodeFromString(ApiResponse.serializer(ReportFields.serializer()), File("../fixtures/headlines.json").readText())
        val now = System.currentTimeMillis()
        val headlines = response.data.map { it.toEntity(existing = null, now = now, full = true) }.take(6)
        shoot("today") {
            // The top bar of Today, with "UNOFFICIAL" next to the logotype.
            ScreenScaffold(title = "Re.L", onSettings = {}, titleContent = { TodayTitle() }, actions = { TodayRefresh {} }) { padding ->
                Column(Modifier.padding(padding)) {
                    PrimaryScrollableTabRow(selectedTabIndex = 0, edgePadding = 8.dp) {
                        listOf("All", "Situation reports", "Analysis", "Maps", "Appeals", "Sudan").forEachIndexed { i, label ->
                            RubricTab(label, active = i == 0, onClick = {})
                        }
                    }
                    LazyColumn {
                        item {
                            Column(Modifier.padding(horizontal = Space.screen, vertical = Space.m)) {
                                // The day of the capture: the rows say "Yesterday" or a date relative to it.
                                Text(Display.longDate(LocalDate.now()), style = MaterialTheme.typography.titleSmall)
                                Text("Updated 16:05", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { SectionHeader("New headlines") }
                        item { HeadlineHero(headlines.first(), isRead = false, onClick = {}) }
                        items(headlines.drop(1)) { DocumentRow(it, onClick = {}, isNew = true, isRead = it == headlines[2], title = it.displayTitle) }
                    }
                }
            }
        }
    }
}
