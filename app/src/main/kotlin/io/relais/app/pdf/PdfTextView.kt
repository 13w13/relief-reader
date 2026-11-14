// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.relais.api.Display
import io.relais.app.R
import io.relais.app.ui.Banner
import io.relais.app.ui.LocalRelaisColors
import io.relais.app.ui.LocalTextStep
import io.relais.app.ui.PlexCondensed
import io.relais.app.ui.SelectableText
import io.relais.app.ui.Space

/*
 * The text view of a PDF (theme-compose.md 5.11): bars, banner, page markers, the text in
 * the report's styles, and the lines that send back to the PDF for tables and figures.
 * Stateless: PdfViewerScreen holds the state, the screenshot tests draw these directly.
 */

enum class PdfMode { TEXT, PDF }

/** One row of the text view. */
sealed interface TextRow {
    val page: Int

    data class Marker(override val page: Int) : TextRow
    data class Body(override val page: Int, val block: Int, val item: TextBlock) : TextRow
    data class Link(override val page: Int, val figure: Figure) : TextRow
    /** The reading stopped at a limit after [page] (from 0), the last page read; the PDF has [total]. */
    data class End(override val page: Int, val total: Int) : TextRow
}

/**
 * The rows of the text view. A table shows as a "Table on page n" line in its place; a page
 * with a figure ends with a "Figure on page n" line. A PDF of one page has no marker. When
 * the PDF has more pages ([total]) than were read, a "Text stops at page n" line ends the text.
 */
fun textRows(pages: List<PdfTextPage>, total: Int = pages.size): List<TextRow> = buildList {
    val markers = pages.size > 1
    for (page in pages) {
        if (markers) add(TextRow.Marker(page.index))
        var tableShown = false
        page.blocks.forEachIndexed { index, block ->
            if (block.kind == BlockKind.TABLE) {
                if (!tableShown) add(TextRow.Link(page.index, Figure.TABLE))
                tableShown = true
            } else {
                add(TextRow.Body(page.index, index, block))
            }
        }
        if (page.figure == Figure.FIGURE) add(TextRow.Link(page.index, Figure.FIGURE))
    }
    val last = pages.lastOrNull()
    if (last != null && total > pages.size) add(TextRow.End(last.index, total))
}

internal fun TextRow.key(): String = when (this) {
    is TextRow.Marker -> "m$page"
    is TextRow.Body -> "b$page.$block"
    is TextRow.Link -> "l$page.${figure.name}"
    is TextRow.End -> "e$page"
}

/** Top bar of both views: close, file name, the Text / PDF switch, search. Dark in the PDF view. */
@Composable
fun PdfTopBar(
    title: String,
    mode: PdfMode,
    showSwitch: Boolean,
    onMode: (PdfMode) -> Unit,
    onClose: () -> Unit,
    onSearch: (() -> Unit)?,
    background: Color,
    content: Color,
) {
    Row(
        Modifier.fillMaxWidth().background(background).statusBarsPadding().padding(end = Space.xs).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("pdf_close")) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pdf_close), tint = content)
        }
        Text(
            title,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showSwitch) ModeSwitch(mode, onMode, dark = mode == PdfMode.PDF)
        if (onSearch != null) {
            IconButton(onClick = onSearch, modifier = Modifier.testTag("pdf_search")) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.pdf_search), tint = content)
            }
        }
    }
}

/** "Text" and "PDF" in a pill; the active segment in ink on paper, inverted in the dark PDF bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitch(mode: PdfMode, onMode: (PdfMode) -> Unit, dark: Boolean) {
    val cs = MaterialTheme.colorScheme
    val light = Color(0xFFF2F2F2)
    val colors = if (dark) {
        SegmentedButtonDefaults.colors(
            activeContainerColor = light, activeContentColor = Color(0xFF16181D), activeBorderColor = light,
            inactiveContainerColor = Color.Transparent, inactiveContentColor = light, inactiveBorderColor = light.copy(alpha = 0.6f),
        )
    } else {
        SegmentedButtonDefaults.colors(
            activeContainerColor = cs.onSurface, activeContentColor = cs.surface, activeBorderColor = cs.onSurface,
            inactiveContainerColor = cs.surface, inactiveContentColor = cs.onSurface, inactiveBorderColor = cs.outline,
        )
    }
    SingleChoiceSegmentedButtonRow(Modifier.width(132.dp).padding(horizontal = Space.xs)) {
        PdfMode.entries.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index, PdfMode.entries.size),
                colors = colors,
                icon = {},
                modifier = Modifier.testTag(if (m == PdfMode.TEXT) "mode_text" else "mode_pdf"),
            ) {
                Text(stringResource(if (m == PdfMode.TEXT) R.string.pdf_view_text else R.string.pdf_view_pdf), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Search in the open PDF (theme-compose.md 10.10), under the top bar: a pill field, "1 of 2"
 * with previous and next, then the results panel.
 */
