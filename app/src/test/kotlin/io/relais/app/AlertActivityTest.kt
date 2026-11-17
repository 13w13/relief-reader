// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.AlertItem
import io.relais.app.data.AlertKind
import io.relais.app.data.AlertPlan
import io.relais.app.data.ReportEntity
import io.relais.app.work.Work
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A report opened by its id (review of PR 47): only Re.L's own alerts can do it, through
 * AlertActivity, which is not exported. MainActivity, the launcher, no longer reads the id.
 * Made-up reports.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlertActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun report(id: Long) = ReportEntity(
        id = id, title = "Noravia: Flash update No. $id", headlineTitle = null, headlineSummary = null,
        imageUrl = null, imageCaption = null, imageCopyright = null, urlAlias = null, origin = null,
        dateCreated = 1_794_614_400_000L, dateOriginal = null, primaryCountryId = 990, primaryCountryName = "Noravia",
        countries = emptyList(), sources = emptyList(), formatId = 10, formatName = "Situation Report",
        themes = emptyList(), languages = listOf("English"), files = emptyList(), bodyHtml = null,
        isHeadline = false, fromRss = false, fetchedAt = 1_794_614_400_000L,
    )

    @Test
    fun noOtherAppCanStartIt() {
        val pm = app.packageManager
        assertFalse(pm.getActivityInfo(ComponentName(app, AlertActivity::class.java), 0).exported)
        // MainActivity is the launcher, so exported: it must not open a report by its id.
        assertTrue(pm.getActivityInfo(ComponentName(app, MainActivity::class.java), 0).exported)
    }

    @Test
    fun anAlertsTapOpensItsReport() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Work.notifyAlerts(app, AlertPlan(single = listOf(AlertItem("country:990", "Noravia", AlertKind.FLASH_UPDATE, report(990000300))), grouped = 0))
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val pending = shadowOf(posted.contentIntent)
        assertTrue(pending.isActivityIntent)
        val intent = pending.savedIntent
        assertEquals(AlertActivity::class.java.name, intent.component?.className)
        // The tap hands the report to the main screen, inside the app.
        val open = MutableStateFlow<Long?>(null)
        AlertActivity.take(intent, open)
        assertEquals(990000300L, open.value)
    }

    @Test
    fun theGroupedAlertOpensFollowing() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Work.notifyAlerts(app, AlertPlan(single = emptyList(), grouped = 4))
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val open = MutableStateFlow<Long?>(null)
        AlertActivity.take(shadowOf(posted.contentIntent).savedIntent, open)
        assertEquals(AlertActivity.OPEN_FOLLOWING, open.value)
    }

    @Test
    fun itBringsTheAppForwardWithNoExtraAndFinishes() {
        val activity = Robolectric.buildActivity(AlertActivity::class.java, AlertActivity.intent(app, 42)).create().get()
        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity!!
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertNull(started.extras)
    }

    @Test
    fun anExtraThatCannotBeReadIsIgnored() {
        val open = MutableStateFlow<Long?>(null)
        AlertActivity.take(Intent().putExtra(AlertActivity.EXTRA_OPEN, "42"), open)
        AlertActivity.take(Intent(), open)
        AlertActivity.take(null, open)
        assertNull(open.value)
    }

    @Test
    fun theReportIdIsReadByAlertActivityAlone() {
        // An app that sets the id on MainActivity reaches nothing: only AlertActivity reads it
        // (Work.kt puts it in the notification), and the request the main screen opens is
        // declared in the container, set by AlertActivity, read and cleared by the main screen.
        val main = File("src/main/kotlin")
        fun filesWith(text: String) = main.walk().filter { it.isFile && it.readText().contains(text) }.map { it.name }.toSet()
        assertEquals(setOf("AlertActivity.kt", "Work.kt"), filesWith("EXTRA_OPEN"))
        assertEquals(setOf("AlertActivity.kt", "RelaisApp.kt", "Navigation.kt"), filesWith("openRequest"))
    }
}
