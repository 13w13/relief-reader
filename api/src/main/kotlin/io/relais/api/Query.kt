// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A ReliefWeb filter: either one condition or a group of conditions. */
sealed interface Filter {
    fun toJson(): JsonObject

    /** `value == null` tests that the field exists, as for headlines. */
    data class Condition(
        val field: String,
        val value: JsonElement? = null,
        val operator: String? = null,
        val negate: Boolean = false,
    ) : Filter {
        override fun toJson(): JsonObject = buildJsonObject {
            put("field", field)
            if (value != null) put("value", value)
            if (operator != null) put("operator", operator)
            if (negate) put("negate", true)
        }
    }

    data class Group(
        val operator: String,
        val conditions: List<Filter>,
        val negate: Boolean = false,
    ) : Filter {
        override fun toJson(): JsonObject = buildJsonObject {
            put("operator", operator)
            put("conditions", JsonArray(conditions.map { it.toJson() }))
            if (negate) put("negate", true)
        }
    }
}

object Filters {
    fun exists(field: String): Filter = Filter.Condition(field)

    fun eq(field: String, value: Long): Filter = Filter.Condition(field, JsonPrimitive(value))

    /** Matches any of the text values, such as disaster statuses. */
    fun anyOfText(field: String, values: Collection<String>): Filter =
        if (values.size == 1) Filter.Condition(field, JsonPrimitive(values.first()))
        else Filter.Condition(field, JsonArray(values.map { JsonPrimitive(it) }), operator = "OR")

    /** Matches any of the values. One value becomes a plain equality. */
    fun anyOf(field: String, values: Collection<Long>): Filter =
        if (values.size == 1) eq(field, values.first())
        else Filter.Condition(field, JsonArray(values.map { JsonPrimitive(it) }), operator = "OR")

    fun not(filter: Filter): Filter = when (filter) {
        is Filter.Condition -> filter.copy(negate = true)
        is Filter.Group -> filter.copy(negate = true)
    }

    fun dateRange(field: String, from: Instant? = null, to: Instant? = null): Filter =
        Filter.Condition(field, buildJsonObject {
            if (from != null) put("from", ApiDates.format(from))
            if (to != null) put("to", ApiDates.format(to))
        })

    fun and(vararg filters: Filter?): Filter? = group("AND", filters.filterNotNull())

    fun or(vararg filters: Filter?): Filter? = group("OR", filters.filterNotNull())

    fun and(filters: List<Filter>): Filter? = group("AND", filters)

    private fun group(operator: String, filters: List<Filter>): Filter? = when (filters.size) {
        0 -> null
        1 -> filters.first()
        else -> Filter.Group(operator, filters)
    }
}

data class Facet(
    val field: String,
    val name: String? = null,
    val limit: Int? = null,
    val interval: String? = null,
    val sort: String? = null,
    /** Counts only the documents that match, for this facet alone. */
    val filter: Filter? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("field", field)
        if (filter != null) put("filter", filter.toJson())
        if (name != null) put("name", name)
        if (limit != null) put("limit", limit)
        if (interval != null) put("interval", interval)
        if (sort != null) put("sort", sort)
    }
}

/** Body of a POST request to a list endpoint. */
data class ApiQuery(
    val preset: String? = "latest",
    val limit: Int = 20,
    val offset: Int = 0,
    val sort: List<String> = Sorts.NEWEST,
    val filter: Filter? = null,
    val query: String? = null,
    val queryOperator: String = "AND",
    /** Fields the text query looks in; empty: ReliefWeb's default fields. */
    val queryFields: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val facets: List<Facet> = emptyList(),
    val slim: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (preset != null) put("preset", preset)
        put("limit", limit)
        if (offset > 0) put("offset", offset)
        if (sort.isNotEmpty()) put("sort", buildJsonArray { sort.forEach { add(JsonPrimitive(it)) } })
        if (filter != null) put("filter", filter.toJson())
        if (!query.isNullOrBlank()) put("query", buildJsonObject {
            put("value", query)
            put("operator", queryOperator)
            if (queryFields.isNotEmpty()) put("fields", buildJsonArray { queryFields.forEach { add(JsonPrimitive(it)) } })
        })
        if (fields.isNotEmpty()) put("fields", buildJsonObject {
            put("include", buildJsonArray { fields.forEach { add(JsonPrimitive(it)) } })
        })
        if (facets.isNotEmpty()) put("facets", JsonArray(facets.map { it.toJson() }))
        if (slim) put("slim", 1)
    }
}

