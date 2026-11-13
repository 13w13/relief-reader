// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.pdf

import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorProcessor
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Limits of one reading (security audit S1, section 3). Past one of them the reading stops,
 * and the pages read whole are kept: the text view then ends with "Text stops at page n".
 */
data class PdfLimits(
    val maxPages: Int = 400,
    val maxMillis: Long = 90_000,
    /** Characters kept in memory until the layout, at about 80 bytes each. */
    val maxGlyphs: Int = glyphBudget(),
    /** The reading stops when less free heap than this is left after a page. */
    val minFreeHeap: Long = 24L * 1024 * 1024,
) {
    companion object {
        /** Larger files are not read as text at all. */
        const val MAX_FILE_BYTES = 60L * 1024 * 1024

        /** A quarter of the app's heap for the characters, between 200,000 and 2 million. */
        fun glyphBudget(maxHeap: Long = Runtime.getRuntime().maxMemory()): Int =
            (maxHeap / 4 / BYTES_PER_GLYPH).coerceIn(200_000L, 2_000_000L).toInt()

        private const val BYTES_PER_GLYPH = 80L
    }
}

/** Why a reading stopped before the last page. */
enum class PdfStop { PAGES, TIME, MEMORY }

/** The pages read whole, the page count of the PDF, and why the reading stopped early, if it did. */
class PdfRead(val pages: List<PageInput>, val pageCount: Int, val stop: PdfStop?)

/**
 * Reads the characters, the images and the paths of each page with PdfBox-Android
 * (Apache-2.0). Positions only: the reading order is worked out by [PdfLayout].
 * On Android, PDFBoxResourceLoader.init(context) must have run once before.
 */
object PdfBoxText {
    /**
     * Reads the pages, within [limits]. [onPage] gets (pages done, pages to read); throwing
     * from it stops the reading. An encrypted PDF that needs a password throws, and so does
     * one that needs more scratch space than [STORAGE_BYTES].
     */
    fun read(file: File, limits: PdfLimits = PdfLimits(), onPage: (Int, Int) -> Unit = { _, _ -> }): PdfRead {
        val deadline = System.nanoTime() + limits.maxMillis * 1_000_000
        return PDDocument.load(file, MemoryUsageSetting.setupMixed(MEMORY_BYTES, STORAGE_BYTES)).use { document ->
            val count = document.numberOfPages
            val collector = Collector(count.coerceAtMost(limits.maxPages), limits, deadline, onPage)
            collector.startPage = 1
            collector.endPage = collector.total
            val stop = try {
                collector.getText(document)
                if (count > limits.maxPages) PdfStop.PAGES else null
            } catch (e: LimitReached) {
                e.stop
            }
            PdfRead(collector.pages, count, stop)
        }
    }

    /** Thrown by the collector when a limit is reached: the page under way is dropped. */
    private class LimitReached(val stop: PdfStop) : RuntimeException(stop.name)

    private const val MEMORY_BYTES = 32L * 1024 * 1024
    /** Memory and temporary file together, for the streams of the PDF. */
    private const val STORAGE_BYTES = 160L * 1024 * 1024

    private class Collector(
        val total: Int,
        private val limits: PdfLimits,
        private val deadline: Long,
        private val onPage: (Int, Int) -> Unit,
    ) : PDFTextStripper() {
        val pages = ArrayList<PageInput>()
        private var glyphCount = 0
        private var positions = 0
        private var operators = 0
        private var glyphs = ArrayList<Glyph>()
        private var imageArea = 0f
        private var pathOps = 0
        private var width = 0f
        private var height = 0f
        private val boldFonts = IdentityHashMap<PDFont, Boolean>()

        init {
            sortByPosition = false
            for (op in listOf("re", "l", "c", "v", "y")) addOperator(PathCounter(op))
            addOperator(DrawImageOrForm())
        }

        override fun startPage(page: PDPage) {
            checkTime()
            glyphs = ArrayList()
            imageArea = 0f
            pathOps = 0
            val box = page.cropBox
            val turned = page.rotation % 180 != 0
            width = if (turned) box.height else box.width
            height = if (turned) box.width else box.height
        }

        override fun processTextPosition(text: TextPosition) {
            // Every glyph counts for the time, even those dropped below: one Tj can hold millions.
            if (++positions and 0xFF == 0) checkTime()
            // Vertical text (axis labels, map labels) stays out of the reading text.
            if (text.dir != 0f) return
            val unicode = text.unicode ?: return
            if (++glyphCount > limits.maxGlyphs) throw LimitReached(PdfStop.MEMORY)
            // The scale of the text matrix gives the size as drawn; fontSizeInPt is wrong for some writers (Quartz).
            val size = text.yScale.takeIf { it > 0.5f } ?: text.fontSizeInPt
            glyphs += Glyph(unicode, text.xDirAdj, text.yDirAdj, text.widthDirAdj, size, isBold(text.font))
        }

        override fun endPage(page: PDPage) {
            pages += PageInput(width, height, glyphs, imageArea, pathOps)
            onPage(pages.size, total)
            if (pages.size < total) {
                checkTime()
                if (lowOnMemory()) throw LimitReached(PdfStop.MEMORY)
            }
        }

        /** Less free heap than [PdfLimits.minFreeHeap], even after a collection: garbage counts as used until then. */
        private fun lowOnMemory(): Boolean {
            fun free(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
            if (free() >= limits.minFreeHeap) return false
            System.gc()
            return free() < limits.minFreeHeap
        }

        /** Every operator of the page, of its forms and of Type3 glyphs goes through here: the time is checked. */
        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (++operators and 0x3FF == 0) checkTime()
            super.processOperator(operator, operands)
        }

        private fun checkTime() {
            if (System.nanoTime() > deadline) throw LimitReached(PdfStop.TIME)
        }

        private fun isBold(font: PDFont?): Boolean {
            if (font == null) return false
            return boldFonts.getOrPut(font) {
                val name = font.name.orEmpty()
                val descriptor = font.fontDescriptor
                BOLD_NAMES.any { name.contains(it, ignoreCase = true) } ||
                    descriptor != null && (descriptor.isForceBold || descriptor.fontWeight >= 600f)
            }
        }

        /** Counts path operators: ruled tables, charts and maps draw many of them. */
        private inner class PathCounter(private val op: String) : OperatorProcessor() {
            override fun process(operator: Operator, operands: MutableList<COSBase>) {
                pathOps++
            }

            override fun getName(): String = op
        }

        /** Adds up the area of the images; forms are still read for their text. */
        private inner class DrawImageOrForm : OperatorProcessor() {
            private val forms = DrawObject()

            override fun process(operator: Operator, operands: MutableList<COSBase>) {
                val name = operands.firstOrNull() as? COSName ?: return
                val resources = this@Collector.resources ?: return
                if (resources.isImageXObject(name)) {
                    val m = graphicsState.currentTransformationMatrix
                    imageArea += abs(m.scaleX * m.scaleY - m.shearX * m.shearY)
                } else {
                    forms.setContext(this@Collector)
                    forms.process(operator, operands)
                }
            }

            override fun getName(): String = "Do"
        }
    }

    private val BOLD_NAMES = listOf("bold", "black", "heavy", "semibold", "demi")
}
