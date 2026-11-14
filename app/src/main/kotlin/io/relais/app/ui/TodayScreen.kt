// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.api.MostRead
import io.relais.api.Requests
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.LoadError
import io.relais.app.data.MostCovered
import io.relais.app.data.ReadingWithReport
import io.relais.app.data.ReportEntity
import io.relais.app.data.Repository
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayState(
    /** This week's headlines, newest first: the pile. */
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
    val hasAccess: Boolean = true,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val lastRefreshAt: Long = 0,
    /** "Most read on ReliefWeb": the site's two most read reports since yesterday. */
    val mostRead: List<ReportEntity> = emptyList(),
    /** "Most reports this week": publication counts, not needs. Kept at the founder's request (14/11). */
    val mostCovered: MostCovered? = null,
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
     * The end of Today's windows (headlines of the week, continue reading). It moves on each
     * refresh and each return to Today: fixed at creation, it kept last week's headlines while
     * the app stayed in memory (Produit, 13/11).
     */
    private val now = MutableStateFlow(System.currentTimeMillis())

    /** "What's new" to show on Today after an update, once (13.4). */
    val whatsNew = MutableStateFlow<WhatsNew.Release?>(null)

    /** The scroll of Today, kept here: back from a report, the page keeps its place (13/11). */
    val listState = LazyListState()

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
        ) { headlines, readIds, saved, continueReading ->
            TodayState(
                headlines = headlines,
                readIds = readIds.toSet(),
                savedIds = saved.map { it.reading.reportId }.toSet(),
                continueReading = continueReading.filter { it.report != null },
                hasAccess = hasAccess,
            )
        }
    }

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
    ) { (s, newSince), settings, (isLoading, err), (read, covered) ->
        val (from, followNew, latest) = newSince
        s.copy(
            since = from,
            followNew = followNew,
            latestReports = latest,
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
}

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

/**
 * M5 Today, one page since v0.4 (revue-densite-v0.3.md and revue-ux-v0.3.md, 14/11): the pile of
 * headlines, then what is new from what is followed, continue reading, the latest reports, most
 * read and most reports this week. No rubric tabs: Following and the pages do that.
 */
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

    val pager = rememberPagerState { state.headlines.size + 1 }
    val pileReady = pileInPlace(state, pager)

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
                var hidden by rememberSaveable { mutableStateOf(false) }
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
                LazyColumn(Modifier.fillMaxSize().testTag("today_list"), state = vm.listState) {
                    todayPage(
                        state, vm, nav, pager, pileReady,
                        prefetchNext = { !container.pdfs.isMetered() },
                        onSeeHistory = {
                            container.libraryRequest.value = LibraryView.HISTORY.ordinal
                            nav.tab(LibraryRoute)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Where the pile stands. At each visit (P14), it opens on the first headline not read. Back from
 * a report opened from the pile, it stands on the last headline read there (the report notes it
 * in AppContainer.headlineFocus), even after Next or Previous in the report.
 *
 * Returns whether the pile is in place. Until then Today does not compose it: card 0 would
 * otherwise compose, and load its photo on mobile data, before the jump to the right card
 * (review of PR 12). requestScrollToPage takes effect at the pile's first measure.
 */
@Composable
private fun pileInPlace(state: TodayState, pager: PagerState): Boolean {
    val container = LocalContext.current.container
    val hasHeadlines = state.headlines.isNotEmpty()
    var openedFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val focus by container.headlineFocus.collectAsStateWithLifecycle()
    LaunchedEffect(state.since, hasHeadlines) {
        if (!hasHeadlines || openedFor == state.since) return@LaunchedEffect
        if (container.headlineFocus.value == null) pager.requestScrollToPage(pileOpening(state.headlines, state.readIds))
        openedFor = state.since
    }
    LaunchedEffect(focus, state.headlines) {
        val id = focus ?: return@LaunchedEffect
        if (!hasHeadlines) return@LaunchedEffect
        val index = state.headlines.indexOfFirst { it.id == id }
        if (index >= 0) pager.requestScrollToPage(index)
        container.headlineFocus.value = null
    }
    return hasHeadlines && openedFor == state.since && focus == null
}

/** "Updated 14:32", or "Updated 12 Nov, 14:32" when it was not today. */
@Composable
private fun updatedLine(at: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val time = if (day == LocalDate.now(zone)) Display.time(at) else Display.day(at) + ", " + Display.time(at)
    return stringResource(R.string.today_updated, time)
}

/** "Saturday 14 November · Updated 07:45", on one line. */
@Composable
internal fun TodayDateLine(lastRefreshAt: Long) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(Display.longDate(LocalDate.now()), style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp), maxLines = 1)
        // When Today last reached ReliefWeb, so that a pull shows it happened (13/11).
        if (lastRefreshAt > 0) {
            Text(
                " · " + updatedLine(lastRefreshAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("today_updated"),
            )
        }
    }
}

/** "2 new since your last visit", or "Nothing new since your last visit" (accorded 14/11). */
@Composable
internal fun headlinesNote(headlines: List<ReportEntity>, since: Long): String {
    val fresh = headlines.count { it.dateCreated > since }
    return if (fresh > 0) pluralStringResource(R.plurals.today_new_since_visit, fresh, fresh) else stringResource(R.string.today_nothing_new)
}

private fun LazyListScope.todayPage(
    state: TodayState,
    vm: TodayViewModel,
    nav: Nav,
    pager: PagerState,
    pileReady: Boolean,
    prefetchNext: () -> Boolean,
    onSeeHistory: () -> Unit,
) {
    item(key = "date") { TodayDateLine(state.lastRefreshAt) }

    // 1. Headlines: the pile, newest first, opened on the first one not read.
    val onOlder = { nav.olderHeadlines(state.hasAccess) }
    if (state.headlines.isEmpty()) {
        item(key = "headlines") {
            Column {
                SectionHeader(stringResource(R.string.today_headlines))
                if (!state.loading) {
                    Text(
                        stringResource(R.string.today_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
                    )
                    OlderHeadlinesLink(onOlder, Modifier.padding(horizontal = Space.screen))
                }
            }
        }
    } else {
        item(key = "headlines") {
            SectionHeader(stringResource(R.string.today_headlines), headlinesNote(state.headlines, state.since))
        }
        item(key = "pile") {
            if (pileReady) HeadlinePile(
                headlines = state.headlines,
                readIds = state.readIds,
                since = state.since,
                pager = pager,
                onOpen = { nav.headline(it.id) },
                onOlder = onOlder,
                prefetchNext = prefetchNext,
                menu = { report, close -> HeadlineMenu(report, state, vm, close) },
            )
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
        item(key = "continue") {
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

    // 5. Most read on ReliefWeb, then most reports this week: it shows where the attention goes
    // (the founder, 14/11).
    if (state.mostRead.isNotEmpty()) {
        item(key = "most_read") { SectionHeader(stringResource(R.string.most_read_title), actionLabel = stringResource(R.string.today_see_all), onAction = nav::mostRead, actionTag = "most_read_all") }
        items(state.mostRead, key = { "m" + it.id }) { report ->
            DocumentRow(report, onClick = { nav.report(report.id) }, isRead = report.id in state.readIds)
        }
    }
    state.mostCovered?.let { covered -> item(key = "covered") { MostCoveredBlock(covered, nav) } }

    // The end of the page: an ink rule (theme-compose.md 5.8).
    item(key = "end") {
        HorizontalDivider(
            thickness = SectionRule,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xl).testTag("today_end"),
        )
    }
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
