// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.DownloadState
import io.relais.app.data.FileLite
import io.relais.app.data.ReportEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Issue 36: a refused host, a server's error and a network error read differently on the report,
 * and a failed download offers the browser as a refused one does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DownloadMessagesTest {
    @get:Rule
    val compose = createComposeRule()

    private val file = FileLite(
        url = "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/funding-status.pdf",
        filename = "funding-status.pdf", size = 352_000, pages = 4, mimetype = "application/pdf",
    )

    private val report = ReportEntity(
        id = 4_100_001, title = "Noravia: Regional funding status", headlineTitle = null, headlineSummary = null,
        imageUrl = null, imageCaption = null, imageCopyright = null, urlAlias = null, origin = null,
        dateCreated = 1794614400000L, dateOriginal = null, primaryCountryId = null, primaryCountryName = "Noravia",
        countries = emptyList(), sources = emptyList(), formatId = 10, formatName = "Situation Report",
        themes = emptyList(), languages = listOf("en"), files = listOf(file), bodyHtml = "<p>Text</p>",
        isHeadline = false, fromRss = false, fetchedAt = 1794614400000L,
    )

    @Test
    fun eachFailureReadsItsOwnWay() {
        var state by mutableStateOf<DownloadState>(DownloadState.Failed("HTTP 404"))
        compose.setContent {
            RelaisTheme(darkTheme = false) { FileLines(report, mapOf(file.url to state), emptyMap(), onRead = {}, onCancel = {}) }
        }
        compose.onNodeWithText("Download failed (HTTP 404).").assertIsDisplayed()
        compose.onNodeWithTag("file_failed_open_browser").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()

        state = DownloadState.Failed("no connection")
        compose.waitForIdle()
        compose.onNodeWithText("Download failed (no connection).").assertIsDisplayed()

        state = DownloadState.Refused
        compose.waitForIdle()
        compose.onNodeWithText("Re.L downloads files from reliefweb.int only.").assertIsDisplayed()
        compose.onNodeWithTag("file_open_browser").assertIsDisplayed()
        compose.onNodeWithTag("file_failed").assertDoesNotExist()
    }
}
