// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.relais.api.ApiException
import io.relais.api.Block
import io.relais.api.Display
import io.relais.api.HtmlBlocks
import io.relais.api.Span
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.DownloadState
import io.relais.app.data.PdfDownloader
import io.relais.app.data.FileLite
import io.relais.app.data.HighlightEntity
import io.relais.app.data.LoadError
import io.relais.app.data.PdfEntity
import io.relais.app.data.ReadingEntity
import io.relais.app.data.ReportEntity
import io.relais.app.data.Repository
import io.relais.app.data.citationSource
import io.relais.app.data.toLoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReportState(
    val report: ReportEntity? = null,
    val blocks: List<Block> = emptyList(),
    val reading: ReadingEntity? = null,
    val sameCountry: List<ReportEntity> = emptyList(),
    /** The same document in other languages, on the phone (theme-compose.md 10.8). */
    val twins: List<ReportEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val downloaded: Map<String, PdfEntity> = emptyMap(),
    val loading: Boolean = true,
    val error: LoadError? = null,
    val notFound: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReportViewModel(private val c: AppContainer, private val id: Long, headline: Boolean = false) : ViewModel() {
    val readingSize: StateFlow<Int> = c.settings.flow.map { it.readingSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, io.relais.app.data.DEFAULT_READING_SIZE)

    fun setReadingSize(index: Int) = viewModelScope.launch { c.settings.setReadingSize(index) }

    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<LoadError?>(null)
    private val notFound = MutableStateFlow(false)
    private val report = c.dao.observeReport(id)

    private val blocks = report.map { it?.bodyHtml }.distinctUntilChanged()
        .map { html -> if (html.isNullOrEmpty()) emptyList() else HtmlBlocks.parse(html) }
        .flowOn(Dispatchers.Default)

    private val sameCountry = report.map { it?.primaryCountryId }.distinctUntilChanged().flatMapLatest { countryId ->
        if (countryId == null) flowOf(emptyList()) else c.dao.observeSameCountry(countryId, id, 3)
    }

    /** Language twins by the rule of the lists (LanguageTwins), among the reports on the phone. */
    private val twins = report.distinctUntilChanged { a, b -> a?.id == b?.id && a?.fetchedAt == b?.fetchedAt }.flatMapLatest { r ->
        val formatId = r?.formatId
        if (r == null || r.fromRss || formatId == null) {
            flowOf(emptyList())
        } else {
            val window = 4 * Repository.ONE_DAY
            c.dao.observeTwinCandidates(r.id, r.primaryCountryId, formatId, r.dateCreated - window, r.dateCreated + window).map { candidates ->
                val group = twinGroups(listOf(r) + candidates).firstOrNull { g -> g.main.id == r.id || g.others.any { it.id == r.id } }
                group?.let { g -> (listOf(g.main) + g.others).filter { it.id != r.id } }.orEmpty()
            }
        }
    }.flowOn(Dispatchers.Default)

    private val main = combine(
        combine(report, blocks) { r, b -> r to b },
        c.dao.observeReading(id),
        sameCountry,
        c.dao.observeHighlights(id),
        twins,
    ) { (r, b), reading, same, highlights, tw ->
        ReportState(report = r, blocks = b, reading = reading, sameCountry = same, highlights = highlights, twins = tw)
    }

    /**
     * Opened from the pile of Today: its place among this week's headlines, as the pile counts
     * them, for the block "Headline 3 of 6" after the text (v0.4). Null otherwise.
     */
    val place: StateFlow<HeadlinePlace?> = if (!headline) {
        MutableStateFlow(null)
    } else {
        c.repository.access.flatMapLatest { access ->
            c.dao.observeHeadlines(fromRss = !access, since = System.currentTimeMillis() - Repository.SEVEN_DAYS)
        }.combine(c.dao.observeReadIds()) { headlines, read -> headlinePlace(headlines, id, read.toSet()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    val hasAccess: Boolean get() = c.repository.hasAccess

    val state: StateFlow<ReportState> = combine(main, c.pdfs.states, c.pdfs.downloaded, loading, error) { s, dl, done, l, e ->
        s.copy(downloads = dl, downloaded = done, loading = l, error = e)
    }.combine(notFound) { s, nf -> s.copy(notFound = nf) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportState())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            c.repository.opened(id)
            try {
                c.repository.ensureReport(id)
            } catch (e: ApiException.NotFound) {
                notFound.value = true
            } catch (e: Exception) {
                error.value = e.toLoadError()
            } finally {
                loading.value = false
            }
        }
    }

    fun setSaved(saved: Boolean) = viewModelScope.launch {
        c.repository.setSaved(id, saved)
        c.pdfs.relocate(id, keep = saved)
    }

    fun markUnread() = viewModelScope.launch { c.repository.setRead(id, false) }

    fun savePosition(index: Int, offset: Int, percent: Int, finished: Boolean) =
        viewModelScope.launch { c.repository.savePosition(id, index, offset, percent, finished) }

    fun download(file: FileLite) = c.pdfs.start(file.url, id)
    fun cancel(file: FileLite) = c.pdfs.cancel(file.url)
    fun isMetered() = c.pdfs.isMetered()

    fun addHighlight(report: ReportEntity, block: Int, start: Int, end: Int, text: String, note: String?) =
        viewModelScope.launch { c.repository.addHighlight(report, block, start, end, text, note) }

    fun deleteHighlight(id: Long) = viewModelScope.launch { c.repository.deleteHighlight(id) }

    /** Highlights from the selection bar and returns its id, for "Add a note". */
    suspend fun addHighlightNow(report: ReportEntity, block: Int, start: Int, end: Int, text: String): Long =
        c.repository.addHighlight(report, block, start, end, text, null)

    fun setHighlightNote(id: Long, note: String) = viewModelScope.launch { c.repository.setHighlightNote(id, note) }
}

private sealed interface ReportRow {
    data object Photo : ReportRow
    data object Header : ReportRow
    data object Twins : ReportRow
    data object Files : ReportRow
    data class Notice(val text: Int, val action: Int? = null) : ReportRow
    data object Summary : ReportRow
    data object Preview : ReportRow
    data class Body(val index: Int, val block: Block) : ReportRow
    data object HeadlineNav : ReportRow
    data object Details : ReportRow
    data object Links : ReportRow
    data object SameCountry : ReportRow
    data object Footer : ReportRow
}

private val LANGUAGES = mapOf("en" to "English", "fr" to "French", "es" to "Spanish", "ar" to "Arabic", "ru" to "Russian")

/** Plain text of a block, the base for highlight offsets. */
private fun Block.text(): String? = when (this) {
    is Block.Paragraph -> spans.joinToString("") { it.text }
    is Block.Heading -> spans.joinToString("") { it.text }
    is Block.ListItem -> spans.joinToString("") { it.text }
    is Block.Quote -> spans.joinToString("") { it.text }
    else -> null
}

/** M6 Report. `jumpToBlock` opens the report on a highlighted passage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(id: Long, jumpToBlock: Int, nav: Nav, fromHeadline: Boolean = false) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "report-$id") { ReportViewModel(container, id, fromHeadline) }
    val state by vm.state.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    // Back on Today, the pile stands on the last headline read here.
    if (fromHeadline) LaunchedEffect(id) { container.headlineFocus.value = id }
    val readingSize by vm.readingSize.collectAsStateWithLifecycle()
    var showTextSize by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var restored by rememberSaveable { mutableStateOf(false) }
    var confirmFile by remember { mutableStateOf<FileLite?>(null) }
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    var highlightMode by rememberSaveable { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<Int?>(null) }
    // A passage selected in the body (long press), with the selection bar over the bottom.
    var passage by remember { mutableStateOf<Passage?>(null) }
    var clearSelection by remember { mutableIntStateOf(0) }
    fun dismissSelection() {
        passage = null
        clearSelection++
    }
    val report = state.report
    val saved = state.reading?.savedAt != null

    val savedMsg = stringResource(R.string.report_saved_snackbar)
    val undoLabel = stringResource(R.string.action_undo)
    val resumedMsg = stringResource(R.string.report_resumed)
    val backToTopLabel = stringResource(R.string.report_back_to_top)
    val openOnRwLabel = stringResource(R.string.report_open_reliefweb)
    val highlightHint = stringResource(R.string.highlight_hint)
    val highlightedMsg = stringResource(R.string.selection_highlighted)
    val copiedMsg = stringResource(R.string.selection_copied)
    val addNoteLabel = stringResource(R.string.selection_add_note)
    var noteFor by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = passage != null) { dismissSelection() }

    val hasPlace = place != null
    val rows: List<ReportRow> = remember(report, state.blocks, state.notFound, state.error, state.twins, hasPlace) {
        buildList {
            if (report == null) return@buildList
            if (report.imageUrl != null) add(ReportRow.Photo)
            add(ReportRow.Header)
            if (state.twins.isNotEmpty()) add(ReportRow.Twins)
            when {
                report.goneAt != null || state.notFound -> add(ReportRow.Notice(R.string.report_gone))
                report.fromRss -> add(ReportRow.Notice(R.string.report_rss_mode, R.string.report_read_on_reliefweb))
                report.bodyHtml == null && state.error == LoadError.OFFLINE -> add(ReportRow.Notice(R.string.report_not_saved_offline))
            }
            if (report.fromRss && report.headlineSummary != null) add(ReportRow.Summary)
            if (report.files.isNotEmpty()) add(ReportRow.Files)
            // Full text loaded but empty (map, infographic, PDF only): the first page in large.
            if (!report.fromRss && report.bodyHtml != null && state.blocks.isEmpty()) add(ReportRow.Preview)
            state.blocks.forEachIndexed { index, block -> add(ReportRow.Body(index, block)) }
            // Right after the text, before Details (revue-densite-v0.3.md, section 3).
            if (hasPlace) add(ReportRow.HeadlineNav)
            if (!report.fromRss) add(ReportRow.Details)
            add(ReportRow.Links)
            if (state.sameCountry.isNotEmpty()) add(ReportRow.SameCountry)
            add(ReportRow.Footer)
        }
    }
    val firstBody = rows.indexOfFirst { it is ReportRow.Body }
    val lastBody = rows.indexOfLast { it is ReportRow.Body }

    fun openFile(file: FileLite) {
        val r = report ?: return
        nav.pdf(file.url, r.id, file.description?.removePrefix("- ")?.ifBlank { null } ?: file.filename ?: r.title, r.urlAlias)
    }

    // A download started from this screen opens in the viewer as soon as it is complete.
    LaunchedEffect(pendingOpen, state.downloaded, state.downloads) {
        val url = pendingOpen ?: return@LaunchedEffect
        val file = report?.files?.firstOrNull { it.url == url } ?: return@LaunchedEffect
        if (state.downloaded.containsKey(url)) {
            pendingOpen = null
            openFile(file)
        } else if (state.downloads[url] == DownloadState.Failed || state.downloads[url] is DownloadState.TooLarge) {
            pendingOpen = null
        }
    }

    // Open on a highlighted passage, or go back to the saved reading position, once.
    LaunchedEffect(firstBody, state.reading?.itemIndex) {
        if (restored || firstBody < 0) return@LaunchedEffect
        val reading = state.reading
        restored = true
        if (jumpToBlock >= 0) {
            listState.scrollToItem(firstBody + jumpToBlock.coerceIn(0, lastBody - firstBody))
        } else if (reading != null && reading.itemIndex > firstBody && reading.finishedAt == null) {
            listState.scrollToItem(reading.itemIndex.coerceAtMost(rows.lastIndex), reading.itemOffset)
            val result = snackbar.showSnackbar(resumedMsg, backToTopLabel)
            if (result == SnackbarResult.ActionPerformed) listState.scrollToItem(0)
        }
    }

    // Save the reading position continuously.
    LaunchedEffect(firstBody, lastBody) {
        if (firstBody < 0) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            )
        }.collectLatest { (index, offset, lastVisible) ->
            delay(700)
            if (!restored) return@collectLatest
            val blocks = (lastBody - firstBody + 1).coerceAtLeast(1)
            val seen = (lastVisible - firstBody + 1).coerceIn(0, blocks)
            vm.savePosition(index, offset, seen * 100 / blocks, lastVisible >= lastBody)
        }
    }

    ScreenScaffold(
        title = "",
        onBack = { nav.back() },
        snackbarHostState = snackbar,
        actions = {
            if (report != null && !report.fromRss) {
                if (state.blocks.isNotEmpty()) {
                    IconToggleButton(checked = highlightMode, onCheckedChange = {
                        highlightMode = it
                        if (it) dismissSelection()
                        if (it) scope.launch { snackbar.showSnackbar(highlightHint) }
                    }, modifier = Modifier.testTag("highlighter")) {
                        Icon(Icons.Rounded.BorderColor, contentDescription = stringResource(R.string.highlight_action))
                    }
                }
                if (saved) {
                    FilledTonalButton(onClick = { vm.setSaved(false) }, shape = CircleShape, modifier = Modifier.testTag("save_button")) {
                        Icon(Icons.Rounded.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.action_saved), modifier = Modifier.padding(start = 6.dp))
                    }
                } else {
                    OutlinedButton(onClick = {
                        vm.setSaved(true)
                        scope.launch {
                            val result = snackbar.showSnackbar(savedMsg, undoLabel)
                            if (result == SnackbarResult.ActionPerformed) vm.setSaved(false)
                        }
                    }, shape = CircleShape, modifier = Modifier.testTag("save_button")) {
                        Icon(Icons.Rounded.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.action_save), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            val shareUrl = report?.urlAlias
            if (report != null && shareUrl != null) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, report.displayTitle)
                        .putExtra(Intent.EXTRA_TEXT, "${report.displayTitle}\n$shareUrl")
                    context.startActivity(Intent.createChooser(send, null))
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.report_share))
                }
                OverflowMenu { close ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.text_size)) },
                        leadingIcon = { Icon(Icons.Rounded.FormatSize, contentDescription = null) },
                        onClick = { close(); showTextSize = true },
                    )
                    DropdownMenuItem(text = { Text(openOnRwLabel) }, onClick = { close(); safeOpen(uriHandler, shareUrl) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_mark_unread)) },
                        onClick = { close(); vm.markUnread() },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            if (state.loading && report?.bodyHtml == null && report?.fromRss != true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (firstBody >= 0) {
                val progress by remember(firstBody, lastBody) {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        ((last - firstBody + 1).toFloat() / (lastBody - firstBody + 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            if (report == null) {
                if (!state.loading) {
                    LoadErrorBanner(state.error ?: LoadError.OTHER, onRetry = vm::load, onOpenSettings = nav::settings, persistent = true)
                }
                return@Column
            }
            if (state.error != null && state.error != LoadError.OFFLINE && report.bodyHtml == null && !report.fromRss) {
                LoadErrorBanner(state.error, onRetry = vm::load, onOpenSettings = nav::settings)
            }
            Box(Modifier.fillMaxSize()) {
            ReadingTypography(readingSize) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                itemsIndexed(rows, key = { index, row -> if (row is ReportRow.Body) "b$index" else row.toString() }) { _, row ->
                    when (row) {
                        ReportRow.Photo -> Photo(report)
                        ReportRow.Header -> ReportHeader(
                            report,
                            onCountry = { cid, name -> nav.country(cid, name) },
                            onSource = { sid, name -> nav.source(sid, name) },
                        )
                        ReportRow.Twins -> AlsoIn(state.twins, onOpen = { nav.report(it) })
                        ReportRow.Files -> FilesSection(
                            report = report,
                            downloads = state.downloads,
                            downloaded = state.downloaded,
                            onRead = { file ->
                                when {
                                    state.downloaded.containsKey(file.url) -> openFile(file)
                                    vm.isMetered() && (file.size ?: 0) > 5_000_000 -> confirmFile = file
                                    else -> {
                                        pendingOpen = file.url
                                        vm.download(file)
                                    }
                                }
                            },
                            onCancel = { file ->
                                pendingOpen = null
                                vm.cancel(file)
                            },
                        )
                        is ReportRow.Notice -> {
                            val url = report.urlAlias
                            val action: (() -> Unit)? = if (row.action != null && url != null) {
                                { safeOpen(uriHandler, url) }
                            } else {
                                null
                            }
                            Banner(stringResource(row.text), row.action?.let { stringResource(it) }, action)
                        }
                        ReportRow.Summary -> Text(
                            report.headlineSummary.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(Space.screen),
                        )
                        ReportRow.Preview -> NoBodyPreview(report)
                        is ReportRow.Body -> {
                            val highlights = state.highlights.filter { it.blockIndex == row.index }
                            val blockText = row.block.text()
                            val editable = highlightMode && blockText != null
                            BlockView(
                                block = row.block,
                                highlights = highlights,
                                modifier = if (editable) {
                                    Modifier.clickable { editingBlock = row.index }
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                } else {
                                    Modifier
                                },
                                // Outside highlighter mode, the text is selectable: the selection bar.
                                onSelect = if (highlightMode || blockText == null) {
                                    null
                                } else {
                                    { start, end ->
                                        val from = start.coerceIn(0, blockText.length)
                                        val to = end.coerceIn(from, blockText.length)
                                        passage = if (to > from) {
                                            Passage(row.index, from, to, blockText.substring(from, to))
                                        } else {
                                            passage?.takeIf { it.blockIndex != row.index }
                                        }
                                    }
                                },
                                clearSignal = clearSelection,
                            )
                        }
                        ReportRow.HeadlineNav -> place?.let { p ->
                            HeadlineNavBlock(
                                p,
                                onNext = { nav.swapHeadline(it.id) },
                                onPrevious = { nav.swapHeadline(it.id) },
                                onOlder = { nav.olderHeadlines(vm.hasAccess) },
                            )
                        }
                        ReportRow.Details -> Details(report, nav)
                        ReportRow.Links -> Links(report)
                        ReportRow.SameCountry -> Column {
                            SectionHeader(stringResource(R.string.report_same_country))
                            state.sameCountry.forEach { DocumentRow(it, onClick = { nav.report(it.id) }) }
                        }
                        ReportRow.Footer -> Text(
                            stringResource(R.string.report_footer, report.sourceLong.ifBlank { "the source" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Space.screen).padding(bottom = 24.dp),
                        )
                    }
                }
            }
            }
            passage?.let { p ->
                SelectionBar(
                    passage = p,
                    source = report.citationSource(),
                    onHighlight = {
                        dismissSelection()
                        scope.launch {
                            val id = vm.addHighlightNow(report, p.blockIndex, p.start, p.end, p.text)
                            val result = snackbar.showSnackbar(highlightedMsg, addNoteLabel, duration = SnackbarDuration.Long)
                            if (result == SnackbarResult.ActionPerformed) noteFor = id
                        }
                    },
                    onDismiss = { dismissSelection() },
                    onCopied = { scope.launch { snackbar.showSnackbar(copiedMsg) } },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                )
            }
            }
        }
    }

    if (showTextSize) {
        // The preview shows the paragraph being read.
        val sample = rows.drop(listState.firstVisibleItemIndex).firstNotNullOfOrNull { row ->
            (row as? ReportRow.Body)?.block?.takeIf { it is Block.Paragraph }?.text()
        }
        TextSizeSheet(readingSize, sample, onChange = vm::setReadingSize, onDismiss = { showTextSize = false })
    }

    confirmFile?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmFile = null },
            text = { Text(stringResource(R.string.report_confirm_mobile, Display.size(file.size).orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingOpen = file.url
                    vm.download(file)
                    confirmFile = null
                }) { Text(stringResource(R.string.report_download)) }
            },
            dismissButton = { TextButton(onClick = { confirmFile = null }) { Text(stringResource(R.string.report_not_now)) } },
        )
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

    val blockIndex = editingBlock
    val blockText = blockIndex?.let { state.blocks.getOrNull(it)?.text() }
    if (report != null && blockIndex != null && blockText != null) {
        ModalBottomSheet(
            onDismissRequest = { editingBlock = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            HighlightSheet(
                text = blockText,
                existing = state.highlights.filter { it.blockIndex == blockIndex },
                onSave = { start, end, note ->
                    vm.addHighlight(report, blockIndex, start, end, blockText.substring(start, end), note)
                    editingBlock = null
                },
                onDelete = { vm.deleteHighlight(it) },
            )
        }
    }
}

/** Select a passage of one block, add an optional note, save. */
@Composable
private fun HighlightSheet(
    text: String,
    existing: List<HighlightEntity>,
    onSave: (start: Int, end: Int, note: String?) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var value by remember(text) { mutableStateOf(TextFieldValue(text, TextRange(0, text.length))) }
    var note by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(stringResource(R.string.highlight_select), style = MaterialTheme.typography.titleSmall)
        BasicTextField(
            value = value,
            onValueChange = { value = it.copy(text = text) },
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(Space.m),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.highlight_note)) },
            modifier = Modifier.fillMaxWidth(),
        )
        existing.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\"${h.text}\"" + (h.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(h.id) }) { Text(stringResource(R.string.highlight_remove)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = Space.l), horizontalArrangement = Arrangement.End) {
            Button(onClick = {
                val selection = value.selection
                val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
                val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
                if (end > start) onSave(start, end, note) else onSave(0, text.length, note)
            }, shape = CircleShape) { Text(stringResource(R.string.highlight_action)) }
        }
    }
}

