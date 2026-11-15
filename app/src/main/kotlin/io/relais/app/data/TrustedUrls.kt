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
     * The address of an image the app loads (Coil, the images of reports): https on port 443, on
     * a named host. An http address is moved to https; anything else (file:, content:,
     * android.resource:, data:), an IP address and localhost give null. Images may come from other
     * hosts than ReliefWeb: an image pasted in a report keeps its original host (Product, 15/11).
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
        if (parsed.port != 443) return null
        val host = parsed.host
        // A name, never an IP address, public or not (Product, 15/11: real images are on named
        // hosts). Nor 127.1, 2130706433 or 0x7f.0.0.1, which a resolver may read as an address
        // (review of PR 26).
        if (isIpLiteral(host) || looksNumeric(host)) return null
        if (isLocalName(host)) return null
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

    /** localhost and its subdomains, which always lead to the phone itself. */
    fun isLocalName(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "localhost" || h.endsWith(".localhost")
    }

    /**
     * A name that a resolver may read as an IPv4 address: its last label is all digits, or starts
     * with 0x.
     */
    fun looksNumeric(host: String): Boolean {
        val last = host.trimEnd('.').substringAfterLast('.')
        return last.isNotEmpty() && (last.all { it in '0'..'9' } || last.startsWith("0x", ignoreCase = true))
    }

    /** An IPv4 or IPv6 address written as such (OkHttp keeps IPv6 without brackets). */
    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4.matches(host)

    /**
     * Whether an IP address written as such is a public one, judged on its bytes by
     * [PublicDns.isPublic] (IPv4 inside IPv6 included). A name, or a malformed address, is not.
     * Never a DNS lookup: only literals are parsed.
     */
    fun isPublicAddress(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        val address = when {
            IPV4.matches(h) -> {
                val parts = h.split('.').map { it.toInt() }
                if (parts.any { it > 255 }) return false
                java.net.InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
            }
            h.contains(':') -> runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false
            else -> return false
        }
        return PublicDns.isPublic(address)
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/**
 * The report's page on ReliefWeb, for "Open on ReliefWeb", Share and citations: its alias when it
 * is a ReliefWeb address (from the API, the feed or a restored backup), else built from its id.
 */
val ReportEntity.reliefWebPage: String? get() = TrustedUrls.reportPage(urlAlias, id)
