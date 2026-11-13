// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.RelaisJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * The app's tuning: sync and cache times, features on or off, a minimum version. It carries the
 * call reductions of T16 (budget-appels.md). Planned as a hosted config.json (T18), dropped with
 * the decision of 13/11 to host nothing; [parse] stays for a file someone may host one day.
 */
@Serializable
data class RemoteConfig(
    val schema: Int = SCHEMA,
    val sync: SyncConfig = SyncConfig(),
    val cacheHours: CacheConfig = CacheConfig(),
    val features: Features = Features(),
    /** Below this versionCode, Today asks to update ([update]). */
    val minVersionCode: Int = 0,
    val update: UpdateInfo = UpdateInfo(),
) {
    @Serializable
    data class SyncConfig(
        /** Background sync every so many hours, skipped when the app synced in the last 3 hours. */
        val backgroundHours: Int = 12,
        /** Random delay so that phones do not all wake at the same minute. */
        val jitterMinutes: Int = 90,
        /** Opening Today syncs when the last sync is older than this. */
        val openMinutes: Int = 30,
        /** A pull within this time of the last check says "Up to date" without loading. */
        val pullMinutes: Int = 5,
    )

    @Serializable
    data class CacheConfig(
        val mostCovered: Int = 24,
        val newDisasters: Int = 24,
        val mostRead: Int = 24,
        val countryFeed: Int = 2,
        val overview: Int = 6,
    )

    @Serializable
    data class Features(
        val mostRead: Boolean = true,
        val latestReports: Boolean = true,
        val alerts: Boolean = true,
    )

    @Serializable
    data class UpdateInfo(val message: String? = null, val url: String? = null)

    /** The same values, each within its bounds. */
    fun bounded(): RemoteConfig = copy(
        sync = SyncConfig(
            backgroundHours = sync.backgroundHours.coerceIn(3, 48),
            jitterMinutes = sync.jitterMinutes.coerceIn(0, 180),
            openMinutes = sync.openMinutes.coerceIn(5, 360),
            pullMinutes = sync.pullMinutes.coerceIn(1, 60),
        ),
        cacheHours = CacheConfig(
            mostCovered = cacheHours.mostCovered.coerceIn(1, 72),
            newDisasters = cacheHours.newDisasters.coerceIn(1, 72),
            mostRead = cacheHours.mostRead.coerceIn(6, 72),
            countryFeed = cacheHours.countryFeed.coerceIn(1, 24),
            overview = cacheHours.overview.coerceIn(1, 48),
        ),
        minVersionCode = minVersionCode.coerceAtLeast(0),
    )

    val backgroundMillis: Long get() = sync.backgroundHours * Repository.ONE_HOUR
    val openMillis: Long get() = sync.openMinutes * 60_000L
    val pullMillis: Long get() = sync.pullMinutes * 60_000L
    val countryFeedMillis: Long get() = cacheHours.countryFeed * Repository.ONE_HOUR
    val overviewMillis: Long get() = cacheHours.overview * Repository.ONE_HOUR

    companion object {
        const val SCHEMA = 1

        /** The file's text as a config: null when unreadable or of a newer schema. */
        fun parse(text: String): RemoteConfig? = runCatching {
            RelaisJson.decodeFromString(serializer(), text).takeIf { it.schema <= SCHEMA }?.bounded()
        }.getOrNull()
    }
}

/**
 * Holds the tuning. Founder's decision of 13/11 (PLAN.md 0octies, "without maintenance"): each
 * reader brings their own appname and nothing is hosted, so no file is read; the built-in values
 * apply. The class stays as the one place where these times live.
 */
class RemoteConfigStore(initial: RemoteConfig = RemoteConfig()) {
    private val _config = MutableStateFlow(initial.bounded())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()

    /** The current values, for code outside a flow. */
    val current: RemoteConfig get() = _config.value
}
