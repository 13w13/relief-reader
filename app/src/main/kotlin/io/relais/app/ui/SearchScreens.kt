// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.FacetValue
import io.relais.api.Ids
import io.relais.api.Requests
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.LoadError
import io.relais.app.data.ReportEntity
import io.relais.app.data.SearchParams
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

// --- M9 Search -------------------------------------------------------------------------------

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val recent: StateFlow<List<String>> = c.settings.flow.map { it.recentSearches }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Followed live: an appname saved in Settings opens Search at once (first launch review, 13/11). */
    val hasAccess: StateFlow<Boolean> = c.repository.access.stateIn(viewModelScope, SharingStarted.Eagerly, c.repository.hasAccess)

    fun remember(query: String) = viewModelScope.launch { c.settings.addRecentSearch(query) }
    fun clear() = viewModelScope.launch { c.settings.clearRecentSearches() }
}

@Composable
fun SearchScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { SearchViewModel(container) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val hasAccess by vm.hasAccess.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showTips by remember { mutableStateOf(false) }

    fun submit(text: String) {
        if (text.isBlank()) return
        vm.remember(text)
        nav.results(text.trim())
    }

    ScreenScaffold(title = stringResource(R.string.tab_search), onSettings = nav::settings) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                if (!hasAccess) {
                    Banner(stringResource(R.string.state_no_access), stringResource(R.string.action_open_settings), nav::settings)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_field)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    enabled = hasAccess,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                TextButton(onClick = { showTips = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.search_tips))
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.search_recent), modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clear() }) { Text(stringResource(R.string.search_clear)) }
                    }
                }
                items(recent) { text ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = hasAccess) { submit(text) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    if (showTips) {
        AlertDialog(
            onDismissRequest = { showTips = false },
            title = { Text(stringResource(R.string.search_tips)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.search_tips_examples), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.search_tips_legend), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showTips = false }) { Text(stringResource(R.string.pick_done)) } },
        )
    }
}

// --- M10 Results and filters -------------------------------------------------------------------

/** Format groups of the filter sheet: the five tabs of M8, plus Appeal and Other. */
private val FORMAT_GROUPS: List<Pair<Int, List<Long>>> = listOf(
    R.string.tab_sitreps to listOf(Ids.FORMAT_SITREP),
    R.string.tab_analysis to listOf(Ids.FORMAT_ANALYSIS, Ids.FORMAT_ASSESSMENT, Ids.FORMAT_EVALUATION),
    R.string.tab_maps to listOf(Ids.FORMAT_MAP, Ids.FORMAT_INFOGRAPHIC, Ids.FORMAT_INTERACTIVE),
    R.string.tab_news to listOf(Ids.FORMAT_NEWS),
    R.string.format_appeal to listOf(Ids.FORMAT_APPEAL),
    R.string.format_other to listOf(Ids.FORMAT_GUIDELINE, Ids.FORMAT_OTHER, Ids.FORMAT_UN_DOCUMENT),
)

internal val LANGUAGE_OPTIONS: List<Pair<Int, Long>> = listOf(
    R.string.language_english to Ids.LANGUAGE_ENGLISH,
    R.string.language_french to Ids.LANGUAGE_FRENCH,
    R.string.language_spanish to Ids.LANGUAGE_SPANISH,
    R.string.language_arabic to Ids.LANGUAGE_ARABIC,
)

internal val POSTED_OPTIONS: List<Pair<Int, Int?>> = listOf(
    R.string.posted_any to null,
    R.string.posted_7 to 7,
    R.string.posted_30 to 30,
    R.string.posted_365 to 365,
)

data class ResultsState(
    val params: SearchParams,
    val reports: List<ReportEntity> = emptyList(),
    val totalCount: Int = 0,
    val facets: Map<String, List<FacetValue>> = emptyMap(),
    val countryNames: Map<Long, String> = emptyMap(),
    val loading: Boolean = false,
    val error: LoadError? = null,
)

/**
 * Sorted by relevance, a search covers the past 12 months by default (shown as a removable
 * "Past 12 months" filter): otherwise "cholera" opens on reports from 2011. Newest has no
 * default period.
 */
private const val RELEVANCE_DEFAULT_DAYS = 365

