// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.CountryTab
import io.relais.api.Display
import io.relais.api.MostRead
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.FollowedRow
import io.relais.app.data.LoadError
import io.relais.app.data.MostCovered
import io.relais.app.data.ReadingWithReport
import io.relais.app.data.ReportEntity
import io.relais.app.data.Repository
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.relais.api.Requests
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Rubric tabs of Today (theme-compose.md 5.4). */
sealed interface TodayTab {
    data object All : TodayTab
    data class Format(val tab: CountryTab) : TodayTab
    data class Country(val id: Long, val name: String) : TodayTab
    /** A followed crisis (theme-compose.md 10.2), labelled with its short name. */
    data class Crisis(val id: Long, val name: String) : TodayTab
}

private val FORMAT_TABS = listOf(CountryTab.SITREPS, CountryTab.ANALYSIS, CountryTab.MAPS, CountryTab.APPEALS)

/** Stable key of a tab, for the pager and the test tags. */
private val TodayTab.key: String
    get() = when (this) {
        TodayTab.All -> "all"
        is TodayTab.Format -> tab.name.lowercase()
        is TodayTab.Country -> "country_$id"
        is TodayTab.Crisis -> "crisis_$id"
    }

data class TodayState(
    val headlines: List<ReportEntity> = emptyList(),
    val readIds: Set<Long> = emptySet(),
    val savedIds: Set<Long> = emptySet(),
    val continueReading: List<ReadingWithReport> = emptyList(),
    /** Start of what counts as new on Today (P14): the end of the previous visit. */
    val since: Long = 0,
    /** Reports and headlines of what is followed, new since [since], the newest first (P14 block 2). */
    val followNew: List<ReportEntity> = emptyList(),
    /** Reports of every country new since [since], without what block 2 has (P14 block 4). */
    val latestReports: List<io.relais.app.data.TwinGroup> = emptyList(),
    val countries: List<FollowedRow> = emptyList(),
    val hasAccess: Boolean = true,
    val showRssBanner: Boolean = false,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val lastRefreshAt: Long = 0,
    /** "Most read on ReliefWeb": the site's two most read reports since yesterday. */
    val mostRead: List<ReportEntity> = emptyList(),
    /** "Most reports this week": publication counts, not needs. */
    val mostCovered: MostCovered? = null,
    /** "New disasters this week" (tour-a2.md 6.3); null until fetched once. */
    val newDisasters: List<io.relais.app.data.DisasterInfo>? = null,
    /** Followed crises, one rubric each. */
    val crises: List<io.relais.app.data.FollowedDisasterRow> = emptyList(),
)

