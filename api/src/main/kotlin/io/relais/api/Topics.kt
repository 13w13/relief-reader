// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Regional crises: ReliefWeb's topics (lot 3, equipe/produit/fiche-topics.md). Nobody in the team
 * has seen a real answer of the API for topics yet: everything here reads tolerantly. A field
 * missing or of another shape is ignored; a list (river) whose address the translation does not
 * understand is skipped, and the page says so.
 */

/** One list of a topic, as the site gives it: its title and its address on reliefweb.int. */
@Serializable
data class TopicRiver(val title: String, val url: String)

/** A topic as the app keeps it. */
@Serializable
data class TopicInfo(
    val id: Long,
    val title: String,
    val urlAlias: String? = null,
    val featured: Boolean = false,
    /** `date.changed`, for "Updated 12 Nov". */
    val changedAt: Long? = null,
    val introductionHtml: String? = null,
    val rivers: List<TopicRiver> = emptyList(),
)

object Topics {
    /** Every readable topic of a list answer (`{"data":[{"id":…,"fields":{…}}, …]}`). */
    fun list(answer: JsonElement): List<TopicInfo> =
        ((answer as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { item(it) }

    /** The topic of a one-item answer, or null. */
    fun one(answer: JsonElement): TopicInfo? = list(answer).firstOrNull()

    /** One item: an id (number or text) and its fields; anything missing or of another shape gives null. */
    fun item(element: JsonElement): TopicInfo? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return null
        return info(id, obj["fields"] as? JsonObject ?: return null)
    }

    private fun info(id: Long, f: JsonObject): TopicInfo? {
        val title = f.text("title")?.takeIf { it.isNotBlank() } ?: return null
        val date = f["date"] as? JsonObject
        return TopicInfo(
            id = id,
            title = title.trim(),
            urlAlias = f.text("url_alias"),
            featured = f.flag("featured"),
            changedAt = date?.text("changed")?.let { ApiDates.parse(it) },
            introductionHtml = f.text("introduction-html")?.takeIf { it.isNotBlank() },
            rivers = (f["rivers"] as? JsonArray).orEmpty().mapNotNull { river ->
                val r = river as? JsonObject ?: return@mapNotNull null
                val url = r.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TopicRiver(r.text("title")?.trim().orEmpty(), url.trim())
            },
        )
    }

    /**
     * The followed form of a topic: the OR of its lists of reports that need no search words.
     * A list without any condition (all of ReliefWeb) is left out of it.
     */
    fun followFilter(rivers: List<TopicRiver>): Filter? {
        val filters = rivers.mapNotNull { RiverQuery.parse(it.url) }.filter { it.query == null }.mapNotNull { it.filter }
        return when (filters.size) {
            0 -> null
            1 -> filters.single()
            else -> Filter.Group("OR", filters)
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }?.contentOrNull

    private fun JsonObject.flag(name: String): Boolean {
        val p = this[name] as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: p.longOrNull?.let { it != 0L } ?: (p.contentOrNull?.lowercase() in setOf("true", "yes", "1"))
    }
}

/**
 * A list of the site (`https://reliefweb.int/updates?view=…&advanced-search=…&search=…`) as an
 * API filter, in the site's own grammar (UN-OCHA/rwint9-site, reliefweb_rivers/src/
 * AdvancedSearch.php, parseAdvancedSearchParameter and getApiFilter; ReportRiver.php for the codes
 * and the views). Lists of other kinds (disasters, jobs, training) and unknown codes give null:
 * the list cannot be shown in the app.
 */
object RiverQuery {
    data class Parsed(val filter: Filter?, val query: String?)

    /** Map, infographic and interactive (ReportRiver.php, views "maps" and "reports"). */
    val MAP_FORMATS = listOf(12L, 12570L, 38974L)

    private val REFERENCES = mapOf(
        "PC" to "primary_country.id", "C" to "country.id", "S" to "source.id", "OT" to "source.type.id",
        "D" to "disaster.id", "DT" to "disaster_type.id", "T" to "theme.id", "F" to "format.id", "L" to "language.id",
    )
    private val DATES = mapOf("DO" to "date.original", "DA" to "date.created")

    private val OPERATORS = mapOf(
        "(" to "with", "!(" to "without", ")_(" to "and-with", ")_!(" to "and-without",
        ").(" to "or-with", ").!(" to "or-without", "." to "or", "_" to "and",
    )

    // The site's two patterns, as written in AdvancedSearch.php.
    private val VALID = Regex("""^(((^|[._])!?)\(([A-Z]+(-?\d+|\d+-\d*|[0-9a-z-]+)([._](?!\)))?)+\))+$""")
    private val TOKEN = Regex("""(!?\(|\)[._]!?\(|[._])([A-Z]+)(\d+-\d*|-?\d+|[0-9a-z-]+)""")

    fun parse(url: String): Parsed? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "reliefweb.int" && !host.endsWith(".reliefweb.int")) return null
        // Reports only: the river of updates.
        if (uri.path.orEmpty().trimEnd('/') != "/updates") return null
        val params = params(uri.rawQuery)
        val view = when (params["view"]?.trim().orEmpty()) {
            "", "all" -> null
            "headlines" -> Filters.exists("headline")
            "maps" -> Filters.anyOf("format.id", MAP_FORMATS)
            "reports" -> Filter.Condition("format.id", JsonArray(MAP_FORMATS.map { JsonPrimitive(it) }), operator = "OR", negate = true)
            else -> return null
        }
        val advanced = params["advanced-search"]?.trim()?.takeIf { it.isNotEmpty() }?.let { advancedFilter(it) ?: return null }
        val search = params["search"]?.trim()?.takeIf { it.isNotEmpty() }
        return Parsed(Filters.and(view, advanced), search)
    }

