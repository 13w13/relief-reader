// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.pdf.BlockKind
import io.relais.app.pdf.PdfTextContent
import io.relais.app.pdf.PdfTextPage
import io.relais.app.pdf.TextBlock
import io.relais.app.pdf.TextRow
import io.relais.app.pdf.key
import io.relais.app.pdf.textRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The text view's list starts with the banner on its first opening only (v0.4). Every jump
 * (saved position, passage, search result, page of the PDF) counts it only when it shows:
 * a fixed offset put them one row too far (review of PR 13).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PdfTextRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sentence = "Water trucking reached the northern camps while families moved to two collective centres. "
    private val rows: List<TextRow> = textRows(
        (0 until 3).map { page ->
            PdfTextPage(page, (0 until 8).map { TextBlock(BlockKind.PARAGRAPH, "Page ${page + 1}, block $it. " + sentence.repeat(4)) }, null)
        },
    )

    @Test
    fun theBannerCountsOnlyWhenItShows() {
        val without = rowIndex(rows, page = 1, block = 3, lead = 0)
        val with = rowIndex(rows, page = 1, block = 3, lead = 1)
        assertEquals(without + 1, with)
        for (lead in 0..1) {
            val row = rowAt(rows, rowIndex(rows, page = 1, block = 3, lead = lead), lead) as TextRow.Body
            assertEquals(1, row.page)
            assertEquals(3, row.block)
        }
    }

    @Test
    fun withoutTheBannerAJumpLandsOnItsRow() = jumpLandsOnItsRow(showBanner = false)

    @Test
    fun withTheBannerAJumpLandsOnItsRow() = jumpLandsOnItsRow(showBanner = true)

    private fun jumpLandsOnItsRow(showBanner: Boolean) {
        val state = LazyListState()
        compose.setContent {
            RelaisTheme(darkTheme = false) { PdfTextContent(rows, state, onOpenPdf = {}, showBanner = showBanner) }
        }
        val lead = if (showBanner) 1 else 0
        val target = rowIndex(rows, page = 1, block = 3, lead = lead)
        compose.runOnIdle { runBlocking { state.scrollToItem(target) } }
        compose.runOnIdle {
            val expected = rows.first { it is TextRow.Body && it.page == 1 && it.block == 3 }
            assertEquals(expected.key(), state.layoutInfo.visibleItemsInfo.first().key)
        }
    }
}
