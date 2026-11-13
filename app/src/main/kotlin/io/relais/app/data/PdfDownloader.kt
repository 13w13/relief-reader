// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

/** A file larger than the limit, found by its announced size or while it downloads. */
private class TooLargeException(val bytes: Long) : IOException("Over ${PdfDownloader.MAX_BYTES} bytes")

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
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.cacheDir, "pdf").apply { mkdirs() }
    private val keepDir = File(context.filesDir, "pdf").apply { mkdirs() }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    val downloaded: Flow<Map<String, PdfEntity>> = dao.observePdfs().map { list ->
        list.filter { File(it.path).exists() }.associateBy { it.url }
    }

    fun isMetered(): Boolean =
        appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    fun start(url: String, reportId: Long) {
        if (jobs[url]?.isActive == true) return
        _states.update { it + (url to DownloadState.Running(0f)) }
        jobs[url] = scope.launch(Dispatchers.IO) {
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
                    if (total > MAX_BYTES) throw TooLargeException(total)
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastShown = 0f
                            while (input.read(buffer).also { read = it } != -1) {
                                ensureActive()
                                output.write(buffer, 0, read)
                                done += read
                                // A server that announces no size, or a smaller one, is stopped at the limit.
                                if (done > MAX_BYTES) throw TooLargeException(done)
                                if (total > 0) {
                                    val progress = done.toFloat() / total
                                    if (progress - lastShown >= 0.02f) {
                                        lastShown = progress
                                        _states.update { it + (url to DownloadState.Running(progress)) }
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
                _states.update { it - url }
                onSaved(url, reportId, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                _states.update { it - url }
                throw e
            } catch (e: TooLargeException) {
                part.delete()
                _states.update { it + (url to DownloadState.TooLarge(e.bytes)) }
            } catch (e: Exception) {
                part.delete()
                _states.update { it + (url to DownloadState.Failed) }
            } finally {
                jobs.remove(url)
            }
        }
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
        if (file.exists()) file else {
            dao.deletePdf(url)
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
            File(pdf.path).delete()
            dao.deletePdf(pdf.url)
            onDeleted(pdf.url)
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