    private fun params(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { part ->
            val name = part.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val value = part.substringAfter('=', "")
            decode(name) to decode(value)
        }.toMap()

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** A group being built, as in getApiFilter: an operator, its conditions, a negation. */
    private class Group(var operator: String, val items: MutableList<Any> = mutableListOf(), val negate: Boolean = false)

    /** The `advanced-search` value as a filter, or null when it holds what the app cannot read. */
    fun advancedFilter(value: String): Filter? {
        if (!VALID.matches(value)) return null
        var root = Group("AND")
        var current: Group? = null
        for (m in TOKEN.findAll(value)) {
            val (prefix, code, raw) = m.destructured
            val operator = OPERATORS[prefix] ?: return null
            val condition = condition(code, raw) ?: return null
            var inner = if (operator == "or") "OR" else "AND"
            if ("with" in operator) {
                val group = Group("AND", negate = "out" in operator)
                inner = if ("or" in operator) "OR" else "AND"
                if (inner != root.operator) root = Group(inner, mutableListOf(root))
                root.items += group
                current = group
            }
            val group = current ?: return null
            group.operator = inner
            group.items += condition
        }
        return build(root)
    }

    private fun condition(code: String, raw: String): Filter? {
        REFERENCES[code]?.let { field -> return raw.toLongOrNull()?.let { Filters.eq(field, it) } }
        DATES[code]?.let { field -> return dates(field, raw) }
        return null
    }

    /** "20250101-20251231", "20250101-" (from), "-20251231" (until) or "20250101" (that day). */
    private fun dates(field: String, raw: String): Filter? {
        val parts = raw.split('-', limit = 2)
        fun day(s: String?) = s?.takeIf { it.length == 8 }?.let { runCatching { LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt()) }.getOrNull() }
        var from = day(parts.getOrNull(0))
        var to = if (parts.size == 1) from else day(parts.getOrNull(1))
        if (from == null && to == null) return null
        if (from != null && to != null && to.isBefore(from)) from = to.also { to = from }
        // The site's "to" is inclusive: the end of that day.
        return Filters.dateRange(
            field,
            from = from?.atStartOfDay()?.toInstant(ZoneOffset.UTC),
            to = to?.plusDays(1)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.minusSeconds(1),
        )
    }

    private fun build(group: Group): Filter? {
        val children = group.items.mapNotNull { if (it is Group) build(it) else it as? Filter }
        if (children.isEmpty()) return null
        if (children.size == 1 && !group.negate) return children.single()
        return Filter.Group(group.operator, children, negate = group.negate)
    }
}
