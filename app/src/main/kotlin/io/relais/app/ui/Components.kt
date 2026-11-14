// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.relais.api.Display
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.LoadError
import io.relais.app.data.ReportEntity
import kotlinx.coroutines.launch

/** Whether photos and report images load now ("Load images" and the network); true in tests. */
val LocalImagesAllowed = androidx.compose.runtime.compositionLocalOf { true }

/** The phone has no network: rows of reports that cannot be read show dimmed. */
val LocalOffline = androidx.compose.runtime.compositionLocalOf { false }

/** Addresses of the PDFs on the phone, for the rows dimmed offline. */
val LocalPdfsOnPhone = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/** Top bar with an optional back arrow and the settings icon, as on every tab (M5 to M11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    /** Replaces [title] in the top bar: the Re.L logotype on Today. */
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { titleContent?.invoke() ?: Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    actions()
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                        }
                    }
                },
            )
        },
        snackbarHost = { snackbarHostState?.let { SwipeSnackbarHost(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

/**
 * "Re.L" in Literata Bold 25 sp, the dot alone in the red of the icon (theme-compose.md 11.2,
 * v0.3). The red dot exists only here and in the icon; elsewhere the name is plain text.
 */
@Composable
fun Logotype(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = buildAnnotatedString {
            append("Re")
            withStyle(SpanStyle(color = LocalRelaisColors.current.brand)) { append(".") }
            append("L")
        },
        style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-0.5).sp),
        color = cs.onSurface,
        modifier = modifier.semantics { contentDescription = "Re.L" },
    )
}

/** Section title (theme-compose.md 5.5): 2 dp ink rule, condensed capitals, optional note on the right. */
@Composable
fun SectionHeader(
    text: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Test tag of the action, for the Maestro flows. */
    actionTag: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.section, bottom = Space.xs)) {
        HorizontalDivider(thickness = SectionRule, color = cs.onSurface)
        // theme-compose.md 10.1: a title and note longer than 44 characters put the note below.
        val noteBelow = subtitle != null && text.length + subtitle.length > 44
        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.uppercase(),
                style = SectionTitleStyle.copy(fontSize = LocalDisplaySize.current.section, lineHeight = LocalDisplaySize.current.sectionLine),
                color = cs.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (subtitle != null && !noteBelow) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = Space.s),
                    modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier,
                ) { Text(actionLabel) }
            }
        }
        if (subtitle != null && noteBelow) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * Opens a link from ReliefWeb's data in the browser or the mail app, and nothing else (audit S1,
 * 9.6): https, http and mailto only; a link the phone cannot open is ignored.
 */
fun safeOpen(uriHandler: androidx.compose.ui.platform.UriHandler, url: String?): Boolean {
    val link = url?.trim().orEmpty()
    val scheme = link.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme !in SAFE_SCHEMES) return false
    return runCatching { uriHandler.openUri(link) }.isSuccess
}

private val SAFE_SCHEMES = setOf("https", "http", "mailto")

