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

/** What a list row says of a report's PDF before it is opened: its weight and its pages. */
object PdfFacts {
    /** The pages to show: the API's count, else the one read from the file on the phone; null when unknown. */
    fun pages(pdf: FileLite, onPhone: Map<String, Int>): Long? = (pdf.pages ?: onPhone[pdf.url]?.toLong())?.takeIf { it > 0 }

    /** "PDF · 2.3 MB · 12 pages", with what is known; null when neither the size nor the pages are. */
    fun line(size: String?, pages: String?): String? =
        if (size == null && pages == null) null else listOfNotNull("PDF", size, pages).joinToString(" · ")
}

private val NONE = MutableStateFlow<Map<String, Int>>(emptyMap())

/**
 * The PDF line of a list row: "PDF · 2.3 MB · 12 pages" in ink 2. The size is the API's
 * (`file.filesize`); the pages are the API's (`file.pagecount`), or those of the file already on
 * the phone. Nothing is shown for what is not known, and nothing at all without a PDF.
 */
@Composable
fun PdfFactsLine(report: ReportEntity, modifier: Modifier = Modifier) {
    val pdf = report.pdf ?: return
    // Screenshot tests run without RelaisApp: the API's values only.
    val counts = (LocalContext.current.applicationContext as? RelaisApp)?.container?.pdfText?.pageCounts ?: NONE
    val onPhone by counts.collectAsStateWithLifecycle()
    val pages = PdfFacts.pages(pdf, onPhone)
    val pagesText = pages?.let { pluralStringResource(R.plurals.pdf_facts_pages, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it) }
    val line = PdfFacts.line(Display.size(pdf.size), pagesText) ?: return
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("pdf_facts_${report.id}"),
    )
}
