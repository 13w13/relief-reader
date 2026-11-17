// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether a visit of a report counts: in History, marked read, its reading place kept (review of
 * PR 47). A report the reader opens in Re.L counts at once. One opened from a link another app
 * sent counts only from the reader's first touch, key or scroll in it, so an app sending links
 * cannot fill History or mark titles read. The place reached before that is kept and written at
 * that moment: a short report that fits on the screen, only touched, is still read to its end.
 *
 * ReportViewModel holds it, so a rotation keeps it as it is. After the process is killed, a
 * report from a link starts again uncounted.
 */
internal class ReportVisit(
    fromLink: Boolean,
    private val scope: CoroutineScope,
    private val opened: suspend () -> Unit,
    private val save: suspend (Place) -> Unit,
) {
    data class Place(val index: Int, val offset: Int, val percent: Int, val finished: Boolean)

    var counts: Boolean = !fromLink
        private set

    /** The last place reached while the visit did not count yet. */
    private var waiting: Place? = null

    /** The report loads (or loads again): History and "read", unless it came from a link. */
    suspend fun loaded() {
        if (counts) opened()
    }

    /** The reader's first touch, key or scroll: from a link, the visit counts from now. */
    fun interacted() {
        if (counts) return
        counts = true
        val place = waiting
        waiting = null
        scope.launch {
            opened()
            place?.let { save(it) }
        }
    }

    /** The place the reader reached: written when the visit counts, kept for later otherwise. */
    fun reached(place: Place) {
        if (!counts) {
            waiting = place
            return
        }
        scope.launch { save(place) }
    }
}

/**
 * The reader's own input in a report, for [onInput]: the first touch of each gesture (a tap, a
 * drag) and each key (a keyboard, a D-pad). It takes nothing from the content.
 */
internal fun Modifier.readerInput(onInput: () -> Unit): Modifier = this
    .pointerInput(onInput) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onInput()
        }
    }
    .onPreviewKeyEvent {
        onInput()
        false
    }

/**
 * Calls [onScroll] at the first move of [listState] once [positioned], that is once the app's
 * own scroll (to a saved place, to a highlight) is done. From then, a move is the reader's: a
 * drag, a key, a D-pad, or TalkBack's scroll actions and its moves to a line off the screen,
 * which touch nothing.
 */
@Composable
internal fun OnReaderScroll(listState: LazyListState, positioned: Boolean, onScroll: () -> Unit) {
    val current by rememberUpdatedState(onScroll)
    LaunchedEffect(listState, positioned) {
        if (!positioned) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.drop(1).first()
        current()
    }
}
