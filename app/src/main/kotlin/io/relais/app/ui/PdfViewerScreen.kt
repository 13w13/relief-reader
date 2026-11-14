// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.pdf.BlockMarks
import io.relais.app.pdf.PdfDocEntity
import io.relais.app.pdf.PdfMode
import io.relais.app.pdf.PdfSearch
import io.relais.app.pdf.PdfSearchField
import io.relais.app.pdf.PdfSearchResults
import io.relais.app.pdf.PdfTextBottomBar
import io.relais.app.pdf.PdfTextContent
import io.relais.app.pdf.PdfTextLoading
import io.relais.app.pdf.PdfTextPage
import io.relais.app.pdf.PdfTopBar
import io.relais.app.pdf.TextMatch
import io.relais.app.pdf.TextRow
import io.relais.app.pdf.locate
import io.relais.app.pdf.textRows
import io.relais.app.data.HighlightEntity
import io.relais.app.data.ReportEntity
import io.relais.app.data.citationSource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** A PDF opened with Android's own PdfRenderer. One page is rendered at a time. */
class PdfDocument(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int = renderer.pageCount

    /** Width / height of every page, read once, kept within a sane range. */
    val ratios: List<Float> = (0 until pageCount).map { index ->
        renderer.openPage(index).use { page ->
            (page.width.coerceAtLeast(1).toFloat() / page.height.coerceAtLeast(1)).coerceIn(MIN_RATIO, MAX_RATIO)
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = "$index:$widthPx"
            cache.get(key) ?: renderer.openPage(index).use { page ->
                // A hostile or broken PDF can declare huge pages: cap the bitmap size before allocating it.
                val ratio = ratios[index]
                var width = widthPx.coerceIn(200, 2400)
                var height = (width / ratio).toInt().coerceIn(1, MAX_SIDE)
                if (width.toLong() * height > MAX_PIXELS) {
                    val shrink = kotlin.math.sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height))
                    width = (width * shrink).toInt().coerceAtLeast(1)
                    height = (height * shrink).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    private companion object {
        const val MIN_RATIO = 0.1f
        const val MAX_RATIO = 10f
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 6_000_000L
    }

    override fun close() {
        cache.evictAll()
        renderer.close()
        descriptor.close()
    }
}

/** Where the text view opens: page, block on that page, pixels into that block. */
data class TextPosition(val page: Int, val block: Int, val offset: Int = 0)

/** The text of the PDF, as far as the viewer knows. */
sealed interface PdfTextState {
    data object Loading : PdfTextState
    data class Ready(val pages: List<PdfTextPage>, val rows: List<TextRow>, val start: TextPosition) : PdfTextState

    /** No text view: [status] is PdfDocEntity.NO_TEXT, FAILED, TOO_LARGE, or 0 for a map or an infographic. */
    data class None(val status: Int) : PdfTextState
}

data class PdfViewerState(
    val document: PdfDocument? = null,
    val fileSize: Long? = null,
    val startPage: Int = 0,
    val failed: Boolean = false,
    val text: PdfTextState = PdfTextState.Loading,
)

/** Search in the open PDF: what was typed, the matches in reading order, the one shown. */
data class PdfSearchState(
    val open: Boolean = false,
    val query: String = "",
    val matches: List<TextMatch> = emptyList(),
    val index: Int = 0,
    /** The keyboard opens for a search the reader starts, not for one opened from the Library. */
    val focus: Boolean = false,
) {
    val current: TextMatch? get() = matches.getOrNull(index)
}

class PdfViewerViewModel(
    private val c: AppContainer,
    private val url: String,
    private val reportId: Long,
    private val openPage: Int,
    private val openBlock: Int,
    private val openQuery: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(PdfViewerState())
    val state: StateFlow<PdfViewerState> = _state

    /** Text first (theme-compose.md 5.11); the PDF when there is no text to show. */
    val mode = MutableStateFlow(PdfMode.TEXT)

    /** The page being read, shared by the two views so that the switch keeps the place. */
    val page = MutableStateFlow(0)

    val search = MutableStateFlow(PdfSearchState())

    val readingSize: StateFlow<Int> = c.settings.flow.map { it.readingSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, io.relais.app.data.DEFAULT_READING_SIZE)

    /** Share of pages read while the text is taken from the PDF. */
    val progress: StateFlow<Float?> = c.pdfText.progress.map { it[url] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The report of the PDF: what a highlight and a citation name. */
    val report: StateFlow<ReportEntity?> = c.dao.observeReport(reportId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Highlights made in this PDF's Text view. A report can have several PDFs: a highlight
     * belongs to this one when its page holds the passage. After a newer extractor read the
     * PDF again, the passage may sit in another block: it is shown there.
     */
    val highlights: StateFlow<List<HighlightEntity>> = combine(c.dao.observeHighlights(reportId), _state) { list, s ->
        val pages = (s.text as? PdfTextState.Ready)?.pages ?: return@combine emptyList()
        list.mapNotNull { h ->
            val page = h.pdfPage?.let { pages.getOrNull(it - 1) } ?: return@mapNotNull null
            val at = page.locate(h.blockIndex, h.start, h.end, h.text) ?: return@mapNotNull null
            if (at.block == h.blockIndex && at.start == h.start) h else h.copy(blockIndex = at.block, start = at.start, end = at.end)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The block a highlight opened on (page, block), outlined for 1.5 s. */
    val flash = MutableStateFlow<Pair<Int, Int>?>(null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val file = c.pdfs.fileFor(url)
        if (file == null) {
            _state.value = PdfViewerState(failed = true, text = PdfTextState.None(PdfDocEntity.FAILED))
            mode.value = PdfMode.PDF
            return
        }
        val document = runCatching { withContext(Dispatchers.IO) { PdfDocument(file) } }.getOrNull()?.takeIf { it.pageCount > 0 }
        val startPage = if (openPage >= 0) openPage else c.dao.pdfPage(reportId) ?: 0
        page.value = startPage
        _state.value = PdfViewerState(document, file.length(), startPage, failed = false)
        if (document != null) c.repository.pdfOpened(reportId)

        val format = c.dao.report(reportId)?.formatId
        val text: PdfTextState = if (format in io.relais.app.pdf.NO_TEXT_FORMATS) {
            PdfTextState.None(0)
        } else {
            val doc = runCatching { c.pdfText.ensure(url, reportId, file) }.getOrNull()
            if (doc?.status == PdfDocEntity.TEXT) {
                val pages = c.pdfText.pages(url)
                val start = if (openPage >= 0) TextPosition(openPage, openBlock.coerceAtLeast(0)) else TextPosition(doc.textPage, doc.textBlock, doc.textOffset)
                // Fewer pages read than the PDF has: the reading stopped at a limit (PdfLimits).
                PdfTextState.Ready(pages, textRows(pages, total = document?.pageCount ?: pages.size), start)
            } else {
                PdfTextState.None(doc?.status ?: PdfDocEntity.FAILED)
            }
        }
        _state.value = _state.value.copy(text = text, failed = document == null && text !is PdfTextState.Ready)
        if (text !is PdfTextState.Ready) {
            mode.value = PdfMode.PDF
        } else if (!openQuery.isNullOrBlank()) {
            search.value = PdfSearchState(open = true, focus = false)
            setQuery(openQuery)
        } else if (openPage >= 0 && openBlock >= 0) {
            flash.value = openPage to openBlock
            delay(1_500)
            flash.value = null
        }
    }

    /** Saves the passage selected in the Text view ([passage] carries its page from 1) and returns its id. */
    suspend fun addHighlight(passage: Passage): Long? {
        val r = report.value ?: c.dao.report(reportId) ?: return null
        return c.repository.addHighlight(r, passage.blockIndex, passage.start, passage.end, passage.text, null, passage.pdfPage)
    }

    /** "Add a note", from the snackbar after a highlight. */
    fun setHighlightNote(id: Long, note: String) = viewModelScope.launch { c.repository.setHighlightNote(id, note) }

    fun setMode(m: PdfMode) {
        if (m == PdfMode.TEXT && _state.value.text is PdfTextState.None) return
        if (m == PdfMode.PDF && _state.value.document == null) return
        mode.value = m
    }

    fun setPage(p: Int) {
        page.value = p
    }

    fun savePdfPage(p: Int) = viewModelScope.launch { c.repository.savePdfPage(reportId, p) }

    /** [position] restores the list exactly; [shownPage] is the page the reader sees, kept for the PDF view. */
    fun saveTextPosition(position: TextPosition, shownPage: Int) = viewModelScope.launch {
        c.pdfText.savePosition(url, position.page, position.block, position.offset)
        c.repository.savePdfPage(reportId, shownPage)
    }

    fun setReadingSize(index: Int) = viewModelScope.launch { c.settings.setReadingSize(index) }

    /** The text view's banner: on its first opening only, then kept for this opening (v0.4). */
    var showTextBanner by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        private set

    init {
        viewModelScope.launch { showTextBanner = !c.settings.current().pdfTextBannerSeen }
    }

    fun textBannerShown() = viewModelScope.launch { c.settings.setPdfTextBannerSeen() }

    /** Search works on the text: from the PDF view, it opens the Text view (theme-compose.md 10.10). */
    fun openSearch() {
        if (_state.value.text is PdfTextState.Ready) mode.value = PdfMode.TEXT
        search.value = search.value.copy(open = true, focus = true)
    }

    fun closeSearch() {
        search.value = PdfSearchState()
    }

    /** New query: matches in reading order, from the first one (produit/passe-v0.2.md, 7.4). */
    fun setQuery(q: String) {
        val pages = (_state.value.text as? PdfTextState.Ready)?.pages ?: emptyList()
        search.value = search.value.copy(query = q, matches = PdfSearch.find(pages, q), index = 0)
    }

    fun nextMatch(step: Int) {
        val s = search.value
        if (s.matches.isEmpty()) return
        goToMatch((s.index + step).mod(s.matches.size))
    }

    private var flashJob: kotlinx.coroutines.Job? = null

    /** Shows match [index]: the list scrolls to its paragraph, which glows for 1.5 s. */
    fun goToMatch(index: Int) {
        val s = search.value
        val match = s.matches.getOrNull(index) ?: return
        search.value = s.copy(index = index)
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            flash.value = match.page to match.block
            delay(1_500)
            flash.value = null
        }
    }

    override fun onCleared() {
        _state.value.document?.close()
    }
}

/**
 * Index in the text list of a page and block. [lead] items come first: 1 when the banner shows,
 * 0 since v0.4 once it has been seen (review of PR 13: a fixed 1 put every jump a row too far).
 */
internal fun rowIndex(rows: List<TextRow>, page: Int, block: Int, lead: Int): Int {
    val exact = rows.indexOfFirst { it is TextRow.Body && it.page == page && it.block >= block }
    val index = if (exact >= 0 && block > 0) exact else rows.indexOfFirst { it.page >= page }.takeIf { it >= 0 } ?: 0
    return index + lead
}

/** The row at a list index, or null for the banner. */
internal fun rowAt(rows: List<TextRow>, index: Int, lead: Int): TextRow? = rows.getOrNull(index - lead)

/**
 * Built-in PDF reader with two views (theme-compose.md 5.11): the text taken from the PDF, in
 * Relais's typography, and the pages as drawn. [page], [block] and [query] open the reader on a
 * highlight or a search result.
 */
@Composable
fun PdfViewerScreen(
    url: String,
    reportId: Long,
    title: String,
    sourceUrl: String?,
    nav: Nav,
    page: Int = -1,
    block: Int = -1,
    query: String? = null,
) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "pdf-$url-$page-$block-$query") { PdfViewerViewModel(container, url, reportId, page, block, query) }
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val readingSize by vm.readingSize.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val currentPage by vm.page.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val flash by vm.flash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val highlightedMsg = stringResource(R.string.selection_highlighted)
    val addNoteLabel = stringResource(R.string.selection_add_note)
    var noteFor by remember { mutableStateOf<Long?>(null) }
    val copiedMsg = stringResource(R.string.selection_copied)
    // A passage selected in the Text view (long press): the shared selection bar (Selection.kt).
    var passage by remember { mutableStateOf<Passage?>(null) }
    var clearSelection by remember { mutableIntStateOf(0) }
    fun dismissSelection() {
        passage = null
        clearSelection++
    }
    val extra = LocalRelaisColors.current
    val barText = Color(0xFFF2F2F2)
    val cs = MaterialTheme.colorScheme
    val textList = rememberLazyListState()
    val pdfList = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var showTextSize by remember { mutableStateOf(false) }
    var textRestored by rememberSaveable { mutableStateOf(false) }
    var pdfRestored by rememberSaveable { mutableStateOf(false) }
    val text = state.text
    val ready = text as? PdfTextState.Ready
    val document = state.document

    BackHandler(enabled = search.open) { vm.closeSearch() }
    BackHandler(enabled = passage != null) { dismissSelection() }

    // Items before the first row of the text: the banner, on its first opening only. Unknown
    // (null) until the setting is read: no position is restored or saved before then.
    val bannerKnown = vm.showTextBanner != null
    fun lead() = if (vm.showTextBanner == true) 1 else 0

    // The text view opens on its saved position, or on the passage asked for.
    LaunchedEffect(ready, bannerKnown) {
        if (ready == null || textRestored || !bannerKnown) return@LaunchedEffect
        textRestored = true
        val start = ready.start
        if (start.page > 0 || start.block > 0 || start.offset > 0) {
            textList.scrollToItem(rowIndex(ready.rows, start.page, start.block, lead()), start.offset.coerceAtLeast(0))
        }
    }
    LaunchedEffect(document) {
        if (document == null || pdfRestored) return@LaunchedEffect
        pdfRestored = true
        if (state.startPage in 1 until document.pageCount) pdfList.scrollToItem(state.startPage)
    }

    // The switch keeps the place: each view goes to the page the other one was on.
    var lastMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (mode == lastMode) return@LaunchedEffect
        lastMode = mode
        val target = vm.page.value
        val current = vm.state.value
        val doc = current.document
        val text = current.text as? PdfTextState.Ready
        if (mode == PdfMode.PDF && doc != null) {
            if (pdfList.firstVisibleItemIndex != target) pdfList.scrollToItem(target.coerceIn(0, doc.pageCount - 1))
        } else if (mode == PdfMode.TEXT && text != null) {
            val shown = rowAt(text.rows, textList.firstVisibleItemIndex, lead())?.page ?: 0
            if (shown != target) textList.scrollToItem(rowIndex(text.rows, target, 0, lead()))
        }
    }

    // Follow and save the place in each view. The page being read is the one at the top of
    // the screen, or, at the end of the text, where a short last page cannot reach the top,
    // the page of the last row in view.
    LaunchedEffect(ready, mode, bannerKnown) {
        if (ready == null || mode != PdfMode.TEXT || !bannerKnown) return@LaunchedEffect
        snapshotFlow {
            val last = if (textList.canScrollForward) -1 else textList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(textList.firstVisibleItemIndex, textList.firstVisibleItemScrollOffset, last)
        }.collectLatest { (index, offset, last) ->
            val row = rowAt(ready.rows, index, lead()) ?: ready.rows.firstOrNull() ?: return@collectLatest
            val shown = (if (last >= 0) rowAt(ready.rows, last, lead()) else null)?.page ?: row.page
            vm.setPage(shown)
            delay(600)
            val blockIndex = (row as? TextRow.Body)?.block ?: 0
            vm.saveTextPosition(TextPosition(row.page, blockIndex, if (row is TextRow.Body) offset else 0), shown)
        }
    }
    LaunchedEffect(document, mode) {
        if (document == null || mode != PdfMode.PDF) return@LaunchedEffect
        snapshotFlow { pdfList.firstVisibleItemIndex }.collectLatest { p ->
            vm.setPage(p)
            delay(500)
            vm.savePdfPage(p)
        }
    }

    // A search result: the passage in the text, the page in the PDF.
    LaunchedEffect(search.current, mode) {
        val match = search.current ?: return@LaunchedEffect
        if (mode == PdfMode.TEXT && ready != null) {
            val row = ready.rows.indexOfFirst { it is TextRow.Body && it.page == match.page && it.block == match.block }
                .takeIf { it >= 0 } ?: ready.rows.indexOfFirst { it.page == match.page && it is TextRow.Link }
            if (row >= 0) textList.animateScrollToItem(row + lead())
        } else if (mode == PdfMode.PDF && document != null) {
            pdfList.animateScrollToItem(match.page.coerceIn(0, document.pageCount - 1))
        }
    }

    val dark = mode == PdfMode.PDF
    val barBackground = if (dark) extra.pdfBar else cs.surface
    val barContent = if (dark) barText else cs.onSurface
    Column(Modifier.fillMaxSize().background(if (dark) extra.pdfBackground else cs.surface)) {
        PdfTopBar(
            title = title,
            mode = mode,
            showSwitch = ready != null && document != null,
            onMode = vm::setMode,
            onClose = { nav.back() },
            onSearch = if (ready != null) vm::openSearch else null,
            background = barBackground,
            content = barContent,
        )
        // Search, under the bar (theme-compose.md 10.10): the field, then the results.
        if (search.open && ready != null && mode == PdfMode.TEXT) {
            PdfSearchField(
                query = search.query,
                onQuery = vm::setQuery,
                count = search.matches.size,
                current = search.index,
                onPrevious = { vm.nextMatch(-1) },
                onNext = { vm.nextMatch(1) },
                onClose = vm::closeSearch,
                requestFocus = search.focus,
            )
            val results = remember(search.matches) {
                search.matches.map { m ->
                    val blockText = ready.pages.getOrNull(m.page)?.blocks?.getOrNull(m.block)?.text.orEmpty()
                    m to PdfSearch.snippetAt(blockText, m.start, m.end)
                }
            }
            PdfSearchResults(search.query, results, onOpen = vm::goToMatch)
        }
        if (mode == PdfMode.PDF && text is PdfTextState.None && text.status != 0 && document != null) {
            Text(
                stringResource(
                    when (text.status) {
                        PdfDocEntity.NO_TEXT -> R.string.pdf_no_text
                        PdfDocEntity.TOO_LARGE -> R.string.pdf_text_too_large
                        else -> R.string.pdf_text_failed
                    },
                ),
                color = barText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(extra.pdfBar).padding(horizontal = 16.dp, vertical = 6.dp).testTag("pdf_no_text"),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.failed -> PdfFailed(sourceUrl, barText, Modifier.align(Alignment.Center))
                mode == PdfMode.TEXT -> when (text) {
                    is PdfTextState.Ready -> ReadingTypography(readingSize) {
                        val matchesByBlock = remember(search.matches) { search.matches.groupBy { it.page to it.block } }
                        val highlightsByBlock = remember(highlights) { highlights.groupBy { (it.pdfPage ?: 0) - 1 to it.blockIndex } }
                        val current = search.current
                        val showBanner = vm.showTextBanner == true
                        LaunchedEffect(showBanner) { if (showBanner) vm.textBannerShown() }
                        PdfTextContent(
                            rows = text.rows,
                            listState = textList,
                            showBanner = showBanner,
                            onOpenPdf = { p ->
                                if (p >= 0) vm.setPage(p)
                                vm.setMode(PdfMode.PDF)
                            },
                            marks = { p, b ->
                                val matches = matchesByBlock[p to b]
                                val saved = highlightsByBlock[p to b]
                                if (matches == null && saved == null) {
                                    BlockMarks.NONE
                                } else {
                                    BlockMarks(
                                        highlights = saved.orEmpty().map { it.start until it.end },
                                        matches = matches.orEmpty().map { it.start until it.end },
                                        current = current?.takeIf { it.page == p && it.block == b }?.let { it.start until it.end },
                                    )
                                }
                            },
                            onSelect = { p, b, start, end ->
                                val blockText = text.pages.getOrNull(p)?.blocks?.getOrNull(b)?.text.orEmpty()
                                val from = start.coerceIn(0, blockText.length)
                                val to = end.coerceIn(from, blockText.length)
                                passage = if (to > from) {
                                    Passage(b, from, to, blockText.substring(from, to), pdfPage = p + 1)
                                } else {
                                    passage?.takeIf { it.blockIndex != b || it.pdfPage != p + 1 }
                                }
                            },
                            clearSignal = clearSelection,
                            flash = flash,
                        )
                    }
                    else -> PdfTextLoading(progress, onOpenPdf = { vm.setMode(PdfMode.PDF) })
                }
                document == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> PdfPages(document, pdfList, scale, onScale = { scale = it })
            }
            val r = report
            val p = passage
            if (mode == PdfMode.TEXT && r != null && p != null) {
                SelectionBar(
                    passage = p,
                    source = r.citationSource(),
                    onHighlight = {
                        dismissSelection()
                        scope.launch {
                            val id = vm.addHighlight(p) ?: return@launch
                            // As in a report: "Highlighted. Saved in Library." with "Add a note" (5.9).
                            val result = snackbar.showSnackbar(highlightedMsg, addNoteLabel, duration = SnackbarDuration.Long)
                            if (result == SnackbarResult.ActionPerformed) noteFor = id
                        }
                    },
                    onDismiss = { dismissSelection() },
                    onCopied = { scope.launch { snackbar.showSnackbar(copiedMsg) } },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = Space.screen, vertical = Space.m)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                )
            }
            SwipeSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        if (mode == PdfMode.TEXT && ready != null) {
            PdfTextBottomBar(currentPage, ready.pages.size, onTextSize = { showTextSize = true })
        } else if (mode == PdfMode.PDF) {
            PdfBottomBar(document, pdfList, scale, state.fileSize, onScale = { scale = it }, barColor = extra.pdfBar, barText = barText)
        }
    }

    noteFor?.let { id ->
        var note by remember(id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text(stringResource(R.string.selection_add_note)) },
            text = {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.highlight_note)) })
            },
            confirmButton = { TextButton(onClick = { vm.setHighlightNote(id, note); noteFor = null }) { Text(stringResource(R.string.pick_done)) } },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text(stringResource(R.string.report_not_now)) } },
        )
    }

    if (showTextSize && ready != null) {
        val sample = ready.pages.getOrNull(currentPage)?.blocks?.firstOrNull { it.text.length > 80 }?.text
        TextSizeSheet(readingSize, sample, onChange = vm::setReadingSize, onDismiss = { showTextSize = false })
    }
}

