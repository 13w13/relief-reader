// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.relais.api.ApiException
import io.relais.app.MainActivity
import io.relais.app.R
import io.relais.app.container
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Skips a background or summary sync when the app synced within this time (T16). */
private const val SKIP_IF_SYNCED_WITHIN = 3 * 3_600_000L

/**
 * Background sync, every 12 hours by default (the valve's backgroundHours): one API request for
 * headlines, followed countries and crises, and the latest reports of Today.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        return try {
            // The app synced on its own in the last 3 hours: nothing to fetch (T16).
            val last = c.settings.current().lastSyncAt
            if (System.currentTimeMillis() - last >= SKIP_IF_SYNCED_WITHIN) c.repository.sync()
            c.popular.refresh()
            // The new documents of what the reader follows: their PDFs at the next Wi-Fi.
            if (c.settings.current().preloadPdfs) PreloadWorker.afterSync(applicationContext)
            // A new interval from the valve takes effect from the next run.
            Work.schedulePeriodicSync(applicationContext)
            maybeCleanup(applicationContext)
            Result.success()
        } catch (e: ApiException.Network) {
            Result.retry()
        } catch (e: Exception) {
            Result.success()
        }
    }
}

/** M13: one notification a day, counted from the reports already on the phone. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        /** Optional start of the counting window, used by the debug trigger. */
        const val KEY_SINCE = "since"
    }

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val settings = c.settings.current()
        if (!settings.digestEnabled) return Result.success()
        if (System.currentTimeMillis() - settings.lastSyncAt >= SKIP_IF_SYNCED_WITHIN) runCatching { c.repository.sync() }
        val now = System.currentTimeMillis()
        val forced = inputData.getLong(KEY_SINCE, 0L)
        val since = when {
            forced > 0 -> forced
            settings.lastDigestAt > 0 -> settings.lastDigestAt
            else -> now - 24 * 3_600_000L
        }
        val digest = c.repository.digestSince(since)
        if (!digest.isEmpty) Work.notifyDigest(applicationContext, digest)
        c.settings.setLastDigestAt(now)
        return Result.success()
    }
}

private suspend fun maybeCleanup(context: Context) {
    val c = context.container
    val s = c.settings.current()
    val now = System.currentTimeMillis()
    if (now - s.lastCleanupAt < 24 * 3_600_000L) return
    c.repository.cleanup(s.historyDays)
    // PDFs whose file is gone, and texts left without a PDF, leave the search index (issue 5).
    c.pdfs.forgetMissing()
    c.pdfText.forgetAllExcept(c.dao.pdfs().map { it.url }.toSet())
    c.settings.setLastCleanupAt(now)
}

object Work {
    /** The accent behind the R.L silhouette in the notification header (theme-compose.md 11.1). */
    private const val NOTIFICATION_ACCENT = 0xFF1D4ED8.toInt()
    const val DIGEST_CHANNEL = "daily_summary"
    const val ALERT_CHANNEL = "alerts"
    private const val SYNC = "sync"
    private const val DIGEST = "digest"

    /**
     * Every backgroundHours of the valve. The run falls anywhere in a window of jitterMinutes at
     * the end of each period, after a random first delay: phones do not all wake at the same
     * minute (T18). Called at start and after each run; an unchanged interval keeps its schedule.
     */
    fun schedulePeriodicSync(context: Context) {
        val config = context.container.remoteConfig.current
        val hours = config.sync.backgroundHours.toLong()
        val jitter = config.sync.jitterMinutes.toLong()
        val prefs = context.getSharedPreferences("work", Context.MODE_PRIVATE)
        if (prefs.getLong("sync_hours", -1) == hours && prefs.getLong("sync_jitter", -1) == jitter) return
        val flex = maxOf(jitter, PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS / 60_000L).coerceAtMost(hours * 60)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS, flex, TimeUnit.MINUTES)
            .setInitialDelay(kotlin.random.Random.nextLong(0, jitter + 1), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
        prefs.edit().putLong("sync_hours", hours).putLong("sync_jitter", jitter).apply()
    }

    /** Schedules the daily summary around [hour]:[minute], or cancels it. */
    fun scheduleDigest(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(DIGEST)
            return
        }
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        val request = PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(DIGEST, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** "New appeal for Sudan" / "<title> · IFRC", or one grouped notification (passe-v0.2.md 6.4). */
    @SuppressLint("MissingPermission")
    fun notifyAlerts(context: Context, plan: io.relais.app.data.AlertPlan) {
        if (!canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        // Each notification opens its own screen: the report, or Following for the grouped one.
        fun builder(id: Int, open: Long): NotificationCompat.Builder {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, open)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rel)
                .setColor(NOTIFICATION_ACCENT)
                .setContentIntent(pending)
                .setAutoCancel(true)
        }
        if (plan.grouped > 0) {
            val text = context.resources.getQuantityString(R.plurals.alert_grouped, plan.grouped, plan.grouped)
            manager.notify(ALERT_GROUPED_ID, builder(ALERT_GROUPED_ID, MainActivity.OPEN_FOLLOWING).setContentTitle(text).build())
            return
        }
        plan.single.forEach { item ->
            val title = context.getString(
                if (item.kind == io.relais.app.data.AlertKind.APPEAL) R.string.alert_appeal else R.string.alert_flash,
                item.where,
            )
            val text = listOf(item.report.title, item.report.sourceShort).filter { it.isNotBlank() }.joinToString(" · ")
            val id = ALERT_ID_BASE + (item.report.id % 1000).toInt()
            manager.notify(
                id,
                builder(id, item.report.id).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).build(),
            )
        }
    }

    private const val ALERT_ID_BASE = 1000
    private const val ALERT_GROUPED_ID = 999

    @SuppressLint("MissingPermission")
    fun notifyDigest(context: Context, digest: io.relais.app.data.Repository.Digest) {
        if (!canNotify(context)) return
        val parts = mutableListOf<String>()
        if (digest.headlines == 1) parts += context.getString(R.string.notif_headlines_one)
        if (digest.headlines > 1) parts += context.getString(R.string.notif_headlines_many, digest.headlines)
        when {
            digest.countries.size == 1 -> parts += context.getString(
                R.string.notif_one_country, digest.countries[0].second, digest.countries[0].first,
            )
            digest.countries.size > 1 -> parts += digest.countries.joinToString(", ") { "${it.first} ${it.second}" }
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rel)
                .setColor(NOTIFICATION_ACCENT)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(parts.joinToString(" · "))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }
}