@Composable
internal fun Photo(report: ReportEntity) {
    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
        AsyncImage(
            model = report.imageUrl,
            contentDescription = report.imageCaption,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(MaterialTheme.shapes.medium),
        )
        val credit = listOfNotNull(report.imageCaption, report.imageCopyright).joinToString(" ")
        if (credit.isNotBlank()) {
            Text(credit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReportHeader(
    report: ReportEntity,
    onCountry: ((Long, String) -> Unit)? = null,
    /** Each organization's name opens its page (v0.3, theme-compose.md 13.2). */
    onSource: ((Long, String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            val format = Display.shortFormat(report.formatId, report.formatName)
            if (format.isNotBlank()) {
                Text(format.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
            }
            val country = Display.countryContext(report.primaryCountryName, report.otherCountryCount)
            val countryId = report.primaryCountryId
            if (country.isNotBlank()) {
                Text(
                    country.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onPrimaryContainer,
                    modifier = if (onCountry != null && countryId != null) {
                        Modifier.clickable { onCountry(countryId, report.primaryCountryName.orEmpty()) }
                    } else {
                        Modifier
                    },
                )
            }
        }
        // A headline opens on the editors' title and summary, as on its card in Today;
        // the document's own title goes to Details.
        SelectionContainer {
            Text(report.displayTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }.testTag("report_title"))
        }
        if (report.headlineTitle != null && !report.fromRss) {
            report.headlineSummary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp, lineHeight = 25.sp), color = cs.onSurfaceVariant)
            }
        }
        val named = report.sources.filter { it.name.isNotBlank() }
        if (onSource != null && named.isNotEmpty()) {
            // One link per organization, in the text's style, onPrimaryContainer and underlined.
            val link = androidx.compose.ui.text.TextLinkStyles(
                androidx.compose.ui.text.SpanStyle(color = cs.onPrimaryContainer, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            )
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    named.forEachIndexed { i, source ->
                        if (i > 0) append(", ")
                        withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("source_${source.id}", link) { onSource(source.id, source.shortname.ifBlank { source.name }) }) {
                            append(source.name)
                        }
                    }
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("report_sources"),
            )
        } else if (report.sourceLong.isNotBlank()) {
            Text(report.sourceLong, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
        // One line of dates (theme-compose.md 12.7): the posting date only when it is more than
        // two days after publication; the period only when it is old.
        Column {
            val small = MaterialTheme.typography.bodySmall
            Display.oldCoverage(report.title, report.formatId, report.formatName)?.let {
                Text(it, style = small.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
            }
            val published = report.dateOriginal
            val dates = when {
                published == null -> stringResource(R.string.report_posted, Display.fullDate(report.dateCreated))
                report.dateCreated - published > 2 * 24 * 60 * 60 * 1000L ->
                    stringResource(R.string.report_published_posted, Display.fullDate(published), Display.fullDate(report.dateCreated))
                else -> stringResource(R.string.report_published, Display.fullDate(published))
            }
            Text(dates, style = small, color = cs.onSurfaceVariant)
        }
    }
}

/** Files section (theme-compose.md 5.4): thumbnail, details, one "Read" button for the built-in viewer. */
@Composable
internal fun FilesSection(
    report: ReportEntity,
    downloads: Map<String, DownloadState>,
    downloaded: Map<String, PdfEntity>,
    onRead: (FileLite) -> Unit,
    onCancel: (FileLite) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column {
        SectionHeader(stringResource(R.string.report_files))
        report.files.forEach { file ->
            val type = file.mimetype?.substringAfter('/')?.uppercase() ?: "FILE"
            val onPhone = downloaded.containsKey(file.url)
            val tooLarge = !onPhone && (PdfDownloader.tooLarge(file.size) || downloads[file.url] is DownloadState.TooLarge)
            val details = listOfNotNull(
                type,
                Display.size(file.size),
                file.pages?.takeIf { it > 0 }?.let { stringResource(R.string.report_pages, it.toInt()) },
            ).joinToString(" · ")
            val label = if (report.files.size == 1) {
                stringResource(R.string.report_full_report)
            } else {
                file.description?.removePrefix("- ")?.ifBlank { null } ?: file.filename ?: type
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs).testTag("file_row"),
            ) {
                Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    val thumbnail = file.previewSmall
                    Box(
                        Modifier.width(42.dp).height(56.dp).clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbnail != null) {
                            AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (onPhone) {
                                Icon(
                                    Icons.Rounded.DownloadDone,
                                    contentDescription = stringResource(R.string.library_pdf_on_phone),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                )
                            }
                        }
                        if (downloads[file.url] == DownloadState.Failed) {
                            Text(stringResource(R.string.report_download_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (tooLarge) {
                            Text(
                                stringResource(R.string.report_too_large, Display.size(PdfDownloader.MAX_BYTES).orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("file_too_large"),
                            )
                        }
                    }
                    // Over 100 MB: ReliefWeb's page, where the browser can download it.
                    if (tooLarge) {
                        TextButton(onClick = { safeOpen(uriHandler, report.urlAlias) }) {
                            Text(stringResource(R.string.report_open_reliefweb))
                        }
                    } else when (val d = downloads[file.url]) {
                        is DownloadState.Running -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(progress = { d.progress }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                            }
                            TextButton(onClick = { onCancel(file) }) { Text(stringResource(R.string.report_cancel)) }
                        }
                        else -> Button(onClick = { onRead(file) }, shape = CircleShape) {
                            Text(stringResource(if (d == DownloadState.Failed) R.string.action_try_again else R.string.report_read))
                        }
                    }
                }
            }
        }
    }
}

/** A report without text (map, infographic, PDF only): the first page in large. */
@Composable
private fun NoBodyPreview(report: ReportEntity) {
    Column(Modifier.padding(Space.screen)) {
        val preview = report.files.firstNotNullOfOrNull { it.previewLarge ?: it.previewSmall }
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = report.title,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            )
            Spacer(Modifier.height(Space.s))
        }
        Text(stringResource(R.string.report_no_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Details(report: ReportEntity, nav: Nav) {
    Column(Modifier.padding(bottom = Space.s)) {
        SectionHeader(stringResource(R.string.report_details))
        val countries = report.countries.sortedByDescending { it.id == report.primaryCountryId }
        if (countries.isNotEmpty()) {
            DetailLabel(stringResource(R.string.report_countries))
            countries.forEach { country ->
                Text(
                    country.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = country.id > 0) { nav.country(country.id, country.name) }
                        .padding(horizontal = Space.screen, vertical = 10.dp),
                )
            }
        }
        val crises = report.disasters.orEmpty().filter { it.id > 0 && it.name.isNotBlank() }
        if (crises.isNotEmpty()) {
            DetailLabel(stringResource(R.string.report_crises))
            crises.forEach { crisis ->
                Text(
                    crisis.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { nav.crisis(crisis.id, crisis.name) }
                        .padding(horizontal = Space.screen, vertical = 10.dp)
                        .testTag("report_crisis_${crisis.id}"),
                )
            }
        }
        if (report.headlineTitle != null && report.headlineTitle != report.title) {
            DetailLabel(stringResource(R.string.report_document_title))
            DetailValue(report.title)
        }
        if (report.themes.isNotEmpty()) {
            DetailLabel(stringResource(R.string.report_themes))
            DetailValue(report.themes.joinToString(", ") { it.name })
        }
        // The format is in the kicker; English goes without saying (12.7).
        if (report.languages.any { it != "en" && it != "English" }) {
            DetailLabel(stringResource(R.string.report_language))
            DetailValue(report.languages.joinToString(", ") { LANGUAGES[it] ?: it })
        }
    }
}

@Composable
private fun DetailLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = Space.screen, end = Space.screen, top = Space.s),
)

@Composable
private fun DetailValue(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Space.screen, vertical = 2.dp))

