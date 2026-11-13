// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun resource(name: String): String =
    requireNotNull(ApiTests::class.java.classLoader.getResource(name)) { "missing $name" }.readText()

class ApiTests {
    // --- RSS -----------------------------------------------------------------

    @Test
    fun parsesHeadlinesFeed() {
        val items = RssParser.parse(resource("rw_headlines.xml"))
        assertEquals(20, items.size)
        val first = items.first()
        assertEquals("Test: food insecurity rises in the eastern sites", first.title)
        assertEquals(listOf("Chad"), first.countries)
        assertEquals("Test Food Programme", first.source)
        assertEquals("Analysis", first.format)
        assertEquals(1_234_567L, first.enclosureLength)
        assertEquals("© Re.L test images", first.imageCopyright)
        assertTrue(first.summary!!.startsWith("Test estimates 1.05 million people"))
        assertTrue(first.themes.contains("Food and Nutrition"))
        assertFalse(first.themes.contains("Analysis"))
        assertTrue(first.id < 0)
        assertEquals(first.id, RssParser.syntheticId(first.link))
        assertNotNull(first.publishedAt)
    }

    @Test
    fun parsesCountryFeed() {
        val items = RssParser.parse(resource("rw_sdn.xml"))
        assertEquals(20, items.size)
        assertTrue(items.all { it.title.isNotBlank() && it.link.startsWith("https://reliefweb.int/") })
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    // --- Requests --------------------------------------------------------------

    @Test
    fun syncRequestCombinesHeadlinesAndCountries() {
        val since = Instant.parse("2026-11-01T00:00:00Z")
        val json = Requests.sync(listOf(220L, 145L), since).toJson()
        assertEquals("latest", json["preset"]!!.jsonPrimitive.content)
        assertEquals(200, json["limit"]!!.jsonPrimitive.content.toInt())
        val filter = json["filter"]!!.jsonObject
        assertEquals("AND", filter["operator"]!!.jsonPrimitive.content)
        val conditions = filter["conditions"]!!.jsonArray
        val who = conditions[0].jsonObject
        assertEquals("OR", who["operator"]!!.jsonPrimitive.content)
        val headline = who["conditions"]!!.jsonArray[0].jsonObject
        assertEquals("headline", headline["field"]!!.jsonPrimitive.content)
        assertNull(headline["value"])
        val countries = who["conditions"]!!.jsonArray[1].jsonObject
        assertEquals("primary_country.id", countries["field"]!!.jsonPrimitive.content)
        assertEquals(2, countries["value"]!!.jsonArray.size)
        val date = conditions[1].jsonObject["value"]!!.jsonObject
        assertEquals("2026-11-01T00:00:00+00:00", date["from"]!!.jsonPrimitive.content)
        assertTrue(json["fields"]!!.jsonObject["include"]!!.jsonArray.any { it.jsonPrimitive.content == "body-html" })
    }

    @Test
    fun syncWithoutCountriesKeepsOnlyHeadlines() {
        val json = Requests.sync(emptyList(), Instant.EPOCH).toJson()
        val first = json["filter"]!!.jsonObject["conditions"]!!.jsonArray[0].jsonObject
        assertEquals("headline", first["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun countryTabRequestFiltersFormats() {
        val json = Requests.country(220, CountryTab.MAPS).toJson()
        val conditions = json["filter"]!!.jsonObject["conditions"]!!.jsonArray
        val formats = conditions[1].jsonObject
        assertEquals("format.id", formats["field"]!!.jsonPrimitive.content)
        assertEquals(3, formats["value"]!!.jsonArray.size)
        assertEquals("OR", formats["operator"]!!.jsonPrimitive.content)
        // The first page brings the facets of the filter sheet; older pages do not.
        assertNotNull(json["facets"])
        assertNull(Requests.country(220, CountryTab.MAPS, before = Instant.parse("2026-11-01T00:00:00Z")).toJson()["facets"])
    }

    @Test
    fun keyDocumentsKeepHeadlinesAndSheetFiltersCombine() {
        val filter = CountryFilter(sources = setOf(1503), languages = setOf(Ids.LANGUAGE_FRENCH), postedDays = 30)
        val json = Requests.country(220, CountryTab.KEY, filter, now = Instant.parse("2026-11-07T00:00:00Z")).toJson().toString()
        assertTrue(json, json.contains("{\"field\":\"headline\"}"))
        assertTrue(json, json.contains("\"field\":\"source.id\""))
        assertTrue(json, json.contains("\"field\":\"language.id\""))
        assertTrue(json, json.contains("\"from\":\"2026-10-08T00:00:00+00:00\""))
        assertEquals(3, filter.count)
        assertEquals("", CountryFilter().signature())
    }

    @Test
    fun searchUsesRelevanceWithKeywords() {
        val json = Requests.search("cholera").toJson()
        assertEquals("score:desc", json["sort"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cholera", json["query"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        val newest = Requests.search("").toJson()
        assertEquals("date.created:desc", newest["sort"]!!.jsonArray[0].jsonPrimitive.content)
        assertNull(newest["query"])
    }

    @Test
    fun apiDatesHaveNoZ() {
        assertEquals("2026-11-07T10:20:30+00:00", ApiDates.format(Instant.parse("2026-11-07T10:20:30.456Z")))
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), ApiDates.parse("2026-11-05T00:00:00+00:00"))
        assertNull(ApiDates.parse("not a date"))
    }

    // --- Client -----------------------------------------------------------------

    private val sampleReports = """
        {"totalCount": 2, "count": 1, "data": [{"id": "4171234", "score": 1, "fields": {
          "id": 4171234, "title": "Sudan: sample",
          "date": {"created": "2026-11-05T00:00:00+00:00", "original": "2026-10-01T00:00:00+00:00"},
          "primary_country": {"id": 220, "name": "Sudan", "shortname": "Sudan", "iso3": "sdn"},
          "country": [{"id": 220, "name": "Sudan"}, {"id": 75, "name": "Chad"}],
          "source": {"id": 1503, "name": "Médecins Sans Frontières", "shortname": "MSF"},
          "format": [{"id": 3, "name": "Analysis"}],
          "file": [{"url": "https://reliefweb.int/attachments/x/a.pdf", "filesize": "11623228", "pagecount": 24}],
          "body-html": "<p>Hello <strong>world</strong></p>"
        }}],
        "embedded": {"facets": {"format.id": {"data": [{"value": 3, "count": 12}, {"value": "10", "count": 4}]}}}}
    """.trimIndent()

    @Test
    fun clientParsesTolerantly() = runTest {
        var seenUrl = ""
        var calls = 0
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(sampleReports, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ReliefWebClient(HttpClient(engine), appname = { "test-app" }, onCall = { calls++ })
        val response = client.reports(Requests.headlines())
        assertTrue(seenUrl.contains("appname=test-app"))
        assertEquals(1, calls)
        assertEquals(2, response.totalCount)
        val report = response.data.single()
        assertEquals(4171234L, report.id)
        assertEquals("Sudan", report.fields.primaryCountry?.shortname)
        assertEquals(1, report.fields.source.size) // single object accepted as a list
        assertEquals(11_623_228L, report.fields.file.single().filesize)
        assertEquals("12", response.embedded!!.facets["format.id"]!!.data.first().count.toString())
        assertEquals("10", response.embedded!!.facets["format.id"]!!.data[1].value)
    }

    @Test
    fun clientMapsErrorsWithoutLeakingTheUrl() = runTest {
        fun clientFor(status: HttpStatusCode, body: String) = ReliefWebClient(
            HttpClient(MockEngine { respond(body, status) }),
            appname = { "secret-name" },
        )
        try {
            clientFor(HttpStatusCode.Forbidden, """{"status":403,"error":{"message":"You are not using an approved appname."}}""")
                .reports(Requests.headlines())
            fail("expected AccessDenied")
        } catch (e: ApiException.AccessDenied) {
            assertEquals("You are not using an approved appname.", e.message)
            assertFalse(e.message!!.contains("secret-name"))
        }
        try {
            clientFor(HttpStatusCode.OK, "<html><body>blocked</body></html>").reports(Requests.headlines())
            fail("expected Blocked")
        } catch (e: ApiException.Blocked) {
            // expected
        }
        try {
            clientFor(HttpStatusCode.TooManyRequests, "{}").reports(Requests.headlines())
            fail("expected RateLimited")
        } catch (e: ApiException.RateLimited) {
            // expected
        }
        try {
            ReliefWebClient(HttpClient(MockEngine { respond("{}") }), appname = { " " }).check()
            fail("expected NoAppname")
        } catch (e: ApiException.NoAppname) {
            // expected
        }
    }

    // --- HTML blocks ------------------------------------------------------------

    @Test
    fun htmlBecomesBlocks() {
        val html = """
            <h2>Key figures</h2>
            <p>About <strong>1.05 million</strong> people, see <a href="/report/x">the report</a>.</p>
            <ul><li>One</li><li>Two<ul><li>Nested</li></ul></li></ul>
            <ol start="3"><li><p>Three</p></li></ol>
            <table><thead><tr><th>Area</th><th>People</th></tr></thead>
            <tbody><tr><td>Darfur</td><td>12,000</td></tr></tbody></table>
            <blockquote><p>Quoted</p></blockquote>
            <hr>
            <p><img src="https://reliefweb.int/img.png" alt="Map"></p>
            <script>alert(1)</script>
        """.trimIndent()
        val blocks = HtmlBlocks.parse(html)
        assertEquals(Block.Heading(2, listOf(Span("Key figures"))), blocks[0])
        val paragraph = blocks[1] as Block.Paragraph
        assertEquals("About ", paragraph.spans[0].text)
        assertTrue(paragraph.spans[1].bold)
        assertEquals("https://reliefweb.int/report/x", paragraph.spans.first { it.link != null }.link)
        val items = blocks.filterIsInstance<Block.ListItem>()
        assertEquals(listOf("One", "Two", "Nested", "Three"), items.map { it.spans.joinToString("") { s -> s.text } })
        assertEquals(1, items[2].depth)
        assertEquals(3, items[3].number)
        assertTrue(items[3].ordered)
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertTrue(table.hasHeader)
        assertEquals(listOf(listOf("Area", "People"), listOf("Darfur", "12,000")), table.rows)
        assertTrue(blocks.any { it is Block.Quote })
        assertTrue(blocks.any { it == Block.Rule })
        assertEquals("https://reliefweb.int/img.png", blocks.filterIsInstance<Block.Image>().single().url)
        assertFalse(blocks.toString().contains("alert"))
    }

    @Test
    fun plainTextForSearch() {
        assertEquals("Hello world", HtmlBlocks.plainText("<p>Hello <b>world</b></p>"))
    }

    // --- Display ------------------------------------------------------------------

    @Test
    fun displayRules() {
        assertEquals("852 KB", Display.size(852_000))
        assertEquals("5.3 MB", Display.size(5_251_204))
        assertEquals("11.6 MB", Display.size(11_623_228))
        assertNull(Display.size(0))
        assertEquals("Situation report", Display.shortFormat(10, "Situation Report"))
        assertEquals("News release", Display.shortFormat(null, "News and Press Release"))
        assertEquals("Sudan +7", Display.countryContext("Sudan", 7))

        val now = LocalDate.of(2026, 11, 7).atTime(10, 0).toInstant(ZoneOffset.UTC)
        fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Today", Display.day(at(2026, 11, 7), now, ZoneOffset.UTC))
        assertEquals("Yesterday", Display.day(at(2026, 11, 6), now, ZoneOffset.UTC))
        assertEquals("4 Nov", Display.day(at(2026, 11, 4), now, ZoneOffset.UTC))
        assertEquals("4 Nov 2025", Display.day(at(2025, 11, 4), now, ZoneOffset.UTC))
        assertEquals("Published 10 Aug, posted Yesterday", Display.cardDate(at(2026, 11, 6), at(2026, 8, 10), now, ZoneOffset.UTC))
        assertEquals("Yesterday", Display.cardDate(at(2026, 11, 6), at(2026, 11, 4), now, ZoneOffset.UTC))
        assertEquals("msf.org", Display.domain("https://www.msf.org/some/page"))
    }
}

class CoversTests {
    private fun c(title: String, format: Long = Ids.FORMAT_SITREP) = Display.covers(title, format, null)

    @Test
    fun readsThePeriodFromTitles() {
        org.junit.Assert.assertEquals("Covers 1–31 Aug", c("Sudan: Flash Update No. 3 (1 to 31 August 2026)"))
        org.junit.Assert.assertEquals("Covers Jan–Sep 2026", c("Sudan Nutrition Sector Bulletin, January – September 2026"))
        org.junit.Assert.assertEquals("Covers up to 27 Oct", c("Soudan : Situation humanitaire au 27 octobre 2026"))
        org.junit.Assert.assertEquals("Covers Jul 2026", c("Sudan Situation: UNHCR Monthly External Update #121 - July 2026"))
        org.junit.Assert.assertEquals("Covers 2–8 Nov", c("Mozambique: Situation Report du 2 au 8 novembre 2026"))
        org.junit.Assert.assertNull(c("Sudan: Humanitarian Update", Ids.FORMAT_SITREP))
        org.junit.Assert.assertNull(c("Analysis of July 2026", Ids.FORMAT_ANALYSIS))
        org.junit.Assert.assertEquals("Covers Aug 2026", c("Map of floods - August 2026 (Publication date: 3 September 2026)", Ids.FORMAT_MAP))
    }

    @Test
    fun anOldPeriodIsSaidPlainly() {
        val now = java.time.LocalDate.of(2026, 11, 8).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val sitrep = Ids.FORMAT_SITREP
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 7, 31), Display.coverageEnd("UNHCR Monthly External Update #121 - July 2026", sitrep, null))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 8, 31), Display.coverageEnd("Flash Update No. 3 (1 to 31 August 2026)", sitrep, null))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 9, 30), Display.coverageEnd("Nutrition Sector Bulletin, January – September 2026", sitrep, null))
        org.junit.Assert.assertEquals(
            "Covers Jul 2026, 3 months ago",
            Display.oldCoverage("Sudan Situation: UNHCR Monthly External Update #121 - July 2026", sitrep, null, now, java.time.ZoneOffset.UTC),
        )
        // A recent period, or no period at all: nothing to say.
        org.junit.Assert.assertNull(Display.oldCoverage("Situation humanitaire au 27 octobre 2026", sitrep, null, now, java.time.ZoneOffset.UTC))
        org.junit.Assert.assertNull(Display.oldCoverage("Sudan: Humanitarian Update", sitrep, null, now, java.time.ZoneOffset.UTC))
    }
}

