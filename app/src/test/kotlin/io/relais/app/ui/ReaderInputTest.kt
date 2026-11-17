// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What counts as the reader acting in a report (review of PR 47): a touch, a key, and any move of
 * the list once the app's own positioning is done, which covers a keyboard, a D-pad and
 * TalkBack's scroll actions. The app's scroll to a saved place does not count.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ReaderInputTest {
    @get:Rule
    val compose = createComposeRule()

    private var inputs = 0
    private var scrolls = 0
    private var positioned by mutableStateOf(false)
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private val focus = FocusRequester()

    private fun show() = compose.setContent {
        list = rememberLazyListState()
        scope = rememberCoroutineScope()
        OnReaderScroll(list, positioned) { scrolls++ }
        Column(Modifier.fillMaxSize().readerInput { inputs++ }) {
            Box(Modifier.size(10.dp).focusRequester(focus).focusable().testTag("focus"))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("list")) {
                items(200) { Text("Line $it", Modifier.height(40.dp)) }
            }
        }
    }

    @Test
    fun theAppsOwnScrollIsNotTheReaders() {
        show()
        // Back to a saved place, before the list is "positioned".
        compose.runOnIdle { scope.launch { list.scrollToItem(50) } }
        compose.waitForIdle()
        compose.runOnIdle { positioned = true }
        compose.waitForIdle()
        assertEquals(50, list.firstVisibleItemIndex)
        assertEquals(0, scrolls)
        assertEquals(0, inputs)
    }

    @Test
    fun aScrollWithoutATouchCounts() {
        show()
        compose.runOnIdle { positioned = true }
        compose.waitForIdle()
        // TalkBack's or a keyboard's scroll: a semantic action, no pointer.
        compose.onNodeWithTag("list").performScrollToIndex(120)
        compose.waitForIdle()
        assertEquals(1, scrolls)
        assertEquals(0, inputs)
    }

    @Test
    fun talkBacksScrollForwardCounts() {
        show()
        compose.runOnIdle { positioned = true }
        compose.waitForIdle()
        compose.onNodeWithTag("list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.waitForIdle()
        assertEquals(1, scrolls)
        assertEquals(0, inputs)
    }

    @Test
    fun aTouchCounts() {
        show()
        compose.onNodeWithTag("list").performTouchInput {
            down(center)
            up()
        }
        assertTrue(inputs > 0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aKeyCounts() {
        show()
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithTag("focus").assertIsFocused()
        compose.onNodeWithTag("focus").performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(inputs > 0)
    }
}
