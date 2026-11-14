// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import io.relais.app.data.HighlightEntity
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import io.relais.app.data.Citation
import io.relais.app.data.CitationSource
import io.relais.app.data.HighlightExport
import io.relais.app.data.Repository
import io.relais.app.data.citationSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.pdf.pdfTarget
import io.relais.app.data.ReadingWithReport
import io.relais.app.data.ReportEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryView { SAVED, HISTORY, HIGHLIGHTS }
enum class LibraryFacet { COUNTRY, CRISIS, PERIOD, FORMAT, THEME, ORGANIZATION }

/** Facets of each view: crisis and period for highlights (passe-v0.2.md 5.1), theme for reports. */
fun LibraryView.facets(): List<LibraryFacet> =
    if (this == LibraryView.HIGHLIGHTS) {
        listOf(LibraryFacet.COUNTRY, LibraryFacet.CRISIS, LibraryFacet.PERIOD, LibraryFacet.FORMAT, LibraryFacet.ORGANIZATION)
    } else {
        listOf(LibraryFacet.COUNTRY, LibraryFacet.FORMAT, LibraryFacet.THEME, LibraryFacet.ORGANIZATION)
    }

/** Values of the period facet: a highlight of yesterday is in all three. The app is in English. */
private val PERIODS = listOf(7 to "Last 7 days", 30 to "Last 30 days", 365 to "Last 12 months")
enum class GroupBy { DATE, COUNTRY, FORMAT, ORGANIZATION, THEME }

data class LibraryItem(val reading: ReadingWithReport, val report: ReportEntity, val pdfOnPhone: Boolean)

/** Saved or History: every item, and the groups left after the search and the filters. */
data class ReadingList(
    val all: List<LibraryItem> = emptyList(),
    val groups: List<Pair<String, List<LibraryItem>>> = emptyList(),
)

data class LibraryState(
    val view: LibraryView = LibraryView.SAVED,
    val query: String = "",
    val filters: Map<LibraryFacet, Set<String>> = emptyMap(),
    val groupBy: GroupBy = GroupBy.DATE,
    val saved: ReadingList = ReadingList(),
    val history: ReadingList = ReadingList(),
    val allHighlights: List<HighlightEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    /** Reports of the highlights, by id, for the crisis facet, the citation and the export. */
    val highlightReports: Map<Long, ReportEntity> = emptyMap(),
    val loaded: Boolean = false,
    /** Reports whose PDF text holds the search, with where (pdf/, T9). */
    val pdfHits: Map<Long, io.relais.app.pdf.PdfHit> = emptyMap(),
) {
    fun list(v: LibraryView): ReadingList = if (v == LibraryView.SAVED) saved else history
}

/** Values of a highlight for a facet, from the report metadata stored with it and its report. */
fun LibraryFacet.valuesOf(h: HighlightEntity, report: ReportEntity?, now: Long = System.currentTimeMillis()): List<String> = when (this) {
    LibraryFacet.COUNTRY -> listOfNotNull(h.countryName)
    LibraryFacet.CRISIS -> report?.disasters.orEmpty().map { it.name }.filter { it.isNotBlank() }
    LibraryFacet.PERIOD -> PERIODS.filter { (days, _) -> now - h.createdAt <= days * Repository.ONE_DAY }.map { it.second }
    LibraryFacet.FORMAT -> listOf(Display.shortFormat(null, h.formatName)).filter { it.isNotBlank() }
    LibraryFacet.THEME -> emptyList()
    LibraryFacet.ORGANIZATION -> h.sourceName?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()
}

