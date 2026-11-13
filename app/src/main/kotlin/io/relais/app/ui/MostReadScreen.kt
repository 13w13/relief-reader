// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.MostRead
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.CountryEntity
import io.relais.app.data.DisasterInfo
import io.relais.app.data.FollowedCountryEntity
import io.relais.app.data.FollowedDisasterEntity
import io.relais.app.data.MostReadCache
import io.relais.app.data.ReportEntity
import io.relais.app.data.toInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One group of the Most read screen: the front page, a country or a crisis, up to 5 documents. */
data class MostReadGroup(
    val key: String,
    val title: String,
    val countryId: Long? = null,
    val disasterId: Long? = null,
    val following: Boolean = false,
    val reports: List<ReportEntity> = emptyList(),
)

data class MostReadState(
    val loaded: Boolean = false,
    /** When ReliefWeb last regenerated the file (Last-Modified); null without a usable file. */
    val updatedAt: Long? = null,
    val groups: List<MostReadGroup> = emptyList(),
    /** Documents not on the phone are still being fetched: the groups show what is known. */
    val resolving: Boolean = false,
    /** Groups of the file not shown yet: "Show more" loads the next ones (T16). */
    val more: Int = 0,
)

private data class MostReadInputs(
    val cache: MostReadCache?,
    val countries: List<CountryEntity>,
    val followed: List<FollowedCountryEntity>,
    val followedCrises: List<FollowedDisasterEntity>,
    val open: List<DisasterInfo>?,
)

