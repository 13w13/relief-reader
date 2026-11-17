// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.app.data.ReliefWebLinks.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A ReliefWeb link from another app (17/11): only a page of reliefweb.int goes on. Made-up addresses. */
class ReliefWebLinksTest {
    private val report = "https://reliefweb.int/report/sudan/sudan-test-situation-report-no-2-990000300"

    @Test
    fun aTitleThenALink() {
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("Sudan: Test situation report No. 2\n$report"))
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("Read this: $report."))
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("($report)"))
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("<$report>"))
    }

    @Test
    fun theFirstReliefWebLinkWins() {
        val other = "https://reliefweb.int/report/chad/chad-test-flash-update-990000301"
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("$report and $other"))
        // Links to other sites, or http, before it are skipped.
        assertEquals(Link.Report(other), ReliefWebLinks.fromSharedText("https://example.org/a http://reliefweb.int/report/x/y $other"))
    }

    @Test
    fun noReliefWebLinkGivesNothing() {
        listOf(
            null,
            "",
            "   ",
            "No link here",
            "https://example.org/report/sudan/x",
            "http://reliefweb.int/report/sudan/x",
            "reliefweb.int/report/sudan/x",
            "ftp://reliefweb.int/report/sudan/x",
        ).forEach { assertNull("$it", ReliefWebLinks.fromSharedText(it)) }
    }

    @Test
    fun lookAlikesAndOtherForms() {
        listOf(
            "https://reliefweb.int.example.org/report/sudan/x",
            "https://www.reliefweb.int/report/sudan/x",
            "https://api.reliefweb.int/v2/reports/1",
            "https://xn--reliefwb-yxa.int/report/sudan/x",
            "https://reliefweb.іnt/report/sudan/x",
            "https://user:pass@reliefweb.int/report/sudan/x",
            "https://reliefweb.int:8443/report/sudan/x",
            "https://203.0.113.5/report/sudan/x",
            "file:///sdcard/report.html",
            "content://com.example/report",
            "intent://reliefweb.int/report/x#Intent;end",
            "javascript:alert(1)",
            "https://reliefweb.int/report/sudan/x%22%20OR%20id:1",
        ).forEach { assertNull(it, ReliefWebLinks.fromUrl(it)) }
    }

    @Test
    fun theQueryAndFragmentGoTheSlashToo() {
        assertEquals(Link.Report(report), ReliefWebLinks.fromUrl("$report?utm_source=whatsapp#top"))
        assertEquals(Link.Report(report), ReliefWebLinks.fromUrl("$report/"))
        assertEquals(Link.Report(report), ReliefWebLinks.fromUrl("HTTPS://RELIEFWEB.INT/report/sudan/sudan-test-situation-report-no-2-990000300"))
    }

    @Test
    fun eachKindOfPage() {
        assertEquals(Link.Report("https://reliefweb.int/map/sudan/sudan-test-map"), ReliefWebLinks.fromUrl("https://reliefweb.int/map/sudan/sudan-test-map"))
        assertEquals(Link.Node("https://reliefweb.int/node/990000300", 990000300), ReliefWebLinks.fromUrl("https://reliefweb.int/node/990000300"))
        assertEquals(Link.Country("https://reliefweb.int/country/sdn", "sdn"), ReliefWebLinks.fromUrl("https://reliefweb.int/country/SDN"))
        listOf(
            "https://reliefweb.int",
            "https://reliefweb.int/",
            "https://reliefweb.int/disaster/fl-2026-000001-sdn",
            "https://reliefweb.int/organization/ern",
            "https://reliefweb.int/updates?search=sudan",
            "https://reliefweb.int/report/sudan",
            "https://reliefweb.int/node/0",
            "https://reliefweb.int/node/abc",
        ).forEach { assertEquals(it, Link.Other::class, ReliefWebLinks.fromUrl(it)!!::class) }
    }

    @Test
    fun longTextsAndLinksAreBounded() {
        // A link past the first 4,096 characters is not read.
        assertNull(ReliefWebLinks.fromSharedText("x".repeat(ReliefWebLinks.MAX_TEXT) + " $report"))
        assertEquals(Link.Report(report), ReliefWebLinks.fromSharedText("x".repeat(100) + " $report " + "y".repeat(100_000)))
        // A link longer than any ReliefWeb address is refused.
        assertNull(ReliefWebLinks.fromUrl("https://reliefweb.int/report/sudan/" + "a".repeat(ReliefWebLinks.MAX_URL)))
    }
}
