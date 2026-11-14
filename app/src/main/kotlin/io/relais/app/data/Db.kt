// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.relais.api.RelaisJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class Ref(val id: Long, val name: String)

@Serializable
data class SourceLite(val id: Long, val shortname: String, val name: String)

@Serializable
data class FileLite(
    val url: String,
    val filename: String? = null,
    val size: Long? = null,
    val pages: Long? = null,
    val mimetype: String? = null,
    val description: String? = null,
    val previewSmall: String? = null,
    val previewLarge: String? = null,
)

/** A report kept on the phone. `bodyHtml` is null until the full text has been fetched. */
@Entity(
    tableName = "report",
    indices = [Index("primaryCountryId"), Index("dateCreated"), Index("isHeadline")],
)
data class ReportEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val headlineTitle: String?,
    val headlineSummary: String?,
    val imageUrl: String?,
    val imageCaption: String?,
    val imageCopyright: String?,
    val urlAlias: String?,
    val origin: String?,
    val dateCreated: Long,
    val dateOriginal: Long?,
    val primaryCountryId: Long?,
    val primaryCountryName: String?,
    val countries: List<Ref>,
    val sources: List<SourceLite>,
    val formatId: Long?,
    val formatName: String?,
    val themes: List<Ref>,
    val languages: List<String>,
    val files: List<FileLite>,
    val bodyHtml: String?,
    val isHeadline: Boolean,
    val fromRss: Boolean,
    val fetchedAt: Long,
    val goneAt: Long? = null,
    /** Crises (ReliefWeb disasters) of the report; null for a report kept before version 2. */
    val disasters: List<Ref>? = null,
) {
    val displayTitle: String get() = headlineTitle ?: title
    val sourceShort: String get() = sources.joinToString(", ") { it.shortname }
    val sourceLong: String get() = sources.joinToString(", ") { it.name }
    val otherCountryCount: Int
        get() = if (primaryCountryId == null) maxOf(0, countries.size - 1) else countries.count { it.id != primaryCountryId }
    val pdf: FileLite? get() = files.firstOrNull { it.mimetype == "application/pdf" || it.url.endsWith(".pdf", true) }
}

/** Ordered content of a list (headlines, a country tab). */
@Entity(tableName = "feed_item", primaryKeys = ["feedKey", "reportId"], indices = [Index("reportId")])
data class FeedItemEntity(val feedKey: String, val reportId: Long, val position: Int)

@Entity(tableName = "feed_meta")
data class FeedMetaEntity(
    @PrimaryKey val feedKey: String,
    val fetchedAt: Long,
    val totalCount: Int,
    val facetsJson: String?,
    val exhausted: Boolean = false,
)

@Entity(tableName = "country")
data class CountryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val shortname: String?,
    val iso3: String?,
    val status: String?,
) {
    val displayName: String get() = shortname ?: name
}

@Entity(tableName = "followed_country")
data class FollowedCountryEntity(
    @PrimaryKey val countryId: Long,
    val name: String,
    val followedAt: Long,
    val lastVisitAt: Long,
)

/** A crisis (a ReliefWeb disaster) followed next to the countries. Since version 2. */
@Entity(tableName = "followed_disaster")
data class FollowedDisasterEntity(
    @PrimaryKey val disasterId: Long,
    val name: String,
    val followedAt: Long,
    val lastVisitAt: Long,
    val glide: String? = null,
    val typeName: String? = null,
    /** ReliefWeb's status: alert, current (ongoing) or past. */
    val status: String? = null,
    val countries: List<Ref> = emptyList(),
)

/**
 * An organization followed next to countries and crises (v0.3, P13). Since version 3. With
 * [reportsOnly], it adds only its reports to the feed; it never hides a document that came by
 * a followed country or crisis.
 */
@Entity(tableName = "followed_source")
data class FollowedSourceEntity(
    @PrimaryKey val sourceId: Long,
    val shortname: String,
    val name: String,
    val typeName: String? = null,
    val reportsOnly: Boolean = false,
    val followedAt: Long,
    val lastVisitAt: Long,
)

