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

    private val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        asked += url
        val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        val next = redirects[url]
        if (next != null) {
            builder.code(codes[url] ?: 302).message("Redirect").header("Location", next).body("".toResponseBody()).build()
        } else {
            builder.code(200).message("OK").body(("%PDF-1.7\n" + "x".repeat(40)).toResponseBody("application/pdf".toMediaType())).build()
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
