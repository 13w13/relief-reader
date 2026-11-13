// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.relais.app.work.DigestWorker
import io.relais.app.work.SyncWorker

/**
 * Debug builds only (tests-parcours.md 0.3, item 3): runs the sync or the daily summary now,
 * instead of waiting for WorkManager's schedule. No screen: it enqueues the work and closes.
 *
 *     adb shell am start -a android.intent.action.VIEW -d relaisdebug://sync
 *     adb shell am start -a android.intent.action.VIEW -d relaisdebug://digest
 *
 * or `openLink: relaisdebug://digest` in a Maestro flow. The daily summary still follows the
 * app's rules: nothing is posted if it is turned off or if there is nothing new.
 */
class DebugTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val work = WorkManager.getInstance(applicationContext)
        when (intent?.data?.host) {
            "sync" -> work.enqueueUniqueWork(
                "debug-sync",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
            "digest" -> work.enqueueUniqueWork(
                "debug-digest",
                ExistingWorkPolicy.REPLACE,
                // Count the last 7 days, so a test gets a summary even on a quiet day.
                OneTimeWorkRequestBuilder<DigestWorker>()
                    .setInputData(androidx.work.workDataOf(DigestWorker.KEY_SINCE to System.currentTimeMillis() - 7 * 24 * 3_600_000L))
                    .build(),
            )
            else -> Log.w(TAG, "Unknown trigger: ${intent?.data}. Use relaisdebug://sync or relaisdebug://digest.")
        }
        finish()
    }

    private companion object {
        const val TAG = "RelaisDebug"
    }
}
