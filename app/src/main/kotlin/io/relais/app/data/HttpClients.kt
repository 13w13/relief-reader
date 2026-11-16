// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * The HTTP clients of the app, built in one place, with one User-Agent (issue 36).
 *
 * ReliefWeb answers 404 to a request for a file under /attachments/ when the User-Agent has no
 * "/" (Product, 16/11: "Re.L (Android)" refused, "Re.L/0.6.0" and "Re.L/Android" accepted). 0.6.1
 * sent "Re.L (Android)" and could not download PDFs. Every request now carries [USER_AGENT],
 * set by [UserAgentStamp] on the base client that every other client is built from, whatever its
 * caller wrote.
 */
object HttpClients {
    /** Product/version form, the same for every reader: no app version, nothing that tells one apart. */
    const val USER_AGENT = "Re.L/Android"

    /** The form ReliefWeb accepts, checked by the tests on every header the app sends. */
    val USER_AGENT_FORM = Regex("^[A-Za-z0-9.]+/[A-Za-z0-9.]+$")

    /** Puts [USER_AGENT] on every request, replacing any other. */
    object UserAgentStamp : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }

    /**
     * The base client: the User-Agent, its time limits, and no redirect from https to http
     * (security audit of 15/11). PDFs, the Most read file, the check for new versions and the
     * Ktor client of the API and the feed use it.
     */
    fun base(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient = builder
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addInterceptor(UserAgentStamp)
        .build()

    /**
     * For Coil (security review of PR 26): the base client, its User-Agent included; redirects
     * followed by [ImageHops], which checks each address before connecting; names resolved to
     * public addresses only; and no proxy, so that the address check holds.
     */
    fun images(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(PublicDns)
        .addInterceptor(ImageHops)
        .build()

    /** The API and the RSS feed: Ktor on the base client, so the same User-Agent. */
    fun ktor(base: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = base }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
        install(UserAgent) { agent = USER_AGENT }
        expectSuccess = false
    }

    /** The answer of GitHub's latest release, for the check for new versions; null unless 200. */
    suspend fun latestRelease(client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UpdateCheck.LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 200) response.peekBody(UpdateCheck.MAX_BYTES).string() else null
        }
    }
}
