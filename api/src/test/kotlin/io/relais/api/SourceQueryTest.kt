// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Search organizations" as a query_string (founder, 15/11). Made-up organization names. */
class SourceQueryTest {
    @Test
    fun eachWordIsAPrefixWithAndWithoutItsAccents() {
        assertEquals("(humanit OR humanit*)", SourceQuery.prefix("humanit"))
        assertEquals(
            "(solidarités OR solidarités* OR solidarites OR solidarites*) AND (internat OR internat*)",
            SourceQuery.prefix("Solidarités Internat"),
        )
        // A two-letter acronym, whatever the case and the spaces.
        assertEquals("(hi OR hi*)", SourceQuery.prefix("  HI "))
    }

    @Test
    fun specialCharactersSeparateWordsAndOperatorsStayWords() {
        assertEquals("(noravia OR noravia*) AND (sans OR sans*) AND (frontières OR frontières* OR frontieres OR frontieres*)", SourceQuery.prefix("Noravia-Sans-Frontières"))
        assertEquals("(kestrova OR kestrova*) AND (food OR food*) AND (kfm OR kfm*)", SourceQuery.prefix("Kestrova Food (KFM)"))
        assertEquals("(water OR water*) AND (and OR and*) AND (hygiene OR hygiene*)", SourceQuery.prefix("Water AND Hygiene"))
        assertEquals("(a OR a*) AND (b OR b*)", SourceQuery.prefix("a+b\"*?:/"))
        assertNull(SourceQuery.prefix("  ()  "))
    }

    @Test
    fun theSecondTryHasTheWordsWithoutWildcards() {
        assertEquals("solidarités internat", SourceQuery.plain("Solidarités Internat"))
        assertNull(SourceQuery.plain("*"))
    }

    @Test
    fun theRequestCarriesTheQueryAndItsFields() {
        val json = Requests.searchSources("Solidarités").toJson()
        val query = json["query"]!!.jsonObject
        assertEquals("(solidarités OR solidarités* OR solidarites OR solidarites*)", query["value"]!!.jsonPrimitive.content)
        assertEquals("AND", query["operator"]!!.jsonPrimitive.content)
        assertEquals(20, json["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("solidarités", Requests.searchSources("Solidarités", prefix = false).toJson()["query"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }
}
