// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiException
import kotlinx.coroutines.CancellationException

/**
 * The order of "Search organizations" (founder, 15/11: typing HI found nothing). Many
 * organizations go by a short acronym (HI, WV, CA, UN); the API's full-text order can bury it
 * behind longer names that contain the same letters. First the acronym equal to what was typed,
 * whatever the case; then the acronyms that start with it; then the names that start with it;
 * then the rest, in the API's order.
 */
object SourceRanking {
    /** How many organizations a search asks for, and how many it shows. */
    const val ASKED = 20
    const val SHOWN = 10

    fun rank(text: String, found: List<SourceInfo>): List<SourceInfo> {
        val typed = text.trim().lowercase()
        if (typed.isEmpty()) return found.take(SHOWN)
        fun tier(s: SourceInfo): Int {
            val short = s.shortname.trim().lowercase()
            return when {
                short == typed -> 0
                short.startsWith(typed) -> 1
                s.name.trim().lowercase().startsWith(typed) -> 2
                else -> 3
            }
        }
        // sortedBy is stable: the API's order stays within each tier.
        return found.sortedBy(::tier).take(SHOWN)
    }

    /**
     * Whether a failed first try earns the second one, without wildcards: only when the API
     * refused or garbled that request (HTTP 400, an answer it could not read). A 429, a 403, a
     * web page instead of data, a missing appname or no connection would fail the same way
     * twice, and a second request after a 429 only adds to the count.
     */
    fun retries(e: Throwable): Boolean =
        e is ApiException.BadRequest || (e is ApiException.Server && e.unreadable)

    /**
     * The two tries of a search. The second runs when the first finds nothing or fails in a way
     * [retries] accepts; any other error of the first is thrown as it is. When the first
     * answered with nothing and the second fails the same way, the result is "nothing found";
     * an error about access (429, 403, a web page, no appname, no connection) is shown.
     */
    suspend fun <T> twoTries(first: suspend () -> List<T>, second: suspend () -> List<T>): List<T> {
        val answered = try {
            first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (retries(e)) null else throw e
        }
        if (!answered.isNullOrEmpty()) return answered
        return try {
            second()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (answered != null && retries(e)) emptyList() else throw e
        }
    }
}