@Composable
fun PdfSearchField(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    requestFocus: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The keyboard opens when the reader asks to search, not when a result is opened with its
    // word already in the field; going from result to result closes it (theme-compose.md 10.10).
    LaunchedEffect(Unit) { if (requestFocus) runCatching { focus.requestFocus() } }
    fun browse(step: () -> Unit) {
        keyboard?.hide()
        focusManager.clearFocus()
        step()
    }
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(start = Space.screen, end = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 44.dp).background(cs.surfaceVariant, CircleShape).padding(horizontal = Space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.pdf_search), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { browse(onNext) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("pdf_search_field"),
            )
        }
        if (query.trim().length >= 2 && count > 0) {
            Text(
                stringResource(R.string.pdf_search_count, current + 1, count),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.s).testTag("pdf_search_count"),
            )
        }
        IconButton(onClick = { browse(onPrevious) }, enabled = count > 0) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.pdf_search_previous))
        }
        IconButton(onClick = { browse(onNext) }, enabled = count > 0) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.pdf_search_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pdf_search_close))
        }
    }
}

/**
 * One result in the panel or in the Library: "PAGE 3", then the passage around the word, the
 * word marked as in the text (primaryContainer, underlined).
 */
@Composable
fun PageSnippet(page: Int, snippet: String, onClick: () -> Unit, modifier: Modifier = Modifier, terms: List<String> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        append(snippet)
        PdfSearch.ranges(snippet, terms).forEach { r ->
            addStyle(SpanStyle(background = cs.primaryContainer, color = cs.onPrimaryContainer, textDecoration = TextDecoration.Underline), r.first, r.last + 1)
        }
    }
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp)
            .padding(horizontal = Space.screen, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.pdf_page_marker, page + 1).uppercase(),
            style = TextStyle(fontFamily = PlexCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
            color = cs.onSurface,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The results panel: "7 results for "measles"", the first six results, then "1 more in the
 * text" (a tap shows them all); 300 dp high at most, a rule below.
 */
@Composable
fun PdfSearchResults(query: String, results: List<Pair<TextMatch, String>>, onOpen: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var all by remember(query) { mutableStateOf(false) }
    val q = query.trim()
    if (q.length < 2) return
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            Text(
                if (results.isEmpty()) {
                    stringResource(R.string.pdf_search_no_result, q)
                } else {
                    pluralStringResource(R.plurals.pdf_search_results, results.size, results.size, q)
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs).testTag("pdf_search_summary"),
            )
            val shown = if (all) results else results.take(SHOWN_RESULTS)
            shown.forEachIndexed { index, (match, snippet) ->
                PageSnippet(match.page, snippet, onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onOpen(index)
                }, Modifier.testTag("pdf_search_result"), terms = listOf(q))
            }
            val more = results.size - shown.size
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.pdf_search_more, more, more),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    modifier = Modifier.fillMaxWidth().clickable { all = true }.heightIn(min = 48.dp)
                        .padding(horizontal = Space.screen, vertical = Space.m),
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
    }
}

private const val SHOWN_RESULTS = 6

/** What the text of a block carries: saved highlights, search matches, the current match. */
class BlockMarks(
    val highlights: List<IntRange> = emptyList(),
    val matches: List<IntRange> = emptyList(),
    val current: IntRange? = null,
) {
    companion object {
        val NONE = BlockMarks()
    }
}

/**
 * The text of the PDF: banner ([showBanner], the first opening only), then pages. [marks] gives what to draw on a block (page,
 * block). With [onSelect], text can be selected as in a report (SelectableText) and reports
 * page, block and range; [clearSignal] clears the selection. [flash] outlines the block a
 * highlight opened on, for a moment.
 */
@Composable
fun PdfTextContent(
    rows: List<TextRow>,
    listState: LazyListState,
    onOpenPdf: (page: Int) -> Unit,
    modifier: Modifier = Modifier,
    marks: (page: Int, block: Int) -> BlockMarks = { _, _ -> BlockMarks.NONE },
    onSelect: ((page: Int, block: Int, start: Int, end: Int) -> Unit)? = null,
    clearSignal: Int = 0,
    flash: Pair<Int, Int>? = null,
    showBanner: Boolean = true,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().testTag("pdf_text"), contentPadding = PaddingValues(bottom = Space.xl)) {
        if (showBanner) {
            item(key = "banner") {
                Banner(stringResource(R.string.pdf_text_banner), stringResource(R.string.pdf_open_pdf), { onOpenPdf(-1) })
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key() }) { _, row ->
            when (row) {
                is TextRow.Marker -> PageMarker(row.page)
                is TextRow.Link -> FigureLink(row.page, row.figure, onClick = { onOpenPdf(row.page) })
                is TextRow.End -> TextStops(row.page, row.total, onClick = { onOpenPdf(row.page + 1) })
                is TextRow.Body -> {
                    val select = onSelect
                    PdfBlockText(
                        row.item,
                        marks(row.page, row.block),
                        onSelect = if (select != null) { { start, end -> select(row.page, row.block, start, end) } } else null,
                        clearSignal = clearSignal,
                        flash = flash == row.page to row.block,
                    )
                }
            }
        }
    }
}

/** "PAGE 2" in condensed capitals, ink 2, then a rule to the edge. */
@Composable
fun PageMarker(page: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.section, bottom = Space.s)
            .testTag("page_marker_${page + 1}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.pdf_page_marker, page + 1).uppercase(),
            style = TextStyle(fontFamily = PlexCondensed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(Space.s))
        HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
    }
}

