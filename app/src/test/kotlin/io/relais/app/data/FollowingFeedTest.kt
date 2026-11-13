// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The feed of Following with organizations (produit/suivre-organisation.md §6, real cases): one
 * line per document, whatever brought it; "Reports only" limits what an organization adds and
 * never hides a document that came by a followed country.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FollowingFeedTest {
    private lateinit var db: RelaisDatabase
    private val dao get() = db.dao()
    private val now = 1_762_500_000_000L

    private val acaps = SourceLite(9417, "ACAPS", "ACAPS")
    private val wfp = SourceLite(1741, "WFP", "World Food Programme")
    private val unhcr = SourceLite(2868, "UNHCR", "UN High Commissioner for Refugees")

    private fun report(id: Long, title: String, countryId: Long, countryName: String, formatId: Long, vararg sources: SourceLite) = ReportEntity(
        id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = now - id, dateOriginal = null,
        primaryCountryId = countryId, primaryCountryName = countryName, countries = listOf(Ref(countryId, countryName)),
        sources = sources.toList(), formatId = formatId, formatName = null, themes = emptyList(), languages = listOf("English"),
        files = emptyList(), bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = now,
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RelaisDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private suspend fun feed(): List<Long> = dao.observeFollowingFeed(true, listOf(-1L), false, 100).first().map { it.id }

    @Test
    fun aJointReportByACountryAndTwoOrganizationsIsOneLine() = runBlocking {
        // Case 1: "Yemen Joint Monitoring Report" (3 Nov), Yemen, ACAPS and WFP followed.
        dao.upsertReports(listOf(report(1, "Yemen Joint Monitoring Report", 255, "Yemen", 10, acaps, wfp)))
        dao.upsertFollowed(FollowedCountryEntity(255, "Yemen", 0, 0))
        dao.upsertFollowedSource(FollowedSourceEntity(9417, "ACAPS", "ACAPS", followedAt = 0, lastVisitAt = 0))
        dao.upsertFollowedSource(FollowedSourceEntity(1741, "WFP", "World Food Programme", followedAt = 0, lastVisitAt = 0))
        assertEquals(listOf(1L), feed())
    }

    @Test
    fun reportsOnlyNeverHidesWhatAFollowedCountryBrings() = runBlocking {
        // Case 3: a UNHCR infographic on Sudan, Sudan followed, UNHCR on "Reports only": present.
        // Case 4: a UNHCR situation report on Afghanistan, not followed: present; its infographic: not.
        dao.upsertReports(
            listOf(
                report(1, "Sudan: refugee arrivals (infographic)", 220, "Sudan", 12570, unhcr),
                report(2, "Afghanistan: returns situation report", 13, "Afghanistan", 10, unhcr),
                report(3, "Afghanistan: returns (infographic)", 13, "Afghanistan", 12570, unhcr),
            ),
        )
        dao.upsertFollowed(FollowedCountryEntity(220, "Sudan", 0, 0))
        dao.upsertFollowedSource(FollowedSourceEntity(2868, "UNHCR", "UN High Commissioner for Refugees", reportsOnly = true, followedAt = 0, lastVisitAt = 0))
        assertEquals(listOf(1L, 2L), feed())

        // "Everything": the infographic on Afghanistan joins the feed.
        dao.setSourceReportsOnly(2868, false)
        assertEquals(listOf(1L, 2L, 3L), feed())
    }

    @Test
    fun anOrganizationIdIsNotMistakenForAnother() = runBlocking {
        // "id":94 must not match "id":9417.
        dao.upsertReports(listOf(report(1, "Colombia: analysis", 49, "Colombia", 3, acaps)))
        dao.upsertFollowedSource(FollowedSourceEntity(94, "XX", "Another organization", followedAt = 0, lastVisitAt = 0))
        assertEquals(emptyList<Long>(), feed())
    }
}