/** Away from Today for longer than this, and the next opening is a new visit (P14). */
private const val VISIT_GAP = 30 * 60 * 1000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)

    /** Followed live: an appname saved in Settings opens Today at once (first launch review, 13/11). */
    private val access = c.repository.access.stateIn(viewModelScope, SharingStarted.Eagerly, c.repository.hasAccess)

    /**
     * The end of Today's windows (headlines of the week, continue reading, format rubrics). It
     * moves on each refresh and each return to Today: fixed at creation, it kept last week's
     * headlines while the app stayed in memory (Produit, 13/11).
     */
    private val now = MutableStateFlow(System.currentTimeMillis())

    /** "What's new" to show on Today after an update, once (13.4). */
    val whatsNew = MutableStateFlow<WhatsNew.Release?>(null)

    /**
     * Once per versionCode, noted as seen when shown. Never after a first install (the version is
     * noted then), never for a version without lines.
     */
    fun checkWhatsNew(context: android.content.Context) = viewModelScope.launch {
        val code = io.relais.app.BuildConfig.VERSION_CODE
        if (c.settings.current().whatsNewSeen >= code) return@launch
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val firstInstall = info == null || info.firstInstallTime == info.lastUpdateTime
        c.settings.setWhatsNewSeen(code)
        if (!firstInstall) whatsNew.value = WhatsNew.of(code)
    }

    /** Today comes on screen: a new visit after 30 minutes away (P14). */
    fun onVisit() = viewModelScope.launch { c.settings.startTodayVisit(System.currentTimeMillis(), VISIT_GAP) }

    /** Today leaves the screen: the end of this visit, unless it comes back within 30 minutes. */
    fun onLeave() = viewModelScope.launch { c.settings.setTodaySeenAt(System.currentTimeMillis()) }

    /** Moves the windows to now, at most every 10 minutes. */
    fun advanceWindow() {
        val t = System.currentTimeMillis()
        if (t - now.value > 10 * 60 * 1000L) now.value = t
    }

    private val content = combine(now, access) { at, a -> at to a }.flatMapLatest { (at, hasAccess) ->
        combine(
            c.dao.observeHeadlines(fromRss = !hasAccess, since = at - Repository.SEVEN_DAYS),
            c.dao.observeReadIds(),
            c.dao.observeSaved(),
            c.dao.observeContinueReading(since = at - 14 * Repository.ONE_DAY),
            c.repository.observeFollowedRows(),
        ) { headlines, readIds, saved, continueReading, countries ->
            TodayState(
                headlines = headlines,
                readIds = readIds.toSet(),
                savedIds = saved.map { it.reading.reportId }.toSet(),
                continueReading = continueReading.filter { it.report != null },
                countries = countries,
                hasAccess = hasAccess,
            )
        }
    }

    private val rubrics = mutableMapOf<String, StateFlow<List<ReportEntity>?>>()
    private val listStates = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()

    /**
     * Documents of a rubric page; each page of the pager reads its own. Kept as a StateFlow:
     * back from a report, the page has its documents at once and keeps its place (13/11).
     */
    fun reports(t: TodayTab): StateFlow<List<ReportEntity>?> = rubrics.getOrPut(t.key) {
        when (t) {
            TodayTab.All -> flowOf(emptyList())
            is TodayTab.Format -> now.flatMapLatest { at -> c.dao.observeRecentByFormats(t.tab.formatIds, at - Repository.SEVEN_DAYS) }
            is TodayTab.Country -> c.dao.observeCountryReports(t.id)
            is TodayTab.Crisis -> c.dao.observeDisasterReports(t.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** The scroll of each page of Today, kept here for the same reason. */
    fun listState(key: String): androidx.compose.foundation.lazy.LazyListState = listStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }

    private val mostRead = c.popular.mostRead.mapLatest { cache ->
        runCatching { c.repository.reportsByUrls(cache?.urls(MostRead.FRONT).orEmpty()) }.getOrDefault(emptyList())
    }

    private val popular = combine(mostRead, c.popular.mostCovered) { read, covered ->
        read to covered?.takeIf { !it.isEmpty && System.currentTimeMillis() - it.fetchedAt < Repository.ONE_DAY }
    }

    /** What is new since the last visit (P14): block 2 from what is followed, block 4 from everywhere. */
    private val since = c.settings.flow.map { it.todaySince }.distinctUntilChanged()

    private val fresh = since.flatMapLatest { from ->
        combine(
            c.dao.observeFollowingFeed(false, Requests.REPORT_FORMATS, true, 200),
            c.dao.observeLatestReports(Requests.REPORT_FORMATS, from, 60),
        ) { follow, latest ->
            val followNew = follow.filter { it.dateCreated > from }
            val taken = followNew.map { it.id }.toSet()
            val groups = if (c.remoteConfig.current.features.latestReports) {
                twinGroups(latest.filter { it.id !in taken && !it.isHeadline }).take(5)
            } else {
                emptyList()
            }
            Triple(from, followNew, groups)
        }
    }

    val state: StateFlow<TodayState> = combine(
        combine(content, fresh) { s, f -> s to f },
        c.settings.flow, combine(loading, error) { l, e -> l to e }, popular,
        combine(c.repository.observeNewDisasters(), c.repository.observeFollowedDisasterRows()) { d, f -> d to f },
    ) { (s, newSince), settings, (isLoading, err), (read, covered), (newDisasters, crises) ->
        val (from, followNew, latest) = newSince
        s.copy(
            since = from,
            followNew = followNew,
            latestReports = latest,
            newDisasters = newDisasters,
            crises = crises,
            showRssBanner = !s.hasAccess && !settings.rssBannerSeen,
            loading = isLoading,
            error = err,
            lastRefreshAt = settings.lastHeadlinesRefreshAt,
            mostRead = read,
            mostCovered = covered,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(hasAccess = access.value, loading = true))

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        advanceWindow()
        // Each block checks whether it is due (CSV once a day on Wi-Fi, counts every 6 hours).
        viewModelScope.launch { c.popular.refresh() }
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                if (access.value) {
                    val last = c.settings.current().lastSyncAt
                    if (force || System.currentTimeMillis() - last > c.remoteConfig.current.openMillis) c.repository.sync()
                } else {
                    c.repository.refreshHeadlines(force)
                }
            } catch (e: Exception) {
                error.value = e.toLoadError()
            } finally {
                loading.value = false
            }
        }
    }

    fun setRead(id: Long, read: Boolean) = viewModelScope.launch { c.repository.setRead(id, read) }
    fun setSaved(id: Long, saved: Boolean) = viewModelScope.launch { c.repository.setSaved(id, saved) }
    fun hideFromContinue(id: Long) = viewModelScope.launch { c.repository.hideFromContinue(id) }
    fun rssBannerShown() = viewModelScope.launch { c.settings.setRssBannerSeen() }
}

