// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.relais.api.Display
import io.relais.app.R
import io.relais.app.data.ReportEntity
import kotlinx.coroutines.launch

/*
 * The headlines of Today as a pile that the reader leafs through (revue-densite-v0.3.md, section
 * 3, and revue-ux-v0.3.md, section 8): one card at a time, newest first, with arrows that stay
 * on screen, a place counter ("3 of 6") and an end card that lists the week's titles. The same
 * counter closes a report opened from the pile ("Headline 3 of 6", Next, Previous).
 */

/** Where the pile opens: the first headline not read, newest first; the first one when all are read. */
internal fun pileOpening(headlines: List<ReportEntity>, readIds: Set<Long>): Int =
    headlines.indexOfFirst { it.id !in readIds }.coerceAtLeast(0)

/** The place of a headline in this week's pile, for the block at the end of its report. */
internal data class HeadlinePlace(
    /** From 0, newest first, as in the pile. */
    val index: Int,
    val total: Int,
    val previous: ReportEntity?,
    /** Null on the last one: "Next" then leads to the older headlines. */
    val next: ReportEntity?,
    val nextUnread: Boolean,
)

internal fun headlinePlace(headlines: List<ReportEntity>, id: Long, readIds: Set<Long>): HeadlinePlace? {
    val index = headlines.indexOfFirst { it.id == id }
    if (index < 0) return null
    val next = headlines.getOrNull(index + 1)
    return HeadlinePlace(index, headlines.size, headlines.getOrNull(index - 1), next, next != null && next.id !in readIds)
}

/** The country line of a card, dropped when the title already starts with that country. */
internal fun pileCountry(report: ReportEntity): String? {
    val line = metaLine(report)
    if (line.isBlank()) return null
    val name = report.primaryCountryName
    if (!name.isNullOrBlank() && report.displayTitle.startsWith(name, ignoreCase = true)) return null
    return line
}

/**
 * The pile: [headlines] newest first, one page each, then the end card. Only the card on screen
 * composes, so only its photo loads; [prefetchNext] says whether the next card's photo may load
 * ahead (on Wi-Fi only, decision of the team lead, 14/11). [menu] opens on a long press.
 */
@Composable
fun HeadlinePile(
    headlines: List<ReportEntity>,
    readIds: Set<Long>,
    since: Long,
    pager: PagerState,
    onOpen: (ReportEntity) -> Unit,
    onOlder: () -> Unit,
    prefetchNext: () -> Boolean = { false },
    menu: (@Composable ColumnScope.(report: ReportEntity, close: () -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val total = headlines.size
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val canPrefetch by rememberUpdatedState(prefetchNext)
    LaunchedEffect(pager.settledPage, headlines) {
        if (preview || !canPrefetch()) return@LaunchedEffect
        val url = headlines.getOrNull(pager.settledPage + 1)?.imageUrl ?: return@LaunchedEffect
        SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(url).build())
    }
    fun go(page: Int) {
        scope.launch { pager.animateScrollToPage(page.coerceIn(0, total)) }
    }
    // Every card has the same height (fixed lines); the end card takes at least that height, so
    // that the page does not jump from one card to the next.
    var cardHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().testTag("headline_pile")) {
        HorizontalPager(
            state = pager,
            key = { headlines.getOrNull(it)?.id ?: Long.MIN_VALUE },
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val report = headlines.getOrNull(page)
            if (report != null) {
                HeadlineCard(
                    report = report,
                    position = page,
                    total = total,
                    isRead = report.id in readIds,
                    isNew = report.dateCreated > since,
                    onClick = { onOpen(report) },
                    onPrevious = if (page > 0) ({ go(page - 1) }) else null,
                    onNext = { go(page + 1) },
                    menu = menu,
                    modifier = Modifier.onSizeChanged { if (it.height > cardHeight) cardHeight = it.height },
                )
            } else {
                PileEnd(
                    headlines, readIds, since, onOpen, onOlder,
                    modifier = Modifier.heightIn(min = with(density) { cardHeight.toDp() }),
                )
            }
        }
        PileBar(pager.currentPage, headlines, readIds, onPrevious = { go(pager.currentPage - 1) }, onNext = { go(pager.currentPage + 1) })
    }
}

