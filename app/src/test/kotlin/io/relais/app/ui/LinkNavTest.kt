// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Links from other apps and the back stack (review of PR 47, point 4): a new link takes the place
 * of the screen the last link opened (its link screen or its report), instead of piling up.
 * Made-up addresses and ids.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LinkNavTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: NavHostController
    private lateinit var nav: Nav

    private val a = "https://reliefweb.int/report/sudan/sudan-test-flash-update-990000301"
    private val b = "https://reliefweb.int/report/chad/chad-test-flash-update-990000302"

    private fun host() {
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                controller = rememberNavController()
                nav = Nav(controller)
                NavHost(controller, startDestination = TodayRoute) {
                    composable<TodayRoute> { Text("Today") }
                    composable<ReportRoute> { Text("Report") }
                    composable<LinkRoute> { Text("Link") }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(): NavBackStackEntry = controller.currentBackStackEntry!!
    private fun below(): NavBackStackEntry = controller.previousBackStackEntry!!
    private fun NavBackStackEntry.isToday() = destination.hasRoute<TodayRoute>()

    @Test
    fun aNewLinkTakesThePlaceOfTheLastLinksScreen() {
        host()
        compose.runOnIdle { nav.link(a) }
        compose.runOnIdle { nav.reportFromLink(1) }
        compose.runOnIdle {
            assertEquals(ReportRoute(1, fromLink = true), top().toRoute<ReportRoute>())
            assertTrue(below().isToday())
            // A second link while the first one's report is open.
            nav.link(b)
        }
        compose.runOnIdle {
            assertEquals(LinkRoute(b), top().toRoute<LinkRoute>())
            assertTrue(below().isToday())
            nav.reportFromLink(2)
        }
        compose.runOnIdle {
            assertEquals(ReportRoute(2, fromLink = true), top().toRoute<ReportRoute>())
            assertTrue(below().isToday())
            // Two links before either opens: the second takes the first one's place.
            nav.link(a)
        }
        compose.runOnIdle { nav.link(b) }
        compose.runOnIdle {
            assertEquals(LinkRoute(b), top().toRoute<LinkRoute>())
            assertTrue(below().isToday())
            // The same link again: still one screen.
            nav.link(b)
        }
        compose.runOnIdle {
            assertEquals(LinkRoute(b), top().toRoute<LinkRoute>())
            assertTrue(below().isToday())
            controller.popBackStack()
        }
        compose.runOnIdle { assertTrue(top().isToday()) }
    }

    @Test
    fun aLinkAfterTheReaderMovedOnKeepsWhereTheyWere() {
        host()
        compose.runOnIdle { nav.link(a) }
        compose.runOnIdle { nav.reportFromLink(1) }
        // The reader opens another report from there: theirs, not the link's.
        compose.runOnIdle { nav.report(2) }
        compose.runOnIdle { nav.link(b) }
        compose.runOnIdle {
            assertEquals(LinkRoute(b), top().toRoute<LinkRoute>())
            assertEquals(ReportRoute(2), below().toRoute<ReportRoute>())
            controller.popBackStack()
        }
        compose.runOnIdle {
            assertEquals(ReportRoute(2), top().toRoute<ReportRoute>())
            controller.popBackStack()
        }
        compose.runOnIdle {
            assertEquals(ReportRoute(1, fromLink = true), top().toRoute<ReportRoute>())
            assertTrue(below().isToday())
        }
    }
}
