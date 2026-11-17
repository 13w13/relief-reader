// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A checked link waiting for the main screen (the first launch setup, the tour, Connect), with
 * when it came: it opens within [FRESH_FOR], not hours later (review of PR 47).
 */
data class PendingLink(val url: String, val at: Long) {
    fun isFresh(now: Long): Boolean = now - at in 0L until FRESH_FOR

    companion object {
        const val FRESH_FOR = 10 * 60 * 1000L
    }
}

/**
 * A ReliefWeb link given to Re.L by another app (founder, 17/11): shared as text, or opened from a
 * link. This is an entry other apps can reach, so it is strict: only a page of reliefweb.int
 * itself, https on the standard port, no user info, no subdomain or look-alike host, a short
 * path of plain characters; the query and the fragment are dropped. Anything else gives null.
 * Nothing else from the shared text is kept.
 */
object ReliefWebLinks {
    /** The part of a shared text read: a title and a link are a few hundred characters. */
    const val MAX_TEXT = 4096

    /** Longer than any ReliefWeb page address. */
    const val MAX_URL = 512

    /** What the link points to; [url] is the page, normalized (no query, no fragment, no "/" at the end). */
    sealed interface Link {
        val url: String

        /** A report or a map: /report/<country>/<title> or /map/<country>/<title>, found by its address. */
        data class Report(override val url: String) : Link

        /** /node/<id>: the page of a ReliefWeb node, a report when the API knows it as one. */
        data class Node(override val url: String, val id: Long) : Link

        /** /country/<iso3>: the country's page. */
        data class Country(override val url: String, val iso3: String) : Link

        /** Another page of reliefweb.int (a crisis, an organization, a search, the home page). */
        data class Other(override val url: String) : Link
    }

    private const val HOST = "reliefweb.int"

    /**
     * Letters, digits and the few marks of ReliefWeb's addresses; never a quote, a backslash or a
     * space. This rule is what keeps the API query safe: Repository.fetchReportForLink builds
     * `url_alias:"<address>"` by concatenation, so a quote let through here would let a link change
     * the query (review of PR 47). Do not loosen it; ReliefWebLinksTest holds it.
     */
    private val SAFE_PATH = Regex("^/[A-Za-z0-9._~%/-]*$")

    /** A segment of a report's address: ReliefWeb's slugs, so not even an encoded character. */
    private val SEGMENT = "[A-Za-z0-9._~-]+"
    private val REPORT = Regex("^/(report|map)/$SEGMENT/$SEGMENT$")
    private val NODE = Regex("^/node/(\\d{1,12})$")
    private val COUNTRY = Regex("^/country/([A-Za-z]{3})$")

    /** An address in a text, up to a space or a mark that never ends a ReliefWeb address. */
    private val URL_IN_TEXT = Regex("""(?i)\bhttps?://[^\s<>"'()\[\]{}]+""")

    /**
     * The first link to a page of reliefweb.int in a shared text ("Title https://…"), read in its
     * first [MAX_TEXT] characters. An http link, another site or a look-alike is skipped.
     */
    fun fromSharedText(text: CharSequence?): Link? {
        val head = text?.let { it.subSequence(0, minOf(it.length, MAX_TEXT)).toString() } ?: return null
        for (match in URL_IN_TEXT.findAll(head)) {
            fromUrl(match.value.trimEnd('.', ',', ';', ':', '!', '?'))?.let { return it }
        }
        return null
    }

    /** The link when [url] is a page of reliefweb.int in the strict form above; else null. */
    fun fromUrl(url: String?): Link? {
        val text = url?.trim()?.takeIf { it.length <= MAX_URL } ?: return null
        // The checks of the pages the app opens (TrustedUrls), then the exact host.
        val page = TrustedUrls.reliefWebPage(text)?.toHttpUrlOrNull() ?: return null
        if (page.host != HOST) return null
        val path = page.encodedPath.trimEnd('/').ifEmpty { "/" }
        if (!SAFE_PATH.matches(path) || "//" in path) return null
        val clean = "https://$HOST" + (if (path == "/") "" else path)
        NODE.matchEntire(path)?.let { m -> m.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { return Link.Node(clean, it) } }
        COUNTRY.matchEntire(path)?.let { m ->
            val iso3 = m.groupValues[1].lowercase()
            return Link.Country("https://$HOST/country/$iso3", iso3)
        }
        if (REPORT.matches(path)) return Link.Report(clean)
        return Link.Other(clean)
    }
}