object Sorts {
    val NEWEST = listOf("date.created:desc", "id:desc")
    val RELEVANT = listOf("score:desc", "date.created:desc")
}

/**
 * Field sets, named after what each screen shows (card, full report). Taxonomy objects are
 * requested whole (for example `file`, `headline`), which returns all their sub-fields.
 */
object Fields {
    val REPORT_CARD = listOf(
        "id", "title", "url_alias", "origin", "status", "date",
        "primary_country", "country", "source", "format", "theme", "language",
        "headline", "file", "image", "disaster",
    )
    val REPORT_FULL = REPORT_CARD + listOf("body-html")
    val DISASTER_CARD = listOf(
        "id", "name", "url_alias", "status", "glide", "date", "primary_type", "type", "primary_country", "country",
    )
    val COUNTRY_ROW = listOf("id", "name", "shortname", "iso3", "status", "url_alias")
}

/** ReliefWeb identifiers that the app needs (content formats, languages, countries). */
object Ids {
    const val FORMAT_ANALYSIS = 3L
    const val FORMAT_APPEAL = 4L
    const val FORMAT_ASSESSMENT = 5L
    const val FORMAT_EVALUATION = 6L
    const val FORMAT_GUIDELINE = 7L
    const val FORMAT_NEWS = 8L
    const val FORMAT_OTHER = 9L
    const val FORMAT_SITREP = 10L
    const val FORMAT_UN_DOCUMENT = 11L
    const val FORMAT_MAP = 12L
    const val FORMAT_INFOGRAPHIC = 12570L
    const val FORMAT_INTERACTIVE = 38974L

    const val LANGUAGE_ENGLISH = 267L
    const val LANGUAGE_FRENCH = 268L
    const val LANGUAGE_SPANISH = 269L
    const val LANGUAGE_ARABIC = 6876L

    const val COUNTRY_SUDAN = 220L

    val MAP_FORMATS = setOf(FORMAT_MAP, FORMAT_INFOGRAPHIC, FORMAT_INTERACTIVE)
}

/** Tabs of the country page (M8), with the formats each one covers. */
enum class CountryTab(val formatIds: List<Long>, val includesHeadlines: Boolean = false) {
    /**
     * Default view of a country (produit/filtres-following.md 2.1): situation reports, analyses,
     * assessments, evaluations, appeals, maps and infographics, plus any editors' headline.
     * News releases, guidelines, UN documents and other documents stay in [ALL].
     */
    KEY(
        listOf(
            Ids.FORMAT_SITREP, Ids.FORMAT_ANALYSIS, Ids.FORMAT_ASSESSMENT, Ids.FORMAT_EVALUATION,
            Ids.FORMAT_APPEAL, Ids.FORMAT_MAP, Ids.FORMAT_INFOGRAPHIC, Ids.FORMAT_INTERACTIVE,
        ),
        includesHeadlines = true,
    ),
    ALL(emptyList()),
    SITREPS(listOf(Ids.FORMAT_SITREP)),
    ANALYSIS(listOf(Ids.FORMAT_ANALYSIS, Ids.FORMAT_ASSESSMENT, Ids.FORMAT_EVALUATION)),
    MAPS(listOf(Ids.FORMAT_MAP, Ids.FORMAT_INFOGRAPHIC, Ids.FORMAT_INTERACTIVE)),
    NEWS(listOf(Ids.FORMAT_NEWS)),
    APPEALS(listOf(Ids.FORMAT_APPEAL)),
}

/**
 * Filters of a country page's sheet (produit/filtres-following.md), kept per country.
 * Several values of one filter are combined with OR.
 */
@Serializable
data class CountryFilter(
    val sources: Set<Long> = emptySet(),
    val languages: Set<Long> = emptySet(),
    val themes: Set<Long> = emptySet(),
    val postedDays: Int? = null,
) {
    val count: Int get() = sources.size + languages.size + themes.size + (if (postedDays != null) 1 else 0)
    val isEmpty: Boolean get() = count == 0

    /** Part of the cache key of a feed: one cached feed per combination. */
    fun signature(): String = if (isEmpty) "" else listOf(
        "s" + sources.sorted().joinToString("."),
        "l" + languages.sorted().joinToString("."),
        "t" + themes.sorted().joinToString("."),
        "d" + (postedDays ?: ""),
    ).joinToString("_")
}

object ApiDates {
    private val OUT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)

    /** The API expects `2026-11-07T00:00:00+00:00`: no `Z`, no fractions of a second. */
    fun format(instant: Instant): String = OUT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /** Parses an API date to epoch milliseconds, or null. */
    fun parse(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }
}
