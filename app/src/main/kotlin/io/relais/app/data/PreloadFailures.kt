// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The downloads the Wi-Fi preload failed, in a small file of the app's private storage: a PDF
 * that failed is tried again a day later, and left after two failures (Preload.mayTry). Kept
 * apart from the database: losing it costs one more try, nothing else.
 */
class PreloadFailures(private val file: File) {
    suspend fun read(): Map<String, Preload.Failure> = lock.withLock { withContext(Dispatchers.IO) { load() } }

    suspend fun record(url: String, now: Long, permanent: Boolean): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val next = Preload.withFailure(load(), url, now, permanent)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Preload.encode(next.values))
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun load(): Map<String, Preload.Failure> =
        runCatching { if (file.exists()) Preload.decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap())

    companion object {
        /** The periodic pass and the one after a sync may run at once: one writer at a time. */
        private val lock = Mutex()

        fun of(context: Context) = PreloadFailures(File(context.filesDir, "preload_failures.tsv"))
    }
}
