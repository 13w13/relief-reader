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
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.TextStep
import io.relais.app.ui.WelcomeStep
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real SettingsStore (DataStore): first a v0.5 settings file migrated on opening, then the
 * new-version check, not a copy of its writes (review of PR 23), then Appearance (the welcome
 * screen included) and the text sizes of v0.6. One test only: the store's DataStore is one per
 * process, and the v0.5 file must be written before it opens; so the welcome screen's test on the
 * real store is here too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsStoreTest {
    @get:Rule
    val compose = createComposeRule()

    private fun answer(version: String) =
        """{"tag_name": "v$version", "html_url": "https://github.com/13w13/relief-reader/releases/tag/v$version", "draft": false, "prerelease": false}"""

    @Test
    fun theStoreKeepsWhatTheReaderChose() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // A phone updated from v0.5: its settings file holds the step as an index, 3 = Large
        // (19 sp). Written by a DataStore of its own, closed before the app's opens the file.
        val writer = CoroutineScope(Dispatchers.IO + Job())
        val v05 = PreferenceDataStoreFactory.create(scope = writer, produceFile = { context.preferencesDataStoreFile("settings") })
        v05.edit {
            it[intPreferencesKey("reading_size")] = 3
            // "Not now" as 0.6.x stored it: a version, no date.
            it[androidx.datastore.preferences.core.stringPreferencesKey("update_dismissed")] = "0.6.1"
            // A reader who finished the first launch long ago.
            it[androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_done")] = true
        }
        writer.coroutineContext.job.cancelAndJoin()

        // The real store runs the migrations it is built with (review of PR 24): the same 19 sp.
        val before = System.currentTimeMillis()
        val store = SettingsStore(context)
        // A reader updating from 0.6.x never sees the first launch setup again (review of PR 44),
        // nor gets the tour uninvited (issue 37).
        assertTrue(store.current().onboardingDone)
        assertFalse(store.current().needsOnboarding)
        assertFalse(store.current().showsTour)
        // A first launch setup that ends: the tour once, then never again after Skip or Start reading.
        store.setOnboardingDone()
        assertTrue(store.current().showsTour)
        store.setTourSeen()
        assertFalse(store.current().showsTour)
        // The 0.6.x "Not now" is dated at the update: its notice comes back 3 days later.
        assertEquals("0.6.1", store.current().updateDismissed)
        assertTrue(store.current().updateDismissedAt in before..System.currentTimeMillis())
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
        store.dismissUpdate("0.5.2", now)
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

        // The APK of the release (16/11): none from answers without assets, as for a value stored by
        // 0.6.x; kept with its release, kept after a failure, removed by a release without one.
        assertNull(store.current().updateApk)
        val apk = "https://github.com/13w13/relief-reader/releases/download/v0.5.4/rel-0.5.4.apk"
        store.recordUpdateCheck(now, LatestRelease("0.5.4", "https://github.com/13w13/relief-reader/releases/tag/v0.5.4", apk))
        assertEquals(apk, store.current().updateApk)
        store.recordUpdateCheck(now + 1, null)
        assertEquals(apk, store.current().updateApk)
        store.recordUpdateCheck(now + 2, LatestRelease("0.5.5", "https://github.com/13w13/relief-reader/releases/tag/v0.5.5"))
        assertEquals("0.5.5", store.current().updateVersion)
        assertNull(store.current().updateApk)

        // A quota refusal's time, and a "Not now" cleared by a Check now, are kept (review of PR 44).
        store.holdUpdateCheck(now + UpdateCheck.HOUR)
        assertEquals(now + UpdateCheck.HOUR, store.current().updateHeldUntil)
        store.dismissUpdate("0.5.5", now)
        store.clearUpdateDismissed()
        assertNull(store.current().updateDismissed)
        assertEquals(0L, store.current().updateDismissedAt)

        // Appearance (v0.6): System by default, then each choice comes back.
        assertEquals(Appearance.SYSTEM.name, store.current().appearance)
        store.setAppearance(Appearance.DARK)
        assertEquals(Appearance.DARK.name, store.current().appearance)
        store.setAppearance(Appearance.LIGHT)
        assertEquals(Appearance.LIGHT.name, store.current().appearance)

        // The welcome screen on this real store (16/11, review of PR 44), wired as OnboardingFlow:
        // it shows the store's appearance through a StateFlow (as AppContainer.appearance) and a
        // tap saves at once. Kept across a rotation, with no frame of another choice first.
        val flowScope = CoroutineScope(Dispatchers.IO + Job())
        val appearance = store.flow.map { Appearance.of(it.appearance) }
            .stateIn(flowScope, SharingStarted.Eagerly, Appearance.of(store.current().appearance))
        val restoration = StateRestorationTester(compose)
        // One composition per test: the welcome screen, then the tour (below).
        var screen by androidx.compose.runtime.mutableStateOf("welcome")
        restoration.setContent {
            val shown by appearance.collectAsState()
            RelaisTheme(darkTheme = shown == Appearance.DARK) {
                if (screen == "welcome") {
                    WelcomeStep(appearance = shown, onAppearance = { runBlocking { store.setAppearance(it) } }, onContinue = {})
                } else {
                    // As MainActivity: the end of the tour notes it seen in the store.
                    io.relais.app.ui.TourScreen(onDone = { runBlocking { store.setTourSeen() } })
                }
            }
        }
        compose.onNodeWithTag("appearance_light").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("appearance_dark").performScrollTo().performClick()
        assertEquals(Appearance.DARK.name, store.current().appearance)
        compose.waitUntil(5_000) { appearance.value == Appearance.DARK }
        compose.onNodeWithTag("appearance_dark").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("appearance_dark").performScrollTo().assertIsSelected()

        // The tour after a first launch setup, ended with Skip: seen, in the real store (review of PR 45).
        store.setOnboardingDone()
        assertTrue(store.current().showsTour)
        screen = "tour"
        compose.onNodeWithTag("tour_skip").performClick()
        assertFalse(store.current().showsTour)
        flowScope.cancel()
        store.setAppearance(Appearance.LIGHT)

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
