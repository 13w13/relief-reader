// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("page_older_headlines")) {
            val (headlines, read) = state ?: return@LazyColumn
            items(headlines, key = { it.id }) { report ->
                DocumentRow(report, onClick = { nav.headline(report.id) }, isRead = report.id in read, title = report.displayTitle)
            }
            item(key = "end") {
                val allRead = headlines.isNotEmpty() && headlines.all { it.id in read }
                Text(
                    if (allRead) stringResource(R.string.older_all_read, headlines.size) else stringResource(R.string.older_feed_limit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xl).testTag("older_end"),
                )
            }
        }
    }
}