/** A message with an optional action, used for the common states of ecrans.md 0.3. */
@Composable
fun Banner(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * A state message never hides the content and never blocks a touch (theme-compose.md 13.3):
 * - offline with saved content: one discreet line, "Offline · saved at 08:02", no background,
 *   no button, gone when the network is back;
 * - an error with content on screen: a long snackbar with a close button (and "Retry" or
 *   "Settings"), when the screen gives its [snackbar];
 * - nothing else to show ([persistent]): one compact line with its action, closable.
 */
@Composable
fun LoadErrorBanner(
    error: LoadError?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    cachedAt: Long? = null,
    persistent: Boolean = false,
    snackbar: SnackbarHostState? = null,
) {
    if (error == null) return
    if (error == LoadError.OFFLINE && cachedAt != null && !persistent) {
        OfflineLine(cachedAt)
        return
    }
    val retry = stringResource(R.string.action_retry)
    val settings = stringResource(R.string.action_settings)
    if (snackbar != null && !persistent) {
        val message = when (error) {
            LoadError.OFFLINE -> stringResource(R.string.state_offline_empty)
            LoadError.DENIED -> stringResource(R.string.state_denied_short)
            LoadError.RATE_LIMITED -> stringResource(R.string.state_rate_limited)
            LoadError.NO_ACCESS -> stringResource(R.string.state_no_access)
            LoadError.OTHER -> if (cachedAt != null) stringResource(R.string.state_couldnt_update, Display.time(cachedAt)) else stringResource(R.string.state_error)
        }
        val action: Pair<String, () -> Unit>? = when (error) {
            LoadError.DENIED, LoadError.NO_ACCESS -> settings to onOpenSettings
            LoadError.OTHER, LoadError.OFFLINE -> retry to onRetry
            LoadError.RATE_LIMITED -> null
        }
        val currentAction by androidx.compose.runtime.rememberUpdatedState(action)
        androidx.compose.runtime.LaunchedEffect(error, cachedAt) {
            val result = snackbar.showSnackbar(message, currentAction?.first, withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) currentAction?.second?.invoke()
        }
        return
    }
    var closed by remember(error, cachedAt) { mutableStateOf(false) }
    val passing = !persistent && (error == LoadError.OTHER || error == LoadError.RATE_LIMITED)
    androidx.compose.runtime.LaunchedEffect(error, passing) {
        if (passing) {
            kotlinx.coroutines.delay(STATUS_SECONDS * 1000L)
            closed = true
        }
    }
    if (closed) return
    val tryAgain = stringResource(R.string.action_try_again)
    val openSettings = stringResource(R.string.action_open_settings)
    when (error) {
        LoadError.OFFLINE -> if (cachedAt != null) {
            StatusLine(stringResource(R.string.state_offline_cached, Display.time(cachedAt)), onClose = { closed = true }, onTap = onRetry, tapLabel = tryAgain)
        } else {
            StatusLine(stringResource(R.string.state_offline_empty), tryAgain, onRetry, onClose = { closed = true })
        }
        LoadError.DENIED -> StatusLine(stringResource(R.string.state_denied), openSettings, onOpenSettings, onClose = { closed = true })
        LoadError.RATE_LIMITED -> StatusLine(stringResource(R.string.state_rate_limited), onClose = { closed = true })
        LoadError.NO_ACCESS -> StatusLine(stringResource(R.string.state_no_access), openSettings, onOpenSettings, onClose = { closed = true })
        LoadError.OTHER -> StatusLine(stringResource(R.string.state_error), tryAgain, onRetry, onClose = { closed = true })
    }
}

/**
 * One snackbar at a time, which a sideways swipe sends away (theme-compose.md 13.3, v0.3); the
 * close button stays on long messages. Swiping does not run the action (Dismissed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        // Each snackbar starts from a fresh swipe state, or the next one would arrive swiped away.
        androidx.compose.runtime.key(data) {
            val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
            androidx.compose.runtime.LaunchedEffect(swipe.currentValue) {
                if (swipe.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled) data.dismiss()
            }
            androidx.compose.material3.SwipeToDismissBox(state = swipe, backgroundContent = {}) {
                androidx.compose.material3.Snackbar(data)
            }
        }
    }
}

/**
 * Offline for a while, content saved (13.3): 28 dp, cloud_off of 14 dp, ink 2, no background.
 * A sideways swipe hides it until the network comes back; TalkBack has "Dismiss".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineLine(cachedAt: Long) {
    val cs = MaterialTheme.colorScheme
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
    androidx.compose.runtime.LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled) hidden = true
    }
    val dismiss = stringResource(R.string.action_dismiss)
    androidx.compose.material3.SwipeToDismissBox(state = swipe, backgroundContent = {}) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface).heightIn(min = 28.dp).padding(horizontal = Space.screen)
                .semantics { customActions = listOf(CustomAccessibilityAction(dismiss) { hidden = true; true }) }
                .testTag("offline_line"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                stringResource(R.string.state_offline_line, Display.time(cachedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** Seconds a passing error stays before it leaves on its own. */
const val STATUS_SECONDS = 6

/** One compact line on surfaceVariant: the message, an optional action, and a close button. */
@Composable
fun StatusLine(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: () -> Unit,
    onTap: (() -> Unit)? = null,
    tapLabel: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).heightIn(min = 40.dp)
            .then(if (onTap != null) Modifier.clickable(onClickLabel = tapLabel, onClick = onTap) else Modifier)
            .padding(start = Space.screen).testTag("status_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = Space.s)) { Text(actionLabel) }
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("status_close")) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** First line of a card: "Sudan +7 · Situation report". */
fun contextLine(report: ReportEntity): String {
    val country = Display.countryContext(report.primaryCountryName, report.otherCountryCount)
    val format = Display.shortFormat(report.formatId, report.formatName)
    return listOf(country, format).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Source line of a card: "UNHCR · Published 10 Aug, posted 6 Nov". */
fun sourceLine(report: ReportEntity): String {
    val date = Display.cardDate(report.dateCreated, report.dateOriginal)
    return listOf(report.sourceShort, date).filter { it.isNotBlank() }.joinToString(" · ")
}

fun pdfLabel(report: ReportEntity): String? = report.pdf?.size?.let { size -> Display.size(size)?.let { "PDF $it" } }

/** Kicker (theme-compose.md 5.1): FORMAT in bold ink, ORGANIZATION and TIME in ink 2, dot if new. */
@Composable
fun Kicker(report: ReportEntity, isNew: Boolean, onSourcePage: Long? = null, muted: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val newLabel = stringResource(R.string.label_new)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (isNew) {
            Box(Modifier.size(7.dp).background(cs.primary, CircleShape).semantics { contentDescription = newLabel })
        }
        val format = Display.shortFormat(report.formatId, report.formatName)
        if (format.isNotBlank()) {
            Text(format.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (muted) cs.onSurfaceVariant else cs.onSurface, maxLines = 1)
        }
        Text(
            listOf(kickerSource(report, onSourcePage), Display.kickerTime(report.dateCreated)).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The organization in a kicker: on its own page (theme-compose.md 13.1) it is not repeated, and
 * a joint publication says with whom ("WITH FAO +4").
 */
@Composable
private fun kickerSource(report: ReportEntity, onSourcePage: Long?): String {
    if (onSourcePage == null) return report.sourceShort
    val others = report.sources.filter { it.id != onSourcePage }
    if (others.isEmpty()) return ""
    val first = others.first().shortname.ifBlank { others.first().name }
    return stringResource(R.string.kicker_with, first) + if (others.size > 1) " +${others.size - 1}" else ""
}

/**
 * The country of a row in a feed that mixes countries ("Sudan +7"), alone on its line; nothing
 * on the country's own page (theme-compose.md 12.7). The PDF's pages and size have their own
 * line (PdfFactsLine).
 */
fun metaLine(report: ReportEntity, inCountry: Boolean = false): String =
    if (inCountry) "" else Display.countryContext(report.primaryCountryName, report.otherCountryCount)

/**
 * Document row of the dense list (theme-compose.md 5.2 and 12.7): kicker, title on 3 lines at
 * most, an old period, the country in mixed feeds; a read row has its title in ink 2. [menu]
 * opens on a long press (Save, Share), and as "More options" for TalkBack. [pdfPreview] shows
 * the first lines of a PDF read on the phone: Library and Search only.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DocumentRow(
    report: ReportEntity,
    onClick: () -> Unit,
    isNew: Boolean = false,
    isRead: Boolean = false,
    status: String? = null,
    title: String = report.title,
    inCountry: Boolean = false,
    pdfPreview: Boolean = false,
    /** On an organization's page: its name is not repeated in the kicker (13.1). */
    onSourcePage: Long? = null,
    /** Test tag of the row, for the Maestro flows; doc_<id> by default. */
    tag: String = "doc_${report.id}",
    /** Shown under the row, above its hairline: "Also in French" for a language twin. */
    extra: (@Composable () -> Unit)? = null,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.action_more)
    // Offline, a report with neither its text nor a PDF on the phone: the secondary colour, no
    // label; a tap still opens its notice (lot 3, C).
    val dimmed = io.relais.app.data.unreadableOffline(report, LocalOffline.current, LocalPdfsOnPhone.current)
    Column {
        Box {
            Column(
                Modifier.fillMaxWidth().testTag(tag)
                    .combinedClickable(onClick = onClick, onLongClick = if (menu != null) ({ menuOpen = true }) else null)
                    .semantics {
                        if (menu != null) customActions = listOf(CustomAccessibilityAction(moreLabel) { menuOpen = true; true })
                    }
                    .padding(horizontal = Space.screen, vertical = Space.rowVertical),
                verticalArrangement = Arrangement.spacedBy(Space.kickerGap),
            ) {
                Kicker(report, isNew && !isRead, onSourcePage, muted = dimmed)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isRead || dimmed) FontWeight.SemiBold else FontWeight.Bold),
                    color = if (isRead || dimmed) cs.onSurfaceVariant else cs.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // An old period, the only date besides the kicker's (revue-lecteur-v0.2.md, defect 1).
                Display.oldCoverage(report.title, report.formatId, report.formatName)?.let { old ->
                    Text(old, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
                }
                // "Please refer to the attached file": the first lines of the PDF read on the phone (10.9).
                if (pdfPreview) io.relais.app.pdf.pdfPreview(report)?.let { io.relais.app.pdf.PdfPreviewLine(it) }
                val meta = metaLine(report, inCountry)
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                // "PDF · 12 pages · 2.3 MB": what the API or the file on the phone says, nothing when unknown.
                io.relais.app.pdf.PdfFactsLine(report)
                // Ink 2, in Saved and History alike (theme-compose.md 5.2): blue is for actions,
                // unread marks and the active filter.
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (menu != null) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
        }
        extra?.invoke()
        HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
    }
}

@Composable
fun OverflowMenu(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/**
 * Pull to refresh (Material 3) around a list or a pager. The spinner also shows while the
 * screen loads on its own, in place of a progress bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = modifier.testTag("pull_refresh"),
        content = content,
    )
}

/**
 * A swipe between tabs: the pager is the source of truth for the screen, and [onSettled]
 * tells the view model which page the reader stopped on. A tap on a tab scrolls the pager.
 */
@Composable
fun OnPageSettled(pager: androidx.compose.foundation.pager.PagerState, onSettled: (Int) -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(onSettled)
    androidx.compose.runtime.LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { current(it) }
    }
}

/** The report at the reader's text size: body and title scale, lists and interface do not. */
@Composable
fun ReadingTypography(size: Int, content: @Composable () -> Unit) {
    val step = TextStep.of(size)
    val t = MaterialTheme.typography
    MaterialTheme(
        typography = t.copy(
            bodyLarge = t.bodyLarge.copy(fontSize = step.body, lineHeight = step.line),
            headlineSmall = t.headlineSmall.copy(fontSize = step.title, lineHeight = step.titleLine),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalTextStep provides step, content = content)
    }
}

/**
 * "Aa" (theme-compose.md 9.2 and 12.6). With [onDisplay], "Display" first (Compact, Default,
 * Large, for the whole app; [preview] adds two list rows at that size), then "Report text".
 * The report text: five "A" of growing size, the step's name, and a preview of [sample] (the
 * paragraph being read). Reset brings back Compact and Small.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSizeControl(
    size: Int,
    sample: String?,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDisplay: ((DisplaySize) -> Unit)? = null,
    preview: Boolean = false,
) {
    val step = TextStep.of(size)
    val display = LocalDisplaySize.current
    val cs = MaterialTheme.colorScheme
    val default = io.relais.app.data.DEFAULT_READING_SIZE
    val segmented = SegmentedButtonDefaults.colors(activeContainerColor = cs.onSurface, activeContentColor = cs.surface)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (onDisplay != null) R.string.display_title else R.string.text_size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onDisplay?.invoke(DisplaySize.Compact); onChange(default) },
                enabled = size != default || (onDisplay != null && display != DisplaySize.Compact),
            ) {
                Text(stringResource(R.string.text_size_reset))
            }
        }
        if (onDisplay != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("display_sizes")) {
                DisplaySize.entries.forEachIndexed { index, d ->
                    SegmentedButton(
                        selected = d == display,
                        onClick = { onDisplay(d) },
                        shape = SegmentedButtonDefaults.itemShape(index, DisplaySize.entries.size),
                        colors = segmented,
                        icon = {},
                        modifier = Modifier.testTag("display_" + d.name.lowercase()),
                    ) {
                        Text(stringResource(d.label), maxLines = 1)
                    }
                }
            }
            if (preview) DisplayPreview()
            Text(stringResource(R.string.report_text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.s))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("text_size_steps")) {
            TextStep.entries.forEachIndexed { index, s ->
                val description = stringResource(s.label)
                SegmentedButton(
                    selected = index == step.ordinal,
                    onClick = { onChange(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, TextStep.entries.size),
                    colors = segmented,
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text("A", fontFamily = Literata, fontSize = s.button)
                }
            }
        }
        Text(
            stringResource(R.string.text_size_current, stringResource(step.label), step.body.value.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Text(
            sample?.takeIf { it.isNotBlank() } ?: stringResource(R.string.text_size_sample),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = step.body, lineHeight = step.line),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .background(cs.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(Space.m),
        )
    }
}

/** Two list rows at the chosen display level, under "Display" in Settings. */
@Composable
private fun DisplayPreview() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
        listOf(R.string.display_preview_1, R.string.display_preview_2).forEachIndexed { i, title ->
            if (i > 0) HorizontalDivider(thickness = Hairline, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = Space.screen))
            Column(Modifier.padding(horizontal = Space.screen, vertical = Space.rowVertical), verticalArrangement = Arrangement.spacedBy(Space.kickerGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.display_preview_format).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cs.onSurface)
                    Text(stringResource(R.string.display_preview_source).uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            }
        }
    }
}