private val FORMAT_LABELS = mapOf(
    CountryTab.SITREPS to R.string.tab_sitreps,
    CountryTab.ANALYSIS to R.string.tab_analysis,
    CountryTab.MAPS to R.string.tab_maps,
    CountryTab.APPEALS to R.string.tab_appeals,
)

/** The logotype, then the UNOFFICIAL badge 10 dp after it (theme-compose.md 11.2). */
@Composable
fun TodayTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logotype()
        Text(
            stringResource(R.string.label_unofficial).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
fun TodayRefresh(onRefresh: () -> Unit) {
    IconButton(onClick = onRefresh) {
        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
    }
}

/** M5 Today, in the a2 grammar: rubric tabs, large headline, dense list, an end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { TodayViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // "What's new" after an update (13.4): only on Today itself, never over a report opened from
    // a notification, which pauses Today within this delay.
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) vm.checkWhatsNew(context)
    }
    val whatsNew by vm.whatsNew.collectAsStateWithLifecycle()
    whatsNew?.let { release -> WhatsNewSheet(release, onDismiss = { vm.whatsNew.value = null }) }

    // Back on Today (from a report, another tab, or the app in the background): the week moves
    // on, and after 30 minutes away a new visit starts (P14).
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.advanceWindow()
        vm.onVisit()
        onPauseOrDispose { vm.onLeave() }
    }

    val tabs: List<TodayTab> = buildList {
        add(TodayTab.All)
        if (state.hasAccess) {
            FORMAT_TABS.forEach { add(TodayTab.Format(it)) }
            state.countries.forEach { add(TodayTab.Country(it.countryId, it.name)) }
            state.crises.forEach { add(TodayTab.Crisis(it.disaster.disasterId, it.disaster.name)) }
        }
    }
    // The pager holds the current rubric: a swipe or a tap on a tab moves it.
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, tabs.lastIndex)
    fun go(index: Int) {
        if (index >= 0) scope.launch { pager.animateScrollToPage(index) }
    }
    // From a rubric (a format or a country), Back returns to All before leaving Today.
    BackHandler(enabled = current != 0) { go(0) }

    val snackbar = remember { SnackbarHostState() }
    val pull = rememberPull(state.lastRefreshAt.takeIf { state.hasAccess }, snackbar) { vm.refresh(force = true) }

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        onSettings = nav::settings,
        snackbarHostState = snackbar,
        titleContent = { TodayTitle() },
        actions = { TodayRefresh { vm.refresh(force = true) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(
                selectedTabIndex = current,
                edgePadding = 8.dp,
                modifier = Modifier.testTag("today_tabs"),
            ) {
                tabs.forEachIndexed { index, t ->
                    val active = index == current
                    Tab(selected = active, onClick = { go(index) }, text = {
                        val label = when (t) {
                            TodayTab.All -> stringResource(R.string.tab_all)
                            is TodayTab.Format -> stringResource(FORMAT_LABELS.getValue(t.tab))
                            is TodayTab.Country -> t.name
                            is TodayTab.Crisis -> crisisShort(t.name)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val count = when (t) {
                                is TodayTab.Country -> state.countries.firstOrNull { it.countryId == t.id }?.newCount
                                is TodayTab.Crisis -> state.crises.firstOrNull { it.disaster.disasterId == t.id }?.newCount
                                else -> null
                            } ?: 0
                            if (count > 0) {
                                Box(
                                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    })
                }
                if (state.hasAccess) {
                    Tab(selected = false, onClick = nav::pickCountries, icon = {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.today_add_country))
                    })
                }
            }
            LoadErrorBanner(
                error = state.error,
                onRetry = { vm.refresh(force = true) },
                onOpenSettings = nav::settings,
                cachedAt = state.lastRefreshAt.takeIf { it > 0 },
                persistent = state.headlines.isEmpty(),
                snackbar = snackbar,
            )
            // Without an appname (the public APK's normal case, PLAN.md 0octies): say on each visit
            // what the app shows and what needs an appname. Closable; never over the content.
            if (!state.hasAccess) {
                var hidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                if (!hidden) {
                    StatusLine(
                        stringResource(R.string.today_headlines_only),
                        stringResource(R.string.action_settings),
                        nav::settings,
                        onClose = { hidden = true },
                    )
                }
            }
            Refreshable(loading = state.loading, onRefresh = pull, modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pager,
                    key = { tabs.getOrNull(it)?.key ?: it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (val t = tabs.getOrNull(page)) {
                        null -> Unit
                        TodayTab.All -> LazyColumn(Modifier.fillMaxSize().testTag("page_all"), state = vm.listState(TodayTab.All.key)) {
                            allTab(
                                state, vm, nav,
                                onCountry = { id -> go(tabs.indexOfFirst { it is TodayTab.Country && it.id == id }) },
                                onSeeHistory = {
                                    container.libraryRequest.value = LibraryView.HISTORY.ordinal
                                    nav.tab(LibraryRoute)
                                },
                            )
                        }
                        else -> RubricPage(t, state, vm, nav)
                    }
                }
            }
        }
    }
}

/** "Updated 14:32", or "Updated 12 Nov, 14:32" when it was not today. */
@Composable
private fun updatedLine(at: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val time = if (day == LocalDate.now(zone)) Display.time(at) else Display.day(at) + ", " + Display.time(at)
    return stringResource(R.string.today_updated, time)
}

/** "No new headlines since your last visit. The latest is from Thu 12 Nov." */
@Composable
internal fun NoNewHeadlines(latest: ReportEntity?) {
    Text(
        if (latest != null) stringResource(R.string.today_no_new_headlines, Display.weekdayDay(latest.dateCreated)) else stringResource(R.string.today_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m).testTag("today_no_new_headlines"),
    )
}

/** The headlines of Today's first block: the new ones, the large one, and the earlier ones. */
internal data class HeadlineBlocks(
    val new: List<ReportEntity>,
    val hero: ReportEntity?,
    val earlier: List<ReportEntity>,
)

/**
 * Splits the week's headlines, newest first, for Today. New: posted since the last visit. The
 * large one, with its photo: the first new one not read yet; failing that, the most recent of
 * the week, read or not (P14 changed for v0.3.2, at the founder's request: a large headline on
 * every visit, as in v0.2.3). Earlier: the others of the week, without the large one.
 */
internal fun headlineBlocks(headlines: List<ReportEntity>, since: Long, readIds: Set<Long>): HeadlineBlocks {
    val new = headlines.filter { it.dateCreated > since }
    val hero = new.firstOrNull { it.id !in readIds } ?: headlines.firstOrNull()
    val earlier = headlines.filter { it.dateCreated <= since && it.id != hero?.id }
    return HeadlineBlocks(new, hero, earlier)
}

/** "8 earlier headlines this week", which unfolds into their rows (P14 block 5). */
@Composable
private fun EarlierHeadlines(earlier: List<ReportEntity>, state: TodayState, vm: TodayViewModel, nav: Nav) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
                .padding(horizontal = Space.screen).testTag("today_earlier"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.today_earlier_headlines, earlier.size, earlier.size),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
            )
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
        if (open) {
            earlier.forEach { report ->
                DocumentRow(report, onClick = { nav.report(report.id) }, isRead = report.id in state.readIds, title = report.displayTitle, tag = headlineTag(report, state)) { close ->
                    HeadlineMenu(report, state, vm, close)
                }
            }
        }
    }
}

