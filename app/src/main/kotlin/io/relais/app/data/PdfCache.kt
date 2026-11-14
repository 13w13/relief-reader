// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

/**
 * The space of the downloaded PDFs (issue 6). The PDFs of reports that are not saved live in the
 * app's cache folder and stay under a cap the reader picks in Settings; past it, the oldest go
 * first. The PDFs of saved reports live elsewhere (filesDir) and never count.
 */
object PdfCache {
    /** The caps offered in Settings, in MB. Each is well above [PdfDownloader.MAX_BYTES]. */
    val CAPS_MB = listOf(200, 300, 500, 1000)
    const val DEFAULT_CAP_MB = 300

    /**
     * A PDF downloaded or opened in the last hour stays, whatever the cap: it may be on screen,
     * or its text being read. PdfEntity.downloadedAt is the last download or opening.
     */
    const val IN_USE_MS = 3_600_000L

    /** Decimal units, as [io.relais.api.Display.size] and Android's storage settings. */
    fun capBytes(mb: Int): Long = (if (mb in CAPS_MB) mb else DEFAULT_CAP_MB) * 1_000_000L

    /** "300 MB", "1 GB": the chips of Settings. */
    fun capLabel(mb: Int): String = if (mb >= 1000 && mb % 1000 == 0) "${mb / 1000} GB" else "$mb MB"

    /** The PDFs of the cache folder: the ones the cap counts. */
    fun inCache(pdfs: List<PdfEntity>, cacheDir: String): List<PdfEntity> {
        val folder = cacheDir.trimEnd('/') + "/"
        return pdfs.filter { it.path.startsWith(folder) }
    }

    /**
     * The PDFs to delete to bring the cache folder under [cap], the least recently downloaded or
     * opened first. Never the one just downloaded, never one in use ([IN_USE_MS]), never one of a
     * saved or highlighted report ([protectedReports]), never one outside the cache folder. A PDF
     * being downloaded is a `.part` file with no row in the database, so it is never among [pdfs].
     */
    fun toEvict(
        pdfs: List<PdfEntity>,
        cacheDir: String,
        cap: Long,
        justDownloaded: String?,
        protectedReports: Set<Long>,
        now: Long,
    ): List<PdfEntity> {
        val cache = inCache(pdfs, cacheDir)
        var total = cache.sumOf { it.bytes }
        if (total <= cap) return emptyList()
        val out = ArrayList<PdfEntity>()
        for (pdf in cache.sortedBy { it.downloadedAt }) {
            if (total <= cap) break
            if (pdf.url == justDownloaded || pdf.reportId in protectedReports) continue
            if (now - pdf.downloadedAt < IN_USE_MS) continue
            out += pdf
            total -= pdf.bytes
        }
        return out
    }
}

/**
 * What the Wi-Fi preload downloads (PreloadWorker): the PDFs of the newest key documents of what
 * the reader follows, already in the database from the sync. It adds no API request, only file
 * downloads, and leaves room under the cap for the PDFs the reader opens by hand. A download that
 * fails is tried again a day later, once: ReliefWeb is not asked twice a day for a missing file.
 */
object Preload {
    /** At most this many PDFs saved a pass. */
    const val MAX_FILES = 12

    /** At most this many downloads tried a pass, failures included. */
    const val MAX_TRIES = 24

    /** The newest documents of Following looked at, before the pass picks among them. */
    const val CANDIDATES = 60

    /** A preloaded PDF is at most 25 MB (decimal, as the cap); a larger one waits until the reader opens it. */
    const val MAX_FILE_BYTES = 25_000_000L

    /** Reports of the last 7 days: what a reader catches up on. */
    const val MAX_AGE_MS = 7 * 24 * 3_600_000L

    /** The preload fills the cache folder to 80 % of the cap, never more. */
    const val CAP_SHARE = 0.8

    /** A failed download is tried again a day later at the earliest... */
    const val RETRY_AFTER_MS = 24 * 3_600_000L

