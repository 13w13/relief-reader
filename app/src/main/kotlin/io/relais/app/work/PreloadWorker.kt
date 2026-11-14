// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.relais.api.Requests
import io.relais.app.container
import io.relais.app.data.PdfCache
import io.relais.app.data.Preload
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Download PDFs on Wi-Fi": the PDFs of the newest key documents of what the reader follows
 * (countries, crises, organizations), with their text read, so that they open offline in the
 * morning. No API request: the reports come from the database the sync fills; only their files
 * are downloaded, on an unmetered network, under the cap of Settings (data/PdfCache.kt).
 */
class PreloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val settings = c.settings.current()
        if (!settings.preloadPdfs) return Result.success()
        // Files cleared by Android leave the database and the search index first (issue 5).
        c.pdfs.forgetMissing()
        // The documents of Following's Key view: reports and the editors' headlines.
        val reports = c.dao.observeFollowingFeed(false, Requests.REPORT_FORMATS, true, Preload.CANDIDATES).first()
        val downloaded = c.dao.pdfs().filter { File(it.path).exists() }.map { it.url }.toSet()
        val plan = Preload.plan(reports, downloaded, c.pdfs.cacheBytes(), PdfCache.capBytes(settings.pdfCapMb), System.currentTimeMillis())
        for (item in plan) {
            // The rule of the daily Most read file: nothing on a metered network.
            if (isStopped || c.pdfs.isMetered()) break
            val file = c.pdfs.download(item.url, item.reportId, Preload.MAX_FILE_BYTES) ?: continue
            runCatching { c.pdfText.ensure(item.url, item.reportId, file) }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "preload"
        private const val AFTER_SYNC = "preload_after_sync"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /** Every 6 hours when on Wi-Fi with battery and storage, or nothing when the setting is off. */
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(PERIODIC)
                manager.cancelUniqueWork(AFTER_SYNC)
                return
            }
            val request = PeriodicWorkRequestBuilder<PreloadWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** After a sync, the new reports of what the reader follows, at the next Wi-Fi. */
        fun afterSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<PreloadWorker>().setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER_SYNC, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
