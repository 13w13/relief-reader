// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.relais.api.Display
import io.relais.app.R
import io.relais.app.RelaisApp
import io.relais.app.data.FileLite
import io.relais.app.data.ReportEntity
import kotlinx.coroutines.flow.MutableStateFlow

/** What the app says of a report's PDF before it is opened: its pages and its weight. */
internal object PdfFacts {
    /** The pages to show: the API's count, else the one read from the file on the phone; null when unknown. */
    fun pages(pdf: FileLite, onPhone: Map<String, Int>): Long? = (pdf.pages ?: onPhone[pdf.url]?.toLong())?.takeIf { it > 0 }

    /** "PDF · 12 pages · 2.3 MB", with what is known; null when neither the pages nor the size are. */
    fun line(pages: String?, size: String?): String? =
        if (pages == null && size == null) null else listOfNotNull("PDF", pages, size).joinToString(" · ")
}

private val NONE = MutableStateFlow<Map<String, Int>>(emptyMap())

/**
 * "PDF · 12 pages · 2.3 MB" for [report], or null without a PDF or when neither is known. The
 * pages are the API's (`file.pagecount`), or those of the file already on the phone; the size
 * is the API's (`file.filesize`). For list rows (PdfFactsLine) and the report's file line.
 */
@Composable
internal fun pdfFactsText(report: ReportEntity): String? {
    val pdf = report.pdf ?: return null
    // Screenshot tests run without RelaisApp: the API's values only.
    val counts = (LocalContext.current.applicationContext as? RelaisApp)?.container?.pdfText?.pageCounts ?: NONE
    val onPhone by counts.collectAsStateWithLifecycle()
    val pages = PdfFacts.pages(pdf, onPhone)
    val pagesText = pages?.let { pluralStringResource(R.plurals.pdf_facts_pages, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it) }
    return PdfFacts.line(pagesText, Display.size(pdf.size))
}

/** The PDF line of a list row, in ink 2 under the country; nothing when [pdfFactsText] is null. */
@Composable
internal fun PdfFactsLine(report: ReportEntity, modifier: Modifier = Modifier) {
    val line = pdfFactsText(report) ?: return
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("pdf_facts_${report.id}"),
    )
}
