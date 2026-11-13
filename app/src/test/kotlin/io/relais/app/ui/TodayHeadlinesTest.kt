// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import io.relais.app.data.ReportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Today's first block (v0.3.2): a large headline on every visit, kept out of the earlier ones. */
class TodayHeadlinesTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1794614400000L // 14 Nov 2026, 00:00 UTC
    private val since = now - 2 * hour // the last visit

    private fun headline(id: Long, hoursAgo: Long) = ReportEntity(
        id = id, title = "Headline $id", headlineTitle = "Headline $id", headlineSummary = null,
        imageUrl = "https://relais.test/$id.jpg", imageCaption = null, imageCopyright = null, urlAlias = null,
        origin = null, dateCreated = now - hoursAgo * hour, dateOriginal = null, primaryCountryId = 220,
        primaryCountryName = "Sudan", countries = emptyList(), sources = emptyList(), formatId = 8,
        formatName = "News and Press Release", themes = emptyList(), languages = listOf("en"), files = emptyList(),
        bodyHtml = null, isHeadline = true, fromRss = false, fetchedAt = now,
    )

    // The week's headlines, newest first, as observeHeadlines gives them.
    private val newest = headline(1, hoursAgo = 1)
    private val newer = headline(2, hoursAgo = 1)
    private val yesterday = headline(3, hoursAgo = 20)
    private val monday = headline(4, hoursAgo = 80)
    private val week = listOf(newest, newer, yesterday, monday)

    @Test
    fun theFirstNewUnreadHeadlineIsLarge() {
        val blocks = headlineBlocks(week, since, readIds = setOf(1L))
        assertEquals(listOf(1L, 2L), blocks.new.map { it.id })
        assertEquals(2L, blocks.hero?.id)
        assertEquals(listOf(3L, 4L), blocks.earlier.map { it.id })
    }

    @Test
    fun withEveryNewHeadlineReadTheMostRecentStaysLarge() {
        val blocks = headlineBlocks(week, since, readIds = setOf(1L, 2L))
        assertEquals(1L, blocks.hero?.id)
        assertEquals(listOf(3L, 4L), blocks.earlier.map { it.id })
    }

    @Test
    fun withoutANewHeadlineTheMostRecentOfTheWeekIsLargeAndLeavesTheEarlierOnes() {
        val blocks = headlineBlocks(listOf(yesterday, monday), since, readIds = emptySet())
        assertEquals(emptyList<Long>(), blocks.new.map { it.id })
        assertEquals(3L, blocks.hero?.id)
        assertEquals(listOf(4L), blocks.earlier.map { it.id })
    }

    @Test
    fun aReadHeadlineCanBeTheLargeOne() {
        val blocks = headlineBlocks(listOf(yesterday, monday), since, readIds = setOf(3L))
        assertEquals(3L, blocks.hero?.id)
    }

    @Test
    fun noHeadlineInTheWeekNoLargeOne() {
        val blocks = headlineBlocks(emptyList(), since, readIds = emptySet())
        assertNull(blocks.hero)
        assertEquals(emptyList<ReportEntity>(), blocks.earlier)
    }
}
