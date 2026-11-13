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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.relais.app.BuildConfig
import io.relais.app.R

/** The licence texts shipped in assets/licenses/, in the order of the screen. */
private val LICENCE_FILES = listOf(
    "THIRD_PARTY_NOTICES.txt" to "Third-party software",
    "LICENSE.txt" to "GNU General Public License, version 3",
    "Literata-OFL.txt" to "Literata, SIL Open Font License 1.1",
    "Inter-OFL.txt" to "Inter, SIL Open Font License 1.1",
    "IBMPlexSansCondensed-OFL.txt" to "IBM Plex Sans Condensed, SIL Open Font License 1.1",
    "LiberationSans-OFL.txt" to "Liberation Sans (in PdfBox-Android), SIL Open Font License 1.1",
    "Apache-2.0.txt" to "Apache License, version 2.0",
    "jsoup-MIT.txt" to "jsoup, MIT License",
)

/**
 * "Open source licences" (T19): Re.L under GPL-3.0-or-later, a link to the source code when the
 * public repository exists, then each licence text, folded.
 */
@Composable
fun LicencesScreen(nav: Nav) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val texts = remember {
        LICENCE_FILES.map { (file, title) ->
            title to runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    ScreenScaffold(title = stringResource(R.string.licences_title), onBack = { nav.back() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("page_licences")) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = Space.screen, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyMedium)
                    if (BuildConfig.SOURCE_URL.isNotBlank()) {
                        TextButton(onClick = { safeOpen(uriHandler, BuildConfig.SOURCE_URL) }) { Text(stringResource(R.string.about_source_code)) }
                    }
                }
            }
            items(texts, key = { it.first }) { (title, text) -> LicenceText(title, text) }
        }
    }
}

@Composable
private fun LicenceText(title: String, text: String) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.padding(horizontal = Space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant)
        }
        if (open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
            )
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}