/** A bordered line that opens the PDF on the page of a table or a figure. */
@Composable
fun FigureLink(page: Int, figure: Figure, onClick: () -> Unit) {
    val label = stringResource(if (figure == Figure.TABLE) R.string.pdf_figure_table else R.string.pdf_figure_figure, page + 1)
    PdfLinkRow(if (figure == Figure.TABLE) Icons.Rounded.TableChart else Icons.Rounded.Image, label, "view_in_pdf_${page + 1}", onClick)
}

/** "Text stops at page n of m" after the last page read ([page], from 0); opens the next page. */
@Composable
fun TextStops(page: Int, total: Int, onClick: () -> Unit) {
    PdfLinkRow(Icons.Rounded.Description, stringResource(R.string.pdf_text_stops, page + 1, total), "pdf_text_stops", onClick)
}

/** A line of the text view that sends to the PDF: icon, label, "View in PDF". */
@Composable
private fun PdfLinkRow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val action = stringResource(R.string.pdf_view_in_pdf)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).padding(horizontal = Space.screen, vertical = Space.s)
            .semantics(mergeDescendants = true) { contentDescription = "$label · $action" }
            .testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.m).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, modifier = Modifier.weight(1f).padding(horizontal = Space.m))
            Text(action, style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
    }
}

/** A heading or a paragraph, in the report's body styles (follows "Aa"); selectable with [onSelect]. */
@Composable
fun PdfBlockText(
    block: TextBlock,
    marks: BlockMarks,
    modifier: Modifier = Modifier,
    onSelect: ((start: Int, end: Int) -> Unit)? = null,
    clearSignal: Int = 0,
    flash: Boolean = false,
) {
    val body = MaterialTheme.typography.bodyLarge
    val step = LocalTextStep.current
    val heading = block.kind == BlockKind.HEADING
    // Body subheads of a report: the text font in bold, body + 2 sp unless long (ReportScreen).
    val style = when {
        !heading -> body
        block.text.length <= 140 -> body.copy(fontWeight = FontWeight.Bold, fontSize = (step.body.value + 2).sp, lineHeight = (step.line.value + 2).sp)
        else -> body.copy(fontWeight = FontWeight.Bold)
    }
    val cs = MaterialTheme.colorScheme
    val outer = modifier.widthIn(max = 600.dp).fillMaxWidth()
        .padding(start = Space.screen, end = Space.screen, top = if (heading) Space.m else 6.dp, bottom = 6.dp)
        // A highlight or a result just opened: a halo for a moment (theme-compose.md 5.9, 10.10).
        .then(if (flash) Modifier.background(cs.primaryContainer, MaterialTheme.shapes.extraSmall) else Modifier)
        .then(if (heading) Modifier.semantics { heading() } else Modifier)
    val text = marked(block.text, marks)
    if (onSelect != null) {
        SelectableText(text, style.copy(color = cs.onSurface), onSelect, outer.testTag("pdf_block"), clearSignal)
    } else {
        Text(text, style = style, color = cs.onSurface, modifier = outer)
    }
}

@Composable
private fun marked(text: String, marks: BlockMarks): AnnotatedString {
    if (marks === BlockMarks.NONE) return AnnotatedString(text)
    val highlight = LocalRelaisColors.current.highlight
    val cs = MaterialTheme.colorScheme
    return buildAnnotatedString {
        append(text)
        fun mark(range: IntRange, style: SpanStyle) {
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
        marks.highlights.forEach { mark(it, SpanStyle(background = highlight)) }
        // Search results: never the yellow of highlights (theme-compose.md 10.10).
        marks.matches.forEach { mark(it, SpanStyle(background = cs.primaryContainer, color = cs.onPrimaryContainer, textDecoration = TextDecoration.Underline)) }
        marks.current?.let { mark(it, SpanStyle(background = cs.primary, color = cs.onPrimary)) }
    }
}

/**
 * Bottom bar of the text view: "Aa" and the page being read. The file's size left it in v0.4: it
 * is of no use while reading (revue-densite-v0.3.md, change 8).
 */
@Composable
fun PdfTextBottomBar(page: Int, pageCount: Int, onTextSize: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface).navigationBarsPadding()) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            val description = stringResource(R.string.text_size)
            TextButton(onClick = onTextSize, modifier = Modifier.semantics { contentDescription = description }.testTag("pdf_text_size")) {
                Text(stringResource(R.string.pdf_text_size_short), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            }
            Text(
                if (pageCount > 0) stringResource(R.string.pdf_page, page + 1, pageCount) else "",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f).padding(start = Space.s),
            )
        }
    }
}

/** While the text is being read: progress, and the PDF one tap away. */
@Composable
fun PdfTextLoading(progress: Float?, onOpenPdf: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress != null && progress > 0f) {
            CircularProgressIndicator(progress = { progress })
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(Space.l))
        Text(stringResource(R.string.pdf_text_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenPdf) { Text(stringResource(R.string.pdf_open_pdf)) }
    }
}
