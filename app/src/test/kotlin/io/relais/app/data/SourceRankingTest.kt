// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /** The two tries with made-up answers; counts the requests each search sends. */
    private class Tries(val first: () -> List<String>, val second: () -> List<String>) {
        var sent = 0
        suspend fun search() = SourceRanking.twoTries({ sent++; first() }, { sent++; second() })
    }

    private fun unreadable() = ApiException.Server(200, "${ApiException.Server.UNREADABLE}: SerializationException")

    @Test
    fun aFirstAnswerWithResultsIsTheOnlyRequest() = runBlocking {
        val t = Tries({ listOf("HI") }, { fail("no second try"); emptyList() })
        assertEquals(listOf("HI"), t.search())
        assertEquals(1, t.sent)
    }

    @Test
    fun nothingFoundOrARefusedRequestGetsOneSecondTry() = runBlocking {
        for (first in listOf<() -> List<String>>({ emptyList() }, { throw ApiException.BadRequest("Invalid query") }, { throw unreadable() })) {
            val t = Tries(first, { listOf("Solidarités") })
            assertEquals(listOf("Solidarités"), t.search())
            assertEquals(2, t.sent)
        }
    }

    @Test
    fun anAccessErrorIsShownWithoutASecondRequest() = runBlocking {
        val errors = listOf(
            ApiException.RateLimited(60),
            ApiException.AccessDenied("Forbidden"),
            ApiException.Blocked(),
            ApiException.NoAppname(),
            ApiException.Network(java.io.IOException()),
            ApiException.Server(503, "Service unavailable"),
        )
        for (error in errors) {
            val t = Tries({ throw error }, { fail("no second try after ${error::class.simpleName}"); emptyList() })
            try {
                t.search()
                fail("${error::class.simpleName} should be thrown")
            } catch (e: ApiException) {
                assertSame(error, e)
            }
            assertEquals(1, t.sent)
        }
    }

    @Test
    fun afterAnEmptyAnswerARefusedSecondTryMeansNothingFound() = runBlocking {
        val t = Tries({ emptyList() }, { throw ApiException.BadRequest("Invalid query") })
        assertTrue(t.search().isEmpty())
        assertEquals(2, t.sent)
    }

    @Test
    fun afterAnEmptyAnswerAnAccessErrorIsStillShown() = runBlocking {
        val limited = ApiException.RateLimited(null)
        val t = Tries({ emptyList() }, { throw limited })
        try {
            t.search()
            fail("the 429 should be thrown")
        } catch (e: ApiException) {
            assertSame(limited, e)
        }
    }

    @Test
    fun twoRefusedTriesShowTheSecondError() = runBlocking {
        val second = ApiException.BadRequest("Invalid query: plain")
        val t = Tries({ throw ApiException.BadRequest("Invalid query: wildcards") }, { throw second })
        try {
            t.search()
            fail("the second error should be thrown")
        } catch (e: ApiException) {
            assertSame(second, e)
        }
        assertEquals(2, t.sent)
    }

    @Test
    fun aCancelledSearchStopsThere() = runBlocking {
        val t = Tries({ throw CancellationException("typing") }, { fail("no second try"); emptyList() })
        try {
            t.search()
            fail("the cancellation should be thrown")
        } catch (e: CancellationException) {
            assertEquals("typing", e.message)
        }
        assertEquals(1, t.sent)
    }
}