/** Reading state of a report: history, saved, position, read mark. */
@Entity(tableName = "reading")
data class ReadingEntity(
    @PrimaryKey val reportId: Long,
    val firstOpenedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val itemIndex: Int = 0,
    val itemOffset: Int = 0,
    val percent: Int = 0,
    val finishedAt: Long? = null,
    val savedAt: Long? = null,
    val pdfOpenedAt: Long? = null,
    val pdfPage: Int = 0,
    val readAt: Long? = null,
    val inHistory: Boolean = false,
    val hiddenFromContinue: Boolean = false,
)

@Entity(tableName = "pdf")
data class PdfEntity(
    @PrimaryKey val url: String,
    val reportId: Long,
    val path: String,
    val bytes: Long,
    val downloadedAt: Long,
)

@Entity(tableName = "api_usage")
data class ApiUsageEntity(@PrimaryKey val day: String, val calls: Int)

@Fts4
@Entity(tableName = "report_fts")
data class ReportFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val title: String,
    val sources: String,
    val body: String,
)

/** A passage highlighted in the text of a report, with an optional note. */
@Entity(tableName = "highlight", indices = [Index("reportId")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    val reportTitle: String,
    val countryName: String?,
    val sourceName: String?,
    val formatName: String?,
    val reportDate: Long,
    val createdAt: Long,
    val text: String,
    val note: String?,
    /**
     * Index of the body block, then character offsets inside that block. For a passage of a
     * PDF, the block and the offsets refer to the text of that page in the Text view.
     */
    val blockIndex: Int,
    val start: Int,
    val end: Int,
    /** Page of the PDF (from 1) for a passage highlighted in a PDF; null for the report body. Since version 2. */
    val pdfPage: Int? = null,
)

@Fts4
@Entity(tableName = "highlight_fts")
data class HighlightFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val text: String,
    val note: String,
    val title: String,
)

data class ReadingWithReport(
    @Embedded val reading: ReadingEntity,
    @Relation(parentColumn = "reportId", entityColumn = "id")
    val report: ReportEntity?,
)

data class FollowedRow(
    val countryId: Long,
    val name: String,
    val status: String?,
    val lastVisitAt: Long,
    val newCount: Int,
    val lastReportAt: Long?,
    /** New documents of the Key documents view (CountryTab.KEY). Since v0.2.2 counts use [newCount]. */
    val newKeyCount: Int = 0,
)

/** CountryTab.KEY in SQL: its formats, or an editors' headline. Keep in step with Query.kt. */
const val KEY_FORMATS = "(10, 3, 5, 6, 4, 12, 12570, 38974)"
const val KEY_CONDITION_R3 = "(r3.formatId IN " + KEY_FORMATS + " OR r3.isHeadline = 1)"
const val KEY_CONDITION = "(formatId IN " + KEY_FORMATS + " OR isHeadline = 1)"
const val KEY_CONDITION_R = "(r.formatId IN " + KEY_FORMATS + " OR r.isHeadline = 1)"

/**
 * A report of a crisis: `disasters` holds JSON such as [{"id":52586,"name":"…"}]. RelaisJson
 * writes "id" first, so `"id":52586,` matches that crisis only.
 */
const val IN_DISASTER = "disasters LIKE '%\"id\":' || :id || ',%'"
const val IN_DISASTER_R = "r.disasters LIKE '%\"id\":' || d.disasterId || ',%'"

/** A report of an organization: `sources` holds JSON such as [{"id":9417,"shortname":"ACAPS",…}]. */
const val IN_SOURCE = "sources LIKE '%\"id\":' || :id || ',%'"
const val IN_SOURCE_R = "r.sources LIKE '%\"id\":' || s.sourceId || ',%'"

/** The formats of "Reports only" (Requests.REPORT_FORMATS): situation report, analysis, assessment, appeal, evaluation. */
const val REPORT_FORMATS_SQL = "(10, 3, 5, 4, 6)"

/** A followed organization adds its documents, or only its reports. */
const val SOURCE_ADDS_R = "(s.reportsOnly = 0 OR r.formatId IN " + REPORT_FORMATS_SQL + ")"

