// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.ReportEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class OlderHeadlinesViewModel(c: AppContainer) : ViewModel() {
    /** Every headline of ReliefWeb's feed kept on the phone, newest first, and the read ones. */
    val state: StateFlow<Pair<List<ReportEntity>, Set<Long>>?> = combine(
        c.dao.observeHeadlines(fromRss = true, since = 0),
        c.dao.observeReadIds(),
    ) { headlines, read -> headlines to read.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * "Older headlines" without an appname (revue-ux-v0.3.md, section 6): the headlines of the RSS
 * feed on the phone, newest first, then a clear end. The feed carries the 20 latest; the phone
 * keeps those it received before.
 */
@Composable
fun OlderHeadlinesScreen(nav: Nav) {
    val container = LocalContext.current.container
    val vm = viewModel { OlderHeadlinesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.pile_older), onBack = { nav.back() }) { padding ->
        val (headlines, read) = state ?: return@ScreenScaffold
        // A plain report: opened from here, it notes no place in the pile, and Today does not
        // jump to its card later (review of PR 12).
        OlderHeadlinesList(headlines, read, onOpen = { nav.report(it) }, modifier = Modifier.padding(padding))
    }
}

/** The list and its end: what is read, and where older headlines are to be found. */
@Composable
internal fun OlderHeadlinesList(headlines: List<ReportEntity>, read: Set<Long>, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("page_older_headlines")) {
        items(headlines, key = { it.id }) { report ->
            DocumentRow(report, onClick = { onOpen(report.id) }, isRead = report.id in read, title = report.displayTitle)
        }
        item(key = "end") {
            val allRead = headlines.isNotEmpty() && headlines.all { it.id in read }
            Column(Modifier.padding(horizontal = Space.screen, vertical = Space.xl).testTag("older_end"), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    if (allRead) pluralStringResource(R.plurals.older_all_read, headlines.size, headlines.size) else stringResource(R.string.older_every_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.older_feed_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
