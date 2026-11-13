// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Ids
import io.relais.api.Requests
import io.relais.api.Scope
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.CountryEntity
import io.relais.app.data.LoadError
import io.relais.app.data.OverviewData
import io.relais.app.data.Pin
import io.relais.app.data.PinKind
import io.relais.app.data.Pinned
import io.relais.app.data.ProfileAppeal
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The overview of a country, behind "Overview" on its page (theme-compose.md 12.2): pinned slots
 * first, then situation, needs and response, analysis, reports posted, its disasters and who's
 * publishing (produit/tour-a2.md 6.2). [previousVisit] is the country page's, for the dots.
 */
class OverviewViewModel(private val c: AppContainer, countryId: Long, val previousVisit: Long) : ViewModel() {
    val scope: Scope = Scope.country(countryId)

    val overview: StateFlow<OverviewData> = c.repository.observeOverview(scope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewData())

    /** Pinned slots, on the 50 latest documents of the overview (passe-v0.2.md, 3.1). */
    val pins: StateFlow<List<Pin>> = combine(overview, c.settings.flow.map { it.hiddenPins }) { ov, hidden ->
        Pinned.pick(ov.latest, System.currentTimeMillis(), hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val country: StateFlow<CountryEntity?> = c.dao.observeCountry(countryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<LoadError?>(null)

    init {
        load(force = false)
    }

    fun hidePin(reportId: Long) = viewModelScope.launch { c.settings.hidePin(reportId) }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) = viewModelScope.launch {
        if (!c.repository.hasAccess) {
            error.value = LoadError.NO_ACCESS
            return@launch
        }
        loading.value = true
        error.value = null
        try {
            c.repository.loadOverview(scope, force)
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }
}

@Composable
fun OverviewScreen(countryId: Long, name: String, since: Long, nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { OverviewViewModel(container, countryId, since) }
    val ov by vm.overview.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val country by vm.country.collectAsStateWithLifecycle()
    val title = name.ifBlank { country?.displayName.orEmpty() }

    ScreenScaffold(title = stringResource(R.string.overview_screen_title, title), onBack = { nav.back() }, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LoadErrorBanner(error, onRetry = { vm.refresh() }, onOpenSettings = nav::settings, cachedAt = ov.fetchedAt, persistent = ov.fetchedAt == null, snackbar = snackbar)
            val pull = rememberPull(ov.fetchedAt, snackbar) { vm.refresh() }
            Refreshable(loading = loading, onRefresh = pull, modifier = Modifier.fillMaxSize()) {
                OverviewPage(countryId, title, country, vm, nav)
            }
        }
    }
}

@Composable
private fun pinLabel(kind: PinKind): String = when (kind) {
    PinKind.OCHA -> stringResource(R.string.pinned_ocha)
    PinKind.FLASH_UPDATE -> stringResource(R.string.pinned_flash)
    PinKind.APPEAL -> stringResource(R.string.pinned_appeal)
}

/** The pinned slots (passe-v0.2.md, 3): a label, the document, and "Hide". */
private fun LazyListScope.pinnedItems(pins: List<Pin>, vm: OverviewViewModel, nav: Nav) {
    if (pins.isEmpty()) return
    // theme-compose.md 10.7: one block on surfaceVariant, radius 12 dp, a hairline between pins.
    item(key = "pinned") {
        val cs = MaterialTheme.colorScheme
        Surface(
            color = cs.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s).testTag("pinned"),
        ) {
            Column {
                pins.forEachIndexed { i, pin ->
                    if (i > 0) HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
                    PinnedRow(pin, vm, nav)
                }
            }
        }
    }
}

@Composable
private fun PinnedRow(pin: Pin, vm: OverviewViewModel, nav: Nav) {
    val cs = MaterialTheme.colorScheme
    val label = pinLabel(pin.kind)
    val pinnedFor = stringResource(R.string.pinned_a11y, label)
    val hideFor = stringResource(R.string.pinned_hide_a11y, pin.report.title)
    Column(Modifier.testTag("pinned_${pin.report.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = pinnedFor }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PushPin, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(15.dp))
                Text(
                    label.uppercase(),
                    style = SectionTitleStyle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = cs.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            TextButton(onClick = { vm.hidePin(pin.report.id) }, modifier = Modifier.semantics { contentDescription = hideFor }) {
                Text(stringResource(R.string.pinned_hide))
            }
        }
        DocumentRow(pin.report, onClick = { nav.report(pin.report.id) }, isNew = pin.report.dateCreated > vm.previousVisit, inCountry = true)
    }
}

@Composable
private fun SmallEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m),
    )
}