@Composable
private fun PdfFailed(sourceUrl: String?, color: Color, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pdf_failed), color = color, style = MaterialTheme.typography.bodyMedium)
        if (sourceUrl != null) {
            TextButton(onClick = { safeOpen(uriHandler, sourceUrl) }) { Text(stringResource(R.string.report_open_reliefweb)) }
        }
    }
}

/** The pages as drawn: a list, pinch or buttons to zoom. */
@Composable
private fun PdfPages(document: PdfDocument, listState: LazyListState, scale: Float, onScale: (Float) -> Unit) {
    val currentScale by rememberUpdatedState(scale)
    val currentOnScale by rememberUpdatedState(onScale)
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Two fingers zoom; one finger is left to the scrolling lists.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var current = currentScale
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size >= 2) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) {
                            current = (current * zoom).coerceIn(1f, 4f)
                            currentOnScale(current)
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        val pageWidth = (maxWidth - 16.dp) * scale
        val density = LocalDensity.current
        val renderWidth = with(density) { ((maxWidth - 16.dp) * (if (scale >= 1.5f) 2f else 1f)).roundToPx() }
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = scale > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(pageWidth + 16.dp).fillMaxHeight().testTag("pdf_pages"),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(document.pageCount) { index ->
                    PdfPageView(document, index, renderWidth, Modifier.width(pageWidth).aspectRatio(document.ratios[index]))
                }
            }
        }
    }
}

