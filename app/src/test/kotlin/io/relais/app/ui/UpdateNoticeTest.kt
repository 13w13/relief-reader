// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.LatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The notice on Today and the switch in Settings > About (founder, 15/11). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UpdateNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val release = LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2")

    @Test
    fun theNoticeOpensTheReleasePageOrHidesThisVersion() {
        val opened = mutableListOf<String>()
        var notNow = 0
        compose.setContent {
            RelaisTheme(darkTheme = false) { UpdateLine(release, onNotNow = { notNow++ }, onOpen = { opened += it }) }
        }
        compose.onNodeWithText("Re.L 0.5.2 is available").assertIsDisplayed()
        compose.onNodeWithTag("update_open").performClick()
        assertEquals(listOf(release.url), opened)
        compose.onNodeWithTag("update_not_now").performClick()
        assertEquals(1, notNow)
    }

    @Test
    fun aPageOutsideTheRepositoryNeverOpens() {
        val opened = mutableListOf<String>()
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                UpdateLine(LatestRelease("0.5.2", "https://example.org/13w13/relief-reader/"), onNotNow = {}, onOpen = { opened += it })
            }
        }
        compose.onNodeWithTag("update_open").performClick()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun withoutTheCheckInTheBuildSettingsHasNoSwitch() {
        compose.setContent {
            RelaisTheme(darkTheme = false) { Column { AboutSection(onLicences = {}, onWhatsNew = {}, updateCheck = null) } }
        }
        compose.onNodeWithTag("update_check_switch").assertDoesNotExist()
        compose.onNodeWithText("Check for new versions").assertDoesNotExist()
    }

    @Test
    fun theSwitchTurnsTheCheckOff() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutSection(onLicences = {}, onWhatsNew = {}, updateCheck = true, onUpdateCheck = { changes += it })
                }
            }
        }
        compose.onNodeWithTag("update_check_switch").performScrollTo().performClick()
        compose.onNodeWithText("Check for new versions").assertIsDisplayed()
        assertEquals(listOf(false), changes)
    }
}
