// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * pdftext.db: the text taken from the PDFs on the phone. A cache that can be rebuilt from
 * the files, kept apart from relais.db (owned by the main schema): its version can change
 * with a destructive migration, nothing the reader wrote is lost.
 */

/** One PDF whose text was read. [status] is one of the PdfDocEntity.* constants. */
@Entity(tableName = "pdf_doc", indices = [Index("reportId")])
data class PdfDocEntity(
    @PrimaryKey val url: String,
    val reportId: Long,
    val status: Int,
    val pageCount: Int,
    val chars: Int,
    /** [PdfTextStore.EXTRACTOR_VERSION] at extraction: a newer version reads the file again. */
    val version: Int,
    val fileBytes: Long,
    val extractedAt: Long,
    val millis: Long,
    /** The first lines of the text, for the list preview. */
    val preview: String?,
    /** Position in the text view: page, block on that page, offset in pixels. */
    val textPage: Int = 0,
    val textBlock: Int = 0,
    val textOffset: Int = 0,
) {
    companion object {
        const val TEXT = 1
        const val NO_TEXT = 2
        const val FAILED = 3
        /** Over a limit of [PdfLimits] before a single page was read: file size, time or memory. */
        const val TOO_LARGE = 4
    }
}

/** One page: its blocks as JSON (see [PageBlocks]) and what it sends back to the PDF for. */
@Entity(tableName = "pdf_page", indices = [Index(value = ["url", "page"], unique = true)])
data class PdfPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val page: Int,
    val blocks: String,
    val figure: String?,
    val chars: Int,
)

/** Full-text search over the pages. unicode61 folds case and accents: "deplacees" finds "déplacées". */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "pdf_page_fts")
data class PdfPageFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val text: String,
)

data class PdfHitRow(val url: String, val page: Int, val reportId: Long, val text: String)

data class PdfPreviewRow(val reportId: Long, val preview: String)

@Dao
abstract class PdfTextDao {
    @Query("SELECT * FROM pdf_doc WHERE url = :url")
    abstract suspend fun doc(url: String): PdfDocEntity?

    @Query("SELECT * FROM pdf_doc WHERE url = :url")
    abstract fun observeDoc(url: String): Flow<PdfDocEntity?>

    @Query("SELECT * FROM pdf_page WHERE url = :url ORDER BY page")
    abstract suspend fun pages(url: String): List<PdfPageEntity>

    @Query("SELECT * FROM pdf_doc WHERE reportId = :reportId ORDER BY extractedAt")
    abstract suspend fun docsForReport(reportId: Long): List<PdfDocEntity>

    @Query("SELECT * FROM pdf_page WHERE url = :url AND page = :page")
    abstract suspend fun page(url: String, page: Int): PdfPageEntity?

    @Upsert
    abstract suspend fun upsertDoc(doc: PdfDocEntity)

    @Insert
    abstract suspend fun insertPages(pages: List<PdfPageEntity>): List<Long>

    @Insert
    abstract suspend fun insertFts(entries: List<PdfPageFtsEntity>)

    @Query("DELETE FROM pdf_page_fts WHERE rowid IN (SELECT id FROM pdf_page WHERE url = :url)")
    abstract suspend fun deleteFts(url: String)

    @Query("DELETE FROM pdf_page WHERE url = :url")
    abstract suspend fun deletePages(url: String)

    @Query("DELETE FROM pdf_doc WHERE url = :url")
    abstract suspend fun deleteDocRow(url: String)

    @Transaction
    open suspend fun replace(doc: PdfDocEntity, pages: List<PdfPageEntity>, texts: List<String>) {
        delete(doc.url)
        upsertDoc(doc)
        if (pages.isEmpty()) return
        val ids = insertPages(pages)
        insertFts(ids.zip(texts) { id, text -> PdfPageFtsEntity(id, text) })
    }

    @Transaction
    open suspend fun delete(url: String) {
        deleteFts(url)
        deletePages(url)
        deleteDocRow(url)
    }

    @Query("UPDATE pdf_doc SET textPage = :page, textBlock = :block, textOffset = :offset WHERE url = :url")
    abstract suspend fun savePosition(url: String, page: Int, block: Int, offset: Int)

    @Query(
        "SELECT p.url AS url, p.page AS page, d.reportId AS reportId, f.text AS text " +
            "FROM pdf_page_fts f JOIN pdf_page p ON p.id = f.rowid JOIN pdf_doc d ON d.url = p.url " +
            "WHERE pdf_page_fts MATCH :query ORDER BY d.reportId, p.page LIMIT 300",
    )
    abstract suspend fun search(query: String): List<PdfHitRow>

    @Query("SELECT reportId, preview FROM pdf_doc WHERE status = 1 AND preview IS NOT NULL ORDER BY extractedAt")
    abstract fun observePreviews(): Flow<List<PdfPreviewRow>>
}

@Database(entities = [PdfDocEntity::class, PdfPageEntity::class, PdfPageFtsEntity::class], version = 1, exportSchema = false)
abstract class PdfTextDatabase : RoomDatabase() {
    abstract fun dao(): PdfTextDao

    companion object {
        fun create(context: Context): PdfTextDatabase =
            Room.databaseBuilder(context, PdfTextDatabase::class.java, "pdftext.db")
                // A cache: a new schema reads the files again.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
