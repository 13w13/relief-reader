// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId

/*
 * The Library in one file (lot 3, equipe/produit/fiche-sauvegarde-library.md): Saved, History,
 * Highlights and what is followed, for a new phone or a lost one. The file stays with the reader;
 * no server is involved. Never in it: the appname, the full text of reports, PDFs, caches, the
 * count of calls. A restore merges and deletes nothing.
 */

/** A report's card, so that Saved and History show it without network after a restore. */
@Serializable
data class BackupCard(
    val title: String,
    val headlineTitle: String? = null,
    val sources: List<SourceLite> = emptyList(),
    val primaryCountryId: Long? = null,
    val primaryCountryName: String? = null,
    val formatId: Long? = null,
    val formatName: String? = null,
    val dateCreated: Long,
    val dateOriginal: Long? = null,
    val urlAlias: String? = null,
    val fromRss: Boolean = false,
)

@Serializable
data class BackupReading(
    val reportId: Long,
    val savedAt: Long? = null,
    val inHistory: Boolean = false,
    val firstOpenedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val percent: Int = 0,
    val finishedAt: Long? = null,
    val readAt: Long? = null,
    val pdfPage: Int = 0,
    /** Where the reading stopped, so that Continue reading opens there (review of PR 17). */
    val itemIndex: Int = 0,
    val itemOffset: Int = 0,
    val card: BackupCard? = null,
)

@Serializable
data class BackupHighlight(
    val reportId: Long,
    val reportTitle: String,
    val countryName: String? = null,
    val sourceName: String? = null,
    val formatName: String? = null,
    val reportDate: Long,
    val createdAt: Long,
    val text: String,
    val note: String? = null,
    val blockIndex: Int,
    val start: Int,
    val end: Int,
    val pdfPage: Int? = null,
)

@Serializable data class BackupCountry(val id: Long, val name: String)

@Serializable data class BackupCrisis(val id: Long, val name: String, val glide: String? = null)

@Serializable data class BackupSource(val id: Long, val shortname: String, val name: String, val reportsOnly: Boolean = false)

/** A followed regional crisis (topic): its lists travel with it, translated when read. */
@Serializable data class BackupTopic(val id: Long, val title: String = "", val urlAlias: String? = null, val rivers: List<io.relais.api.TopicRiver> = emptyList())

@Serializable
data class LibraryBackup(
    val app: String = LibraryBackupFormat.APP,
    val kind: String = LibraryBackupFormat.KIND,
    val version: Int = LibraryBackupFormat.VERSION,
    val exportedAt: Long,
    val readings: List<BackupReading> = emptyList(),
    val highlights: List<BackupHighlight> = emptyList(),
    val countries: List<BackupCountry> = emptyList(),
    val crises: List<BackupCrisis> = emptyList(),
    val sources: List<BackupSource> = emptyList(),
    val topics: List<BackupTopic> = emptyList(),
) {
    /** What the confirmation names: "12 saved, 140 in history, 40 highlights and 6 followed." */
    data class Counts(val saved: Int, val history: Int, val highlights: Int, val followed: Int)

    /**
     * The counts of what a restore brings, with the phone's "Keep history for" ([historyDays],
     * -1 for Forever): History older than that is not brought back, so it is not counted.
     */
    fun counts(now: Long, historyDays: Int): Counts = Counts(
        readings.count { it.savedAt != null },
        readings.count { it.inHistory && keptInHistory(it, now, historyDays) },
        highlights.size,
        countries.size + crises.size + sources.size + topics.size,
    )
}

/** Whether a row of History is within the phone's keep. */
internal fun keptInHistory(r: BackupReading, now: Long, historyDays: Int): Boolean =
    historyDays <= 0 || (r.lastOpenedAt ?: r.firstOpenedAt ?: 0L) >= now - historyDays * Repository.ONE_DAY

object LibraryBackupFormat {
    const val APP = "Re.L"
    const val KIND = "library-backup"
    const val VERSION = 1

    /** Files from far larger libraries than expected are refused before reading them. */
    const val MAX_BYTES = 20L * 1024 * 1024

    // Defaults written (app, kind, version); unknown fields of a later file ignored.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: LibraryBackup): String = json.encodeToString(LibraryBackup.serializer(), backup)

    sealed interface Parsed {
        data class Ok(val backup: LibraryBackup) : Parsed
        data object NotBackup : Parsed
        data object Newer : Parsed
    }

    /** Longest highlight text and note kept from a file: a passage, not a book. */
    const val MAX_TEXT = 10_000
    const val MAX_NOTE = 2_000

