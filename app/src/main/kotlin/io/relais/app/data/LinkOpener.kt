// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiException
import kotlinx.coroutines.CancellationException

/** Where a ReliefWeb link leads in Re.L, or why it does not. */
sealed interface LinkTarget {
    data class Report(val id: Long) : LinkTarget
    data class Country(val id: Long, val name: String) : LinkTarget

    /** Not on the phone, and the API needs an appname (headlines only). */
    data object NeedsAppname : LinkTarget

    /** Not on the phone, and no network. */
    data object Offline : LinkTarget

    /** ReliefWeb answered: no report at this address. */
    data object NotFound : LinkTarget

    /** A page of reliefweb.int that Re.L has no screen for: the browser opens it. */
    data object NotAReport : LinkTarget

    data class Failed(val reason: String) : LinkTarget
}

/**
 * From a ReliefWeb link to a screen of Re.L (founder, 17/11). A report is looked for on the phone
 * first, by its address or its node id: saved reports, the cache, and the headlines of the feed
 * (which carry their address), so headlines only and offline work for those. Then, with an
 * appname and a network, one API request for this one address, as the reader asked; nothing
 * else is fetched. A country page opens from the countries on the phone. Anything else goes to
 * the browser: Re.L does not guess.
 *
 * @param onPhone the report already on the phone for this link, or null.
 * @param countryByIso3 the country of this ISO code, from the phone.
 * @param fetch asks the API for this link's report, saves it and returns its id; null when
 *   ReliefWeb has none. May throw.
 */
class LinkOpener(
    private val onPhone: suspend (ReliefWebLinks.Link) -> Long?,
    private val countryByIso3: suspend (String) -> CountryEntity?,
    private val hasAccess: () -> Boolean,
    private val online: () -> Boolean,
    private val fetch: suspend (ReliefWebLinks.Link) -> Long?,
) {
    suspend fun open(link: ReliefWebLinks.Link): LinkTarget = when (link) {
        is ReliefWebLinks.Link.Report, is ReliefWebLinks.Link.Node -> report(link)
        is ReliefWebLinks.Link.Country ->
            countryByIso3(link.iso3)?.let { LinkTarget.Country(it.id, it.displayName) } ?: LinkTarget.NotAReport
        is ReliefWebLinks.Link.Other -> LinkTarget.NotAReport
    }

    private suspend fun report(link: ReliefWebLinks.Link): LinkTarget {
        onPhone(link)?.let { return LinkTarget.Report(it) }
        if (!hasAccess()) return LinkTarget.NeedsAppname
        if (!online()) return LinkTarget.Offline
        return try {
            fetch(link)?.let { LinkTarget.Report(it) } ?: LinkTarget.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.NoAppname) {
            LinkTarget.NeedsAppname
        } catch (e: ApiException.Network) {
            LinkTarget.Offline
        } catch (e: ApiException) {
            LinkTarget.Failed(e.status?.let { "HTTP $it" } ?: "ReliefWeb did not answer")
        } catch (e: Exception) {
            LinkTarget.Failed("unexpected error")
        }
    }
}