/** headline_unread_<id> or headline_read_<id>: the journeys find a headline left to read. */
private fun headlineTag(report: ReportEntity, state: TodayState): String =
    (if (report.id in state.readIds) "headline_read_" else "headline_unread_") + report.id

/** A format or country page of Today, with its own documents. */
@Composable
private fun RubricPage(t: TodayTab, state: TodayState, vm: TodayViewModel, nav: Nav) {
    val reports by vm.reports(t).collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("page_" + t.key), state = vm.listState(t.key)) {
        val list = reports ?: return@LazyColumn
        when (t) {
            TodayTab.All -> Unit
            is TodayTab.Format -> {
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.today_format_empty)) }
                items(list, key = { "f" + it.id }) { report ->
                    DocumentRow(report, onClick = { nav.report(report.id) }, isRead = report.id in state.readIds)
                }
            }
            is TodayTab.Country -> countryTab(t, list, state, nav)
            is TodayTab.Crisis -> crisisTab(t, list, state, nav)
        }
    }
}

private fun LazyListScope.allTab(
    state: TodayState,
    vm: TodayViewModel,
    nav: Nav,
    onCountry: (Long) -> Unit,
    onSeeHistory: () -> Unit,
) {
    val total = state.headlines.size
    val read = state.headlines.count { it.id in state.readIds }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Display.longDate(LocalDate.now()), style = MaterialTheme.typography.titleSmall)
                // When Today last reached ReliefWeb, so that a pull shows it happened (13/11).
                if (state.lastRefreshAt > 0) {
                    Text(
                        updatedLine(state.lastRefreshAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("today_updated"),
                    )
                }
            }
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { read.toFloat() / total },
                    modifier = Modifier.width(64.dp).height(4.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    stringResource(R.string.today_progress, read, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.s),
                )
            }
        }
    }

    // 1. New headlines (P14): those posted since the last visit; without any, one line says so and
    // gives the date of the latest. A large headline with its photo on every visit (v0.3.2).
    val (newHeadlines, hero, earlier) = headlineBlocks(state.headlines, state.since, state.readIds)
    item(key = "new_headlines") { SectionHeader(stringResource(R.string.today_new_headlines)) }
    if (newHeadlines.isEmpty()) {
        item(key = "no_new_headlines") { NoNewHeadlines(latest = state.headlines.firstOrNull()) }
    }
    if (hero != null) {
        item(key = "hero") {
            HeadlineHero(hero, isRead = hero.id in state.readIds, onClick = { nav.report(hero.id) }, tag = headlineTag(hero, state)) { close ->
                HeadlineMenu(hero, state, vm, close)
            }
        }
    }
    items(newHeadlines.filter { it.id != hero?.id }, key = { "h" + it.id }) { report ->
        DocumentRow(report, onClick = { nav.report(report.id) }, isNew = true, isRead = report.id in state.readIds, title = report.displayTitle, tag = headlineTag(report, state)) { close ->
            HeadlineMenu(report, state, vm, close)
        }
    }

    // 2. Latest from what you follow: reports and headlines of followed countries, crises and
    // organizations, new since the last visit; the 3 newest, then the rest in Following.
    if (state.followNew.isNotEmpty()) {
        item(key = "follow_new") { SectionHeader(stringResource(R.string.today_latest_follow)) }
        items(state.followNew.take(3), key = { "y" + it.id }) { report ->
            DocumentRow(report, onClick = { nav.report(report.id) }, isNew = true, isRead = report.id in state.readIds)
        }
        val more = state.followNew.size - 3
        if (more > 0) {
            item(key = "follow_more") {
                TextButton(onClick = { nav.tab(FollowingRoute) }, modifier = Modifier.padding(horizontal = Space.s).testTag("today_more_following")) {
                    Text(pluralStringResource(R.plurals.today_more_in_following, more, more))
                }
            }
        }
    }

    // 3. Continue reading: two compact lines at most.
    if (state.continueReading.isNotEmpty()) {
        item {
            SectionHeader(
                stringResource(R.string.today_continue),
                actionLabel = stringResource(R.string.today_see_all),
                onAction = onSeeHistory,
            )
        }
        items(state.continueReading.take(2), key = { "c" + it.reading.reportId }) { row ->
            val report = row.report ?: return@items
            ContinueLine(report, row.reading.percent, onClick = { nav.report(report.id) }, onHide = { vm.hideFromContinue(report.id) })
        }
    }

    // 4. Latest reports on ReliefWeb: the 5 newest reports of every country since the last
    // visit, without those of block 2; versions in other languages on one line.
    if (state.latestReports.isNotEmpty()) {
        item(key = "latest_reports") {
            SectionHeader(
                stringResource(R.string.today_latest_reports),
                stringResource(R.string.today_latest_reports_note),
                actionLabel = stringResource(R.string.today_see_all),
                onAction = { nav.results("", reportsOnly = true) },
                actionTag = "latest_reports_all",
            )
        }
        items(state.latestReports, key = { "l" + it.main.id }) { group ->
            val report = group.main
            DocumentRow(
                report,
                onClick = { nav.report(report.id) },
                isNew = true,
                isRead = report.id in state.readIds,
                extra = if (group.others.isEmpty()) null else { { AlsoIn(group.others, onOpen = { nav.report(it) }) } },
            )
        }
    }

    // 5. Earlier this week: the other headlines of the 7 days, folded in one line.
    if (earlier.isNotEmpty()) {
        item(key = "earlier") { EarlierHeadlines(earlier, state, vm, nav) }
    }

    // Neither block counts in "N of M read": Today stays finishable (tour-a2.md 5.4).
    if (state.mostRead.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.most_read_title), actionLabel = stringResource(R.string.today_see_all), onAction = nav::mostRead, actionTag = "most_read_all") }
        items(state.mostRead, key = { "m" + it.id }) { report ->
            DocumentRow(report, onClick = { nav.report(report.id) }, isRead = report.id in state.readIds)
        }
    }
    state.mostCovered?.let { covered -> item(key = "covered") { MostCoveredBlock(covered, nav) } }

    state.newDisasters?.let { crises ->
        item(key = "new_disasters") { SectionHeader(stringResource(R.string.today_new_disasters)) }
        if (crises.isEmpty()) {
            item(key = "new_disasters_empty") {
                Text(
                    stringResource(R.string.today_new_disasters_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m),
                )
            }
        }
        items(crises, key = { "nd" + it.id }) { d -> DisasterRow(d, onClick = { nav.crisis(d.id, d.name) }) }
    }

    if (state.hasAccess) {
        item { SectionHeader(stringResource(R.string.today_countries)) }
        if (state.countries.isEmpty()) {
            item { EmptyState(stringResource(R.string.today_invite), stringResource(R.string.today_choose_countries), nav::pickCountries) }
        } else {
            val withNews = state.countries.filter { it.newCount > 0 }
            val upToDate = state.countries.filter { it.newCount == 0 }
            items(withNews, key = { "k" + it.countryId }) { row ->
                Column {
                    TextButton(onClick = { onCountry(row.countryId) }, modifier = Modifier.padding(horizontal = Space.s)) {
                        Text(
                            newCountLine(row.name, row),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
                }
            }
            if (upToDate.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.today_up_to_date_countries, upToDate.joinToString(", ") { it.name }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
                    )
                }
            }
        }
    }

    if (total > 0) {
        item {
            EndOfReading(left = total - read, total = total, onAllHeadlines = if (state.hasAccess) ({ nav.results("", headlinesOnly = true) }) else null)
        }
    }
}