    /**
     * A Re.L backup of this version or an earlier one; a newer one is refused, with its own
     * message. Any other text, valid JSON or not, is "not a backup" and never throws (review of
     * PR 17: an object in place of "app" crashed the app).
     */
    fun parse(text: String): Parsed = runCatching {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching Parsed.NotBackup
        fun field(name: String) = obj[name] as? JsonPrimitive
        if (field("app")?.contentOrNull != APP || field("kind")?.contentOrNull != KIND) return@runCatching Parsed.NotBackup
        val version = field("version")?.intOrNull ?: return@runCatching Parsed.NotBackup
        if (version > VERSION) return@runCatching Parsed.Newer
        val backup = json.decodeFromJsonElement(LibraryBackup.serializer(), obj)
        Parsed.Ok(
            backup.copy(
                highlights = backup.highlights.map { it.copy(text = it.text.take(MAX_TEXT), note = it.note?.take(MAX_NOTE)) },
            ),
        )
    }.getOrElse { Parsed.NotBackup }

    /** "rel-library-2026-11-14.json". */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "rel-library-" + Instant.ofEpochMilli(now).atZone(zone).toLocalDate() + ".json"
}

/** Builds the file from the phone, and merges a file into it. */
class LibraryBackupStore(
    private val dao: RelaisDao,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    suspend fun export(now: Long): LibraryBackup {
        val readings = dao.libraryReadings()
        val cards = dao.reportsByIds(readings.map { it.reportId }).associateBy { it.id }
        return LibraryBackup(
            exportedAt = now,
            readings = readings.map { it.toBackup(cards[it.reportId]?.toCard()) },
            highlights = dao.allHighlights().map { it.toBackup() },
            countries = dao.followed().map { BackupCountry(it.countryId, it.name) },
            crises = dao.followedDisasters().map { BackupCrisis(it.disasterId, it.name, it.glide) },
            sources = dao.followedSources().map { BackupSource(it.sourceId, it.shortname, it.name, it.reportsOnly) },
            topics = dao.followedTopics().map { BackupTopic(it.topicId, it.title, it.urlAlias, it.rivers) },
        )
    }

    /**
     * Merges [backup] into the phone, deleting nothing. A report of Saved or History keeps the
     * phone's row, completed by the file's (saved, in history, the furthest reading); its card
     * comes back when the phone has no copy. A highlight already there (same report, same place)
     * is not added twice. A follow already there stays as it is; a new one counts its last 7
     * days as new, as when it is followed by hand, and fills in at the next sync. History older
     * than the phone's keep ([historyDays], -1 for Forever) is not brought back.
     */
    suspend fun restore(backup: LibraryBackup, now: Long, historyDays: Int = -1) {
        val readings = backup.readings
            .map { if (it.inHistory && !keptInHistory(it, now, historyDays)) it.copy(inHistory = false) else it }
            .filter { it.inHistory || it.savedAt != null }
        transaction {
            // The cards the phone lacks, each indexed for the Library's search by its title and
            // organizations (its text comes when it is opened).
            val cards = readings.mapNotNull { r -> r.card?.toEntity(r.reportId, now) }
            val inserted = dao.insertReportsIfAbsent(cards)
            cards.zip(inserted).filter { (_, row) -> row != -1L }.forEach { (card, _) ->
                dao.insertFts(ReportFtsEntity(card.id, card.displayTitle, card.sourceLong, ""))
            }
            readings.forEach { r ->
                val mine = dao.reading(r.reportId)
                dao.upsertReading(mine?.mergedWith(r) ?: r.toEntity())
            }
            val places = dao.allHighlights().map { it.place() }.toMutableSet()
            backup.highlights.forEach { h ->
                if (!places.add(h.place())) return@forEach
                val entity = h.toEntity()
                val id = dao.insertHighlight(entity)
                dao.insertHighlightFts(HighlightFtsEntity(id, entity.text, entity.note.orEmpty(), entity.reportTitle))
            }
            val since = now - Repository.SEVEN_DAYS
            backup.countries.forEach { if (dao.followedCountry(it.id) == null) dao.upsertFollowed(FollowedCountryEntity(it.id, it.name, now, lastVisitAt = since)) }
            backup.crises.forEach {
                if (dao.followedDisaster(it.id) == null) {
                    dao.upsertFollowedDisaster(FollowedDisasterEntity(it.id, it.name, now, lastVisitAt = since, glide = it.glide))
                }
            }
            backup.topics.filter { it.title.isNotBlank() }.forEach {
                if (dao.followedTopic(it.id) == null) {
                    dao.upsertFollowedTopic(FollowedTopicEntity(it.id, it.title, it.urlAlias, it.rivers, followedAt = now, lastVisitAt = since))
                }
            }
            backup.sources.forEach {
                if (dao.followedSource(it.id) == null) {
                    dao.upsertFollowedSource(FollowedSourceEntity(it.id, it.shortname, it.name, reportsOnly = it.reportsOnly, followedAt = now, lastVisitAt = since))
                }
            }
        }
    }
}

