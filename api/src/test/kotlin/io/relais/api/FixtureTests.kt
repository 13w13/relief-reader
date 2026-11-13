// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parses responses in the API's shape (fixtures/ at the repository root, written by tools/make_fixtures.py). */
class FixtureTests {
    private fun reports(name: String): ApiResponse<ReportFields> =
        RelaisJson.decodeFromString(ApiResponse.serializer(ReportFields.serializer()), File("../fixtures/$name").readText())

    @Test
    fun headlinesHaveEditorialFieldsAndText() {
        val response = reports("headlines.json")
        assertEquals(50, response.data.size)
        assertTrue(response.data.all { it.id > 0 && !it.fields.headline?.title.isNullOrBlank() })
        assertTrue(response.data.all { !it.fields.bodyHtml.isNullOrBlank() })
        assertTrue(response.data.all { ApiDates.parse(it.fields.date?.created) != null })
        assertTrue(response.data.any { it.fields.file.any { f -> (f.pagecount ?: 0) > 0 && f.preview != null } })
        assertTrue(response.data.any { it.fields.headline?.image?.urlLarge != null })
    }

    @Test
    fun countryReportsAndFormatFacets() {
        val response = reports("sudan-reports.json")
        assertTrue(response.data.all { it.fields.primaryCountry?.id == Ids.COUNTRY_SUDAN })
        val formats = response.embedded!!.facets.getValue("format.id").data
        assertTrue(formats.any { it.value == Ids.FORMAT_SITREP.toString() && it.count > 0 })
        // Documents without text (maps, infographics) still parse, with files.
        assertTrue(response.data.any { it.fields.bodyHtml == null && it.fields.file.isNotEmpty() })
    }

    @Test
    fun bodiesBecomeBlocks() {
        val response = reports("headlines.json")
        response.data.forEach { item ->
            val blocks = HtmlBlocks.parse(item.fields.bodyHtml.orEmpty())
            assertTrue("no blocks for ${item.id}", blocks.isNotEmpty())
        }
    }

    @Test
    fun countriesHaveHumanitarianStatus() {
        val text = File("../fixtures/countries.json").readText()
        val response = RelaisJson.decodeFromString(ApiResponse.serializer(CountryFields.serializer()), text)
        assertTrue(response.data.size > 200)
        assertTrue(response.data.any { it.fields.iso3 == "sdn" && it.fields.status == "ongoing" })
    }

    @Test
    fun combinedSyncFilterReturnsHeadlinesAndCountries() {
        val response = reports("sync-sample.json")
        assertTrue(response.data.any { it.fields.headline != null })
        assertTrue(response.data.any { it.fields.primaryCountry?.id == Ids.COUNTRY_SUDAN })
    }
}
