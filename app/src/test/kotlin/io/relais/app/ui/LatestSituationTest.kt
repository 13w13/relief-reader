// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import io.relais.app.data.ReportEntity
import io.relais.app.data.SourceLite
import org.junit.Assert.assertEquals
import org.junit.Test

/** revue-lecteur-v0.2.md, defect 1: the overview's situation report is the one covering the latest period. */
class LatestSituationTest {
    private val day = 24 * 60 * 60 * 1000L
    private val nov6 = 1794009600000L // 7 Nov 2026, 00:00 UTC: "posted in November"

    private fun sitrep(id: Long, title: String, posted: Long) = ReportEntity(
        id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = posted, dateOriginal = null,
        primaryCountryId = 220, primaryCountryName = "Sudan", countries = emptyList(), sources = listOf(SourceLite(1, "UNHCR", "UNHCR")),
        formatId = 10, formatName = "Situation Report", themes = emptyList(), languages = listOf("en"), files = emptyList(),
        bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = posted,
    )

    @Test
    fun aJulyUpdatePostedInNovemberIsNotTheLatestSituation() {
        val july = sitrep(1, "Sudan Situation: UNHCR Monthly External Update #121 - July 2026", nov6)
        val flash = sitrep(2, "Sudan: Flash Update No. 5 (as of 1 November 2026)", nov6 - 3 * day)
        assertEquals(2L, latestSituation(listOf(july, flash))?.id)
        // Without a period in the title, the publication date stands for it.
        val undated = sitrep(3, "Sudan: Humanitarian Update", nov6 - day)
        assertEquals(3L, latestSituation(listOf(july, undated))?.id)
    }
}