/**
 * Every line of ReliefWeb's most-read file (theme-compose.md 12.4), grouped: across ReliefWeb,
 * followed countries, other countries, then crises on alert or ongoing. The file does not rank
 * the groups against each other, and neither does the screen.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MostReadViewModel(private val c: AppContainer) : ViewModel() {
    /**
     * How many groups to show and resolve: 10 at first (one request at most), then 10 more at
     * each "Show more", instead of the whole file at once (budget-appels.md, T16).
     */
    private val shown = kotlinx.coroutines.flow.MutableStateFlow(GROUP_PAGE)

    fun showMore() {
        shown.value += GROUP_PAGE
    }

    /** The scroll of the screen, kept here so that Back from a report finds the same place. */
    val listState = androidx.compose.foundation.lazy.LazyListState()

    val state: StateFlow<MostReadState> = combine(
        c.popular.mostRead,
        c.dao.observeCountries(),
        c.dao.observeFollowed(),
        c.dao.observeFollowedDisasters(),
        c.repository.observeOpenDisasters(),
    ) { cache, countries, followed, crises, open -> MostReadInputs(cache, countries, followed, crises, open) }
        .distinctUntilChanged()
        .combine(shown) { input, n -> input to n }
        .transformLatest { (input, n) -> build(input, n) { emit(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MostReadState())

    init {
        viewModelScope.launch {
            // The file once a day on Wi-Fi (Popular); the names of open crises, kept 6 hours.
            runCatching { c.popular.refresh() }
            runCatching { c.repository.loadOpenDisasters() }
        }
    }

    /** The groups with what the phone has first, then with the documents fetched by batches. */
    private suspend fun build(input: MostReadInputs, limit: Int, emit: suspend (MostReadState) -> Unit) {
        val now = System.currentTimeMillis()
        val cache = input.cache?.takeIf { now - it.updatedAt <= MostReadCache.STALE_AFTER }
        val rows = cache?.rows.orEmpty()
        if (rows.isEmpty()) {
            emit(MostReadState(loaded = input.cache != null || input.open != null))
            return
        }

        val countries = input.countries.associateBy { it.id }
        val followedIds = input.followed.map { it.countryId }
        val followedCrises = input.followedCrises.map { it.disasterId }.toSet()
        val crises = (input.open.orEmpty() + input.followedCrises.map { it.toInfo() }).associateBy { it.id }
        fun urls(key: String) = rows[key].orEmpty().take(MAX_PER_GROUP)

        val allGroups = buildList {
            if (rows.containsKey(MostRead.FRONT)) add(MostReadGroup(MostRead.FRONT, "", reports = emptyList()))
            followedIds.filter { rows.containsKey(it.toString()) }.forEach { id ->
                val name = countries[id]?.displayName ?: input.followed.first { it.countryId == id }.name
                add(MostReadGroup(id.toString(), name, countryId = id, following = true))
            }
            rows.keys.mapNotNull { it.toLongOrNull() }
                .filter { it in countries && it !in followedIds }
                .map { countries.getValue(it) }
                .sortedBy { it.displayName }
                .forEach { add(MostReadGroup(it.id.toString(), it.displayName, countryId = it.id)) }
            rows.keys.mapNotNull { it.toLongOrNull() }
                .filter { it !in countries }
                .mapNotNull { crises[it] }
                .filter { it.status in OPEN_STATUSES }
                .sortedBy { it.name }
                .forEach { add(MostReadGroup(it.id.toString(), it.name, disasterId = it.id, following = it.id in followedCrises)) }
        }
        // The phone first, shown at once; then the API by batches of 50 (Repository).
        val more = (allGroups.size - limit).coerceAtLeast(0)
        val groups = allGroups.take(limit)
        val all = groups.flatMap { urls(it.key) }
        fun fill(found: Map<String, ReportEntity>) =
            groups.map { g -> g.copy(reports = urls(g.key).mapNotNull { found[it] }) }.filter { it.reports.isNotEmpty() }
        val onPhone = runCatching { c.repository.reportsByUrlMap(all, fetch = false) }.getOrDefault(emptyMap())
        val complete = onPhone.size >= all.distinct().size
        emit(MostReadState(loaded = true, updatedAt = cache?.updatedAt, groups = fill(onPhone), resolving = !complete, more = more))
        if (complete) return
        val found = runCatching { c.repository.reportsByUrlMap(all) }.getOrDefault(onPhone)
        emit(MostReadState(loaded = true, updatedAt = cache?.updatedAt, groups = fill(found), more = more))
    }

    private companion object {
        const val MAX_PER_GROUP = 5
        const val GROUP_PAGE = 10
        val OPEN_STATUSES = setOf("alert", "ongoing", "current")
    }
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)

@Composable
fun MostReadScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { MostReadViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val front = stringResource(R.string.most_read_front)
    val shown = state.groups.filter { g -> query.isBlank() || (g.title.ifBlank { front }).contains(query.trim(), ignoreCase = true) }

    ScreenScaffold(
        title = stringResource(R.string.most_read_title),
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(
                    if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(if (searching) R.string.action_close else R.string.most_read_search),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.most_read_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s).testTag("most_read_search"),
                )
            }
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Bottom room as padding, not as an item: an item there while loading would anchor the
            // list, and the groups would arrive above the screen.
            LazyColumn(Modifier.fillMaxSize().testTag("page_most_read"), state = vm.listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                val updated = state.updatedAt
                if (state.loaded && (updated == null || (state.groups.isEmpty() && !state.resolving))) {
                    item(key = "none") { EmptyState(stringResource(R.string.most_read_none)) }
                    return@LazyColumn
                }
                if (updated != null) {
                    item(key = "header") {
                        val at = Instant.ofEpochMilli(updated).atZone(ZoneId.systemDefault())
                        val text = if (at.toLocalDate() == LocalDate.now()) {
                            stringResource(R.string.most_read_header, HOUR.format(at))
                        } else {
                            stringResource(R.string.most_read_header_day, DAY.format(at))
                        }
                        Column(Modifier.padding(horizontal = Space.screen, vertical = Space.s)) {
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("most_read_header"))
                            // Fetched by batches: say that the groups are still filling.
                            if (state.resolving) {
                                Text(
                                    stringResource(R.string.most_read_resolving),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Space.xs),
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.s))
                            }
                        }
                    }
                }
                if (shown.isEmpty() && query.isNotBlank()) item(key = "no_match") { EmptyState(stringResource(R.string.most_read_no_match)) }
                shown.forEach { group ->
                    item(key = "g" + group.key) {
                        val title = group.title.ifBlank { front }
                        val note = if (group.following) stringResource(R.string.country_following) else null
                        // Tapping the title opens that page on Most read (12.4).
                        val open: (() -> Unit)? = when {
                            group.countryId != null -> { { nav.country(group.countryId, group.title, PageFilter.MOST_READ) } }
                            group.disasterId != null -> { { nav.crisis(group.disasterId, group.title, PageFilter.MOST_READ) } }
                            else -> null
                        }
                        SectionHeader(
                            if (open != null) "$title  ›" else title,
                            note,
                            modifier = (if (open != null) Modifier.clickable(onClick = open) else Modifier).testTag("most_read_group_${group.key}"),
                        )
                    }
                    items(group.reports, key = { "m${group.key}-${it.id}" }) { report ->
                        DocumentRow(report, onClick = { nav.report(report.id) }, inCountry = group.countryId != null)
                    }
                }
                if (state.more > 0 && query.isBlank()) {
                    item(key = "more") {
                        TextButton(onClick = vm::showMore, modifier = Modifier.padding(horizontal = 8.dp, vertical = Space.s).testTag("most_read_more")) {
                            Text(stringResource(R.string.most_read_more))
                        }
                    }
                }
            }
        }
    }
}