private fun LazyListScope.countryTab(t: TodayTab.Country, reports: List<ReportEntity>, state: TodayState, nav: Nav) {
    val row = state.countries.firstOrNull { it.countryId == t.id }
    val lastVisit = row?.lastVisitAt ?: Long.MAX_VALUE
    item {
        Column(Modifier.padding(horizontal = Space.screen, vertical = Space.m)) {
            Text(t.name, style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { nav.country(t.id, t.name) }) { Text(stringResource(R.string.today_country_page)) }
        }
    }
    // Every document, language twins on one line, as on the country page (decision of 8/11).
    scopeLists(reports, lastVisit, state, nav, inCountry = true)
}

/** "New since your last visit", then "Seen before", twins merged ("Also in French"). */
private fun LazyListScope.scopeLists(reports: List<ReportEntity>, lastVisit: Long, state: TodayState, nav: Nav, inCountry: Boolean) {
    val fresh = twinGroups(reports.filter { it.dateCreated > lastVisit })
    val seen = twinGroups(reports.filter { it.dateCreated <= lastVisit })
    if (fresh.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.today_new_since)) }
        items(fresh, key = { "n" + it.main.id }) { g -> TwinRow(g, state, nav, isNew = true, inCountry = inCountry) }
    }
    if (seen.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.country_seen_before)) }
        items(seen, key = { "s" + it.main.id }) { g -> TwinRow(g, state, nav, isNew = false, inCountry = inCountry) }
    }
}

