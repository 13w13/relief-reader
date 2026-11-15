// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.relais.app.R
import io.relais.app.data.LatestRelease
import io.relais.app.data.UpdateCheck

/**
 * "Re.L 0.5.2 is available" at the top of Today (founder, 15/11): the same compact line as the
 * other status lines, with "Not now", which hides it for this version only, and "See the
 * release", which opens the release page in the browser. Only a page of the repository on
 * github.com opens.
 */
@Composable
fun UpdateLine(release: LatestRelease, onNotNow: () -> Unit, onOpen: (String) -> Unit = rememberReleaseOpener()) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).heightIn(min = 40.dp)
            .padding(start = Space.screen, end = Space.xs).testTag("update_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.update_available, release.version),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        TextButton(onClick = onNotNow, contentPadding = PaddingValues(horizontal = Space.s), modifier = Modifier.testTag("update_not_now")) {
            Text(stringResource(R.string.update_not_now))
        }
        TextButton(
            onClick = { UpdateCheck.releasePage(release.url)?.let(onOpen) },
            contentPadding = PaddingValues(horizontal = Space.s),
            modifier = Modifier.testTag("update_open"),
        ) {
            Text(stringResource(R.string.update_see_release))
        }
    }
}

/** Opens a release page in the browser, through [safeOpen]. */
@Composable
private fun rememberReleaseOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return { url -> safeOpen(uriHandler, url) }
}
