// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Smaller text (founder, 15/11): a 10 sp floor everywhere, and nothing moves for Compact. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TextSizesTest {
    private fun Typography.styles(): Map<String, TextStyle> = mapOf(
        "displaySmall" to displaySmall, "headlineSmall" to headlineSmall, "headlineMedium" to headlineMedium,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun atLeast10(what: String, size: TextUnit) = assertTrue("$what is $size", size.isSp && size.value >= 10f)

    @Test
    fun noTextUnder10Sp() {
        DisplaySize.entries.forEach { d ->
            listOf(
                "rowTitle" to d.rowTitle, "kicker" to d.kicker, "info" to d.info, "chipText" to d.chipText,
                "section" to d.section, "heroTitle" to d.heroTitle,
            ).forEach { (name, size) -> atLeast10("${d.name}.$name", size) }
            typographyFor(d).styles().forEach { (name, style) -> atLeast10("${d.name} $name", style.fontSize) }
        }
        TextStep.entries.forEach { s ->
            atLeast10("${s.name}.body", s.body)
            atLeast10("${s.name}.title", s.title)
            atLeast10("${s.name}.button", s.button)
        }
        atLeast10("SectionTitleStyle", SectionTitleStyle.fontSize)
    }

    @Test
    fun smallestIsSmallerAndTighterThanCompact() {
        val s = DisplaySize.Smallest
        val c = DisplaySize.Compact
        assertTrue(s.rowTitle.value < c.rowTitle.value && s.kicker.value < c.kicker.value && s.info.value < c.info.value)
        assertTrue(s.heroTitle.value < c.heroTitle.value && s.section.value < c.section.value)
        assertTrue(s.rowVertical < c.rowVertical && s.screen < c.screen && s.sectionTop < c.sectionTop)
        val small = typographyFor(s).styles()
        typographyFor(c).styles().forEach { (name, style) ->
            assertTrue("$name: ${small.getValue(name).fontSize} < ${style.fontSize}", small.getValue(name).fontSize.value < style.fontSize.value)
        }
        assertEquals(DisplaySize.Smallest, DisplaySize.entries.first())
    }

    @Test
    fun compactAndTheOtherLevelsAreUnchanged() {
        // The values of v0.5, for a reader who changes nothing.
        val c = DisplaySize.Compact
        assertEquals(listOf(15.sp, 20.sp, 11.sp, 14.sp, 12.sp, 16.sp, 13.sp, 13.sp, 18.sp, 22.sp, 27.sp), listOf(c.rowTitle, c.rowTitleLine, c.kicker, c.kickerLine, c.info, c.infoLine, c.chipText, c.section, c.sectionLine, c.heroTitle, c.heroTitleLine))
        assertEquals(listOf(9.dp, 3.dp, 14.dp, 30.dp, 20.dp), listOf(c.rowVertical, c.kickerGap, c.screen, c.chip, c.sectionTop))
        listOf(DisplaySize.Compact, DisplaySize.Default, DisplaySize.Large).forEach { d ->
            assertEquals(1f, d.uiScale)
            assertEquals(20.dp, d.sectionTop)
            val t = typographyFor(d)
            // Only the four styles of the level move; the others are the theme's.
            assertEquals(RelaisTypography.bodyMedium, t.bodyMedium)
            assertEquals(RelaisTypography.labelLarge, t.labelLarge)
            assertEquals(RelaisTypography.titleSmall, t.titleSmall)
            assertEquals(RelaisTypography.bodyLarge, t.bodyLarge)
        }
        // The report steps of v0.5 keep their sizes.
        assertEquals(listOf(15.sp, 16.sp, 17.sp, 19.sp, 21.sp), TextStep.entries.drop(1).map { it.body })
        assertEquals(listOf(24.sp, 26.sp, 28.sp, 31.sp, 34.sp), TextStep.entries.drop(1).map { it.line })
    }

    @Test
    fun theSmallestStepKeepsItsLineHeightInProportion() {
        val smallest = TextStep.Smallest
        val smaller = TextStep.Smaller
        assertEquals(14.sp, smallest.body)
        assertEquals(smaller.line.value / smaller.body.value, smallest.line.value / smallest.body.value, 0.02f)
    }
}
