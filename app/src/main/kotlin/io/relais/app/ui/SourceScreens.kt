// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.api.HtmlBlocks
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.FollowedSourceEntity
import io.relais.app.data.LoadError
import io.relais.app.data.SourceInfo
import io.relais.app.data.toLoadError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Organizations (v0.3): produit/suivre-organisation.md (P13), theme-compose.md 13.1 and 13.2.

/** "About 3 documents a week on ReliefWeb", or the forms for 1 and fewer than 1. */
@Composable
private fun rhythmLine(perWeek: Double): String {
    val n = perWeek.roundToInt()
    return when {
        perWeek < 0.5 -> stringResource(R.string.source_rhythm_fewer)
        else -> pluralStringResource(R.plurals.source_rhythm, n, n)
    }
}

/** The domain of a homepage, without https:// and www. */
internal fun domainOf(url: String?): String? =
    url?.substringAfter("://")?.substringBefore('/')?.removePrefix("www.")?.takeIf { it.isNotBlank() }

/** "12 Nov", or "today" and "yesterday" in running text. */
private fun shortDay(at: Long): String = Display.day(at).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }

/**
 * The head of an organization's page, three lines since v0.4 (revue-densite-v0.3.md, change 6,
 * in the order of the product review): its full name; its rhythm and latest document; its type,
 * where it is based and its website. Its main countries and the editors' description fold under
 * "About ACAPS", as "About this disaster" does. The short name is in the top bar, with Follow.
 * No logo: a logo would make the page look endorsed by the organization.
 */
@Composable
internal fun SourceHeader(info: SourceInfo?, name: String) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var open by rememberSaveable { mutableStateOf(false) }
    val paragraphs = remember(info?.descriptionHtml) {
        info?.descriptionHtml?.let { HtmlBlocks.plainText(it) }.orEmpty()
            .split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val full = info?.name?.takeIf { it.isNotBlank() } ?: info?.shortname ?: name
        Text(full, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        if (info != null) {
            val rhythm = listOfNotNull(
                if (info.perWeek < 0.5) stringResource(R.string.source_head_rhythm_fewer)
                else info.perWeek.roundToInt().let { pluralStringResource(R.plurals.source_head_rhythm, it, it) },
                info.latestAt?.let { stringResource(R.string.source_head_latest, shortDay(it)) },
            ).joinToString(" · ")
            Text(rhythm, style = small, color = cs.onSurfaceVariant, modifier = Modifier.testTag("source_rhythm"))
            // "International NGO · Based in Switzerland · acaps.org", the domain a link.
            val domain = domainOf(info.homepage)
            val facts = listOfNotNull(info.typeName, info.countryName?.let { stringResource(R.string.source_based_in, it) })
            if (facts.isNotEmpty() || domain != null) {
                val link = TextLinkStyles(SpanStyle(color = cs.primary))
                Text(
                    buildAnnotatedString {
                        append(facts.joinToString(" · "))
                        if (domain != null) {
                            if (facts.isNotEmpty()) append(" · ")
                            withLink(LinkAnnotation.Clickable("website", link) { safeOpen(uriHandler, info.homepage) }) { append(domain) }
                        }
                    },
                    style = small,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag("source_facts"),
                )
            }
        }
    }
    val mostly = info?.let { mostlyOn(it.mainCountries) }
    if (mostly != null || paragraphs.isNotEmpty()) {
        val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
                .semantics { stateDescription = state }
                .padding(horizontal = Space.screen).testTag("source_about"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.source_about, info?.shortname?.takeIf { it.isNotBlank() } ?: name),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.m), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mostly?.let { Text(it, style = small, color = cs.onSurfaceVariant) }
                if (paragraphs.isNotEmpty()) {
                    Text(stringResource(R.string.crisis_description_by), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp)
                    paragraphs.forEach { Text(it, style = body, color = cs.onSurface) }
                }
            }
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}

/** "Mostly on Yemen, Colombia and Ukraine"; nothing without countries. */
@Composable
private fun mostlyOn(countries: List<String>): String? = when (countries.size) {
    0 -> null
    1 -> stringResource(R.string.source_mostly_on_1, countries[0])
    2 -> stringResource(R.string.source_mostly_on_2, countries[0], countries[1])
    else -> stringResource(R.string.source_mostly_on_3, countries[0], countries[1], countries[2])
}