    /** ...and left after this many failures. */
    const val MAX_FAILURES = 2

    data class Item(val reportId: Long, val url: String, val bytes: Long?)

    /** A PDF the preload failed to download: when last, and how many times. */
    data class Failure(val url: String, val at: Long, val count: Int)

    /** The PDFs of the reports of the last 7 days, the newest first, each URL once. */
    fun recent(reports: List<ReportEntity>, now: Long): List<Item> {
        val seen = HashSet<String>()
        return reports.sortedByDescending { it.dateCreated }
            .filter { now - it.dateCreated <= MAX_AGE_MS }
            .mapNotNull { report -> report.pdf?.takeIf { seen.add(it.url) }?.let { Item(report.id, it.url, it.size) } }
    }

    /** The PDFs already on the phone whose text was never read: a pass stopped between the two. */
    fun unread(recent: List<Item>, onPhone: Set<String>, read: Set<String>): List<Item> =
        recent.filter { it.url in onPhone && it.url !in read }

    /**
     * The PDFs this pass may download, in order: not on the phone, not announced larger than
     * [MAX_FILE_BYTES], and not failed in the last day nor [MAX_FAILURES] times.
     */
    fun candidates(recent: List<Item>, onPhone: Set<String>, failures: Map<String, Failure>, now: Long): List<Item> =
        recent.filter { item ->
            item.url !in onPhone &&
                (item.bytes == null || item.bytes <= MAX_FILE_BYTES) &&
                mayTry(failures[item.url], now)
        }

    fun mayTry(failure: Failure?, now: Long): Boolean =
        failure == null || (failure.count < MAX_FAILURES && now - failure.at >= RETRY_AFTER_MS)

    /** The room the preload has: 80 % of the cap, minus what the cache folder holds. */
    fun room(cacheBytes: Long, cap: Long): Long = (cap * CAP_SHARE).toLong() - cacheBytes

    /**
     * One pass: [fetch] downloads an item and returns its bytes, or null when it failed or was
     * already being downloaded. Stops at [MAX_FILES] PDFs saved (not tried), [MAX_TRIES] tries, or
     * when [stop] says so. An item that does not fit the room left is skipped; a PDF of unknown
     * size needs [MAX_FILE_BYTES] of room. Returns how many PDFs were saved.
     */
    suspend fun run(items: List<Item>, room: Long, stop: () -> Boolean, fetch: suspend (Item) -> Long?): Int {
        var left = room
        var saved = 0
        var tries = 0
        for (item in items) {
            if (saved >= MAX_FILES || tries >= MAX_TRIES || stop()) break
            if ((item.bytes ?: MAX_FILE_BYTES) > left) continue
            tries++
            val bytes = fetch(item) ?: continue
            saved++
            left -= bytes
        }
        return saved
    }

    /**
     * The failures with this one added. A file over the limit is left at once ([permanent]): it will
     * not shrink. Failures older than a report can be are dropped.
     */
    fun withFailure(failures: Map<String, Failure>, url: String, now: Long, permanent: Boolean): Map<String, Failure> {
        val count = if (permanent) MAX_FAILURES else (failures[url]?.count ?: 0) + 1
        return (failures + (url to Failure(url, now, count))).filterValues { now - it.at <= MAX_AGE_MS + RETRY_AFTER_MS }
    }

    /** One failure a line: count, time, URL, separated by tabs. */
    fun encode(failures: Collection<Failure>): String =
        failures.filter { it.url.none(Char::isWhitespace) }.joinToString("\n") { "${it.count}\t${it.at}\t${it.url}" }

    fun decode(text: String): Map<String, Failure> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        val count = parts.getOrNull(0)?.toIntOrNull()
        val at = parts.getOrNull(1)?.toLongOrNull()
        val url = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        if (count == null || at == null || url == null) null else Failure(url, at, count)
    }.associateBy { it.url }
}
