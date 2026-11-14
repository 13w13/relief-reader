// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import io.relais.app.data.ReportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pile of headlines on Today and the block at the end of a report opened from it (v0.4). */
class HeadlinePileTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1794614400000L // 14 Nov 2026, 00:00 UTC

    private fun headline(id: Long, hoursAgo: Long, title: String = "Sudan: Headline $id", country: String? = "Sudan") = ReportEntity(
        id = id, title = title, headlineTitle = title, headlineSummary = null,
        imageUrl = "https://relais.test/$id.jpg", imageCaption = null, imageCopyright = null, urlAlias = null,
        origin = null, dateCreated = now - hoursAgo * hour, dateOriginal = null, primaryCountryId = 220,
        primaryCountryName = country, countries = emptyList(), sources = emptyList(), formatId = 8,
        formatName = "News and Press Release", themes = emptyList(), languages = listOf("en"), files = emptyList(),
        bodyHtml = null, isHeadline = true, fromRss = false, fetchedAt = now,
    )

    // The week's headlines, newest first, as observeHeadlines gives them.
    private val week = listOf(headline(1, 1), headline(2, 3), headline(3, 20), headline(4, 80))

    @Test
    fun thePileOpensOnTheFirstHeadlineNotRead() {
        assertEquals(0, pileOpening(week, readIds = emptySet()))
        assertEquals(2, pileOpening(week, readIds = setOf(1L, 2L)))
        // An older one left: the pile opens on it, past the newer read ones.
        assertEquals(3, pileOpening(week, readIds = setOf(1L, 2L, 3L)))
    }

    @Test
    fun allReadThePileOpensOnTheFirst() {
        assertEquals(0, pileOpening(week, readIds = setOf(1L, 2L, 3L, 4L)))
        assertEquals(0, pileOpening(emptyList(), readIds = emptySet()))
    }

    @Test
    fun aHeadlineKnowsItsPlaceAndItsNeighbours() {
        val place = headlinePlace(week, id = 2, readIds = setOf(1L, 2L))!!
        assertEquals(1, place.index)
        assertEquals(4, place.total)
        assertEquals(1L, place.previous?.id)
        assertEquals(3L, place.next?.id)
        assertTrue(place.nextUnread)
    }

    @Test
    fun theFirstHasNoPreviousAndTheLastNoNext() {
        val first = headlinePlace(week, id = 1, readIds = emptySet())!!
        assertNull(first.previous)
        assertEquals(2L, first.next?.id)
        val last = headlinePlace(week, id = 4, readIds = emptySet())!!
        assertEquals(3, last.index)
        assertNull(last.next)
        assertFalse(last.nextUnread)
    }

    @Test
    fun aHeadlineOutsideTheWeekHasNoPlace() {
        assertNull(headlinePlace(week, id = 99, readIds = emptySet()))
    }

    @Test
    fun theCountryLineDropsWhenTheTitleStartsWithIt() {
        assertNull(pileCountry(headline(1, 1, title = "Sudan: Floods in Darfur")))
        assertEquals("Sudan", pileCountry(headline(1, 1, title = "Floods in Darfur")))
        assertNull(pileCountry(headline(1, 1, title = "Floods", country = null)))
    }
}