@Composable
private fun TwinRow(group: io.relais.app.data.TwinGroup, state: TodayState, nav: Nav, isNew: Boolean, inCountry: Boolean) {
    val r = group.main
    DocumentRow(
        r,
        onClick = { nav.report(r.id) },
        isNew = isNew,
        isRead = r.id in state.readIds,
        inCountry = inCountry,
        extra = if (group.others.isEmpty()) null else { { AlsoIn(group.others, onOpen = { nav.report(it) }) } },
    )
}

/** A followed crisis on Today: its documents new since the last visit, then the others. */
private fun LazyListScope.crisisTab(t: TodayTab.Crisis, reports: List<ReportEntity>, state: TodayState, nav: Nav) {
    val row = state.crises.firstOrNull { it.disaster.disasterId == t.id }
    val lastVisit = row?.disaster?.lastVisitAt ?: Long.MAX_VALUE
    item {
        Column(Modifier.padding(horizontal = Space.screen, vertical = Space.m)) {
            Text(t.name, style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { nav.crisis(t.id, t.name) }) { Text(stringResource(R.string.today_crisis_page)) }
        }
    }
    if (reports.isEmpty()) item { EmptyState(stringResource(R.string.overview_no_reports_tagged)) }
    scopeLists(reports, lastVisit, state, nav, inCountry = false)
}

