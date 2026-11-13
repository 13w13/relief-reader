// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.relais.app.R
import io.relais.app.data.Citation
import io.relais.app.data.CitationSource

// The selection bar, shared by the report body and the Text view of a PDF (Tech PDF): select a
// passage in a SelectableText, the host keeps the Passage and shows SelectionBar until an
// action or a dismissal, then bumps the clear signal of its texts.

/**
 * A passage selected in a text: the block, the character range inside the block's plain text
 * (the base of HighlightEntity's offsets) and, for a PDF, its page (from 1).
 */
data class Passage(val blockIndex: Int, val start: Int, val end: Int, val text: String, val pdfPage: Int? = null)

/**
 * Text that can be selected like a page, with exact offsets: a read-only text field. It reports
 * each change of selection with [onSelect] (start == end when the selection is gone). Increment
 * [clearSignal] to clear the selection after an action. Android's floating menu is hidden:
 * SelectionBar is the only menu. Taps on links open them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableText(
    text: AnnotatedString,
    style: TextStyle,
    onSelect: (start: Int, end: Int) -> Unit,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
) {
    var selection by remember(text) { mutableStateOf(TextRange.Zero) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val uriHandler = LocalUriHandler.current
    val color = style.color.takeOrElse { LocalContentColor.current }
    LaunchedEffect(clearSignal) {
        if (!selection.collapsed) selection = TextRange(selection.max)
    }
    CompositionLocalProvider(LocalTextToolbar provides HiddenTextToolbar) {
        BasicTextField(
            value = TextFieldValue(text, selection),
            onValueChange = { value ->
                if (value.selection != selection) {
                    selection = value.selection
                    onSelect(value.selection.min, value.selection.max)
                }
            },
            readOnly = true,
            textStyle = style.copy(color = color),
            cursorBrush = SolidColor(Color.Transparent),
            onTextLayout = { layout = it },
            modifier = modifier
                // Compose 1.9 shows Android's own menu (Copy, Select all, Define) through its new
                // context menu, past LocalTextToolbar: no item, no menu; SelectionBar is the menu.
                .filterTextContextMenuComponents { false }
                .onFocusChanged { focus ->
                    if (!focus.isFocused && !selection.collapsed) {
                        selection = TextRange(selection.max)
                        onSelect(selection.max, selection.max)
                    }
                }
                .linkTaps(text, { layout }, { url -> safeOpen(uriHandler, url) }),
        )
    }
}

/** A short tap on a link opens it; the text field's own gestures (selection) still run. */
private fun Modifier.linkTaps(text: AnnotatedString, layout: () -> TextLayoutResult?, open: (String) -> Unit): Modifier {
    if (text.getLinkAnnotations(0, text.length).isEmpty()) return this
    return pointerInput(text) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            val quick = up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
            val still = (up.position - down.position).getDistance() < viewConfiguration.touchSlop
            if (!quick || !still) return@awaitEachGesture
            val offset = layout()?.getOffsetForPosition(up.position) ?: return@awaitEachGesture
            val url = text.getLinkAnnotations(offset, offset + 1)
                .firstNotNullOfOrNull { (it.item as? LinkAnnotation.Url)?.url }
            if (url != null) open(url)
        }
    }
}

private object HiddenTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}

/**
 * The selection bar (theme-compose.md 10.13), over the bottom of the screen while a passage is
 * selected: Highlight (when [onHighlight] is given), Copy with citation, Translate (only when an
 * app of the phone can translate), and Close. Copy and Translate are handled here; [onCopied]
 * lets the host confirm the copy ("Copied with citation.").
 */
@Composable
fun SelectionBar(
    passage: Passage,
    source: CitationSource,
    onHighlight: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    val canTranslate = remember { translateIntent(context, "Relais") != null }
    val clipLabel = stringResource(R.string.selection_clip_label)
    // The same dark bar in both themes.
    Surface(
        modifier = modifier.testTag("selection_bar"),
        shape = RoundedCornerShape(14.dp),
        color = SELECTION_BAR,
        contentColor = Color.White,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onHighlight != null) {
                BarAction(
                    Icons.Rounded.BorderColor, R.string.highlight_action, "selection_highlight", weight = 1f,
                    background = LocalRelaisColors.current.highlight, content = SELECTION_BAR, onClick = onHighlight,
                )
            }
            BarAction(Icons.Rounded.FormatQuote, R.string.selection_copy_citation, "selection_cite", weight = 1.3f) {
                copyText(context, clipLabel, Citation.format(passage.text, source, passage.pdfPage))
                onCopied()
                onDismiss()
            }
            if (canTranslate) {
                BarAction(Icons.Rounded.Translate, R.string.selection_translate, "selection_translate", weight = 1f) {
                    val intent = translateIntent(context, passage.text) ?: return@BarAction
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // The translation app went away since the check: nothing to do.
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).testTag("selection_close")) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.selection_close))
            }
        }
    }
}

private val SELECTION_BAR = Color(0xFF16181D)

/** Icon above its label; a long label wraps to two lines, never an ellipsis. */
@Composable
private fun RowScope.BarAction(
    icon: ImageVector,
    label: Int,
    tag: String,
    weight: Float,
    background: Color = Color.Transparent,
    content: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(weight)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold),
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

internal fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

private val TRANSLATORS = setOf("com.google.android.apps.translate", "com.microsoft.translator", "com.deepl.mobiletranslator")
private val TRANSLATE_LABELS = listOf("translat", "tradu", "übersetz", "перев", "ترجم")

/**
 * An intent that hands [text] to the phone's translation app, or null when no app answers.
 * Android's "translate" action first, then a "process text" entry of a translation app
 * (Google Translate's "Translate"). Relais adds no service of its own.
 */
fun translateIntent(context: Context, text: String): Intent? {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val translate = Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text)
        if (translate.resolveActivity(pm) != null) return translate
    }
    val process = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    @Suppress("DEPRECATION")
    val target = pm.queryIntentActivities(process, 0).firstOrNull { info ->
        val label = info.loadLabel(pm).toString().lowercase()
        info.activityInfo.packageName in TRANSLATORS || TRANSLATE_LABELS.any { it in label }
    } ?: return null
    return process.setClassName(target.activityInfo.packageName, target.activityInfo.name)
}
