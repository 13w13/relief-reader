// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.CountryTab
import io.relais.api.Display
import io.relais.api.RelaisJson
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.CountryEntity
import io.relais.app.data.FollowedCountryEntity
import io.relais.app.data.FollowedRow
import io.relais.app.data.LoadError
import io.relais.app.data.ReportEntity
import io.relais.app.data.Repository
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.res.pluralStringResource
import io.relais.api.CountryFilter
import io.relais.api.Ids
import io.relais.app.data.CountryPrefs
import io.relais.app.data.DisasterInfo
import io.relais.app.data.FeedMetaEntity
import io.relais.app.data.prefix
import io.relais.api.Scope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import io.relais.app.data.ScopeInfo
import io.relais.app.data.toInfo

/** Statuses that ReliefWeb shows as "Humanitarian situation". To confirm with the fixtures. */
val HUMANITARIAN_STATUSES = setOf("ongoing", "current")

// --- M2 Pick countries ------------------------------------------------------------------------

data class PickState(
    val countries: List<CountryEntity> = emptyList(),
    val followed: List<FollowedCountryEntity> = emptyList(),
    val loading: Boolean = false,
    val error: LoadError? = null,
)

class PickCountriesViewModel(private val c: AppContainer) : ViewModel() {
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)

    val state: StateFlow<PickState> =
        combine(c.dao.observeCountries(), c.dao.observeFollowed(), loading, error) { countries, followed, l, e ->
            PickState(countries, followed, l, e)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickState(loading = true))

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                c.repository.refreshCountries()
            } catch (e: Exception) {
                error.value = e.toLoadError()
            } finally {
                loading.value = false
            }
        }
    }

    fun toggle(country: CountryEntity, follow: Boolean) = viewModelScope.launch {
        if (follow) c.repository.follow(country.id, country.displayName) else c.repository.unfollow(country.id)
    }
}

@Composable
fun PickCountriesScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    onboarding: Boolean = false,
    onSkip: (() -> Unit)? = null,
) {
    val container = LocalContext.current.container
    val vm = viewModel { PickCountriesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val followedIds = state.followed.map { it.countryId }.toSet()
    val matches = state.countries.filter {
        query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) ||
            it.shortname?.contains(query.trim(), ignoreCase = true) == true
    }
    val humanitarian = matches.filter { it.status in HUMANITARIAN_STATUSES }

    ScreenScaffold(title = stringResource(R.string.pick_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.pick_help),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Headlines only: what following does before an appname (first launch review, 13/11).
            if (!container.repository.hasAccess) {
                Text(
                    stringResource(R.string.pick_headlines_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("pick_headlines_only"),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.pick_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.countries.isEmpty() && !state.loading) {
                Banner(stringResource(R.string.pick_loading_failed), stringResource(R.string.action_try_again), vm::load)
            }
            LazyColumn(Modifier.weight(1f)) {
                if (state.followed.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.pick_section_following)) }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            items(state.followed, key = { it.countryId }) { f ->
                                FilterChip(
                                    selected = true,
                                    onClick = {
                                        state.countries.firstOrNull { it.id == f.countryId }
                                            ?.let { vm.toggle(it, false) }
                                            ?: vm.toggle(CountryEntity(f.countryId, f.name, f.name, null, null), false)
                                    },
                                    label = { Text(f.name) },
                                )
                            }
                        }
                    }
                }
                if (query.isNotBlank() && matches.isEmpty() && state.countries.isNotEmpty()) {
                    item { EmptyState(stringResource(R.string.pick_no_result, query.trim())) }
                }
                if (humanitarian.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.pick_section_humanitarian)) }
                    items(humanitarian, key = { "h" + it.id }) { country ->
                        CountryCheckRow(country, country.id in followedIds) { vm.toggle(country, it) }
                    }
                }
                if (matches.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.pick_section_all)) }
                    items(matches, key = { "a" + it.id }) { country ->
                        CountryCheckRow(country, country.id in followedIds) { vm.toggle(country, it) }
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onboarding && onSkip != null) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.pick_skip)) }
                }
                Button(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(if (onboarding) R.string.pick_continue else R.string.pick_done))
                }
            }
        }
    }
}

