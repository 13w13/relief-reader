// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The PDFs on the phone: the cap of the cache folder (issue 6), the files Android clears (issue 5)
 * and the preload's downloads. No network: a made-up server answers from inside OkHttp.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PdfDownloaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RelaisDatabase
    private val dao get() = db.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deleted: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val cacheFolder get() = File(context.cacheDir, "pdf")
    private val keptFolder get() = File(context.filesDir, "pdf")

    /** What the made-up server sends back. */
    private var body: () -> ResponseBody = { pdfBytes(40).toResponseBody("application/pdf".toMediaType()) }

    private val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body())
            .build()
    }.build()

    private fun pdfBytes(size: Int) = "%PDF-1.7\n".toByteArray() + ByteArray(size)

    private fun downloader(cap: Long = 1_000_000, protectedReports: Set<Long> = emptySet()) = PdfDownloader(
        context, dao, okHttp, scope, "Re.L test",
        onDeleted = { deleted += it },
        capBytes = { cap },
        protectedReports = { protectedReports },
    )

    /** A PDF already on the phone, [bytes] long, downloaded or last opened at [at]. */
    private suspend fun onPhone(name: String, reportId: Long, bytes: Int, at: Long, dir: File = cacheFolder): PdfEntity {
        dir.mkdirs()
        val file = File(dir, "$name.pdf").apply { writeBytes(ByteArray(bytes)) }
        val pdf = PdfEntity("https://example.org/$name.pdf", reportId, file.absolutePath, bytes.toLong(), at)
        dao.upsertPdf(pdf)
        return pdf
    }

    private fun noPartFile() = cacheFolder.listFiles().orEmpty().none { it.name.endsWith(".part") }

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, RelaisDatabase::class.java).allowMainThreadQueries().build()
        cacheFolder.deleteRecursively()
        keptFolder.deleteRecursively()
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
        cacheFolder.deleteRecursively()
        keptFolder.deleteRecursively()
    }

    // --- Files cleared by Android (issue 5) ------------------------------------------------------

    @Test
    fun aFileClearedByAndroidLeavesTheDatabaseAndTheSearchIndex() = runBlocking {
        val pdf = onPhone("cleared", 1, 100, 1)
        File(pdf.path).delete()
        val pdfs = downloader()
        assertEquals(null, pdfs.fileFor(pdf.url))
        assertEquals(null, dao.pdf(pdf.url))
        assertEquals(listOf(pdf.url), deleted.toList())
    }

    @Test
    fun forgetMissingDropsTheRowsWithoutAFileButKeepsTheTextOfAHighlightedReport() = runBlocking {
        val here = onPhone("here", 1, 100, 1)
        val gone = onPhone("gone", 2, 100, 2)
        val highlighted = onPhone("highlighted", 3, 100, 3)
        File(gone.path).delete()
        File(highlighted.path).delete()
        assertEquals(2, downloader(protectedReports = setOf(3L)).forgetMissing())
        assertEquals(listOf(here.url), dao.pdfs().map { it.url })
        // The highlighted report's text stays: its highlights point into it.
        assertEquals(listOf(gone.url), deleted.toList())
    }

    @Test
    fun openingAPdfMakesItRecentForTheCap() = runBlocking {
        val pdf = onPhone("read", 1, 100, 1)
        val before = System.currentTimeMillis()
        assertEquals(pdf.path, downloader().fileFor(pdf.url)?.path)
        assertTrue(dao.pdf(pdf.url)!!.downloadedAt >= before)
    }

    // --- The cap (issue 6) -----------------------------------------------------------------------

    @Test
    fun theCapDeletesTheLeastRecentPdfsThatNoSavedOrHighlightedReportHolds() = runBlocking {
        val saved = onPhone("saved", 1, 100, 10)
        val old = onPhone("old", 2, 100, 20)
        val recent = onPhone("recent", 3, 100, 30)
        // A saved report's PDF in private storage counts for nothing.
        val kept = onPhone("kept", 4, 500, 5, dir = keptFolder)
        val pdfs = downloader(cap = 250, protectedReports = setOf(1L))
        assertEquals(300L, pdfs.cacheBytes())
        assertEquals(1, pdfs.enforceCap())
        assertFalse(File(old.path).exists())
        assertTrue(File(saved.path).exists())
        assertTrue(File(recent.path).exists())
        assertTrue(File(kept.path).exists())
        assertEquals(setOf(saved.url, recent.url, kept.url), dao.pdfs().map { it.url }.toSet())
        assertEquals(listOf(old.url), deleted.toList())
    }

    // --- The preload's downloads -----------------------------------------------------------------

    @Test
    fun thePreloadDownloadsAPdfToTheCacheFolder() = runBlocking {
        val result = downloader().download("https://example.org/new.pdf", 7, Preload.MAX_FILE_BYTES)
        assertTrue(result is Preloaded.Saved)
        val file = (result as Preloaded.Saved).file
        assertTrue(file.path.startsWith(cacheFolder.absolutePath))
        assertTrue(looksLikePdf(file))
        assertEquals(file.absolutePath, dao.pdf("https://example.org/new.pdf")?.path)
        assertTrue(noPartFile())
    }

    @Test
    fun aFileOverTheLimitIsFinalAndAWebPageIsAFailure() = runBlocking {
        val pdfs = downloader()
        // Announced over the limit.
        body = { pdfBytes(5_000).toResponseBody("application/pdf".toMediaType()) }
        assertEquals(Preloaded.TooLarge, pdfs.download("https://example.org/big.pdf", 8, maxBytes = 1_000))
        // No size announced: stopped at the limit while it comes in.
        body = { Buffer().write(pdfBytes(5_000)).asResponseBody("application/pdf".toMediaType(), -1) }
        assertEquals(Preloaded.TooLarge, pdfs.download("https://example.org/unsized.pdf", 9, maxBytes = 1_000))
        body = { "<!DOCTYPE html><html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()) }
        assertEquals(Preloaded.Failed, pdfs.download("https://example.org/page.pdf", 10, Preload.MAX_FILE_BYTES))
        assertTrue(dao.pdfs().isEmpty())
        assertTrue(noPartFile())
    }

    @Test
    fun aPreloadedPdfOverTheCapPushesOutTheOldestButNeverItself() = runBlocking {
        val old = onPhone("old", 1, 100, 1)
        body = { pdfBytes(190).toResponseBody("application/pdf".toMediaType()) }
        val result = downloader(cap = 250).download("https://example.org/fresh.pdf", 2, Preload.MAX_FILE_BYTES)
        assertTrue(result is Preloaded.Saved)
        assertFalse(File(old.path).exists())
        assertEquals(listOf("https://example.org/fresh.pdf"), dao.pdfs().map { it.url })
    }

    /** A server that sends a PDF slowly, 1 KB every 20 ms, without announcing its size. */
    private class SlowSource : Source {
        val started = CountDownLatch(1)
        val chunks = AtomicInteger()
        private var first = true

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (first) {
                first = false
                sink.writeUtf8("%PDF-1.7\n")
                started.countDown()
                return 9
            }
            Thread.sleep(20)
            chunks.incrementAndGet()
            sink.write(ByteArray(1024))
            return 1024
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {}
    }

    @Test
    fun aStoppedPreloadStopsItsDownloadAndLeavesNoFile() = runBlocking {
        val slow = SlowSource()
        body = { slow.buffer().asResponseBody("application/pdf".toMediaType(), -1) }
        val pdfs = downloader()
        // The worker's coroutine, as WorkManager runs it, then stops it (metered network, setting off).
        val worker = launch(Dispatchers.IO) { pdfs.download("https://example.org/slow.pdf", 1, Preload.MAX_FILE_BYTES) }
        assertTrue(slow.started.await(5, TimeUnit.SECONDS))
        delay(200)
        worker.cancelAndJoin()
        val chunksWhenStopped = slow.chunks.get()
        delay(300)
        // Nothing more comes in once the worker is stopped: no 25 MB on mobile data.
        assertEquals(chunksWhenStopped, slow.chunks.get())
        assertTrue(dao.pdfs().isEmpty())
        assertTrue(noPartFile())
    }
}
