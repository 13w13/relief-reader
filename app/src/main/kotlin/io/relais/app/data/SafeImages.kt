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
 * The redirects of the image client, followed here one by one (the client follows none): each
 * address, the first and every Location, is checked by [allowed] before any connection is made
 * (security review of PR 26). Five redirects at most.
 */
object ImageHops : okhttp3.Interceptor {
    const val MAX_REDIRECTS = 5

    /** https on port 443, and what [TrustedUrls.image] accepts. */
    fun allowed(url: okhttp3.HttpUrl): Boolean = url.isHttps && url.port == 443 && TrustedUrls.image(url.toString()) != null

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        var request = chain.request()
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(request.url)) throw java.io.IOException("Image address refused")
            val response = chain.proceed(request)
            if (!response.isRedirect) return response
            val next = response.header("Location")?.let { request.url.resolve(it) } ?: return response
            response.close()
            request = request.newBuilder().url(next).build()
        }
        throw java.io.IOException("Too many redirects")
    }
}

/**
 * A name resolves to public addresses only, for the image and PDF clients (security review of
 * PR 26): a host whose name points at the phone, the local network or a link-local address is
 * not reached, whatever its name says. Judged on the address's bytes, IPv4 inside IPv6 included.
 */
object PublicDns : okhttp3.Dns {
    fun isPublic(address: java.net.InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 0xff }
        return when (b.size) {
            4 -> isPublicV4(b)
            16 -> isPublicV6(b)
            else -> false
        }
    }

    /**
     * Not 0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16, 100.64/10 (CGNAT), 192.0.0/24,
     * 198.18/15 (benchmarks), nor multicast and above (224/3).
     */
    private fun isPublicV4(b: List<Int>): Boolean {
        val (a, c) = b[0] to b[1]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
            (a == 100 && c in 64..127) || (a == 192 && c == 0 && b[2] == 0) || (a == 198 && c in 18..19))
    }

    /**
     * Not ::/96 (unspecified, loopback, IPv4-compatible), nor fc00::/7, fe80::/10, fec0::/10,
     * multicast, Teredo (2001::/32), documentation (2001:db8::/32), local NAT64 (64:ff9b:1::/48).
     * An IPv4 address carried inside (::ffff:0:0/96, NAT64 64:ff9b::/96, 6to4 2002::/16) is
     * judged as IPv4.
     */
    private fun isPublicV6(b: List<Int>): Boolean {
        fun v4(from: Int) = isPublicV4(b.subList(from, from + 4))
        val nat64 = b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b
        return when {
            b.subList(0, 12).all { it == 0 } -> false
            b.subList(0, 10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff -> v4(12)
            nat64 && b.subList(4, 12).all { it == 0 } -> v4(12)
            nat64 && b[4] == 0x00 && b[5] == 0x01 -> false
            b[0] == 0x20 && b[1] == 0x02 -> v4(2)
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00 -> false
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8 -> false
            (b[0] and 0xfe) == 0xfc -> false
            b[0] == 0xfe && (b[1] and 0xc0) == 0x80 -> false
            b[0] == 0xfe && (b[1] and 0xc0) == 0xc0 -> false
            b[0] == 0xff -> false
            else -> true
        }
    }

    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val public = okhttp3.Dns.SYSTEM.lookup(hostname).filter { isPublic(it) }
        if (public.isEmpty()) throw java.net.UnknownHostException("No public address for this host")
        return public
    }
}