/** One line: the title, then "43% read". A long press offers to remove it from the list. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContinueLine(report: ReportEntity, percent: Int, onClick: () -> Unit, onHide: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().testTag("doc_${report.id}")
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = Space.screen, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                report.displayTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.today_continue_percent, percent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.s),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.today_remove_continue)) }, onClick = { menu = false; onHide() })
        }
    }
}

/** Counts in grey text, no bar and no big numbers: a volume of reports is not a measure of need. */
@Composable
private fun MostCoveredBlock(covered: MostCovered, nav: Nav) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.testTag("most_covered")) {
        SectionHeader(stringResource(R.string.most_covered_title), stringResource(R.string.most_covered_subtitle))
        Column(Modifier.padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            covered.countries.take(5).forEach { line ->
                Text(
                    pluralStringResource(R.plurals.most_covered_line, line.count, line.name, line.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { nav.country(line.id, line.name) }.padding(vertical = Space.xs),
                )
            }
            covered.disasters.take(3).forEach { line ->
                Text(
                    pluralStringResource(R.plurals.most_covered_line, line.count, line.name, line.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Space.xs),
                )
            }
            Text(
                stringResource(R.string.most_covered_limits),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s, bottom = Space.m),
            )
        }
    }
}

@Composable
private fun HeadlineMenu(report: ReportEntity, state: TodayState, vm: TodayViewModel, close: () -> Unit) {
    val isRead = report.id in state.readIds
    val isSaved = report.id in state.savedIds
    if (!report.fromRss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (isSaved) R.string.action_saved else R.string.action_save)) },
            onClick = { vm.setSaved(report.id, !isSaved); close() },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read)) },
        onClick = { vm.setRead(report.id, !isRead); close() },
    )
}

/** "today", "yesterday" in running text; "4 Nov" stays as is. */
fun relativeDay(epochMillis: Long): String {
    val day = Display.day(epochMillis)
    return if (day == "Today" || day == "Yesterday") day.lowercase() else day
}

/** End of Today (theme-compose.md 5.8): an ink rule, then headlines left or "You're up to date". */
@Composable
fun EndOfReading(left: Int, total: Int, onAllHeadlines: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val done = left == 0
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xl)) {
        HorizontalDivider(thickness = SectionRule, color = cs.onSurface)
        Column(
            Modifier.fillMaxWidth().padding(top = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (done) {
                Box(Modifier.size(44.dp).background(cs.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.onPrimary)
                }
            }
            Text(
                when {
                    done -> stringResource(R.string.today_up_to_date)
                    left == 1 -> stringResource(R.string.today_left_one)
                    else -> stringResource(R.string.today_left_many, left)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (done) stringResource(R.string.today_end, total) else stringResource(R.string.today_left_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onAllHeadlines != null) TextButton(onClick = onAllHeadlines) { Text(stringResource(R.string.today_all_headlines)) }
        }
    }
}
