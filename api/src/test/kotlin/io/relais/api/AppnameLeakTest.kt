// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * No exception and no error text from the client holds the appname, and answers are read with a
 * ceiling (security audit of 15/11). The appname here is made up.
 */
class AppnameLeakTest {
    private val name = "noravia-reader-7f3k"

    private fun client(engine: MockEngine) = ReliefWebClient(HttpClient(engine), appname = { name })

    /** The message and every cause of [e], none of which may hold the appname. */
    private fun assertNoAppname(e: Throwable) {
        var t: Throwable? = e
        while (t != null) {
            assertFalse("${t::class.simpleName}: ${t.message}", t.message.orEmpty().contains(name, ignoreCase = true))
            assertFalse(t.stackTraceToString().contains(name, ignoreCase = true))
            t = t.cause
        }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("expected an exception")
        error("unreachable")
    }

    @Test
    fun aNetworkErrorKeepsOnlyTheClassOfItsCause() = runTest {
        val url = "https://api.reliefweb.int/v2/reports?appname=$name"
        listOf(IOException("Failed to connect to $url"), HttpRequestTimeoutException(url, 20_000)).forEach { cause ->
            val e = failure { client(MockEngine { throw cause }).reports(Requests.headlines()) }
            assertTrue(e is ApiException.Network)
            assertNull(e.cause)
            assertEquals("Network error: ${cause::class.simpleName}", e.message)
            assertNoAppname(e)
        }
    }

    @Test
    fun anErrorMessageThatRepeatsTheAppnameLosesIt() = runTest {
        fun answering(status: HttpStatusCode, message: String) =
            client(MockEngine { respond("""{"status":${status.value},"error":{"message":"$message"}}""", status) })
        val bad = failure { answering(HttpStatusCode.BadRequest, "Unknown field for appname $name").reports(Requests.headlines()) }
        assertTrue(bad is ApiException.BadRequest)
        assertEquals("Unknown field for appname [appname]", bad.message)
        assertNoAppname(bad)
        val denied = failure { answering(HttpStatusCode.Forbidden, "${name.uppercase()} is not approved").reports(Requests.headlines()) }
        assertTrue(denied is ApiException.AccessDenied)
        assertNoAppname(denied)
        val server = failure { answering(HttpStatusCode.InternalServerError, "Failed for $name").reports(Requests.headlines()) }
        assertTrue(server is ApiException.Server)
        assertNoAppname(server)
    }

    @Test
    fun redactLeavesOtherTextAlone() {
        assertEquals("Invalid query", ReliefWebClient.redact("Invalid query", name))
        assertEquals("a [appname] b [appname]", ReliefWebClient.redact("a $name b ${name.uppercase()}", name))
        assertEquals("text", ReliefWebClient.redact("text", null))
        assertEquals("text", ReliefWebClient.redact("text", "  "))
    }

    @Test
    fun anAnswerOverTheLimitIsNotParsed() = runTest {
        // One byte over the limit, with no announced length: the client counts and stops.
        val tooMuch = ByteArray((ReliefWebClient.MAX_ANSWER_BYTES + 1).toInt()) { 'x'.code.toByte() }
        val engine = MockEngine { respond(ByteReadChannel(tooMuch), HttpStatusCode.OK) }
        val e = failure { client(engine).reports(Requests.headlines()) }
        assertTrue("${e::class.simpleName}: ${e.message}", e is ApiException.Server)
        assertNoAppname(e)
    }

    /** A body that never ends, written 64 KB at a time; [written] counts what went out. */
    private fun endless(scope: kotlinx.coroutines.CoroutineScope, written: java.util.concurrent.atomic.AtomicLong): io.ktor.utils.io.ByteReadChannel =
        scope.writer {
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            // Until the reader stops and the channel closes.
            runCatching {
                while (true) {
                    channel.writeFully(chunk)
                    channel.flush()
                    written.addAndGet(chunk.size.toLong())
                }
            }
        }.channel

