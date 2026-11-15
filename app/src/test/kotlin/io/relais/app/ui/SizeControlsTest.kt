// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.Appearance
import io.relais.app.data.DEFAULT_READING_SIZE
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Reading on a 360 dp phone with large system text (review of PR 24): no label of
 * Appearance or Display is cut, and no "A" of the report steps overflows its segment. Native
 * graphics, so that text has its real width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class SizeControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun reading(fontScale: Float, display: DisplaySize = DisplaySize.Compact) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = false, display = display) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AppearanceChoice(Appearance.SYSTEM) {}
                        TextSizeControl(DEFAULT_READING_SIZE, sample = null, onChange = {}, onDisplay = {}, modifier = Modifier.padding(Space.screen))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun SemanticsNodeInteraction.assertWhole(what: String) {
        val r = layout()
        assertFalse("$what is cut", r.hasVisualOverflow || r.isLineEllipsized(r.lineCount - 1))
    }

    private val labels = listOf("System", "Light", "Dark", "Smallest", "Compact", "Default", "Large")

    @Test
    fun at130PercentNoLabelIsCut() {
        reading(fontScale = 1.3f)
        labels.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertWhole(it) }
    }

    @Test
    fun at100PercentNoLabelIsCut() {
        reading(fontScale = 1f)
        labels.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertWhole(it) }
    }

    @Test
    fun atSmallestNoLabelIsCut() {
        reading(fontScale = 1.3f, display = DisplaySize.Smallest)
        labels.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertWhole(it) }
    }

    @Test
    fun at200PercentNoStepOverflows() {
        reading(fontScale = 2f)
        labels.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertWhole(it) }
        TextStep.entries.forEach { compose.onNodeWithTag("text_step_" + it.name.lowercase(), useUnmergedTree = true).assertWhole("A of ${it.name}") }
    }
}
