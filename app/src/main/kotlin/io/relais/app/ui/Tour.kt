// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.relais.app.R
import io.relais.app.data.FileLite
import io.relais.app.data.FollowedRow
import io.relais.app.data.PdfEntity
import io.relais.app.data.Ref
import io.relais.app.data.ReportEntity
import io.relais.app.data.SourceLite
import kotlinx.coroutines.launch

/**
 * The first launch tour (issue 37, founder, 16/11): one idea per page, a short title and two
 * sentences at most, in the words of the screens. Shown once after the first launch setup, and
 * from Settings > About Re.L > "Show the tour again".
 */
internal enum class TourPage(@StringRes val title: Int, @StringRes val text: Int) {
    TODAY(R.string.tour_today_title, R.string.tour_today_text),
    FOLLOWING(R.string.tour_following_title, R.string.tour_following_text),
    REPORT(R.string.tour_report_title, R.string.tour_report_text),
    HIGHLIGHT(R.string.tour_highlight_title, R.string.tour_highlight_text),
    OFFLINE(R.string.tour_offline_title, R.string.tour_offline_text),
}

/**
 * The tour: pages turned by a swipe (HorizontalPager), the progress dots, "Skip" on every page,
 * "Next", then "Start reading" on the last. Back goes to the page before, and on the first page
 * skips the tour. [onDone] runs once, for "Skip", "Start reading" and Back on the first page.
 * With animations turned off on the phone, Next and Back turn the page without sliding.
 * [initialPage] is for the screenshots.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TourScreen(onDone: () -> Unit, initialPage: Int = 0) {
    val pages = TourPage.entries
    val pager = rememberPagerState(initialPage = initialPage) { pages.size }
    val scope = rememberCoroutineScope()
    val still = rememberAnimationsOff()
    fun go(page: Int) {
        scope.launch { if (still) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    BackHandler { if (pager.currentPage > 0) go(pager.currentPage - 1) else onDone() }
    val last = pager.currentPage == pages.lastIndex
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("tour")) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i -> TourPageContent(pages[i]) }
        TourDots(pager.currentPage, pages.size, Modifier.align(Alignment.CenterHorizontally).padding(vertical = Space.s))
        // A flow: at very large text, "Start reading" goes under "Skip" instead of being squeezed.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.s).padding(bottom = Space.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("tour_skip")) {
                Text(stringResource(R.string.tour_skip))
            }
            Button(
                onClick = { if (last) onDone() else go(pager.currentPage + 1) },
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = Space.s).testTag("tour_next"),
            ) {
                Text(stringResource(if (last) R.string.tour_start else R.string.tour_next))
            }
        }
    }
}

/** One page: the illustration (decorative), then the title and the text; scrolls when it is long. */
@Composable
private fun TourPageContent(page: TourPage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen).padding(top = Space.l, bottom = Space.m)
            .testTag("tour_page_" + page.name.lowercase()),
    ) {
        TourIllustration(page)
        Spacer(Modifier.height(Space.l))
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag("tour_title"),
        )
        Spacer(Modifier.height(Space.s))
        Text(stringResource(page.text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The dots: the current page in ink, the others in the hairline colour. Read as "Page 2 of 5". */
@Composable
private fun TourDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val description = stringResource(R.string.tour_page_of, current + 1, count)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description }.testTag("tour_dots"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).background(if (i == current) cs.onSurface else cs.outlineVariant, CircleShape))
        }
    }
}

/**
 * The real components of the app with made-up data, so that they follow the theme and the
 * display size, in a frame. Decorative: nothing in them is read by TalkBack (the page's text
 * carries the meaning), and a tap does nothing, while a swipe still turns the page.
 */
