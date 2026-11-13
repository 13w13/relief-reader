// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of produit/passe-v0.2.md, 6.3. */
class AlertsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_762_500_000_000L

    private fun report(id: Long, title: String, formatId: Long, at: Long, countryId: Long = 220, published: Long? = null, crisis: Long? = null) =
        ReportEntity(
            id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
            imageCopyright = null, urlAlias = null, origin = null, dateCreated = at, dateOriginal = published,
            primaryCountryId = countryId, primaryCountryName = null, countries = emptyList(),
            sources = listOf(SourceLite(1, "UNHCR", "UNHCR")), formatId = formatId, formatName = null, themes = emptyList(),
            languages = listOf("en"), files = emptyList(), bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = at,
            disasters = crisis?.let { listOf(Ref(it, "Crisis")) },
        )

    private val followed = mapOf(220L to "Sudan", 122L to "Lebanon")

    @Test
    fun flashUpdateInSudanButNotADtmFlashAlert() {
        val since = now - day
        val reports = listOf(
            report(1, "UNHCR Flash Update #1 – Blue Nile State", 10, now - 2 * 3_600_000L),
            report(2, "DTM Sudan Focused Flash Alert: Update 004", 9, now - 3_600_000L),
            report(3, "Lebanon: weekly update", 10, now - 3_600_000L, countryId = 122),
        )
        val items = Alerts.candidates(reports, since, now, followed, emptyMap())
        assertEquals(listOf("country:220" to 1L), items.map { it.scopeKey to it.report.id })
        assertEquals(AlertKind.FLASH_UPDATE, items.single().kind)
    }

    @Test
    fun oldPlanPostedLateIsNoNewAppeal() {
        val late = report(1, "Sudan: Humanitarian Response Plan 2025", 4, now - 3_600_000L, published = now - 200 * day)
        val fresh = report(2, "Sudan Crisis: Revised Emergency Appeal #2", 4, now - 3_600_000L, published = now - 2 * day)
        assertEquals(null, Alerts.kindOf(late, now))
        assertEquals(AlertKind.APPEAL, Alerts.kindOf(fresh, now))
        // Posted before the last sync: already known.
        assertTrue(Alerts.candidates(listOf(fresh), since = now, now = now, countries = followed, crises = emptyMap()).isEmpty())
    }

    @Test
    fun capsOnePerItemThreeADayThenGrouped() {
        fun item(i: Int) = AlertItem("country:$i", "C$i", AlertKind.APPEAL, report(i.toLong(), "Appeal", 4, now, countryId = i.toLong()))
        assertEquals(2, Alerts.plan(listOf(item(1), item(2)), emptySet(), 0).single.size)
        // Sudan already alerted today.
        assertEquals(listOf("country:2"), Alerts.plan(listOf(item(1), item(2)), setOf("country:1"), 1).single.map { it.scopeKey })
        // Four at once, or one more than the day allows: one grouped notification.
        assertEquals(AlertPlan(emptyList(), 4), Alerts.plan((1..4).map(::item), emptySet(), 0))
        assertEquals(AlertPlan(emptyList(), 2), Alerts.plan(listOf(item(5), item(6)), emptySet(), 2))
        // After the grouped one, nothing more that day.
        assertTrue(Alerts.plan(listOf(item(7)), emptySet(), 4).isEmpty)
    }

    @Test
    fun oneReportOfAFollowedCountryAndCrisisIsOneAlert() {
        val r = report(1, "Flash Update No. 3", 10, now, crisis = 52586)
        val items = Alerts.candidates(listOf(r), now - day, now, followed, mapOf(52586L to "Ebola"))
        assertEquals(1, items.size)
    }
}
