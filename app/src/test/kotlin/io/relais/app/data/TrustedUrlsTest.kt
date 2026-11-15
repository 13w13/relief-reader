// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import okhttp3.ResponseBody.Companion.toResponseBody
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
        // Other ways of writing an address are refused (review of PR 26).
        listOf(
            "https://2130706433/x.png", "https://0x7f.0.0.1/x.png", "https://0x7f000001/x.png", "https://127.1/x.png",
            "https://127.000.000.001/x.png", "https://011.1.1.1/x.png", "https://cdn.example.123/x.png",
        ).forEach { assertNull(it, TrustedUrls.image(it)) }
        // A name with digits elsewhere is a name.
        assertEquals("https://123.example.org/x.png", TrustedUrls.image("https://123.example.org/x.png"))
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
    fun localNamesAndRedirectHopsAreRefused() {
        listOf("https://localhost/x.png", "https://LOCALHOST./x.png", "https://cdn.localhost/x.png").forEach { assertNull(it, TrustedUrls.image(it)) }
        fun url(s: String) = okhttp3.HttpUrl.Builder().scheme(s.substringBefore("://")).host(s.substringAfter("://").substringBefore('/')).build()
        assertTrue(ImageHops.allowed(url("https://reliefweb.int/")))
        assertTrue(ImageHops.allowed(url("https://images.example.org/")))
        listOf("http://images.example.org/", "https://localhost/", "https://127.0.0.1/", "https://10.0.0.8/").forEach {
            assertFalse(it, ImageHops.allowed(url(it)))
        }
    }

    /** A made-up server behind [ImageHops]: each address asked is noted; [redirects] sends on. */
    private class ImageServer {
        val asked = mutableListOf<String>()
        val redirects = mutableMapOf<String, String>()
        val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ImageHops)
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                asked += url
                val builder = okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                val next = redirects[url]
                if (next != null) {
                    builder.code(302).message("Found").header("Location", next).body("".toByteArray().toResponseBody()).build()
                } else {
                    builder.code(200).message("OK").body("PNG".toByteArray().toResponseBody()).build()
                }
            }
            .build()

        fun get(url: String): Int = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.code }
    }

    @Test
    fun imageRedirectsAreCheckedBeforeTheyAreAsked() {
        val ok = ImageServer()
        ok.redirects["https://images.example.org/a.png"] = "/b.png"
        assertEquals(200, ok.get("https://images.example.org/a.png"))
        assertEquals(listOf("https://images.example.org/a.png", "https://images.example.org/b.png"), ok.asked)

        listOf("https://10.0.0.1/b.png", "http://images.example.org/b.png", "https://images.example.org:8443/b.png", "https://localhost/b.png", "https://[64:ff9b::a00:1]/b.png").forEach { target ->
            val server = ImageServer()
            server.redirects["https://images.example.org/a.png"] = target
            try {
                server.get("https://images.example.org/a.png")
                fail("$target should be refused")
            } catch (e: java.io.IOException) {
                assertEquals(target, listOf("https://images.example.org/a.png"), server.asked)
            }
        }

        val loop = ImageServer()
        (0..9).forEach { loop.redirects["https://images.example.org/$it.png"] = "https://images.example.org/${it + 1}.png" }
        try {
            loop.get("https://images.example.org/0.png")
            fail("too many redirects")
        } catch (e: java.io.IOException) {
            assertEquals(ImageHops.MAX_REDIRECTS + 1, loop.asked.size)
        }
        // The first address is checked too, before anything is asked.
        val first = ImageServer()
        try {
            first.get("https://127.0.0.1/a.png")
            fail("refused")
        } catch (e: java.io.IOException) {
            assertTrue(first.asked.isEmpty())
        }
    }

    @Test
    fun namesResolveToPublicAddressesOnly() {
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888").forEach {
            assertTrue(it, PublicDns.isPublic(java.net.InetAddress.getByName(it)))
        }
        listOf(
            "127.0.0.1", "10.0.0.1", "172.20.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fc00::1", "fd00::5",
            "64:ff9b::a00:1", "2002:c0a8:101::1", "2001:0:4136:e378::1", "198.18.0.1",
        ).forEach {
            assertFalse(it, PublicDns.isPublic(java.net.InetAddress.getByName(it)))
        }
    }

    @Test
    fun publicAndPrivateAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888").forEach { assertTrue(it, TrustedUrls.isPublicAddress(it)) }
        listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "999.1.1.1",
            // IPv4 inside IPv6 (security review of PR 26): NAT64, 6to4, IPv4-compatible.
            "64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b:1::1", "2002:a00:1::", "2002:7f00:1::1", "::a00:1",
            // Teredo, documentation, site-local.
            "2001:0:4136:e378::1", "2001:db8::1", "fec0::1",
            // A name is never looked up here.
            "reliefweb.int", "localhost",
        ).forEach { assertFalse(it, TrustedUrls.isPublicAddress(it)) }
        // The IPv4 inside is public: so is the address.
        listOf("64:ff9b::808:808", "2002:808:808::1", "::ffff:8.8.8.8").forEach { assertTrue(it, TrustedUrls.isPublicAddress(it)) }
    }
}
