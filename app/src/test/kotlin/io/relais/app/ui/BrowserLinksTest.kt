// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.LinkActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * "Open in browser" and "Open on ReliefWeb" for a reliefweb.int page (review of PR 47): once the
 * reader lets Re.L open these links, a plain ACTION_VIEW would come back to Re.L. A made-up
 * browser is installed; the addresses are made up.
 *
 * Robolectric uses an intent's selector only to find an explicit component; Android uses it to
 * find any target. So the selector is resolved here on its own, as Android does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BrowserLinksTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val report = "https://reliefweb.int/report/sudan/sudan-test-situation-report-no-2-990000300"
    private val linkActivity = LinkActivity::class.java.name
    private val browser = ComponentName("org.example.browser", "org.example.browser.BrowserActivity")

    @Before
    fun installABrowser() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
    }

    private fun handlers(intent: Intent) = app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }

    @Test
    fun aPlainViewOfAReportCanComeToRel() {
        // Why a selector is needed: Re.L is among the apps of a reliefweb.int report link.
        val plain = Intent(Intent.ACTION_VIEW, Uri.parse(report)).addCategory(Intent.CATEGORY_BROWSABLE)
        val apps = handlers(plain)
        assertTrue(apps.toString(), linkActivity in apps)
        assertTrue(apps.toString(), browser.className in apps)
    }

    @Test
    fun theBrowserIntentIsFoundAmongBrowsersOnly() {
        listOf(report, "https://reliefweb.int/node/990000300", "https://reliefweb.int/map/sudan/sudan-test-map").forEach { url ->
            val intent = BrowserLinks.browserIntent(" $url ")
            // The browser gets the full address, as a link.
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertTrue(Intent.CATEGORY_BROWSABLE in intent.categories)
            assertNull(intent.component)
            // Android finds the app with the selector: a web address with no host.
            val selector = intent.selector!!
            assertEquals(Intent.ACTION_VIEW, selector.action)
            assertEquals("https", selector.data?.scheme)
            assertNull(selector.data?.host)
            assertNull("one level of selector only", selector.selector)
            assertEquals(listOf(browser.className), handlers(selector))
        }
    }

    @Test
    fun aPhoneWithoutABrowserGetsTheChooserWithoutRel() {
        val chooser = BrowserLinks.chooserWithoutRel(app, report)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertEquals(listOf(ComponentName(app, LinkActivity::class.java)), excluded)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(report, target.dataString)
    }

    @Test
    fun theAppsLinksGoThroughTheBrowserIntent() {
        // MainActivity gives the handler its own context: an activity, as here.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val handler = RelaisUriHandler(activity)
        handler.openUri(report)
        val started = shadowOf(activity).nextStartedActivity!!
        assertEquals(report, started.dataString)
        assertEquals("https", started.selector?.data?.scheme)
        assertNull(started.selector?.data?.host)
        // A subdomain of reliefweb.int too.
        handler.openUri("https://m.reliefweb.int/report/sudan/x")
        assertTrue(shadowOf(activity).nextStartedActivity!!.selector != null)
        // Another site opens as before, with no selector.
        handler.openUri("https://example.org/page")
        val other = shadowOf(activity).nextStartedActivity!!
        assertEquals("https://example.org/page", other.dataString)
        assertNull(other.selector)
        assertFalse(BrowserLinks.isReliefWeb("https://reliefweb.int.example.org/x"))
    }

    @Test
    fun aLinkNoAppCanOpenSaysSo() {
        // As Compose's own handler: IllegalArgumentException; and a message, not silence.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        try {
            RelaisUriHandler(activity).openUri("mailto:office@example.org")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("No app on this phone can open this link.", ShadowToast.getTextOfLatestToast())
        }
        // safeOpen, which every button of the app uses, says it did not open.
        assertFalse(safeOpen(RelaisUriHandler(activity), "mailto:office@example.org"))
    }
}