data class FollowedDisasterRow(
    @Embedded val disaster: FollowedDisasterEntity,
    val newCount: Int,
    /** New documents of the Key documents view. Since v0.2.2 counts use [newCount]. */
    val newKeyCount: Int = 0,
    /** A crisis can stay "ongoing" for months: the date of its latest document tells (passe-v0.2.md 1.3). */
    val lastReportAt: Long? = null,
)

/** A report and the followed crisis it was found for. */
data class ScopedReport(@Embedded val report: ReportEntity, val scopeId: Long)

data class IdBody(val id: Long, val bodyHtml: String?, val isHeadline: Boolean)

data class TagsRow(val sources: List<SourceLite>, val themes: List<Ref>)

@Dao
interface RelaisDao {
    // Reports
    @Upsert
    suspend fun upsertReports(reports: List<ReportEntity>)

    @Query("SELECT * FROM report WHERE id = :id")
    fun observeReport(id: Long): Flow<ReportEntity?>

    @Query("SELECT * FROM report WHERE id = :id")
    suspend fun report(id: Long): ReportEntity?

    @Query("SELECT * FROM report WHERE id IN (:ids)")
    suspend fun reportsByIds(ids: List<Long>): List<ReportEntity>

    @Query("SELECT * FROM report WHERE urlAlias IN (:urls)")
    suspend fun reportsByUrls(urls: List<String>): List<ReportEntity>

    @Query("SELECT * FROM country WHERE id IN (:ids)")
    suspend fun countriesByIds(ids: List<Long>): List<CountryEntity>

    @Query("SELECT id, bodyHtml, isHeadline FROM report WHERE id IN (:ids)")
    suspend fun existing(ids: List<Long>): List<IdBody>

    @Query("UPDATE report SET goneAt = :at WHERE id = :id")
    suspend fun markGone(id: Long, at: Long)

    @Query(
        "SELECT * FROM report WHERE isHeadline = 1 AND fromRss = :fromRss AND dateCreated >= :since " +
            "ORDER BY dateCreated DESC",
    )
    fun observeHeadlines(fromRss: Boolean, since: Long): Flow<List<ReportEntity>>

    @Query(
        "SELECT * FROM report WHERE primaryCountryId = :countryId AND id != :excludeId AND fromRss = 0 " +
            "ORDER BY dateCreated DESC LIMIT :limit",
    )
    fun observeSameCountry(countryId: Long, excludeId: Long, limit: Int): Flow<List<ReportEntity>>

    @Query(
        "SELECT * FROM report WHERE fromRss = 0 AND dateCreated >= :since AND formatId IN (:formatIds) " +
            "ORDER BY dateCreated DESC LIMIT 100",
    )
    fun observeRecentByFormats(formatIds: List<Long>, since: Long): Flow<List<ReportEntity>>

    @Query("SELECT * FROM report WHERE primaryCountryId = :countryId AND fromRss = 0 ORDER BY dateCreated DESC LIMIT 60")
    fun observeCountryReports(countryId: Long): Flow<List<ReportEntity>>

    /** Possible language twins of a report on the phone: same main country and format, posted close by. */
    @Query(
        "SELECT * FROM report WHERE id != :id AND fromRss = 0 AND primaryCountryId IS :countryId AND formatId = :formatId " +
            "AND dateCreated BETWEEN :from AND :to",
    )
    fun observeTwinCandidates(id: Long, countryId: Long?, formatId: Long, from: Long, to: Long): Flow<List<ReportEntity>>

    /** Organizations and themes of the latest documents on the phone: names for Search's sheet. */
    @Query("SELECT sources, themes FROM report WHERE fromRss = 0 ORDER BY fetchedAt DESC LIMIT :limit")
    suspend fun recentTags(limit: Int): List<TagsRow>

    /** Organizations and themes of a country's documents on the phone: names for the filter sheet. */
    @Query("SELECT sources, themes FROM report WHERE primaryCountryId = :countryId AND fromRss = 0")
    fun observeCountryTags(countryId: Long): Flow<List<TagsRow>>

