// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The safeguards of the network clients (security audit of 15/11, review of PR 26), checked on
 * the clients as the app builds them. A new OkHttp (5.3.2 in the toolchain PR) or a change in
 * HttpClients must not drop one without a test failing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NetworkClientsTest {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val port = server.localPort

    /** The path of each request the local server received, in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** A local HTTP server: [answer] gives the whole response for a path. One request per connection. */
    private fun serve(answer: (String) -> String) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    asked += path
                    it.getOutputStream().apply { write(answer(path).toByteArray()); flush() }
                }
            }
        }
    }

    private fun redirect(location: String) =
        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private val ok = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"

    @After
    fun stop() = server.close()

    @Test
    fun theImagesClientKeepsItsSafeguards() {
        val images = HttpClients.images(HttpClients.base())
        // Redirects only through ImageHops, which checks each address before it is asked.
        assertFalse(images.followRedirects)
        assertFalse(images.followSslRedirects)
        assertTrue(images.interceptors.contains(ImageHops))
        // No proxy: a proxy would resolve the name itself, past PublicDns.
        assertEquals(Proxy.NO_PROXY, images.proxy)
        assertSame(PublicDns, images.dns)
        assertTrue(images.interceptors.contains(HttpClients.UserAgentStamp))
    }

    /**
     * The base client (API, feed, PDFs, Most read, GitHub) follows no redirect that changes the
     * scheme: OkHttp's rule is the same both ways, so http to https shows it without a TLS server.
     */
    @Test
    fun theBaseClientNeverFollowsARedirectToAnotherScheme() {
        assertFalse(HttpClients.base().followSslRedirects)
        serve { path ->
            when (path) {
                "/a" -> redirect("https://127.0.0.1:$port/b")
                "/c" -> redirect("/d")
                else -> ok
            }
        }
        val client = HttpClients.base()
        fun get(path: String) = client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute().use { it.code }

        assertEquals(302, get("/a"))
        assertEquals(listOf("/a"), asked.toList())

        // The same scheme is followed: the server's redirects do work.
        asked.clear()
        assertEquals(200, get("/c"))
        assertEquals(listOf("/c", "/d"), asked.toList())
    }
}
