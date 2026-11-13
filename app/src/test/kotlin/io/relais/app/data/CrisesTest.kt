// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiResponse
import io.relais.api.DisasterFields
import io.relais.api.RelaisJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrisesTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_762_500_000_000L
    private val ocha = SourceLite(1503, "OCHA", "UN Office for the Coordination of Humanitarian Affairs")
    private val unhcr = SourceLite(2868, "UNHCR", "UN High Commissioner for Refugees")

    private fun report(
        id: Long,
        title: String = "Report $id",
        language: String = "English",
        source: SourceLite = unhcr,
        formatId: Long = 12,
        countryId: Long = 51,
        at: Long = now,
    ) = ReportEntity(
        id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = at, dateOriginal = null,
        primaryCountryId = countryId, primaryCountryName = "Chad", countries = emptyList(), sources = listOf(source),
        formatId = formatId, formatName = null, themes = emptyList(), languages = listOf(language), files = emptyList(),
        bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = now,
    )

    private fun map(id: Long, title: String, language: String, file: String, pages: Long = 1, at: Long = now) =
        report(id, title, language, at = at).copy(files = listOf(FileLite("https://x/$file", file, 100_000, pages, "application/pdf")))

    @Test
    fun mapInTwoLanguagesBecomesOneLine() {
        // S3: the same file name once "EN" and "FR" are removed, and the same page count.
        val fr = map(2, "UNHCR Tchad | Cartographie des sites", "fr", "UNHCR_TCD_Sites_Mapping_FR.pdf", at = now - hour)
        val en = map(1, "UNHCR Chad | Mapping of sites", "en", "UNHCR_TCD_Sites_Mapping_EN.pdf")
        val other = map(3, "Chad: Refugee arrivals", "en", "arrivals.pdf", at = now - 5 * hour)
        val groups = LanguageTwins.group(listOf(fr, en, other))
        assertEquals(2, groups.size)
        assertEquals(1L, groups[0].main.id)
        assertEquals(listOf(2L), groups[0].others.map { it.id })
        assertEquals(3L, groups[1].main.id)
    }

    @Test
    fun sitrepTwinsByTheNumbersOfTitleAndPages() {
        // S2: "No. 3" and "27 October 2026" in both titles, same page count.
        val en = map(1, "Haiti: Kenscoff - Situation Report No. 3 (27 October 2026)", "en", "a.pdf", pages = 4)
        val fr = map(2, "Haïti : Kenscoff - Rapport de situation n° 3 (27 octobre 2026)", "fr", "b.pdf", pages = 4)
        assertEquals(listOf("S2"), LanguageTwins.signals(en, fr) { emptySet() })
        assertEquals(listOf("3", "27", "m10", "2026"), LanguageTwins.titleKey("Rapport de situation n° 3 (27 octobre 2026)"))
        // Version 2: 15 % apart is fine from 10 pages on (26 and 23 pages), not below.
        assertEquals(listOf("S2"), LanguageTwins.signals(en.withPages(26), fr.withPages(23)) { emptySet() })
        assertEquals(emptyList<String>(), LanguageTwins.signals(en.withPages(4), fr.withPages(5)) { emptySet() })
        // More than 20 % apart without S1: vetoed.
        assertTrue(LanguageTwins.vetoed(en.withPages(4), fr.withPages(9), listOf("S2")))
        assertFalse(LanguageTwins.vetoed(en.withPages(7), fr.withPages(9), listOf("S1")))
    }

    private fun ReportEntity.withPages(n: Long) = copy(files = listOf(files[0].copy(pages = n)))

    @Test
    fun shortPressReleaseTwinsByTheNumbersOfTheText() {
        // Version 2 of S1: three numbers in common and half of them, for short releases.
        val es = report(1, "CICR: comunicado", "es", formatId = 8).copy(bodyHtml = "<p>2026: 2.700 personas, 22.730 hogares y 34 centros</p>")
        val en = report(2, "ICRC: press release", "en", formatId = 8).copy(bodyHtml = "<p>2026: 2,700 people, 22,730 households, 34 centres, 43 staff</p>")
        assertEquals(setOf("2026", "2700", "22730", "34", "43"), LanguageTwins.numbers(en.bodyHtml))
        val groups = LanguageTwins.group(listOf(es, en))
        assertEquals(1, groups.size)
        assertEquals(2L, groups[0].main.id)
    }

    @Test
    fun noMergeWhenInDoubt() {
        val en = map(1, "UNHCR Chad | Mapping of sites", "en", "UNHCR_TCD_Sites_Mapping_EN.pdf")
        val fr = map(2, "UNHCR Tchad | Cartographie", "fr", "UNHCR_TCD_Sites_Mapping_FR.pdf")
        // No strong signal: candidates only.
        assertTrue(LanguageTwins.pairs(listOf(report(1, language = "en"), report(2, language = "fr"))).isEmpty())
        // More than three days apart, another organization, another format, a language in common.
        assertFalse(LanguageTwins.isCandidate(en, fr.copy(dateCreated = now - 4 * 24 * hour)))
        assertFalse(LanguageTwins.isCandidate(en, fr.copy(sources = listOf(ocha))))
        assertFalse(LanguageTwins.isCandidate(en, fr.copy(formatId = 10)))
        assertFalse(LanguageTwins.isCandidate(en, fr.copy(languages = listOf("fr", "en"))))
        // "ot" (other language) does not count as a language in common.
        assertTrue(LanguageTwins.isCandidate(en.copy(languages = listOf("en", "ot")), fr.copy(languages = listOf("fr", "ot"))))
        // A long report facing a text with no file: vetoed.
        assertTrue(LanguageTwins.vetoed(en.withPages(95), fr.copy(files = emptyList()), listOf("S1")))
        // Two French matches for one English document: ambiguous, no merge.
        val fr2 = map(3, "UNHCR Tchad | Cartographie bis", "fr", "UNHCR_TCD_Sites_Mapping_FR.pdf")
        assertTrue(LanguageTwins.group(listOf(en, fr, fr2)).all { it.others.isEmpty() })
    }

    @Test
    fun pinnedOchaOverviewByItsTitleAsInChad() {
        // passe-v0.2.md 3.2: OCHA's overview for Chad is in the Other format; its title pins it.
        val day = 24 * hour
        val apercu = report(1, "TCHAD - Aperçu de la situation humanitaire à l'Est (Au 03 Novembre 2026)", source = ocha, formatId = 9, at = now - day)
        assertEquals(listOf(PinKind.OCHA to 1L), Pinned.pick(listOf(report(2, at = now), apercu), now).map { it.kind to it.report.id })
        val humanitarian = report(3, "South Sudan Humanitarian Update (as of 30 October 2026)", source = ocha, formatId = 12570, at = now - 2 * day)
        assertTrue(Pinned.isOchaUpdate(humanitarian))
        assertFalse(Pinned.isOchaUpdate(report(4, "Chad: Displacement map", source = ocha, formatId = 12)))
    }

    @Test
    fun pinnedOchaUpdateThenNewAppeals() {
        val day = 24 * hour
        val old = report(1, "Chad: Flash Update No. 2", source = ocha, formatId = 8, at = now - 40 * day)
        val flash = report(2, "Chad: Flash Update No. 3", source = ocha, formatId = 8, at = now - 2 * day)
        val sitrep = report(3, "Chad: Situation Report", source = ocha, formatId = 10, at = now - 5 * day)
        val appeal = report(4, "Chad: Flash Appeal", formatId = 4, at = now - day)
        val lateAppeal = report(6, "Chad: Plan 2025", formatId = 4, at = now - day).copy(dateOriginal = now - 200 * day)
        val news = report(5, formatId = 8, at = now)
        val pins = Pinned.pick(listOf(news, appeal, lateAppeal, sitrep, flash, old), now)
        assertEquals(listOf(PinKind.OCHA to 2L, PinKind.APPEAL to 4L), pins.map { it.kind to it.report.id })
        // "Hide" sends the OCHA update back to the list: the next one fills the slot.
        assertEquals(3L, Pinned.pick(listOf(sitrep, flash), now, hidden = setOf(2L)).first().report.id)
    }

    @Test
    fun onACrisisPageOchaIsPinnedOnlyWhenAboutTheCrisis() {
        // revue-lecteur-v0.2.md, defect 5: a general update on South Sudan sat on top of the Ebola page.
        val day = 24 * hour
        val ebola = DisasterInfo(52586, "Central/Eastern Africa: Ebola Outbreak - May 2026", primaryCountryId = 75)
        val southSudan = report(1, "South Sudan Humanitarian Update (as of 30 October 2026)", source = ocha, formatId = 10, countryId = 8657, at = now - day)
        val ebolaFlash = report(2, "DRC: Ebola Outbreak - Flash Update No. 12", source = ocha, formatId = 8, countryId = 75, at = now - 2 * day)
        assertEquals(listOf(2L), Pinned.pick(listOf(southSudan, ebolaFlash), now, crisis = ebola).map { it.report.id })
        assertTrue(Pinned.aboutCrisis(report(3, "Uganda: Ebola situation report", source = ocha, countryId = 999), ebola))
        assertFalse(Pinned.aboutCrisis(southSudan, ebola))
        // On a country page, no such filter.
        assertEquals(listOf(1L), Pinned.pick(listOf(southSudan, ebolaFlash), now).map { it.report.id })
    }

    @Test
    fun pinnedFallsBackOnAnyFlashUpdate() {
        val day = 24 * hour
        val unhcr = report(1, "UNHCR Flash Update #1 – Blue Nile State", formatId = 10, at = now - 6 * day)
        val pins = Pinned.pick(listOf(report(2, at = now), unhcr), now)
        assertEquals(listOf(PinKind.FLASH_UPDATE to 1L), pins.map { it.kind to it.report.id })
        assertEquals(emptyList<Pin>(), Pinned.pick(listOf(unhcr.copy(dateCreated = now - 20 * day)), now))
    }

    @Test
    fun disastersOfTheFixtures() {
        val response = RelaisJson.decodeFromString(
            ApiResponse.serializer(DisasterFields.serializer()),
            File("../fixtures/disasters-current.json").readText(),
        )
        // The file lists disasters that are not over; it changes with each refresh.
        val infos = response.data.map { it.toInfo() }
        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.name.isNotBlank() && it.isOpen && it.countries.isNotEmpty() })
        assertTrue(infos.any { it.typeName != null })
        assertTrue(infos.all { it.createdAt != null })
    }
}
