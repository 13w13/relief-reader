// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.relais.api.CountryFilter
import io.relais.api.RelaisJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val onboardingDone: Boolean = false,
    /** Appname entered on the phone. Never displayed again, never logged. */
    val userAppname: String? = null,
    val digestEnabled: Boolean = false,
    val digestHour: Int = 8,
    val digestMinute: Int = 0,
    /** -1 means forever. */
    val historyDays: Int = 365,
    val lastSyncAt: Long = 0,
    val lastHeadlinesRefreshAt: Long = 0,
    val lastDigestAt: Long = 0,
    val lastCleanupAt: Long = 0,
    val countriesFetchedAt: Long = 0,
    val blockedUntil: Long = 0,
    val accessDenied: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val rssBannerSeen: Boolean = false,
    /** The banner of a PDF's text view shows on its first opening only (v0.4). */
    val pdfTextBannerSeen: Boolean = false,
    /** Article text size, an index into TextStep (Theme.kt). */
    val readingSize: Int = DEFAULT_READING_SIZE,
    /** "Load images": ALWAYS, WIFI or NEVER (LoadImages), for the pile and the reports (lot 3, C). */
    val loadImages: String = "ALWAYS",
    /** "Display" (theme-compose.md 12.6): the name of a DisplaySize, Compact when unset. */
    val display: String? = null,
    /** Last opening of Following: its feed marks what was posted since (12.3). */
    val followingVisitAt: Long = 0,
    /** Today (P14): the last time it was on screen, and what counts as new during this visit. */
    val todaySeenAt: Long = 0,
    val todaySince: Long = 0,
    /** "Connect to ReliefWeb" was answered: in onboarding, or once after an update (v0.3.1). */
    val connectAsked: Boolean = false,
    /** The versionCode whose "What's new" was shown, or noted at a first install (13.4). */
    val whatsNewSeen: Int = 0,
    /** Times the "Select a passage" banner showed: twice at most (12.7). */
    val selectionTips: Int = 0,
    /** Pinned documents sent back to their list with "Hide" (produit/passe-v0.2.md, 3.1). */
    val hiddenPins: Set<Long> = emptySet(),
    /** "Alerts for new appeals and flash updates" (passe-v0.2.md, 6): off by default. */
    val alerts: Boolean = false,
    /** Alerts sent today (UTC day), for the caps: one per followed item, three in all. */
    val alertDay: String = "",
    val alertScopes: Set<String> = emptySet(),
    val alertCount: Int = 0,
    /** "Download PDFs on Wi-Fi": the PDFs of the newest key documents of what you follow (PreloadWorker). */
    val preloadPdfs: Boolean = true,
    /** The space for the PDFs of reports not saved, in MB (issue 6): one of PdfCache.CAPS_MB. */
    val pdfCapMb: Int = PdfCache.DEFAULT_CAP_MB,
)

/**
 * After the onboarding, "Connect to ReliefWeb" shows once more only for a phone with no appname
 * at all (none entered, none built in) that never answered it: an update from an APK with the
 * built-in appname to the public one (v0.3.1).
 */
fun needsConnect(s: Settings, builtInAppname: String): Boolean =
    s.onboardingDone && !s.connectAsked && s.userAppname.isNullOrBlank() && builtInAppname.isBlank()

/** Small: the report text step of the Compact display (12.6). */
const val DEFAULT_READING_SIZE = 1

/** What a country page remembers (produit/filtres-following.md 3.1): its tab and its filters. */
@Serializable
data class CountryPrefs(val tab: String? = null, val filter: CountryFilter = CountryFilter())

