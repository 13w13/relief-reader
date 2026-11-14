// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Library in one file (lot 3): the round trip from one phone to another, a merge that
 * deletes nothing, and a file that never holds the appname. Made-up data.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LibraryBackupTest {
    private lateinit var old: RelaisDatabase
    private lateinit var new: RelaisDatabase
    private val now = 1_794_614_400_000L
    private val day = 86_400_000L

    private val nco = SourceLite(990101, "NCO", "Noravia Coordination Office")

    private fun report(id: Long, title: String, body: String? = null) = ReportEntity(
        id = id, title = title, headlineTitle = null, headlineSummary = null, imageUrl = null, imageCaption = null,
        imageCopyright = null, urlAlias = "https://relais.test/report/$id", origin = null, dateCreated = now - id * day,
        dateOriginal = null, primaryCountryId = 990, primaryCountryName = "Noravia", countries = listOf(Ref(990, "Noravia")),
        sources = listOf(nco), formatId = 10, formatName = "Situation Report", themes = emptyList(), languages = listOf("English"),
        files = emptyList(), bodyHtml = body, isHeadline = false, fromRss = false, fetchedAt = now,
    )

    private fun highlight(reportId: Long, start: Int, text: String, note: String? = null) = HighlightEntity(
        reportId = reportId, reportTitle = "Noravia: Situation Report No. $reportId", countryName = "Noravia", sourceName = "NCO",
        formatName = "Situation Report", reportDate = now - reportId * day, createdAt = now - day, text = text, note = note,
        blockIndex = 2, start = start, end = start + text.length,
    )

    private fun memory(): RelaisDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RelaisDatabase::class.java)
            .allowMainThreadQueries().build()

    @Before
    fun open() {
        old = memory()
        new = memory()
    }

    @After
    fun close() {
        old.close()
        new.close()
    }

    /** The old phone: 1 saved, 2 in history, 3 opened once and in neither, a highlight, three follows. */
    private suspend fun fillOldPhone() {
        val dao = old.dao()
        dao.upsertReports(listOf(report(1, "Noravia: Situation Report No. 1", "<p>Text</p>"), report(2, "Noravia: Situation Report No. 2"), report(3, "Noravia: Situation Report No. 3")))
        dao.upsertReading(ReadingEntity(reportId = 1, savedAt = now - day, firstOpenedAt = now - 2 * day, lastOpenedAt = now - day, percent = 40))
        dao.upsertReading(ReadingEntity(reportId = 2, inHistory = true, firstOpenedAt = now - 3 * day, lastOpenedAt = now - 3 * day, percent = 100, finishedAt = now - 3 * day))
        dao.upsertReading(ReadingEntity(reportId = 3, readAt = now - day))
        dao.insertHighlight(highlight(1, 10, "Floods displaced about 8,000 people.", note = "for the brief"))
        dao.upsertFollowed(FollowedCountryEntity(990, "Noravia", now - 30 * day, now - day))
        dao.upsertFollowedDisaster(FollowedDisasterEntity(52001, "Noravia: Floods - Nov 2026", now - 10 * day, now - day, glide = "FL-2026-000123-NOR"))
        dao.upsertFollowedSource(FollowedSourceEntity(990101, "NCO", "Noravia Coordination Office", reportsOnly = true, followedAt = now - 5 * day, lastVisitAt = now - day))
    }

    @Test
    fun theFileHoldsTheLibraryAndNeverTheAppname() = runBlocking {
        fillOldPhone()
        val backup = LibraryBackupStore(old.dao()).export(now)
        val json = LibraryBackupFormat.encode(backup)
        assertTrue(json, json.contains("\"app\":\"Re.L\"") && json.contains("\"kind\":\"library-backup\"") && json.contains("\"version\":1"))
        // Saved and History only: report 3, merely read, stays out.
        assertEquals(listOf(1L, 2L), backup.readings.map { it.reportId }.sorted())
        assertEquals(LibraryBackup.Counts(saved = 1, history = 1, highlights = 1, followed = 3), backup.counts)
        // No appname, no full text, no PDF.
        assertFalse(json, json.contains("appname", ignoreCase = true))
        assertFalse(json, json.contains("<p>Text</p>"))
        assertFalse(json, json.contains("bodyHtml"))
    }

    @Test
    fun aRestoreMergesAndDeletesNothing() = runBlocking {
        fillOldPhone()
        val file = LibraryBackupFormat.encode(LibraryBackupStore(old.dao()).export(now))

        // The new phone already has report 2, saved but not in history, the same highlight, a
        // highlight of its own, and Noravia followed since yesterday.
        val dao = new.dao()
        dao.upsertReports(listOf(report(2, "Noravia: Situation Report No. 2", "<p>Full text</p>")))
        dao.upsertReading(ReadingEntity(reportId = 2, savedAt = now - 2 * day, lastOpenedAt = now - 2 * day, percent = 20))
        dao.insertHighlight(highlight(1, 10, "Floods displaced about 8,000 people.", note = "for the brief"))
        dao.insertHighlight(highlight(2, 50, "Water trucking reached 12,400 people."))
        dao.upsertFollowed(FollowedCountryEntity(990, "Noravia", now - day, now - day))

        val parsed = LibraryBackupFormat.parse(file) as LibraryBackupFormat.Parsed.Ok
        LibraryBackupStore(dao).restore(parsed.backup, now)

        // Report 1 comes back as a card, saved, its text to load on opening.
        val one = dao.reportsByIds(listOf(1L)).single()
        assertEquals("Noravia: Situation Report No. 1", one.title)
        assertNull(one.bodyHtml)
        assertNotNull(dao.reading(1)?.savedAt)
        // Report 2 keeps the phone's full copy and saved date, and joins History.
        assertEquals("<p>Full text</p>", dao.reportsByIds(listOf(2L)).single().bodyHtml)
        val two = dao.reading(2)!!
        assertEquals(now - 2 * day, two.savedAt)
        assertTrue(two.inHistory)
        assertEquals(100, two.percent)
        // The same highlight once; the new phone's own highlight stays.
        assertEquals(2, dao.allHighlights().size)
        // Noravia stays as it was; the crisis and the organization arrive, 7 days counted as new.
        assertEquals(now - day, dao.followedCountry(990)!!.followedAt)
        assertEquals(now - Repository.SEVEN_DAYS, dao.followedDisaster(52001)!!.lastVisitAt)
        assertTrue(dao.followedSource(990101)!!.reportsOnly)

        // A second restore of the same file changes nothing.
        LibraryBackupStore(dao).restore(parsed.backup, now)
        assertEquals(2, dao.allHighlights().size)
        assertEquals(2, dao.libraryReadings().size)
    }

    @Test
    fun anotherFileOrANewerBackupIsRefused() {
        assertEquals(LibraryBackupFormat.Parsed.NotBackup, LibraryBackupFormat.parse("not json"))
        assertEquals(LibraryBackupFormat.Parsed.NotBackup, LibraryBackupFormat.parse("""{"app":"Other","kind":"library-backup","version":1,"exportedAt":0}"""))
        assertEquals(LibraryBackupFormat.Parsed.Newer, LibraryBackupFormat.parse("""{"app":"Re.L","kind":"library-backup","version":2,"exportedAt":0}"""))
        // An unknown field of the same version is ignored.
        val ok = LibraryBackupFormat.parse("""{"app":"Re.L","kind":"library-backup","version":1,"exportedAt":5,"topics":[{"id":1}]}""")
        assertEquals(5L, (ok as LibraryBackupFormat.Parsed.Ok).backup.exportedAt)
    }

    @Test
    fun theFileIsNamedAfterItsDay() {
        assertEquals("rel-library-2026-11-14.json", LibraryBackupFormat.fileName(now, java.time.ZoneOffset.UTC))
    }
}
