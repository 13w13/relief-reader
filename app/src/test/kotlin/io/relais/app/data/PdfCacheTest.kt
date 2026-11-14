// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cap of the PDF cache (issue 6) and the passes of the Wi-Fi preload, on made-up reports. */
class PdfCacheTest {
    private val cache = "/data/user/0/org.relreader.app/cache/pdf"
    private val kept = "/data/user/0/org.relreader.app/files/pdf"
    private val mb = 1_000_000L
    private val now = 1_763_100_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** A PDF on the phone, downloaded or last opened [hoursAgo] hours ago. */
    private fun pdf(name: String, reportId: Long, bytes: Long, hoursAgo: Long, dir: String = cache) =
        PdfEntity("https://example.org/$name.pdf", reportId, "$dir/$name.pdf", bytes, now - hoursAgo * hour)

    private fun report(id: Long, ageHours: Long, file: FileLite?) = ReportEntity(
        id = id, title = "Test report $id", headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = null, origin = null, dateCreated = now - ageHours * hour, dateOriginal = null,
        primaryCountryId = 1, primaryCountryName = "Testland", countries = listOf(Ref(1, "Testland")),
        sources = emptyList(), formatId = 10, formatName = null, themes = emptyList(), languages = listOf("English"),
        files = listOfNotNull(file), bodyHtml = null, isHeadline = false, fromRss = false, fetchedAt = now,
    )

