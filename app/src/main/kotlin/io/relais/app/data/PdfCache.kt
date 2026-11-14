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
     * The PDFs to delete to bring the cache folder under [cap], the oldest first (downloadedAt).
     * Never the one just downloaded, never one of a saved or highlighted report
     * ([protectedReports]), never one outside the cache folder. A PDF being downloaded is a
     * `.part` file with no row in the database, so it is never among [pdfs].
     */
    fun toEvict(
        pdfs: List<PdfEntity>,
        cacheDir: String,
        cap: Long,
        justDownloaded: String?,
        protectedReports: Set<Long>,
    ): List<PdfEntity> {
        val cache = inCache(pdfs, cacheDir)
        var total = cache.sumOf { it.bytes }
        if (total <= cap) return emptyList()
        val out = ArrayList<PdfEntity>()
        for (pdf in cache.sortedBy { it.downloadedAt }) {
            if (total <= cap) break
            if (pdf.url == justDownloaded || pdf.reportId in protectedReports) continue
            out += pdf
            total -= pdf.bytes
        }
        return out
    }
}

/**
 * What the Wi-Fi preload downloads (PreloadWorker): the PDFs of the newest key documents of what
 * the reader follows, already in the database from the sync. It adds no API request, only file
 * downloads, and leaves room under the cap for the PDFs the reader opens by hand.
 */
object Preload {
    /** At most this many files a run. */
    const val MAX_FILES = 12

    /** The newest documents of Following looked at, before the plan picks among them. */
    const val CANDIDATES = 60

    /** A preloaded PDF is at most 25 MB; a larger one waits until the reader opens it. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** Reports of the last 7 days: what a reader catches up on. */
    const val MAX_AGE_MS = 7 * 24 * 3_600_000L

    /** The preload fills the cache folder to 80 % of the cap, never more. */
    const val CAP_SHARE = 0.8

    data class Item(val reportId: Long, val url: String, val bytes: Long?)

    /**
     * The PDFs to download, the newest reports first. Skips a report with no PDF, one older than
     * [MAX_AGE_MS], a PDF already on the phone ([downloaded]) or announced larger than
     * [MAX_FILE_BYTES], and stops at [MAX_FILES] or when the next file would pass 80 % of [cap].
     * A PDF of unknown size counts as [MAX_FILE_BYTES].
     */
    fun plan(reports: List<ReportEntity>, downloaded: Set<String>, cacheBytes: Long, cap: Long, now: Long): List<Item> {
        var left = (cap * CAP_SHARE).toLong() - cacheBytes
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (report in reports.sortedByDescending { it.dateCreated }) {
            if (out.size >= MAX_FILES) break
            if (now - report.dateCreated > MAX_AGE_MS) continue
            val pdf = report.pdf ?: continue
            if (pdf.url in downloaded || !seen.add(pdf.url)) continue
            val size = pdf.size
            if (size != null && size > MAX_FILE_BYTES) continue
            val cost = size ?: MAX_FILE_BYTES
            if (cost > left) continue
            out += Item(report.id, pdf.url, size)
            left -= cost
        }
        return out
    }
}