/** A current appeal or plan of the editors' profile: opens the report when its id is known. */
@Composable
private fun AppealRow(appeal: ProfileAppeal, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val parts = Regex("^([^:]{2,40}):\\s*(.+)$").find(appeal.title)?.groupValues
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(horizontal = Space.screen, vertical = Space.rowVertical), verticalArrangement = Arrangement.spacedBy(Space.kickerGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(stringResource(R.string.overview_appeal_kicker).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
                if (parts != null) Text(parts[1].uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Text(parts?.get(2) ?: appeal.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}

/** The blocks of the overview (10.5), for a country. */
@Composable
private fun OverviewPage(countryId: Long, name: String, country: CountryEntity?, vm: OverviewViewModel, nav: Nav) {
    val ov by vm.overview.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().testTag("page_overview")) {
        // Nothing fetched yet: blank while loading; the error banner above says why otherwise.
        if (ov.fetchedAt == null) return@LazyColumn
        val latest = ov.latest
        pinnedItems(pins, vm, nav)

        // The report covering the latest period; an old period is said in plain words (12.2).
        val sitrep = latestSituation(latest + ov.sitreps)
        item(key = "sit") { SectionHeader(stringResource(R.string.overview_situation), stringResource(R.string.overview_situation_note)) }
        item(key = "sit_doc") {
            if (sitrep != null) {
                DocumentRow(sitrep, onClick = { nav.report(sitrep.id) }, isNew = sitrep.dateCreated > vm.previousVisit, inCountry = true)
            } else {
                SmallEmpty(stringResource(R.string.overview_no_sitrep, name))
            }
        }

        // Needs and response: the editors' list of current appeals and plans, else the latest appeals.
        val appeals = ov.info?.appeals.orEmpty()
        val appealDocs = latest.filter { it.formatId == Ids.FORMAT_APPEAL }.ifEmpty { ov.appeals }.take(3)
        item(key = "needs") {
            SectionHeader(
                stringResource(R.string.overview_needs),
                stringResource(if (appeals.isNotEmpty()) R.string.overview_needs_profile else R.string.overview_needs_latest),
            )
        }
        when {
            appeals.isNotEmpty() -> items(appeals, key = { "a" + (it.reportId ?: it.title.hashCode().toLong()) }) { appeal ->
                AppealRow(appeal) {
                    val id = appeal.reportId
                    if (id != null) nav.report(id) else appeal.url?.let { safeOpen(uriHandler, it) }
                }
            }
            appealDocs.isNotEmpty() -> items(appealDocs, key = { "ad" + it.id }) { r ->
                DocumentRow(r, onClick = { nav.report(r.id) }, isNew = r.dateCreated > vm.previousVisit, inCountry = true)
            }
            else -> item(key = "needs_empty") { SmallEmpty(stringResource(R.string.overview_no_appeal, name)) }
        }

        val analyses = latest.filter { it.formatId == Ids.FORMAT_ANALYSIS || it.formatId == Ids.FORMAT_ASSESSMENT }.take(3)
        item(key = "analysis") {
            SectionHeader(
                stringResource(R.string.overview_analysis),
                actionLabel = stringResource(R.string.today_see_all),
                onAction = { nav.country(countryId, name, PageFilter.ANALYSIS) },
            )
        }
        if (analyses.isEmpty()) item(key = "analysis_empty") { SmallEmpty(stringResource(R.string.overview_no_analysis, name)) }
        items(analyses, key = { "an" + it.id }) { r ->
            DocumentRow(r, onClick = { nav.report(r.id) }, isNew = r.dateCreated > vm.previousVisit, inCountry = true)
        }

        // Reports posted: two rolling windows of 7 days (tour-a2.md 6.3), from the format facets.
        val last = ov.facets[Requests.OVERVIEW_LAST_7D]?.values?.sum() ?: 0
        val previous = ov.facets[Requests.OVERVIEW_PREV_7D]?.values?.sum() ?: 0
        item(key = "pace") {
            SectionHeader(stringResource(R.string.overview_pace), stringResource(R.string.overview_pace_note, name))
            Text(
                if (last + previous == 0) stringResource(R.string.overview_pace_none) else pluralStringResource(R.plurals.overview_pace_line, last, last, previous),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m).testTag("overview_pace"),
            )
        }

        // The country's disasters, alert or ongoing on ReliefWeb.
        val related = ov.related
        if (related != null) {
            item(key = "related") { SectionHeader(stringResource(R.string.overview_disasters), stringResource(R.string.overview_related_note)) }
            if (related.isEmpty()) item(key = "related_empty") { SmallEmpty(stringResource(R.string.overview_no_related, name)) }
            items(related, key = { "r" + it.id }) { d -> DisasterRow(d, onClick = { nav.crisis(d.id, d.name) }) }
        }

        // Who's publishing, at the bottom (tour-a2.md 6.3): who publishes most, not who matters most.
        val orgs = ov.facets[Requests.OVERVIEW_ORGS].orEmpty().entries.sortedByDescending { it.value }.take(5)
        if (orgs.isNotEmpty()) {
            item(key = "orgs") { SectionHeader(stringResource(R.string.overview_orgs), stringResource(R.string.overview_orgs_note)) }
            items(orgs, key = { "o" + it.key }) { (org, n) -> CountRow(org, pluralStringResource(R.plurals.overview_org_reports, n, n)) }
        }

        country?.iso3?.let { iso3 ->
            item(key = "open") {
                TextButton(onClick = { safeOpen(uriHandler, "https://reliefweb.int/country/$iso3") }, modifier = Modifier.padding(horizontal = 8.dp, vertical = Space.m)) {
                    Text(stringResource(R.string.report_open_reliefweb))
                }
            }
        }
    }
}

/** A light list (theme-compose.md 10.6): counts, not reading; 48 dp rows. */
@Composable
private fun CountRow(label: String, count: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = cs.onSurface, modifier = Modifier.weight(1f))
            Text(count, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}
