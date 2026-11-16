// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.LaunchedEffect
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
import io.relais.app.ui.TourScreen
import io.relais.app.data.showsTour
import io.relais.app.ui.ConnectScreen
import io.relais.app.data.needsConnect
import io.relais.app.ui.RelaisTheme
import io.relais.app.ui.DisplaySize
import io.relais.app.ui.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** A report to open (an alert), or [OPEN_FOLLOWING] for Following (the grouped alert). */
        const val EXTRA_OPEN = "io.relais.app.open"
        const val OPEN_FOLLOWING = -1L

        // The scrims of enableEdgeToEdge's defaults, for three-button navigation.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        // The window under Compose: res/values(-night)/colors.xml, window_background.
        private const val LIGHT_WINDOW = 0xFFF6F7F9.toInt()
        private const val DARK_WINDOW = 0xFF121316.toInt()
    }

    private fun handle(intent: Intent?) {
        val open = intent?.getLongExtra(EXTRA_OPEN, 0L) ?: 0L
        if (open != 0L) container.openRequest.value = open
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Each time the app comes to the foreground: the check of a new version, when it is due (at most once an hour). */
    override fun onStart() {
        super.onStart()
        // Silent: an error of the settings store must not reach the app.
        container.scope.launch { runCatching { container.updateCheck.checkIfDue() } }
        // Back from the share menu, or any return: exports shared a while ago go (Exports).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { io.relais.app.data.Exports.clearOld(this@MainActivity) } }
    }

    /** Whether the phone itself is in dark mode (with Android 12's per-app night mode, the app's). */
    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * The window under Compose and the system bars, for a light or dark app: the status bar icons
     * stay readable both ways, whatever the phone's own mode ("Appearance", v0.6).
     */
    private fun applyWindow(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM),
        )
        window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_WINDOW else LIGHT_WINDOW))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame: the reader's Appearance, read at launch (AppContainer).
        applyWindow(container.appearance.value.isDark(systemDark()))
        if (savedInstanceState == null) handle(intent)
        val settingsFlow = container.settings.flow
        val appearanceFlow = container.appearance
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val appearance by appearanceFlow.collectAsStateWithLifecycle()
            val dark = isDark(appearance)
            LaunchedEffect(dark) { applyWindow(dark) }
            RelaisTheme(darkTheme = dark, display = DisplaySize.of(settings?.display)) {
                // testTagsAsResourceId exposes the test tags as resource ids, for the Maestro flows in .maestro/.
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val current = settings
                    when {
                        current == null -> Box(Modifier.fillMaxSize())
                        !current.onboardingDone -> OnboardingFlow()
                        // The tour (issue 37): once, right after the first launch setup, before Today.
                        current.showsTour -> TourScreen(onDone = { container.scope.launch { runCatching { container.settings.setTourSeen() } } })
                        needsConnect(current, BuildConfig.RELIEFWEB_APPNAME) -> ConnectScreen()
                        else -> MainScreen()
                    }
                }
            }
        }
    }
}
