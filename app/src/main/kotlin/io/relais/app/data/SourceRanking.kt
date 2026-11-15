// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

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
}