    @Query(
        "SELECT COUNT(*) FROM report WHERE isHeadline = 1 AND fromRss = :fromRss AND dateCreated > :since",
    )
    suspend fun countHeadlinesSince(fromRss: Boolean, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM report WHERE primaryCountryId = :countryId AND fromRss = 0 AND dateCreated > :since",
    )
    suspend fun countCountrySince(countryId: Long, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM report WHERE primaryCountryId = :countryId AND fromRss = 0 AND dateCreated > :since " +
            "AND " + KEY_CONDITION,
    )
    suspend fun countCountryKeySince(countryId: Long, since: Long): Int

    // Lists
    @Query(
        "SELECT r.* FROM feed_item f JOIN report r ON r.id = f.reportId WHERE f.feedKey = :key ORDER BY f.position",
    )
    fun observeFeed(key: String): Flow<List<ReportEntity>>

    @Query("SELECT COUNT(*) FROM feed_item WHERE feedKey = :key")
    suspend fun feedSize(key: String): Int

    @Query(
        "SELECT r.* FROM feed_item f JOIN report r ON r.id = f.reportId WHERE f.feedKey = :key ORDER BY f.position",
    )
    suspend fun feed(key: String): List<ReportEntity>

    @Query(
        "SELECT MIN(r.dateCreated) FROM feed_item f JOIN report r ON r.id = f.reportId WHERE f.feedKey = :key",
    )
    suspend fun feedOldest(key: String): Long?

