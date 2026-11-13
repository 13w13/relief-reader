// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import android.net.ConnectivityManager
import io.relais.api.MostRead
import io.relais.api.RelaisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** What the phone keeps of the "Most read" file: its lines, about 300 addresses. */
@Serializable
data class MostReadCache(
    val etag: String? = null,
    /** When the site last regenerated the file (Last-Modified). */
    val updatedAt: Long = 0,
    val checkedAt: Long = 0,
    val rows: Map<String, List<String>> = emptyMap(),
    /** Every line of the file (T13); before v0.2.2, only the front page and followed countries. */
    val full: Boolean = false,
) {
    /** The addresses of a line, or nothing once the file is more than 72 hours old. */
    fun urls(key: String, now: Long = System.currentTimeMillis()): List<String> =
        if (now - updatedAt > STALE_AFTER) emptyList() else rows[key].orEmpty()

    companion object {
        const val STALE_AFTER = 72 * Repository.ONE_HOUR
    }
}

@Serializable
data class CountLine(val id: Long = 0, val name: String, val count: Int)

@Serializable
data class MostCovered(
    val fetchedAt: Long = 0,
    val countries: List<CountLine> = emptyList(),
    val disasters: List<CountLine> = emptyList(),
) {
    val isEmpty: Boolean get() = countries.isEmpty() && disasters.isEmpty()
}

/**
 * "Most read on ReliefWeb" and "Most reports this week" for Today (produit/tour-a2.md 5).
 * The first measures what visitors of the site read, the second how many reports were
 * posted; neither measures how serious a crisis is.
 */
class Popular(
    context: Context,
    private val okHttp: OkHttpClient,
    private val userAgent: String,
    private val repository: Repository,
    private val dao: RelaisDao,
    /** The valve: how often the blocks refresh, and whether Most read is on. */
    private val config: () -> RemoteConfig = { RemoteConfig() },
) {
    private val readFile = File(context.filesDir, "most-read.json")
    private val coveredFile = File(context.filesDir, "most-covered.json")
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()

    private val _mostRead = MutableStateFlow(load(readFile, MostReadCache.serializer()))
    val mostRead: StateFlow<MostReadCache?> = _mostRead.asStateFlow()

    private val _mostCovered = MutableStateFlow(load(coveredFile, MostCovered.serializer()))
    val mostCovered: StateFlow<MostCovered?> = _mostCovered.asStateFlow()

    /** Called by Today and the background sync; each block decides whether it is due. */
    suspend fun refresh() = mutex.withLock {
        runCatching { refreshMostRead() }
        runCatching { refreshMostCovered() }
        // "New disasters this week": one request, at most every 6 hours (Repository.cachedJson).
        runCatching { repository.refreshNewDisasters() }
    }

    /** Once a day, on Wi-Fi only: the file weighs 1.24 MB and the server does not compress it. */
    private suspend fun refreshMostRead() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // A cache of an older version kept only some lines: fetch the whole file again.
        val cached = _mostRead.value?.takeIf { it.full }
        if (!config().features.mostRead) return@withContext
        if (cached != null && now - cached.checkedAt < config().cacheHours.mostRead * Repository.ONE_HOUR) return@withContext
        if (connectivity?.isActiveNetworkMetered != false) return@withContext
        val request = Request.Builder().url(MostRead.URL).header("User-Agent", userAgent)
            .apply { cached?.etag?.let { header("If-None-Match", it) } }
            .build()
        okHttp.newCall(request).execute().use { response ->
            when {
                response.code == 304 && cached != null -> save(readFile, MostReadCache.serializer(), cached.copy(checkedAt = now))
                response.isSuccessful -> {
                    val rows = mutableMapOf<String, List<String>>()
                    response.body?.charStream()?.buffered()?.useLines { lines ->
                        lines.forEach { line -> MostRead.parseLine(line)?.let { (key, urls) -> rows[key] = urls } }
                    }
                    val updated = response.headers.getDate("Last-Modified")?.time ?: now
                    save(readFile, MostReadCache.serializer(), MostReadCache(response.header("ETag"), updated, now, rows, full = true))
                }
                else -> Unit
            }
        }
        _mostRead.value = load(readFile, MostReadCache.serializer())
    }

    /** One request, kept 6 hours. */
    private suspend fun refreshMostCovered() {
        val cached = _mostCovered.value
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < config().cacheHours.mostCovered * Repository.ONE_HOUR) return
        if (!repository.hasAccess) return
        val result = repository.fetchMostCovered()
        withContext(Dispatchers.IO) { save(coveredFile, MostCovered.serializer(), result) }
        _mostCovered.value = result
    }

    private fun <T> load(file: File, serializer: KSerializer<T>): T? =
        runCatching { if (file.exists()) RelaisJson.decodeFromString(serializer, file.readText()) else null }.getOrNull()

    private fun <T> save(file: File, serializer: KSerializer<T>, value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(RelaisJson.encodeToString(serializer, value))
        tmp.renameTo(file)
    }
}