/** Where a highlight sits: the same passage of the same report is one highlight. */
private data class Place(val reportId: Long, val pdfPage: Int?, val block: Int, val start: Int, val end: Int)

private fun HighlightEntity.place() = Place(reportId, pdfPage, blockIndex, start, end)
private fun BackupHighlight.place() = Place(reportId, pdfPage, blockIndex, start, end)

private fun ReportEntity.toCard() = BackupCard(
    title = title, headlineTitle = headlineTitle, sources = sources, primaryCountryId = primaryCountryId,
    primaryCountryName = primaryCountryName, formatId = formatId, formatName = formatName,
    dateCreated = dateCreated, dateOriginal = dateOriginal, urlAlias = urlAlias, fromRss = fromRss,
)

private fun BackupCard.toEntity(id: Long, now: Long) = ReportEntity(
    id = id, title = title, headlineTitle = headlineTitle, headlineSummary = null, imageUrl = null, imageCaption = null,
    imageCopyright = null, urlAlias = urlAlias, origin = null, dateCreated = dateCreated, dateOriginal = dateOriginal,
    primaryCountryId = primaryCountryId, primaryCountryName = primaryCountryName,
    countries = listOfNotNull(primaryCountryId?.let { Ref(it, primaryCountryName.orEmpty()) }),
    sources = sources, formatId = formatId, formatName = formatName, themes = emptyList(), languages = emptyList(),
    // Never a headline: without its summary and photo it would show bare in Today's pile until
    // the next sync (review of PR 17). The next sync restores the flag of a recent one.
    files = emptyList(), bodyHtml = null, isHeadline = false, fromRss = fromRss, fetchedAt = now,
)

private fun ReadingEntity.toBackup(card: BackupCard?) = BackupReading(
    reportId = reportId, savedAt = savedAt, inHistory = inHistory, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt,
    percent = percent, finishedAt = finishedAt, readAt = readAt, pdfPage = pdfPage, itemIndex = itemIndex,
    itemOffset = itemOffset, card = card,
)

private fun BackupReading.toEntity() = ReadingEntity(
    reportId = reportId, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt, itemIndex = itemIndex,
    itemOffset = itemOffset, percent = percent, finishedAt = finishedAt, savedAt = savedAt, pdfPage = pdfPage,
    readAt = readAt, inHistory = inHistory,
)

/** The phone's row, completed by the file's: nothing the phone knows is lost. */
internal fun ReadingEntity.mergedWith(b: BackupReading) = copy(
    savedAt = savedAt ?: b.savedAt,
    inHistory = inHistory || b.inHistory,
    firstOpenedAt = listOfNotNull(firstOpenedAt, b.firstOpenedAt).minOrNull(),
    lastOpenedAt = listOfNotNull(lastOpenedAt, b.lastOpenedAt).maxOrNull(),
    percent = maxOf(percent, b.percent),
    finishedAt = finishedAt ?: b.finishedAt,
    readAt = readAt ?: b.readAt,
    pdfPage = if (pdfPage > 0) pdfPage else b.pdfPage,
    // The phone's place in the text, or the file's when the phone has none.
    itemIndex = if (itemIndex > 0) itemIndex else b.itemIndex,
    itemOffset = if (itemIndex > 0) itemOffset else b.itemOffset,
)

private fun HighlightEntity.toBackup() = BackupHighlight(
    reportId = reportId, reportTitle = reportTitle, countryName = countryName, sourceName = sourceName,
    formatName = formatName, reportDate = reportDate, createdAt = createdAt, text = text, note = note,
    blockIndex = blockIndex, start = start, end = end, pdfPage = pdfPage,
)

private fun BackupHighlight.toEntity() = HighlightEntity(
    reportId = reportId, reportTitle = reportTitle, countryName = countryName, sourceName = sourceName,
    formatName = formatName, reportDate = reportDate, createdAt = createdAt, text = text, note = note,
    blockIndex = blockIndex, start = start, end = end, pdfPage = pdfPage,
)
