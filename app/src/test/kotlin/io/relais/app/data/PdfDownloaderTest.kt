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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

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

    /** What the made-up server sends back: a body and its content type. */
    private var body: ByteArray = "%PDF-1.7\nmade-up test file\n%%EOF".toByteArray()
    private var contentType = "application/pdf"

    private val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }.build()

    private fun downloader(cap: Long = 1_000_000, protectedReports: Set<Long> = emptySet()) = PdfDownloader(
        context, dao, okHttp, scope, "Re.L test",
        onDeleted = { deleted += it },
        capBytes = { cap },
        protectedReports = { protectedReports },
    )

    /** A PDF already on the phone, [bytes] long, downloaded at [at]. */
    private suspend fun onPhone(name: String, reportId: Long, bytes: Int, at: Long, dir: File = cacheFolder): PdfEntity {
        dir.mkdirs()
        val file = File(dir, "$name.pdf").apply { writeBytes(ByteArray(bytes)) }
        val pdf = PdfEntity("https://example.org/$name.pdf", reportId, file.absolutePath, bytes.toLong(), at)
        dao.upsertPdf(pdf)
        return pdf
    }

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

    @Test
    fun aFileClearedByAndroidLeavesTheDatabaseAndTheSearchIndex() = runBlocking {
        val pdf = onPhone("cleared", 1, 100, 1)
        File(pdf.path).delete()
        val pdfs = downloader()
        assertNull(pdfs.fileFor(pdf.url))
        assertNull(dao.pdf(pdf.url))
        assertEquals(listOf(pdf.url), deleted.toList())
    }

    @Test
    fun forgetMissingDropsOnlyTheRowsWithoutAFile() = runBlocking {
        val here = onPhone("here", 1, 100, 1)
        val gone = onPhone("gone", 2, 100, 2)
        File(gone.path).delete()
        assertEquals(1, downloader().forgetMissing())
        assertEquals(listOf(here.url), dao.pdfs().map { it.url })
        assertEquals(listOf(gone.url), deleted.toList())
    }

    @Test
    fun theCapDeletesTheOldestPdfsThatNoSavedOrHighlightedReportHolds() = runBlocking {
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

    @Test
    fun thePreloadDownloadsAPdfToTheCacheFolder() = runBlocking {
        val file = downloader().download("https://example.org/new.pdf", 7, Preload.MAX_FILE_BYTES)
        assertNotNull(file)
        assertTrue(file!!.path.startsWith(cacheFolder.absolutePath))
        assertTrue(looksLikePdf(file))
        assertEquals(file.absolutePath, dao.pdf("https://example.org/new.pdf")?.path)
        assertTrue(cacheFolder.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun thePreloadLeavesAFileOverItsLimitAndAWebPage() = runBlocking {
        val pdfs = downloader()
        body = "%PDF-1.7\n".toByteArray() + ByteArray(5_000)
        assertNull(pdfs.download("https://example.org/big.pdf", 8, maxBytes = 1_000))
        body = "<!DOCTYPE html><html><title>Just a moment...</title></html>".toByteArray()
        contentType = "text/html"
        assertNull(pdfs.download("https://example.org/page.pdf", 9, Preload.MAX_FILE_BYTES))
        assertTrue(dao.pdfs().isEmpty())
        assertTrue(cacheFolder.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun aPreloadedPdfOverTheCapPushesOutTheOldestButNeverItself() = runBlocking {
        val old = onPhone("old", 1, 100, 1)
        body = "%PDF-1.7\n".toByteArray() + ByteArray(190)
        val file = downloader(cap = 250).download("https://example.org/fresh.pdf", 2, Preload.MAX_FILE_BYTES)
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertFalse(File(old.path).exists())
        assertEquals(listOf("https://example.org/fresh.pdf"), dao.pdfs().map { it.url })
    }
}