/** Values a report has for a facet. A report with two themes counts under both. */
fun LibraryFacet.valuesOf(report: ReportEntity): List<String> = when (this) {
    LibraryFacet.COUNTRY -> listOfNotNull(report.primaryCountryName)
    LibraryFacet.CRISIS -> report.disasters.orEmpty().map { it.name }.filter { it.isNotBlank() }
    LibraryFacet.PERIOD -> emptyList()
    LibraryFacet.FORMAT -> listOf(Display.shortFormat(report.formatId, report.formatName)).filter { it.isNotBlank() }
    LibraryFacet.THEME -> report.themes.map { it.name }.filter { it.isNotBlank() }
    LibraryFacet.ORGANIZATION -> report.sources.map { it.shortname }.filter { it.isNotBlank() }
}

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    /** The scroll of each page, kept here so that Back from a report finds the same place (13/11). */
    val listStates: Map<LibraryView, androidx.compose.foundation.lazy.LazyListState> =
        LibraryView.entries.associateWith { androidx.compose.foundation.lazy.LazyListState() }

    private val view = MutableStateFlow(LibraryView.SAVED)
    private val query = MutableStateFlow("")
    private val ftsIds = MutableStateFlow<Set<Long>?>(null)
    private val pdfHits = MutableStateFlow<Map<Long, io.relais.app.pdf.PdfHit>>(emptyMap())
    private val highlightIds = MutableStateFlow<Set<Long>?>(null)
    private val filters = MutableStateFlow<Map<LibraryFacet, Set<String>>>(emptyMap())
    private val groupBy = MutableStateFlow(GroupBy.DATE)

    // Saved and History are both kept up to date: the pager shows the next page while swiping.
    private fun itemsOf(source: Flow<List<ReadingWithReport>>) = combine(source, c.pdfs.downloaded) { rows, pdfs ->
        rows.mapNotNull { row ->
            val report = row.report ?: return@mapNotNull null
            LibraryItem(row, report, report.files.any { it.url in pdfs })
        }
    }

    private val items = combine(itemsOf(c.dao.observeSaved()), itemsOf(c.dao.observeHistory())) { saved, history -> saved to history }

    private val highlightReports = c.dao.observeHighlightReports().map { list -> list.associateBy { it.id } }

    private val highlightsFiltered = combine(c.dao.observeAllHighlights(), highlightReports, query, highlightIds, filters) { all, reports, q, ids, f ->
        val searched = if (q.isBlank() || ids == null) all else all.filter { it.id in ids }
        Triple(all, searched.filter { h -> f.all { (facet, wanted) -> wanted.isEmpty() || facet.valuesOf(h, reports[h.reportId]).any { it in wanted } } }, reports)
    }

    val state: StateFlow<LibraryState> = combine(
        combine(view, query, ftsIds, pdfHits) { v, q, ids, hits -> Triple(v, q, ids) to hits },
        filters,
        groupBy,
        items,
        highlightsFiltered,
    ) { (search, hits), f, g, (saved, history), (allHighlights, highlights, reports) ->
        val (v, q, ids) = search
        fun build(all: List<LibraryItem>, view: LibraryView): ReadingList {
            val searched = if (q.isBlank() || ids == null) all else all.filter { it.report.id in ids }
            val filtered = searched.filter { item ->
                f.all { (facet, wanted) -> wanted.isEmpty() || facet.valuesOf(item.report).any { it in wanted } }
            }
            return ReadingList(all, group(filtered, view, g))
        }
        LibraryState(
            v, q, f, g, build(saved, LibraryView.SAVED), build(history, LibraryView.HISTORY),
            allHighlights, highlights, reports, loaded = true, pdfHits = if (q.isBlank()) emptyMap() else hits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    private fun group(list: List<LibraryItem>, v: LibraryView, g: GroupBy): List<Pair<String, List<LibraryItem>>> {
        if (g == GroupBy.DATE) {
            if (v == LibraryView.SAVED) return if (list.isEmpty()) emptyList() else listOf("" to list)
            return list.groupBy { Display.day(it.reading.reading.lastOpenedAt ?: 0L) }.toList()
        }
        val facet = when (g) {
            GroupBy.COUNTRY -> LibraryFacet.COUNTRY
            GroupBy.FORMAT -> LibraryFacet.FORMAT
            GroupBy.ORGANIZATION -> LibraryFacet.ORGANIZATION
            else -> LibraryFacet.THEME
        }
        val map = linkedMapOf<String, MutableList<LibraryItem>>()
        list.forEach { item ->
            facet.valuesOf(item.report).ifEmpty { listOf("Other") }.forEach { value -> map.getOrPut(value) { mutableListOf() } += item }
        }
        return map.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    fun setView(v: LibraryView) {
        view.value = v
    }

    fun setQuery(q: String) {
        query.value = q
        viewModelScope.launch {
            // The text of the PDFs read on the phone is searched too (pdftext.db).
            pdfHits.value = if (q.isBlank()) emptyMap() else c.pdfText.search(q).associateBy { it.reportId }
            ftsIds.value = if (q.isBlank()) null else c.repository.searchLibrary(q)
            highlightIds.value = if (q.isBlank()) null else c.repository.searchHighlights(q)
        }
    }

    fun deleteHighlight(id: Long) = viewModelScope.launch { c.repository.deleteHighlight(id) }

    /**
     * The Markdown export of the highlights left by the filters and the search. The title names a
     * single country or crisis when one is chosen, else the search.
     */
    fun export(): HighlightExport.Result {
        val s = state.value
        val single = listOf(LibraryFacet.COUNTRY, LibraryFacet.CRISIS).firstNotNullOfOrNull { s.filters[it]?.singleOrNull() }
        val label = single ?: s.query.trim().takeIf { it.isNotEmpty() }?.let { "\u201C$it\u201D" }
        return HighlightExport.build(s.highlights, s.highlightReports, label, System.currentTimeMillis())
    }

    fun setFilter(facet: LibraryFacet, values: Set<String>) {
        filters.value = filters.value + (facet to values)
    }

    fun clearFilters() {
        filters.value = emptyMap()
    }

    fun setGroupBy(g: GroupBy) {
        groupBy.value = g
    }

    fun removeSaved(id: Long) = viewModelScope.launch { c.repository.setSaved(id, false) }
    fun restoreSaved(id: Long) = viewModelScope.launch { c.repository.setSaved(id, true) }
    fun removeHistory(id: Long) = viewModelScope.launch { c.repository.removeFromHistory(id) }
}

private val FACET_LABELS = mapOf(
    LibraryFacet.COUNTRY to R.string.filter_country,
    LibraryFacet.CRISIS to R.string.library_crisis,
    LibraryFacet.PERIOD to R.string.library_period,
    LibraryFacet.FORMAT to R.string.filter_format,
    LibraryFacet.THEME to R.string.library_theme,
    LibraryFacet.ORGANIZATION to R.string.library_organization,
)

private val VIEW_LABELS = mapOf(
    LibraryView.SAVED to R.string.library_saved,
    LibraryView.HISTORY to R.string.library_history,
    LibraryView.HIGHLIGHTS to R.string.library_highlights,
)

private val GROUP_LABELS = mapOf(
    GroupBy.DATE to R.string.group_date,
    GroupBy.COUNTRY to R.string.filter_country,
    GroupBy.FORMAT to R.string.filter_format,
    GroupBy.ORGANIZATION to R.string.library_organization,
    GroupBy.THEME to R.string.library_theme,
)

/** M11 Library: Saved, History and Highlights, all on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { LibraryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var openFacet by remember { mutableStateOf<LibraryFacet?>(null) }
    var filterSheet by remember { mutableStateOf(false) }
    // The pager holds the current view: a swipe or a tap on a tab moves it.
    val pager = rememberPagerState { LibraryView.entries.size }
    OnPageSettled(pager) { vm.setView(LibraryView.entries[it]) }
    // Today's "See all" asks for History.
    LaunchedEffect(Unit) {
        container.libraryRequest.collect { page ->
            if (page != null) {
                pager.scrollToPage(page)
                container.libraryRequest.value = null
            }
        }
    }
    val removedMsg = stringResource(R.string.library_remove_saved)
    val undoLabel = stringResource(R.string.action_undo)
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.selection_clip_label)
    val copiedMsg = stringResource(R.string.selection_copied)
    var exportResult by remember { mutableStateOf<HighlightExport.Result?>(null) }
    var queryText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query) }

    ScreenScaffold(title = stringResource(R.string.tab_library), onSettings = nav::settings, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tabs in the style of 5.4: Inter, ink for the active one (revue-densite-v0.3.md, change 9).
            val cs = MaterialTheme.colorScheme
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp, modifier = Modifier.testTag("library_tabs")) {
                LibraryView.entries.forEachIndexed { index, v ->
                    val active = index == pager.currentPage
                    Tab(
                        selected = active,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        selectedContentColor = cs.onSurface,
                        unselectedContentColor = cs.onSurfaceVariant,
                        text = {
                            Text(
                                stringResource(VIEW_LABELS.getValue(v)),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                            )
                        },
                    )
                }
            }
            // Search and filters from ten items on, or while some are on (change 9); the facets
            // and "Group by" sit in a sheet behind the icon at the end of the field.
            val count = if (state.view == LibraryView.HIGHLIGHTS) state.allHighlights.size else state.list(state.view).all.size
            val active = state.filters.values.count { it.isNotEmpty() } + if (state.groupBy != GroupBy.DATE && state.view != LibraryView.HIGHLIGHTS) 1 else 0
            if (count >= SEARCH_FROM || queryText.isNotBlank() || active > 0) {
                // The field keeps its own text: bound to the view model's state, which lags, fast
                // typing lost or swapped letters ("measles" became "mealess" in an e2e run).
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it; vm.setQuery(it) },
                    label = {
                        Text(stringResource(if (state.view == LibraryView.HIGHLIGHTS) R.string.library_search_highlights else R.string.library_search))
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        val label = if (active > 0) stringResource(R.string.results_filters_count, active) else stringResource(R.string.results_filters)
                        IconButton(onClick = { filterSheet = true }, modifier = Modifier.testTag("library_filters")) {
                            BadgedBox(badge = { if (active > 0) Badge(containerColor = cs.primary) { Text(active.toString()) } }) {
                                Icon(Icons.Outlined.FilterList, contentDescription = label)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("library_search"),
                )
            }

            // The Library is on the phone: a pull only re-reads it, the spinner closes at once.
            Refreshable(loading = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val v = LibraryView.entries[page]
                if (v == LibraryView.HIGHLIGHTS) {
                    HighlightsList(
                        vm.listStates.getValue(LibraryView.HIGHLIGHTS),
                        state,
                        onOpen = { h ->
                            // A passage of a PDF opens the PDF's Text view on it (pdf/, T9).
                            scope.launch {
                                val target = container.pdfTarget(h)
                                val page = h.pdfPage
                                if (target != null && page != null) {
                                    nav.pdf(target.url, h.reportId, target.title, target.sourceUrl, page - 1, h.blockIndex)
                                } else {
                                    nav.report(h.reportId, if (h.pdfPage == null) h.blockIndex else -1)
                                }
                            }
                        },
                        onDelete = { vm.deleteHighlight(it.id) },
                        onClear = vm::clearFilters,
                        onCopy = { h ->
                            val report = state.highlightReports[h.reportId]
                            val source = report?.citationSource()
                                ?: CitationSource(listOfNotNull(h.sourceName), h.reportTitle, h.formatName, null, h.reportDate, null)
                            copyText(context, clipLabel, Citation.format(h.text, source, h.pdfPage))
                            scope.launch { snackbar.showSnackbar(copiedMsg) }
                        },
                        onExport = { exportResult = vm.export() },
                    )
                } else {
                    ReadingListPage(v, state, vm, nav, onRemoveSaved = { id ->
                        vm.removeSaved(id)
                        scope.launch {
                            val result = snackbar.showSnackbar(removedMsg, undoLabel)
                            if (result == SnackbarResult.ActionPerformed) vm.restoreSaved(id)
                        }
                    })
                }
            }
            }
        }
    }

    exportResult?.let { result ->
        ExportSheet(result, onDismiss = { exportResult = null })
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }, modifier = Modifier.testTag("library_filter_sheet")) {
            LibraryFilterSheet(
                state,
                onFacet = { filterSheet = false; openFacet = it },
                onGroupBy = vm::setGroupBy,
                onClear = { vm.clearFilters(); vm.setGroupBy(GroupBy.DATE) },
            )
        }
    }

    openFacet?.let { facet ->
        val values = if (state.view == LibraryView.HIGHLIGHTS) {
            state.allHighlights.flatMap { facet.valuesOf(it, state.highlightReports[it.reportId]) }
        } else {
            state.list(state.view).all.flatMap { facet.valuesOf(it.report) }
        }
        val counts = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        var selection by remember(facet) { mutableStateOf(state.filters[facet].orEmpty()) }
        AlertDialog(
            onDismissRequest = { openFacet = null },
            title = { Text(stringResource(FACET_LABELS.getValue(facet))) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    counts.forEach { (value, count) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selection = if (value in selection) selection - value else selection + value
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = value in selection, onCheckedChange = {
                                selection = if (it) selection + value else selection - value
                            })
                            Text(stringResource(R.string.library_value_count, value, count))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setFilter(facet, selection); openFacet = null }) { Text(stringResource(R.string.pick_done)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.setFilter(facet, emptySet()); openFacet = null }) { Text(stringResource(R.string.filter_clear_all)) }
            },
        )
    }
}

/** Search and filters show from this many items in a view. */
private const val SEARCH_FROM = 10

/** The facets of the view, each opening its list, then "Group by" for Saved and History. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFilterSheet(state: LibraryState, onFacet: (LibraryFacet) -> Unit, onGroupBy: (GroupBy) -> Unit, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = Space.xl).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.results_filters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text(stringResource(R.string.filter_clear_all)) }
        }
        state.view.facets().forEach { facet ->
            val selected = state.filters[facet].orEmpty()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onFacet(facet) }.padding(horizontal = Space.screen)
                    .testTag("library_facet_" + facet.name.lowercase()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(FACET_LABELS.getValue(facet)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    Text(selected.sorted().joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
            }
        }
        if (state.view != LibraryView.HIGHLIGHTS) {
            Text(
                stringResource(R.string.library_group_by, stringResource(GROUP_LABELS.getValue(state.groupBy))),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = Space.screen, end = Space.screen, top = Space.l, bottom = Space.s),
            )
            FlowRow(Modifier.padding(horizontal = Space.screen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupBy.entries.forEach { g ->
                    FilterChip(
                        selected = state.groupBy == g,
                        onClick = { onGroupBy(g) },
                        label = { Text(stringResource(GROUP_LABELS.getValue(g))) },
                        modifier = Modifier.testTag("library_group_" + g.name.lowercase()),
                    )
                }
            }
        }
    }
}

/** Saved or History, as one page of the Library pager. */
@Composable
private fun ReadingListPage(v: LibraryView, state: LibraryState, vm: LibraryViewModel, nav: Nav, onRemoveSaved: (Long) -> Unit) {
    val list = state.list(v)
    val removedMsg = stringResource(R.string.library_remove_saved)
    LazyColumn(Modifier.fillMaxSize().testTag("page_" + v.name.lowercase()), state = vm.listStates.getValue(v)) {
        // Found in the text of the PDFs read on the phone (theme-compose.md 10.12, pdf/).
        val pdfHits = state.pdfHits.values.mapNotNull { hit -> list.all.firstOrNull { it.report.id == hit.reportId }?.let { hit to it.report } }
        if (state.loaded && list.groups.isEmpty() && pdfHits.isEmpty()) {
            item {
                when {
                    list.all.isEmpty() -> EmptyState(
                        stringResource(if (v == LibraryView.SAVED) R.string.library_saved_empty else R.string.library_history_empty),
                    )
                    state.query.isNotBlank() -> EmptyState(stringResource(R.string.library_search_none, state.query))
                    else -> EmptyState(stringResource(R.string.library_filters_none), stringResource(R.string.results_clear_filters), vm::clearFilters)
                }
            }
        }
        list.groups.forEach { (title, groupItems) ->
            if (title.isNotEmpty()) item(key = "g$title") { SectionHeader(title) }
            items(groupItems, key = { "$title-${it.report.id}" }) { item ->
                val r = item.reading.reading
                val status = if (v == LibraryView.SAVED) {
                    listOfNotNull(
                        r.savedAt?.let { stringResource(R.string.library_saved_on, Display.day(it)) },
                        if (item.report.bodyHtml != null) stringResource(R.string.library_offline) else null,
                        if (item.pdfOnPhone) stringResource(R.string.library_pdf_on_phone) else null,
                    ).joinToString(" · ")
                } else {
                    listOfNotNull(
                        when {
                            r.finishedAt != null -> stringResource(R.string.library_finished)
                            r.pdfOpenedAt != null && r.percent == 0 -> stringResource(R.string.library_pdf_opened)
                            else -> stringResource(R.string.library_read_percent, r.percent)
                        },
                        r.lastOpenedAt?.let { Display.time(it) },
                    ).joinToString(" · ")
                }
                DocumentRow(item.report, onClick = { nav.report(item.report.id) }, status = status, pdfPreview = true) { close ->
                    if (v == LibraryView.SAVED) {
                        DropdownMenuItem(text = { Text(removedMsg) }, onClick = { close(); onRemoveSaved(item.report.id) })
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_remove_history)) },
                            onClick = { close(); vm.removeHistory(item.report.id) },
                        )
                    }
                }
            }
        }
        if (pdfHits.isNotEmpty()) {
            item(key = "pdf-hits") { SectionHeader(stringResource(R.string.library_found_in_pdfs), subtitle = pdfHits.size.toString()) }
            items(pdfHits, key = { "pdf-" + it.first.reportId }) { (hit, report) ->
                fun open() {
                    val file = report.files.firstOrNull { it.url == hit.url }
                    nav.pdf(hit.url, report.id, io.relais.app.pdf.pdfTitle(report, file), report.urlAlias, hit.page, 0, state.query)
                }
                DocumentRow(report, onClick = { open() }, pdfPreview = true, extra = {
                    io.relais.app.pdf.PageSnippet(hit.page, hit.snippet, onClick = { open() }, Modifier.testTag("pdf_hit_${report.id}"), terms = io.relais.app.pdf.PdfSearch.words(state.query))
                })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightsList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    state: LibraryState,
    onOpen: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
    onClear: () -> Unit,
    onCopy: (HighlightEntity) -> Unit,
    onExport: () -> Unit,
) {
    val extra = LocalRelaisColors.current
    LazyColumn(Modifier.fillMaxSize().testTag("page_highlights"), state = listState) {
        if (state.allHighlights.isNotEmpty()) {
            // The tools row of Highlights (theme-compose.md 10.14): the count, then "More options".
            item(key = "export") {
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = Space.screen, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.library_highlight_count, state.highlights.size, state.highlights.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("highlights_more")) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_action)) },
                                onClick = { menu = false; onExport() },
                                modifier = Modifier.testTag("export_markdown"),
                            )
                        }
                    }
                }
            }
        }
        if (state.loaded && state.highlights.isEmpty()) {
            item {
                when {
                    state.allHighlights.isEmpty() -> EmptyState(stringResource(R.string.library_highlights_empty))
                    state.query.isNotBlank() -> EmptyState(stringResource(R.string.library_search_none, state.query))
                    else -> EmptyState(stringResource(R.string.library_filters_none), stringResource(R.string.results_clear_filters), onClear)
                }
            }
        }
        items(state.highlights, key = { "hl" + it.id }) { h ->
            // A long press offers "Copy with citation" (passe-v0.2.md 4.3), as does the menu.
            var pressMenu by remember { mutableStateOf(false) }
            Column {
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(h) }, onLongClick = { pressMenu = true }), verticalAlignment = Alignment.Top) {
                    DropdownMenu(expanded = pressMenu, onDismissRequest = { pressMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_copy_citation)) }, onClick = { pressMenu = false; onCopy(h) })
                    }
                    Column(
                        Modifier.weight(1f).padding(start = Space.screen, top = Space.rowVertical, bottom = Space.rowVertical),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val kicker = listOfNotNull(
                            Display.shortFormat(null, h.formatName).ifBlank { null },
                            h.sourceName,
                            stringResource(R.string.highlight_on, Display.day(h.createdAt)),
                            h.pdfPage?.let { stringResource(R.string.highlight_page, it) },
                        ).joinToString(" · ")
                        Text(kicker.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            h.text,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.background(extra.highlight).padding(horizontal = 2.dp),
                        )
                        h.note?.let {
                            Text(stringResource(R.string.highlight_note_line, it), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                        }
                        Text(
                            listOfNotNull(h.countryName, h.reportTitle).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OverflowMenu { close ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_copy_citation)) }, onClick = { close(); onCopy(h) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.highlight_remove)) }, onClick = { close(); onDelete(h) })
                    }
                }
                HorizontalDivider(thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
            }
        }
    }
}

