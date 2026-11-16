// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.LatestRelease
import io.relais.app.data.ManualCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The notice on Today and the switch in Settings > About (founder, 15/11). Native graphics, as for
 * the screenshots: the default mode gives text a made-up width, and the layout depends on it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    private fun notice(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = false) { UpdateLine(release, onNotNow = {}, onOpen = {}) }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun atNormalSizeTheButtonsShareTheLine() {
        notice(fontScale = 1f)
        val text = compose.onNodeWithTag("update_text").getBoundsInRoot()
        val open = compose.onNodeWithTag("update_open").getBoundsInRoot()
        assertTrue("buttons on the message's line", open.top < text.bottom && open.left >= text.right)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun atLargeTextTheMessageKeepsTheWidthAndTheButtonsGoUnder() {
        notice(fontScale = 2f)
        val line = compose.onNodeWithTag("update_line").getBoundsInRoot()
        val text = compose.onNodeWithTag("update_text").getBoundsInRoot()
        val notNow = compose.onNodeWithTag("update_not_now").getBoundsInRoot()
        val open = compose.onNodeWithTag("update_open").getBoundsInRoot()
        assertTrue("buttons under the message", notNow.top >= text.bottom && open.top >= text.bottom)
        // The message keeps at least half the line.
        val textWidth = text.right - text.left
        val lineWidth = line.right - line.left
        assertTrue("message width $textWidth of $lineWidth", textWidth >= lineWidth / 2)
        compose.onNodeWithText("Re.L 0.5.2 is available").assertIsDisplayed()
    }

    @Test
    fun aPageOutsideTheRepositoryNeverOpens() {
        val opened = mutableListOf<String>()
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                UpdateLine(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/%2e%2e/other"), onNotNow = {}, onOpen = { opened += it })
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

    // --- Download on Today, Check now in About (founder, 16/11) ------------------------------------

    private val apk = "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk"
    private val withApk = release.copy(apk = apk)

    @Test
    fun theNoticeOffersDownloadOnlyWithAValidApk() {
        val opened = mutableListOf<String>()
        var shown by androidx.compose.runtime.mutableStateOf(withApk)
        compose.setContent {
            RelaisTheme(darkTheme = false) { UpdateLine(shown, onNotNow = {}, onOpen = { opened += it }) }
        }
        compose.onNodeWithTag("update_download").performClick()
        assertEquals(listOf(apk), opened)
        // No APK, or an APK of another version: no button.
        shown = release
        compose.onNodeWithTag("update_download").assertDoesNotExist()
        shown = release.copy(apk = "https://github.com/13w13/relief-reader/releases/download/v0.5.1/rel-0.5.1.apk")
        compose.onNodeWithTag("update_download").assertDoesNotExist()
        compose.onNodeWithTag("update_open").assertIsDisplayed()
    }

    private fun noticeWithApk(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = false) { UpdateLine(withApk, onNotNow = {}, onOpen = {}) }
            }
        }
    }

    /** The three buttons stay inside the line, on a 360 dp phone at normal and at 200 % text. */
    private fun assertButtonsInside() {
        val line = compose.onNodeWithTag("update_line").getBoundsInRoot()
        listOf("update_not_now", "update_open", "update_download").forEach { tag ->
            val b = compose.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag inside the line: $b in $line", b.left >= line.left && b.right <= line.right && b.bottom <= line.bottom)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun withDownloadOnA360dpPhoneTheButtonsFit() {
        noticeWithApk(fontScale = 1f)
        assertButtonsInside()
        compose.onNodeWithText("Re.L 0.5.2 is available").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun withDownloadAtLargeTextTheButtonsGoUnderTheMessage() {
        noticeWithApk(fontScale = 2f)
        assertButtonsInside()
        val text = compose.onNodeWithTag("update_text").getBoundsInRoot()
        val download = compose.onNodeWithTag("update_download").getBoundsInRoot()
        assertTrue("Download under the message", download.top >= text.bottom)
        compose.onNodeWithText("Re.L 0.5.2 is available").assertIsDisplayed()
    }

    private fun about(check: ManualCheck?, switch: Boolean? = true, onCheckNow: () -> Unit = {}, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutSection(onLicences = {}, onWhatsNew = {}, updateCheck = switch, checkNow = check, onCheckNow = onCheckNow, onOpen = onOpen)
                }
            }
        }
    }

    @Test
    fun checkNowWorksWithTheSwitchOff() {
        var taps = 0
        about(ManualCheck.Idle, switch = false, onCheckNow = { taps++ })
        compose.onNodeWithTag("check_now_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, taps)
        compose.onNodeWithTag("check_now_result").assertDoesNotExist()
    }

    @Test
    fun withoutTheBuildFlagThereIsNoCheckNow() {
        about(check = null, switch = null)
        compose.onNodeWithTag("check_now_button").assertDoesNotExist()
        compose.onNodeWithText("Check now").assertDoesNotExist()
    }

    @Test
    fun whileCheckingTheButtonIsInactive() {
        var taps = 0
        about(ManualCheck.Checking, onCheckNow = { taps++ })
        compose.onNodeWithTag("check_now_button").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, taps)
        // Under the button, past the edge of the test's small screen: scrolled to first.
        compose.onNodeWithText("Checking…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun eachResultIsSaidUnderTheButton() {
        var state by androidx.compose.runtime.mutableStateOf<ManualCheck>(ManualCheck.UpToDate("0.7.0"))
        compose.setContent {
            RelaisTheme(darkTheme = false) { CheckNow(state, onCheckNow = {}, onOpen = {}) }
        }
        compose.onNodeWithText("Re.L 0.7.0 is up to date.").assertIsDisplayed()
        state = ManualCheck.Failed("HTTP 403")
        compose.onNodeWithText("Couldn't check (HTTP 403)").assertIsDisplayed()
        state = ManualCheck.Failed("no connection")
        compose.onNodeWithText("Couldn't check (no connection)").assertIsDisplayed()
        compose.onNodeWithTag("check_now_download").assertDoesNotExist()
        compose.onNodeWithTag("check_now_release").assertDoesNotExist()
        state = ManualCheck.Available(LatestRelease("0.7.1", "https://github.com/13w13/relief-reader/releases/tag/v0.7.1"))
        compose.onNodeWithText("Re.L 0.7.1 is available.").assertIsDisplayed()
    }

    @Test
    fun aNewVersionOffersTheApkAndTheRelease() {
        val opened = mutableListOf<String>()
        val page = "https://github.com/13w13/relief-reader/releases/tag/v0.7.1"
        val apk071 = "https://github.com/13w13/relief-reader/releases/download/v0.7.1/rel-0.7.1.apk"
        var state by androidx.compose.runtime.mutableStateOf<ManualCheck>(ManualCheck.Available(LatestRelease("0.7.1", page, apk071)))
        compose.setContent {
            RelaisTheme(darkTheme = false) { CheckNow(state, onCheckNow = {}, onOpen = { opened += it }) }
        }
        compose.onNodeWithText("Download the APK").performClick()
        compose.onNodeWithText("See the release").performClick()
        assertEquals(listOf(apk071, page), opened)
        // Without a valid APK: "See the release" only.
        state = ManualCheck.Available(LatestRelease("0.7.1", page, "https://example.org/rel-0.7.1.apk"))
        compose.onNodeWithTag("check_now_download").assertDoesNotExist()
        compose.onNodeWithTag("check_now_release").assertIsDisplayed()
        state = ManualCheck.Available(LatestRelease("0.7.1", page))
        compose.onNodeWithTag("check_now_download").assertDoesNotExist()
    }
}
