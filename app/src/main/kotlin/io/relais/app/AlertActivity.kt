// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where an alert's notification leads (review of PR 47). This activity is not exported: only
 * Re.L's own notifications, through their PendingIntent, can start it. So [EXTRA_OPEN] is read
 * here and nowhere else. It hands the report, or Following, to the main screen inside the app
 * (AppContainer.openRequest), then brings MainActivity forward with no extra, and shows nothing.
 *
 * MainActivity is exported, as the launcher, and no longer reads EXTRA_OPEN: another app that
 * sets it there gets nothing opened, nothing written and no request.
 */
class AlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            (applicationContext as? RelaisApp)?.container?.let { take(intent, it.openRequest) }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        /** A report to open (an alert), or [OPEN_FOLLOWING] for Following (the grouped alert). */
        const val EXTRA_OPEN = "io.relais.app.open"
        const val OPEN_FOLLOWING = -1L

        /** The intent of an alert's notification: this activity, with what to open. */
        fun intent(context: Context, open: Long): Intent =
            Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN, open)

        /** Hands what [intent] opens to the main screen; an extra that cannot be read is ignored. */
        fun take(intent: Intent?, openRequest: MutableStateFlow<Long?>) {
            val open = runCatching { intent?.getLongExtra(EXTRA_OPEN, 0L) }.getOrNull() ?: 0L
            if (open != 0L) openRequest.value = open
        }
    }
}
