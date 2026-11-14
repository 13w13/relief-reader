// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.relais.api.CountryTab
import io.relais.app.R

/**
 * One filter of the row that replaced the tabs (theme-compose.md 12.1), in its order. [tab] is
 * the API feed it reads; Most read has none: it reads ReliefWeb's file.
 */
enum class PageFilter(val tab: CountryTab?, @StringRes val label: Int) {
    ALL(CountryTab.ALL, R.string.tab_all),
    SITREPS(CountryTab.SITREPS, R.string.tab_sitreps),
    ANALYSIS(CountryTab.ANALYSIS, R.string.tab_analysis),
    MAPS(CountryTab.MAPS, R.string.tab_maps),
    APPEALS(CountryTab.APPEALS, R.string.tab_appeals),
    NEWS(CountryTab.NEWS, R.string.tab_news),
    KEY(CountryTab.KEY, R.string.tab_key),
    MOST_READ(null, R.string.filter_most_read),
    ;

    /** Test tag of the chip and of the list it shows ("filter_all", "page_all"). */
    val tag: String get() = name.lowercase()

    companion object {
        /** The filter named in a route ("" or unknown: All). */
        fun of(name: String?): PageFilter = entries.firstOrNull { it.name == name } ?: ALL

        /** Every filter but Most read, for a feed with no line in ReliefWeb's file. */
        val FEEDS: List<PageFilter> = entries.filter { it.tab != null }
    }
}

/**
 * The row of filters, kept under the top bar while the list scrolls: one active filter, as
 * pills with no check and no count; the sheet of organizations and languages behind the icon
 * at the end, with a badge once filters are on.
 */
@Composable
fun FilterRow(
    filters: List<PageFilter>,
    selected: PageFilter,
    onSelect: (PageFilter) -> Unit,
    sheetCount: Int = 0,
    onSheet: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = Space.screen),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).selectableGroup().testTag("filter_row"),
            ) {
                items(filters, key = { it.name }) { f ->
                    FilterPill(stringResource(f.label), f == selected, onClick = { if (f != selected) onSelect(f) }, tag = "filter_" + f.tag)
                }
            }
            if (onSheet != null) {
                val label = if (sheetCount > 0) stringResource(R.string.results_filters_count, sheetCount) else stringResource(R.string.results_filters)
                IconButton(onClick = onSheet, modifier = Modifier.testTag("country_filters")) {
                    BadgedBox(badge = { if (sheetCount > 0) Badge(containerColor = cs.primary) { Text(sheetCount.toString()) } }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = label)
                    }
                }
            }
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
    }
}

/** A pill of the row: outlined in ink 2, or filled primaryContainer and SemiBold when active. */
@Composable
private fun FilterPill(label: String, active: Boolean, onClick: () -> Unit, tag: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) cs.primaryContainer else Color.Transparent,
        border = if (active) null else BorderStroke(1.dp, cs.outline),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(Space.chip)
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = Space.chipText,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
