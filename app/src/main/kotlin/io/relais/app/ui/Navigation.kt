// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.relais.app.BuildConfig
import io.relais.app.container
import io.relais.app.pdf.LocalPdfPreviews
import io.relais.app.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object TodayRoute
@Serializable object FollowingRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute
// `headline` marks a report opened from the pile of Today: it ends with "Headline 3 of 6" (v0.4).
@Serializable data class ReportRoute(val id: Long, val block: Int = -1, val headline: Boolean = false)
// page, block and query open the reader on a highlight or a search result (pdf/, T9).
@Serializable data class PdfRoute(
    val url: String,
    val reportId: Long,
    val title: String,
    val sourceUrl: String? = null,
    val page: Int = -1,
    val block: Int = -1,
    val query: String? = null,
)
// `name` has a default so that the deep link relais://country/{id} works with the id alone.
// `filter` names a PageFilter to open on (Most read from its screen); All otherwise.
@Serializable data class CountryRoute(val id: Long, val name: String = "", val filter: String = "")
@Serializable data class CrisisRoute(val id: Long, val name: String = "", val filter: String = "")
// The overview of a country, opened from its page; `since` is the page's last visit, for the dots.
@Serializable data class OverviewRoute(val id: Long, val name: String, val since: Long = Long.MAX_VALUE)
@Serializable data class SourceRoute(val id: Long, val name: String = "", val filter: String = "")
/** A regional crisis (ReliefWeb topic, lot 3). */
@Serializable data class TopicRoute(val id: Long, val title: String = "")
@Serializable object PickSourcesRoute
@Serializable object LicencesRoute
@Serializable object MostReadRoute
@Serializable object ManageFollowingRoute
@Serializable object PickCrisesRoute
@Serializable data class ResultsRoute(val query: String, val headlinesOnly: Boolean = false, val reportsOnly: Boolean = false)
@Serializable object PickCountriesRoute
/** The headlines of ReliefWeb's feed on the phone, for a reader without an appname (v0.4). */
@Serializable object OlderHeadlinesRoute

/** [tag] is the test tag of the bottom bar item, read by the Maestro flows in .maestro/. */
private data class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val tag: String)

private val TABS = listOf(
    Tab(TodayRoute, TodayRoute::class, R.string.tab_today, Icons.AutoMirrored.Rounded.Article, "tab_today"),
    Tab(FollowingRoute, FollowingRoute::class, R.string.tab_following, Icons.Rounded.Public, "tab_following"),
    Tab(SearchRoute, SearchRoute::class, R.string.tab_search, Icons.Rounded.Search, "tab_search"),
    Tab(LibraryRoute, LibraryRoute::class, R.string.tab_library, Icons.Rounded.Bookmarks, "tab_library"),
)

/** Whether the back stack holds the route [T]. */
private inline fun <reified T : Any> NavHostController.holds(): Boolean = runCatching { getBackStackEntry<T>() }.isSuccess

