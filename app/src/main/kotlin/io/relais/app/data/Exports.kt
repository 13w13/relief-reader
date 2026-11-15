// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import java.io.File

/**
 * The files Re.L hands to the share menu (library backup, highlights), in cacheDir/exports
 * (security audit of 15/11): at each launch and each return to the app, a file shared more than
 * [KEEP_MS] ago goes, and each new export replaces the previous one. Not at once: the receiving
 * app (a mail draft, an upload) may still be reading it, even after Android has started Re.L
 * again only to serve the file (review of PR 26).
 */
object Exports {
    const val KEEP_MS = 10 * 60 * 1000L

    fun dir(context: Context): File = File(context.cacheDir, "exports")

    /** Deletes every export. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Deletes the exports written before [now] minus [KEEP_MS]. Returns how many. */
    fun clearOld(context: Context, now: Long = System.currentTimeMillis()): Int {
        val old = dir(context).listFiles()?.filter { now - it.lastModified() > KEEP_MS }.orEmpty()
        old.forEach { it.delete() }
        return old.size
    }
}