/** First parameters of a results screen: a typed query is sorted by relevance, so it gets the default period. */
fun initialSearchParams(query: String, headlinesOnly: Boolean, reportsOnly: Boolean = false) = SearchParams(
    text = query,
    headlinesOnly = headlinesOnly,
    postedDays = if (query.isNotBlank()) RELEVANCE_DEFAULT_DAYS else null,
    // Today's "Latest reports on ReliefWeb", "See all" (P14): the reports, newest first.
    formatIds = if (reportsOnly) Requests.REPORT_FORMATS.toSet() else emptySet(),
    sort = if (reportsOnly) Requests.SearchSort.NEWEST else null,
)

/** Changes the sort; the default period follows relevance, a period the reader chose stays. */
fun SearchParams.withSort(sort: Requests.SearchSort): SearchParams = copy(
    sort = sort,
    postedDays = when {
        sort == Requests.SearchSort.NEWEST && postedDays == RELEVANCE_DEFAULT_DAYS -> null
        sort == Requests.SearchSort.RELEVANT && postedDays == null -> RELEVANCE_DEFAULT_DAYS
        else -> postedDays
    },
)

class ResultsViewModel(private val c: AppContainer, query: String, headlinesOnly: Boolean, reportsOnly: Boolean = false) : ViewModel() {
    /** The scroll of the results, kept here so that Back from a report finds the same place. */
    val listState = androidx.compose.foundation.lazy.LazyListState()

    private val _state = MutableStateFlow(ResultsState(initialSearchParams(query, headlinesOnly, reportsOnly)))
    val state: StateFlow<ResultsState> = _state

    init {
        viewModelScope.launch {
            c.dao.observeCountries().collect { list ->
                _state.value = _state.value.copy(countryNames = list.associate { it.id to it.displayName })
            }
        }
        search(_state.value.params)
    }

    fun search(params: SearchParams) {
        _state.value = _state.value.copy(params = params, reports = emptyList(), loading = true, error = null)
        load(offset = 0)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.reports.size >= s.totalCount) return
        _state.value = s.copy(loading = true, error = null)
        load(offset = s.reports.size)
    }

    private fun load(offset: Int) {
        viewModelScope.launch {
            val params = _state.value.params
            try {
                val page = c.repository.search(params, offset)
                val current = _state.value
                if (current.params != params) return@launch
                _state.value = current.copy(
                    reports = if (offset == 0) page.reports else current.reports + page.reports,
                    totalCount = page.totalCount,
                    facets = if (offset == 0) page.facets else current.facets,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.toLoadError())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(query: String, headlinesOnly: Boolean, nav: Nav, reportsOnly: Boolean = false) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "results-$query-$headlinesOnly-$reportsOnly") { ResultsViewModel(container, query, headlinesOnly, reportsOnly) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val params = state.params
    val activeFilters = params.countryIds.size + FORMAT_GROUPS.count { (_, ids) -> params.formatIds.containsAll(ids) } +
        params.languageIds.size + (if (params.postedDays != null) 1 else 0)
    val title = when {
        headlinesOnly -> stringResource(R.string.results_headlines_title)
        reportsOnly -> stringResource(R.string.today_latest_reports)
        else -> query
    }

    ScreenScaffold(title = title, onBack = { nav.back() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("page_results"), state = vm.listState) {
            item {
                if (state.loading && state.reports.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                LoadErrorBanner(state.error, onRetry = { vm.search(params) }, onOpenSettings = nav::settings, persistent = state.reports.isEmpty())
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.results_count, NumberFormat.getIntegerInstance(Locale.ENGLISH).format(state.totalCount)),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { showFilters = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = null)
                        Text(
                            if (activeFilters > 0) stringResource(R.string.results_filters_count, activeFilters)
                            else stringResource(R.string.results_filters),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (query.isNotBlank()) {
                    val sort = params.sort ?: Requests.SearchSort.RELEVANT
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sort == Requests.SearchSort.RELEVANT,
                            onClick = { vm.search(params.withSort(Requests.SearchSort.RELEVANT)) },
                            label = { Text(stringResource(R.string.results_relevant)) },
                        )
                        FilterChip(
                            selected = sort == Requests.SearchSort.NEWEST,
                            onClick = { vm.search(params.withSort(Requests.SearchSort.NEWEST)) },
                            label = { Text(stringResource(R.string.results_newest)) },
                        )
                    }
                }
                if (activeFilters > 0) {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        params.countryIds.forEach { id ->
                            RemovableChip(state.countryNames[id] ?: id.toString()) {
                                vm.search(params.copy(countryIds = params.countryIds - id))
                            }
                        }
                        FORMAT_GROUPS.filter { (_, ids) -> params.formatIds.containsAll(ids) }.forEach { (label, ids) ->
                            RemovableChip(stringResource(label)) { vm.search(params.copy(formatIds = params.formatIds - ids.toSet())) }
                        }
                        LANGUAGE_OPTIONS.filter { (_, id) -> id in params.languageIds }.forEach { (label, id) ->
                            RemovableChip(stringResource(label)) { vm.search(params.copy(languageIds = params.languageIds - id)) }
                        }
                        POSTED_OPTIONS.firstOrNull { it.second != null && it.second == params.postedDays }?.let { (label, _) ->
                            RemovableChip(stringResource(label)) { vm.search(params.copy(postedDays = null)) }
                        }
                    }
                }
            }
            if (!state.loading && state.error == null && state.reports.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.results_none, query),
                        if (activeFilters > 0) stringResource(R.string.results_clear_filters) else null,
                        if (activeFilters > 0) {
                            { vm.search(SearchParams(text = query, headlinesOnly = headlinesOnly)) }
                        } else {
                            null
                        },
                    )
                }
            }
            items(state.reports, key = { it.id }) { report ->
                DocumentRow(report, onClick = { nav.report(report.id) }, pdfPreview = true)
            }
            if (state.reports.isNotEmpty() && state.reports.size < state.totalCount) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else TextButton(onClick = { vm.loadMore() }) { Text(stringResource(R.string.results_load_more)) }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheet(
                params = params,
                facets = state.facets,
                countryNames = state.countryNames,
                onApply = { vm.search(it); showFilters = false },
            )
        }
    }
}

