// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.LinkOpener
import io.relais.app.data.LinkTarget
import io.relais.app.data.ReliefWebLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Resolves one link once (a rotation keeps the result); "Try again" asks again. */
class LinkViewModel(private val opener: LinkOpener, private val url: String) : ViewModel() {
    private val _target = MutableStateFlow<LinkTarget?>(null)

    /** Null while it looks for the report. */
    val target: StateFlow<LinkTarget?> = _target.asStateFlow()

    init {
        resolve()
    }

    fun resolve() {
        val link = ReliefWebLinks.fromUrl(url)
        _target.value = null
        viewModelScope.launch { _target.value = if (link == null) LinkTarget.NotAReport else opener.open(link) }
    }

    /**
     * On coming back to the screen, "needs an appname" only is looked for again: the reader went
     * to Settings for it. Anything else waits for "Try again", so coming back from the browser
     * never spends a request or opens the report unasked (review of PR 47).
     */
    fun resolveAgainOnReturn() {
        if (_target.value == LinkTarget.NeedsAppname) resolve()
    }
}

/**
 * A ReliefWeb link given by another app (17/11): the report opens in place of this screen;
 * otherwise a short message, "Open in browser" always, and "Add an appname" or "Try again" when
 * they help. Any other page of reliefweb.int, a country's included, opens no screen of Re.L.
 * The link was checked twice before it came here.
 */
@Composable
fun LinkScreen(url: String, nav: Nav) {
    val context = LocalContext.current
    val vm = viewModel(key = url) { LinkViewModel(context.container.linkOpener, url) }
    val target by vm.target.collectAsStateWithLifecycle()
    // Back from Settings with an appname: looked for again (review of PR 47).
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.resolveAgainOnReturn() }
    LaunchedEffect(target) {
        when (val t = target) {
            is LinkTarget.Report -> nav.reportFromLink(t.id)
            else -> Unit
        }
    }
    val uriHandler = LocalUriHandler.current
    ScreenScaffold(title = stringResource(R.string.link_title), onBack = { nav.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinkMessage(
                target = target,
                onBrowser = { safeOpen(uriHandler, ReliefWebLinks.fromUrl(url)?.url) },
                onAddAppname = nav::settings,
                onRetry = vm::resolve,
            )
        }
    }
}

/**
 * The message for a link that does not open in Re.L, or "Opening…" while Re.L looks for it.
 * Read out by TalkBack when it changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinkMessage(target: LinkTarget?, onBrowser: () -> Unit, onAddAppname: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("link_message"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val text = when (target) {
            null, is LinkTarget.Report -> stringResource(R.string.link_opening)
            LinkTarget.NeedsAppname -> stringResource(R.string.link_needs_appname)
            LinkTarget.Offline -> stringResource(R.string.link_offline)
            LinkTarget.NotFound -> stringResource(R.string.link_not_found)
            LinkTarget.NotAReport -> stringResource(R.string.link_not_a_report)
            LinkTarget.TryLater -> stringResource(R.string.link_try_later)
            is LinkTarget.Failed -> stringResource(R.string.link_failed, target.reason)
        }
        if (target == null || target is LinkTarget.Report) {
            CircularProgressIndicator(Modifier.testTag("link_progress"))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("link_text"),
        )
        val done = target != null && target !is LinkTarget.Report
        if (done) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target == LinkTarget.NeedsAppname) {
                    Button(onClick = onAddAppname, modifier = Modifier.testTag("link_add_appname")) { Text(stringResource(R.string.link_add_appname)) }
                }
                if (target is LinkTarget.Failed || target == LinkTarget.Offline || target == LinkTarget.NeedsAppname || target == LinkTarget.TryLater) {
                    Button(onClick = onRetry, modifier = Modifier.testTag("link_retry")) { Text(stringResource(R.string.link_try_again)) }
                }
                OutlinedButton(onClick = onBrowser, modifier = Modifier.testTag("link_browser")) { Text(stringResource(R.string.action_open_browser)) }
            }
        }
    }
}
