// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import kotlinx.coroutines.flow.map
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.api.HtmlBlocks
import io.relais.api.Ids
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.DisasterInfo
import io.relais.app.data.FollowedDisasterRow
import io.relais.app.data.LanguageTwins
import io.relais.app.data.LoadError
import io.relais.app.data.TwinGroup
import io.relais.app.data.ReportEntity
import io.relais.app.data.ScopeInfo
import io.relais.app.data.toInfo
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import io.relais.app.data.Ref
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Crises (v0.2, then T13): the head of a crisis page, crises in Following, "Follow a crisis".

private val CRISIS_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val CRISIS_MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

private fun crisisDate(ms: Long, format: DateTimeFormatter = CRISIS_DATE): String =
    format.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun statusLabel(status: String?): String? = when (status) {
    "alert" -> stringResource(R.string.crisis_status_alert)
    "ongoing", "current" -> stringResource(R.string.crisis_status_ongoing)
    "past" -> stringResource(R.string.crisis_status_past)
    else -> null
}

/** "Ongoing · Epidemic · since May 2026". */
@Composable
internal fun crisisLine(crisis: DisasterInfo): String = listOfNotNull(
    statusLabel(crisis.status),
    crisis.typeName,
    crisis.eventAt?.let { stringResource(R.string.crisis_since, crisisDate(it, CRISIS_MONTH)) },
).joinToString(" · ")

