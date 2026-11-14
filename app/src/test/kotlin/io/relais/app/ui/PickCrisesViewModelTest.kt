// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.AppContainer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Follow a crisis" opens (review of PR 19): the view model is built on the main thread, where
 * viewModelScope runs Main.immediate, so loadTopics() starts inside the constructor. With its
 * state declared after init, that crashed the app before any request. Without an appname the
 * topics cannot load: their section shows its error, and nothing crashes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PickCrisesViewModelTest {
    @Test
    fun thePickerBuildsAndItsRegionalCrisesFailAlone() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        val vm = PickCrisesViewModel(container)
        val deadline = System.currentTimeMillis() + 10_000
        while (!vm.topicsFailed.value && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("The regional crises should report their own failure", vm.topicsFailed.value)
        assertNull(vm.topics.value)
    }
}
