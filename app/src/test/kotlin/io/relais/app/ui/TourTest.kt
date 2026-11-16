// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.combinedClickable
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.Settings
import io.relais.app.data.showsTour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The first launch tour (issue 37). Native graphics, as for the screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var done = 0

    private val titles = listOf("Today", "Following", "A report", "Highlight and Library", "Offline")

    private fun tour(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = false) { TourScreen(onDone = { done++ }) }
            }
        }
    }

    private fun assertPage(index: Int) {
        compose.onNodeWithText(titles[index]).assertIsDisplayed()
        compose.onNodeWithContentDescription("Page ${index + 1} of 5").assertExists()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun fivePagesThenStartReading() {
        tour()
        titles.indices.forEach { i ->
            assertPage(i)
            if (i < titles.lastIndex) {
                compose.onNodeWithText("Next").performClick()
                compose.waitForIdle()
            }
        }
        assertEquals(0, done)
        compose.onNodeWithText("Start reading").performClick()
        assertEquals(1, done)
    }

    @Test
    fun skipEndsTheTourOnEveryPage() {
        var start by mutableStateOf(0)
        compose.setContent {
            RelaisTheme(darkTheme = false) { key(start) { TourScreen(onDone = { done++ }, initialPage = start) } }
        }
        titles.indices.forEach { i ->
            done = 0
            start = i
            compose.waitForIdle()
            assertPage(i)
            compose.onNodeWithTag("tour_skip").assertIsDisplayed().performClick()
            assertEquals(titles[i], 1, done)
        }
    }

    @Test
    fun backGoesToThePageBeforeThenSkips() {
        tour()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        assertPage(2)
        back()
        assertPage(1)
        back()
        assertPage(0)
        assertEquals(0, done)
        back()
        assertEquals(1, done)
    }

    @Test
    fun thePageSurvivesARotation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RelaisTheme(darkTheme = false) { TourScreen(onDone = { done++ }) } }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        assertPage(2)
    }

    @Test
    fun theIllustrationsAreDecorative() {
        tour()
        // The made-up headline is drawn, but TalkBack reads the page's title and text only.
        compose.onNodeWithText("Osmira: floods displace about 8,000 people in two districts").assertDoesNotExist()
        compose.onNodeWithText("Headlines").assertDoesNotExist()
        compose.onNodeWithText("Today").assertIsDisplayed()
    }

    /** Skip and Next stay on screen and whole; the page's text is reachable by scrolling. */
    private fun assertReachable() {
        val root = compose.onRoot().getBoundsInRoot()
        listOf("tour_skip", "tour_next").forEach { tag ->
            val b = compose.onNodeWithTag(tag).assertIsDisplayed().getBoundsInRoot()
            assertTrue("$tag inside the screen: $b in $root", b.top >= root.top && b.bottom <= root.bottom && b.left >= root.left && b.right <= root.right)
            assertTrue("$tag at least 48 dp high", b.bottom - b.top >= androidx.compose.ui.unit.Dp(47.9f))
        }
        compose.onNodeWithText("The day's headlines", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tour_next").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun onASmallPhoneEverythingIsReachable() {
        tour()
        assertReachable()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun atLargeTextEverythingIsReachable() {
        tour(fontScale = 2f)
        assertReachable()
        // The last page too, with "Start reading".
        repeat(4) { compose.onNodeWithTag("tour_next").performClick(); compose.waitForIdle() }
        compose.onNodeWithText("Start reading").assertIsDisplayed()
    }

    @Test
    fun showTheTourAgainOpensIt() {
        var opened = 0
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutSection(onLicences = {}, onWhatsNew = {}, onTour = { opened++ })
                }
            }
        }
        compose.onNodeWithText("Show the tour again").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theTourComesOnceAfterTheFirstLaunchOnly() {
        // A first launch: not before the setup ends, then once.
        assertFalse(Settings().showsTour)
        assertFalse(Settings(tourPending = true).showsTour)
        assertTrue(Settings(onboardingDone = true, tourPending = true).showsTour)
        // A reader updating from 0.6.x: setup done long ago, no tour flag.
        assertFalse(Settings(onboardingDone = true).showsTour)
    }

    // --- Review of PR 45 ---------------------------------------------------------------------------

    @Test
    fun aSecondTapEndsTheTourOnlyOnce() {
        tour()
        compose.onNodeWithTag("tour_skip").performClick()
        compose.onNodeWithTag("tour_skip").performClick()
        back()
        assertEquals(1, done)
    }

    @Test
    fun startReadingTwiceEndsItOnce() {
        compose.setContent { RelaisTheme(darkTheme = false) { TourScreen(onDone = { done++ }, initialPage = 4) } }
        compose.onNodeWithText("Start reading").performClick()
        compose.onNodeWithText("Start reading").performClick()
        assertEquals(1, done)
    }

    /**
     * From Settings and back, through the app's own Nav and TourRoute, as MainScreen declares
     * them: a double tap on Skip during the transition comes back to Settings, never further.
     */
    @Test
    fun fromSettingsADoubleSkipComesBackToSettings() {
        compose.setContent {
            val controller = androidx.navigation.compose.rememberNavController()
            val nav = androidx.compose.runtime.remember(controller) { Nav(controller) }
            RelaisTheme(darkTheme = false) {
                androidx.navigation.compose.NavHost(controller, startDestination = TourTestToday) {
                    androidx.navigation.compose.composable<TourTestToday> {
                        androidx.compose.material3.TextButton(onClick = { controller.navigate(TourTestSettings) }) { androidx.compose.material3.Text("Open the settings") }
                    }
                    androidx.navigation.compose.composable<TourTestSettings> {
                        androidx.compose.material3.TextButton(onClick = nav::tour) { androidx.compose.material3.Text("Show the tour again") }
                    }
                    androidx.navigation.compose.composable<TourRoute> { TourScreen(onDone = { nav.back() }) }
                }
            }
        }
        compose.onNodeWithText("Open the settings").performClick()
        compose.onNodeWithText("Show the tour again").performClick()
        compose.waitForIdle()
        assertPage(0)
        // Two taps before the transition moves on.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tour_skip").performClick()
        compose.onNodeWithTag("tour_skip").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Show the tour again").assertIsDisplayed()
        compose.onNodeWithText("Open the settings").assertDoesNotExist()
    }

    /** The transparent surface over the illustrations: taps and long presses stop there, swipes go on. */
    @Test
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    fun theIllustrationTakesTapsButNotSwipes() {
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                val pager = androidx.compose.foundation.pager.rememberPagerState { 2 }
                androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.testTag("pager")) { page ->
                    Inert(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxSize().combinedClickable(onClick = { clicks++ }, onLongClick = { longClicks++ }),
                        ) { androidx.compose.material3.Text("page $page") }
                    }
                }
            }
        }
        compose.onNodeWithTag("inert").performTouchInput { click() }
        compose.onNodeWithTag("inert").performTouchInput { longClick() }
        assertEquals(0, clicks)
        assertEquals(0, longClicks)
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("page 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun atLargeTextThePageTextIsOnScreenWithoutScrolling() {
        tour(fontScale = 2f)
        compose.onNodeWithTag("tour_text").assertIsDisplayed()
        compose.onNodeWithTag("tour_title").assertIsDisplayed()
    }
}

@kotlinx.serialization.Serializable
internal object TourTestToday

@kotlinx.serialization.Serializable
internal object TourTestSettings
