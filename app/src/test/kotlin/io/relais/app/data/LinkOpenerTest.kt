// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiException
import io.relais.app.data.ReliefWebLinks.Link
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** From a ReliefWeb link to a screen (17/11): each case, with made-up reports and requests counted. */
class LinkOpenerTest {
    private val report = Link.Report("https://reliefweb.int/report/sudan/sudan-test-situation-report-no-2-990000300")
    private val node = Link.Node("https://reliefweb.int/node/990000300", 990000300)

    private class Phone(
        var onPhone: Long? = null,
        var access: Boolean = true,
        var online: Boolean = true,
        var answer: () -> Long? = { 990000300 },
    ) {
        var requests = 0
        var clock = 1_794_614_400_000L
        val opener = LinkOpener(
            onPhone = { onPhone },
            hasAccess = { access },
            online = { online },
            fetch = { requests++; answer() },
            now = { clock },
        )

        fun open(link: Link) = runBlocking { opener.open(link) }
    }

    @Test
    fun aReportOnThePhoneOpensWithoutARequest() {
        // Saved, cached, or a headline of the feed: with an appname or not, online or not.
        listOf(true, false).forEach { access ->
            listOf(true, false).forEach { online ->
                val phone = Phone(onPhone = 42, access = access, online = online)
                assertEquals(LinkTarget.Report(42), phone.open(report))
                assertEquals(LinkTarget.Report(42), phone.open(node))
                assertEquals(0, phone.requests)
            }
        }
    }

    @Test
    fun withoutAnAppnameTheReportNeedsOne() {
        val phone = Phone(access = false)
        assertEquals(LinkTarget.NeedsAppname, phone.open(report))
        assertEquals(LinkTarget.NeedsAppname, phone.open(node))
        assertEquals(0, phone.requests)
    }

    @Test
    fun offlineSaysSoWithoutARequest() {
        val phone = Phone(online = false)
        assertEquals(LinkTarget.Offline, phone.open(report))
        assertEquals(0, phone.requests)
    }

    @Test
    fun oneRequestForTheReportAsked() {
        val phone = Phone()
        assertEquals(LinkTarget.Report(990000300), phone.open(report))
        assertEquals(1, phone.requests)
        assertEquals(LinkTarget.Report(990000300), phone.open(node))
        assertEquals(2, phone.requests)
    }

    @Test
    fun noReportAtThatAddress() {
        assertEquals(LinkTarget.NotFound, Phone(answer = { null }).open(report))
    }

    @Test
    fun failuresAreSaid() {
        assertEquals(LinkTarget.Offline, Phone(answer = { throw ApiException.Network(java.io.IOException()) }).open(report))
        assertEquals(LinkTarget.NeedsAppname, Phone(answer = { throw ApiException.NoAppname() }).open(report))
        assertEquals(LinkTarget.Failed("HTTP 403"), Phone(answer = { throw ApiException.AccessDenied("denied") }).open(report))
        assertEquals(LinkTarget.Failed("HTTP 500"), Phone(answer = { throw ApiException.Server(500, "x") }).open(report))
        assertEquals(LinkTarget.Failed("ReliefWeb did not answer"), Phone(answer = { throw ApiException.Blocked() }).open(report))
        assertEquals(LinkTarget.Failed("unexpected error"), Phone(answer = { throw IllegalStateException() }).open(report))
    }

    private fun reportNo(n: Int) = Link.Report("https://reliefweb.int/report/sudan/sudan-test-flash-update-no-$n")

    @Test
    fun fiveRequestsInTenMinutesThenTryLater() {
        // Another app sending links on its own (review of PR 47): five requests, then none.
        val phone = Phone()
        (1..5).forEach { assertEquals(LinkTarget.Report(990000300), phone.open(reportNo(it))) }
        assertEquals(5, phone.requests)
        assertEquals(LinkTarget.TryLater, phone.open(reportNo(6)))
        phone.clock += LinkOpener.WINDOW - 1
        assertEquals(LinkTarget.TryLater, phone.open(reportNo(7)))
        assertEquals(5, phone.requests)
        // A report on the phone still opens meanwhile: it costs no request.
        phone.onPhone = 42
        assertEquals(LinkTarget.Report(42), phone.open(reportNo(8)))
        phone.onPhone = null
        // Ten minutes after the first ones, requests go again.
        phone.clock += 1
        assertEquals(LinkTarget.Report(990000300), phone.open(reportNo(9)))
        assertEquals(6, phone.requests)
    }

    @Test
    fun failedRequestsCountInTheBudgetToo() {
        val phone = Phone(answer = { throw ApiException.Server(500, "x") })
        (1..LinkOpener.BUDGET).forEach { assertEquals(LinkTarget.Failed("HTTP 500"), phone.open(reportNo(it))) }
        assertEquals(LinkTarget.TryLater, phone.open(reportNo(99)))
        assertEquals(LinkOpener.BUDGET, phone.requests)
    }

    @Test
    fun anAddressWithoutAReportIsNotAskedAgainForTenMinutes() {
        val phone = Phone(answer = { null })
        assertEquals(LinkTarget.NotFound, phone.open(report))
        assertEquals(LinkTarget.NotFound, phone.open(report))
        phone.clock += LinkOpener.NOT_FOUND_FOR - 1
        assertEquals(LinkTarget.NotFound, phone.open(report))
        assertEquals(1, phone.requests)
        // Another address is asked.
        assertEquals(LinkTarget.NotFound, phone.open(node))
        assertEquals(2, phone.requests)
        phone.clock += 1
        assertEquals(LinkTarget.NotFound, phone.open(report))
        assertEquals(3, phone.requests)
    }

    @Test
    fun otherPagesGoToTheBrowserWithoutARequest() {
        // A country's page too: a link from outside opens no screen of Re.L but a report (review of PR 47).
        val phone = Phone()
        listOf(
            "https://reliefweb.int",
            "https://reliefweb.int/country/sdn",
            "https://reliefweb.int/disaster/fl-2026-000001-sdn",
            "https://reliefweb.int/organization/ern",
        ).forEach {
            assertEquals(it, LinkTarget.NotAReport, phone.open(Link.Other(it)))
        }
        assertEquals(0, phone.requests)
    }
}
