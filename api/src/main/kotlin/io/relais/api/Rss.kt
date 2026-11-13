// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One item of a ReliefWeb RSS feed, used when the app has no appname. */
data class RssItem(
    val id: Long,
    val title: String,
    val link: String,
    val publishedAt: Long?,
    val countries: List<String>,
    val source: String?,
    val format: String?,
    val themes: List<String>,
    val summary: String?,
    val previewUrl: String?,
    val imageUrl: String?,
    val imageCaption: String?,
    val imageCopyright: String?,
    val enclosureUrl: String?,
    val enclosureLength: Long?,
    val enclosureType: String?,
)

object RssParser {
    const val HEADLINES_URL = "https://reliefweb.int/updates/rss.xml?view=headlines"

    fun parse(xml: String): List<RssItem> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null

            val descriptionHtml = item.selectFirst("description")?.text().orEmpty()
            val description = Jsoup.parseBodyFragment(descriptionHtml)
            val countries = description.select("div.tag.country").text()
                .substringAfter(":", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val sourceFromTag = description.select("div.tag.source").text().substringAfter(":", "").trim()
            val previewUrl = description.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            val summary = description.select("p").map { it.text().trim() }.lastOrNull { it.isNotEmpty() }

            val categories = item.select("category").map { it.text().trim() }.filter { it.isNotEmpty() }
            val format = categories.firstOrNull { it in FormatNames.ALL }
            val source = item.selectFirst("author")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: sourceFromTag.takeIf { it.isNotEmpty() }
            val themes = categories.filter { it != format && it != source && it !in countries }

            val media = item.selectFirst("media|content")
            val enclosure = item.selectFirst("enclosure")

            RssItem(
                id = syntheticId(link),
                title = title,
                link = link,
                publishedAt = parseDate(item.selectFirst("pubDate")?.text()),
                countries = countries,
                source = source,
                format = format,
                themes = themes,
                summary = summary,
                previewUrl = previewUrl,
                imageUrl = media?.attr("url")?.takeIf { it.isNotBlank() },
                imageCaption = media?.selectFirst("media|title")?.text()?.takeIf { it.isNotBlank() },
                imageCopyright = media?.selectFirst("media|copyright")?.text()?.takeIf { it.isNotBlank() },
                enclosureUrl = enclosure?.attr("url")?.takeIf { it.isNotBlank() },
                enclosureLength = enclosure?.attr("length")?.toLongOrNull(),
                enclosureType = enclosure?.attr("type")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * RSS items have no numeric id. A stable negative id derived from the link keeps them
     * apart from API documents, whose ids are positive.
     */
    fun syntheticId(link: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (b in link.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return -((hash ushr 1) or 1L)
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
}

/** Format names as the API and the RSS feed write them. */
object FormatNames {
    val BY_ID: Map<Long, String> = mapOf(
        Ids.FORMAT_ANALYSIS to "Analysis",
        Ids.FORMAT_APPEAL to "Appeal",
        Ids.FORMAT_ASSESSMENT to "Assessment",
        Ids.FORMAT_EVALUATION to "Evaluation and Lessons Learned",
        Ids.FORMAT_GUIDELINE to "Manual and Guideline",
        Ids.FORMAT_NEWS to "News and Press Release",
        Ids.FORMAT_OTHER to "Other",
        Ids.FORMAT_SITREP to "Situation Report",
        Ids.FORMAT_UN_DOCUMENT to "UN Document",
        Ids.FORMAT_MAP to "Map",
        Ids.FORMAT_INFOGRAPHIC to "Infographic",
        Ids.FORMAT_INTERACTIVE to "Interactive",
    )
    val ALL: Set<String> = BY_ID.values.toSet()
    val ID_BY_NAME: Map<String, Long> = BY_ID.entries.associate { (id, name) -> name to id }
}
