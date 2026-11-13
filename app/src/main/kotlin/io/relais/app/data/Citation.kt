// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a citation names (produit/passe-v0.2.md, 4.1). */
data class CitationSource(
    /** Long names of the organizations (`source.name`). */
    val organizations: List<String>,
    /** The document's own title, never the editors' headline title. */
    val title: String,
    /** The API's format name: "Situation Report", "Analysis". */
    val format: String?,
    /** `date.original`, then `date.created`. */
    val published: Long?,
    val posted: Long?,
    /** The report's page on ReliefWeb, never the PDF's address. */
    val url: String?,
)

fun ReportEntity.citationSource(): CitationSource = CitationSource(
    organizations = sources.map { it.name.ifBlank { it.shortname } }.filter { it.isNotBlank() },
    title = title,
    format = formatName,
    published = dateOriginal,
    posted = dateCreated,
    url = urlAlias,
)

/**
 * "Copy with citation" (produit/passe-v0.2.md, 4.1): three lines of plain text.
 *
 *     "75 per cent of the patients treated for measles were not vaccinated"
 *     Médecins Sans Frontières, "Sudan: …", Analysis, published 4 Nov 2026, posted on ReliefWeb 5 Nov 2026.
 *     https://reliefweb.int/report/sudan/…
 *
 * A passage of a PDF adds its page ("p. 3") after the format.
 */
object Citation {
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val SPACES = Regex("\\s+")

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = DATE.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "A", "A and B", "A, B, and C" (theme-compose.md 10.13). */
    fun names(values: List<String>): String = when (values.size) {
        0 -> ""
        1 -> values[0]
        2 -> values[0] + " and " + values[1]
        else -> values.dropLast(1).joinToString(", ") + ", and " + values.last()
    }

    fun format(passage: String, source: CitationSource, page: Int? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val quote = "\"" + passage.replace(SPACES, " ").trim() + "\""
        val reference = listOfNotNull(
            names(source.organizations).ifBlank { null },
            "\"" + source.title.replace(SPACES, " ").trim() + "\"",
            source.format?.takeIf { it.isNotBlank() },
            page?.let { "p. $it" },
            source.published?.let { "published ${date(it, zone)}" },
            source.posted?.let { "posted on ReliefWeb ${date(it, zone)}" },
        ).joinToString(", ") + "."
        return listOfNotNull(quote, reference, source.url).joinToString("\n")
    }
}