@Composable
private fun CountryCheckRow(country: CountryEntity, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(country.displayName, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- M7 Following: the list behind "Manage" (theme-compose.md 10.2) ---------------------------

class ManageFollowingViewModel(private val c: AppContainer) : ViewModel() {
    /** Followed organizations and what each added since its last visit (v0.3). */
    val sources: StateFlow<List<Pair<io.relais.app.data.FollowedSourceEntity, Int>>> = c.repository.observeFollowedSourceRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setReportsOnly(id: Long, reportsOnly: Boolean) = viewModelScope.launch { c.repository.setSourceReportsOnly(id, reportsOnly) }

    fun unfollowSource(id: Long) = viewModelScope.launch { c.repository.unfollowSource(id) }

    val rows: StateFlow<List<FollowedRow>?> = c.repository.observeFollowedRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val crises: StateFlow<List<io.relais.app.data.FollowedDisasterRow>> = c.repository.observeFollowedDisasterRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<LoadError?>(null)
    val lastSyncAt: StateFlow<Long> = c.settings.flow.map { it.lastSyncAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun unfollow(id: Long) = viewModelScope.launch { c.repository.unfollow(id) }

    fun unfollowCrisis(id: Long) = viewModelScope.launch { c.repository.unfollowDisaster(id) }

    /** Pull to refresh: the grouped sync, one request for every followed country. */
    fun refresh() = viewModelScope.launch {
        if (loading.value) return@launch
        loading.value = true
        error.value = null
        try {
            c.repository.sync()
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }
}

/** What is followed, with its counts: add, stop following, open a page. */
@Composable
fun ManageFollowingScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { ManageFollowingViewModel(container) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val crises by vm.crises.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pull = rememberPull(lastSyncAt, snackbar) { vm.refresh() }

    ScreenScaffold(title = stringResource(R.string.following_manage_title), onBack = { nav.back() }, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LoadErrorBanner(error, onRetry = { vm.refresh() }, onOpenSettings = nav::settings, persistent = rows.isNullOrEmpty(), snackbar = snackbar)
            Refreshable(loading = loading, onRefresh = pull, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("page_manage_following")) {
                    val list = rows ?: return@LazyColumn
                    // theme-compose.md 10.2: one list, the countries then the disasters, each with its type.
                    if (list.isEmpty() && crises.isEmpty()) {
                        item {
                            EmptyState(stringResource(R.string.following_empty), stringResource(R.string.today_choose_countries), nav::pickCountries)
                        }
                    }
                    items(list, key = { it.countryId }) { row ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.country(row.countryId, row.name) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.following_kind_country).uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                                val status = if (row.status in HUMANITARIAN_STATUSES) stringResource(R.string.following_humanitarian) else null
                                val last = row.lastReportAt?.let { stringResource(R.string.following_last_report, Display.day(it)) }
                                Text(
                                    newCountLine(status, row) + androidx.compose.ui.text.AnnotatedString(last?.let { " · $it" }.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OverflowMenu { close ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.following_stop)) },
                                    onClick = { close(); vm.unfollow(row.countryId) },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    items(crises, key = { "d" + it.disaster.disasterId }) { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                FollowedCrisisRow(row) { nav.crisis(row.disaster.disasterId, row.disaster.name) }
                            }
                            // A past disaster stays until the reader stops following it (passe-v0.2.md 1.3).
                            if (row.disaster.status == "past") {
                                TextButton(onClick = { vm.unfollowCrisis(row.disaster.disasterId) }) { Text(stringResource(R.string.following_stop)) }
                            } else {
                                OverflowMenu { close ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.following_stop)) },
                                        onClick = { close(); vm.unfollowCrisis(row.disaster.disasterId) },
                                    )
                                }
                            }
                        }
                    }
                    if (crises.isEmpty() && list.isNotEmpty()) {
                        item(key = "crises_empty") {
                            Text(
                                stringResource(R.string.following_crises_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    // Organizations (13.2): after countries and crises, with their choice and a menu.
                    item(key = "organizations") { SectionHeader(stringResource(R.string.following_organizations)) }
                    items(sources, key = { "o" + it.first.sourceId }) { (source, newCount) ->
                        FollowedSourceRow(
                            source, newCount,
                            onOpen = { nav.source(source.sourceId, source.shortname) },
                            onReportsOnly = { vm.setReportsOnly(source.sourceId, it) },
                            onStop = { vm.unfollowSource(source.sourceId) },
                        )
                    }
                    item(key = "follow_source") { FollowSourceLine(nav::pickSources) }
                    item(key = "follow_buttons") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Text only: with an icon, "Follow a country" does not fit half of a 360 dp screen.
                            OutlinedButton(
                                onClick = nav::pickCountries,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.weight(1f).testTag("follow_country"),
                            ) {
                                Text(stringResource(R.string.following_add), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = nav::pickCrises,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.weight(1f).testTag("follow_crisis"),
                            ) {
                                Text(stringResource(R.string.following_add_crisis), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Following: one feed (theme-compose.md 12.3) -----------------------------------------------

/** The feed of what is followed: documents of the followed countries and crises, once each. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FollowingViewModel(private val c: AppContainer) : ViewModel() {
    val rows: StateFlow<List<FollowedRow>?> = c.dao.observeFollowedRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sources: StateFlow<List<io.relais.app.data.FollowedSourceEntity>> = c.dao.observeFollowedSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val crises: StateFlow<List<io.relais.app.data.FollowedDisasterRow>> = c.dao.observeFollowedDisasterRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<LoadError?>(null)
    val lastSyncAt: StateFlow<Long> = c.settings.flow.map { it.lastSyncAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val selected = MutableStateFlow(PageFilter.ALL)

    /**
     * The scroll of the feed, kept here so that Back from a report finds the same place
     * (founder, 13/11). Only another filter moves it.
     */
    val listState = LazyListState()

    /** The last opening of Following before this one: what was posted since has the dot. */
    var previousVisit: Long = Long.MAX_VALUE
        private set

    /** The followed pages' addresses in ReliefWeb's most-read file, page after page. */
    private val mostReadUrls: Flow<List<String>> = combine(c.popular.mostRead, c.dao.observeFollowed(), c.dao.observeFollowedDisasters()) { cache, countries, crises ->
        (countries.map { it.countryId } + crises.map { it.disasterId }).flatMap { cache?.urls(it.toString()).orEmpty() }.distinct()
    }

    val filters: StateFlow<List<PageFilter>> = mostReadUrls.map { if (it.isEmpty()) PageFilter.FEEDS else PageFilter.entries }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageFilter.FEEDS)

    val filter: StateFlow<PageFilter> = combine(selected, filters) { f, list -> if (f in list) f else PageFilter.ALL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageFilter.ALL)

    /** From the phone: what the daily sync and the pages brought; null until read. */
    val feed: StateFlow<List<ReportEntity>?> = selected.flatMapLatest { f ->
        val tab = f.tab
        if (tab == null) {
            mostReadUrls.mapLatest { urls -> runCatching { c.repository.reportsByUrls(urls) }.getOrDefault(emptyList()) }
        } else {
            c.dao.observeFollowingFeed(tab.formatIds.isEmpty(), tab.formatIds.ifEmpty { listOf(-1L) }, tab.includesHeadlines, FEED_LIMIT)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            previousVisit = c.settings.current().followingVisitAt.takeIf { it > 0 } ?: Long.MAX_VALUE
            c.settings.setFollowingVisitAt(System.currentTimeMillis())
        }
    }

    fun select(f: PageFilter) {
        if (f == selected.value) return
        selected.value = f
        // Another filter starts at the top of its list, the row still under the top bar.
        viewModelScope.launch { if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1) }
    }

    /** Pull to refresh: the grouped sync, one request for every followed country. */
    fun refresh() = viewModelScope.launch {
        if (loading.value) return@launch
        loading.value = true
        error.value = null
        try {
            c.repository.sync()
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }

    private companion object {
        const val FEED_LIMIT = 200
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FollowingScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { FollowingViewModel(container) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val crises by vm.crises.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pull = rememberPull(lastSyncAt, snackbar) { vm.refresh() }
    val groups = remember(feed) { feed.orEmpty().let(::twinGroups) }
    val list = vm.listState

    ScreenScaffold(title = stringResource(R.string.following_title), onSettings = nav::settings, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LoadErrorBanner(error, onRetry = { vm.refresh() }, onOpenSettings = nav::settings, persistent = feed.isNullOrEmpty(), snackbar = snackbar)
            Refreshable(loading = loading, onRefresh = pull, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("page_following"), state = list) {
                    val countries = rows ?: return@LazyColumn
                    if (countries.isEmpty() && crises.isEmpty() && sources.isEmpty()) {
                        item(key = "empty") { FollowEmpty(nav) }
                        return@LazyColumn
                    }
                    item(key = "followed") {
                        FollowedLine(
                            countries.map { FollowedName(it.name, "following_name_country_${it.countryId}") { nav.country(it.countryId, it.name) } } +
                                crises.map { FollowedName(crisisShort(it.disaster.name), "following_name_crisis_${it.disaster.disasterId}") { nav.crisis(it.disaster.disasterId, it.disaster.name) } } +
                                sources.map { source -> FollowedName(source.shortname, "following_name_source_${source.sourceId}") { nav.source(source.sourceId, source.shortname) } },
                            onManage = nav::manageFollowing,
                        )
                    }
                    stickyHeader(key = "filters") { FilterRow(filters, filter, vm::select) }
                    val reports = feed ?: return@LazyColumn
                    if (reports.isEmpty() && !loading) {
                        item(key = "none") {
                            EmptyState(
                                if (filter == PageFilter.ALL) stringResource(R.string.following_feed_nothing)
                                else stringResource(R.string.following_feed_none, stringResource(filter.label).lowercase()),
                            )
                        }
                    }
                    // Each line keeps its main country ("Sudan +7"); new since the last visit: the dot.
                    items(groups, key = { it.main.id }) { group ->
                        val report = group.main
                        DocumentRow(
                            report,
                            onClick = { nav.report(report.id) },
                            isNew = report.dateCreated > vm.previousVisit,
                            extra = if (group.others.isEmpty()) null else { { AlsoIn(group.others, onOpen = { nav.report(it) }) } },
                        )
                    }
                }
            }
        }
    }
}

/** A name on the line of what is followed, and the page it opens. */
internal data class FollowedName(val label: String, val tag: String, val open: () -> Unit)

/**
 * What is followed, on one line: the names that fit, each opening its page (revue-ux-v0.3.md,
 * change 6), then "+N" for the others, which opens Manage like the button.
 */
@Composable
internal fun FollowedLine(names: List<FollowedName>, onManage: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Space.screen, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            val width = constraints.maxWidth
            val labels = names.map { it.label }
            val shown = remember(labels, width, style) {
                fitCount(labels, width) { measurer.measure(it, style, softWrap = false, maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)).hasVisualOverflow }
            }
            Row(Modifier.testTag("following_line"), verticalAlignment = Alignment.CenterVertically) {
                names.take(shown).forEachIndexed { i, name ->
                    if (i > 0) Text(", ", style = style, color = cs.onSurfaceVariant)
                    Text(
                        name.label,
                        style = style,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).clickable(onClick = name.open)
                            .wrapContentHeight(Alignment.CenterVertically).testTag(name.tag),
                    )
                }
                if (shown < names.size) {
                    Text(
                        " +${names.size - shown}",
                        style = style,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onManage).wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.testTag("following_manage")) { Text(stringResource(R.string.following_manage)) }
    }
}

/** How many names fit on the line, with "+N" for the others; at least one. */
internal fun fitCount(names: List<String>, width: Int, overflows: (String) -> Boolean): Int {
    if (names.isEmpty() || width <= 0) return names.size
    for (k in names.size downTo 1) {
        val text = names.take(k).joinToString(", ") + if (k < names.size) " +${names.size - k}" else ""
        if (!overflows(text)) return k
    }
    return 1
}

/** Nothing followed yet: the two ways to start (12.3). */
@Composable
private fun FollowEmpty(nav: Nav) {
    Column(Modifier.fillMaxWidth()) {
        EmptyState(stringResource(R.string.following_feed_empty))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = nav::pickCountries, shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f).testTag("follow_country")) {
                Text(stringResource(R.string.following_add), maxLines = 1)
            }
            OutlinedButton(onClick = nav::pickCrises, shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f).testTag("follow_crisis")) {
                Text(stringResource(R.string.following_add_crisis), maxLines = 1)
            }
        }
    }
}

