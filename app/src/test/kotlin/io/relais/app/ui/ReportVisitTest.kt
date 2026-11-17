// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Whether a visit of a report counts (review of PR 47): what ReportViewModel writes, through
 * ReportVisit, when the report opens in Re.L and when it comes from a link another app sent.
 * ReportViewModel itself needs the whole AppContainer (its database file, the process's
 * settings store), so the rule it delegates to is tested here; the triggers are in ReaderInputTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ReportVisitTest {
    private val writes = mutableListOf<String>()

    private fun visit(fromLink: Boolean, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) = ReportVisit(
        fromLink = fromLink,
        scope = scope,
        opened = { writes += "opened" },
        save = { writes += "place ${it.index} ${it.percent}%" + if (it.finished) " finished" else "" },
    )

    private fun place(index: Int, percent: Int, finished: Boolean = false) = ReportVisit.Place(index, 0, percent, finished)

    @Test
    fun aReportOpenedInRelCountsAtOnce() = runBlocking<Unit> {
        val v = visit(fromLink = false)
        assertTrue(v.counts)
        v.loaded()
        v.reached(place(3, 40))
        v.interacted()
        assertEquals(listOf("opened", "place 3 40%"), writes)
    }

    @Test
    fun fromALinkNothingIsWrittenOnLoading() = runBlocking<Unit> {
        val v = visit(fromLink = true)
        v.loaded()
        v.reached(place(2, 30))
        v.reached(place(4, 60))
        // "Try again" loads again: still nothing.
        v.loaded()
        assertFalse(v.counts)
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun fromALinkTheFirstActionWritesTheVisitAndThePlaceReached() = runBlocking<Unit> {
        val v = visit(fromLink = true)
        v.loaded()
        // A short report: all of it on the screen, so at its end from the start.
        v.reached(place(5, 100, finished = true))
        v.interacted()
        assertTrue(v.counts)
        assertEquals(listOf("opened", "place 5 100% finished"), writes)
        // Once only; from then on, each place is written.
        v.interacted()
        v.reached(place(6, 100, finished = true))
        assertEquals(listOf("opened", "place 5 100% finished", "place 6 100% finished"), writes)
    }

    @Test
    fun touchedBeforeAnyPlaceOnlyCountsTheVisit() {
        val v = visit(fromLink = true)
        v.interacted()
        assertEquals(listOf("opened"), writes)
    }

    /** What ReportViewModel is to ReportVisit: its holder, kept across a rotation. */
    class Holder : ViewModel() {
        lateinit var visit: ReportVisit
    }

    @Test
    fun aRotationKeepsTheVisitAProcessRestartStartsUncounted() {
        // A rotation recreates the screen, not its ViewModel store: the same visit, still counted.
        val store = ViewModelStore()
        val first = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[Holder::class.java]
        first.visit = visit(fromLink = true)
        first.visit.interacted()
        val afterRotation = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[Holder::class.java]
        assertSame(first, afterRotation)
        assertTrue(afterRotation.visit.counts)
        afterRotation.visit.interacted()
        assertEquals(listOf("opened"), writes)
        // After the process is killed, the route still says "from a link": uncounted until the reader acts.
        val restarted = visit(fromLink = true)
        assertFalse(restarted.counts)
        restarted.reached(place(1, 10))
        assertEquals(listOf("opened"), writes)
        store.clear()
    }
}
