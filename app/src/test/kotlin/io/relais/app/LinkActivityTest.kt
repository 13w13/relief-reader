// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The door for other apps (17/11): what LinkActivity takes from a share or a link, what it hands
 * to MainActivity, and what MainActivity takes from an intent (IntentInbox). Made-up addresses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LinkActivityTest {
    private val report = "https://reliefweb.int/report/sudan/sudan-test-situation-report-no-2-990000300"

    private fun share(text: CharSequence?, type: String = "text/plain") =
        Intent(Intent.ACTION_SEND).setType(type).apply { if (text != null) putExtra(Intent.EXTRA_TEXT, text) }

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    /** Runs LinkActivity on [intent]; the intent it started, or null. It always finishes. */
    private fun run(intent: Intent): Intent? {
        val activity = Robolectric.buildActivity(LinkActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        return shadowOf(activity).nextStartedActivity
    }

    @Test
    fun aSharedTitleAndLinkOpenTheReportInRel() {
        val started = run(share("Sudan: Test situation report No. 2\n$report?utm_source=whatsapp"))!!
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(report, started.getStringExtra(MainActivity.EXTRA_LINK))
        // Only the checked link goes on, in Re.L's own task, single top.
        assertEquals(setOf(MainActivity.EXTRA_LINK), started.extras?.keySet())
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, started.flags and flags)
    }

    @Test
    fun aLinkOpenedWithRelGoesOnToo() {
        assertEquals(report, run(view("$report#top"))!!.getStringExtra(MainActivity.EXTRA_LINK))
        assertEquals("https://reliefweb.int/node/990000300", run(view("https://reliefweb.int/node/990000300"))!!.getStringExtra(MainActivity.EXTRA_LINK))
    }

    @Test
    fun anythingElseIsRefusedWithAMessage() {
        listOf(
            share("No link"),
            share("https://reliefweb.int.example.org/report/sudan/x"),
            share("http://reliefweb.int/report/sudan/x"),
            share(report, type = "image/png"),
            share(null),
            view("file:///sdcard/Download/report.html"),
            view("content://com.example.files/report"),
            view("intent://reliefweb.int/report/x#Intent;scheme=https;end"),
            view("https://www.reliefweb.int/report/sudan/x"),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
            Intent("io.relais.app.OTHER").putExtra(Intent.EXTRA_TEXT, report),
        ).forEach { intent ->
            assertNull(intent.toString(), run(intent))
            assertEquals("Re.L opens links to reliefweb.int only.", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun extrasOfAnotherTypeAreNotRead() {
        // EXTRA_TEXT as a bundle or a number instead of text: refused, no crash.
        assertNull(run(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, android.os.Bundle())))
        assertNull(run(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 42)))
    }

    @Test
    fun aVeryLongSharedTextIsReadOnlyAtItsStart() {
        assertNull(run(share("x".repeat(10_000) + " $report")))
        assertEquals(report, run(share("$report " + "y".repeat(1_000_000)))!!.getStringExtra(MainActivity.EXTRA_LINK))
    }

    // --- MainActivity's intents -------------------------------------------------------------------

    private val open = MutableStateFlow<Long?>(null)
    private val link = MutableStateFlow<String?>(null)
    private val inbox = IntentInbox(open, link)

    @Test
    fun theLinkIsTakenOnceAndNotAgainAfterARotation() {
        val intent = Intent().putExtra(MainActivity.EXTRA_LINK, report)
        inbox.take(intent, restored = false)
        assertEquals(report, link.value)
        // Consumed by the main screen, then the activity is recreated with the same intent.
        link.value = null
        inbox.take(intent, restored = true)
        assertNull(link.value)
    }

    @Test
    fun aLinkSentStraightToMainActivityIsCheckedAgain() {
        // MainActivity is exported (the launcher): another app could set the extra itself.
        listOf("https://example.org/x", "file:///sdcard/x", "javascript:alert(1)", "http://reliefweb.int/report/a/b").forEach {
            inbox.take(Intent().putExtra(MainActivity.EXTRA_LINK, it), restored = false)
            assertNull(it, link.value)
        }
        inbox.take(Intent().putExtra(MainActivity.EXTRA_LINK, "$report?x=1"), restored = false)
        assertEquals(report, link.value)
    }

    @Test
    fun aNotificationStillOpensItsReport() {
        inbox.take(Intent().putExtra(MainActivity.EXTRA_OPEN, 42L), restored = false)
        assertEquals(42L, open.value)
        assertNull(link.value)
    }

    @Test
    fun onlyTheMainScreenTakesALink() {
        // A link shared during the first launch waits: the setup, the tour and Connect come first.
        assertEquals(io.relais.app.data.AppScreen.LOADING, io.relais.app.data.appScreen(null, ""))
        assertEquals(io.relais.app.data.AppScreen.ONBOARDING, io.relais.app.data.appScreen(io.relais.app.data.Settings(), ""))
        assertEquals(
            io.relais.app.data.AppScreen.TOUR,
            io.relais.app.data.appScreen(io.relais.app.data.Settings(onboardingDone = true, tourPending = true, connectAsked = true), ""),
        )
        assertEquals(io.relais.app.data.AppScreen.CONNECT, io.relais.app.data.appScreen(io.relais.app.data.Settings(onboardingDone = true), ""))
        assertEquals(io.relais.app.data.AppScreen.MAIN, io.relais.app.data.appScreen(io.relais.app.data.Settings(onboardingDone = true, connectAsked = true), ""))
        assertEquals(io.relais.app.data.AppScreen.MAIN, io.relais.app.data.appScreen(io.relais.app.data.Settings(onboardingDone = true), "built-in"))
    }
}
