// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.api.HtmlBlocks
import io.relais.api.RiverQuery
import io.relais.api.TopicInfo
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.LoadError
import io.relais.app.data.ReportEntity
import io.relais.app.data.toLoadError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * A regional crisis (ReliefWeb topic, lot 3): its head (title, updated, the editors' introduction
 * folded), a filter per list the app can read, then that list's documents. Lists it cannot read
 * are skipped, with a line and a link to the page on ReliefWeb.
 */

class TopicViewModel(private val c: AppContainer, private val id: Long, private val title: String) : ViewModel() {
    val info = MutableStateFlow<TopicInfo?>(null)
    val reports = MutableStateFlow<List<ReportEntity>?>(null)
    val error = MutableStateFlow<LoadError?>(null)
    val loading = MutableStateFlow(false)

    /** null: every list the app can read; otherwise the index of one list in the topic's rivers. */
    val selected = MutableStateFlow<Int?>(null)

    val followed: StateFlow<Boolean> = c.dao.observeFollowedTopics().map { list -> list.any { it.topicId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        load()
        viewModelScope.launch { c.repository.markTopicVisited(id) }
    }

    fun load(force: Boolean = false) = viewModelScope.launch {
        loading.value = true
        error.value = null
        try {
            info.value = c.repository.topic(id, force)
            loadReports()
        } catch (e: Exception) {
            error.value = e.toLoadError()
        } finally {
            loading.value = false
        }
    }

    fun select(river: Int?) {
        if (river == selected.value) return
        selected.value = river
        viewModelScope.launch { loadReports() }
    }

    private suspend fun loadReports() {
        val topic = info.value ?: return
        reports.value = null
        reports.value = try {
            c.repository.topicReports(topic, selected.value)
        } catch (e: Exception) {
            error.value = e.toLoadError()
            emptyList()
        }
    }

    fun toggleFollow() = viewModelScope.launch {
        runCatching { if (followed.value) c.repository.unfollowTopic(id) else c.repository.followTopic(id, title) }
            .onFailure { error.value = it.toLoadError() }
    }
}

/** The lists of a topic that the app can read, with their index among the topic's rivers. */
internal fun readableRivers(info: TopicInfo): List<IndexedValue<io.relais.api.TopicRiver>> =
    info.rivers.withIndex().filter { RiverQuery.parse(it.value.url) != null }

@Composable
fun TopicScreen(id: Long, title: String, nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "topic-$id") { TopicViewModel(container, id, title) }
    val info by vm.info.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val followed by vm.followed.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    ScreenScaffold(
        title = info?.title ?: title,
        onBack = { nav.back() },
        actions = { FollowChip(followed, onClick = { vm.toggleFollow() }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("page_topic")) {
            item(key = "status") {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LoadErrorBanner(error, onRetry = { vm.load(force = true) }, onOpenSettings = nav::settings, persistent = info == null)
            }
            val topic = info ?: return@LazyColumn
            item(key = "head") { TopicHead(topic) }
            val readable = readableRivers(topic)
            if (readable.size < topic.rivers.size) {
                item(key = "skipped") {
                    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.s).testTag("topic_skipped")) {
                        Text(stringResource(R.string.topic_lists_skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { safeOpen(uriHandler, topic.urlAlias) }, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.report_open_reliefweb))
                        }
                    }
                }
            }
            if (readable.isNotEmpty()) {
                item(key = "lists") {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = Space.screen),
                            modifier = Modifier.testTag("topic_lists"),
                        ) {
                            item(key = "all") { FilterPill(stringResource(R.string.tab_all), selected == null, onClick = { vm.select(null) }, tag = "topic_list_all") }
                            items(readable, key = { it.index }) { (index, river) ->
                                FilterPill(
                                    river.title.ifBlank { stringResource(R.string.topic_list_n, index + 1) },
                                    selected == index,
                                    onClick = { vm.select(index) },
                                    tag = "topic_list_$index",
                                )
                            }
                        }
                        HorizontalDivider(thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Space.s))
                    }
                }
            }
            val list = reports
            if (list == null) {
                if (readable.isNotEmpty() && error == null) item(key = "loading_list") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Space.screen)) }
            } else {
                if (list.isEmpty() && readable.isNotEmpty()) item(key = "empty") { EmptyState(stringResource(R.string.topic_list_empty)) }
                items(list, key = { it.id }) { report -> DocumentRow(report, onClick = { nav.report(report.id) }) }
            }
            item(key = "open") {
                TextButton(onClick = { safeOpen(uriHandler, topic.urlAlias) }, modifier = Modifier.padding(horizontal = Space.s, vertical = Space.m).testTag("topic_open")) {
                    Text(stringResource(R.string.report_open_reliefweb))
                }
            }
        }
    }
}

/** The title, "Updated 12 Nov", the note on what a regional crisis is, the introduction folded. */
@Composable
internal fun TopicHead(topic: TopicInfo) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val paragraphs = remember(topic.introductionHtml) {
        topic.introductionHtml?.let { HtmlBlocks.plainText(it) }.orEmpty()
            .split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(topic.title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        topic.changedAt?.let { Text(stringResource(R.string.topic_updated, Display.day(it)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        Text(stringResource(R.string.topic_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (paragraphs.isNotEmpty()) {
            Text(stringResource(R.string.crisis_description_by), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
            val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp)
            if (open) {
                paragraphs.forEach { Text(it, style = body, color = cs.onSurface) }
            } else {
                Text(paragraphs.first(), style = body, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("topic_more")) {
                Text(stringResource(if (open) R.string.topic_show_less else R.string.topic_show_more))
            }
        }
    }
}
