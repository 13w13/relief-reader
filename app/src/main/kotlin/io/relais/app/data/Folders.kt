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

    /**
     * Creates a folder, or says why not. The check and the insert are one transaction: a double
     * tap on Create makes one folder (review of PR 48).
     */
    suspend fun create(name: String): NameCheck {
        var result: NameCheck = NameCheck.Empty
        transaction {
            val check = check(name)
            result = if (check !is NameCheck.Ok) {
                check
            } else {
                val at = now()
                NameCheck.Created(dao.insertFolder(FolderEntity(name = check.name, createdAt = at, updatedAt = at)), check.name)
            }
        }
        return result
    }

    suspend fun rename(folderId: Long, name: String): NameCheck {
        var result: NameCheck = NameCheck.Empty
        transaction {
            result = check(name, except = folderId)
            (result as? NameCheck.Ok)?.let { dao.renameFolder(folderId, it.name, now()) }
        }
        return result
    }

    /** Deletes the folder and its links to reports; the reports stay in Saved (rule 5). */
    suspend fun delete(folderId: Long) = transaction {
        dao.deleteFolderReports(folderId)
        dao.deleteFolderRow(folderId)
    }

    /**
     * Puts reports in a folder, saving those not saved yet (rule 3). Returns how many were
     * saved by this, for "Saved and added to …". Only reports on the phone; never a report of
     * the RSS feed, which cannot be saved (the same rule as a restore, [canBeInAFolder]).
     */
    suspend fun add(folderId: Long, reportIds: List<Long>): Int {
        var newlySaved = emptyList<Long>()
        transaction {
            val at = now()
            val ids = reportIds.distinct().filter { id -> canBeInAFolder(dao.report(id)) }
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
        transaction { removed = unsaveRows(dao, reportId) }
        return removed
    }

    /** Undo of [unsave]: saved again, at its first time, and back in each folder. */
    suspend fun undo(removed: Unsaved) = transaction { undoRows(dao, removed, now()) }

    companion object {
        const val MAX_NAME = 60

        /** Line breaks and other controls, and the marks that turn the direction of the text. */
        private val HIDDEN = Regex("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]")

        /**
         * A folder name as kept, typed or restored: controls and direction marks out, spaces
         * collapsed, 60 characters at most (review of PR 48).
         */
        fun clean(name: String): String =
            name.replace(HIDDEN, " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()

        /** A report that may be in a folder: on the phone, and not of the RSS feed (it cannot be saved). */
        fun canBeInAFolder(report: ReportEntity?): Boolean = report != null && !report.fromRss

        /**
         * The one way out of Saved, from anywhere (Repository.setSaved, the Library, a report,
         * Today): no longer saved, out of every folder. To run in a transaction.
         */
        suspend fun unsaveRows(dao: RelaisDao, reportId: Long): Unsaved {
            val reading = dao.reading(reportId)
            val removed = Unsaved(reportId, reading?.savedAt, dao.foldersOfReport(reportId))
            if (reading != null) dao.upsertReading(reading.copy(savedAt = null))
            dao.deleteReportFromFolders(reportId)
            return removed
        }

        /**
         * Save or Saved off, as Repository.setSaved runs it for Today, a report and its Undo:
         * off goes through [unsaveRows], so out of the folders too. To run in a transaction.
         */
        suspend fun setSavedRows(dao: RelaisDao, reportId: Long, saved: Boolean, now: Long): Unsaved? {
            if (!saved) return unsaveRows(dao, reportId)
            val current = dao.reading(reportId) ?: ReadingEntity(reportId)
            dao.upsertReading(current.copy(savedAt = now))
            return null
        }

        /** Undo of [unsaveRows]: saved again, at its first time, and back in each folder. */
        suspend fun undoRows(dao: RelaisDao, removed: Unsaved, now: Long) {
            val current = dao.reading(removed.reportId) ?: ReadingEntity(removed.reportId)
            dao.upsertReading(current.copy(savedAt = removed.savedAt ?: now))
            dao.insertFolderReports(removed.folders)
        }
    }
}