class MostReadTests {
    @Test
    fun parsesAQuotedListOfAddresses() {
        val (key, urls) = MostRead.parseLine("220,\"/report/sudan/a,https://reliefweb.int/map/sudan/b,/node/4237190\"")!!
        assertEquals("220", key)
        assertEquals(
            listOf("https://reliefweb.int/report/sudan/a", "https://reliefweb.int/map/sudan/b", "https://reliefweb.int/node/4237190"),
            urls,
        )
        assertEquals(4237190L, MostRead.nodeId(urls[2]))
    }

    @Test
    fun refusesLinesWithoutTwoColumns() {
        assertEquals(null, MostRead.parseLine("front"))
        assertEquals(null, MostRead.parseLine("<!DOCTYPE html>"))
        assertEquals(null, MostRead.parseLine("front,\"\""))
    }

    @Test
    fun buildsTheSiteQuery() {
        val query = Requests.byUrls(listOf("https://reliefweb.int/report/a", "https://reliefweb.int/node/12")).toJson().toString()
        assertTrue(query, query.contains("url_alias:\\\"https://reliefweb.int/report/a\\\" OR id:12"))
    }

    @Test
    fun mostCoveredLeavesWorldOutAndReturnsNoDocument() {
        val json = Requests.mostCovered(java.time.Instant.parse("2026-11-01T00:00:00Z")).toJson().toString()
        assertTrue(json, json.contains("\"limit\":0"))
        assertTrue(json, json.contains("\"negate\":true"))
        assertTrue(json, json.contains("\"field\":\"disaster.name\""))
    }
}
