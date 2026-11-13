// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures two PDF text extractors on an emulator (pdf-bench.yml):
 * PdfBox-Android with PdfLayout, and Android's own text API (PdfRenderer.Page.getTextContents,
 * API 35 or S extension 13). Times are the median of three runs after one warm-up run.
 * Results go to logcat under the tag RelaisBench.
 */
@RunWith(AndroidJUnit4::class)
class PdfExtractorBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String): File = File(context.cacheDir, name).also { file ->
        context.assets.open("test-pdf/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun <T> median(block: () -> T): Pair<Long, T> {
        var result = block()
        val times = (1..3).map {
            val started = System.nanoTime()
            result = block()
            (System.nanoTime() - started) / 1_000_000
        }
        return times.sorted()[1] to result
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    @Test
    fun compareExtractors() {
        PDFBoxResourceLoader.init(context)
        val extension = if (Build.VERSION.SDK_INT >= 30) SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) else 0
        log("device: API ${Build.VERSION.SDK_INT}, S extension $extension, ${Build.SUPPORTED_ABIS.firstOrNull()}")
        for (name in listOf("long-25-pages.pdf", "sitrep-two-columns.pdf")) {
            val file = asset(name)
            val (boxMs, doc) = median { PdfLayout.document(PdfBoxText.read(file).pages) }
            log("$name: PdfBox + layout ${doc.pages.size} pages, median $boxMs ms, ${doc.pages.sumOf { it.chars }} characters")
            if (Build.VERSION.SDK_INT >= 35) {
                val (platformMs, texts) = median { platformText(file) }
                log("$name: platform getTextContents ${texts.size} pages, median $platformMs ms, ${texts.sumOf { it.length }} characters")
            }
        }
        // Reading order on page 2 of the sitrep, written row by row across two columns.
        val sitrep = asset("sitrep-two-columns.pdf")
        val box = PdfLayout.document(PdfBoxText.read(sitrep).pages).pages[1].blocks.joinToString(" / ") { it.text }
        log("order, PdfBox + layout, page 2: ${box.take(240)}")
        if (Build.VERSION.SDK_INT >= 35) {
            log("order, platform, page 2: ${platformText(sitrep)[1].replace('\n', ' ').take(240)}")
        }
        assertTrue(box.startsWith("School attendance rose"))
    }

    @RequiresApi(35)
    private fun platformText(file: File): List<String> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
                }
            }
        }

    private companion object {
        const val TAG = "RelaisBench"
    }
}
