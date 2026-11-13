// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A PDF of the Library whose text holds the words searched. */
data class PdfHit(val reportId: Long, val url: String, val page: Int, val snippet: String)

/** The blocks of a page, stored as JSON in pdf_page.blocks. */
object PageBlocks {
    @Serializable
    private data class Stored(val k: String, val t: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Stored.serializer())

    fun encode(blocks: List<TextBlock>): String =
        json.encodeToString(serializer, blocks.map { Stored(it.kind.name.take(1), it.text) })

    fun decode(text: String): List<TextBlock> = runCatching {
        json.decodeFromString(serializer, text).map { s ->
            TextBlock(
                when (s.k) {
                    "H" -> BlockKind.HEADING
                    "T" -> BlockKind.TABLE
                    else -> BlockKind.PARAGRAPH
                },
                s.t,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * The text of the PDFs on the phone: read once with PdfBox-Android
 * after a download, laid out by [PdfLayout], kept in pdftext.db for the text view, the
 * search and the list preview. One PDF is read at a time, to keep memory low.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfTextStore(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val database by lazy { PdfTextDatabase.create(appContext) }
    private val dao: PdfTextDao get() = database.dao()
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val jobs = ConcurrentHashMap<String, Deferred<PdfDocEntity?>>()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    @Volatile private var pdfBoxReady = false

    /** Share of pages read, by PDF URL, while a PDF is being read. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** First lines of the text, by report: for "Please refer to the attached file" in lists. */
    val previews: Flow<Map<Long, String>> = dao.observePreviews().map { rows ->
        // The oldest PDF of a report wins: it is usually the main file.
        rows.reversed().associate { it.reportId to it.preview }
    }

    fun observeDoc(url: String): Flow<PdfDocEntity?> = dao.observeDoc(url)

    /** After a download: reads the text in the background. */
    fun extractLater(url: String, reportId: Long, file: File) {
        scope.launch {
            try {
                ensure(url, reportId, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Text extraction failed", e)
            }
        }
    }

    /** The text of the PDF, read now if it was not read yet, or by an older version. */
    suspend fun ensure(url: String, reportId: Long, file: File): PdfDocEntity? {
        val known = dao.doc(url)
        if (known != null && known.version == EXTRACTOR_VERSION && known.fileBytes == file.length()) return known
        val job = jobs.computeIfAbsent(url) {
            scope.async(worker, start = CoroutineStart.LAZY) { extract(url, reportId, file, known) }
        }
        return try {
            job.await()
        } finally {
            jobs.remove(url, job)
        }
    }

    suspend fun pages(url: String): List<PdfTextPage> = dao.pages(url).map { page ->
        PdfTextPage(page.page, PageBlocks.decode(page.blocks), page.figure?.let { f -> Figure.entries.firstOrNull { it.name == f } })
    }

    suspend fun savePosition(url: String, page: Int, block: Int, offset: Int) = dao.savePosition(url, page, block, offset)

    /**
     * The PDF of a report that holds a highlight (page from 0): the one whose text has the
     * passage at that place, else the first one read. A report can have several PDFs.
     */
    suspend fun urlFor(reportId: Long, page: Int, block: Int, start: Int, end: Int, text: String): String? {
        val docs = dao.docsForReport(reportId).filter { it.status == PdfDocEntity.TEXT }
        for (doc in docs) {
            val stored = dao.page(doc.url, page) ?: continue
            if (PdfTextPage(page, PageBlocks.decode(stored.blocks), null).locate(block, start, end, text) != null) return doc.url
        }
        return docs.firstOrNull()?.url
    }

    /** The PDF was deleted from the phone: its text goes too. */
    fun forget(url: String) {
        scope.launch { dao.delete(url) }
    }

    /** PDFs read on the phone that hold every word of [text]: one hit per report, its first page. */
    suspend fun search(text: String): List<PdfHit> {
        val query = PdfSearch.ftsQuery(text) ?: return emptyList()
        val terms = PdfSearch.words(text)
        val rows = runCatching { dao.search(query) }.getOrDefault(emptyList())
        return rows.groupBy { it.reportId }.map { (reportId, hits) ->
            val first = hits.first()
            // The extract comes from the paragraph of the word, not across a heading.
            PdfHit(reportId, first.url, first.page, PdfSearch.snippet(PdfSearch.paragraphWith(first.text, terms), terms, before = 50, after = 60))
        }
    }

    private suspend fun extract(url: String, reportId: Long, file: File, previous: PdfDocEntity?): PdfDocEntity {
        withContext(Dispatchers.IO) { initPdfBox() }
        val started = System.nanoTime()
        val bytes = file.length()
        var stop: PdfStop? = null
        var tooHeavy = false
        val doc = if (bytes > PdfLimits.MAX_FILE_BYTES) null else try {
            _progress.update { it + (url to 0f) }
            val read = PdfBoxText.read(file) { done, total -> _progress.update { it + (url to done.toFloat() / total) } }
            stop = read.stop
            if (read.pages.isEmpty()) null else PdfLayout.document(read.pages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A broken or encrypted file, or one too large for memory: the PDF view still works.
            Log.w(TAG, "Could not read the text of a PDF", e)
            tooHeavy = e is OutOfMemoryError || e is IOException && e.message.orEmpty().contains("scratch file", ignoreCase = true)
            null
        } finally {
            _progress.update { it - url }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val now = System.currentTimeMillis()
        val status = statusOf(bytes, doc, stop, tooHeavy)
        val entity = PdfDocEntity(
            url = url,
            reportId = reportId,
            status = status,
            pageCount = doc?.pages?.size ?: 0,
            chars = doc?.pages?.sumOf { it.chars } ?: 0,
            version = EXTRACTOR_VERSION,
            fileBytes = bytes,
            extractedAt = now,
            millis = millis,
            preview = doc?.takeIf { it.hasText }?.let { preview(it) },
            textPage = previous?.textPage ?: 0,
            textBlock = previous?.textBlock ?: 0,
            textOffset = previous?.textOffset ?: 0,
        )
        val pages = if (status == PdfDocEntity.TEXT) doc!!.pages else emptyList()
        dao.replace(
            entity,
            pages.mapIndexed { index, page ->
                PdfPageEntity(url = url, page = index, blocks = PageBlocks.encode(page.blocks), figure = page.figure?.name, chars = page.chars)
            },
            pages.map { page -> page.blocks.joinToString("\n") { it.text } },
        )
        Log.i(TAG, "Read ${entity.pageCount} pages in $millis ms, status $status" + (stop?.let { ", stopped by the $it limit" } ?: ""))
        return entity
    }

    private fun initPdfBox() {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(appContext)
            pdfBoxReady = true
        }
    }

    companion object {
        /**
         * Bump to read every PDF again after a change in PdfLayout or PdfBoxText.
         * 2: bullets bigger than their text no longer make headings (v0.2.1).
         */
        const val EXTRACTOR_VERSION = 2
        private const val TAG = "PdfText"

        /**
         * The status of a reading. A file over [PdfLimits.MAX_FILE_BYTES] is not read; a reading
         * stopped by time or memory before its first page, or out of memory, is TOO_LARGE. A
         * reading stopped later keeps its pages, and a stop by time or memory before any text
         * is TOO_LARGE too, not NO_TEXT: the text may come further on.
         */
        fun statusOf(bytes: Long, doc: DocText?, stop: PdfStop?, tooHeavy: Boolean): Int = when {
            bytes > PdfLimits.MAX_FILE_BYTES -> PdfDocEntity.TOO_LARGE
            doc == null -> if (tooHeavy || stop != null) PdfDocEntity.TOO_LARGE else PdfDocEntity.FAILED
            doc.hasText -> PdfDocEntity.TEXT
            stop == PdfStop.TIME || stop == PdfStop.MEMORY -> PdfDocEntity.TOO_LARGE
            else -> PdfDocEntity.NO_TEXT
        }

        /** The first real paragraph of the first pages: not a title, not a table, not a short line. */
        fun preview(doc: DocText): String? {
            val blocks = doc.pages.take(3).flatMap { it.blocks }
            val text = blocks.firstOrNull { it.kind == BlockKind.PARAGRAPH && it.text.length >= 60 }?.text
                ?: blocks.firstOrNull { it.kind != BlockKind.TABLE }?.text
                ?: return null
            val clean = text.trimStart { it in PdfLayout.MARKS || it in "-– " }.replace(Regex("\\s+"), " ")
            return if (clean.length <= 280) clean else clean.take(279).substringBeforeLast(' ') + "…"
        }
    }
}