/**
 * Before following an organization of more than 10 documents a week (13.2): its real numbers,
 * "Reports only" chosen by default, or "Everything".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowSourceSheet(info: SourceInfo, onFollow: (reportsOnly: Boolean) -> Unit, onDismiss: () -> Unit) {
    var reportsOnly by rememberSaveable { mutableStateOf(true) }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding().testTag("follow_source_sheet")) {
            Text(
                stringResource(R.string.source_sheet_title, info.shortname, info.perWeek.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = Space.m),
            )
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(true, stringResource(R.string.source_reports_only), stringResource(R.string.source_reports_only_note, info.reportsPerWeek.roundToInt())),
                    Triple(false, stringResource(R.string.source_everything), stringResource(R.string.source_everything_note, info.perWeek.roundToInt())),
                ).forEach { (value, label, note) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = reportsOnly == value, role = Role.RadioButton, onClick = { reportsOnly = value })
                            .testTag(if (value) "source_choice_reports" else "source_choice_everything"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reportsOnly == value, onClick = null)
                        Column(Modifier.padding(start = Space.m)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp))
                            Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = { onFollow(reportsOnly) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = Space.m).testTag("source_sheet_follow")) {
                Text(stringResource(R.string.country_follow))
            }
        }
    }
}

// --- Follow an organization: search and suggestions (13.2) --------------------------------

data class PickSourcesState(
    val query: String = "",
    val results: List<SourceInfo>? = null,
    val suggestions: List<Pair<SourceInfo, Int>> = emptyList(),
    val followed: Map<Long, FollowedSourceEntity> = emptyMap(),
    val loading: Boolean = false,
    val error: LoadError? = null,
)

class PickSourcesViewModel(private val c: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<SourceInfo>?>(null)
    private val suggestions = MutableStateFlow<List<Pair<SourceInfo, Int>>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)

    /** The organization whose sheet is open: over 10 documents a week (13.2). */
    val asking = MutableStateFlow<SourceInfo?>(null)

    val state: StateFlow<PickSourcesState> = combine(
        combine(query, results) { q, r -> q to r },
        suggestions,
        c.dao.observeFollowedSources().map { list -> list.associateBy { it.sourceId } },
        combine(loading, error) { l, e -> l to e },
    ) { (q, r), s, f, (l, e) -> PickSourcesState(q, r, s, f, l, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickSourcesState())

    private var search: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val countries = c.dao.followed().map { it.countryId }
            val exclude = c.dao.followedSources().map { it.sourceId }.toSet()
            suggestions.value = runCatching { c.repository.sourceSuggestions(countries, exclude) }.getOrDefault(emptyList())
        }
    }

    /** From 3 letters, after 300 ms without typing (13.2). */
    fun setQuery(text: String) {
        query.value = text
        search?.cancel()
        if (text.trim().length < 3) {
            results.value = null
            return
        }
        search = viewModelScope.launch {
            delay(300)
            loading.value = true
            error.value = null
            try {
                results.value = c.repository.searchSources(text)
            } catch (e: Exception) {
                error.value = e.toLoadError()
            } finally {
                loading.value = false
            }
        }
    }

    /** Checked: a small organization is followed at once; a large one asks first. */
    fun toggle(source: SourceInfo, follow: Boolean) = viewModelScope.launch {
        if (!follow) {
            c.repository.unfollowSource(source.id)
            return@launch
        }
        val info = runCatching { c.repository.loadSource(source.id) }.getOrNull() ?: source
        if (info.large) asking.value = info else c.repository.followSource(info, reportsOnly = false)
    }

    fun follow(info: SourceInfo, reportsOnly: Boolean) = viewModelScope.launch {
        asking.value = null
        c.repository.followSource(info, reportsOnly)
    }
}

/** "Follow an organization": search by name, and suggestions while the field is empty (13.2). */
@Composable
fun PickSourcesScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { PickSourcesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val asking by vm.asking.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (text != state.query) vm.setQuery(text) }

    ScreenScaffold(
        title = stringResource(R.string.source_follow_title),
        onBack = { nav.back() },
        actions = { TextButton(onClick = { nav.back() }, modifier = Modifier.testTag("pick_sources_done")) { Text(stringResource(R.string.pick_done)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.setQuery(it) },
                label = { Text(stringResource(R.string.source_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(Space.screen).testTag("source_search"),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadErrorBanner(state.error, onRetry = { vm.setQuery(text) }, onOpenSettings = nav::settings)
            LazyColumn(Modifier.weight(1f).testTag("pick_sources")) {
                val results = state.results
                if (results == null) {
                    if (state.suggestions.isNotEmpty()) {
                        item(key = "suggestions") { SectionHeader(stringResource(R.string.source_suggestions)) }
                        items(state.suggestions, key = { "s" + it.first.id }) { (source, count) ->
                            SourceCheckRow(source, source.id in state.followed, pluralStringResource(R.plurals.source_suggestion_line, count, count)) { vm.toggle(source, it) }
                        }
                    }
                } else {
                    if (results.isEmpty() && !state.loading) item(key = "none") { EmptyState(stringResource(R.string.source_no_match, text.trim())) }
                    items(results, key = { "r" + it.id }) { source ->
                        SourceCheckRow(source, source.id in state.followed, null) { vm.toggle(source, it) }
                    }
                }
            }
        }
    }
    asking?.let { info -> FollowSourceSheet(info, onFollow = { vm.follow(info, it) }, onDismiss = { vm.asking.value = null }) }
}

@Composable
private fun SourceCheckRow(source: SourceInfo, checked: Boolean, note: String?, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(horizontal = Space.screen, vertical = Space.s).testTag("pick_source_${source.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(source.shortname, style = MaterialTheme.typography.titleMedium)
            if (source.name.isNotBlank() && source.name != source.shortname) {
                Text(source.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            }
            source.typeName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle(it) })
    }
    HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
}

// --- Manage: the organizations followed (13.2) --------------------------------------------

/** One followed organization in Manage: its short name, "N new", and its choice for a large one. */
@Composable
internal fun FollowedSourceRow(source: FollowedSourceEntity, newCount: Int, onOpen: () -> Unit, onReportsOnly: (Boolean) -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = Space.screen, top = 8.dp, bottom = 8.dp).testTag("followed_source_${source.sourceId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(source.shortname, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (source.reportsOnly) R.string.source_line_reports else R.string.source_line_everything, source.shortname),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (newCount > 0) Text(stringResource(R.string.following_new, newCount), style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.source_reports_only)) }, onClick = { menu = false; onReportsOnly(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.source_everything)) }, onClick = { menu = false; onReportsOnly(false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.following_stop)) }, onClick = { menu = false; onStop() })
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = Space.screen), thickness = Hairline, color = cs.outlineVariant)
}

/** "Follow an organization", at the end of the section. */
@Composable
internal fun FollowSourceLine(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = Space.screen).testTag("follow_source"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.source_follow_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = Space.m))
    }
}
