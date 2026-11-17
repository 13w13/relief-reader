// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

/**
 * The folders of the Library (founder, 17/11; equipe/produit/fiche-dossiers-library.md). A folder
 * is a name the reader chose, put on saved reports; a report may be in several.
 * - A report put in a folder is saved, if it was not (rule 3): it stays on the phone, readable
 *   offline, and the database never deletes it.
 * - Removed from Saved, it leaves its folders too; Undo puts back both.
 * - Deleting a folder deletes the folder only: its reports stay in Saved.
 *
 * @param relocate moves a report's PDFs to the kept storage when it becomes saved, as Save does.
 */
class Folders(
    private val dao: RelaisDao,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val relocate: suspend (reportId: Long, keep: Boolean) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface NameCheck {
        data class Ok(val name: String) : NameCheck

        /** [create] made the folder [id]. */
        data class Created(val id: Long, val name: String) : NameCheck
        data object Empty : NameCheck

        /** Another folder has this name, whatever the capitals: that one is offered instead. */
        data class Taken(val folderId: Long) : NameCheck
    }

    /**
     * A folder name as kept: spaces at both ends and line breaks removed, cut to [MAX_NAME].
     * Empty, or the name of another folder ignoring capitals ([except] being renamed), is refused.
     */
    suspend fun check(name: String, except: Long? = null): NameCheck {
        val clean = clean(name)
        if (clean.isEmpty()) return NameCheck.Empty
        val same = dao.allFolders().firstOrNull { it.id != except && it.name.equals(clean, ignoreCase = true) }
        return if (same != null) NameCheck.Taken(same.id) else NameCheck.Ok(clean)
    }

    /** Creates a folder, or says why not. */
    suspend fun create(name: String): NameCheck {
        val check = check(name)
        if (check !is NameCheck.Ok) return check
        val at = now()
        val id = dao.insertFolder(FolderEntity(name = check.name, createdAt = at, updatedAt = at))
        return NameCheck.Created(id, check.name)
    }

    suspend fun rename(folderId: Long, name: String): NameCheck {
        val check = check(name, except = folderId)
        if (check is NameCheck.Ok) dao.renameFolder(folderId, check.name, now())
        return check
    }

    /** Deletes the folder and its links to reports; the reports stay in Saved (rule 5). */
    suspend fun delete(folderId: Long) = transaction {
        dao.deleteFolderReports(folderId)
        dao.deleteFolderRow(folderId)
    }

    /**
     * Puts reports in a folder, saving those not saved yet (rule 3). Returns how many were
     * saved by this, for "Saved and added to …". Reports of the RSS feed are never put there:
     * they cannot be saved.
     */
    suspend fun add(folderId: Long, reportIds: List<Long>): Int {
        var newlySaved = emptyList<Long>()
        transaction {
            val at = now()
            val ids = reportIds.distinct().filter { id -> dao.report(id)?.fromRss != true }
            newlySaved = ids.filter { dao.reading(it)?.savedAt == null }
            newlySaved.forEach { id ->
                val current = dao.reading(id) ?: ReadingEntity(id)
                dao.upsertReading(current.copy(savedAt = at))
            }
            dao.insertFolderReports(ids.map { FolderReportEntity(folderId, it, at) })
            if (ids.isNotEmpty()) dao.touchFolder(folderId, at)
        }
        newlySaved.forEach { relocate(it, true) }
        return newlySaved.size
    }

    /** Takes a report out of a folder; it stays in Saved (rule 6). */
    suspend fun remove(folderId: Long, reportId: Long) = transaction {
        dao.deleteFolderReport(folderId, reportId)
        dao.touchFolder(folderId, now())
    }

    /** What [unsave] took away, for Undo. */
    data class Unsaved(val reportId: Long, val savedAt: Long?, val folders: List<FolderReportEntity>)

    /** Removes a report from Saved and from its folders (rule 3), in one go. */
    suspend fun unsave(reportId: Long): Unsaved {
        var removed = Unsaved(reportId, null, emptyList())
        transaction {
            val reading = dao.reading(reportId)
            removed = Unsaved(reportId, reading?.savedAt, dao.foldersOfReport(reportId))
            if (reading != null) dao.upsertReading(reading.copy(savedAt = null))
            dao.deleteReportFromFolders(reportId)
        }
        return removed
    }

    /** Undo of [unsave]: saved again, at its first time, and back in each folder. */
    suspend fun undo(removed: Unsaved) = transaction {
        val current = dao.reading(removed.reportId) ?: ReadingEntity(removed.reportId)
        dao.upsertReading(current.copy(savedAt = removed.savedAt ?: now()))
        dao.insertFolderReports(removed.folders)
    }

    companion object {
        const val MAX_NAME = 60

        fun clean(name: String): String = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()
    }
}
