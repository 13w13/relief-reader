// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The update must not lose what the reader keeps: a version 1 database (v0.1.2, the build on
 * the founder's phone) with a saved report, a history, a highlight with its note and a
 * followed country comes out intact in the current version. The schemas come from
 * app/schemas, packaged as assets of the debug build.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RelaisDatabase::class.java)

    @Test
    fun everyVersionHasItsMigration() {
        assertEquals((1 until DB_VERSION).toList(), MIGRATIONS.map { it.startVersion })
        MIGRATIONS.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
    }

    @Test
    fun userDataOfVersion1SurvivesTheMigrations() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO report (id, title, dateCreated, primaryCountryId, primaryCountryName, countries, sources, " +
                    "formatId, formatName, themes, languages, files, bodyHtml, isHeadline, fromRss, fetchedAt) VALUES " +
                    "(4237046, 'Sudan: Vaccine-preventable disease outbreaks in Darfur', 1762473600000, 220, 'Sudan', " +
                    "'[{\"id\":220,\"name\":\"Sudan\"}]', '[{\"id\":1503,\"shortname\":\"MSF\",\"name\":\"Médecins Sans Frontières\"}]', " +
                    "8, 'News and Press Release', '[]', '[\"English\"]', '[]', '<p>75 per cent of the patients</p>', 0, 0, 1762480000000)",
            )
            db.execSQL(
                "INSERT INTO report (id, title, dateCreated, countries, sources, themes, languages, files, isHeadline, fromRss, fetchedAt) " +
                    "VALUES (2, 'Chad: Flash Update No. 3', 1762387200000, '[]', '[]', '[]', '[]', '[]', 0, 0, 1762480000000)",
            )
            // Saved and in the history, 40 % read; then a report in the history only.
            db.execSQL(
                "INSERT INTO reading (reportId, firstOpenedAt, lastOpenedAt, itemIndex, itemOffset, percent, savedAt, pdfPage, " +
                    "inHistory, hiddenFromContinue) VALUES (4237046, 1000, 2000, 7, 12, 40, 1500, 0, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO reading (reportId, firstOpenedAt, lastOpenedAt, itemIndex, itemOffset, percent, pdfPage, " +
                    "inHistory, hiddenFromContinue) VALUES (2, 3000, 3000, 0, 0, 100, 0, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO highlight (id, reportId, reportTitle, countryName, sourceName, formatName, reportDate, createdAt, " +
                    "text, note, blockIndex, start, end) VALUES (1, 4237046, 'Sudan: Vaccine-preventable disease outbreaks in Darfur', " +
                    "'Sudan', 'MSF', 'News and Press Release', 1762473600000, 1762490000000, " +
                    "'75 per cent of the patients treated for measles', 'for the health note', 3, 10, 57)",
            )
            db.execSQL(
                "INSERT INTO highlight_fts (rowid, text, note, title) VALUES (1, '75 per cent of the patients treated for measles', " +
                    "'for the health note', 'Sudan: Vaccine-preventable disease outbreaks in Darfur')",
            )
            db.execSQL("INSERT INTO country (id, name, shortname, iso3, status) VALUES (220, 'Sudan', NULL, 'sdn', 'current')")
            db.execSQL("INSERT INTO followed_country (countryId, name, followedAt, lastVisitAt) VALUES (220, 'Sudan', 500, 2500)")
        }

        // Room checks each migrated schema against app/schemas.
        helper.runMigrationsAndValidate(name, DB_VERSION, true, *MIGRATIONS).close()

        // Then the app's own opening: Room checks the schema again, against the compiled entities.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RelaisDatabase::class.java, name)
            .addMigrations(*MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val dao = db.dao()

                val saved = dao.observeSaved().first().single()
                assertEquals(4237046L, saved.reading.reportId)
                assertEquals(40, saved.reading.percent)
                assertEquals(7, saved.reading.itemIndex)
                assertEquals("Sudan: Vaccine-preventable disease outbreaks in Darfur", saved.report?.title)
                assertEquals("MSF", saved.report?.sourceShort)
                assertNull(saved.report?.disasters)

                assertEquals(listOf(2L, 4237046L), dao.observeHistory().first().map { it.reading.reportId })

                val highlight = dao.observeAllHighlights().first().single()
                assertEquals("75 per cent of the patients treated for measles", highlight.text)
                assertEquals("for the health note", highlight.note)
                assertEquals(3, highlight.blockIndex)
                assertEquals(10, highlight.start)
                assertEquals(57, highlight.end)
                assertNull(highlight.pdfPage)
                assertEquals(listOf(1L), dao.searchHighlights("measles"))

                val followed = dao.followed().single()
                assertEquals(220L, followed.countryId)
                assertEquals(2500L, followed.lastVisitAt)

                // The tables of version 2 are there and writable.
                assertTrue(dao.followedDisasters().isEmpty())
                dao.upsertFollowedDisaster(
                    FollowedDisasterEntity(52255, "Sudan: Conflict - Apr 2023", 1, 1, status = "current", countries = listOf(Ref(220, "Sudan"))),
                )
                assertEquals(listOf(Ref(220, "Sudan")), dao.followedDisasters().single().countries)

                // The table of version 3 (v0.3, organizations) is there and writable.
                assertTrue(dao.followedSources().isEmpty())
                dao.upsertFollowedSource(FollowedSourceEntity(9417, "ACAPS", "ACAPS", "Academic and Research Institution", reportsOnly = false, followedAt = 1, lastVisitAt = 1))
                dao.setSourceReportsOnly(9417, true)
                assertTrue(dao.followedSource(9417)!!.reportsOnly)
            }
        } finally {
            db.close()
        }
    }
}
