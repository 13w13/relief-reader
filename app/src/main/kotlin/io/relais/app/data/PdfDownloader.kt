// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    data class Running(val progress: Float) : DownloadState
    data object Failed : DownloadState

    /** Over [PdfDownloader.MAX_BYTES]: not downloaded; the report offers ReliefWeb instead. */
    data class TooLarge(val bytes: Long) : DownloadState
}

/** What a download of the Wi-Fi preload gives (PdfDownloader.download). */
sealed interface Preloaded {
    data class Saved(val file: File) : Preloaded

    /** Over the preload's limit, by its announced size or while it came in: it will not shrink. */
    data object TooLarge : Preloaded

    /** An HTTP error, a web page in place of the PDF, or the network: worth one more try later. */
    data object Failed : Preloaded

    /** Already being downloaded, or the reader opened it meanwhile and took over: nothing to note. */
    data object Busy : Preloaded
}

/** A file larger than the limit, found by its announced size or while it downloads. */
private class TooLargeException(val bytes: Long) : IOException("Over the size limit")

/**
 * Downloads PDFs on request. A PDF goes to the app cache, which Android may clear;
 * the PDF of a report saved in the Library goes to private storage and stays for
 * offline reading. Files are read by the built-in viewer only.
 */
class PdfDownloader(
    context: Context,
    private val dao: RelaisDao,
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val userAgent: String,
    /** Told when a PDF is saved on the phone (its text is then read), and when one is deleted. */
    private val onSaved: (url: String, reportId: Long, file: File) -> Unit = { _, _, _ -> },
    private val onDeleted: (url: String) -> Unit = {},
    /** The cap of the cache folder, from Settings (issue 6), read after each download. */
    private val capBytes: suspend () -> Long = { PdfCache.capBytes(PdfCache.DEFAULT_CAP_MB) },
    /** Saved and highlighted reports: their PDFs are never deleted to make room. */
    private val protectedReports: suspend () -> Set<Long> = { emptySet() },
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.cacheDir, "pdf").apply { mkdirs() }
    private val keepDir = File(context.filesDir, "pdf").apply { mkdirs() }

    /** The cache folder, whose PDFs the cap counts (PdfCache.inCache). */
    val cacheFolder: String get() = cacheDir.absolutePath
    private val jobs = ConcurrentHashMap<String, Job>()

    /** The downloads of the Wi-Fi preload: the reader's own download takes over from them. */
    private val preloads: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    val downloaded: Flow<Map<String, PdfEntity>> = dao.observePdfs().map { list ->
        list.filter { File(it.path).exists() }.associateBy { it.url }
    }

    fun isMetered(): Boolean =
        appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    fun start(url: String, reportId: Long) {
        val previous = jobs[url]
        if (previous?.isActive == true && previous !in preloads) return
        _states.update { it + (url to DownloadState.Running(0f)) }
        jobs[url] = scope.launch(Dispatchers.IO) {
            // A PDF the Wi-Fi preload is fetching: it stops, its .part file goes, and the
            // reader's download starts with its progress and the reader's size limit.
            previous?.cancelAndJoin()
            try {
                val file = fetch(url, reportId, MAX_BYTES) { progress -> _states.update { it + (url to DownloadState.Running(progress)) } }
                _states.update { it - url }
                onSaved(url, reportId, file)
                enforceCap(justDownloaded = url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _states.update { it - url }
                throw e
            } catch (e: TooLargeException) {
                _states.update { it + (url to DownloadState.TooLarge(e.bytes)) }
            } catch (e: Exception) {
                _states.update { it + (url to DownloadState.Failed) }
            } finally {
                jobs.remove(url, coroutineContext.job)
            }
        }
    }

    /**
     * Downloads a PDF without telling the screens: the Wi-Fi preload (PreloadWorker). The caller
     * reads its text; the cap is kept as after any download. When the caller is cancelled
     * (WorkManager stops the worker on a metered network, or the setting goes off), the transfer
     * stops with it and its `.part` file goes. When the reader opens the same PDF meanwhile,
     * their download takes over (start) and this one gives [Preloaded.Busy].
     */
    suspend fun download(url: String, reportId: Long, maxBytes: Long): Preloaded {
        var result: Preloaded = Preloaded.Busy
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                result = Preloaded.Saved(fetch(url, reportId, maxBytes) {})
                enforceCap(justDownloaded = url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: TooLargeException) {
                result = Preloaded.TooLarge
            } catch (e: Exception) {
                if (result !is Preloaded.Saved) result = Preloaded.Failed
            } finally {
                jobs.remove(url, coroutineContext.job)
            }
        }
        preloads.add(job)
        try {
            // The same map as start(): a PDF being downloaded, or about to be, is not downloaded twice.
            val running = jobs.putIfAbsent(url, job)
            if (running != null && (!running.isCompleted || !jobs.replace(url, running, job))) {
                job.cancel()
                return Preloaded.Busy
            }
            job.start()
            job.join()
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller is stopped: so is the transfer, before the caller goes.
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw e
        } finally {
            preloads.remove(job)
        }
    }

    /**
     * The download itself: to the cache folder, or to private storage for a saved report. A file
     * larger than [maxBytes], by its announced size or while it comes in, is stopped. Throws on
     * failure; the `.part` file never stays.
     */
    private suspend fun fetch(url: String, reportId: Long, maxBytes: Long, onProgress: (Float) -> Unit): File {
        val keep = dao.reading(reportId)?.savedAt != null
        val dir = if (keep) keepDir else cacheDir
        val name = fileName(url)
        val part = File(dir, "$name.part")
        try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                val total = body.contentLength()
                if (total > maxBytes) throw TooLargeException(total)
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastShown = 0f
                        while (input.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            done += read
                            // A server that announces no size, or a smaller one, is stopped at the limit.
                            if (done > maxBytes) throw TooLargeException(done)
                            if (total > 0) {
                                val progress = done.toFloat() / total
                                if (progress - lastShown >= 0.02f) {
                                    lastShown = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            }
            // A web page served in place of the file (a block page, an error) is not kept.
            if (!looksLikePdf(part)) throw IOException("Not a PDF")
            val target = File(dir, name)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save the file")
            dao.upsertPdf(PdfEntity(url, reportId, target.absolutePath, target.length(), System.currentTimeMillis()))
            return target
        } finally {
            part.delete()
        }
    }

    /**
     * Keeps the cache folder under the cap (issue 6): deletes the oldest PDFs first, never the
     * one just downloaded, nor one of a saved or highlighted report. Returns how many went.
     */
    suspend fun enforceCap(justDownloaded: String? = null): Int = withContext(Dispatchers.IO) {
        val evict = PdfCache.toEvict(dao.pdfs(), cacheDir.absolutePath, capBytes(), justDownloaded, protectedReports(), System.currentTimeMillis())
        evict.forEach { delete(it) }
        evict.size
    }

    /**
     * Forgets the PDFs whose file is gone (issue 5): Android may clear the cache folder on its
     * own. Their row goes, and their text leaves the search index, except for a saved or
     * highlighted report: its highlights and reading position point into that text, which
     * serves again when the PDF is downloaded again. Returns how many.
     */
    suspend fun forgetMissing(): Int = withContext(Dispatchers.IO) {
        val missing = dao.pdfs().filter { !File(it.path).exists() }
        if (missing.isEmpty()) return@withContext 0
        val keepText = protectedReports()
        missing.forEach { pdf ->
            dao.deletePdf(pdf.url)
            if (pdf.reportId !in keepText) onDeleted(pdf.url)
        }
        missing.size
    }

    /** Saved and highlighted reports: their PDFs are never deleted to make room, nor their texts forgotten. */
    suspend fun protectedReportIds(): Set<Long> = protectedReports()

    /** The bytes of the PDFs in the cache folder: what the cap counts. */
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
        PdfCache.inCache(dao.pdfs(), cacheDir.absolutePath).filter { File(it.path).exists() }.sumOf { it.bytes }
    }

    private suspend fun delete(pdf: PdfEntity) {
        File(pdf.path).delete()
        dao.deletePdf(pdf.url)
        onDeleted(pdf.url)
    }

    companion object {
        /** 100 MB: larger files stay on ReliefWeb (audit S1, and the PDF limits of v0.2.2). */
        const val MAX_BYTES = 100L * 1024 * 1024

        fun tooLarge(size: Long?): Boolean = size != null && size > MAX_BYTES
    }

    fun cancel(url: String) {
        jobs[url]?.cancel()
    }

    /** The downloaded file, or null if it is missing (never downloaded, or cleared by Android). */
    suspend fun fileFor(url: String): File? = withContext(Dispatchers.IO) {
        val pdf = dao.pdf(url) ?: return@withContext null
        val file = File(pdf.path)
        if (file.exists()) {
            // Opened: the cap keeps it for now, and takes the least recently used first (PdfCache).
            dao.upsertPdf(pdf.copy(downloadedAt = System.currentTimeMillis()))
            file
        } else {
            // Cleared by Android: the row goes, and the text leaves the search index (issue 5),
            // unless a saved or highlighted report holds it (forgetMissing).
            dao.deletePdf(url)
            if (pdf.reportId !in protectedReports()) onDeleted(url)
            null
        }
    }

    /** Moves the PDFs of a report to private storage when it is saved, back to the cache otherwise. */
    suspend fun relocate(reportId: Long, keep: Boolean) = withContext(Dispatchers.IO) {
        val dir = if (keep) keepDir else cacheDir
        for (pdf in dao.pdfsForReport(reportId)) {
            val from = File(pdf.path)
            if (!from.exists() || from.parentFile == dir) continue
            val to = File(dir, from.name)
            if (from.renameTo(to) || (runCatching { from.copyTo(to, overwrite = true) }.isSuccess && from.delete())) {
                dao.upsertPdf(pdf.copy(path = to.absolutePath))
            }
        }
    }

    /** Deletes downloaded PDFs, except those of saved reports. Returns how many were deleted. */
    suspend fun deleteAll(keepReportIds: Set<Long>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (pdf in dao.pdfs()) {
            if (pdf.reportId in keepReportIds) continue
            delete(pdf)
            count++
        }
        count
    }

    private fun fileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".pdf"
    }
}

/** A PDF has "%PDF-" in its first 1024 bytes; an HTML page saved in its place does not. */
internal fun looksLikePdf(file: File): Boolean {
    val head = ByteArray(1024)
    val read = file.inputStream().use { it.read(head) }
    return read > 0 && String(head, 0, read, Charsets.ISO_8859_1).contains("%PDF-")
}
