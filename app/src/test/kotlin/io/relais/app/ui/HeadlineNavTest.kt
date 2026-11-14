// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.ReportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** How a headline opens, moves on and comes back (review of PR 12, points 1 and 2). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HeadlineNavTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: NavHostController
    private lateinit var nav: Nav

    private fun host(older: @Composable () -> Unit = {}) {
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                controller = rememberNavController()
                nav = Nav(controller)
                NavHost(controller, startDestination = TodayRoute) {
                    composable<TodayRoute> { Text("Today") }
                    composable<ReportRoute> { Text("Report") }
                    composable<OlderHeadlinesRoute> { older() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun onToday() = controller.currentBackStackEntry?.destination?.hasRoute<TodayRoute>() == true

    private fun report(): ReportRoute = controller.currentBackStackEntry!!.toRoute<ReportRoute>()

    private fun headline(id: Long) = ReportEntity(
        id = id, title = "Noravia: Headline $id", headlineTitle = "Noravia: Headline $id", headlineSummary = null,
        imageUrl = null, imageCaption = null, imageCopyright = null, urlAlias = null, origin = null,
        dateCreated = 1794614400000L, dateOriginal = null, primaryCountryId = null, primaryCountryName = "Noravia",
        countries = emptyList(), sources = emptyList(), formatId = 8, formatName = "News and Press Release",
        themes = emptyList(), languages = listOf("en"), files = emptyList(), bodyHtml = null,
        isHeadline = true, fromRss = true, fetchedAt = 1794614400000L,
    )

    @Test
    fun aDoubleTapOnACardStacksTheReportOnce() {
        host()
        compose.runOnIdle {
            nav.headline(1)
            nav.headline(1)
        }
        compose.runOnIdle {
            assertEquals(1L, report().id)
            controller.popBackStack()
        }
        compose.runOnIdle { assertTrue("Back should land on Today", onToday()) }
    }

    @Test
    fun nextReplacesTheReportAndBackReturnsToToday() {
        host()
        compose.runOnIdle { nav.headline(1) }
        compose.runOnIdle {
            nav.swapHeadline(2)
            nav.swapHeadline(2)
        }
        compose.runOnIdle {
            assertEquals(2L, report().id)
            assertTrue(report().headline)
            controller.popBackStack()
        }
        compose.runOnIdle { assertTrue("Back after Next should land on Today", onToday()) }
    }

    @Test
    fun aHeadlineOpenedFromOlderHeadlinesIsAPlainReport() {
        val list = listOf(headline(7), headline(8))
        host(older = { OlderHeadlinesList(list, read = emptySet(), onOpen = { nav.report(it) }) })
        compose.runOnIdle { nav.olderHeadlines(hasAccess = false) }
        compose.onNodeWithTag("doc_8").performClick()
        compose.runOnIdle {
            assertEquals(8L, report().id)
            // No headline flag: the report notes no focus, and Today keeps its card.
            assertFalse(report().headline)
        }
    }
}
