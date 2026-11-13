// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Export as Markdown" (produit/passe-v0.2.md, 5): the highlights left by the filters of
 * Library > Highlights, one section per report (latest publication first), its passages in the
 * order of the text, each a Markdown quote with its note and the day it was highlighted.
 */
object HighlightExport {
    data class Result(val fileName: String, val markdown: String, val reports: Int, val highlights: Int)

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private val FULL = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val DAY_ONLY = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    /** "1 to 8 November 2026", "28 October to 8 November 2026", "8 November 2026". */
    fun period(from: LocalDate, to: LocalDate): String = when {
        from == to -> FULL.format(to)
        from.year != to.year -> "${FULL.format(from)} to ${FULL.format(to)}"
        from.month != to.month -> "${DAY_MONTH.format(from)} to ${FULL.format(to)}"
        else -> "${DAY_ONLY.format(from)} to ${FULL.format(to)}"
    }

    /** "sudan" from "Sudan", "central-eastern-africa-ebola" from a crisis name. */
    fun slug(label: String?): String =
        label?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), "-")?.trim('-')?.take(40)?.trimEnd('-')?.ifBlank { null } ?: "all"

    /**
     * [filter] names what the filters keep ("Sudan"), or null for every highlight. [reports]
     * gives the reports still on the phone; a missing one falls back on what the highlight stored.
     */
    fun build(
        highlights: List<HighlightEntity>,
        reports: Map<Long, ReportEntity>,
        filter: String?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = day(now)
        val fileName = "rel-highlights-${slug(filter)}-${ISO.format(today)}.md"
        if (highlights.isEmpty()) return Result(fileName, "", 0, 0)

        val byReport = highlights.groupBy { it.reportId }
        fun published(id: Long): Long = reports[id]?.let { it.dateOriginal ?: it.dateCreated } ?: byReport.getValue(id).first().reportDate
        val order = byReport.keys.sortedByDescending(::published)
        val first = day(highlights.minOf { it.createdAt })
        val last = day(highlights.maxOf { it.createdAt })
        val reportCount = byReport.size

        val md = StringBuilder()
        md.append("# Highlights").append(filter?.let { ": $it" }.orEmpty()).append(", ").append(period(first, last)).append("\n\n")
        md.append("Exported from Re.L on ").append(Citation.date(now, zone)).append(": ")
            .append(count(reportCount, "report")).append(", ").append(count(highlights.size, "highlight")).append(".\n")
        md.append("Reports belong to the organizations that published them. Links go to ReliefWeb.\n")

        for (id in order) {
            val passages = byReport.getValue(id)
            val r = reports[id]
            val h0 = passages.first()
            md.append("\n## ").append(r?.title ?: h0.reportTitle).append("\n\n")
            val organizations = r?.sources?.map { it.name.ifBlank { it.shortname } }?.filter { it.isNotBlank() }
                ?: listOfNotNull(h0.sourceName)
            if (organizations.isNotEmpty()) md.append("- Organization: ").append(Citation.names(organizations)).append("\n")
            (r?.primaryCountryName ?: h0.countryName)?.let { md.append("- Country: ").append(it).append("\n") }
            (r?.formatName ?: h0.formatName)?.let { md.append("- Format: ").append(it).append("\n") }
            val posted = r?.dateCreated ?: h0.reportDate
            val dates = listOfNotNull(
                r?.dateOriginal?.let { "Published: ${Citation.date(it, zone)}." },
                "Posted on ReliefWeb: ${Citation.date(posted, zone)}.",
            ).joinToString(" ")
            md.append("- ").append(dates).append("\n")
            r?.urlAlias?.let { md.append("- Link: ").append(it).append("\n") }

            // The body first, in the order of its blocks; then the PDF, page by page.
            val sorted = passages.sortedWith(compareBy<HighlightEntity>({ it.pdfPage ?: 0 }, { it.blockIndex }, { it.start }))
            for (h in sorted) {
                md.append("\n> ").append(h.text.replace(Regex("\\s+"), " ").trim()).append("\n\n")
                h.note?.takeIf { it.isNotBlank() }?.let { md.append("Note: ").append(it.trim().removeSuffix(".")).append(".\n") }
                md.append("Highlighted on ").append(Citation.date(h.createdAt, zone))
                h.pdfPage?.let { md.append(", p. ").append(it).append(" of the PDF") }
                md.append(".\n")
            }
        }
        return Result(fileName, md.toString(), reportCount, highlights.size)
    }

    private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