/**
 * "Export highlights" (theme-compose.md 10.14), full screen: the counts, the file name, the whole
 * file, then Share in a fixed footer. The file is shared as text/plain with its .md name and
 * its text: few apps declare text/markdown, and a text/markdown share sheet would leave out
 * mail and messaging apps.
 */
@Composable
private fun ExportSheet(result: HighlightExport.Result, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.export_title)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A dialog is a window of its own: it needs its own testTagsAsResourceId for Maestro.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        ScreenScaffold(title = title, onBack = onDismiss) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
                if (result.highlights == 0) {
                    EmptyState(stringResource(R.string.export_none))
                    return@Column
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(
                        stringResource(
                            R.string.export_counts,
                            pluralStringResource(R.plurals.export_reports, result.reports, result.reports),
                            pluralStringResource(R.plurals.export_highlights, result.highlights, result.highlights),
                        ),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(result.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            result.markdown,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
                            modifier = Modifier.padding(Space.m).testTag("export_preview"),
                        )
                    }
                }
                HorizontalDivider(thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = { shareMarkdown(context, result, title) },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.screen).testTag("export_share"),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.export_share), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        }
    }
}

private fun shareMarkdown(context: android.content.Context, result: HighlightExport.Result, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, result.fileName)
        .putExtra(Intent.EXTRA_TEXT, result.markdown)
    runCatching {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, result.fileName).apply { writeText(result.markdown) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(result.fileName, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}
