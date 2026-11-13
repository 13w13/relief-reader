// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.relais.app.ui.MainScreen
import io.relais.app.ui.OnboardingFlow
import io.relais.app.ui.ConnectScreen
import io.relais.app.data.needsConnect
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.DisplaySize

class MainActivity : ComponentActivity() {
    companion object {
        /** A report to open (an alert), or [OPEN_FOLLOWING] for Following (the grouped alert). */
        const val EXTRA_OPEN = "io.relais.app.open"
        const val OPEN_FOLLOWING = -1L
    }

    private fun handle(intent: Intent?) {
        val open = intent?.getLongExtra(EXTRA_OPEN, 0L) ?: 0L
        if (open != 0L) container.openRequest.value = open
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val settingsFlow = container.settings.flow
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            RelaisTheme(display = DisplaySize.of(settings?.display)) {
                // testTagsAsResourceId exposes the test tags as resource ids, for the Maestro flows in .maestro/.
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val current = settings
                    when {
                        current == null -> Box(Modifier.fillMaxSize())
                        !current.onboardingDone -> OnboardingFlow()
                        needsConnect(current, BuildConfig.RELIEFWEB_APPNAME) -> ConnectScreen()
                        else -> MainScreen()
                    }
                }
            }
        }
    }
}
