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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Where PDFs come from (security audit of 15/11): reliefweb.int only, checked again at every
 * redirect, five at most. No network: a made-up server answers from inside OkHttp, and every
 * address asked is noted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PdfHostsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RelaisDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Redirects of the made-up server: from an address to the next one, with their status (302 by default). */
    private val redirects = mutableMapOf<String, String>()
    private val codes = mutableMapOf<String, Int>()

    /** Addresses the made-up server answers 404 for, as ReliefWeb's nginx does for a missing file. */
    private val missing = mutableSetOf<String>()

    /** Every request as it left the app (before OkHttp adds its own headers). */
    private val requests: MutableList<okhttp3.Request> = Collections.synchronizedList(ArrayList())

    private val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        asked += url
        requests += chain.request()
        val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        val next = redirects[url]
        when {
            next != null -> builder.code(codes[url] ?: 302).message("Redirect").header("Location", next).body("".toResponseBody()).build()
            url in missing -> builder.code(404).message("Not Found").body("404 Not Found".toResponseBody("text/plain".toMediaType())).build()
            else -> builder.code(200).message("OK").body(("%PDF-1.7\n" + "x".repeat(40)).toResponseBody("application/pdf".toMediaType())).build()
        }
    }.build()

    private fun downloader() = PdfDownloader(context, db.dao(), okHttp, scope, "Re.L (Android)")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, RelaisDatabase::class.java).allowMainThreadQueries().build()
        File(context.cacheDir, "pdf").deleteRecursively()
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun aFileFromReliefWebIsDownloaded() = runBlocking {
        val result = downloader().download("https://reliefweb.int/attachments/a1/sitrep.pdf", 1, 1_000_000)
        assertTrue(result.toString(), result is Preloaded.Saved)
        assertEquals(listOf("https://reliefweb.int/attachments/a1/sitrep.pdf"), asked)
    }

    /**
     * Issue 36: the address of the API is asked exactly as given, path and encoding included, in one
     * request with the app's User-Agent. ReliefWeb's nginx serves /attachments/ by UUID and
     * extension only, but the address must not change on the way.
     */
    @Test
    fun theAddressOfTheApiIsAskedExactly() = runBlocking {
        val issue = "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/" +
            "2026%20Rowca%20regional%20funding%20status%20new%2016%2011%202026.pdf"
        assertTrue(downloader().download(issue, 40, 1_000_000) is Preloaded.Saved)
        assertEquals(listOf(issue), asked)
        val request = requests.single()
        assertEquals("/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/2026%20Rowca%20regional%20funding%20status%20new%2016%2011%202026.pdf", request.url.encodedPath)
        assertEquals("GET", request.method)
        assertEquals("Re.L (Android)", request.header("User-Agent"))
        // Nothing else from the app: no Range, no Referer, no Accept.
        assertEquals(listOf("User-Agent"), request.headers.names().toList())

        // Other spellings an answer could carry: a raw space is sent as %20, a non-ASCII letter in
        // UTF-8, an encoded % stays encoded, capitals stay.
        listOf(
            "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/Sitrep No 4.PDF" to
                "/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/Sitrep%20No%204.PDF",
            "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/Évaluation rapide.pdf" to
                "/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/%C3%89valuation%20rapide.pdf",
            "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/a%2520b.pdf" to
                "/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/a%2520b.pdf",
        ).forEach { (url, path) ->
            requests.clear()
            assertTrue(url, downloader().download(url, 41, 1_000_000) is Preloaded.Saved)
            assertEquals(url, path, requests.single().url.encodedPath)
        }
    }

    /** Issue 36: a 404 shows as such, not as a bare "Download failed". */
    @Test
    fun aServerErrorShowsItsStatus() = runBlocking {
        val url = "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/missing.pdf"
        missing += url
        val pdfs = downloader()
        pdfs.start(url, 42)
        val state = withTimeout(5_000) { pdfs.states.first { it[url] is DownloadState.Failed } }
        assertEquals(DownloadState.Failed("HTTP 404"), state[url])
        assertEquals(Preloaded.Failed, downloader().download(url, 43, 1_000_000))
    }

    @Test
    fun eachFailureHasItsOwnWords() {
        assertEquals("HTTP 404", failureReason(HttpStatusException(404)))
        assertEquals("HTTP 503", failureReason(HttpStatusException(503)))
        assertEquals("no public address", failureReason(PublicDns.NoPublicAddress()))
        assertEquals("address not found", failureReason(java.net.UnknownHostException("reliefweb.int")))
        assertEquals("timed out", failureReason(java.net.SocketTimeoutException()))
        assertEquals("no connection", failureReason(java.net.ConnectException()))
        assertEquals("secure connection failed", failureReason(javax.net.ssl.SSLHandshakeException("handshake")))
        assertEquals("not a PDF", failureReason(NotPdfException()))
        assertEquals("too many redirects", failureReason(TooManyRedirectsException()))
    }

    /** Issue 36: after an update, the files the preload gave up on are tried again. */
    @Test
    fun anUpdateForgetsThePreloadFailures() {
        val dir = File(context.filesDir, "preload-test").apply { deleteRecursively(); mkdirs() }
        File(dir, "preload_failures.tsv").writeText("2\t1\thttps://reliefweb.int/attachments/x.pdf\n")
        File(dir, "preload_failures-15.tsv").writeText("2\t1\thttps://reliefweb.int/attachments/y.pdf\n")
        val current = PreloadFailures.file(dir, versionCode = 16)
        assertEquals("preload_failures-16.tsv", current.name)
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name })
        current.writeText("2\t1\thttps://reliefweb.int/attachments/z.pdf\n")
        assertEquals(current, PreloadFailures.file(dir, versionCode = 16))
        assertTrue(current.exists())
    }

    @Test
    fun aFileFromElsewhereIsNeverAsked() = runBlocking {
        listOf(
            "https://example.org/report.pdf",
            "http://reliefweb.int/attachments/a1/sitrep.pdf",
            "https://reliefweb.int.example.org/report.pdf",
            "https://127.0.0.1/report.pdf",
            "https://10.0.0.2/report.pdf",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/report.pdf",
            "https://reliefweb.int:8443/report.pdf",
            "file:///data/data/org.relreader.app/databases/relais.db",
            "not an address",
        ).forEach { url ->
            assertEquals(url, Preloaded.Refused, downloader().download(url, 2, 1_000_000))
        }
        assertTrue(asked.toString(), asked.isEmpty())
    }

    @Test
    fun aRedirectWithinReliefWebIsFollowed() = runBlocking {
        redirects["https://reliefweb.int/attachments/b2/report.pdf"] = "https://reliefweb.int/sites/default/files/resources/report.pdf"
        redirects["https://reliefweb.int/sites/default/files/resources/report.pdf"] = "/sites/default/files/resources/report-v2.pdf"
        val result = downloader().download("https://reliefweb.int/attachments/b2/report.pdf", 3, 1_000_000)
        assertTrue(result.toString(), result is Preloaded.Saved)
        assertEquals(
            listOf(
                "https://reliefweb.int/attachments/b2/report.pdf",
                "https://reliefweb.int/sites/default/files/resources/report.pdf",
                "https://reliefweb.int/sites/default/files/resources/report-v2.pdf",
            ),
            asked,
        )
    }

    @Test
    fun aRedirectElsewhereIsRefusedBeforeItIsAsked() = runBlocking {
        listOf("https://example.org/x.pdf", "http://reliefweb.int/x.pdf", "https://192.168.1.1/x.pdf").forEach { target ->
            asked.clear()
            redirects["https://reliefweb.int/attachments/c3/report.pdf"] = target
            assertEquals(target, Preloaded.Refused, downloader().download("https://reliefweb.int/attachments/c3/report.pdf", 4, 1_000_000))
            assertEquals(listOf("https://reliefweb.int/attachments/c3/report.pdf"), asked)
        }
    }

    @Test
    fun everyRedirectStatusIsCheckedTheSameWay() = runBlocking {
        listOf(301, 303, 307, 308).forEach { code ->
            asked.clear()
            redirects.clear()
            codes.clear()
            val from = "https://reliefweb.int/attachments/e$code/report.pdf"
            redirects[from] = "https://reliefweb.int/sites/default/files/e$code.pdf"
            codes[from] = code
            assertTrue("$code", downloader().download(from, 10L + code, 1_000_000) is Preloaded.Saved)
            assertEquals("$code", 2, asked.size)
            asked.clear()
            redirects[from] = "https://example.org/e$code.pdf"
            assertEquals("$code", Preloaded.Refused, downloader().download(from, 20L + code, 1_000_000))
            assertEquals("$code", listOf(from), asked)
        }
    }

    @Test
    fun aTrailingDotIsTheSameHostAndALookalikeIsNot() = runBlocking {
        // reliefweb.int. is reliefweb.int, written in full.
        assertTrue(downloader().download("https://reliefweb.int./attachments/f6/report.pdf", 30, 1_000_000) is Preloaded.Saved)
        asked.clear()
        // An internationalized look-alike (a Cyrillic i, an accented e) is another host.
        listOf("https://reliefweb.\u0456nt/attachments/f6/report.pdf", "https://r\u00e9liefweb.int/attachments/f6/report.pdf").forEach { url ->
            assertEquals(url, Preloaded.Refused, downloader().download(url, 31, 1_000_000))
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun moreThanFiveRedirectsFail() = runBlocking {
        (0..9).forEach { redirects["https://reliefweb.int/r/$it.pdf"] = "https://reliefweb.int/r/${it + 1}.pdf" }
        assertEquals(Preloaded.Failed, downloader().download("https://reliefweb.int/r/0.pdf", 5, 1_000_000))
        assertEquals(PdfDownloader.MAX_REDIRECTS + 1, asked.size)
    }

    @Test
    fun theReaderIsToldAndThePreloadSkipsIt() = runBlocking {
        val pdfs = downloader()
        pdfs.start("https://example.org/report.pdf", 6)
        val state = withTimeout(5_000) { pdfs.states.first { it["https://example.org/report.pdf"] == DownloadState.Refused } }
        assertEquals(DownloadState.Refused, state["https://example.org/report.pdf"])
        // The preload does not even consider it.
        val items = listOf(
            Preload.Item(6, "https://example.org/report.pdf", 1_000),
            Preload.Item(7, "https://reliefweb.int/attachments/d4/report.pdf", 1_000),
        )
        assertEquals(listOf(7L), Preload.candidates(items, emptySet(), emptyMap(), now = 1L).map { it.reportId })
        assertTrue(asked.isEmpty())
    }
}
