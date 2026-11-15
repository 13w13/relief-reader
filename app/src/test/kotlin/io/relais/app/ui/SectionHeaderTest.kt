// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A section title never breaks inside a word (capture today-update-font-200 of PR 23: "HE / AD /
 * LIN / ES"): the note beside it moves under the line when they don't fit. Native graphics, so
 * that text has its real width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SectionHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun header(fontScale: Float, display: DisplaySize = DisplaySize.Compact) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RelaisTheme(darkTheme = false, display = display) { SectionHeader("Headlines", "1 new since your last visit") }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun atLargeTextTheTitleStaysOnOneLineAndTheNoteGoesUnder() {
        header(fontScale = 2f)
        val title = compose.onNodeWithTag("section_title").getBoundsInRoot()
        val note = compose.onNodeWithTag("section_note").getBoundsInRoot()
        // One line of the Compact section title (18 sp line) at 200 %: about 36 dp, not four lines.
        assertTrue("title height ${title.bottom - title.top}", title.bottom - title.top < 54.dp)
        assertTrue("note under the title", note.top >= title.bottom)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun atNormalSizeTheNoteStaysBesideTheTitle() {
        header(fontScale = 1f)
        val title = compose.onNodeWithTag("section_title").getBoundsInRoot()
        val note = compose.onNodeWithTag("section_note").getBoundsInRoot()
        assertTrue("note beside the title", note.top < title.bottom && note.left >= title.right)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun atSmallestTheNoteStaysBesideTheTitleOnASmallPhone() {
        header(fontScale = 1f, display = DisplaySize.Smallest)
        val title = compose.onNodeWithTag("section_title").getBoundsInRoot()
        val note = compose.onNodeWithTag("section_note").getBoundsInRoot()
        assertTrue("note beside the title", note.top < title.bottom && note.left >= title.right)
    }
}
