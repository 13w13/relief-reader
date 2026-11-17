// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.content.Intent
import io.relais.app.data.ReliefWebLinks
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What MainActivity takes from an intent: a report to open (a notification) and a ReliefWeb link
 * (LinkActivity, 17/11). Each intent once: [take] with `restored` true (a rotation, a restart
 * that brings the same intent back) does nothing. MainActivity is exported, as the launcher, so
 * any app could send these extras: the link is checked again here, and extras that cannot be
 * read are ignored rather than crash the app.
 */
class IntentInbox(
    private val openRequest: MutableStateFlow<Long?>,
    private val linkRequest: MutableStateFlow<io.relais.app.data.PendingLink?>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun take(intent: Intent?, restored: Boolean) {
        if (restored || intent == null) return
        runCatching {
            val open = intent.getLongExtra(MainActivity.EXTRA_OPEN, 0L)
            if (open != 0L) openRequest.value = open
            ReliefWebLinks.fromUrl(intent.getStringExtra(MainActivity.EXTRA_LINK))?.let { linkRequest.value = io.relais.app.data.PendingLink(it.url, now()) }
        }
    }
}
