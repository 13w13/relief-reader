// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.PdfCache
import io.relais.app.work.PreloadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PdfStorageState(
    /** All the PDFs on the phone, saved reports included. */
    val totalBytes: Long = 0,
    /** The PDFs of the cache folder: what the cap counts. */
    val cacheBytes: Long = 0,
    val capMb: Int = PdfCache.DEFAULT_CAP_MB,
    val preload: Boolean = true,
)

class PdfStorageViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PdfStorageState> = combine(c.settings.flow, c.dao.observePdfs()) { s, pdfs ->
        PdfStorageState(
            totalBytes = pdfs.sumOf { it.bytes },
            cacheBytes = PdfCache.inCache(pdfs, c.pdfs.cacheFolder).sumOf { it.bytes },
            capMb = s.pdfCapMb,
            preload = s.preloadPdfs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PdfStorageState())

    /** A smaller cap deletes the oldest PDFs at once, never those of saved or highlighted reports. */
    fun setCap(mb: Int) = viewModelScope.launch {
        c.settings.setPdfCapMb(mb)
        c.pdfs.enforceCap()
    }

    fun setPreload(context: Context, on: Boolean) = viewModelScope.launch {
        c.settings.setPreloadPdfs(on)
        PreloadWorker.schedule(context, on)
    }
}

/**
 * Settings > Storage, above "Delete downloaded PDFs": the space the PDFs take, the cap of the
 * PDFs of reports that are not saved (issue 6), and the Wi-Fi preload (PreloadWorker).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfStorageSection() {
    val context = LocalContext.current
    val vm = viewModel { PdfStorageViewModel(context.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column {
        StorageText(stringResource(R.string.settings_pdfs, Display.size(state.totalBytes) ?: "0 KB"))
        StorageText(stringResource(R.string.pdf_cache_space))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PdfCache.CAPS_MB.forEach { mb ->
                FilterChip(
                    selected = state.capMb == mb,
                    onClick = { vm.setCap(mb) },
                    label = { Text(PdfCache.capLabel(mb)) },
                    modifier = Modifier.testTag("pdf_cap_$mb"),
                )
            }
        }
        StorageText(
            stringResource(R.string.pdf_cache_used, Display.size(state.cacheBytes) ?: "0 KB", PdfCache.capLabel(state.capMb)),
            secondary = true,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pdf_preload), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = state.preload,
                onCheckedChange = { vm.setPreload(context, it) },
                modifier = Modifier.testTag("preload_switch"),
            )
        }
        StorageText(stringResource(R.string.pdf_preload_help), secondary = true)
    }
}

@Composable
private fun StorageText(text: String, secondary: Boolean = false) {
    Text(
        text,
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