@Composable
private fun PdfBottomBar(
    document: PdfDocument?,
    listState: LazyListState,
    scale: Float,
    fileSize: Long?,
    onScale: (Float) -> Unit,
    barColor: Color,
    barText: Color,
) {
    Row(
        Modifier.fillMaxWidth().background(barColor).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = document?.pageCount ?: 0
        Text(
            if (count > 0) stringResource(R.string.pdf_page, listState.firstVisibleItemIndex + 1, count) else "",
            color = barText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onScale((scale - 0.5f).coerceAtLeast(1f)) }, enabled = scale > 1f) {
            Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.pdf_zoom_out), tint = barText)
        }
        IconButton(onClick = { onScale((scale + 0.5f).coerceAtMost(4f)) }, enabled = scale < 4f) {
            Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.pdf_zoom_in), tint = barText)
        }
        fileSize?.let { size ->
            Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = barText, modifier = Modifier.size(18.dp))
            Text(Display.size(size).orEmpty(), color = barText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun PdfPageView(document: PdfDocument, index: Int, renderWidth: Int, modifier: Modifier) {
    var bitmap by remember(index, renderWidth) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, renderWidth) {
        bitmap = runCatching { document.render(index, renderWidth).asImageBitmap() }.getOrNull()
    }
    val description = stringResource(R.string.pdf_page_description, index + 1)
    Box(modifier.background(Color.White)) {
        bitmap?.let { Image(it, contentDescription = description, modifier = Modifier.fillMaxSize()) }
    }
}