/** "Ebola Outbreak" for "Central/Eastern Africa: Ebola Outbreak - May 2026": no place, no date (10.2). */
/** "Regional crises": the topics, featured first; an error of their own when they cannot load. */
private fun androidx.compose.foundation.lazy.LazyListScope.regionalCrises(
    topics: List<io.relais.api.TopicInfo>,
    loaded: Boolean,
    failed: Boolean,
    followed: Set<Long>,
    onToggle: (io.relais.api.TopicInfo, Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    if (!failed && loaded && topics.isEmpty()) return
    item(key = "regional") { SectionHeader(stringResource(R.string.topics_section), if (loaded && !failed) topics.size.toString() else null) }
    item(key = "regional_note") {
        Text(
            stringResource(R.string.topic_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (failed) {
        item(key = "regional_failed") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("topics_failed"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.topics_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
            }
        }
        return
    }
    items(topics, key = { "t" + it.id }) { topic ->
        val checked = topic.id in followed
        Row(
            Modifier.fillMaxWidth().clickable { onToggle(topic, !checked) }.padding(horizontal = 8.dp).testTag("pick_topic_${topic.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle(topic, it) })
            Text(topic.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        }
    }
}

internal fun crisisShort(name: String): String =
    name.replace(Regex("^[^:]+:\\s*"), "").replace(Regex("\\s*[-\u2013]\\s*[A-Za-z]{3,9}\\.? \\d{4}$"), "").ifBlank { name }

/** "Uganda, DR Congo, Rwanda +2". */
private fun countriesLine(countries: List<io.relais.app.data.Ref>): String {
    val names = countries.map { it.name }.filter { it.isNotBlank() }
    return names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
}

/**
 * The situation report of an overview: the one covering the latest period, not the latest
 * posted (revue-lecteur-v0.2.md, defect 1: a July update posted in November is not the news).
 * Without a period in the title, the publication date stands for it.
 */
internal fun latestSituation(reports: List<ReportEntity>): ReportEntity? {
    val zone = ZoneId.systemDefault()
    return reports.filter { it.formatId == Ids.FORMAT_SITREP }.distinctBy { it.id }
        .maxWithOrNull(
            compareBy<ReportEntity>(
                { Display.coverageEnd(it.title, it.formatId, it.formatName) ?: Instant.ofEpochMilli(it.dateOriginal ?: it.dateCreated).atZone(zone).toLocalDate() },
                { it.dateCreated },
            ),
        )
}

/** Language twins of a list; the texts on the phone feed the numbers signal (S1). */
internal fun twinGroups(reports: List<ReportEntity>): List<TwinGroup> = LanguageTwins.group(reports)

/** "Also in French", under a document whose twins in other languages are merged into it. */
@Composable
internal fun AlsoIn(others: List<ReportEntity>, onOpen: (Long) -> Unit) {
    // theme-compose.md 10.8: translate icon, "Also in", then each language as a link.
    val cs = MaterialTheme.colorScheme
    val twins = others.map { it to (it.languages.firstOrNull()?.let(LanguageTwins::languageName).orEmpty()) }.sortedBy { it.second }
    Row(Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Translate, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.also_in_prefix), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
        twins.forEachIndexed { i, (twin, language) ->
            Text(
                language + if (i < twins.lastIndex) "," else "",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                color = cs.onPrimaryContainer,
                modifier = Modifier.minimumInteractiveComponentSize().clickable { onOpen(twin.id) }.padding(horizontal = 2.dp).testTag("also_in_${twin.id}"),
            )
        }
    }
}

/** A crisis in a list: status and type in the kicker, the name, then its countries. */
@Composable
internal fun DisasterRow(crisis: DisasterInfo, onClick: () -> Unit, following: Boolean = false, newLine: String? = null, typeLabel: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("crisis_${crisis.id}")) {
        Column(Modifier.padding(horizontal = Space.screen, vertical = Space.rowVertical), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                // In Following, "DISASTER" first (theme-compose.md 10.2).
                if (typeLabel) {
                    Text(stringResource(R.string.following_kind_disaster).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
                }
                statusLabel(crisis.status)?.let {
                    Text(it.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
                }
                val rest = listOfNotNull(crisis.typeName, crisis.eventAt?.let { stringResource(R.string.crisis_since, crisisDate(it, CRISIS_MONTH)) })
                if (rest.isNotEmpty()) {
                    Text(rest.joinToString(" · ").uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(crisis.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
            val meta = listOfNotNull(countriesLine(crisis.countries).ifBlank { null }, crisis.glide).joinToString(" · ")
            if (meta.isNotBlank() || following) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (following) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(15.dp))
                        Text(stringResource(R.string.country_following), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    }
                }
            }
            if (newLine != null) Text(newLine, style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}

/**
 * The head of a crisis page (theme-compose.md 12.5): the kicker, the full name, "Since …", every
 * affected country as a link, then "About this disaster", folded, with the editors' description.
 */
@Composable
internal fun CrisisHeader(info: ScopeInfo?, crisis: DisasterInfo?, name: String, onCountry: (Ref) -> Unit) {
    val d = info?.disaster ?: crisis
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val paragraphs = remember(info?.descriptionHtml) {
        info?.descriptionHtml?.let { HtmlBlocks.plainText(it) }.orEmpty()
            .split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (d != null) {
            val kicker = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                statusLabel(d.status)?.let { Text(it.uppercase(), style = kicker.copy(fontWeight = FontWeight.Bold), color = cs.onSurface) }
                d.typeName?.let { Text(it.uppercase(), style = kicker, color = cs.onSurfaceVariant) }
            }
        }
        Text(d?.name ?: name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        d?.eventAt?.let { Text(stringResource(R.string.crisis_since_long, crisisDate(it)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        // Every affected country, as one sentence of links; never "+1" (12.5).
        val countries = d?.countries.orEmpty().filter { it.name.isNotBlank() }
        if (countries.isNotEmpty()) {
            val linkStyle = TextLinkStyles(SpanStyle(color = cs.onPrimaryContainer, textDecoration = TextDecoration.Underline))
            Text(
                buildAnnotatedString {
                    countries.forEachIndexed { i, country ->
                        if (i > 0) append(", ")
                        withLink(LinkAnnotation.Clickable("country_${country.id}", linkStyle) { onCountry(country) }) { append(country.name) }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.testTag("crisis_countries"),
            )
        }
    }
    // Always there since v0.4: the note on tagging moved in, out of the way to the list
    // (revue-densite-v0.3.md, change 11).
    run {
        val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
                .semantics { stateDescription = state }
                .padding(horizontal = Space.screen).testTag("crisis_about"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.crisis_about), style = MaterialTheme.typography.labelLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.m), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The caveats first: a description can run to ten screens (chef, 8/11).
                Text(stringResource(R.string.crisis_tagging_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.testTag("crisis_tagging_note"))
                if (paragraphs.isNotEmpty()) Text(stringResource(R.string.crisis_about_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                d?.glide?.let { Text("GLIDE $it", style = SectionTitleStyle.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal), color = cs.onSurfaceVariant) }
                paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp), color = cs.onSurface) }
            }
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}

// --- Following: crises ---------------------------------------------------------------------

/** "Ongoing · Epidemic · 6 countries", then the new documents since the last visit. */
@Composable
internal fun FollowedCrisisRow(row: FollowedDisasterRow, onClick: () -> Unit) {
    val info = row.disaster.toInfo()
    val n = row.newCount
    val news = if (n > 0) pluralStringResource(R.plurals.crisis_new, n, n) else null
    val last = row.lastReportAt?.let { stringResource(R.string.following_last_report, Display.day(it)) }
    DisasterRow(info, onClick = onClick, newLine = listOfNotNull(news, last).joinToString(" · ").ifBlank { null }, typeLabel = true)
}

// --- Follow a crisis -----------------------------------------------------------------------

data class PickCrisesState(
    val crises: List<DisasterInfo>? = null,
    val followed: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val error: LoadError? = null,
)

class PickCrisesViewModel(private val c: AppContainer) : ViewModel() {
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)

    val state: StateFlow<PickCrisesState> = combine(
        c.repository.observeOpenDisasters(),
        c.dao.observeFollowedDisasters(),
        loading,
        error,
    ) { crises, followed, l, e -> PickCrisesState(crises, followed.map { it.disasterId }.toSet(), l, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickCrisesState(loading = true))

    init {
        load()
        loadTopics()
    }

    fun load() = viewModelScope.launch {
        loading.value = true
        error.value = null
        try {
            c.repository.loadOpenDisasters()
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }

    fun toggle(crisis: DisasterInfo, follow: Boolean) = viewModelScope.launch {
        if (follow) c.repository.followDisaster(crisis) else c.repository.unfollowDisaster(crisis.id)
    }

    // Regional crises (topics, lot 3): loaded on their own, so that a failure stays in their
    // section and the rest of the picker works.
    val topics = MutableStateFlow<List<io.relais.api.TopicInfo>?>(null)
    val topicsFailed = MutableStateFlow(false)
    val followedTopics: StateFlow<Set<Long>> = c.dao.observeFollowedTopics().map { list -> list.map { it.topicId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun loadTopics(force: Boolean = false) = viewModelScope.launch {
        topicsFailed.value = false
        runCatching { c.repository.topics(force) }
            .onSuccess { topics.value = it }
            .onFailure { topicsFailed.value = true }
    }

    fun toggleTopic(topic: io.relais.api.TopicInfo, follow: Boolean) = viewModelScope.launch {
        runCatching { if (follow) c.repository.followTopic(topic.id, topic.title) else c.repository.unfollowTopic(topic.id) }
            .onFailure { topicsFailed.value = true }
    }
}

/**
 * "Follow a crisis": ReliefWeb's disasters that are not over, newest first, with a search field.
 * Long conflicts are not disasters on ReliefWeb: the help line says to follow them as countries.
 */
@Composable
fun PickCrisesScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { PickCrisesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val all = state.crises.orEmpty()
    val matches = all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.countries.any { c -> c.name.contains(query.trim(), ignoreCase = true) } }
    val topics by vm.topics.collectAsStateWithLifecycle()
    val topicsFailed by vm.topicsFailed.collectAsStateWithLifecycle()
    val followedTopics by vm.followedTopics.collectAsStateWithLifecycle()
    val topicMatches = topics.orEmpty().filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

    ScreenScaffold(title = stringResource(R.string.pick_crises_title), onBack = { nav.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.pick_crises_help),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.pick_crises_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadErrorBanner(state.error, onRetry = { vm.load() }, onOpenSettings = nav::settings, persistent = state.crises == null)
            LazyColumn(Modifier.weight(1f).testTag("pick_crises")) {
                // Regional crises first (topics, lot 3): editors' pages that span countries.
                regionalCrises(topicMatches, topics != null, topicsFailed, followedTopics, onToggle = vm::toggleTopic, onRetry = { vm.loadTopics(force = true) })
                if (state.crises != null && matches.isEmpty()) {
                    item {
                        // A search with no match is often a conflict (passe-v0.2.md 1.2): say so, offer countries.
                        if (query.isBlank()) {
                            EmptyState(stringResource(R.string.pick_crises_none))
                        } else {
                            EmptyState(stringResource(R.string.pick_crises_conflicts), stringResource(R.string.pick_crises_countries), nav::pickCountries)
                        }
                    }
                }
                if (matches.isNotEmpty()) item { SectionHeader(stringResource(R.string.pick_crises_section), matches.size.toString()) }
                items(matches, key = { it.id }) { crisis ->
                    val checked = crisis.id in state.followed
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.toggle(crisis, !checked) }.padding(horizontal = 8.dp).testTag("pick_crisis_${crisis.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { vm.toggle(crisis, it) })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(crisis.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(crisisLine(crisis).ifBlank { null }, countriesLine(crisis.countries).ifBlank { null }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { nav.back() }) { Text(stringResource(R.string.pick_done)) }
            }
        }
    }
}
