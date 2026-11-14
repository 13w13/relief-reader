// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relais.app.container
import io.relais.app.data.FileLite
import io.relais.app.data.PdfEntity
import io.relais.app.data.ReportEntity
import io.relais.app.data.SourceLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug builds only: puts a generated test PDF (assets/test-pdf, tools/make_test_pdfs.py) on
 * the phone as if it had been downloaded, with a report around it, then opens that report.
 * The Maestro flow p6-pdf-text uses it: nothing is downloaded from ReliefWeb.
 *
 *     adb shell am start -a android.intent.action.VIEW -d relaispdf://sample/sitrep-two-columns
 *
 * Samples: test-sitrep and test-scan (produit/passe-v0.2.md 7.1), sitrep-two-columns,
 * scan-no-text, long-25-pages, french-one-column.
 */
class PdfSampleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent?.data?.lastPathSegment
        val index = SAMPLES.indexOf(name)
        if (name == null || index < 0) {
            Log.w(TAG, "Unknown sample: ${intent?.data}. Use relaispdf://sample/<name> with one of $SAMPLES.")
            finish()
            return
        }
        val reportId = FIRST_ID + index
        runBlocking(Dispatchers.IO) { seed(name, reportId) }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("relais://report/$reportId")).setPackage(packageName))
        finish()
    }

    private suspend fun seed(name: String, reportId: Long) {
        val c = applicationContext.container
        val file = File(File(filesDir, "pdf").apply { mkdirs() }, "$name.pdf")
        assets.open("test-pdf/$name.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val url = "https://relais.test/pdf/$name.pdf"
        val now = System.currentTimeMillis()
        c.dao.upsertReports(
            listOf(
                ReportEntity(
                    id = reportId,
                    title = TITLES.getValue(name),
                    headlineTitle = null,
                    headlineSummary = null,
                    imageUrl = null,
                    imageCaption = null,
                    imageCopyright = null,
                    urlAlias = null,
                    origin = null,
                    dateCreated = now,
                    dateOriginal = null,
                    primaryCountryId = null,
                    primaryCountryName = null,
                    countries = emptyList(),
                    sources = listOf(SourceLite(0, "Relais test", "Relais test documents")),
                    formatId = 10,
                    formatName = "Situation Report",
                    themes = emptyList(),
                    languages = listOf(if (name.startsWith("french")) "fr" else "en"),
                    files = listOf(FileLite(url = url, filename = "$name.pdf", size = file.length(), mimetype = "application/pdf")),
                    // No text on ReliefWeb, as for most situation reports: the list shows the PDF's first lines.
                    bodyHtml = "",
                    isHeadline = false,
                    fromRss = false,
                    fetchedAt = now,
                ),
            ),
        )
        c.dao.upsertPdf(PdfEntity(url, reportId, file.absolutePath, file.length(), now))
    }

    private companion object {
        const val TAG = "RelaisPdfSample"

        /** Far above ReliefWeb's ids (about 4.2 million in 2026). */
        const val FIRST_ID = 99_000_001L
        val SAMPLES = listOf("sitrep-two-columns", "scan-no-text", "long-25-pages", "french-one-column", "test-sitrep", "test-scan")
        val TITLES = mapOf(
            "test-sitrep" to "Test Situation Report No. 1 (test document)",
            "test-scan" to "Scanned communiqué without text (test document)",
            "sitrep-two-columns" to "Noravia: Situation Report No. 7 (test document)",
            "scan-no-text" to "Scanned letter without text (test document)",
            "long-25-pages" to "Noravia long report (test document)",
            "french-one-column" to "Rapport d'essai : Nordland (document de test)",
        )
    }
}
