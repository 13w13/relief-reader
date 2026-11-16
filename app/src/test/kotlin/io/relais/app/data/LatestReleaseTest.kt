// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * HttpClients.latestRelease as the app calls it (review of PR 44): the base client, a made-up
 * GitHub at the end of its chain. A quota refusal (403, 429) must come out as
 * RateLimitedException with the time from its headers, another status as HttpStatusException.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LatestReleaseTest {
    /** The base client answered by a made-up GitHub: [code], [headers] and [body]. */
    private fun github(code: Int, headers: Map<String, String> = emptyMap(), body: String = "{}"): OkHttpClient =
        HttpClients.base().newBuilder().addInterceptor { chain ->
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("x").body(body.toByteArray().toResponseBody())
            headers.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        }.build()

    private fun refusal(client: OkHttpClient): RateLimitedException = runBlocking {
        try {
            HttpClients.latestRelease(client)
            throw AssertionError("a refusal should throw")
        } catch (e: RateLimitedException) {
            e
        }
    }

    @Test
    fun aQuotaRefusalCarriesGitHubsTime() {
        val before = System.currentTimeMillis()
        val after403 = refusal(github(403, mapOf("retry-after" to "120")))
        assertEquals(403, after403.code)
        val until = requireNotNull(after403.until)
        assertTrue("$until", until in before + 120_000..System.currentTimeMillis() + 120_000)

        val reset = (System.currentTimeMillis() / 1000) + 1800
        val after429 = refusal(github(429, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString())))
        assertEquals(429, after429.code)
        assertEquals(reset * 1000, after429.until)
    }

    @Test
    fun aQuotaRefusalWithoutHeadersHasNoTime() {
        assertNull(refusal(github(403)).until)
        assertNull(refusal(github(429)).until)
    }

    @Test
    fun anotherStatusIsAnHttpStatus() = runBlocking {
        try {
            HttpClients.latestRelease(github(500))
            fail("a 500 should throw")
        } catch (e: HttpStatusException) {
            assertEquals(500, e.code)
            assertFalse(e is RateLimitedException)
        }
    }

    @Test
    fun aReleaseIsReturned() = runBlocking {
        assertEquals("""{"tag_name": "v0.7.1"}""", HttpClients.latestRelease(github(200, body = """{"tag_name": "v0.7.1"}""")))
    }
}
