// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.relais.app.R
import io.relais.app.data.LatestRelease
import io.relais.app.data.UpdateCheck

/**
 * "Re.L 0.5.2 is available" at the top of Today (founder, 15/11): the same compact line as the
 * other status lines, with "Not now", which hides it for this version only, and "See the
 * release", which opens the release page in the browser. Only a release page of the repository
 * on github.com opens. When the message and the buttons don't fit on one line (large text, a
 * narrow phone), the buttons go under the message, which keeps the whole width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateLine(release: LatestRelease, onNotNow: () -> Unit, onOpen: (String) -> Unit = rememberReleaseOpener()) {
    val cs = MaterialTheme.colorScheme
    MessageThenActions(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).heightIn(min = 40.dp)
            .padding(start = Space.screen, end = Space.xs).testTag("update_line"),
        message = {
            Text(
                stringResource(R.string.update_available, release.version),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp).testTag("update_text"),
            )
        },
        actions = {
            // A flow, so that at very large text "See the release" goes under "Not now" instead of
            // being squeezed.
            FlowRow(horizontalArrangement = Arrangement.End) {
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
        },
    )
}

/**
 * The message, then the actions at the end of the same line when the message fits there on one
 * line; otherwise the message on the whole width and the actions under it, at the end. The
 * message is measured first, so large text never squeezes it (review of PR 23).
 */
@Composable
private fun MessageThenActions(modifier: Modifier, message: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Layout(contents = listOf(message, actions), modifier = modifier) { (messageParts, actionParts), constraints ->
        val width = constraints.maxWidth
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val buttons = actionParts.first().measure(free)
        val text = messageParts.first()
        val oneLine = text.maxIntrinsicWidth(Constraints.Infinity) + buttons.width <= width
        if (oneLine) {
            val placed = text.measure(free.copy(maxWidth = width - buttons.width))
            val height = maxOf(placed.height, buttons.height, constraints.minHeight)
            layout(width, height) {
                placed.place(0, (height - placed.height) / 2)
                buttons.place(width - buttons.width, (height - buttons.height) / 2)
            }
        } else {
            val placed = text.measure(free)
            val height = maxOf(placed.height + buttons.height, constraints.minHeight)
            layout(width, height) {
                placed.place(0, 0)
                buttons.place(width - buttons.width, placed.height)
            }
        }
    }
}

/** Opens a release page in the browser, through [safeOpen]. */
@Composable
private fun rememberReleaseOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return { url -> safeOpen(uriHandler, url) }
}
