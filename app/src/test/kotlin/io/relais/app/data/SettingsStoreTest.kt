// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real SettingsStore (DataStore): the new-version check, not a copy of its writes (review of
 * PR 23), then Appearance and the text sizes of v0.6. One test only: the store's DataStore is one
 * per process.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsStoreTest {
    private fun answer(version: String) =
        """{"tag_name": "v$version", "html_url": "https://github.com/13w13/relief-reader/releases/tag/v$version", "draft": false, "prerelease": false}"""

    @Test
    fun theStoreKeepsWhatTheReaderChose() = runBlocking {
        val store = SettingsStore(ApplicationProvider.getApplicationContext())
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

        // Text sizes (v0.6): Small and Compact by default; the step is kept by its name.
        assertEquals(DEFAULT_READING_SIZE, store.current().readingSize)
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
