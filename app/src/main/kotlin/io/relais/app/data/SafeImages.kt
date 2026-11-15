// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.NullRequestData

/**
 * The one check every image goes through before Coil loads it (security audit of 15/11): the
 * photos of headlines, the images of reports and the previews of files. https only, http moved
 * to https ([TrustedUrls.image]); file:, content:, android.resource:, data:, a private address,
 * or anything that is not an address, is refused.
 */
object SafeImages : Interceptor {
    /** The address Coil may load for [data], or null. */
    fun allowed(data: Any?): String? = when (data) {
        is String -> TrustedUrls.image(data)
        is coil3.Uri, is android.net.Uri, is okhttp3.HttpUrl -> TrustedUrls.image(data.toString())
        else -> null
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        // No image asked for: Coil gives its own empty result.
        if (data == NullRequestData) return chain.proceed()
        val url = allowed(data)
            ?: return ErrorResult(image = null, request = chain.request, throwable = IllegalArgumentException("Image address refused"))
        return chain.withRequest(chain.request.newBuilder().data(url).build()).proceed()
    }
}

/**
 * Every request of the image client, redirects included, checked as the first address was
 * ([TrustedUrls.image]): a redirect to http, to localhost or to a private address fails there.
 * A network interceptor, so it sees each hop OkHttp follows.
 */
object ImageHops : okhttp3.Interceptor {
    fun allowed(url: okhttp3.HttpUrl): Boolean = url.isHttps && TrustedUrls.image(url.toString()) != null

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        if (!allowed(chain.request().url)) throw java.io.IOException("Image address refused")
        return chain.proceed(chain.request())
    }
}

/**
 * A name resolves to public addresses only, for the image and PDF clients (security review of
 * PR 26): a host whose name points at the phone, the local network or a link-local address is
 * not reached, whatever its name says. Addresses written as such are checked by [TrustedUrls].
 */
object PublicDns : okhttp3.Dns {
    fun isPublic(address: java.net.InetAddress): Boolean =
        !(address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) &&
            TrustedUrls.isPublicAddress(address.hostAddress.orEmpty().substringBefore('%'))

    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val public = okhttp3.Dns.SYSTEM.lookup(hostname).filter { isPublic(it) }
        if (public.isEmpty()) throw java.net.UnknownHostException("No public address for this host")
        return public
    }
}
