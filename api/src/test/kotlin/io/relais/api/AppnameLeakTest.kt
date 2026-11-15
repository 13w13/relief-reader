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
                    val total = written.addAndGet(chunk.size.toLong())
                    if (total % (8L * 1024 * 1024) == 0L) println("endless: ${total / (1024 * 1024)} MB written")
                }
            }.onFailure { println("endless: stopped after ${written.get()} bytes (${it::class.simpleName})") }
        }.channel

    /** Bodies alone, on a streamed answer: the read stops at the limit (review of PR 26). */
    @Test
    fun bodiesStopAnEndlessStreamAtTheLimit() = kotlinx.coroutines.runBlocking {
        val written = java.util.concurrent.atomic.AtomicLong()
        val server = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val http = HttpClient(MockEngine { respond(endless(server, written), HttpStatusCode.OK) })
        try {
            val limit = 1L * 1024 * 1024
            val e = kotlinx.coroutines.withTimeout(30_000) {
                failure {
                    http.prepareGet("https://relais.test/endless").execute { response ->
                        println("bodies: response ${response.status}, reading")
                        Bodies.text(response, limit)
                    }
                }
            }
            println("bodies: ended with ${e::class.simpleName} after ${written.get()} bytes written")
            assertTrue("${e::class.simpleName}: ${e.message}", e is Bodies.TooLarge)
            assertTrue("written ${written.get()}", written.get() < limit + 4L * 1024 * 1024)
        } finally {
            http.close()
            server.cancel()
        }
    }

    /**
     * The client on an endless answer: it reads as the body streams and stops at its limit
     * (review of PR 26). A client that held the whole body in memory first would never finish.
     */
    @Test
    fun anEndlessAnswerIsStoppedWhileItStreams() = kotlinx.coroutines.runBlocking {
        val written = java.util.concurrent.atomic.AtomicLong()
        val server = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val engine = MockEngine { respond(endless(server, written), HttpStatusCode.OK) }
        try {
            val e = kotlinx.coroutines.withTimeout(60_000) { failure { client(engine).reports(Requests.headlines()) } }
            println("client: ended with ${e::class.simpleName} after ${written.get()} bytes written")
            assertTrue("${e::class.simpleName}: ${e.message}", e is ApiException.Server)
            assertNoAppname(e)
            assertTrue("written ${written.get()}", written.get() < ReliefWebClient.MAX_ANSWER_BYTES + 4L * 1024 * 1024)
        } finally {
            server.cancel()
        }
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