// --- M8 Country and crisis pages (theme-compose.md 12) -----------------------------------------

/** Does a document belong to a tab? Key documents also keep every editors' headline. */
fun CountryTab.matches(r: ReportEntity): Boolean =
    formatIds.isEmpty() || r.formatId in formatIds || (includesHeadlines && r.isHeadline)

private val LANGUAGE_IDS = mapOf(
    "English" to Ids.LANGUAGE_ENGLISH,
    "French" to Ids.LANGUAGE_FRENCH,
    "Spanish" to Ids.LANGUAGE_SPANISH,
    "Arabic" to Ids.LANGUAGE_ARABIC,
)

/** The sheet's filters applied to a document on the phone: Most read has no API feed to filter. */
private fun CountryFilter.matches(r: ReportEntity, now: Long): Boolean =
    (sources.isEmpty() || r.sources.any { it.id in sources }) &&
        (themes.isEmpty() || r.themes.any { it.id in themes }) &&
        (languages.isEmpty() || r.languages.any { LANGUAGE_IDS[it] in languages }) &&
        (postedDays?.let { r.dateCreated >= now - it * Repository.ONE_DAY } ?: true)

/** Names of organizations and themes, from the documents on the phone. */
data class SheetNames(val sources: Map<Long, String> = emptyMap(), val themes: Map<Long, String> = emptyMap())

