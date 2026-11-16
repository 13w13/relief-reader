// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.relais.api.MostRead
import io.relais.api.ReliefWebClient
import io.relais.api.RssParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Issue 36: ReliefWeb refuses files to a User-Agent without "/". Every client of the app is built
 * as the app builds it, a made-up server is put at the end of its chain, and the header that
 * actually leaves is checked: one value, of the form ReliefWeb accepts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UserAgentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RelaisDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The User-Agent of each request that reached the made-up server, with its address. */
    private val sent: MutableList<Pair<String, String?>> = Collections.synchronizedList(ArrayList())

    /** [client] with a made-up server at the end of its chain. */
    private fun served(client: OkHttpClient): OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        sent += request.url.toString() to request.header("User-Agent")
        val (type, body) = when {
            request.url.encodedPath.endsWith(".pdf") -> "application/pdf" to "%PDF-1.7\n" + "x".repeat(40)
            request.url.host == "api.github.com" -> "application/json" to """{"tag_name":"v0.6.2","html_url":"https://github.com/13w13/relief-reader/releases/tag/v0.6.2"}"""
            request.url.toString() == RssParser.HEADLINES_URL -> "application/rss+xml" to """<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>"""
            else -> "application/json" to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody(type.toMediaType())).build()
    }.build()

    private fun assertSent(what: String) {
        assertTrue("$what: nothing sent", sent.isNotEmpty())
        sent.forEach { (url, agent) ->
            assertEquals("$what $url", HttpClients.USER_AGENT, agent)
            assertTrue("$what $url: $agent", HttpClients.USER_AGENT_FORM.matches(agent.orEmpty()))
        }
        sent.clear()
    }

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, RelaisDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun theUserAgentHasTheFormReliefWebAccepts() {
        assertEquals("Re.L/Android", HttpClients.USER_AGENT)
        assertTrue(HttpClients.USER_AGENT_FORM.matches(HttpClients.USER_AGENT))
        // What Product saw refused and accepted on /attachments/ (16/11).
        listOf("Re.L (Android)", "Re.L", "Re.L Android").forEach { assertFalse(it, HttpClients.USER_AGENT_FORM.matches(it)) }
        listOf("Re.L/0.6.0", "okhttp/4.12.0", "Re.L/Android").forEach { assertTrue(it, HttpClients.USER_AGENT_FORM.matches(it)) }
    }

    @Test
    fun pdfs() = runBlocking {
        // Even a caller that passed the old value: the base client puts the right one.
        val pdfs = PdfDownloader(context, db.dao(), served(HttpClients.base()), scope, "Re.L (Android)")
        val url = "https://reliefweb.int/attachments/b12721de-8c40-4acd-b050-9ce186eed02c/" +
            "2026%20Rowca%20regional%20funding%20status%20new%2016%2011%202026.pdf"
        assertTrue(pdfs.download(url, 1, 1_000_000) is Preloaded.Saved)
        assertSent("PDF")
    }

    @Test
    fun images() {
        val client = served(HttpClients.images(HttpClients.base()))
        client.newCall(Request.Builder().url("https://reliefweb.int/sites/default/files/images/headline.jpg").build()).execute().close()
        // A request that carries another User-Agent leaves with the app's.
        client.newCall(Request.Builder().url("https://images.example.org/map.png").header("User-Agent", "Re.L (Android)").build()).execute().close()
        assertSent("image")
    }

    @Test
    fun theApi() = runBlocking {
        val http = HttpClients.ktor(served(HttpClients.base()))
        runCatching { ReliefWebClient(http, appname = { "made-up-name" }).check() }
        http.close()
        assertSent("API")
    }

    @Test
    fun theFeed() = runBlocking {
        val http = HttpClients.ktor(served(HttpClients.base()))
        http.prepareGet(RssParser.HEADLINES_URL).execute { it.bodyAsText() }
        http.close()
        assertSent("RSS")
    }

    @Test
    fun theMostReadFile() {
        served(HttpClients.base()).newCall(mostReadRequest(HttpClients.USER_AGENT, null)).execute().close()
        served(HttpClients.base()).newCall(mostReadRequest("Re.L (Android)", "\"etag\"")).execute().close()
        assertSent("Most read (${MostRead.URL})")
    }

    @Test
    fun theCheckForNewVersions() = runBlocking {
        HttpClients.latestRelease(served(HttpClients.base()))
        assertSent("GitHub")
    }
}
