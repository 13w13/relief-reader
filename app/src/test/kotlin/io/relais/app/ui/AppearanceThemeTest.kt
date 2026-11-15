// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.Appearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Appearance" (founder, 15/11): the theme the screens get, on a light and on a dark phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppearanceThemeTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * The background and dark flag the screens see with each Appearance, as MainActivity sets them.
     * One composition (setContent runs once per test), the choice changed in between.
     */
    private fun themes(): Map<Appearance, Pair<Color, Boolean>> {
        var appearance by mutableStateOf(Appearance.SYSTEM)
        var background = Color.Unspecified
        var dark = false
        compose.setContent {
            RelaisTheme(darkTheme = isDark(appearance)) {
                background = MaterialTheme.colorScheme.background
                dark = LocalDarkTheme.current
            }
        }
        return Appearance.entries.associateWith {
            appearance = it
            compose.waitForIdle()
            background to dark
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun onALightPhone() {
        val t = themes()
        assertEquals(LightColors.background to false, t[Appearance.SYSTEM])
        assertEquals(LightColors.background to false, t[Appearance.LIGHT])
        assertEquals(DarkColors.background to true, t[Appearance.DARK])
    }

    @Test
    @Config(qualifiers = "night")
    fun onADarkPhone() {
        val t = themes()
        assertEquals(DarkColors.background to true, t[Appearance.SYSTEM])
        assertEquals(LightColors.background to false, t[Appearance.LIGHT])
        assertEquals(DarkColors.background to true, t[Appearance.DARK])
    }

    @Test
    fun theChoiceAppliesAtOnce() {
        var appearance by mutableStateOf(Appearance.SYSTEM)
        var background = Color.Unspecified
        compose.setContent {
            RelaisTheme(darkTheme = isDark(appearance)) {
                background = MaterialTheme.colorScheme.background
                AppearanceChoice(appearance) { appearance = it }
            }
        }
        compose.onNodeWithTag("appearance_dark").performClick()
        compose.waitForIdle()
        assertEquals(Appearance.DARK, appearance)
        assertEquals(DarkColors.background, background)
        compose.onNodeWithTag("appearance_light").performClick()
        compose.waitForIdle()
        assertEquals(LightColors.background, background)
    }
}
