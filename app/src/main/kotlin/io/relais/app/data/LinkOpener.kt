// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiException
import kotlinx.coroutines.CancellationException

/** Where a ReliefWeb link leads in Re.L, or why it does not. */
sealed interface LinkTarget {
    data class Report(val id: Long) : LinkTarget

    /** Not on the phone, and the API needs an appname (headlines only). */
    data object NeedsAppname : LinkTarget

    /** Not on the phone, and no network. */
    data object Offline : LinkTarget

    /** ReliefWeb answered: no report at this address. */
    data object NotFound : LinkTarget

    /** Several links in a short time: no request for a while (review of PR 47). */
    data object TryLater : LinkTarget

    /**
     * A page of reliefweb.int that is not a report (a country, a crisis, an organization): the
     * browser opens it. A link from outside opens no other screen of Re.L, whose screens fetch
     * and mark what they show (review of PR 47).
     */
    data object NotAReport : LinkTarget

    data class Failed(val reason: String) : LinkTarget
}

/**
 * From a ReliefWeb link to a screen of Re.L (founder, 17/11). A report is looked for on the phone
 * first, by its address or its node id: saved reports, the cache, and the headlines of the feed
 * (which carry their address), so headlines only and offline work for those. Then, with an
 * appname and a network, one API request for this one address, as the reader asked; nothing
 * else is fetched. Any other page, a country's included, goes to the browser: Re.L does not
 * guess, and opens no other screen for a link from outside.
 *
 * Another app can send links without the reader doing anything (review of PR 47): the requests
 * they cost, with the reader's appname, are bounded. At most [BUDGET] in [WINDOW]; beyond, "Try
 * again later" and no request. An address ReliefWeb has no report at is not asked again for
 * [NOT_FOUND_FOR].
 *
 * @param onPhone the report already on the phone for this link, or null.
 * @param fetch asks the API for this link's report, saves it and returns its id; null when
 *   ReliefWeb has none. May throw.
 */
class LinkOpener(
    private val onPhone: suspend (ReliefWebLinks.Link) -> Long?,
    private val hasAccess: () -> Boolean,
    private val online: () -> Boolean,
    private val fetch: suspend (ReliefWebLinks.Link) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** When the last requests were sent, oldest first. */
    private val asked = ArrayDeque<Long>()

    /** Addresses ReliefWeb had no report at, and when it said so. */
    private val notFound = HashMap<String, Long>()

    /** Whether one more request may go now; if so, it is counted. */
    private fun mayAsk(at: Long): Boolean = synchronized(asked) {
        while (asked.isNotEmpty() && at - asked.first() !in 0L until WINDOW) asked.removeFirst()
        if (asked.size >= BUDGET) return false
        asked.addLast(at)
        true
    }

    suspend fun open(link: ReliefWebLinks.Link): LinkTarget = when (link) {
        is ReliefWebLinks.Link.Report, is ReliefWebLinks.Link.Node -> report(link)
        is ReliefWebLinks.Link.Other -> LinkTarget.NotAReport
    }

    private suspend fun report(link: ReliefWebLinks.Link): LinkTarget {
        onPhone(link)?.let { return LinkTarget.Report(it) }
        if (!hasAccess()) return LinkTarget.NeedsAppname
        if (!online()) return LinkTarget.Offline
        val at = now()
        synchronized(notFound) { notFound[link.url] }?.let { if (at - it in 0L until NOT_FOUND_FOR) return LinkTarget.NotFound }
        if (!mayAsk(at)) return LinkTarget.TryLater
        return try {
            fetch(link)?.let { LinkTarget.Report(it) } ?: LinkTarget.NotFound.also {
                synchronized(notFound) {
                    notFound.entries.removeAll { at - it.value !in 0L until NOT_FOUND_FOR }
                    notFound[link.url] = at
                }
            }
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

    companion object {
        const val BUDGET = 5
        const val WINDOW = 10 * 60 * 1000L
        const val NOT_FOUND_FOR = 10 * 60 * 1000L
    }
}
