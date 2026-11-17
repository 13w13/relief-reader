// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.content.Intent
import io.relais.app.data.ReliefWebLinks
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What MainActivity takes from an intent: a ReliefWeb link (LinkActivity, 17/11). Each intent
 * once: [take] with `restored` true (a rotation, a restart that brings the same intent back) does
 * nothing. MainActivity is exported, as the launcher, so any app could send the extra: the link
 * is checked again here and opens like any link from outside, and an extra that cannot be read
 * is ignored rather than crash the app. A report id (AlertActivity.EXTRA_OPEN) is not read here:
 * only Re.L's notifications open a report by its id, through AlertActivity (review of PR 47).
 */
class IntentInbox(
    private val linkRequest: MutableStateFlow<io.relais.app.data.PendingLink?>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun take(intent: Intent?, restored: Boolean) {
        if (restored || intent == null) return
        runCatching {
            ReliefWebLinks.fromUrl(intent.getStringExtra(MainActivity.EXTRA_LINK))?.let { linkRequest.value = io.relais.app.data.PendingLink(it.url, now()) }
        }
    }
}