@Composable
private fun Links(report: ReportEntity) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(vertical = Space.s)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        report.urlAlias?.let { url ->
            TextButton(onClick = { safeOpen(uriHandler, url) }, modifier = Modifier.padding(horizontal = Space.s)) {
                Text(stringResource(R.string.report_open_reliefweb))
            }
        }
        val origin = report.origin
        val domain = Display.domain(origin)
        if (origin != null && domain != null) {
            TextButton(onClick = { safeOpen(uriHandler, origin) }, modifier = Modifier.padding(horizontal = Space.s)) {
                Text(stringResource(R.string.report_original, domain))
            }
        }
    }
}

// --- Native rendering of the report body -----------------------------------------------------

/**
 * One block of the body. With [onSelect], text blocks are selectable (SelectableText) and
 * report the selected range; [clearSignal] clears it.
 */
@Composable
fun BlockView(
    block: Block,
    highlights: List<HighlightEntity> = emptyList(),
    modifier: Modifier = Modifier,
    onSelect: ((start: Int, end: Int) -> Unit)? = null,
    clearSignal: Int = 0,
) {
    val padding = Modifier.padding(horizontal = Space.screen, vertical = 6.dp)
    val ranges = highlights.map { it.start until it.end }
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier.widthIn(max = 600.dp)) {
        when (block) {
            is Block.Paragraph -> BlockText(annotated(block.spans, ranges), body, padding, onSelect, clearSignal)
            is Block.Heading -> BlockText(
                annotated(block.spans, ranges),
                subheadStyle(block, LocalTextStep.current),
                padding.padding(top = Space.m).semantics { heading() },
                onSelect,
                clearSignal,
            )
            is Block.ListItem -> Row(Modifier.padding(start = Space.screen + (block.depth * 16).dp, end = Space.screen, top = 2.dp, bottom = 2.dp)) {
                Text(
                    if (block.ordered) "${block.number}." else "•",
                    style = body,
                    modifier = Modifier.widthIn(min = 24.dp),
                )
                BlockText(annotated(block.spans, ranges), body, Modifier, onSelect, clearSignal)
            }
            is Block.Quote -> Row(padding) {
                Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(Space.m))
                BlockText(annotated(block.spans, ranges), body.copy(fontStyle = FontStyle.Italic), Modifier, onSelect, clearSignal)
            }
            is Block.Table -> TableView(block)
            is Block.Image -> AsyncImage(
                model = block.url,
                contentDescription = block.alt,
                contentScale = ContentScale.FillWidth,
                modifier = padding.fillMaxWidth(),
            )
            Block.Rule -> HorizontalDivider(padding, color = MaterialTheme.colorScheme.outlineVariant)
        }
        highlights.mapNotNull { it.note }.forEach { note ->
            Text(
                stringResource(R.string.highlight_note_line, note),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.screen, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun BlockText(text: AnnotatedString, style: TextStyle, modifier: Modifier, onSelect: ((Int, Int) -> Unit)?, clearSignal: Int) {
    if (onSelect == null) {
        Text(text, style = style, modifier = modifier)
    } else {
        SelectableText(text, style, onSelect, modifier, clearSignal)
    }
}

/** Wide tables scroll sideways; the first row is bold when it is a header. */
@Composable
private fun TableView(table: Block.Table) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            table.rows.forEachIndexed { index, row ->
                val header = index == 0 && table.hasHeader
                Row(if (header) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier) {
                    row.forEach { cell ->
                        Text(
                            cell,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(160.dp).padding(Space.s),
                        )
                    }
                }
                if (index < table.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * Body subheads (chef's reader review, 7/11): the text font in bold, h1 and h2 at body + 2 sp
 * (19 sp at Default), h3 and below at body size; they follow "Aa" and stay below the title.
 * A long "heading", often the standfirst set in a heading tag, reads as a bold paragraph.
 */
@Composable
private fun subheadStyle(block: Block.Heading, step: TextStep): TextStyle {
    val body = MaterialTheme.typography.bodyLarge
    val long = block.spans.sumOf { it.text.length } > LONG_HEADING
    return if (block.level <= 2 && !long) {
        body.copy(fontWeight = FontWeight.Bold, fontSize = (step.body.value + 2).sp, lineHeight = (step.line.value + 2).sp)
    } else {
        body.copy(fontWeight = FontWeight.Bold)
    }
}

private const val LONG_HEADING = 140

@Composable
private fun annotated(spans: List<Span>, highlights: List<IntRange> = emptyList()): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val highlightColor = LocalRelaisColors.current.highlight
    return buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
            val link = span.link
            if (link != null) {
                // The link style twice: SelectableText (a text field) draws no TextLinkStyles.
                val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(link, TextLinkStyles(linkStyle))) { withStyle(style.merge(linkStyle)) { append(span.text) } }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
        highlights.forEach { range ->
            val start = range.first.coerceIn(0, length)
            val end = (range.last + 1).coerceIn(start, length)
            if (end > start) addStyle(SpanStyle(background = highlightColor), start, end)
        }
    }
}