@Composable
internal fun RemovableChip(label: String, onRemove: () -> Unit) {
    val description = stringResource(R.string.filter_remove, label)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private fun facetCount(facets: Map<String, List<FacetValue>>, field: String, ids: Collection<Long>): Int {
    val values = facets[field] ?: return 0
    return values.filter { v -> v.value.toLongOrNull() in ids }.sumOf { it.count }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    params: SearchParams,
    facets: Map<String, List<FacetValue>>,
    countryNames: Map<Long, String>,
    onApply: (SearchParams) -> Unit,
) {
    var draft by remember(params) { mutableStateOf(params) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
    ) {
        Text(stringResource(R.string.filter_country), style = MaterialTheme.typography.titleSmall)
        val countryOptions = (facets["primary_country.id"].orEmpty().mapNotNull { it.value.toLongOrNull() } + draft.countryIds).distinct()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            countryOptions.forEach { id ->
                val count = facetCount(facets, "primary_country.id", listOf(id))
                val name = countryNames[id] ?: id.toString()
                FilterChip(
                    selected = id in draft.countryIds,
                    onClick = {
                        draft = draft.copy(countryIds = if (id in draft.countryIds) draft.countryIds - id else draft.countryIds + id)
                    },
                    label = { Text(if (count > 0) "$name $count" else name) },
                )
            }
        }

        Text(stringResource(R.string.filter_format), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FORMAT_GROUPS.forEach { (label, ids) ->
                val selected = draft.formatIds.containsAll(ids)
                val count = facetCount(facets, "format.id", ids)
                val text = stringResource(label)
                FilterChip(
                    selected = selected,
                    onClick = {
                        draft = draft.copy(formatIds = if (selected) draft.formatIds - ids.toSet() else draft.formatIds + ids)
                    },
                    label = { Text(if (count > 0) "$text $count" else text) },
                )
            }
        }

        Text(stringResource(R.string.filter_language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LANGUAGE_OPTIONS.forEach { (label, id) ->
                val count = facetCount(facets, "language.id", listOf(id))
                val text = stringResource(label)
                FilterChip(
                    selected = id in draft.languageIds,
                    onClick = {
                        draft = draft.copy(languageIds = if (id in draft.languageIds) draft.languageIds - id else draft.languageIds + id)
                    },
                    label = { Text(if (count > 0) "$text $count" else text) },
                )
            }
        }

        Text(stringResource(R.string.filter_posted), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            POSTED_OPTIONS.forEach { (label, days) ->
                FilterChip(
                    selected = draft.postedDays == days,
                    onClick = { draft = draft.copy(postedDays = days) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                draft = draft.copy(countryIds = emptySet(), formatIds = emptySet(), languageIds = emptySet(), postedDays = null)
            }) { Text(stringResource(R.string.filter_clear_all)) }
            Button(onClick = { onApply(draft) }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.filter_show_results))
            }
        }
    }
}