/** The bottom bar of the four tabs; also drawn by the README screenshots. */
@Composable
internal fun MainBottomBar(isSelected: (KClass<*>) -> Boolean, onTab: (route: Any, selected: Boolean) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TABS.forEach { tab ->
            val selected = isSelected(tab.routeClass)
            NavigationBarItem(
                selected = selected,
                onClick = { onTab(tab.route, selected) },
                icon = { Icon(tab.icon, contentDescription = null) },
                modifier = Modifier.testTag(tab.tag),
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

/** Actions shared by every screen. */
class Nav(private val controller: NavHostController) {
    fun back() = controller.popBackStack()
    fun report(id: Long, block: Int = -1) = controller.navigate(ReportRoute(id, block))

    /**
     * A headline of the pile, whose report ends with its place and the next one. Single top: a
     * double tap on a card stacks the report once (review of PR 12).
     */
    fun headline(id: Long) = controller.navigate(ReportRoute(id, headline = true)) { launchSingleTop = true }

    /** Next or Previous at the end of a headline: it replaces the report open, without stacking. */
    fun swapHeadline(id: Long) = controller.navigate(ReportRoute(id, headline = true)) {
        popUpTo<ReportRoute> { inclusive = true }
        launchSingleTop = true
    }

    /** After the pile: the search of headlines by date with an appname, the feed's ones without. */
    fun olderHeadlines(hasAccess: Boolean) =
        if (hasAccess) results("", headlinesOnly = true) else controller.navigate(OlderHeadlinesRoute) { launchSingleTop = true }
    fun pdf(url: String, reportId: Long, title: String, sourceUrl: String?, page: Int = -1, block: Int = -1, query: String? = null) =
        controller.navigate(PdfRoute(url, reportId, title, sourceUrl, page, block, query))
    fun country(id: Long, name: String, filter: PageFilter? = null) = controller.navigate(CountryRoute(id, name, filter?.name.orEmpty()))
    fun crisis(id: Long, name: String, filter: PageFilter? = null) = controller.navigate(CrisisRoute(id, name, filter?.name.orEmpty()))
    fun source(id: Long, name: String, filter: PageFilter? = null) = controller.navigate(SourceRoute(id, name, filter?.name.orEmpty()))
    fun topic(id: Long, title: String) = controller.navigate(TopicRoute(id, title))
    fun pickSources() = controller.navigate(PickSourcesRoute) { launchSingleTop = true }
    fun licences() = controller.navigate(LicencesRoute) { launchSingleTop = true }
    fun overview(id: Long, name: String, since: Long) = controller.navigate(OverviewRoute(id, name, since))
    fun mostRead() = controller.navigate(MostReadRoute) { launchSingleTop = true }
    fun manageFollowing() = controller.navigate(ManageFollowingRoute) { launchSingleTop = true }
    fun pickCrises() = controller.navigate(PickCrisesRoute) { launchSingleTop = true }
    fun results(query: String, headlinesOnly: Boolean = false, reportsOnly: Boolean = false) =
        controller.navigate(ResultsRoute(query, headlinesOnly, reportsOnly))
    fun settings() = controller.navigate(SettingsRoute) { launchSingleTop = true }
    fun pickCountries() = controller.navigate(PickCountriesRoute) { launchSingleTop = true }

    /** Back to the first screen of a tab, dropping what was opened on top of it. */
    fun toRoot(route: Any) {
        if (!controller.popBackStack(route, inclusive = false)) tab(route)
    }

    fun tab(route: Any) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Four tabs at the bottom (ecrans.md 0.1), each keeping its own stack. */
@Composable
fun MainScreen() {
    val controller = rememberNavController()
    val nav = Nav(controller)
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination
    // The bottom bar keeps the tab a page was opened from lit (revue-densite-v0.3.md, section 2):
    // Today is the start, always at the bottom of the stack; another tab sits right above it.
    val originTab: KClass<*> = remember(entry) {
        when {
            controller.holds<FollowingRoute>() -> FollowingRoute::class
            controller.holds<SearchRoute>() -> SearchRoute::class
            controller.holds<LibraryRoute>() -> LibraryRoute::class
            else -> TodayRoute::class
        }
    }

    // The bottom bar is hidden while reading a report or a PDF (theme-compose.md, 5.6).
    val reading = destination?.hasRoute(ReportRoute::class) == true || destination?.hasRoute(PdfRoute::class) == true
    // First lines of the PDFs read on the phone, for the rows of reports without text (pdf/).
    val pdfPreviews by LocalContext.current.container.pdfText.previews.collectAsStateWithLifecycle(emptyMap())

    // A tapped alert opens its report; the grouped one opens Following.
    val container = androidx.compose.ui.platform.LocalContext.current.container
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.openRequest.collect { open ->
            if (open == null) return@collect
            if (open == io.relais.app.MainActivity.OPEN_FOLLOWING) nav.tab(FollowingRoute) else nav.report(open)
            container.openRequest.value = null
        }
    }

    // "Load images" with the network, and what can be read offline (lot 3, C).
    val net by container.network.state.collectAsStateWithLifecycle()
    val loadImages by remember { container.settings.flow.map { io.relais.app.data.LoadImages.of(it.loadImages) } }
        .collectAsStateWithLifecycle(io.relais.app.data.LoadImages.ALWAYS)
    val pdfsOnPhone by remember { container.pdfs.downloaded.map { it.keys } }.collectAsStateWithLifecycle(emptySet())
    CompositionLocalProvider(
        LocalPdfPreviews provides pdfPreviews,
        LocalImagesAllowed provides io.relais.app.data.imagesAllowed(loadImages, net),
        LocalOffline provides !net.online,
        LocalPdfsOnPhone provides pdfsOnPhone,
    ) {
    Scaffold(
        // The top bars of each screen handle the status bar; the navigation bar handles the bottom.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!reading) {
                MainBottomBar(
                    isSelected = { route -> route == originTab },
                    // Tapped again, the current tab goes back to its root (revue-lecteur-v0.2.md, defect 8).
                    onTab = { route, selected -> if (selected) nav.toRoot(route) else nav.tab(route) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = controller, startDestination = TodayRoute) {
                composable<TodayRoute> { TodayScreen(nav) }
                composable<FollowingRoute> { FollowingScreen(nav) }
                composable<SearchRoute> { SearchScreen(nav) }
                composable<LibraryRoute> { LibraryScreen(nav) }
                composable<SettingsRoute> { SettingsScreen(nav) }
                composable<OlderHeadlinesRoute> { OlderHeadlinesScreen(nav) }
                composable<PickCountriesRoute> { PickCountriesScreen(onDone = { nav.back() }, onBack = { nav.back() }) }
                // Deep links relais://report/{id} and relais://country/{id}, used by the Maestro flows.
                composable<ReportRoute>(deepLinks = if (BuildConfig.DEBUG) listOf(navDeepLink<ReportRoute>(basePath = "relais://report")) else emptyList()) {
                    val route = it.toRoute<ReportRoute>()
                    ReportScreen(route.id, route.block, nav, fromHeadline = route.headline)
                }
                composable<PdfRoute> {
                    val route = it.toRoute<PdfRoute>()
                    PdfViewerScreen(route.url, route.reportId, route.title, route.sourceUrl, nav, route.page, route.block, route.query)
                }
                composable<CountryRoute>(deepLinks = if (BuildConfig.DEBUG) listOf(navDeepLink<CountryRoute>(basePath = "relais://country")) else emptyList()) {
                    val route = it.toRoute<CountryRoute>()
                    // Opened by a deep link, the name is empty: the top bar then shows no title.
                    CountryScreen(route.id, route.name, route.filter, nav)
                }
                composable<OverviewRoute> {
                    val route = it.toRoute<OverviewRoute>()
                    OverviewScreen(route.id, route.name, route.since, nav)
                }
                composable<MostReadRoute> { MostReadScreen(nav) }
                composable<SourceRoute>(deepLinks = if (BuildConfig.DEBUG) listOf(navDeepLink<SourceRoute>(basePath = "relais://source")) else emptyList()) {
                    val route = it.toRoute<SourceRoute>()
                    SourceScreen(route.id, route.name, route.filter, nav)
                }
                composable<PickSourcesRoute> { PickSourcesScreen(nav) }
                composable<TopicRoute> {
                    val route = it.toRoute<TopicRoute>()
                    TopicScreen(route.id, route.title, nav)
                }
                composable<LicencesRoute> { LicencesScreen(nav) }
                composable<ManageFollowingRoute> { ManageFollowingScreen(nav) }
                composable<CrisisRoute>(deepLinks = if (BuildConfig.DEBUG) listOf(navDeepLink<CrisisRoute>(basePath = "relais://crisis")) else emptyList()) {
                    val route = it.toRoute<CrisisRoute>()
                    CrisisScreen(route.id, route.name, route.filter, nav)
                }
                composable<PickCrisesRoute> { PickCrisesScreen(nav) }
                composable<ResultsRoute> {
                    val route = it.toRoute<ResultsRoute>()
                    ResultsScreen(route.query, route.headlinesOnly, nav, route.reportsOnly)
                }
            }
        }
    }
    }
}
