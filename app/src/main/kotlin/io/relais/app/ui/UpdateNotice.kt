// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.relais.app.R
import io.relais.app.data.LatestRelease
import io.relais.app.data.ManualCheck
import io.relais.app.data.UpdateCheck
import io.relais.app.data.VersionNotice

/**
 * "Re.L 0.5.2 is available" at the top of Today (founder, 15/11): the same compact line as the
 * other status lines, with "Not now", which hides it for this version only, "See the release",
 * which opens the release page in the browser, and "Download" when the release has a valid APK
 * (founder, 16/11), which opens the APK's address in the browser. Only a release page or an APK of
 * the repository on github.com opens, checked again on the tap. When the message and the buttons
 * don't fit on one line (large text, a narrow phone), the buttons go under the message, which
 * keeps the whole width.
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
                modifier = Modifier.padding(vertical = 6.dp).politeLive().testTag("update_text"),
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
                if (UpdateCheck.apkLink(release.apk, release.version) != null) {
                    TextButton(
                        onClick = { UpdateCheck.apkLink(release.apk, release.version)?.let(onOpen) },
                        contentPadding = PaddingValues(horizontal = Space.s),
                        modifier = Modifier.testTag("update_download"),
                    ) {
                        Text(stringResource(R.string.update_download))
                    }
                }
            }
        },
    )
}

/**
 * "Please update Re.L to 0.7.1." (founder, 16/11): the latest release asks this version to update.
 * The same line as the notice, with "Download" (a valid APK only) and "See the release", and no
 * "Not now": it stays until the app is up to date. Nothing is blocked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PleaseUpdateLine(release: LatestRelease, onOpen: (String) -> Unit = rememberReleaseOpener()) {
    val cs = MaterialTheme.colorScheme
    MessageThenActions(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).heightIn(min = 40.dp)
            .padding(start = Space.screen, end = Space.xs).testTag("please_update_line"),
        message = {
            Text(
                stringResource(R.string.update_please, release.version),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                modifier = Modifier.padding(vertical = 6.dp).politeLive().testTag("please_update_text"),
            )
        },
        actions = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { UpdateCheck.releasePage(release.url)?.let(onOpen) },
                    contentPadding = PaddingValues(horizontal = Space.s),
                    modifier = Modifier.testTag("please_update_open"),
                ) {
                    Text(stringResource(R.string.update_see_release))
                }
                if (UpdateCheck.apkLink(release.apk, release.version) != null) {
                    TextButton(
                        onClick = { UpdateCheck.apkLink(release.apk, release.version)?.let(onOpen) },
                        contentPadding = PaddingValues(horizontal = Space.s),
                        modifier = Modifier.testTag("please_update_download"),
                    ) {
                        Text(stringResource(R.string.update_download))
                    }
                }
            }
        },
    )
}

/**
 * "This version of Re.L is 2 months old. Check for a newer one." (founder, 16/11): from the phone's
 * date only, no request. "Check now" runs the check (inactive while it runs, and the line says
 * so), "Not now" hides it for 14 days. A failed check is said in the line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgedLine(notice: VersionNotice.Aged, onCheckNow: () -> Unit, onNotNow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    MessageThenActions(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).heightIn(min = 40.dp)
            .padding(start = Space.screen, end = Space.xs).testTag("aged_line"),
        message = {
            val failure = notice.failure
            Text(
                when {
                    notice.checking -> stringResource(R.string.update_checking)
                    failure != null -> stringResource(R.string.update_check_failed, failure)
                    else -> pluralStringResource(R.plurals.update_aged, notice.months, notice.months)
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp).politeLive().testTag("aged_text"),
            )
        },
        actions = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNotNow, contentPadding = PaddingValues(horizontal = Space.s), modifier = Modifier.testTag("aged_not_now")) {
                    Text(stringResource(R.string.update_not_now))
                }
                TextButton(
                    onClick = onCheckNow,
                    enabled = !notice.checking,
                    contentPadding = PaddingValues(horizontal = Space.s),
                    modifier = Modifier.testTag("aged_check_now"),
                ) {
                    Text(stringResource(R.string.update_check_now))
                }
            }
        },
    )
}

/**
 * "Check now" in Settings > About (founder, 16/11), under the version line: the button, then the
 * result under it, never in a dialog. The button is inactive while a check is under way. A new
 * version offers "Download the APK" (only with a valid APK) and "See the release", both opened in
 * the browser after a new check of the address.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheckNow(state: ManualCheck, onCheckNow: () -> Unit, onOpen: (String) -> Unit, standing: LatestRelease? = null) {
    Column(Modifier.fillMaxWidth().testTag("check_now")) {
        TextButton(
            onClick = onCheckNow,
            enabled = state != ManualCheck.Checking,
            modifier = Modifier.padding(horizontal = 8.dp).testTag("check_now_button"),
        ) {
            Text(stringResource(R.string.update_check_now))
        }
        // The release to offer: the one a tap just found, else a stored "Please update"
        // ([standing]), which stays except while a check runs (review of PR 46): a failed tap
        // adds its message under it instead of hiding it.
        val offered: ManualCheck.Available? = when {
            state is ManualCheck.Available -> state
            standing != null && state != ManualCheck.Checking -> ManualCheck.Available(standing, pleaseUpdate = true)
            else -> null
        }
        if (offered != null) {
            CheckNowLine(
                stringResource(if (offered.pleaseUpdate) R.string.update_please else R.string.update_found, offered.release.version),
                tag = "check_now_found",
            )
        }
        val message = when (state) {
            ManualCheck.Idle, is ManualCheck.Available -> null
            ManualCheck.Checking -> stringResource(R.string.update_checking)
            is ManualCheck.UpToDate -> stringResource(R.string.update_up_to_date, state.installed)
            is ManualCheck.Failed -> stringResource(R.string.update_check_failed, state.reason)
        }
        if (message != null) CheckNowLine(message, tag = "check_now_result")
        if (offered != null) {
            val release = offered.release
            FlowRow(Modifier.padding(horizontal = 8.dp)) {
                if (UpdateCheck.apkLink(release.apk, release.version) != null) {
                    TextButton(
                        onClick = { UpdateCheck.apkLink(release.apk, release.version)?.let(onOpen) },
                        modifier = Modifier.testTag("check_now_download"),
                    ) {
                        Text(stringResource(R.string.update_download_apk))
                    }
                }
                TextButton(
                    onClick = { UpdateCheck.releasePage(release.url)?.let(onOpen) },
                    modifier = Modifier.testTag("check_now_release"),
                ) {
                    Text(stringResource(R.string.update_see_release))
                }
            }
        }
    }
}

/** A line of Check now's result, read out by TalkBack when it changes (review of PR 44). */
@Composable
private fun CheckNowLine(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).politeLive().testTag(tag),
    )
}

/** A text TalkBack reads out when it changes, without interrupting (reviews of PR 44 and 46). */
private fun Modifier.politeLive(): Modifier = semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }

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

/** Opens a release page or an APK in the browser, through [safeOpen]. */
@Composable
internal fun rememberReleaseOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return { url -> safeOpen(uriHandler, url) }
}