class SettingsStore(private val context: Context) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val userAppname = stringPreferencesKey("user_appname")
        val digestEnabled = booleanPreferencesKey("digest_enabled")
        val digestHour = intPreferencesKey("digest_hour")
        val digestMinute = intPreferencesKey("digest_minute")
        val historyDays = intPreferencesKey("history_days")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastHeadlinesRefreshAt = longPreferencesKey("last_headlines_refresh_at")
        val lastDigestAt = longPreferencesKey("last_digest_at")
        val lastCleanupAt = longPreferencesKey("last_cleanup_at")
        val countriesFetchedAt = longPreferencesKey("countries_fetched_at")
        val blockedUntil = longPreferencesKey("blocked_until")
        val accessDenied = booleanPreferencesKey("access_denied")
        val recentSearches = stringPreferencesKey("recent_searches")
        val rssBannerSeen = booleanPreferencesKey("rss_banner_seen")
        val pdfTextBannerSeen = booleanPreferencesKey("pdf_text_banner_seen")
        val readingSize = intPreferencesKey("reading_size")
        val loadImages = stringPreferencesKey("load_images")
        val display = stringPreferencesKey("display_size")
        val followingVisitAt = longPreferencesKey("following_visit_at")
        val todaySeenAt = longPreferencesKey("today_seen_at")
        val whatsNewSeen = intPreferencesKey("whats_new_seen")
        val connectAsked = booleanPreferencesKey("connect_asked")
        val todaySince = longPreferencesKey("today_since")
        val selectionTips = intPreferencesKey("selection_tips")
        val hiddenPins = stringSetPreferencesKey("hidden_pins")
        val alerts = booleanPreferencesKey("alerts")
        val alertDay = stringPreferencesKey("alert_day")
        val alertScopes = stringSetPreferencesKey("alert_scopes")
        val alertCount = intPreferencesKey("alert_count")
        val preloadPdfs = booleanPreferencesKey("preload_pdfs")
        val pdfCapMb = intPreferencesKey("pdf_cap_mb")
        fun country(id: Long) = stringPreferencesKey("country_$id")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboardingDone = p[Keys.onboardingDone] ?: false,
            userAppname = p[Keys.userAppname]?.takeIf { it.isNotBlank() },
            digestEnabled = p[Keys.digestEnabled] ?: false,
            digestHour = p[Keys.digestHour] ?: 8,
            digestMinute = p[Keys.digestMinute] ?: 0,
            historyDays = p[Keys.historyDays] ?: 365,
            lastSyncAt = p[Keys.lastSyncAt] ?: 0,
            lastHeadlinesRefreshAt = p[Keys.lastHeadlinesRefreshAt] ?: 0,
            lastDigestAt = p[Keys.lastDigestAt] ?: 0,
            lastCleanupAt = p[Keys.lastCleanupAt] ?: 0,
            countriesFetchedAt = p[Keys.countriesFetchedAt] ?: 0,
            blockedUntil = p[Keys.blockedUntil] ?: 0,
            accessDenied = p[Keys.accessDenied] ?: false,
            recentSearches = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            rssBannerSeen = p[Keys.rssBannerSeen] ?: false,
            pdfTextBannerSeen = p[Keys.pdfTextBannerSeen] ?: false,
            readingSize = p[Keys.readingSize] ?: DEFAULT_READING_SIZE,
            loadImages = p[Keys.loadImages] ?: "ALWAYS",
            display = p[Keys.display],
            followingVisitAt = p[Keys.followingVisitAt] ?: 0,
            todaySeenAt = p[Keys.todaySeenAt] ?: 0,
            whatsNewSeen = p[Keys.whatsNewSeen] ?: 0,
            connectAsked = p[Keys.connectAsked] ?: false,
            todaySince = p[Keys.todaySince] ?: 0,
            selectionTips = p[Keys.selectionTips] ?: 0,
            hiddenPins = p[Keys.hiddenPins].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            alerts = p[Keys.alerts] ?: false,
            alertDay = p[Keys.alertDay].orEmpty(),
            alertScopes = p[Keys.alertScopes].orEmpty(),
            alertCount = p[Keys.alertCount] ?: 0,
            preloadPdfs = p[Keys.preloadPdfs] ?: true,
            pdfCapMb = p[Keys.pdfCapMb] ?: PdfCache.DEFAULT_CAP_MB,
        )
    }

    fun countryPrefs(id: Long): Flow<CountryPrefs> = context.dataStore.data.map { p ->
        p[Keys.country(id)]?.let { runCatching { RelaisJson.decodeFromString(CountryPrefs.serializer(), it) }.getOrNull() } ?: CountryPrefs()
    }

    suspend fun setCountryPrefs(id: Long, prefs: CountryPrefs) = context.dataStore.edit {
        if (prefs == CountryPrefs()) it.remove(Keys.country(id))
        else it[Keys.country(id)] = RelaisJson.encodeToString(CountryPrefs.serializer(), prefs)
    }

    suspend fun current(): Settings = flow.first()

    suspend fun setOnboardingDone() = context.dataStore.edit {
        it[Keys.onboardingDone] = true
        it[Keys.connectAsked] = true
    }

    suspend fun setConnectAsked() = context.dataStore.edit { it[Keys.connectAsked] = true }

    suspend fun setUserAppname(value: String?) = context.dataStore.edit {
        if (value.isNullOrBlank()) it.remove(Keys.userAppname) else it[Keys.userAppname] = value.trim()
        it[Keys.accessDenied] = false
        it[Keys.blockedUntil] = 0
    }

    suspend fun setDigest(enabled: Boolean, hour: Int? = null, minute: Int? = null) = context.dataStore.edit {
        it[Keys.digestEnabled] = enabled
        if (hour != null) it[Keys.digestHour] = hour
        if (minute != null) it[Keys.digestMinute] = minute
    }

    suspend fun setHistoryDays(days: Int) = context.dataStore.edit { it[Keys.historyDays] = days }
    suspend fun setLastSyncAt(at: Long) = context.dataStore.edit { it[Keys.lastSyncAt] = at }
    suspend fun setLastHeadlinesRefreshAt(at: Long) = context.dataStore.edit { it[Keys.lastHeadlinesRefreshAt] = at }
    suspend fun setLastDigestAt(at: Long) = context.dataStore.edit { it[Keys.lastDigestAt] = at }
    suspend fun setLastCleanupAt(at: Long) = context.dataStore.edit { it[Keys.lastCleanupAt] = at }
    suspend fun setCountriesFetchedAt(at: Long) = context.dataStore.edit { it[Keys.countriesFetchedAt] = at }
    suspend fun setBlockedUntil(at: Long) = context.dataStore.edit { it[Keys.blockedUntil] = at }
    suspend fun setAccessDenied(denied: Boolean) = context.dataStore.edit { it[Keys.accessDenied] = denied }
    suspend fun setRssBannerSeen() = context.dataStore.edit { it[Keys.rssBannerSeen] = true }
    suspend fun setPdfTextBannerSeen() = context.dataStore.edit { it[Keys.pdfTextBannerSeen] = true }
    suspend fun setLoadImages(mode: String) = context.dataStore.edit { it[Keys.loadImages] = mode }
    suspend fun setReadingSize(index: Int) = context.dataStore.edit { it[Keys.readingSize] = index }

    /** A display level brings the report text to its starting step (12.6). */
    suspend fun setDisplay(name: String, readingSize: Int) = context.dataStore.edit {
        it[Keys.display] = name
        it[Keys.readingSize] = readingSize
    }

    suspend fun setFollowingVisitAt(at: Long) = context.dataStore.edit { it[Keys.followingVisitAt] = at }

    /**
     * Today comes on screen (P14): after more than 30 minutes away, a new visit starts, and what
     * was posted since the end of the last one is new. Within a visit, new stays new.
     */
    suspend fun startTodayVisit(now: Long, gap: Long) = context.dataStore.edit {
        val seen = it[Keys.todaySeenAt] ?: 0L
        if (now - seen > gap) it[Keys.todaySince] = if (seen > 0) seen else now - 24 * 3_600_000L
        it[Keys.todaySeenAt] = now
    }

    suspend fun setTodaySeenAt(at: Long) = context.dataStore.edit { it[Keys.todaySeenAt] = at }

    suspend fun setWhatsNewSeen(versionCode: Int) = context.dataStore.edit { it[Keys.whatsNewSeen] = versionCode }
    suspend fun countSelectionTip() = context.dataStore.edit { it[Keys.selectionTips] = (it[Keys.selectionTips] ?: 0) + 1 }
    suspend fun setAlerts(on: Boolean) = context.dataStore.edit { it[Keys.alerts] = on }
    suspend fun setPreloadPdfs(on: Boolean) = context.dataStore.edit { it[Keys.preloadPdfs] = on }
    suspend fun setPdfCapMb(mb: Int) = context.dataStore.edit { it[Keys.pdfCapMb] = mb }

    /** Keeps the 50 latest hidden pins: older ones have long left the 30-day window. */
    suspend fun hidePin(reportId: Long) = context.dataStore.edit { p ->
        p[Keys.hiddenPins] = (p[Keys.hiddenPins].orEmpty() + reportId.toString()).toList().takeLast(50).toSet()
    }

    /** Records the alerts sent today; a new day starts the count again. */
    suspend fun recordAlerts(day: String, scopes: Set<String>, count: Int) = context.dataStore.edit { p ->
        val sameDay = p[Keys.alertDay] == day
        p[Keys.alertDay] = day
        p[Keys.alertScopes] = (if (sameDay) p[Keys.alertScopes].orEmpty() else emptySet()) + scopes
        p[Keys.alertCount] = (if (sameDay) p[Keys.alertCount] ?: 0 else 0) + count
    }

    suspend fun addRecentSearch(query: String) = context.dataStore.edit { p ->
        val current = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        val updated = (listOf(query.trim()) + current.filter { !it.equals(query.trim(), ignoreCase = true) }).take(5)
        p[Keys.recentSearches] = updated.joinToString("\n")
    }

    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(Keys.recentSearches) }
}
