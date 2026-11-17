// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The rules of the Library's folders (17/11, fiche-dossiers-library.md), on a database in memory. Made-up data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FoldersTest {
    private lateinit var db: RelaisDatabase
    private lateinit var folders: Folders
    private val dao get() = db.dao()
    private var now = 1_794_614_400_000L
    private val relocated = mutableListOf<Pair<Long, Boolean>>()

    private fun report(id: Long, rss: Boolean = false) = ReportEntity(
        id = id, title = "Noravia: Situation Report No. $id", headlineTitle = null, headlineSummary = null, imageUrl = null,
        imageCaption = null, imageCopyright = null, urlAlias = null, origin = null, dateCreated = now - id * 86_400_000L,
        dateOriginal = null, primaryCountryId = 990, primaryCountryName = "Noravia", countries = listOf(Ref(990, "Noravia")),
        sources = listOf(SourceLite(990101, "NCO", "Noravia Coordination Office")), formatId = 10, formatName = "Situation Report",
        themes = emptyList(), languages = listOf("English"), files = emptyList(), bodyHtml = "<p>Text</p>", isHeadline = false,
        fromRss = rss, fetchedAt = 0,
    )

    @Before
    fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RelaisDatabase::class.java)
            .allowMainThreadQueries().build()
        folders = Folders(dao, transaction = { block -> db.withTransaction { block() } }, relocate = { id, keep -> relocated += id to keep }, now = { now })
        dao.upsertReports(listOf(report(1), report(2), report(3), report(-77, rss = true)))
        dao.upsertReading(ReadingEntity(1, savedAt = 100))
        dao.upsertReading(ReadingEntity(2, lastOpenedAt = 200, inHistory = true))
    }

    @After
    fun close() = db.close()

    private suspend fun create(name: String): Long = (folders.create(name) as Folders.NameCheck.Created).id

    @Test
    fun aNameIsCleanedCutAndNeverTwice() = runBlocking {
        assertEquals(Folders.NameCheck.Empty, folders.create("   "))
        assertEquals(Folders.NameCheck.Empty, folders.create(""))
        val chad = folders.create("  Chad \n 2027  ") as Folders.NameCheck.Created
        assertEquals("Chad 2027", chad.name)
        // The same name whatever the capitals: refused, the folder named.
        assertEquals(Folders.NameCheck.Taken(chad.id), folders.create("CHAD 2027"))
        assertEquals(Folders.NameCheck.Taken(chad.id), folders.create("chad 2027 "))
        // 60 characters at most.
        val long = folders.create("x".repeat(100)) as Folders.NameCheck.Created
        assertEquals(60, long.name.length)
        // Renamed: to its own name in other capitals is fine, to another folder's is not.
        assertEquals(Folders.NameCheck.Ok("chad 2027"), folders.rename(chad.id, "chad 2027"))
        assertEquals(Folders.NameCheck.Taken(chad.id), folders.rename(long.id, "Chad 2027"))
        assertEquals(Folders.NameCheck.Empty, folders.rename(long.id, " "))
    }

    @Test
    fun puttingAReportInAFolderSavesIt() = runBlocking {
        val echo = create("ECHO proposal")
        // 1 is saved already, 2 only in History: 2 is saved by this, and its PDFs kept.
        assertEquals(1, folders.add(echo, listOf(1, 2)))
        assertNotNull(dao.reading(2)?.savedAt)
        assertEquals(100L, dao.reading(1)?.savedAt)
        assertEquals(listOf(2L to true), relocated)
        // 3 had no reading at all: saved too.
        assertEquals(1, folders.add(echo, listOf(3)))
        assertNotNull(dao.reading(3)?.savedAt)
        assertEquals(setOf(1L, 2L, 3L), dao.observeFolderReadings(echo).first().map { it.reading.reportId }.toSet())
        // Twice in the same folder: once.
        assertEquals(0, folders.add(echo, listOf(1)))
        assertEquals(3, dao.observeFolders().first().single().reports)
    }

    @Test
    fun aReportOfTheFeedIsNeverPutInAFolder() = runBlocking {
        val echo = create("ECHO proposal")
        assertEquals(0, folders.add(echo, listOf(-77)))
        assertTrue(dao.observeFolderReadings(echo).first().isEmpty())
        assertNull(dao.reading(-77))
    }

    @Test
    fun aReportMayBeInSeveralFolders() = runBlocking {
        val chad = create("Chad 2027")
        val echo = create("ECHO proposal")
        folders.add(chad, listOf(1))
        folders.add(echo, listOf(1))
        assertEquals(setOf(chad, echo), dao.observeFolderIdsOfReport(1).first().toSet())
    }

    @Test
    fun removedFromSavedLeavesItsFoldersUndoPutsBackBoth() = runBlocking {
        val chad = create("Chad 2027")
        val echo = create("ECHO proposal")
        folders.add(chad, listOf(1))
        folders.add(echo, listOf(1))
        val removed = folders.unsave(1)
        assertEquals(2, removed.folders.size)
        assertNull(dao.reading(1)?.savedAt)
        assertTrue(dao.observeFolderIdsOfReport(1).first().isEmpty())
        folders.undo(removed)
        assertEquals(100L, dao.reading(1)?.savedAt)
        assertEquals(setOf(chad, echo), dao.observeFolderIdsOfReport(1).first().toSet())
    }

    @Test
    fun outOfAFolderOrAFolderDeletedTheReportsStayInSaved() = runBlocking {
        val chad = create("Chad 2027")
        folders.add(chad, listOf(1, 2))
        folders.remove(chad, 1)
        assertEquals(100L, dao.reading(1)?.savedAt)
        assertEquals(listOf(2L), dao.observeFolderReadings(chad).first().map { it.reading.reportId })
        folders.delete(chad)
        assertTrue(dao.observeFolders().first().isEmpty())
        assertTrue(dao.allFolderReports().isEmpty())
        assertNotNull(dao.reading(2)?.savedAt)
    }

    @Test
    fun countsAndOrder() = runBlocking {
        val chad = create("Chad 2027")
        now += 1_000
        val echo = create("ECHO proposal")
        dao.insertHighlight(
            HighlightEntity(
                reportId = 1, reportTitle = "t", countryName = null, sourceName = null, formatName = null, reportDate = 0, createdAt = 0,
                text = "A passage", note = null, blockIndex = 0, start = 0, end = 9,
            ),
        )
        now += 1_000
        folders.add(chad, listOf(1, 2))
        // The folder changed last comes first.
        val rows = dao.observeFolders().first()
        assertEquals(listOf(chad, echo), rows.map { it.id })
        assertEquals(2, rows.first().reports)
        assertEquals(1, rows.first().highlights)
        assertEquals(0, rows.last().reports)
    }

    @Test
    fun theCleanupNeverDeletesAReportInAFolder() = runBlocking {
        val chad = create("Chad 2027")
        folders.add(chad, listOf(1))
        // Even if its reading were gone, its place in a folder keeps it.
        db.openHelper.writableDatabase.execSQL("DELETE FROM reading WHERE reportId = 1")
        dao.deleteOldReports(cutoff = Long.MAX_VALUE)
        assertNotNull(dao.report(1))
        assertNull(dao.report(3))
    }

    // --- Review of PR 48 ----------------------------------------------------------------------------

    @Test
    fun savedOffFromTodayOrAReportLeavesTheFoldersToo() = runBlocking {
        // What Repository.setSaved runs, for Today's menu and a report's "Undo" of Save.
        val chad = create("Chad 2027")
        folders.add(chad, listOf(1))
        var removed: Folders.Unsaved? = null
        db.withTransaction { removed = Folders.setSavedRows(dao, 1, saved = false, now = now) }
        assertNull(dao.reading(1)?.savedAt)
        assertTrue(dao.observeFolderIdsOfReport(1).first().isEmpty())
        assertEquals(0, dao.observeFolders().first().single().reports)
        // The same Undo as the Library's.
        db.withTransaction { Folders.undoRows(dao, removed!!, now) }
        assertEquals(100L, dao.reading(1)?.savedAt)
        assertEquals(listOf(chad), dao.observeFolderIdsOfReport(1).first())
        // Saved on: no folder touched.
        db.withTransaction { assertNull(Folders.setSavedRows(dao, 3, saved = true, now = now)) }
        assertEquals(now, dao.reading(3)?.savedAt)
    }

    @Test
    fun aLinkLeftWithoutItsSavedReportNeverCounts() = runBlocking {
        val chad = create("Chad 2027")
        folders.add(chad, listOf(1, 2))
        // As an older build could leave it: the report no longer saved, its link still there.
        dao.upsertReading(dao.reading(2)!!.copy(savedAt = null))
        assertEquals(1, dao.observeFolders().first().single().reports)
        assertEquals(listOf(1L), dao.observeFolderReadings(chad).first().map { it.reading.reportId })
        assertEquals(listOf(1L), dao.folderReports(chad).map { it.id })
        assertEquals(listOf(listOf(1L)), LibraryBackupStore(dao).export(now).folders.map { it.reportIds })
    }

    @Test
    fun aReportNotOnThePhoneIsNeverAdded() = runBlocking {
        val chad = create("Chad 2027")
        assertEquals(0, folders.add(chad, listOf(999)))
        assertTrue(dao.allFolderReports().isEmpty())
        assertNull(dao.reading(999))
    }

    @Test
    fun aDoubleTapOnCreateMakesOneFolder() = runBlocking {
        val results = listOf(
            async(Dispatchers.IO) { folders.create("Chad 2027") },
            async(Dispatchers.IO) { folders.create("Chad 2027") },
        ).map { it.await() }
        assertEquals(1, results.count { it is Folders.NameCheck.Created })
        assertEquals(1, results.count { it is Folders.NameCheck.Taken })
        assertEquals(1, dao.allFolders().size)
    }

    @Test
    fun controlsAndDirectionMarksLeaveTheName() {
        assertEquals("Chad 2027 ECHO", Folders.clean("Chad\u0000 2027\u202E ECHO\u2066\u0007"))
        assertEquals("", Folders.clean("\u202A\u202B\u2069"))
    }
}
