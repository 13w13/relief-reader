// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regional crises (lot 3). The addresses and answers below are made up from the site's grammar
 * (UN-OCHA/rwint9-site, AdvancedSearch.php and ReportRiver.php) and the API's documentation:
 * nobody in the team has seen a real answer for topics yet.
 */
class TopicsTest {
    private fun json(f: Filter?) = f?.toJson().toString()

    @Test
    fun aListOfReportsBecomesTheSitesFilter() {
        val parsed = RiverQuery.parse("https://reliefweb.int/updates?view=reports&advanced-search=%28PC220.PC221%29_%28F10%29")!!
        val s = json(parsed.filter)
        // "Primary country 220 OR 221, AND format 10", and not a map (the view "reports").
        assertTrue(s, s.contains("""{"operator":"OR","conditions":[{"field":"primary_country.id","value":220},{"field":"primary_country.id","value":221}]}"""))
        assertTrue(s, s.contains("""{"field":"format.id","value":10}"""))
        assertTrue(s, s.contains("""{"field":"format.id","value":[12,12570,38974],"operator":"OR","negate":true}"""))
        assertNull(parsed.query)
    }

    @Test
    fun viewsSearchExclusionsAndDates() {
        assertEquals("""{"field":"headline"}""", json(RiverQuery.parse("https://reliefweb.int/updates?view=headlines")!!.filter))
        assertNull(RiverQuery.parse("https://reliefweb.int/updates")!!.filter)
        assertEquals("cholera", RiverQuery.parse("https://reliefweb.int/updates?search=cholera&view=all")!!.query)
        val without = json(RiverQuery.advancedFilter("(PC220)_!(S1503)"))
        assertTrue(without, without.contains("""{"field":"source.id","value":1503}"""))
        assertTrue(without, without.contains("\"negate\":true"))
        // The "to" day is inclusive, as on the site.
        val dates = json(RiverQuery.advancedFilter("(DA20250101-20250131)"))
        assertEquals("""{"field":"date.created","value":{"from":"2025-01-01T00:00:00+00:00","to":"2025-01-31T23:59:59+00:00"}}""", dates)
    }

    @Test
    fun whatTheAppCannotReadIsSkipped() {
        assertNull(RiverQuery.parse("https://reliefweb.int/disasters?advanced-search=(C220)"))
        assertNull(RiverQuery.parse("https://reliefweb.int/jobs"))
        assertNull(RiverQuery.parse("https://example.org/updates?view=reports"))
        assertNull(RiverQuery.parse("not an address"))
        // An unknown code, a broken group, a view the app does not know.
        assertNull(RiverQuery.parse("https://reliefweb.int/updates?advanced-search=(XY12)"))
        assertNull(RiverQuery.parse("https://reliefweb.int/updates?advanced-search=(PC220"))
        assertNull(RiverQuery.parse("https://reliefweb.int/updates?view=videos"))
    }

    private fun answer(items: String): JsonElement = RelaisJson.parseToJsonElement("""{"totalCount":1,"data":[$items]}""")

    private fun item(fields: String): JsonElement = answer("""{"id":"7","fields":$fields}""")

    @Test
    fun aTopicIsReadTolerantly() {
        val topic = Topics.one(
            item(
                """{"title":" Sudan Regional Crisis ","featured":"1","url_alias":"https://reliefweb.int/topics/sudan",
                "date":{"changed":"2026-11-12T08:00:00+00:00"},"introduction-html":"<p>Intro</p>",
                "rivers":[{"title":"Situation reports","url":"https://reliefweb.int/updates?advanced-search=(PC220)"},
                          "not an object", {"title":"No address"}, {"url":"https://reliefweb.int/updates?view=maps"}],
                "icon":{"unexpected":true}}""",
            ),
        )!!
        assertEquals(7L, topic.id)
        assertEquals("Sudan Regional Crisis", topic.title)
        assertTrue(topic.featured)
        assertNotNull(topic.changedAt)
        assertEquals(2, topic.rivers.size)
        assertEquals("", topic.rivers[1].title)
        // Without a title, or with fields of another shape: no topic, no crash.
        assertNull(Topics.one(item("""{"featured":true}""")))
        assertNull(Topics.one(item("""[1,2]""")))
        assertFalse(Topics.one(item("""{"title":"T","featured":{"x":1},"rivers":{"a":1}}"""))!!.featured)
    }

    @Test
    fun anItemWithoutFieldsIsSkippedNotTheList() {
        // Review of PR 19: one broken item no longer fails the whole list.
        val list = Topics.list(answer("""{"id":1},{"id":"x","fields":{"title":"No id"}},"text",{"id":2,"fields":{"title":"Kept"}}"""))
        assertEquals(listOf(2L), list.map { it.id })
        assertEquals(emptyList<TopicInfo>(), Topics.list(RelaisJson.parseToJsonElement("""{"data":{"not":"a list"}}""")))
        assertEquals(emptyList<TopicInfo>(), Topics.list(RelaisJson.parseToJsonElement("[]")))
    }

    @Test
    fun aFollowedTopicIsTheOrOfItsListsWithoutSearchWords() {
        val rivers = listOf(
            TopicRiver("Reports", "https://reliefweb.int/updates?advanced-search=(PC220)"),
            TopicRiver("Maps", "https://reliefweb.int/updates?view=maps&advanced-search=(PC221)"),
            TopicRiver("Search", "https://reliefweb.int/updates?search=cholera"),
            TopicRiver("Jobs", "https://reliefweb.int/jobs"),
        )
        val s = json(Topics.followFilter(rivers))
        assertTrue(s, s.startsWith("""{"operator":"OR","conditions":["""))
        assertTrue(s, s.contains("""{"field":"primary_country.id","value":220}"""))
        assertTrue(s, s.contains("""{"field":"primary_country.id","value":221}"""))
        assertFalse(s, s.contains("cholera"))
        assertNull(Topics.followFilter(listOf(TopicRiver("Jobs", "https://reliefweb.int/jobs"))))
    }
}
