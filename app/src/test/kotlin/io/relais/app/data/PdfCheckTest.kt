// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfCheckTest {
    private fun file(content: ByteArray) = File.createTempFile("relais", ".pdf").apply {
        deleteOnExit()
        writeBytes(content)
    }

    @Test
    fun pdfHeaderIsAccepted() {
        assertTrue(looksLikePdf(file("%PDF-1.7\n%âã\n1 0 obj".toByteArray(Charsets.ISO_8859_1))))
    }

    @Test
    fun webPageIsRefused() {
        assertFalse(looksLikePdf(file("<!DOCTYPE html><html><title>Just a moment...</title>".toByteArray())))
    }

    @Test
    fun emptyFileIsRefused() {
        assertFalse(looksLikePdf(file(ByteArray(0))))
    }
}
