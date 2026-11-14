// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
) {
    /** What the confirmation names: "12 saved, 140 in history, 40 highlights and 6 followed." */
    data class Counts(val saved: Int, val history: Int, val highlights: Int, val followed: Int)

    val counts: Counts
        get() = Counts(readings.count { it.savedAt != null }, readings.count { it.inHistory }, highlights.size, countries.size + crises.size + sources.size)
}

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

    /** A Re.L backup of this version or an earlier one; a newer one is refused, with its own message. */
    fun parse(text: String): Parsed {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Parsed.NotBackup
        if (obj["app"]?.jsonPrimitive?.contentOrNull != APP || obj["kind"]?.jsonPrimitive?.contentOrNull != KIND) return Parsed.NotBackup
        val version = obj["version"]?.jsonPrimitive?.intOrNull ?: return Parsed.NotBackup
        if (version > VERSION) return Parsed.Newer
        return runCatching { Parsed.Ok(json.decodeFromJsonElement(LibraryBackup.serializer(), obj)) }.getOrElse { Parsed.NotBackup }
    }

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
        )
    }

    /**
     * Merges [backup] into the phone, deleting nothing. A report of Saved or History keeps the
     * phone's row, completed by the file's (saved, in history, the furthest reading); its card
     * comes back when the phone has no copy. A highlight already there (same report, same place)
     * is not added twice. A follow already there stays as it is; a new one counts its last 7
     * days as new, as when it is followed by hand, and fills in at the next sync.
     */
    suspend fun restore(backup: LibraryBackup, now: Long) {
        transaction {
            dao.insertReportsIfAbsent(backup.readings.mapNotNull { r -> r.card?.toEntity(r.reportId, now) })
            backup.readings.forEach { r ->
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
    files = emptyList(), bodyHtml = null, isHeadline = headlineTitle != null, fromRss = fromRss, fetchedAt = now,
)

private fun ReadingEntity.toBackup(card: BackupCard?) = BackupReading(
    reportId = reportId, savedAt = savedAt, inHistory = inHistory, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt,
    percent = percent, finishedAt = finishedAt, readAt = readAt, pdfPage = pdfPage, card = card,
)

private fun BackupReading.toEntity() = ReadingEntity(
    reportId = reportId, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt, percent = percent,
    finishedAt = finishedAt, savedAt = savedAt, pdfPage = pdfPage, readAt = readAt, inHistory = inHistory,
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
