// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.Appearance
import io.relais.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Appearance" on the welcome screen (founder, 16/11): its layout and its control. The choice
 * lives in the settings store; here a variable stands for it. The real store, and a rotation, are
 * in SettingsStoreTest (one DataStore per process).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WelcomeTest {
    @get:Rule
    val compose = createComposeRule()

    /** The stored appearance, and the screen drawn from it, in the theme it gives. */
    private var stored by mutableStateOf(Appearance.of(Settings().appearance))
    private var continued = 0

    private fun welcome(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = stored == Appearance.DARK) {
                    WelcomeStep(appearance = stored, onAppearance = { stored = it }, onContinue = { continued++ })
                }
            }
        }
    }

    @Test
    fun systemIsTheDefault() {
        assertEquals(Appearance.SYSTEM, stored)
        welcome()
        compose.onNodeWithTag("appearance_system").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("appearance_dark").assertIsNotSelected()
    }

    @Test
    fun aChoiceAppliesAtOnceWithoutConfirmation() {
        welcome()
        compose.onNodeWithTag("appearance_dark").performScrollTo().performClick()
        // Saved by the tap itself, and shown selected, the screen in the dark theme.
        assertEquals(Appearance.DARK, stored)
        compose.onNodeWithTag("appearance_dark").assertIsSelected()
        assertEquals(0, continued)
        compose.onNodeWithTag("appearance_light").performClick()
        assertEquals(Appearance.LIGHT, stored)
        compose.onNodeWithTag("welcome_continue").performClick()
        assertEquals(1, continued)
    }

    /** "Get started" stays on screen, whole; the choice is reachable by scrolling. */
    private fun assertFitsTheScreen() {
        val root = compose.onRoot().getBoundsInRoot()
        val button = compose.onNodeWithTag("welcome_continue").assertIsDisplayed().getBoundsInRoot()
        assertTrue("Get started inside the screen: $button in $root", button.bottom <= root.bottom && button.top >= root.top)
        compose.onNodeWithTag("appearance_dark").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome_continue").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun onASmallPhoneEverythingIsReachable() {
        welcome()
        assertFitsTheScreen()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun atLargeTextEverythingIsReachable() {
        welcome(fontScale = 2f)
        assertFitsTheScreen()
    }
}
