// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The addresses the app fetches or opens itself (security audit of 15/11). */
class TrustedUrlsTest {
    @Test
    fun filesComeFromReliefWebOnly() {
        listOf(
            "https://reliefweb.int/attachments/a1/sitrep.pdf",
            "https://www.reliefweb.int/report.pdf",
            "https://RELIEFWEB.INT/attachments/a1/sitrep.pdf",
        ).forEach { assertTrue(it, TrustedUrls.isFileUrl(it)) }
        listOf(
            "http://reliefweb.int/attachments/a1/sitrep.pdf",
            "https://reliefweb.int.example.org/report.pdf",
            "https://notreliefweb.int/report.pdf",
            "https://example.org/reliefweb.int/report.pdf",
            "https://user:pass@reliefweb.int/report.pdf",
            "https://reliefweb.int:8443/report.pdf",
            "https://127.0.0.1/report.pdf",
            "https://[fe80::1]/report.pdf",
            "ftp://reliefweb.int/report.pdf",
            "content://org.relreader.app.files/exports/x.pdf",
            "",
            null,
        ).forEach { assertFalse(it.toString(), TrustedUrls.isFileUrl(it)) }
    }

    @Test
    fun imagesAreHttpsOnly() {
        assertEquals("https://reliefweb.int/sites/default/files/photo.jpg", TrustedUrls.image("https://reliefweb.int/sites/default/files/photo.jpg"))
        // Another host is allowed: a report can include images from elsewhere.
        assertEquals("https://images.example.org/map.png", TrustedUrls.image("https://images.example.org/map.png"))
        // http is moved to https.
        assertEquals("https://images.example.org/map.png", TrustedUrls.image("http://images.example.org/map.png"))
        listOf(
            "file:///data/data/org.relreader.app/files/pdf/x.pdf",
            "content://org.relreader.app.files/exports/backup.json",
            "android.resource://org.relreader.app/drawable/icon",
            "data:image/png;base64,iVBORw0KGgo=",
            "javascript:alert(1)",
            "https://127.0.0.1/x.png",
            "https://10.1.2.3/x.png",
            "https://192.168.0.10/x.png",
            "https://169.254.169.254/x.png",
            "https://[::1]/x.png",
            "https://user@images.example.org/x.png",
            "/relative/path.png",
            "   ",
            null,
        ).forEach { assertNull(it.toString(), TrustedUrls.image(it)) }
        // A public address written as such may load.
        assertEquals("https://8.8.8.8/x.png", TrustedUrls.image("https://8.8.8.8/x.png"))
    }

    @Test
    fun coilGetsOnlyCheckedAddresses() {
        assertEquals("https://reliefweb.int/a.jpg", SafeImages.allowed("http://reliefweb.int/a.jpg"))
        assertNull(SafeImages.allowed("file:///etc/hosts"))
        assertNull(SafeImages.allowed(File("/data/data/org.relreader.app/files/x.png")))
        assertNull(SafeImages.allowed(42))
        assertNull(SafeImages.allowed(null))
    }

    @Test
    fun reliefWebPagesOnly() {
        assertEquals("https://reliefweb.int/report/sudan/situation-report", TrustedUrls.reliefWebPage("https://reliefweb.int/report/sudan/situation-report"))
        listOf(
            "http://reliefweb.int/report/sudan/x",
            "https://reliefweb.int.example.org/report/x",
            "https://example.org/report/x",
            "javascript:alert(1)",
            "intent://reliefweb.int/#Intent;end",
            null,
        ).forEach { assertNull(it.toString(), TrustedUrls.reliefWebPage(it)) }
    }

    @Test
    fun aReportPageFallsBackToItsId() {
        assertEquals("https://reliefweb.int/report/sudan/x", TrustedUrls.reportPage("https://reliefweb.int/report/sudan/x", 4_123_456))
        assertEquals("https://reliefweb.int/node/4123456", TrustedUrls.reportPage("https://example.org/phishing", 4_123_456))
        assertEquals("https://reliefweb.int/node/4123456", TrustedUrls.reportPage(null, 4_123_456))
        assertNull(TrustedUrls.reportPage("https://example.org/phishing", 0))
        assertNull(TrustedUrls.reportPage(null, -5))
    }

    @Test
    fun publicAndPrivateAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888").forEach { assertTrue(it, TrustedUrls.isPublicAddress(it)) }
        listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "999.1.1.1",
        ).forEach { assertFalse(it, TrustedUrls.isPublicAddress(it)) }
    }
}