/**
 * A card of the pile: the photo (2:1 in Compact, 16:9 otherwise) on a neutral background while
 * it loads, or without one; then kicker, title on 3 lines and summary on 2, all fixed, so that
 * every card has the same height. The country line is dropped when the title starts with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeadlineCard(
    report: ReportEntity,
    position: Int,
    total: Int,
    isRead: Boolean,
    isNew: Boolean,
    onClick: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
    menu: (@Composable ColumnScope.(report: ReportEntity, close: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalRelaisColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val place = stringResource(R.string.pile_headline_of, position + 1, total)
    val nextLabel = stringResource(R.string.pile_next)
    val previousLabel = stringResource(R.string.pile_previous)
    val moreLabel = stringResource(R.string.action_more)
    Box(modifier) {
        // headline_unread_<id> or headline_read_<id>: the journeys find a headline left to read.
        Column(
            Modifier.fillMaxWidth().testTag(if (isRead) "headline_read_${report.id}" else "headline_unread_${report.id}")
                .combinedClickable(onClick = onClick, onLongClick = if (menu != null) ({ menuOpen = true }) else null)
                .semantics {
                    stateDescription = place
                    customActions = buildList {
                        add(CustomAccessibilityAction(nextLabel) { onNext(); true })
                        if (onPrevious != null) add(CustomAccessibilityAction(previousLabel) { onPrevious(); true })
                        if (menu != null) add(CustomAccessibilityAction(moreLabel) { menuOpen = true; true })
                    }
                },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(LocalDisplaySize.current.heroRatio).background(extra.image)) {
                if (report.imageUrl != null) {
                    AsyncImage(
                        model = report.imageUrl,
                        contentDescription = report.imageCaption,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    report.imageCopyright?.let {
                        Text(
                            it.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.BottomEnd).background(cs.surface).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(Space.screen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Kicker(report, isNew = isNew && !isRead)
                Text(
                    report.displayTitle,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (isRead) cs.onSurfaceVariant else cs.onSurface,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Two lines of summary, or one and the country when the title does not name it.
                val style = MaterialTheme.typography.bodyMedium
                val country = pileCountry(report)
                val summary = report.headlineSummary
                Column(Modifier.fillMaxWidth().height(with(LocalDensity.current) { (style.lineHeight * 2).toDp() })) {
                    if (summary != null) {
                        Text(summary, style = style, color = cs.onSurfaceVariant, maxLines = if (country != null) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (country != null) Text(country, style = style, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (menu != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu(report) { menuOpen = false } }
        }
    }
}

/**
 * After the last card: the week's titles on one line each (ink if not read, ink 2 if read), each
 * reopening its headline, then "Older headlines". A pile shows one headline at a time; the end
 * gives back the overview (condition of the product review).
 */
@Composable
private fun PileEnd(
    headlines: List<ReportEntity>,
    readIds: Set<Long>,
    since: Long,
    onOpen: (ReportEntity) -> Unit,
    onOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val left = headlines.count { it.id !in readIds }
    Column(modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.xs).testTag("pile_end")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            val header = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            if (left == 0) {
                Box(Modifier.size(22.dp).background(cs.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
                }
                Text(stringResource(R.string.today_up_to_date), style = header)
            } else {
                Text(pluralStringResource(R.plurals.pile_end_title, headlines.size, headlines.size), style = header)
                Text(stringResource(R.string.pile_end_left, left), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        headlines.forEachIndexed { i, report ->
            val read = report.id in readIds
            HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(48.dp).clickable { onOpen(report) }
                    .testTag((if (read) "pile_line_read_" else "pile_line_unread_") + report.id),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(16.dp))
                Text(
                    report.displayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (read) FontWeight.SemiBold else FontWeight.Bold,
                    ),
                    color = if (read) cs.onSurfaceVariant else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!read && report.dateCreated > since) NewDot()
            }
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
        OlderHeadlinesLink(onOlder)
    }
}