@Composable
private fun TourIllustration(page: TourPage) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(Hairline, cs.outlineVariant, RoundedCornerShape(12.dp))
            .background(cs.background)
            .padding(vertical = Space.s)
            .decorative()
            .testTag("tour_illustration"),
    ) {
        when (page) {
            TourPage.TODAY -> {
                SectionHeader(stringResource(R.string.today_headlines), stringResource(R.string.today_headlines_subtitle))
                DocumentRow(TourSamples.floods, onClick = {}, isNew = true)
                DocumentRow(TourSamples.outlook, onClick = {})
            }
            TourPage.FOLLOWING -> {
                FollowedCountryRow(TourSamples.osmira, onClick = {})
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Hairline, color = cs.outlineVariant)
                FollowedCountryRow(TourSamples.tarvel, onClick = {})
            }
            TourPage.REPORT -> {
                ReportHeader(TourSamples.floods)
                FileLines(TourSamples.floods, downloads = emptyMap(), downloaded = emptyMap(), onRead = {}, onCancel = {})
            }
            TourPage.HIGHLIGHT -> {
                val highlight = LocalRelaisColors.current.highlight
                val first = stringResource(R.string.tour_sample_sentence_1)
                val second = stringResource(R.string.tour_sample_sentence_2)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(background = highlight)) { append(first) }
                        append(" ")
                        append(second)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
                )
                Text(
                    stringResource(R.string.tour_sample_citation),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.screen).padding(bottom = Space.s),
                )
            }
            TourPage.OFFLINE -> {
                Box(Modifier.padding(horizontal = Space.screen)) { DisplayPreview() }
                Spacer(Modifier.height(Space.s))
                FileLines(
                    TourSamples.floods,
                    downloads = emptyMap(),
                    downloaded = mapOf(TourSamples.pdf.url to PdfEntity(TourSamples.pdf.url, TourSamples.floods.id, "", TourSamples.pdf.size ?: 0, 0)),
                    onRead = {},
                    onCancel = {},
                )
            }
        }
    }
}

/**
 * Decorative: no semantics for TalkBack, and a tap (a press without a move past the touch slop)
 * is consumed before the components see it, so none of them opens anything. A move goes on to
 * the pager, which turns the page.
 */
private fun Modifier.decorative(): Modifier = clearAndSetSemantics {}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
            var moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (change.changedToUpIgnoreConsumed()) {
                    if (!moved) change.consume()
                    break
                }
            }
        }
    }
}

/** Whether the phone's animations are turned off (Settings > Accessibility > Remove animations). */
@Composable
private fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Made-up data for the illustrations: places and organizations that do not exist, never real
 * ReliefWeb content. Negative ids, which ReliefWeb never uses.
 */
internal object TourSamples {
    private val now = System.currentTimeMillis()
    private const val HOUR = 60 * 60 * 1000L

    val pdf = FileLite(
        url = "https://reliefweb.int/attachments/00/example-situation-report.pdf",
        filename = "example-situation-report.pdf",
        size = 1_840_000,
        pages = 12,
        mimetype = "application/pdf",
    )

    private fun report(id: Long, title: String, format: Long, formatName: String, source: SourceLite, ageHours: Long, files: List<FileLite> = emptyList()) = ReportEntity(
        id = id,
        title = title,
        headlineTitle = null,
        headlineSummary = null,
        imageUrl = null,
        imageCaption = null,
        imageCopyright = null,
        urlAlias = null,
        origin = null,
        dateCreated = now - ageHours * HOUR,
        dateOriginal = null,
        primaryCountryId = -10,
        primaryCountryName = "Osmira",
        countries = listOf(Ref(-10, "Osmira")),
        sources = listOf(source),
        formatId = format,
        formatName = formatName,
        themes = emptyList(),
        languages = listOf("English"),
        files = files,
        bodyHtml = null,
        isHeadline = true,
        fromRss = false,
        fetchedAt = now,
    )

    val floods = report(
        -1, "Osmira: floods displace about 8,000 people in two districts", 10, "Situation Report",
        SourceLite(-20, "ERN", "Example Relief Network"), ageHours = 2, files = listOf(pdf),
    )
    val outlook = report(
        -2, "Tarvel: food security outlook for the lean season", 3, "Analysis",
        SourceLite(-21, "TFC", "Tarvel Food Cluster"), ageHours = 5,
    )

    val osmira = FollowedRow(countryId = -10, name = "Osmira", status = "ongoing", lastVisitAt = now - 24 * HOUR, newCount = 6, lastReportAt = now - 2 * HOUR)
    val tarvel = FollowedRow(countryId = -11, name = "Tarvel", status = null, lastVisitAt = now - HOUR, newCount = 0, lastReportAt = now - 30 * HOUR)
}
