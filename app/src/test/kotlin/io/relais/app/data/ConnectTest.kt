// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When "Connect to ReliefWeb" comes back after the onboarding (v0.3.1): only for a phone that
 * updates from an APK with the built-in appname to the public one, and has none of its own.
 */
class ConnectTest {
    private val updated = Settings(onboardingDone = true, connectAsked = false, userAppname = null)

    @Test
    fun anUpdateToThePublicApkAsksOnce() {
        assertTrue(needsConnect(updated, builtInAppname = ""))
        // Answered: with an appname or "Continue with headlines only".
        assertFalse(needsConnect(updated.copy(connectAsked = true), builtInAppname = ""))
    }

    @Test
    fun noQuestionWhenThePhoneCanReachReliefWeb() {
        assertFalse(needsConnect(updated, builtInAppname = "built-in"))
        assertFalse(needsConnect(updated.copy(userAppname = "own"), builtInAppname = ""))
    }

    @Test
    fun theOnboardingAsksItself() {
        assertFalse(needsConnect(updated.copy(onboardingDone = false), builtInAppname = ""))
    }
}
