// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.relais.app.ui.TextStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real SettingsStore (DataStore): first a v0.5 settings file migrated on opening, then the
 * new-version check, not a copy of its writes (review of PR 23), then Appearance and the text
 * sizes of v0.6. One test only: the store's DataStore is one per process, and the v0.5 file must
 * be written before it opens.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsStoreTest {
    private fun answer(version: String) =
        """{"tag_name": "v$version", "html_url": "https://github.com/13w13/relief-reader/releases/tag/v$version", "draft": false, "prerelease": false}"""

    @Test
    fun theStoreKeepsWhatTheReaderChose() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // A phone updated from v0.5: its settings file holds the step as an index, 3 = Large
        // (19 sp). Written by a DataStore of its own, closed before the app's opens the file.
        val writer = CoroutineScope(Dispatchers.IO + Job())
        val v05 = PreferenceDataStoreFactory.create(scope = writer, produceFile = { context.preferencesDataStoreFile("settings") })
        v05.edit { it[intPreferencesKey("reading_size")] = 3 }
        writer.coroutineContext.job.cancelAndJoin()

        // The real store runs the migration it is built with (review of PR 24): the same 19 sp.
        val store = SettingsStore(context)
        assertEquals("Large", READING_STEPS[store.current().readingSize])
        assertEquals(19.sp, TextStep.entries[store.current().readingSize].body)
        var now = 1_794_700_000_000L
        var reply: () -> String? = { answer("0.5.2") }
        var requests = 0
        val check = UpdateCheck(
            enabled = true,
            installed = "0.5.1",
            settings = { store.current() },
            record = { at, found -> store.recordUpdateCheck(at, found) },
            online = { true },
            fetch = { requests++; reply() },
            now = { now },
        )

        // On by default, never checked.
        assertTrue(store.current().updateCheck)
        assertEquals(0L, store.current().updateCheckedAt)

        assertTrue(check.checkIfDue())
        assertEquals(now, store.current().updateCheckedAt)
        assertEquals(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2"), check.notice(store.current()))
        assertFalse(check.checkIfDue())

        // A failure the next day: the time moves, the release found stays.
        now += UpdateCheck.DAY
        reply = { throw java.io.IOException("offline") }
        assertTrue(check.checkIfDue())
        assertEquals(now, store.current().updateCheckedAt)
        assertEquals("0.5.2", check.notice(store.current())?.version)

        // "Not now" hides 0.5.2; 0.5.3 shows.
        store.dismissUpdate("0.5.2")
        assertNull(check.notice(store.current()))
        now += UpdateCheck.DAY
        reply = { answer("0.5.3") }
        assertTrue(check.checkIfDue())
        assertEquals("0.5.3", check.notice(store.current())?.version)

        // The switch off: no request, no notice.
        store.setUpdateCheck(false)
        now += UpdateCheck.DAY
        assertFalse(check.checkIfDue())
        assertNull(check.notice(store.current()))
        assertEquals(3, requests)

        // Appearance (v0.6): System by default, then each choice comes back.
        assertEquals(Appearance.SYSTEM.name, store.current().appearance)
        store.setAppearance(Appearance.DARK)
        assertEquals(Appearance.DARK.name, store.current().appearance)
        store.setAppearance(Appearance.LIGHT)
        assertEquals(Appearance.LIGHT.name, store.current().appearance)

        // Text sizes (v0.6): the step is kept by its name.
        store.setReadingSize(0)
        assertEquals(0, store.current().readingSize)
        assertEquals("Smallest", READING_STEPS[store.current().readingSize])
        store.setDisplay("Smallest", READING_STEPS.indexOf("Smaller"))
        assertEquals("Smallest", store.current().display)
        assertEquals("Smaller", READING_STEPS[store.current().readingSize])
        // Reset: Compact and Small.
        store.setDisplay("Compact", DEFAULT_READING_SIZE)
        assertEquals("Small", READING_STEPS[store.current().readingSize])
    }
}
