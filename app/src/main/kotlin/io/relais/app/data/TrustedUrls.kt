// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which addresses taken from ReliefWeb's data (API answers, RSS feed, a restored backup) the app
 * fetches or opens itself (security audit of 15/11). Links the reader opens in the browser go
 * through safeOpen and are not limited here.
 */
object TrustedUrls {
    /** reliefweb.int or one of its subdomains, by name (never an IP address). */
    fun isReliefWebHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "reliefweb.int" || h.endsWith(".reliefweb.int")
    }

    /**
     * Whether the app downloads a file from [url] (PdfDownloader), checked again at each
     * redirect: https on reliefweb.int or a subdomain, standard port, no user info. An IP
     * address, private, loopback or link-local, never matches.
     */
    fun isFileUrl(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            !isIpLiteral(url.host) && isReliefWebHost(url.host)

    fun isFileUrl(url: String?): Boolean = url?.trim()?.toHttpUrlOrNull()?.let { isFileUrl(it) } ?: false

    /**
     * The address of an image the app loads (Coil, the images of reports): https only. An http
     * address is moved to https; anything else (file:, content:, android.resource:, data:) and
     * an IP address that is private, loopback or link-local give null. Images may come from
     * other hosts than ReliefWeb: a report can include them.
     */
    fun image(url: String?): String? {
        val text = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = text.substringBefore(':', "").lowercase()
        val https = when (scheme) {
            "https" -> text
            "http" -> "https" + text.substring(scheme.length)
            else -> return null
        }
        val parsed = https.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (isIpLiteral(parsed.host) && !isPublicAddress(parsed.host)) return null
        return parsed.toString()
    }

    /**
     * A page of ReliefWeb the app opens or shares ("Open on ReliefWeb", Share): https on
     * reliefweb.int or a subdomain, else null.
     */
    fun reliefWebPage(url: String?): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (isIpLiteral(parsed.host) || !isReliefWebHost(parsed.host)) return null
        return parsed.toString()
    }

    /**
     * The page of a report on ReliefWeb: its alias when it is a ReliefWeb address, else the page
     * built from its id (ReliefWeb's report ids are its page ids, /node/<id> leads to the
     * report). Null without an id.
     */
    fun reportPage(urlAlias: String?, reportId: Long): String? =
        reliefWebPage(urlAlias) ?: reportId.takeIf { it > 0 }?.let { "https://reliefweb.int/node/$it" }

    /** An IPv4 or IPv6 address written as such (OkHttp keeps IPv6 without brackets). */
    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4.matches(host)

    /**
     * Whether an IP address written as such is a public one: not loopback (127/8, ::1), private
     * (10/8, 172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, fe80::/10), unspecified,
     * CGNAT (100.64/10) or multicast.
     */
    fun isPublicAddress(host: String): Boolean {
        if (IPV4.matches(host)) {
            val p = host.split('.').map { it.toInt() }
            if (p.any { it > 255 }) return false
            val (a, b) = p[0] to p[1]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127))
        }
        val h = host.lowercase()
        if (!h.contains(':')) return false
        if (h == "::" || h == "::1") return false
        // IPv4 mapped or compatible (::ffff:10.0.0.1): judged as the IPv4 address.
        val tail = h.substringAfterLast(':')
        if (IPV4.matches(tail)) return isPublicAddress(tail)
        val first = h.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
        return !(first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80 || first and 0xff00 == 0xff00 || first == 0)
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/**
 * The report's page on ReliefWeb, for "Open on ReliefWeb", Share and citations: its alias when it
 * is a ReliefWeb address (from the API, the feed or a restored backup), else built from its id.
 */
val ReportEntity.reliefWebPage: String? get() = TrustedUrls.reportPage(urlAlias, id)
