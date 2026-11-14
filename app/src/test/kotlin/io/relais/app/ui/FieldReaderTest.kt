// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import io.relais.app.data.FileLite
import io.relais.app.data.LoadImages
import io.relais.app.data.NetState
import io.relais.app.data.Ref
import io.relais.app.data.ReportEntity
import io.relais.app.data.imagesAllowed
import io.relais.app.data.unreadableOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lot 3, C: Following without an appname, "Load images", rows dimmed offline. Made-up data. */
class FieldReaderTest {
    private fun report(id: Long, countries: List<String>, body: String? = null, fromRss: Boolean = false, pdf: String? = null) = ReportEntity(
        id = id, title = "Report $id", headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = id, dateOriginal = null, primaryCountryId = null,
        primaryCountryName = countries.firstOrNull(), countries = countries.map { Ref(0, it) }, sources = emptyList(), formatId = null,
        formatName = null, themes = emptyList(), languages = emptyList(),
        files = pdf?.let { listOf(FileLite(url = it, size = 1000, mimetype = "application/pdf")) }.orEmpty(),
        bodyHtml = body, isHeadline = fromRss, fromRss = fromRss, fetchedAt = 0,
    )

    @Test
    fun headlinesModeKeepsTheFeedsHeadlinesOnFollowedCountries() {
        val feed = listOf(report(1, listOf("Noravia")), report(2, listOf("Kestrova", "Noravia")), report(3, listOf("Ostland")), report(4, emptyList()))
        // The match is on the names, whatever their case; any country of a headline counts.
        assertEquals(listOf(1L, 2L), headlinesOnFollowed(feed, listOf("noravia")).map { it.id })
        assertEquals(emptyList<Long>(), headlinesOnFollowed(feed, emptyList()).map { it.id })
    }

    @Test
    fun loadImagesFollowsTheChoiceAndTheNetwork() {
        val wifi = NetState(online = true, metered = false)
        val mobile = NetState(online = true, metered = true)
        assertTrue(imagesAllowed(LoadImages.ALWAYS, mobile))
        assertTrue(imagesAllowed(LoadImages.WIFI, wifi))
        assertFalse(imagesAllowed(LoadImages.WIFI, mobile))
        assertFalse(imagesAllowed(LoadImages.NEVER, wifi))
        // An unknown or missing value is Always, the default.
        assertEquals(LoadImages.ALWAYS, LoadImages.of(null))
        assertEquals(LoadImages.ALWAYS, LoadImages.of("SOMETIMES"))
    }

    @Test
    fun offlineOnlyReportsWithNeitherTextNorPdfAreDimmed() {
        val onPhone = setOf("https://relais.test/3.pdf")
        assertTrue(unreadableOffline(report(1, emptyList()), offline = true, pdfsOnPhone = onPhone))
        // Its text, or its PDF, is on the phone: readable.
        assertFalse(unreadableOffline(report(2, emptyList(), body = "<p>Text</p>"), offline = true, pdfsOnPhone = onPhone))
        assertFalse(unreadableOffline(report(3, emptyList(), pdf = "https://relais.test/3.pdf"), offline = true, pdfsOnPhone = onPhone))
        // A headline of the feed keeps its summary; online, nothing is dimmed.
        assertFalse(unreadableOffline(report(4, emptyList(), fromRss = true), offline = true, pdfsOnPhone = onPhone))
        assertFalse(unreadableOffline(report(5, emptyList()), offline = false, pdfsOnPhone = onPhone))
    }
}