/** "Older headlines ›", at the end of the pile and when the week has none. */
@Composable
internal fun OlderHeadlinesLink(onOlder: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOlder).testTag("pile_older"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.pile_older), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = cs.primary)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun NewDot() {
    val label = stringResource(R.string.label_new)
    Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape).semantics { stateDescription = label })
}

/**
 * Under the card, 48 dp: ‹, one stroke per headline, "3 of 6", ›. The arrows stay on screen and
 * turn grey at either end; the swipe comes on top of them, for TalkBack and for whoever does
 * not guess the gesture. The ink stroke is the current place, pale blue a headline not read,
 * grey a read one; the text and TalkBack say the same.
 */
@Composable
private fun PileBar(current: Int, headlines: List<ReportEntity>, readIds: Set<Long>, onPrevious: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = headlines.size
    Column {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = current > 0, modifier = Modifier.testTag("pile_previous")) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.pile_previous))
            }
            Row(
                Modifier.weight(1f).clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                headlines.forEachIndexed { i, report ->
                    val color = when {
                        i == current -> cs.onSurface
                        report.id in readIds -> cs.outline
                        else -> cs.primary.copy(alpha = 0.45f)
                    }
                    Box(
                        Modifier.weight(1f, fill = false).widthIn(max = 18.dp).fillMaxWidth()
                            .height(if (i == current) 4.dp else 3.dp).background(color, RoundedCornerShape(2.dp)),
                    )
                }
                Box(
                    Modifier.width(6.dp).height(if (current >= total) 4.dp else 3.dp)
                        .background(if (current >= total) cs.onSurface else cs.outline, RoundedCornerShape(2.dp)),
                )
            }
            Text(
                if (current < total) stringResource(R.string.pile_position, current + 1, total) else stringResource(R.string.pile_end),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 56.dp).padding(end = Space.xs).semantics { liveRegion = LiveRegionMode.Polite }.testTag("pile_position"),
            )
            IconButton(onClick = onNext, enabled = current < total, modifier = Modifier.testTag("pile_next")) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.pile_next))
            }
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
    }
}

/**
 * Right after the text of a report opened from the pile, before Details: "Headline 3 of 6", the
 * next one with its title, and "Previous headline" (grey on the first). On the last, "Next"
 * leads to the older headlines. Opening one replaces the report, without stacking.
 */
@Composable
internal fun HeadlineNavBlock(place: HeadlinePlace, onNext: (ReportEntity) -> Unit, onPrevious: (ReportEntity) -> Unit, onOlder: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = place.next
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.s).testTag("headline_nav")) {
        HorizontalDivider(thickness = SectionRule, color = cs.onSurface)
        Text(
            stringResource(R.string.pile_headline_of, place.index + 1, place.total).uppercase(),
            style = SectionTitleStyle.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = cs.onSurface,
            modifier = Modifier.padding(top = Space.s, bottom = Space.xs).semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable { if (next != null) onNext(next) else onOlder() }
                .padding(vertical = 10.dp).testTag("headline_next"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (place.nextUnread) NewDot()
                    Text(
                        stringResource(R.string.headline_nav_next).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = cs.onSurface,
                    )
                    if (next != null) {
                        Text(
                            listOf(Display.shortFormat(next.formatId, next.formatName), next.sourceShort).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    next?.displayTitle ?: stringResource(R.string.pile_older),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.onSurface)
        }
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant)
        val previous = place.previous
        TextButton(
            onClick = { previous?.let(onPrevious) },
            enabled = previous != null,
            contentPadding = PaddingValues(end = Space.s),
            modifier = Modifier.heightIn(min = 48.dp).testTag("headline_previous"),
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.pile_previous))
        }
    }
}