data class CountryState(
    /** False until the remembered sheet filters are read. */
    val ready: Boolean = false,
    /** The active filter of the row; All at every opening (12.1). */
    val filter: PageFilter = PageFilter.ALL,
    /** Organizations, languages and themes of the sheet, remembered for this page. */
    val sheet: CountryFilter = CountryFilter(),
    /** The filters of the row: Most read only when ReliefWeb's file has a line for this page. */
    val filters: List<PageFilter> = PageFilter.FEEDS,
    /** Field, value, count: the values of the filter sheet. */
    val facets: Map<String, Map<String, Int>> = emptyMap(),
    val names: SheetNames = SheetNames(),
    val fetchedAt: Long? = null,
    val following: Boolean = false,
    val country: CountryEntity? = null,
    /** The crisis of a crisis page: from call A, else from Following. */
    val crisis: DisasterInfo? = null,
    /** Call A of a crisis page: its description, for "About this disaster". */
    val info: ScopeInfo? = null,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val moreError: Boolean = false,
)

data class CountryFeed(val reports: List<ReportEntity>, val fetched: Boolean, val exhausted: Boolean)

/**
 * The page of a country or of a crisis ([scope]): one list under a row of filters, the newest
 * first. [initial] is All, or the filter a link asked for (Most read from its screen).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CountryViewModel(private val c: AppContainer, val scope: Scope, private val name: String, initial: PageFilter) : ViewModel() {
    val isCrisis: Boolean = scope.kind == Scope.Kind.DISASTER

    /** An organization's page (v0.3, theme-compose.md 13.1). */
    val isSource: Boolean = scope.kind == Scope.Kind.SOURCE

    private val selected = MutableStateFlow(initial)
    private val sheet = MutableStateFlow(CountryFilter())
    private val ready = MutableStateFlow(false)
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)
    private val moreError = MutableStateFlow(false)

    /** Remembered sheet filters; a crisis is stored under its negated id. */
    private val prefsId = if (isCrisis) -scope.id else scope.id

    /** The scroll of the page, kept here so that Back from a report finds the same place. */
    val listState = LazyListState()

    /** Another filter starts at the top of its list, the row still under the top bar. */
    private fun toFilterRow() = viewModelScope.launch { if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1) }

    /** Last visit before this one: documents posted after it get the blue dot. */
    var previousVisit: Long = Long.MAX_VALUE
        private set

    /** This page's addresses in ReliefWeb's most-read file, in its order (empty: no line). */
    private val mostReadUrls: Flow<List<String>> = c.popular.mostRead.map { it?.urls(scope.id.toString()).orEmpty() }

    private val filterAndSheet = combine(selected, sheet) { f, s -> f to s }

    /** The list under the row: one feed of the API, or the Most read documents in the file's order. */
    val feed: StateFlow<CountryFeed?> = filterAndSheet.flatMapLatest { (f, s) ->
        val tab = f.tab
        if (tab != null) {
            val key = c.repository.feedKey(scope, tab, s)
            combine(c.dao.observeFeed(key), c.dao.observeFeedMeta(key)) { reports, meta ->
                CountryFeed(reports, fetched = meta != null, exhausted = meta?.exhausted == true)
            }
        } else {
            mostReadUrls.mapLatest { urls ->
                val now = System.currentTimeMillis()
                val reports = runCatching { c.repository.reportsByUrls(urls) }.getOrDefault(emptyList())
                CountryFeed(reports.filter { s.matches(it, now) }, fetched = true, exhausted = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What is known of the current feed, and the values of the sheet (All's for Most read). */
    private val current: Flow<Pair<FeedMetaEntity?, Map<String, Map<String, Int>>>> = filterAndSheet.flatMapLatest { (f, s) ->
        val tab = f.tab ?: CountryTab.ALL
        // The sheet shows the counts of the feed without filters when they are known, so that
        // choosing one organization does not hide the others.
        combine(
            c.dao.observeFeedMeta(c.repository.feedKey(scope, tab, s)),
            c.dao.observeFeedMeta(c.repository.feedKey(scope, tab)),
        ) { meta, base -> meta to Repository.facetsOf(base).ifEmpty { Repository.facetsOf(meta) } }
    }

    private val names = c.repository.observeScopeTags(scope).map { rows ->
        SheetNames(
            sources = rows.flatMap { it.sources }.associate { it.id to it.shortname.ifBlank { it.name } },
            themes = rows.flatMap { it.themes }.associate { it.id to it.name },
        )
    }

    private val filters: Flow<List<PageFilter>> = if (isSource) {
        // An organization: no chip for a format without any document in 12 months (13.1).
        c.repository.observeScopeInfo(scope).map { info ->
            val formats = info?.source?.formats.orEmpty().filterValues { it > 0 }.keys
            if (formats.isEmpty()) PageFilter.FEEDS
            else PageFilter.FEEDS.filter { f -> f == PageFilter.ALL || f.tab?.formatIds.orEmpty().any { it in formats } }
        }
    } else {
        mostReadUrls.map { if (it.isEmpty()) PageFilter.FEEDS else PageFilter.entries }
    }

    private val following: Flow<Boolean> = if (isSource) {
        c.dao.observeFollowedSource(scope.id).map { it != null }
    } else if (isCrisis) {
        c.dao.observeFollowedDisaster(scope.id).map { it != null }
    } else {
        c.dao.observeFollowed().map { list -> list.any { it.countryId == scope.id } }
    }

    private val header: Flow<Triple<CountryEntity?, DisasterInfo?, ScopeInfo?>> = if (isSource) {
        c.repository.observeScopeInfo(scope).map { Triple(null, null, it) }
    } else if (isCrisis) {
        combine(c.repository.observeScopeInfo(scope), c.dao.observeFollowedDisaster(scope.id)) { info, followed ->
            Triple(null, info?.disaster ?: followed?.toInfo(), info)
        }
    } else {
        c.dao.observeCountry(scope.id).map { Triple(it, null, null) }
    }

    val state: StateFlow<CountryState> = combine(
        combine(filterAndSheet, ready, filters) { fs, r, list -> Triple(fs, r, list) },
        combine(current, names) { cur, n -> cur to n },
        following,
        header,
        combine(loading, error, moreError) { l, e, m -> Triple(l, e, m) },
    ) { (fs, isReady, list), (cur, n), follows, (country, crisis, info), (l, e, m) ->
        val (f, s) = fs
        CountryState(
            ready = isReady,
            // Asked for Most read, with no line in the file: All.
            filter = if (f in list) f else PageFilter.ALL,
            sheet = s,
            filters = list,
            facets = cur.second,
            names = n,
            fetchedAt = cur.first?.fetchedAt,
            following = follows,
            country = country,
            crisis = crisis,
            info = info,
            loading = l,
            error = e,
            moreError = m,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountryState(loading = true))

    init {
        viewModelScope.launch {
            sheet.value = c.settings.countryPrefs(prefsId).first().filter
            previousVisit = when {
                isSource -> c.dao.followedSource(scope.id)?.lastVisitAt
                isCrisis -> c.dao.followedDisaster(scope.id)?.lastVisitAt
                else -> c.dao.followedCountry(scope.id)?.lastVisitAt
            } ?: Long.MAX_VALUE
            ready.value = true
            load(force = false)
            when {
                isSource -> c.repository.markSourceVisited(scope.id)
                isCrisis -> c.repository.markDisasterVisited(scope.id)
                else -> c.repository.markCountryVisited(scope.id)
            }
        }
        // The head of a crisis or organization page: its info alone, kept 24 hours.
        if (isCrisis || isSource) viewModelScope.launch { runCatching { c.repository.loadScopeInfo(scope) } }
    }

    /** One filter at a time: tapping another replaces it (12.1). */
    fun select(f: PageFilter) {
        if (f == selected.value) return
        selected.value = f
        moreError.value = false
        toFilterRow()
        viewModelScope.launch { load(force = false) }
    }

    fun setSheet(f: CountryFilter) {
        if (f == sheet.value) return
        sheet.value = f
        toFilterRow()
        viewModelScope.launch {
            c.settings.setCountryPrefs(prefsId, CountryPrefs(filter = f))
            load(force = false)
        }
    }

    fun clearSheet() = setSheet(CountryFilter())

    fun refresh() = viewModelScope.launch { load(force = true) }

    private suspend fun load(force: Boolean) {
        // Most read reads the file Today and the daily sync keep; its documents load as it shows.
        val tab = selected.value.tab ?: return
        if (!c.repository.hasAccess) {
            error.value = LoadError.NO_ACCESS
            return
        }
        loading.value = true
        error.value = null
        try {
            c.repository.loadScopeTab(scope, tab, sheet.value, force = force)
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }

    fun loadOlder() = viewModelScope.launch {
        val tab = selected.value.tab ?: return@launch
        moreError.value = false
        try {
            c.repository.loadScopeTab(scope, tab, sheet.value, older = true)
        } catch (e: Exception) {
            moreError.value = true
        }
    }

    /** Follows an organization: in full, or "Reports only" (13.2). */
    fun followSource(reportsOnly: Boolean) = viewModelScope.launch {
        val info = state.value.info?.source ?: return@launch
        c.repository.followSource(info, reportsOnly)
    }

    fun setFollowing(follow: Boolean) = viewModelScope.launch {
        when {
            isSource && follow -> state.value.info?.source?.let { c.repository.followSource(it, reportsOnly = false) }
            isSource -> c.repository.unfollowSource(scope.id)
            !isCrisis && follow -> c.repository.follow(scope.id, name)
            !isCrisis -> c.repository.unfollow(scope.id)
            follow -> c.repository.followDisaster(state.value.crisis ?: DisasterInfo(scope.id, name))
            else -> c.repository.unfollowDisaster(scope.id)
        }
    }
}

@Composable
fun CountryScreen(countryId: Long, name: String, filter: String, nav: Nav) =
    ScopeScreen(Scope.country(countryId), name, PageFilter.of(filter), nav)

@Composable
fun CrisisScreen(disasterId: Long, name: String, filter: String, nav: Nav) =
    ScopeScreen(Scope.disaster(disasterId), name, PageFilter.of(filter), nav)

/** An organization's page (v0.3, theme-compose.md 13.1): the skeleton of a country page. */
@Composable
fun SourceScreen(sourceId: Long, name: String, filter: String, nav: Nav) =
    ScopeScreen(Scope.source(sourceId), name, PageFilter.of(filter), nav)

@Composable
private fun ScopeScreen(scope: Scope, name: String, initial: PageFilter, nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "${scope.prefix}-${scope.id}") { CountryViewModel(container, scope, name, initial) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val full = name.ifBlank { state.crisis?.name ?: state.info?.source?.shortname ?: state.country?.displayName.orEmpty() }
    // A crisis: its short name in the top bar, the full name in its head (12.5).
    val title = if (vm.isCrisis) crisisShort(full) else full
    var asking by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = title,
        onBack = { nav.back() },
        actions = {
            FollowChip(state.following) {
                // A large organization asks first: "Reports only" or "Everything" (13.2).
                val source = state.info?.source
                if (vm.isSource && !state.following && source != null && source.large) asking = true else vm.setFollowing(!state.following)
            }
        },
        snackbarHostState = snackbar,
    ) { padding ->
        if (state.ready) ScopeContent(full, state, vm, nav, snackbar, Modifier.padding(padding))
    }
    val source = state.info?.source
    if (asking && source != null) {
        FollowSourceSheet(source, onFollow = { vm.followSource(it); asking = false }, onDismiss = { asking = false })
    }
}

/** "Follow" or "Following", in the top bar (12.2). */
@Composable
private fun FollowChip(following: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = following,
        onClick = onClick,
        label = { Text(stringResource(if (following) R.string.country_following else R.string.country_follow)) },
        leadingIcon = if (following) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        shape = CircleShape,
        modifier = Modifier.padding(end = 8.dp).testTag("follow_button"),
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScopeContent(name: String, state: CountryState, vm: CountryViewModel, nav: Nav, snackbar: SnackbarHostState, modifier: Modifier) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val groups = remember(feed) { feed?.reports.orEmpty().let(::twinGroups) }
    var showSheet by remember { mutableStateOf(false) }
    // Kept by the view model: Back from a report comes back to the same place (13/11).
    val list = vm.listState

    Column(modifier.fillMaxSize()) {
        LoadErrorBanner(state.error, onRetry = { vm.refresh() }, onOpenSettings = nav::settings, cachedAt = state.fetchedAt, persistent = feed?.reports.isNullOrEmpty(), snackbar = snackbar)
        val pull = rememberPull(state.fetchedAt, snackbar) { vm.refresh() }
        Refreshable(loading = state.loading, onRefresh = pull, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("page_" + state.filter.tag), state = list) {
                item(key = "head") {
                    if (vm.isSource) {
                        SourceHeader(state.info?.source, name)
                    } else if (vm.isCrisis) {
                        CrisisHeader(state.info, state.crisis, name, onCountry = { nav.country(it.id, it.name) })
                    } else {
                        CountryHeader(state.country, onOverview = { nav.overview(vm.scope.id, name, vm.previousVisit) })
                    }
                }
                stickyHeader(key = "filters") {
                    FilterRow(state.filters, state.filter, vm::select, sheetCount = state.sheet.count, onSheet = { showSheet = true })
                }
                if (!state.sheet.isEmpty) item(key = "active") { ActiveFilters(state, onChange = vm::setSheet) }
                val f = feed ?: return@LazyColumn
                // A feed never fetched stays blank until it loads, rather than saying it is empty.
                if (f.reports.isEmpty() && f.fetched && !state.loading && state.error == null) {
                    item(key = "empty") {
                        when {
                            !state.sheet.isEmpty -> EmptyState(stringResource(R.string.country_filters_none), stringResource(R.string.results_clear_filters), vm::clearSheet)
                            state.filter == PageFilter.MOST_READ -> EmptyState(stringResource(R.string.most_read_none))
                            vm.isSource && state.filter == PageFilter.ALL -> EmptyState(stringResource(R.string.source_empty, name))
                            else -> EmptyState(stringResource(R.string.country_empty, stringResource(state.filter.label).lowercase(), name))
                        }
                    }
                }
                // One line per document, language twins merged ("Also in French"); new ones have the dot.
                items(groups, key = { it.main.id }) { group ->
                    val report = group.main
                    DocumentRow(
                        report,
                        onClick = { nav.report(report.id) },
                        isNew = report.dateCreated > vm.previousVisit,
                        // The country stays on a crisis's or an organization's rows (13.1).
                        inCountry = !vm.isCrisis && !vm.isSource,
                        onSourcePage = vm.scope.id.takeIf { vm.isSource },
                        extra = if (group.others.isEmpty()) null else { { AlsoIn(group.others, onOpen = { nav.report(it) }) } },
                    )
                }
                if (f.reports.isNotEmpty() && state.filter.tab != null) {
                    item(key = "more") {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                state.moreError -> {
                                    Text(stringResource(R.string.country_load_more_failed), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.loadOlder() }) { Text(stringResource(R.string.action_try_again)) }
                                }
                                f.exhausted -> Text(stringResource(R.string.country_end), style = MaterialTheme.typography.bodySmall)
                                else -> TextButton(onClick = { vm.loadOlder() }) { Text(stringResource(R.string.country_show_older)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CountryFilterSheet(
                name = name,
                state = state,
                onApply = { vm.setSheet(it); showSheet = false },
                onClear = { vm.clearSheet(); showSheet = false },
            )
        }
    }
}

/**
 * Under the top bar of a country (12.2): its full name when the bar shows a short one, its
 * status, and "Overview" at the right, with no count of documents.
 */
@Composable
private fun CountryHeader(country: CountryEntity?, onOverview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = Space.screen, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (country?.shortname != null && country.shortname != country.name) {
                Text(country.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (country?.status in HUMANITARIAN_STATUSES) {
                Text(stringResource(R.string.following_humanitarian), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        TextButton(onClick = onOverview, contentPadding = PaddingValues(start = 12.dp, end = 8.dp), modifier = Modifier.testTag("open_overview")) {
            Text(stringResource(R.string.tab_overview))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** Active sheet filters as a scrolling row of removable chips; nothing when no filter is on. */
@Composable
private fun ActiveFilters(state: CountryState, onChange: (CountryFilter) -> Unit) {
    val f = state.sheet
    if (f.isEmpty) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = Space.screen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(f.sources.toList(), key = { "s$it" }) { id ->
            RemovableChip(state.names.sources[id] ?: id.toString()) { onChange(f.copy(sources = f.sources - id)) }
        }
        items(LANGUAGE_OPTIONS.filter { (_, id) -> id in f.languages }, key = { "l${it.second}" }) { (label, id) ->
            RemovableChip(stringResource(label)) { onChange(f.copy(languages = f.languages - id)) }
        }
        items(f.themes.toList(), key = { "t$it" }) { id ->
            RemovableChip(state.names.themes[id] ?: id.toString()) { onChange(f.copy(themes = f.themes - id)) }
        }
        POSTED_OPTIONS.firstOrNull { it.second != null && it.second == f.postedDays }?.let { (label, _) ->
            item(key = "d") { RemovableChip(stringResource(label)) { onChange(f.copy(postedDays = null)) } }
        }
    }
}

/**
 * "Filter Sudan" (theme-compose.md 9.3): organization, language and theme, with the counts of
 * the tab. The period stays in Search for now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountryFilterSheet(name: String, state: CountryState, onApply: (CountryFilter) -> Unit, onClear: () -> Unit) {
    var draft by remember(state.sheet) { mutableStateOf(state.sheet) }
    fun counts(field: String): List<Pair<Long, Int>> =
        state.facets[field].orEmpty().mapNotNull { (value, count) -> value.toLongOrNull()?.let { it to count } }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.country_filter_title, name), style = MaterialTheme.typography.titleSmall)

            val sources = (counts("source.id").filter { it.first in state.names.sources } + draft.sources.map { it to 0 }).distinctBy { it.first }
            if (sources.isNotEmpty()) {
                SheetGroup(stringResource(R.string.library_organization))
                ChipGroup(sources, draft.sources, { state.names.sources[it] ?: it.toString() }) { id ->
                    draft = draft.copy(sources = if (id in draft.sources) draft.sources - id else draft.sources + id)
                }
            }

            SheetGroup(stringResource(R.string.filter_language))
            val languageCounts = counts("language.id").toMap()
            val languageNames = LANGUAGE_OPTIONS.associate { (label, id) -> id to stringResource(label) }
            ChipGroup(LANGUAGE_OPTIONS.map { (_, id) -> id to (languageCounts[id] ?: 0) }, draft.languages, { languageNames[it].orEmpty() }) { id ->
                draft = draft.copy(languages = if (id in draft.languages) draft.languages - id else draft.languages + id)
            }

            val themes = (counts("theme.id").filter { it.first in state.names.themes } + draft.themes.map { it to 0 }).distinctBy { it.first }
            if (themes.isNotEmpty()) {
                SheetGroup(stringResource(R.string.library_theme))
                ChipGroup(themes, draft.themes, { state.names.themes[it] ?: it.toString() }) { id ->
                    draft = draft.copy(themes = if (id in draft.themes) draft.themes - id else draft.themes + id)
                }
            }
        }
        // Fixed footer.
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.country_filters_stay, name), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.filter_clear_all)) }
                Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.filter_show_results)) }
            }
        }
    }
}

/** Title of a group of the sheet: condensed capitals in ink 2, under a 1 dp rule. */
@Composable
internal fun SheetGroup(title: String) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            title.uppercase(),
            style = SectionTitleStyle.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Values with their counts ("UNHCR 526"); beyond 10, the 10 most frequent, then "Show all (23)". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(values: List<Pair<Long, Int>>, selected: Set<Long>, label: (Long) -> String, onToggle: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded || values.size <= 10) values else values.take(10)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (id, count) ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = {
                    Text(label(id))
                    if (count > 0) {
                        Text(
                            " $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = if (id in selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                shape = CircleShape,
            )
        }
        if (!expanded && values.size > 10) {
            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.filter_show_all_count, values.size)) }
        }
    }
}
