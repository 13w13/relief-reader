// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.relais.app.R

/**
 * "What's new" (theme-compose.md 13.4): 3 to 5 lines per version, written at each release with
 * its notes and passed through unslop. A lead (2 to 6 words) then a sentence; 90 characters at
 * most for the whole line. Only what is really in the published version.
 */
object WhatsNew {
    data class Line(@StringRes val lead: Int, @StringRes val text: Int)
    data class Release(val versionCode: Int, val label: String, val lines: List<Line>)

    val releases: List<Release> = listOf(
        Release(
            versionCode = 8,
            label = "0.3",
            lines = listOf(
                Line(R.string.whats_new_03_lead_1, R.string.whats_new_03_text_1),
                Line(R.string.whats_new_03_lead_2, R.string.whats_new_03_text_2),
                Line(R.string.whats_new_03_lead_3, R.string.whats_new_03_text_3),
                Line(R.string.whats_new_03_lead_4, R.string.whats_new_03_text_4),
            ),
        ),
        Release(
            versionCode = 11,
            label = "0.4",
            lines = listOf(
                Line(R.string.whats_new_04_lead_1, R.string.whats_new_04_text_1),
                Line(R.string.whats_new_04_lead_2, R.string.whats_new_04_text_2),
                Line(R.string.whats_new_04_lead_3, R.string.whats_new_04_text_3),
                Line(R.string.whats_new_04_lead_4, R.string.whats_new_04_text_4),
                Line(R.string.whats_new_04_lead_5, R.string.whats_new_04_text_5),
            ),
        ),
        Release(
            versionCode = 12,
            label = "0.5",
            lines = listOf(
                Line(R.string.whats_new_05_lead_1, R.string.whats_new_05_text_1),
                Line(R.string.whats_new_05_lead_2, R.string.whats_new_05_text_2),
                Line(R.string.whats_new_05_lead_3, R.string.whats_new_05_text_3),
                Line(R.string.whats_new_05_lead_4, R.string.whats_new_05_text_4),
                Line(R.string.whats_new_05_lead_5, R.string.whats_new_05_text_5),
            ),
        ),
        Release(
            versionCode = 13,
            label = "0.5.1",
            lines = listOf(Line(R.string.whats_new_051_lead_1, R.string.whats_new_051_text_1)),
        ),
        Release(
            versionCode = 14,
            label = "0.6",
            lines = listOfNotNull(
                Line(R.string.whats_new_06_lead_1, R.string.whats_new_06_text_1),
                Line(R.string.whats_new_06_lead_2, R.string.whats_new_06_text_2),
                // Not in a build without the check (F-Droid).
                Line(R.string.whats_new_06_lead_3, R.string.whats_new_06_text_3).takeIf { io.relais.app.BuildConfig.UPDATE_CHECK },
                // 0.5.1's line, for a reader coming from 0.5.0.
                Line(R.string.whats_new_051_lead_1, R.string.whats_new_051_text_1),
            ),
        ),
        Release(
            versionCode = 16,
            label = "0.6.2",
            lines = listOf(Line(R.string.whats_new_062_lead_1, R.string.whats_new_062_text_1)),
        ),
        Release(
            versionCode = 17,
            label = "0.7",
            lines = listOfNotNull(
                Line(R.string.whats_new_07_lead_1, R.string.whats_new_07_text_1),
                Line(R.string.whats_new_07_lead_2, R.string.whats_new_07_text_2),
                // Not in a build without the check (F-Droid).
                Line(R.string.whats_new_07_lead_3, R.string.whats_new_07_text_3).takeIf { io.relais.app.BuildConfig.UPDATE_CHECK },
            ),
        ),
    )

    /** The lines of exactly this version, for the sheet after an update; null: no sheet. */
    fun of(versionCode: Int): Release? = releases.firstOrNull { it.versionCode == versionCode && it.lines.isNotEmpty() }

    /** The latest version with lines up to this one, for Settings > About. */
    fun latest(versionCode: Int): Release? = releases.filter { it.versionCode <= versionCode && it.lines.isNotEmpty() }.maxByOrNull { it.versionCode }
}

/**
 * The sheet (13.4): "VERSION 0.3", "What's new", the lines with a hairline between them, and
 * "Got it". The button, a drag down, a tap on the scrim and Back all close it the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(release: WhatsNew.Release, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp).navigationBarsPadding()
                .testTag("whats_new"),
        ) {
            Text(
                stringResource(R.string.whats_new_version, release.label).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.whats_new_title),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp).semantics { heading() },
            )
            release.lines.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
                val lead = stringResource(line.lead)
                val text = stringResource(line.text)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }
                        append(" ")
                        append(text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 24.dp).testTag("whats_new_ok")) {
                Text(stringResource(R.string.whats_new_got_it))
            }
        }
    }
}
