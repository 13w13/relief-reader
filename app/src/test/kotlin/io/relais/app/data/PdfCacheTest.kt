// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cap of the PDF cache (issue 6) and the plan of the Wi-Fi preload, on made-up reports. */
class PdfCacheTest {
    private val cache = "/data/user/0/org.relreader.app/cache/pdf"
    private val kept = "/data/user/0/org.relreader.app/files/pdf"
    private val mb = 1_000_000L
    private val now = 1_763_100_000_000L
    private val hour = 3_600_000L

    private fun pdf(name: String, reportId: Long, bytes: Long, at: Long, dir: String = cache) =
        PdfEntity("https://example.org/$name.pdf", reportId, "$dir/$name.pdf", bytes, at)

    private fun report(id: Long, ageHours: Long, file: FileLite?) = ReportEntity(
        id = id, title = "Test report $id", headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = now - ageHours * hour, dateOriginal = null,
        primaryCountryId = 1, primaryCountryName = "Testland", countries = listOf(Ref(1, "Testland")),
        sources = emptyList(), formatId = 10, formatName = null, themes = emptyList(), languages = listOf("English"),
        files = listOfNotNull(file), bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = now,
    )

    private fun file(name: String, size: Long?) = FileLite("https://example.org/$name.pdf", "$name.pdf", size = size, mimetype = "application/pdf")

    // --- The cap ---------------------------------------------------------------------------------

    @Test
    fun capsAreInDecimalMegabytesAndAnUnknownOneFallsBackToTheDefault() {
        assertEquals(300 * mb, PdfCache.capBytes(300))
        assertEquals(1000 * mb, PdfCache.capBytes(1000))
        assertEquals(PdfCache.DEFAULT_CAP_MB * mb, PdfCache.capBytes(42))
        assertEquals(listOf("200 MB", "300 MB", "500 MB", "1 GB"), PdfCache.CAPS_MB.map(PdfCache::capLabel))
    }

    @Test
    fun underTheCapNothingGoes() {
        val pdfs = listOf(pdf("a", 1, 100 * mb, 1), pdf("b", 2, 100 * mb, 2))
        assertTrue(PdfCache.toEvict(pdfs, cache, 300 * mb, null, emptySet()).isEmpty())
    }

    @Test
    fun theOldestGoFirstUntilTheCacheIsUnderTheCap() {
        val pdfs = listOf(pdf("new", 3, 100 * mb, 30), pdf("old", 1, 100 * mb, 10), pdf("mid", 2, 100 * mb, 20))
        val evicted = PdfCache.toEvict(pdfs, cache, 150 * mb, null, emptySet()).map { it.reportId }
        assertEquals(listOf(1L, 2L), evicted)
    }

    @Test
    fun savedHighlightedAndJustDownloadedPdfsStay() {
        val pdfs = listOf(
            pdf("saved", 1, 100 * mb, 10),
            pdf("highlighted", 2, 100 * mb, 20),
            pdf("plain", 3, 100 * mb, 30),
            pdf("just", 4, 100 * mb, 40),
        )
        val evicted = PdfCache.toEvict(pdfs, cache, 100 * mb, "https://example.org/just.pdf", setOf(1L, 2L))
        // Only "plain" may go, even though the cache stays over the cap.
        assertEquals(listOf(3L), evicted.map { it.reportId })
    }

    @Test
    fun pdfsOutsideTheCacheFolderNeitherCountNorGo() {
        val pdfs = listOf(
            pdf("kept", 1, 500 * mb, 1, dir = kept),
            // A sibling folder whose name starts like the cache folder's.
            pdf("sibling", 2, 500 * mb, 2, dir = "${cache}2"),
            pdf("cached", 3, 100 * mb, 3),
        )
        assertEquals(listOf(pdfs[2]), PdfCache.inCache(pdfs, cache))
        assertTrue(PdfCache.toEvict(pdfs, cache, 200 * mb, null, emptySet()).isEmpty())
        assertTrue(PdfCache.toEvict(pdfs, "$cache/", 200 * mb, null, emptySet()).isEmpty())
    }

    // --- The plan of the Wi-Fi preload ---------------------------------------------------------

    @Test
    fun theNewestReportsComeFirstAndOnlyThoseOfTheLastSevenDays() {
        val reports = listOf(
            report(1, ageHours = 30, file = file("one", 2 * mb)),
            report(2, ageHours = 2, file = file("two", 2 * mb)),
            report(3, ageHours = 8 * 24, file = file("old", 2 * mb)),
        )
        val plan = Preload.plan(reports, emptySet(), 0, 300 * mb, now)
        assertEquals(listOf(2L, 1L), plan.map { it.reportId })
        assertEquals(2 * mb, plan.first().bytes)
    }

    @Test
    fun reportsWithoutPdfAlreadyDownloadedOrTwiceTheSameFileAreSkipped() {
        val shared = file("shared", 1 * mb)
        val reports = listOf(
            report(1, 1, null),
            report(2, 2, file("done", 1 * mb)),
            report(3, 3, shared),
            report(4, 4, shared),
        )
        val plan = Preload.plan(reports, setOf("https://example.org/done.pdf"), 0, 300 * mb, now)
        assertEquals(listOf(3L), plan.map { it.reportId })
    }

    @Test
    fun aPdfOver25MbWaitsForTheReader() {
        val reports = listOf(report(1, 1, file("big", 26 * mb)), report(2, 2, file("small", 3 * mb)))
        assertEquals(listOf(2L), Preload.plan(reports, emptySet(), 0, 300 * mb, now).map { it.reportId })
    }

    @Test
    fun thePreloadFillsAtMostEightyPercentOfTheCapAndCountsAnUnknownSizeAs25Mb() {
        // Cap 300 MB: the preload may fill 240 MB, 200 MB are taken, 40 MB are left.
        val reports = listOf(
            report(1, 1, file("unknown", null)),
            report(2, 2, file("unknown2", null)),
            report(3, 3, file("ten", 10 * mb)),
            report(4, 4, file("twenty", 20 * mb)),
        )
        val plan = Preload.plan(reports, emptySet(), 200 * mb, 300 * mb, now)
        // 25 MB counted for the first unknown size, then 10 MB: 5 MB are left for the others.
        assertEquals(listOf(1L, 3L), plan.map { it.reportId })
        assertEquals(null, plan.first().bytes)
    }

    @Test
    fun aFullCacheGetsNothing() {
        val reports = listOf(report(1, 1, file("one", 1 * mb)))
        assertTrue(Preload.plan(reports, emptySet(), 250 * mb, 300 * mb, now).isEmpty())
    }

    @Test
    fun atMostTwelveFilesARun() {
        val reports = (1L..20L).map { report(it, it, file("f$it", 1 * mb)) }
        val plan = Preload.plan(reports, emptySet(), 0, 1000 * mb, now)
        assertEquals(Preload.MAX_FILES, plan.size)
        assertEquals((1L..12L).toList(), plan.map { it.reportId })
    }
}
