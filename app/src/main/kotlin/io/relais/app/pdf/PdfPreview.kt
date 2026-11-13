// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import io.relais.app.R
import io.relais.app.data.FileLite
import io.relais.app.data.ReportEntity
import io.relais.app.ui.PlexCondensed

/** Maps, infographics and interactive content: their text has no reading order, they open as pages. */
val NO_TEXT_FORMATS = setOf(12L, 12570L, 38974L)

/** First lines of the text of the PDFs read on the phone, by report (PdfTextStore.previews). */
val LocalPdfPreviews = compositionLocalOf<Map<Long, String>> { emptyMap() }

private val ATTACHED_ONLY = Regex(
    "(?is)^\\s*(<p>\\s*)?(please\\s+)?(refer|see)\\s+(to\\s+)?(the\\s+)?attached\\s+(file|document|report|pdf)s?\\s*\\.?\\s*(</p>)?\\s*$",
)

/**
 * A report whose text on ReliefWeb is nothing, or only "Please refer to the attached file":
 * its list row can show the first lines of its PDF instead.
 */
fun hasNoBody(report: ReportEntity): Boolean {
    if (report.formatId in NO_TEXT_FORMATS) return false
    // The RSS feed puts that sentence in the summary.
    if (report.fromRss) return report.headlineSummary?.let { ATTACHED_ONLY.matches(it) } ?: false
    val body = report.bodyHtml ?: return false
    return body.isBlank() || ATTACHED_ONLY.matches(body)
}

/** The preview for a list row, or null: only for a report without text whose PDF was read. */
@Composable
fun pdfPreview(report: ReportEntity): String? = if (hasNoBody(report)) LocalPdfPreviews.current[report.id] else null

/** The name of a PDF in the reader's top bar, as the report screen gives it. */
fun pdfTitle(report: ReportEntity, file: FileLite?): String =
    file?.description?.removePrefix("- ")?.ifBlank { null } ?: file?.filename ?: report.title

/**
 * The preview of a list row (theme-compose.md 10.9): "FROM THE PDF" with its icon, then the
 * first paragraph of the PDF, two lines at most.
 */
@Composable
fun PdfPreviewLine(preview: String) {
    val cs = MaterialTheme.colorScheme
    val label = stringResource(R.string.pdf_preview_label).uppercase()
    val text = buildAnnotatedString {
        appendInlineContent(ICON, "[PDF]")
        append(" ")
        withStyle(SpanStyle(fontFamily = PlexCondensed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.6.sp, color = cs.onSurface)) {
            append(label)
        }
        append("  ")
        append(preview)
    }
    val icon = mapOf(
        ICON to InlineTextContent(Placeholder(13.sp, 13.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = cs.onSurface, modifier = Modifier.fillMaxSize())
        },
    )
    Text(
        text,
        inlineContent = icon,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        color = cs.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("pdf_preview"),
    )
}

private const val ICON = "pdf"

/** Where a highlight of a PDF opens: the file, its name, the report's ReliefWeb page. */
data class PdfTarget(val url: String, val title: String, val sourceUrl: String?)

/**
 * The PDF a highlight was made in, when its file is still on the phone; null otherwise (a
 * highlight of the report body, or a PDF Android cleared from the cache: open the report).
 */
suspend fun io.relais.app.AppContainer.pdfTarget(h: io.relais.app.data.HighlightEntity): PdfTarget? {
    val page = h.pdfPage ?: return null
    val url = pdfText.urlFor(h.reportId, page - 1, h.blockIndex, h.start, h.end, h.text) ?: return null
    if (pdfs.fileFor(url) == null) return null
    val report = dao.report(h.reportId) ?: return null
    return PdfTarget(url, pdfTitle(report, report.files.firstOrNull { it.url == url }), report.urlAlias)
}
