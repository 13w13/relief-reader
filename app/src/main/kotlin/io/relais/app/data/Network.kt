// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** The phone's network as the lists see it: online or not, and on mobile data or not. */
data class NetState(val online: Boolean = true, val metered: Boolean = false)

/**
 * Follows the default network (lot 3, C): "Load images: Wi-Fi only" and the rows dimmed
 * offline read it. No request is made; Android tells.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun now(): NetState {
        val m = manager ?: return NetState()
        val caps = m.getNetworkCapabilities(m.activeNetwork) ?: return NetState(online = false, metered = false)
        return NetState(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    val state: StateFlow<NetState> = callbackFlow {
        val m = manager
        if (m == null) {
            trySend(NetState())
            awaitClose {}
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(now()) }
            override fun onLost(network: Network) { trySend(now()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(now()) }
        }
        trySend(now())
        runCatching { m.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { m.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), now())
}

/** "Load images" (Settings > Reading): ALWAYS, WIFI or NEVER, Always by default. */
enum class LoadImages { ALWAYS, WIFI, NEVER;
    companion object {
        fun of(name: String?): LoadImages = entries.firstOrNull { it.name == name } ?: ALWAYS
    }
}

/** Whether the photos of the pile and the images of reports load now. */
fun imagesAllowed(mode: LoadImages, net: NetState): Boolean = when (mode) {
    LoadImages.ALWAYS -> true
    LoadImages.WIFI -> !net.metered
    LoadImages.NEVER -> false
}

/**
 * Offline, a report that has neither its text nor a PDF on the phone cannot be read: its row
 * shows in the secondary colour (lot 3, C). A headline of the feed keeps its summary: not dimmed.
 */
fun unreadableOffline(report: ReportEntity, offline: Boolean, pdfsOnPhone: Set<String>): Boolean =
    offline && !report.fromRss && report.bodyHtml.isNullOrEmpty() && report.files.none { it.url in pdfsOnPhone }
