// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.UiModeManager

/**
 * "Appearance" (Settings > Reading, founder, 15/11): follow the phone, or always light, or always
 * dark. Stored by name; an unknown or missing name follows the phone.
 */
enum class Appearance {
    SYSTEM, LIGHT, DARK;

    /** Whether the app is dark with this choice, when the phone itself is [systemDark]. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    /**
     * The night mode Android keeps for the app (UiModeManager.setApplicationNightMode, Android 12
     * and later). The system then draws the launch window in that mode, so a reader who chose Dark
     * on a light phone sees no white flash at launch.
     */
    val nightMode: Int get() = when (this) {
        SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        LIGHT -> UiModeManager.MODE_NIGHT_NO
        DARK -> UiModeManager.MODE_NIGHT_YES
    }

    companion object {
        fun of(name: String?): Appearance = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