/** The "Aa" control in a sheet: Display, then the report text, which changes size behind as the reader taps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSizeSheet(size: Int, sample: String?, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val settings = androidx.compose.ui.platform.LocalContext.current.container.settings
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        TextSizeControl(
            size, sample, onChange,
            Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xl).navigationBarsPadding(),
            onDisplay = { d -> scope.launch { settings.setDisplay(d.name, d.reportStep.ordinal) } },
        )
    }
}

/**
 * What is new for a followed country: "6 new" in the accent colour, else "Up to date" in ink 2.
 * Every document counts, language twins once (decision of 8/11). One text, so that a screen
 * reader and the journeys read "Sudan · 6 new".
 */
@Composable
fun newCountLine(prefix: String?, row: io.relais.app.data.FollowedRow): androidx.compose.ui.text.AnnotatedString {
    val cs = MaterialTheme.colorScheme
    val accent = androidx.compose.ui.text.SpanStyle(color = cs.primary, fontWeight = FontWeight.SemiBold)
    val quiet = androidx.compose.ui.text.SpanStyle(color = cs.onSurfaceVariant)
    val newText = stringResource(R.string.following_new, row.newCount)
    val upToDate = stringResource(R.string.following_up_to_date)
    return androidx.compose.ui.text.buildAnnotatedString {
        if (prefix != null) append("$prefix · ")
        if (row.newCount > 0) withStyle(accent) { append(newText) } else withStyle(quiet) { append(upToDate) }
    }
}

/**
 * A pull within the valve's pullMinutes (5 by default) of the last check calls nothing: the spinner closes and a
 * snackbar says "Up to date. Last checked 08:02." (theme-compose.md 9.4). Otherwise [refresh] runs.
 */
@Composable
fun rememberPull(lastChecked: Long?, snackbar: SnackbarHostState, refresh: () -> Unit): () -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val config = androidx.compose.ui.platform.LocalContext.current.container.remoteConfig
    val message = stringResource(R.string.refresh_up_to_date, lastChecked?.let { Display.time(it) }.orEmpty())
    val currentRefresh by androidx.compose.runtime.rememberUpdatedState(refresh)
    val currentLast by androidx.compose.runtime.rememberUpdatedState(lastChecked)
    val currentMessage by androidx.compose.runtime.rememberUpdatedState(message)
    return remember(snackbar) {
        {
            val last = currentLast
            // A pull reaches the network unless the last check was under 5 minutes ago (13/11:
            // with 30 minutes, a pull often did nothing and the founder saw the same headlines).
            if (last != null && last > 0 && System.currentTimeMillis() - last < config.current.pullMillis) {
                scope.launch { snackbar.showSnackbar(currentMessage) }
            } else {
                currentRefresh()
            }
        }
    }
}