    private fun file(name: String, size: Long?) = FileLite(url(name), "$name.pdf", size = size, mimetype = "application/pdf")
    private fun url(name: String) = "https://example.org/$name.pdf"
    private fun item(id: Long, bytes: Long?) = Preload.Item(id, url("f$id"), bytes)

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
        val pdfs = listOf(pdf("a", 1, 100 * mb, 10), pdf("b", 2, 100 * mb, 20))
        assertTrue(PdfCache.toEvict(pdfs, cache, 300 * mb, null, emptySet(), now).isEmpty())
    }

    @Test
    fun theLeastRecentlyUsedGoFirstUntilTheCacheIsUnderTheCap() {
        val pdfs = listOf(pdf("new", 3, 100 * mb, 10), pdf("old", 1, 100 * mb, 30), pdf("mid", 2, 100 * mb, 20))
        val evicted = PdfCache.toEvict(pdfs, cache, 150 * mb, null, emptySet(), now).map { it.reportId }
        assertEquals(listOf(1L, 2L), evicted)
    }

    @Test
    fun savedHighlightedJustDownloadedAndOpenPdfsStay() {
        val pdfs = listOf(
            pdf("saved", 1, 100 * mb, 40),
            pdf("highlighted", 2, 100 * mb, 30),
            pdf("plain", 3, 100 * mb, 20),
            pdf("just", 4, 100 * mb, 10),
            // Opened twenty minutes ago: on screen, or its text being read.
            PdfEntity(url("open"), 5, "$cache/open.pdf", 100 * mb, now - 20 * 60_000L),
        )
        val evicted = PdfCache.toEvict(pdfs, cache, 100 * mb, url("just"), setOf(1L, 2L), now)
        // Only "plain" may go, even though the cache stays over the cap.
        assertEquals(listOf(3L), evicted.map { it.reportId })
    }

    @Test
    fun pdfsOutsideTheCacheFolderNeitherCountNorGo() {
        val pdfs = listOf(
            pdf("kept", 1, 500 * mb, 30, dir = kept),
            // A sibling folder whose name starts like the cache folder's.
            pdf("sibling", 2, 500 * mb, 20, dir = "${cache}2"),
            pdf("cached", 3, 100 * mb, 10),
        )
        assertEquals(listOf(pdfs[2]), PdfCache.inCache(pdfs, cache))
        assertTrue(PdfCache.toEvict(pdfs, cache, 200 * mb, null, emptySet(), now).isEmpty())
        assertTrue(PdfCache.toEvict(pdfs, "$cache/", 200 * mb, null, emptySet(), now).isEmpty())
    }

    // --- What the preload looks at -------------------------------------------------------------

    @Test
    fun theNewestReportsComeFirstOnlyThoseOfTheLastSevenDaysAndEachFileOnce() {
        val shared = file("shared", 1 * mb)
        val reports = listOf(
            report(1, ageHours = 30, file = file("one", 2 * mb)),
            report(2, ageHours = 2, file = file("two", 2 * mb)),
            report(3, ageHours = 8 * 24, file = file("old", 2 * mb)),
            report(4, ageHours = 1, file = null),
            report(5, ageHours = 40, file = shared),
            report(6, ageHours = 50, file = shared),
        )
        assertEquals(listOf(2L, 1L, 5L), Preload.recent(reports, now).map { it.reportId })
    }

    @Test
    fun aPdfOnThePhoneWithoutItsTextIsReadAgain() {
        val recent = listOf(item(1, mb), item(2, mb), item(3, mb))
        val unread = Preload.unread(recent, onPhone = setOf(url("f1"), url("f2")), read = setOf(url("f1")))
        assertEquals(listOf(2L), unread.map { it.reportId })
    }

    @Test
    fun candidatesLeaveWhatIsOnThePhoneTooLargeOrFailedRecently() {
        val recent = listOf(item(1, mb), item(2, 26 * mb), item(3, null), item(4, mb), item(5, mb), item(6, mb))
        val failures = mapOf(
            url("f4") to Preload.Failure(url("f4"), now - 2 * hour, 1),
            url("f5") to Preload.Failure(url("f5"), now - 2 * day, 1),
            url("f6") to Preload.Failure(url("f6"), now - 2 * day, 2),
        )
        val candidates = Preload.candidates(recent, onPhone = setOf(url("f1")), failures = failures, now = now)
        // f2 is over 25 MB, f4 failed two hours ago, f6 failed twice; f5 failed two days ago, once.
        assertEquals(listOf(3L, 5L), candidates.map { it.reportId })
    }

    @Test
    fun aFailureWaitsADayAndTwoFailuresAreFinal() {
        assertTrue(Preload.mayTry(null, now))
        assertFalse(Preload.mayTry(Preload.Failure("u", now - 23 * hour, 1), now))
        assertTrue(Preload.mayTry(Preload.Failure("u", now - 25 * hour, 1), now))
        assertFalse(Preload.mayTry(Preload.Failure("u", now - 30 * day, 2), now))
    }

    @Test
    fun failuresAreCountedAFileTooLargeIsFinalAndOldOnesAreDropped() {
        var failures = Preload.withFailure(emptyMap(), "a", now - 2 * day, permanent = false)
        failures = Preload.withFailure(failures, "a", now, permanent = false)
        assertEquals(2, failures.getValue("a").count)
        assertEquals(now, failures.getValue("a").at)
        failures = Preload.withFailure(failures, "big", now, permanent = true)
        assertFalse(Preload.mayTry(failures["big"], now + 30 * day))
        val old = mapOf("gone" to Preload.Failure("gone", now - 9 * day, 1))
        assertEquals(setOf("new"), Preload.withFailure(old, "new", now, permanent = false).keys)
    }

    @Test
    fun failuresSurviveTheirFileAndABrokenLineIsSkipped() {
        val failures = listOf(Preload.Failure(url("a"), now, 1), Preload.Failure(url("b"), now - hour, 2))
        val text = Preload.encode(failures) + "\nnot a line\n\t\t\n"
        assertEquals(failures.associateBy { it.url }, Preload.decode(text))
    }

    // --- A pass ----------------------------------------------------------------------------------

    @Test
    fun aPassCountsThePdfsSavedNotTheTries() = runBlocking {
        val items = (1L..30L).map { item(it, mb) }
        val tried = ArrayList<Long>()
        // Every odd report fails: twelve failures do not stop the pass.
        val saved = Preload.run(items, room = 1000 * mb, stop = { false }) { item ->
            tried += item.reportId
            if (item.reportId % 2 == 1L) null else mb
        }
        assertEquals(Preload.MAX_FILES, saved)
        assertEquals((1L..24L).toList(), tried)
    }

    @Test
    fun aPassStopsAtItsTriesWhenEverythingFails() = runBlocking {
        val items = (1L..40L).map { item(it, mb) }
        var tries = 0
        val saved = Preload.run(items, room = 1000 * mb, stop = { false }) { tries++; null }
        assertEquals(0, saved)
        assertEquals(Preload.MAX_TRIES, tries)
    }

    @Test
    fun aPassFillsAtMostItsRoomAndAnUnknownSizeNeeds25Mb() = runBlocking {
        // Cap 300 MB: the preload may fill 240 MB; 200 MB are taken, 40 MB are left.
        val room = Preload.room(cacheBytes = 200 * mb, cap = 300 * mb)
        assertEquals(40 * mb, room)
        val items = listOf(item(1, null), item(2, null), item(3, 10 * mb), item(4, 20 * mb))
        val tried = ArrayList<Long>()
        // The file of unknown size turns out to weigh 20 MB: 20 MB are left, then 10.
        val saved = Preload.run(items, room, stop = { false }) { item -> tried += item.reportId; item.bytes ?: (20 * mb) }
        assertEquals(listOf(1L, 3L), tried)
        assertEquals(2, saved)
    }

    @Test
    fun aPassStopsWhenTheNetworkIsMeteredOrTheWorkerStopped() = runBlocking {
        val items = (1L..5L).map { item(it, mb) }
        var calls = 0
        val saved = Preload.run(items, room = 1000 * mb, stop = { calls >= 2 }) { calls++; mb }
        assertEquals(2, saved)
    }
}
