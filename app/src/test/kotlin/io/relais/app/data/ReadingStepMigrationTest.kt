// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.preferencesOf
import io.relais.app.ui.DisplaySize
import io.relais.app.ui.TextStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6 adds a 14 sp step before the five of v0.5, and stores the step by name (founder, 15/11).
 * A reader on Small or Large before the update keeps exactly the same size after it.
 */
class ReadingStepMigrationTest {
    /** The steps of v0.5 as they were stored: their index, their name and their body size. */
    private val v05 = listOf("Smaller" to 15, "Small" to 16, "Default" to 17, "Large" to 19, "Larger" to 21)

    @Test
    fun theStoredNamesFollowTheSteps() {
        assertEquals(TextStep.entries.map { it.name }, READING_STEPS)
        assertEquals(TextStep.Small, TextStep.entries[DEFAULT_READING_SIZE])
        assertEquals(14.sp, TextStep.entries.first().body)
        assertEquals(6, TextStep.entries.size)
    }

    @Test
    fun everyOldIndexKeepsItsSize() = runBlocking {
        v05.forEachIndexed { oldIndex, (name, body) ->
            val before = preferencesOf(ReadingStepMigration.OLD_KEY to oldIndex)
            assertTrue(ReadingStepMigration.shouldMigrate(before))
            val after = ReadingStepMigration.migrate(before)
            assertEquals(name, after[ReadingStepMigration.KEY])
            assertNull(after[ReadingStepMigration.OLD_KEY])
            assertFalse(ReadingStepMigration.shouldMigrate(after))
            // What the screens read: the index into the new steps, and its size.
            val step = TextStep.entries[readingIndex(after[ReadingStepMigration.KEY])]
            assertEquals(name, step.name)
            assertEquals(body.sp, step.body)
        }
    }

    @Test
    fun smallAndLargeReadersInParticular() = runBlocking {
        val small = ReadingStepMigration.migrate(preferencesOf(ReadingStepMigration.OLD_KEY to 1))
        assertEquals(16.sp, TextStep.entries[readingIndex(small[ReadingStepMigration.KEY])].body)
        val large = ReadingStepMigration.migrate(preferencesOf(ReadingStepMigration.OLD_KEY to 3))
        assertEquals(19.sp, TextStep.entries[readingIndex(large[ReadingStepMigration.KEY])].body)
    }

    @Test
    fun aReaderWhoNeverChoseStaysOnSmall() = runBlocking {
        val untouched = preferencesOf()
        assertFalse(ReadingStepMigration.shouldMigrate(untouched))
        assertEquals(TextStep.Small, TextStep.entries[readingIndex(untouched[ReadingStepMigration.KEY])])
    }

    @Test
    fun oddValuesFallBackToSmallAndANewChoiceIsKept() = runBlocking {
        val odd = ReadingStepMigration.migrate(preferencesOf(ReadingStepMigration.OLD_KEY to 9))
        assertEquals("Small", odd[ReadingStepMigration.KEY])
        val both = ReadingStepMigration.migrate(preferencesOf(ReadingStepMigration.OLD_KEY to 3, ReadingStepMigration.KEY to "Smallest"))
        assertEquals("Smallest", both[ReadingStepMigration.KEY])
        assertNull(both[ReadingStepMigration.OLD_KEY])
        assertEquals(DEFAULT_READING_SIZE, readingIndex("Huge"))
        assertEquals(DEFAULT_READING_SIZE, readingIndex(null))
    }

    @Test
    fun namesAndIndexesRoundTrip() {
        READING_STEPS.indices.forEach { assertEquals(it, readingIndex(readingName(it))) }
        assertEquals("Smallest", readingName(-3))
        assertEquals("Larger", readingName(42))
    }

    @Test
    fun displayLevelsAreStoredByNameAndCompactStaysTheDefault() {
        assertEquals(DisplaySize.Compact, DisplaySize.of(null))
        assertEquals(DisplaySize.Compact, DisplaySize.of("Huge"))
        listOf("Smallest", "Compact", "Default", "Large").forEach { assertEquals(it, DisplaySize.of(it).name) }
        // Each level still brings the report text to the same step as in v0.5.
        assertEquals(TextStep.Small, DisplaySize.Compact.reportStep)
        assertEquals(TextStep.Default, DisplaySize.Default.reportStep)
        assertEquals(TextStep.Large, DisplaySize.Large.reportStep)
        assertEquals(TextStep.Smaller, DisplaySize.Smallest.reportStep)
    }

    @Test
    fun appearanceIsStoredByNameAndFollowsThePhoneByDefault() {
        assertEquals(Appearance.SYSTEM, Appearance.of(null))
        assertEquals(Appearance.SYSTEM, Appearance.of("SEPIA"))
        Appearance.entries.forEach { assertEquals(it, Appearance.of(it.name)) }
        assertTrue(Appearance.SYSTEM.isDark(systemDark = true))
        assertFalse(Appearance.SYSTEM.isDark(systemDark = false))
        assertTrue(Appearance.DARK.isDark(systemDark = false))
        assertFalse(Appearance.LIGHT.isDark(systemDark = true))
        assertEquals(android.app.UiModeManager.MODE_NIGHT_AUTO, Appearance.SYSTEM.nightMode)
        assertEquals(android.app.UiModeManager.MODE_NIGHT_NO, Appearance.LIGHT.nightMode)
        assertEquals(android.app.UiModeManager.MODE_NIGHT_YES, Appearance.DARK.nightMode)
    }
}
