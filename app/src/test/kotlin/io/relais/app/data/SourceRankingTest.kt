// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Search organizations" puts a short acronym first (founder, 15/11). Made-up organizations. */
class SourceRankingTest {
    private fun source(id: Long, short: String, name: String) = SourceInfo(id, short, name)

    // As a full-text search could return them for "hi": names that contain the letters first.
    private val found = listOf(
        source(1, "NHIA", "Noravia Health Insurance Agency"),
        source(2, "OHI", "Ostland Hygiene Initiative"),
        source(3, "KHN", "Kestrova Highland Network"),
        source(4, "HICA", "Highland Coordination Alliance"),
        source(5, "WNH", "Water and Hygiene Noravia"),
        source(6, "hi", "Noravia Inclusion Federation"),
        source(7, "TPH", "Hills People Trust"),
    )

    @Test
    fun theExactAcronymComesFirstThenAcronymsThenNamesThenTheRest() {
        assertEquals(listOf(6L, 4L, 7L, 1L, 2L, 3L, 5L), SourceRanking.rank("HI", found).map { it.id })
        // Spaces and case do not matter.
        assertEquals(6L, SourceRanking.rank(" Hi ", found).first().id)
    }

    @Test
    fun aSearchShowsTenOfTheTwentyAsked() {
        val many = (1L..20L).map { source(it, "S$it", "Source $it") } + source(21, "HI", "Noravia Inclusion Federation")
        val shown = SourceRanking.rank("hi", many)
        assertEquals(SourceRanking.SHOWN, shown.size)
        assertEquals(21L, shown.first().id)
    }
}
