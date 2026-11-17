// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.relais.app.data.ReliefWebLinks

/**
 * The door for other apps (founder, 17/11): Share > Re.L with a text that holds a ReliefWeb link,
 * or a reliefweb.int link opened with Re.L. It shows nothing. It reads the shared text or the
 * link's address, as a string and nothing else, keeps only a page of reliefweb.int
 * (ReliefWebLinks), and hands that string alone to MainActivity, in Re.L's own task: an open app
 * gets it in onNewIntent, with no second copy. Anything else is refused with a short message.
 * No request is made here.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = linkOf(intent)
        if (link != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LINK, link.url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } else {
            Toast.makeText(this, R.string.link_refused, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        /**
         * The ReliefWeb link of an intent from another app: the shared text of ACTION_SEND
         * (text/plain), or the address of ACTION_VIEW. The text is read as a CharSequence only;
         * an extras bundle that cannot be read gives null instead of a crash.
         */
        fun linkOf(intent: Intent?): ReliefWebLinks.Link? = runCatching {
            when (intent?.action) {
                Intent.ACTION_SEND ->
                    if (intent.type?.startsWith("text/plain") == true) ReliefWebLinks.fromSharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) else null
                Intent.ACTION_VIEW -> ReliefWebLinks.fromUrl(intent.dataString)
                else -> null
            }
        }.getOrNull()
    }
}