    @Query("DELETE FROM feed_item WHERE feedKey = :key")
    suspend fun clearFeed(key: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedItems(items: List<FeedItemEntity>)

    @Upsert
    suspend fun upsertFeedMeta(meta: FeedMetaEntity)

    @Query("SELECT * FROM feed_meta WHERE feedKey = :key")
    fun observeFeedMeta(key: String): Flow<FeedMetaEntity?>

    @Query("SELECT * FROM feed_meta WHERE feedKey = :key")
    suspend fun feedMeta(key: String): FeedMetaEntity?

    // Countries
    @Upsert
    suspend fun upsertCountries(countries: List<CountryEntity>)

    @Query("SELECT * FROM country ORDER BY name")
    fun observeCountries(): Flow<List<CountryEntity>>

    @Query("SELECT COUNT(*) FROM country")
    suspend fun countryCount(): Int

    @Query("SELECT * FROM country WHERE id = :id")
    fun observeCountry(id: Long): Flow<CountryEntity?>

    @Upsert
    suspend fun upsertFollowed(country: FollowedCountryEntity)

    @Query("DELETE FROM followed_country WHERE countryId = :id")
    suspend fun unfollow(id: Long)

    @Query("SELECT * FROM followed_country")
    suspend fun followed(): List<FollowedCountryEntity>

    @Query("SELECT * FROM followed_country")
    fun observeFollowed(): Flow<List<FollowedCountryEntity>>

    @Query("SELECT * FROM followed_country WHERE countryId = :id")
    suspend fun followedCountry(id: Long): FollowedCountryEntity?

    @Query("UPDATE followed_country SET lastVisitAt = :at WHERE countryId = :id")
    suspend fun markVisited(id: Long, at: Long)

    // Followed crises
    @Upsert
    suspend fun upsertFollowedDisaster(disaster: FollowedDisasterEntity)

    @Query("DELETE FROM followed_disaster WHERE disasterId = :id")
    suspend fun unfollowDisaster(id: Long)

    @Query("SELECT * FROM followed_disaster ORDER BY name")
    suspend fun followedDisasters(): List<FollowedDisasterEntity>

    @Query("SELECT * FROM followed_disaster ORDER BY name")
    fun observeFollowedDisasters(): Flow<List<FollowedDisasterEntity>>

    @Query("SELECT * FROM followed_disaster WHERE disasterId = :id")
    fun observeFollowedDisaster(id: Long): Flow<FollowedDisasterEntity?>

    @Query("UPDATE followed_disaster SET lastVisitAt = :at WHERE disasterId = :id")
    suspend fun markDisasterVisited(id: Long, at: Long)

    @Query("SELECT * FROM followed_disaster WHERE disasterId = :id")
    suspend fun followedDisaster(id: Long): FollowedDisasterEntity?

    @Query(
        "SELECT d.*, " +
            "(SELECT COUNT(*) FROM report r WHERE r.fromRss = 0 AND r.dateCreated > d.lastVisitAt AND " + IN_DISASTER_R + ") AS newCount, " +
            "(SELECT COUNT(*) FROM report r WHERE r.fromRss = 0 AND r.dateCreated > d.lastVisitAt AND " + IN_DISASTER_R +
            " AND " + KEY_CONDITION_R + ") AS newKeyCount, " +
            "(SELECT MAX(r.dateCreated) FROM report r WHERE r.fromRss = 0 AND " + IN_DISASTER_R + ") AS lastReportAt " +
            "FROM followed_disaster d ORDER BY newCount DESC, d.name",
    )
    fun observeFollowedDisasterRows(): Flow<List<FollowedDisasterRow>>

    @Query("SELECT * FROM report WHERE fromRss = 0 AND " + IN_DISASTER + " ORDER BY dateCreated DESC LIMIT 60")
    fun observeDisasterReports(id: Long): Flow<List<ReportEntity>>

    /**
     * The feed of Following (theme-compose.md 12.3): documents of the followed countries (their
     * main country) and of the followed crises, once each, the newest first; [formatIds] empty
     * keeps every format, [headlines] adds the editors' headlines (Key documents).
     */
    @Query(
        "SELECT * FROM report r WHERE r.fromRss = 0 AND " +
            "(r.primaryCountryId IN (SELECT countryId FROM followed_country) OR " +
            "EXISTS (SELECT 1 FROM followed_disaster d WHERE " + IN_DISASTER_R + ") OR " +
            "EXISTS (SELECT 1 FROM followed_source s WHERE " + IN_SOURCE_R + " AND " + SOURCE_ADDS_R + ")) AND " +
            "(:allFormats OR r.formatId IN (:formatIds) OR (:headlines AND r.isHeadline)) " +
            "ORDER BY r.dateCreated DESC LIMIT :limit",
    )
    fun observeFollowingFeed(allFormats: Boolean, formatIds: List<Long>, headlines: Boolean, limit: Int): Flow<List<ReportEntity>>

    /** Documents new since the last visit of each followed country (its main country), for the counts. */
    @Query(
        "SELECT r.* FROM report r JOIN followed_country f ON r.primaryCountryId = f.countryId " +
            "WHERE r.fromRss = 0 AND r.dateCreated > f.lastVisitAt",
    )
    fun observeNewInFollowedCountries(): Flow<List<ReportEntity>>

    /** Documents new since the last visit of each followed crisis, for the counts. */
    @Query(
        "SELECT r.*, d.disasterId AS scopeId FROM report r JOIN followed_disaster d ON " + IN_DISASTER_R +
            " WHERE r.fromRss = 0 AND r.dateCreated > d.lastVisitAt",
    )
    fun observeNewInFollowedCrises(): Flow<List<ScopedReport>>

    @Query("SELECT * FROM report WHERE primaryCountryId = :countryId AND fromRss = 0 AND dateCreated > :since")
    suspend fun countryReportsSince(countryId: Long, since: Long): List<ReportEntity>

    /** Documents new since the last visit of each followed organization, what it adds only, for the counts. */
    @Query(
        "SELECT r.*, s.sourceId AS scopeId FROM report r JOIN followed_source s ON " + IN_SOURCE_R +
            " WHERE r.fromRss = 0 AND r.dateCreated > s.lastVisitAt AND " + SOURCE_ADDS_R,
    )
    fun observeNewInFollowedSources(): Flow<List<ScopedReport>>

    @Query("SELECT * FROM report WHERE fromRss = 0 AND " + IN_SOURCE + " ORDER BY dateCreated DESC LIMIT 60")
    fun observeSourceReports(id: Long): Flow<List<ReportEntity>>

    @Query("SELECT sources, themes FROM report WHERE fromRss = 0 AND " + IN_SOURCE)
    fun observeSourceTags(id: Long): Flow<List<TagsRow>>

    @Upsert
    suspend fun upsertFollowedSource(source: FollowedSourceEntity)

    @Query("DELETE FROM followed_source WHERE sourceId = :id")
    suspend fun unfollowSource(id: Long)

    @Query("SELECT * FROM followed_source ORDER BY shortname")
    suspend fun followedSources(): List<FollowedSourceEntity>

    @Query("SELECT * FROM followed_source ORDER BY shortname")
    fun observeFollowedSources(): Flow<List<FollowedSourceEntity>>

    @Query("SELECT * FROM followed_source WHERE sourceId = :id")
    suspend fun followedSource(id: Long): FollowedSourceEntity?

    @Query("SELECT * FROM followed_source WHERE sourceId = :id")
    fun observeFollowedSource(id: Long): Flow<FollowedSourceEntity?>

    @Query("UPDATE followed_source SET lastVisitAt = :at WHERE sourceId = :id")
    suspend fun markSourceVisited(id: Long, at: Long)

    @Query("UPDATE followed_source SET reportsOnly = :reportsOnly WHERE sourceId = :id")
    suspend fun setSourceReportsOnly(id: Long, reportsOnly: Boolean)

    @Query("SELECT sources, themes FROM report WHERE fromRss = 0 AND " + IN_DISASTER)
    fun observeDisasterTags(id: Long): Flow<List<TagsRow>>

    @Query(
        "SELECT f.countryId AS countryId, f.name AS name, c.status AS status, f.lastVisitAt AS lastVisitAt, " +
            "(SELECT COUNT(*) FROM report r WHERE r.primaryCountryId = f.countryId AND r.fromRss = 0 " +
            "AND r.dateCreated > f.lastVisitAt) AS newCount, " +
            "(SELECT COUNT(*) FROM report r3 WHERE r3.primaryCountryId = f.countryId AND r3.fromRss = 0 " +
            "AND r3.dateCreated > f.lastVisitAt AND " + KEY_CONDITION_R3 + ") AS newKeyCount, " +
            "(SELECT MAX(r2.dateCreated) FROM report r2 WHERE r2.primaryCountryId = f.countryId " +
            "AND r2.fromRss = 0) AS lastReportAt " +
            "FROM followed_country f LEFT JOIN country c ON c.id = f.countryId " +
            "ORDER BY newCount DESC, f.name",
    )
    fun observeFollowedRows(): Flow<List<FollowedRow>>

    // Reading
    @Query("SELECT * FROM reading WHERE reportId = :id")
    suspend fun reading(id: Long): ReadingEntity?

    @Query("SELECT * FROM reading WHERE reportId = :id")
    fun observeReading(id: Long): Flow<ReadingEntity?>

    @Upsert
    suspend fun upsertReading(reading: ReadingEntity)

    @Query("SELECT reportId FROM reading WHERE readAt IS NOT NULL")
    fun observeReadIds(): Flow<List<Long>>

    @Transaction
    @Query(
        "SELECT * FROM reading WHERE percent >= 5 AND finishedAt IS NULL AND hiddenFromContinue = 0 " +
            "AND lastOpenedAt >= :since ORDER BY lastOpenedAt DESC LIMIT 3",
    )
    fun observeContinueReading(since: Long): Flow<List<ReadingWithReport>>

    /** "Latest reports on ReliefWeb" on Today (P14): reports of every country posted since [since]. */
    @Query(
        "SELECT * FROM report WHERE fromRss = 0 AND formatId IN (:formatIds) AND dateCreated > :since " +
            "ORDER BY dateCreated DESC LIMIT :limit",
    )
    fun observeLatestReports(formatIds: List<Long>, since: Long, limit: Int): Flow<List<ReportEntity>>

    /** "From your countries" on Today: situation reports, analyses and appeals new since the last visit. */
    @Query(
        "SELECT r.* FROM report r JOIN followed_country f ON f.countryId = r.primaryCountryId " +
            "WHERE r.fromRss = 0 AND r.dateCreated > f.lastVisitAt AND r.formatId IN (10, 3, 5, 6, 4) " +
            "ORDER BY r.dateCreated DESC LIMIT 3",
    )
    fun observeFromCountries(): Flow<List<ReportEntity>>

    @Transaction
    @Query("SELECT * FROM reading WHERE savedAt IS NOT NULL ORDER BY savedAt DESC")
    fun observeSaved(): Flow<List<ReadingWithReport>>

    @Transaction
    @Query("SELECT * FROM reading WHERE inHistory = 1 AND lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC")
    fun observeHistory(): Flow<List<ReadingWithReport>>

    @Query("UPDATE reading SET inHistory = 0 WHERE lastOpenedAt < :cutoff")
    suspend fun expireHistory(cutoff: Long)

    /** Reports of History last opened before [cutoff]: what a shorter "Keep history for" removes. */
    @Query("SELECT COUNT(*) FROM reading WHERE inHistory = 1 AND lastOpenedAt < :cutoff")
    suspend fun countHistoryBefore(cutoff: Long): Int

    @Query("UPDATE reading SET inHistory = 0")
    suspend fun clearHistoryFlags()

    @Query("DELETE FROM reading WHERE inHistory = 0 AND savedAt IS NULL AND (readAt IS NULL OR readAt < :readCutoff)")
    suspend fun deleteUnusedReading(readCutoff: Long)

    // Full-text search in the Library
    @Query("DELETE FROM report_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)

    @Insert
    suspend fun insertFts(entry: ReportFtsEntity)

    @Query("SELECT rowid FROM report_fts WHERE report_fts MATCH :query")
    suspend fun searchFts(query: String): List<Long>

    // PDFs
    @Upsert
    suspend fun upsertPdf(pdf: PdfEntity)

    @Query("SELECT * FROM pdf")
    fun observePdfs(): Flow<List<PdfEntity>>

    @Query("SELECT * FROM pdf")
    suspend fun pdfs(): List<PdfEntity>

    @Query("SELECT * FROM pdf WHERE url = :url")
    suspend fun pdf(url: String): PdfEntity?

    @Query("DELETE FROM pdf WHERE url = :url")
    suspend fun deletePdf(url: String)

    // Budget
    @Query("SELECT calls FROM api_usage WHERE day = :day")
    suspend fun calls(day: String): Int?

    @Upsert
    suspend fun upsertUsage(usage: ApiUsageEntity)

    // Highlights
    @Insert
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    @Query("DELETE FROM highlight WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("UPDATE highlight SET note = :note WHERE id = :id")
    suspend fun setHighlightNote(id: Long, note: String?)

    @Query("SELECT * FROM highlight WHERE id = :id")
    suspend fun highlight(id: Long): HighlightEntity?

    @Query("SELECT * FROM highlight WHERE reportId = :reportId ORDER BY blockIndex, start")
    fun observeHighlights(reportId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlight ORDER BY createdAt DESC")
    fun observeAllHighlights(): Flow<List<HighlightEntity>>

    /** Reports with a highlight: kept by the cleanup, they give the crises, links and dates of the export. */
    @Query("SELECT * FROM report WHERE id IN (SELECT reportId FROM highlight)")
    fun observeHighlightReports(): Flow<List<ReportEntity>>

    @Insert
    suspend fun insertHighlightFts(entry: HighlightFtsEntity)

    @Query("DELETE FROM highlight_fts WHERE rowid = :id")
    suspend fun deleteHighlightFts(id: Long)

    @Query("SELECT rowid FROM highlight_fts WHERE highlight_fts MATCH :query")
    suspend fun searchHighlights(query: String): List<Long>

    // PDF position
    @Query("SELECT pdfPage FROM reading WHERE reportId = :id")
    suspend fun pdfPage(id: Long): Int?

    @Query("SELECT reportId FROM pdf WHERE url = :url")
    suspend fun pdfReportId(url: String): Long?

    @Query("SELECT * FROM pdf WHERE reportId = :reportId")
    suspend fun pdfsForReport(reportId: Long): List<PdfEntity>

    // Cleanup
    @Query(
        "DELETE FROM feed_item WHERE feedKey IN (SELECT feedKey FROM feed_meta WHERE fetchedAt < :cutoff)",
    )
    suspend fun deleteOldFeedItems(cutoff: Long)

    @Query(
        "DELETE FROM report WHERE fetchedAt < :cutoff AND id NOT IN (SELECT reportId FROM reading) " +
            "AND id NOT IN (SELECT reportId FROM feed_item) AND id NOT IN (SELECT reportId FROM highlight)",
    )
    suspend fun deleteOldReports(cutoff: Long)

    @Query("DELETE FROM report_fts WHERE rowid NOT IN (SELECT id FROM report)")
    suspend fun deleteOrphanFts()
}

/** JSON codecs for list columns. Kept outside [Converters] with explicit types, so that KSP can read them. */
private object Codecs {
    val refs: KSerializer<List<Ref>> = ListSerializer(Ref.serializer())
    val sources: KSerializer<List<SourceLite>> = ListSerializer(SourceLite.serializer())
    val files: KSerializer<List<FileLite>> = ListSerializer(FileLite.serializer())
    val strings: KSerializer<List<String>> = ListSerializer(String.serializer())
}

class Converters {
    private val refs get() = Codecs.refs
    private val sources get() = Codecs.sources
    private val files get() = Codecs.files
    private val strings get() = Codecs.strings

    @TypeConverter fun refsToJson(value: List<Ref>): String = RelaisJson.encodeToString(refs, value)
    @TypeConverter fun jsonToRefs(value: String): List<Ref> = RelaisJson.decodeFromString(refs, value)
    @TypeConverter fun sourcesToJson(value: List<SourceLite>): String = RelaisJson.encodeToString(sources, value)
    @TypeConverter fun jsonToSources(value: String): List<SourceLite> = RelaisJson.decodeFromString(sources, value)
    @TypeConverter fun filesToJson(value: List<FileLite>): String = RelaisJson.encodeToString(files, value)
    @TypeConverter fun jsonToFiles(value: String): List<FileLite> = RelaisJson.decodeFromString(files, value)
    @TypeConverter fun stringsToJson(value: List<String>): String = RelaisJson.encodeToString(strings, value)
    @TypeConverter fun jsonToStrings(value: String): List<String> = RelaisJson.decodeFromString(strings, value)
}

@Database(
    entities = [
        ReportEntity::class, FeedItemEntity::class, FeedMetaEntity::class, CountryEntity::class,
        FollowedCountryEntity::class, ReadingEntity::class, PdfEntity::class, ApiUsageEntity::class,
        ReportFtsEntity::class, HighlightEntity::class, HighlightFtsEntity::class, FollowedDisasterEntity::class,
        FollowedSourceEntity::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RelaisDatabase : RoomDatabase() {
    abstract fun dao(): RelaisDao

    companion object {
        /**
         * No destructive fallback: Saved, History, Highlights and the followed countries and
         * crises live only in this file. Every new version adds its migration to [MIGRATIONS],
         * its schema to app/schemas and a case to MigrationTest; a missing migration then fails
         * the tests, not the update on a phone.
         */
        fun create(context: Context): RelaisDatabase =
            Room.databaseBuilder(context, RelaisDatabase::class.java, "relais.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/**
 * Version 2 (v0.2): followed crises, the crises of a report, the PDF page of a highlight.
 * Only additions: no table of the user is rewritten.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `followed_disaster` (`disasterId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`followedAt` INTEGER NOT NULL, `lastVisitAt` INTEGER NOT NULL, `glide` TEXT, `typeName` TEXT, " +
                "`status` TEXT, `countries` TEXT NOT NULL, PRIMARY KEY(`disasterId`))",
        )
        db.execSQL("ALTER TABLE `report` ADD COLUMN `disasters` TEXT")
        db.execSQL("ALTER TABLE `highlight` ADD COLUMN `pdfPage` INTEGER")
    }
}

/** Version 3 (v0.3): followed organizations. One table added, nothing else touched. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `followed_source` (`sourceId` INTEGER NOT NULL, `shortname` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `typeName` TEXT, `reportsOnly` INTEGER NOT NULL, `followedAt` INTEGER NOT NULL, " +
                "`lastVisitAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))",
        )
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/** Version of the schema; app/schemas holds one JSON file per version. */
const val DB_VERSION = 3
