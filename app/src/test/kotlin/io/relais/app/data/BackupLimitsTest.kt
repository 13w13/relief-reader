// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A restored backup is bounded (security audit of 15/11). Made-up libraries, some of them traps. */
class BackupLimitsTest {
    private val head = """"app":"Re.L","kind":"library-backup","version":1,"exportedAt":1794700000000"""

    private fun card(title: String, urlAlias: String? = null) =
        """{"title":"$title","dateCreated":1794700000000${urlAlias?.let { ""","urlAlias":"$it"""" } ?: ""}}"""

    @Test
    fun aTitleOfThreeMegabytesIsCut() {
        val huge = "N".repeat(3 * 1024 * 1024)
        val long = "L".repeat(20_000)
        val file = """{$head,"readings":[{"reportId":4100001,"savedAt":1794700000000,"card":${card(huge)}}],
            "highlights":[{"reportId":4100001,"reportTitle":"$long","reportDate":1,"createdAt":2,"text":"$long","note":"$long","blockIndex":0,"start":0,"end":4}],
            "countries":[{"id":2,"name":"$long"}],"topics":[{"id":7,"title":"$long","urlAlias":"https://reliefweb.int/topics/$long"}]}"""
        val parsed = LibraryBackupFormat.parse(file)
        assertTrue(parsed.toString().take(200), parsed is LibraryBackupFormat.Parsed.Ok)
        val b = (parsed as LibraryBackupFormat.Parsed.Ok).backup
        assertEquals(LibraryBackupFormat.MAX_STRING, b.readings.single().card!!.title.length)
        val h = b.highlights.single()
        assertEquals(LibraryBackupFormat.MAX_STRING, h.reportTitle.length)
        assertEquals(LibraryBackupFormat.MAX_TEXT, h.text.length)
        assertEquals(LibraryBackupFormat.MAX_NOTE, h.note!!.length)
        assertEquals(LibraryBackupFormat.MAX_STRING, b.countries.single().name.length)
        assertEquals(LibraryBackupFormat.MAX_STRING, b.topics.single().title.length)
    }

    @Test
    fun twoHundredThousandRowsAreRefusedWithAMessage() {
        val rows = (1..200_000).joinToString(",") { """{"reportId":$it}""" }
        val parsed = LibraryBackupFormat.parse("""{$head,"readings":[$rows]}""")
        assertEquals(LibraryBackupFormat.Parsed.TooLarge, parsed)
    }

    @Test
    fun badIdsAndNumbersAreLeftOutOrBrought() {
        val file = """{$head,
            "readings":[{"reportId":-4,"savedAt":1},{"reportId":0},{"reportId":99999999999},{"reportId":4100002,"percent":900,"pdfPage":-3,"itemIndex":-1,"itemOffset":-9,
              "card":{"title":"Noravia: Situation Report No. 3","dateCreated":1,"primaryCountryId":-2,"formatId":0,"sources":[{"id":-1,"shortname":"X","name":"X"},{"id":17,"shortname":"TRA","name":"Test Refugee Agency"}]}}],
            "highlights":[{"reportId":4100002,"reportTitle":"t","reportDate":1,"createdAt":2,"text":"abc","blockIndex":-1,"start":0,"end":1},
              {"reportId":4100002,"reportTitle":"t","reportDate":1,"createdAt":2,"text":"abc","blockIndex":2,"start":5,"end":1},
              {"reportId":4100002,"reportTitle":"t","reportDate":1,"createdAt":2,"text":"abc","blockIndex":2,"start":0,"end":3,"pdfPage":-7}],
            "countries":[{"id":-990,"name":"Nowhere"},{"id":990,"name":"Noravia"}],
            "crises":[{"id":0,"name":"None"}],"sources":[{"id":-5,"shortname":"N","name":"N"}],"topics":[{"id":-1,"title":"T"}]}"""
        val b = (LibraryBackupFormat.parse(file) as LibraryBackupFormat.Parsed.Ok).backup
        val r = b.readings.single()
        assertEquals(4100002L, r.reportId)
        assertEquals(100, r.percent)
        assertEquals(0, r.pdfPage)
        assertEquals(0, r.itemIndex)
        assertEquals(0, r.itemOffset)
        assertNull(r.card!!.primaryCountryId)
        assertNull(r.card!!.formatId)
        assertEquals(listOf(17L), r.card!!.sources.map { it.id })
        val h = b.highlights.single()
        assertEquals(2, h.blockIndex)
        assertNull(h.pdfPage)
        assertEquals(listOf(990L), b.countries.map { it.id })
        assertTrue(b.crises.isEmpty() && b.sources.isEmpty() && b.topics.isEmpty())
    }

    @Test
    fun onlyReliefWebAddressesComeBack() {
        val file = """{$head,"readings":[
            {"reportId":4100003,"card":${card("a", "https://reliefweb.int/report/noravia/a")}},
            {"reportId":4100004,"card":${card("b", "https://phishing.example.org/report")}},
            {"reportId":4100005,"card":${card("c", "javascript:alert(1)")}}],
            "topics":[{"id":8,"title":"T","urlAlias":"http://reliefweb.int/topics/t"}]}"""
        val b = (LibraryBackupFormat.parse(file) as LibraryBackupFormat.Parsed.Ok).backup
        assertEquals(listOf("https://reliefweb.int/report/noravia/a", null, null), b.readings.map { it.card?.urlAlias })
        assertNull(b.topics.single().urlAlias)
    }
}