    /**
     * Runs [block] on an endless body and checks that it ends with [expected] within seconds, the
     * server stopped shortly after [limit]. Without the ceiling, the read would never end: these
     * tests fail on their timeout if the limit is removed (review of PR 26).
     */
    private fun stopsAtTheLimit(limit: Long, expected: Class<*>, block: suspend (MockEngine) -> Unit) = kotlinx.coroutines.runBlocking {
        val written = java.util.concurrent.atomic.AtomicLong()
        val server = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val engine = MockEngine { respond(endless(server, written), HttpStatusCode.OK) }
        try {
            val start = System.nanoTime()
            val e = kotlinx.coroutines.withTimeout(30_000) { failure { block(engine) } }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("${e::class.simpleName}: ${e.message}", expected.isInstance(e))
            assertNoAppname(e)
            assertTrue("ended after $ms ms", ms < 15_000)
            assertTrue("written ${written.get()}", written.get() < limit + 8L * 1024 * 1024)
        } finally {
            server.cancel()
        }
    }

    /** Bodies alone, on a streamed answer: the read stops at the limit, at once (review of PR 26). */
    @Test
    fun bodiesStopAnEndlessStreamAtTheLimit() {
        repeat(5) {
            stopsAtTheLimit(1L * 1024 * 1024, Bodies.TooLarge::class.java) { engine ->
                HttpClient(engine).use { http -> http.prepareGet("https://relais.test/endless").execute { Bodies.text(it, 1L * 1024 * 1024) } }
            }
        }
    }

    /** The client, through a GET (one report) and a POST (a list): it stops at its limit, at once. */
    @Test
    fun anEndlessAnswerIsStoppedWhileItStreams() {
        stopsAtTheLimit(ReliefWebClient.MAX_ANSWER_BYTES, ApiException.Server::class.java) { engine -> client(engine).report(4_100_001) }
        stopsAtTheLimit(ReliefWebClient.MAX_ANSWER_BYTES, ApiException.Server::class.java) { engine -> client(engine).reports(Requests.headlines()) }
    }

    @Test
    fun anAnnouncedLengthOverTheLimitIsRefused() = runTest {
        val http = HttpClient(MockEngine { respond("x".repeat(100), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "100")) })
        try {
            Bodies.text(http.get("https://relais.test/feed"), maxBytes = 10)
            fail("expected TooLarge")
        } catch (e: Bodies.TooLarge) {
            assertEquals(10L, e.limit)
        } finally {
            http.close()
        }
    }

    @Test
    fun aBodyWithoutLengthIsStoppedAtTheLimit() = runTest {
        val big = HttpClient(MockEngine { respond(ByteReadChannel("x".repeat(100)), HttpStatusCode.OK) })
        try {
            Bodies.text(big.get("https://relais.test/feed"), maxBytes = 10)
            fail("expected TooLarge")
        } catch (e: Bodies.TooLarge) {
            assertEquals(10L, e.limit)
        } finally {
            big.close()
        }
        val small = HttpClient(MockEngine { respond(ByteReadChannel("hello"), HttpStatusCode.OK) })
        assertEquals("hello", Bodies.text(small.get("https://relais.test/feed"), maxBytes = 10))
        small.close()
    }
}

/** Images in a report's text: https only, http moved to https (security audit of 15/11). */
class HtmlImagesTest {
    private fun images(html: String) = HtmlBlocks.parse(html).filterIsInstance<Block.Image>().map { it.url }

    @Test
    fun imagesAreAskedOverHttps() {
        assertEquals(listOf("https://reliefweb.int/a.png"), images("""<p><img src="https://reliefweb.int/a.png" alt="A"></p>"""))
        assertEquals(listOf("https://images.example.org/b.png"), images("""<p><img src="http://images.example.org/b.png"></p>"""))
        // Relative to ReliefWeb, then https.
        assertEquals(listOf("https://reliefweb.int/sites/c.png"), images("""<p><img src="/sites/c.png"></p>"""))
        assertTrue(images("""<p><img src="data:image/png;base64,iVBORw0KGgo="><img src="file:///etc/hosts"><img src="javascript:alert(1)"></p>""").isEmpty())
    }
}
