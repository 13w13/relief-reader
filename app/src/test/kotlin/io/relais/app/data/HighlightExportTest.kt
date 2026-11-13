// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The example of produit/passe-v0.2.md, 5.2, with the real MSF report. */
class HighlightExportTest {
    private val day = 24 * 60 * 60 * 1000L
    private val nov4 = 1793750400000L // 4 Nov 2026, 00:00 UTC
    private val title = "Sudan: Vaccine-preventable disease outbreaks in Darfur - Findings from MSF-supported Health Facilities " +
        "(January 2024- April 2026)"
    private val url = "https://reliefweb.int/report/sudan/sudan-vaccine-preventable-disease-outbreaks-darfur-findings-msf-supported-" +
        "health-facilities-january-2024-april-2026"

    private val report = ReportEntity(
        id = 4237046, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = url, origin = null, dateCreated = nov4 + day, dateOriginal = nov4,
        primaryCountryId = 220, primaryCountryName = "Sudan", countries = emptyList(),
        sources = listOf(SourceLite(1503, "MSF", "Médecins Sans Frontières")), formatId = 3, formatName = "Analysis",
        themes = emptyList(), languages = listOf("en"), files = emptyList(), bodyHtml = null, isHeadline = false,
        fromRss = false, fetchedAt = nov4,
    )

    private fun highlight(id: Long, text: String, at: Long, block: Int = 3, page: Int? = null, note: String? = null) = HighlightEntity(
        id = id, reportId = report.id, reportTitle = title, countryName = "Sudan", sourceName = "MSF", formatName = "Analysis",
        reportDate = report.dateCreated, createdAt = at, text = text, note = note, blockIndex = block, start = 0, end = text.length,
        pdfPage = page,
    )

    @Test
    fun oneReportOneHighlight() {
        val h = highlight(1, "75 per cent of the patients treated for measles were not vaccinated", nov4 + 3 * day, note = "for the health note")
        val result = HighlightExport.build(listOf(h), mapOf(report.id to report), "Sudan", now = nov4 + 4 * day, zone = ZoneOffset.UTC)
        assertEquals("rel-highlights-sudan-2026-11-08.md", result.fileName)
        assertEquals(
            """
            # Highlights: Sudan, 7 November 2026

            Exported from Re.L on 8 Nov 2026: 1 report, 1 highlight.
            Reports belong to the organizations that published them. Links go to ReliefWeb.

            ## $title

            - Organization: Médecins Sans Frontières
            - Country: Sudan
            - Format: Analysis
            - Published: 4 Nov 2026. Posted on ReliefWeb: 5 Nov 2026.
            - Link: $url

            > 75 per cent of the patients treated for measles were not vaccinated

            Note: for the health note.
            Highlighted on 7 Nov 2026.

            """.trimIndent(),
            result.markdown,
        )
    }

    @Test
    fun passagesInTextOrderThenPdfPages() {
        val pdf = highlight(1, "Water trucking reached 4,200 people", nov4, block = 0, page = 9)
        val later = highlight(2, "second passage", nov4, block = 7)
        val earlier = highlight(3, "first passage", nov4, block = 2)
        val md = HighlightExport.build(listOf(pdf, later, earlier), emptyMap(), null, nov4, ZoneOffset.UTC).markdown
        assertTrue(md.startsWith("# Highlights, 4 November 2026"))
        assertTrue(md.indexOf("first passage") < md.indexOf("second passage"))
        assertTrue(md.indexOf("second passage") < md.indexOf("Water trucking"))
        assertTrue(md.contains("Highlighted on 4 Nov 2026, p. 9 of the PDF."))
        // Without the report on the phone: what the highlight stored.
        assertTrue(md.contains("- Organization: MSF"))
    }

    @Test
    fun periodsAndSlugs() {
        assertEquals("1 to 8 November 2026", HighlightExport.period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8)))
        assertEquals("28 October to 8 November 2026", HighlightExport.period(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 8)))
        assertEquals("central-eastern-africa-ebola-outbreak-ma", HighlightExport.slug("Central/Eastern Africa: Ebola Outbreak - May 2026"))
        assertEquals("all", HighlightExport.slug(null))
    }
}
