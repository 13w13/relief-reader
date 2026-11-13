// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** The two real examples of produit/passe-v0.2.md, 4.2. */
class CitationTest {
    private val day = 24 * 60 * 60 * 1000L
    private val nov4 = 1793750400000L // 4 Nov 2026, 00:00 UTC

    @Test
    fun passageOfAReport() {
        val title = "Sudan: Vaccine-preventable disease outbreaks in Darfur - Findings from MSF-supported Health Facilities " +
            "(January 2024- April 2026)"
        val url = "https://reliefweb.int/report/sudan/sudan-vaccine-preventable-disease-outbreaks-darfur-findings-msf-supported-" +
            "health-facilities-january-2024-april-2026"
        val source = CitationSource(listOf("Médecins Sans Frontières"), title, "Analysis", nov4, nov4 + day, url)
        assertEquals(
            "\"75 per cent of the patients treated for measles were not vaccinated\"\n" +
                "Médecins Sans Frontières, \"$title\", Analysis, published 4 Nov 2026, posted on ReliefWeb 5 Nov 2026.\n" +
                url,
            Citation.format("75 per cent of the patients treated\nfor measles were not vaccinated ", source, zone = ZoneOffset.UTC),
        )
    }

    @Test
    fun passageOfAPdfWithoutPublicationDate() {
        val source = CitationSource(listOf("UNHCR", "IOM", "WFP"), "Chad: Flash Update No. 3", "Situation Report", null, nov4 + 2 * day, null)
        assertEquals(
            "\"Funding stands at 12%.\"\nUNHCR, IOM, and WFP, \"Chad: Flash Update No. 3\", Situation Report, p. 3, posted on ReliefWeb 6 Nov 2026.",
            Citation.format("Funding stands at 12%.", source, page = 3, zone = ZoneOffset.UTC),
        )
    }

    @Test
    fun organizationNames() {
        assertEquals("ICRC and IFRC", Citation.names(listOf("ICRC", "IFRC")))
        assertEquals("OCHA, UNICEF, and WHO", Citation.names(listOf("OCHA", "UNICEF", "WHO")))
    }
}
