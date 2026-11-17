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
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.LinkOpener
import io.relais.app.data.LinkTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The screen of a ReliefWeb link from another app (17/11): each message and its buttons. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LinkScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var target by mutableStateOf<LinkTarget?>(null)
    private val taps = mutableListOf<String>()

    private fun show() = compose.setContent {
        RelaisTheme(darkTheme = false) {
            LinkMessage(target, onBrowser = { taps += "browser" }, onAddAppname = { taps += "appname" }, onRetry = { taps += "retry" })
        }
    }

    private fun buttons(vararg tags: String) {
        listOf("link_add_appname", "link_retry", "link_browser").forEach { tag ->
            if (tag in tags) compose.onNodeWithTag(tag).assertIsDisplayed() else compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    @Test
    fun whileLookingNoButton() {
        // The spinner turns for ever: the clock does not run on by itself.
        compose.mainClock.autoAdvance = false
        show()
        compose.onNodeWithText("Opening the report…").assertIsDisplayed()
        compose.onNodeWithTag("link_progress").assertIsDisplayed()
        buttons()
        // Found: the report replaces this screen; until then, still "Opening".
        target = LinkTarget.Report(42)
        compose.onNodeWithText("Opening the report…").assertIsDisplayed()
        buttons()
    }

    @Test
    fun withoutAnAppname() {
        target = LinkTarget.NeedsAppname
        show()
        compose.onNodeWithText("This report needs a ReliefWeb appname to open in Re.L.").assertIsDisplayed()
        buttons("link_add_appname", "link_retry", "link_browser")
        compose.onNodeWithTag("link_add_appname").performClick()
        compose.onNodeWithTag("link_browser").performClick()
        assertEquals(listOf("appname", "browser"), taps)
    }

    @Test
    fun severalLinksInAFewMinutesWait() {
        target = LinkTarget.TryLater
        show()
        compose.onNodeWithText(
            "Re.L opened several links in a few minutes. Try again in a moment, or open this one in your browser.",
        ).assertIsDisplayed()
        buttons("link_retry", "link_browser")
    }

    @Test
    fun backOnTheScreenAStuckLinkIsLookedForAgain() {
        // No appname: the reader adds one in Settings, comes back, and the report opens.
        var access = false
        var requests = 0
        val opener = LinkOpener(
            onPhone = { null },
            countryByIso3 = { null },
            hasAccess = { access },
            online = { true },
            fetch = { requests++; 990000300 },
        )
        val vm = LinkViewModel(opener, "https://reliefweb.int/report/sudan/sudan-test-flash-update-990000300")
        idle()
        assertEquals(LinkTarget.NeedsAppname, vm.target.value)
        vm.resolveAgainIfStuck()
        idle()
        assertEquals(LinkTarget.NeedsAppname, vm.target.value)
        access = true
        vm.resolveAgainIfStuck()
        idle()
        assertEquals(LinkTarget.Report(990000300), vm.target.value)
        // Found: nothing is asked again.
        vm.resolveAgainIfStuck()
        idle()
        assertEquals(1, requests)
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun offlineOrFailedCanTryAgain() {
        target = LinkTarget.Offline
        show()
        compose.onNodeWithText("You're offline, and this report isn't on your phone.").assertIsDisplayed()
        buttons("link_retry", "link_browser")
        target = LinkTarget.Failed("HTTP 500")
        compose.onNodeWithText("Couldn't open the report (HTTP 500).").assertIsDisplayed()
        buttons("link_retry", "link_browser")
        compose.onNodeWithTag("link_retry").performClick()
        assertEquals(listOf("retry"), taps)
    }

    @Test
    fun notAReportOrNotFoundGoToTheBrowser() {
        target = LinkTarget.NotFound
        show()
        compose.onNodeWithText("ReliefWeb has no report at this address.").assertIsDisplayed()
        buttons("link_browser")
        target = LinkTarget.NotAReport
        compose.onNodeWithText("Re.L can't show this ReliefWeb page.").assertIsDisplayed()
        buttons("link_browser")
    }
}
