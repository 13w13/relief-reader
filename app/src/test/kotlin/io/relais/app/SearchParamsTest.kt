// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import io.relais.api.Requests.SearchSort
import io.relais.app.ui.initialSearchParams
import io.relais.app.ui.withSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The default "Past 12 months" period goes with relevance, never with Newest. */
class SearchParamsTest {
    @Test
    fun typedQueryStartsWithTwelveMonths() {
        assertEquals(365, initialSearchParams("cholera", headlinesOnly = false).postedDays)
    }

    @Test
    fun headlinesWithoutQueryHaveNoPeriod() {
        assertNull(initialSearchParams("", headlinesOnly = true).postedDays)
    }

    @Test
    fun newestDropsTheDefaultPeriodAndRelevanceBringsItBack() {
        val newest = initialSearchParams("cholera", headlinesOnly = false).withSort(SearchSort.NEWEST)
        assertNull(newest.postedDays)
        assertEquals(365, newest.withSort(SearchSort.RELEVANT).postedDays)
    }

    @Test
    fun aPeriodChosenByTheReaderStays() {
        val week = initialSearchParams("cholera", headlinesOnly = false).copy(postedDays = 7)
        assertEquals(7, week.withSort(SearchSort.NEWEST).postedDays)
        assertEquals(7, week.withSort(SearchSort.NEWEST).withSort(SearchSort.RELEVANT).postedDays)
    }
}
