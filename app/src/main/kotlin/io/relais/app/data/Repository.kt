// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.relais.api.ApiDates
import io.relais.api.ApiException
import io.relais.api.ApiItem
import io.relais.api.CountryFilter
import io.relais.api.CountryTab
import io.relais.api.FacetValue
import io.relais.api.FormatNames
import io.relais.api.MostRead
import io.relais.api.HtmlBlocks
import io.relais.api.Ids
import io.relais.api.RelaisJson
import io.relais.api.ReliefWebClient
import io.relais.api.ReportFields
import io.relais.api.Requests
import io.relais.api.Scope
import io.relais.api.RssItem
import io.relais.api.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Why a screen could not load fresh data. */
enum class LoadError { OFFLINE, DENIED, RATE_LIMITED, NO_ACCESS, OTHER }

fun Throwable.toLoadError(): LoadError = when (this) {
    is ApiException.Network -> LoadError.OFFLINE
    is ApiException.AccessDenied -> LoadError.DENIED
    is ApiException.RateLimited -> LoadError.RATE_LIMITED
    is ApiException.NoAppname -> LoadError.NO_ACCESS
    else -> LoadError.OTHER
}

enum class AccessCheck { OK, REFUSED, NO_NETWORK, OTHER }

data class SearchParams(
    val text: String,
    val countryIds: Set<Long> = emptySet(),
    val formatIds: Set<Long> = emptySet(),
    val languageIds: Set<Long> = emptySet(),
    val postedDays: Int? = null,
    val sort: Requests.SearchSort? = null,
    val headlinesOnly: Boolean = false,
    val sourceIds: Set<Long> = emptySet(),
    val themeIds: Set<Long> = emptySet(),
)

data class SearchPage(
    val reports: List<ReportEntity>,
    val totalCount: Int,
    val facets: Map<String, List<FacetValue>>,
)

/**
 * The only place that talks to the network for ReliefWeb data. Screens read Room;
 * this class decides when to call the API and writes the results into Room.
 */
class Repository(
    private val dao: RelaisDao,
    private val settings: SettingsStore,
    private val http: HttpClient,
    private val builtInAppname: String,
    private val bundledCountries: () -> List<CountryEntity>,
    /** The valve (RemoteConfig): cache times and features, with safe built-in values. */
    private val config: () -> RemoteConfig = { RemoteConfig() },
    /** Runs a block in one database transaction: much faster for a sync of 200 reports. */
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    /** Appname entered on the phone; kept in memory from [SettingsStore]. */
    @Volatile
    var userAppname: String? = null

    fun appname(): String? = userAppname?.takeIf { it.isNotBlank() } ?: builtInAppname.takeIf { it.isNotBlank() }

    val hasAccess: Boolean get() = appname() != null
    val hasBuiltInAccess: Boolean get() = builtInAppname.isNotBlank()

    /**
     * Access as it changes: an appname saved or removed in Settings opens or closes Today and
     * Search at once, without restarting the app (first launch review, 13/11).
     */
    val access: Flow<Boolean> = settings.flow
        .map { !it.userAppname.isNullOrBlank() || builtInAppname.isNotBlank() }
        .distinctUntilChanged()

    /**
     * The sync that follows a new appname: headlines from the API and the last 7 days of what is
     * followed, now rather than at the next background sync, up to 12 hours later. The first
     * sync announces nothing, so no alert comes out of this catch-up.
     */
    suspend fun syncAfterAccess() {
        if (!hasAccess) return
        settings.setLastSyncAt(0)
        sync()
    }

    private val client = ReliefWebClient(http, ::appname, onCall = ::countCall)

    private suspend fun countCall() {
        val now = System.currentTimeMillis()
        if (settings.current().blockedUntil > now) throw ApiException.RateLimited(null)
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val calls = (dao.calls(day) ?: 0) + 1
        if (calls > DAILY_BUDGET) throw ApiException.RateLimited(null)
        dao.upsertUsage(ApiUsageEntity(day, calls))
    }

    /** Runs an API call and records access refusals and rate limits. */
    private suspend fun <T> api(block: suspend (ReliefWebClient) -> T): T {
        try {
            val result = block(client)
            if (settings.current().accessDenied) settings.setAccessDenied(false)
            return result
        } catch (e: ApiException.AccessDenied) {
            settings.setAccessDenied(true)
            throw e
        } catch (e: ApiException.RateLimited) {
            val until = e.retryAfterSeconds?.let { System.currentTimeMillis() + it * 1000 }
                ?: LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            settings.setBlockedUntil(until)
            throw e
        }
    }

    /**
     * Negative cache (architecture.md, errors): after a network error, a 5xx or a web page
     * instead of data, the same request is not sent again for 60 s; the error is replayed.
     * A pull to refresh repeated on a dead connection costs no call.
     */
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ApiException>>()

    private suspend fun <T> guarded(key: String, block: suspend () -> T): T {
        val now = System.currentTimeMillis()
        failedUntil[key]?.let { (until, error) -> if (now < until) throw error else failedUntil.remove(key) }
        try {
            return block()
        } catch (e: ApiException) {
            if (e is ApiException.Network || e is ApiException.Server || e is ApiException.Blocked) {
                failedUntil[key] = (now + NEGATIVE_TTL) to e
            }
            throw e
        }
    }

    // --- Headlines and sync ---------------------------------------------------

    /** M5 Today: refresh headlines, at most every 30 min (API) or 60 min (RSS) unless forced. */
    suspend fun refreshHeadlines(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = settings.current().lastHeadlinesRefreshAt
        val minGap = if (hasAccess) THIRTY_MIN else SIXTY_MIN
        if (!force && now - last < minGap) return
        guarded("headlines") {
            if (hasAccess) {
                val response = api { it.reports(Requests.headlines(30)) }
                saveReports(response.data)
            } else {
                fetchRssHeadlines()
            }
        }
        settings.setLastHeadlinesRefreshAt(now)
    }

    private suspend fun fetchRssHeadlines() {
        val text = try {
            http.get(RssParser.HEADLINES_URL).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Network(e)
        }
        if (!text.trimStart().startsWith("<?xml") && !text.contains("<rss")) throw ApiException.Blocked()
        val now = System.currentTimeMillis()
        dao.upsertReports(RssParser.parse(text).map { it.toEntity(now) })
    }

    /**
     * Background sync: one request for headlines and every followed country, with full text.
     * Without access, refreshes the RSS headlines instead.
     */
    suspend fun sync() {
        if (!hasAccess) {
            refreshHeadlines()
            return
        }
        guarded("sync") { syncNow() }
    }

    private suspend fun syncNow() {
        val now = System.currentTimeMillis()
        val s = settings.current()
        val weekAgo = now - SEVEN_DAYS
        val since = if (s.lastSyncAt == 0L) weekAgo else maxOf(weekAgo, s.lastSyncAt - ONE_HOUR)
        val followed = dao.followed().map { it.countryId }
        val crises = dao.followedDisasters().map { it.disasterId }
        val sources = dao.followedSources()
        val fetched = ArrayList<ApiItem<ReportFields>>()
        var offset = 0
        var page = 0
        do {
            val response = api {
                it.reports(
                    Requests.sync(
                        followed, Instant.ofEpochMilli(since), offset,
                        followedDisasterIds = crises,
                        followedSourceIds = sources.filter { !it.reportsOnly }.map { it.sourceId },
                        reportsOnlySourceIds = sources.filter { it.reportsOnly }.map { it.sourceId },
                        latestReports = config().features.latestReports,
                    ),
                )
            }
            saveReports(response.data)
            fetched += response.data
            offset += response.data.size
            page++
        } while (response.data.isNotEmpty() && offset < response.totalCount && page < 3)
        settings.setLastSyncAt(now)
        settings.setLastHeadlinesRefreshAt(now)
        // Alerts look at what was posted since the previous sync; the first sync announces nothing.
        if (s.alerts && config().features.alerts && s.lastSyncAt > 0) runCatching { checkAlerts(fetched, s.lastSyncAt, now) }
        if (now - s.countriesFetchedAt > THIRTY_DAYS) runCatching { refreshCountries(force = true) }
    }

    /** Receives the alerts to send; RelaisApp posts them as notifications. */
    @Volatile
    var alertSink: ((AlertPlan) -> Unit)? = null

    /** New appeals and flash updates in what is followed (passe-v0.2.md 6), within the daily caps. */
    private suspend fun checkAlerts(items: List<ApiItem<ReportFields>>, since: Long, now: Long) {
        val sink = alertSink ?: return
        val reports = items.map { it.toEntity(null, now, full = false) }
        val countries = dao.followed().associate { it.countryId to it.name }
        val crises = dao.followedDisasters().associate { it.disasterId to it.name }
        val candidates = Alerts.candidates(reports, since, now, countries, crises)
        if (candidates.isEmpty()) return
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val s = settings.current()
        val sentBefore = if (s.alertDay == today) s.alertCount else 0
        val plan = Alerts.plan(candidates, if (s.alertDay == today) s.alertScopes else emptySet(), sentBefore)
        if (plan.isEmpty) return
        if (plan.grouped > 0) {
            settings.recordAlerts(today, candidates.map { it.scopeKey }.toSet(), Alerts.PER_DAY + 1 - sentBefore)
        } else {
            settings.recordAlerts(today, plan.single.map { it.scopeKey }.toSet(), plan.single.size)
        }
        sink(plan)
    }

    // --- Reports ------------------------------------------------------------------

    /** Saves API documents, keeping any text already on the phone. */
    suspend fun saveReports(items: List<ApiItem<ReportFields>>, full: Boolean = true) {
        if (items.isEmpty()) return
        val existing = dao.existing(items.map { it.id }).associateBy { it.id }
        val now = System.currentTimeMillis()
        val entities = items.map { it.toEntity(existing[it.id], now, full) }
        // Text for full-text search is extracted before the transaction, which stays short.
        val fts = entities.mapNotNull { entity ->
            val body = entity.bodyHtml?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ReportFtsEntity(entity.id, entity.displayTitle, entity.sourceLong, HtmlBlocks.plainText(body))
        }
        transaction {
            dao.upsertReports(entities)
            for (entry in fts) {
                dao.deleteFts(entry.rowid)
                dao.insertFts(entry)
            }
        }
    }

    /** M6: makes sure the full text of a report is on the phone. */
    suspend fun ensureReport(id: Long) {
        val entity = dao.report(id)
        if (entity != null && (entity.bodyHtml != null || entity.fromRss || entity.goneAt != null)) return
        try {
            val item = api { it.report(id) }
            saveReports(listOf(item))
        } catch (e: ApiException.NotFound) {
            if (entity != null) dao.markGone(id, System.currentTimeMillis())
            throw e
        }
    }

    // --- Country page --------------------------------------------------------------

    /** One cached feed per country, tab and combination of filters. */
    fun feedKey(countryId: Long, tab: CountryTab, filter: CountryFilter = CountryFilter()): String =
        feedKey(Scope.country(countryId), tab, filter)

    /** One cached feed per country or crisis, tab and filters; the keys of countries are those of v0.1. */
    fun feedKey(scope: Scope, tab: CountryTab, filter: CountryFilter = CountryFilter()): String {
        val base = "${scope.prefix}:${scope.id}:${tab.name}"
        val signature = filter.signature()
        return if (signature.isEmpty()) base else "$base:$signature"
    }

    /** M8: loads one tab. `older` adds the next page further back in time. */
    suspend fun loadCountryTab(
        countryId: Long,
        tab: CountryTab,
        filter: CountryFilter = CountryFilter(),
        older: Boolean = false,
        force: Boolean = false,
    ) = loadScopeTab(Scope.country(countryId), tab, filter, older, force)

    /** One tab of a country or crisis page. `older` adds the next page further back in time. */
    suspend fun loadScopeTab(
        scope: Scope,
        tab: CountryTab,
        filter: CountryFilter = CountryFilter(),
        older: Boolean = false,
        force: Boolean = false,
    ) {
        val key = feedKey(scope, tab, filter)
        val meta = dao.feedMeta(key)
        val now = System.currentTimeMillis()
        if (!older && !force && meta != null && now - meta.fetchedAt < config().countryFeedMillis) return
        guarded(if (older) "$key:older" else key) { fetchScopeTab(key, meta, scope, tab, filter, older, now) }
    }

    private suspend fun fetchScopeTab(
        key: String,
        meta: FeedMetaEntity?,
        scope: Scope,
        tab: CountryTab,
        filter: CountryFilter,
        older: Boolean,
        now: Long,
    ) {
        if (older) {
            val current = dao.feedSize(key)
            val oldest = dao.feedOldest(key) ?: return
            val response = api {
                it.reports(Requests.feed(scope, tab, filter, before = Instant.ofEpochMilli(oldest - 1000), withFacets = false))
            }
            saveReports(response.data)
            dao.insertFeedItems(response.data.mapIndexed { i, item -> FeedItemEntity(key, item.id, current + i) })
            dao.upsertFeedMeta(
                (meta ?: FeedMetaEntity(key, now, response.totalCount, null))
                    .copy(exhausted = response.data.size < COUNTRY_PAGE),
            )
        } else {
            val response = api { it.reports(Requests.feed(scope, tab, filter, limit = COUNTRY_PAGE)) }
            saveReports(response.data)
            dao.clearFeed(key)
            dao.insertFeedItems(response.data.mapIndexed { i, item -> FeedItemEntity(key, item.id, i) })
            // Counts by field and value, for the filter sheet (FeedFacets).
            val facets = response.embedded?.facets
                ?.mapValues { (_, result) -> result.data.associate { it.value to it.count } }
                ?.takeIf { it.isNotEmpty() }
            dao.upsertFeedMeta(
                FeedMetaEntity(
                    feedKey = key,
                    fetchedAt = now,
                    totalCount = response.totalCount,
                    facetsJson = facets?.let { RelaisJson.encodeToString(FACETS, it) } ?: meta?.facetsJson,
                    exhausted = response.data.size >= response.totalCount,
                ),
            )
        }
    }

    // --- Crises and overviews (v0.2, track A) ---------------------------------------------

    /** Documents of a country (its main country) or of a crisis, on the phone. */
    fun observeScopeReports(scope: Scope): Flow<List<ReportEntity>> = when (scope.kind) {
        Scope.Kind.COUNTRY -> dao.observeCountryReports(scope.id)
        Scope.Kind.DISASTER -> dao.observeDisasterReports(scope.id)
        Scope.Kind.SOURCE -> dao.observeSourceReports(scope.id)
    }

    fun observeScopeTags(scope: Scope): Flow<List<TagsRow>> = when (scope.kind) {
        Scope.Kind.COUNTRY -> dao.observeCountryTags(scope.id)
        Scope.Kind.DISASTER -> dao.observeDisasterTags(scope.id)
        Scope.Kind.SOURCE -> dao.observeSourceTags(scope.id)
    }

    private fun overviewKey(scope: Scope) = "overview:${scope.prefix}:${scope.id}"
    private fun infoKey(scope: Scope) = "info:${scope.prefix}:${scope.id}"
    private fun relatedKey(scope: Scope) = "related:${scope.prefix}:${scope.id}"

    /**
     * A small JSON value cached in feed_meta: returned from the phone while younger than [ttl],
     * else fetched again (errors go through the negative cache).
     */
    private suspend fun <T> cachedJson(key: String, ttl: Long, serializer: KSerializer<T>, force: Boolean, fetch: suspend () -> T): T {
        val meta = dao.feedMeta(key)
        val cached = meta?.facetsJson?.let { runCatching { RelaisJson.decodeFromString(serializer, it) }.getOrNull() }
        val now = System.currentTimeMillis()
        if (!force && meta != null && cached != null && now - meta.fetchedAt < ttl) return cached
        val value = guarded(key) { fetch() }
        dao.upsertFeedMeta(FeedMetaEntity(key, now, 0, RelaisJson.encodeToString(serializer, value)))
        return value
    }

    private fun <T> observeJson(key: String, serializer: KSerializer<T>): Flow<T?> = dao.observeFeedMeta(key).map { meta ->
        meta?.facetsJson?.let { runCatching { RelaisJson.decodeFromString(serializer, it) }.getOrNull() }
    }

    /**
     * Overview of a country or crisis (produit/tour-a2.md 6.2): call A (profile, 24 h), call B
     * (50 latest documents and their counts, 60 min), call C (linked crises, 24 h). When the 50
     * documents hold no situation report, or no appeal and the profile lists none, the tab of
     * that format is loaded too: 3 calls on opening, 5 at most. An error of call B is reported;
     * those of the other calls leave their blocks on what the phone has.
     */
    suspend fun loadOverview(scope: Scope, force: Boolean = false) {
        if (!hasAccess) throw ApiException.NoAppname()
        var failure: Exception? = null
        val info = try {
            loadScopeInfo(scope, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val key = overviewKey(scope)
        try {
            val meta = dao.feedMeta(key)
            val now = System.currentTimeMillis()
            if (force || meta == null || now - meta.fetchedAt >= config().overviewMillis) {
                guarded(key) {
                    val response = api { it.reports(Requests.overview(scope, Instant.ofEpochMilli(now))) }
                    saveReports(response.data, full = false)
                    dao.clearFeed(key)
                    dao.insertFeedItems(response.data.mapIndexed { i, item -> FeedItemEntity(key, item.id, i) })
                    val facets = response.embedded?.facets
                        ?.mapValues { (_, result) -> result.data.associate { it.value to it.count } }
                    dao.upsertFeedMeta(
                        FeedMetaEntity(key, now, response.totalCount, facets?.let { RelaisJson.encodeToString(FACETS, it) }, exhausted = true),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        val countryIds = when (scope.kind) {
            Scope.Kind.COUNTRY -> listOf(scope.id)
            Scope.Kind.DISASTER -> info?.disaster?.countries?.map { it.id }.orEmpty()
            Scope.Kind.SOURCE -> emptyList()
        }
        if (countryIds.isNotEmpty()) {
            runCatching {
                cachedJson(relatedKey(scope), ONE_DAY, DISASTERS, force) {
                    val exclude = scope.id.takeIf { scope.kind == Scope.Kind.DISASTER }
                    api { it.disasters(Requests.relatedDisasters(countryIds, exclude)) }.data.map { it.toInfo() }
                }
            }
        }
        val latest = dao.feed(key)
        if (latest.isNotEmpty()) {
            if (latest.none { it.formatId == Ids.FORMAT_SITREP }) runCatching { loadScopeTab(scope, CountryTab.SITREPS) }
            if (latest.none { it.formatId == Ids.FORMAT_APPEAL } && info?.appeals.isNullOrEmpty()) {
                runCatching { loadScopeTab(scope, CountryTab.APPEALS) }
            }
        }
        failure?.let { throw it }
    }

    /**
     * Call A alone (24 h): the editors' profile of a country, or a crisis with its description,
     * for the head of a crisis page (theme-compose.md 12.5).
     */
    suspend fun loadScopeInfo(scope: Scope, force: Boolean = false): ScopeInfo {
        if (!hasAccess) throw ApiException.NoAppname()
        val info = cachedJson(infoKey(scope), ONE_DAY, ScopeInfo.serializer(), force) { fetchInfo(scope) }
        info.disaster?.let { d ->
            // Keep a followed crisis up to date: its status and countries change.
            dao.followedDisaster(d.id)?.let { f ->
                dao.upsertFollowedDisaster(f.copy(name = d.name, status = d.status, typeName = d.typeName, glide = d.glide, countries = d.countries))
            }
        }
        return info
    }

    fun observeScopeInfo(scope: Scope): Flow<ScopeInfo?> = observeJson(infoKey(scope), ScopeInfo.serializer())

    private suspend fun fetchInfo(scope: Scope): ScopeInfo = when (scope.kind) {
        Scope.Kind.SOURCE -> ScopeInfo(source = fetchSource(scope.id))
        Scope.Kind.COUNTRY -> ScopeInfo(appeals = api { it.country(scope.id) }.fields.profile.appeals())
        Scope.Kind.DISASTER -> {
            val item = api { it.disaster(scope.id) }
            ScopeInfo(disaster = item.toInfo(), descriptionHtml = item.fields.descriptionHtml, appeals = item.fields.profile.appeals())
        }
    }

    fun observeOverview(scope: Scope): Flow<OverviewData> {
        val key = overviewKey(scope)
        val formatTabs = combine(
            dao.observeFeed(feedKey(scope, CountryTab.SITREPS)),
            dao.observeFeed(feedKey(scope, CountryTab.APPEALS)),
        ) { sitreps, appeals -> sitreps to appeals }
        return combine(
            dao.observeFeed(key),
            dao.observeFeedMeta(key),
            observeJson(infoKey(scope), ScopeInfo.serializer()),
            observeJson(relatedKey(scope), DISASTERS),
            formatTabs,
        ) { latest, meta, info, related, (sitreps, appeals) ->
            OverviewData(latest, facetsOf(meta), meta?.fetchedAt, meta?.totalCount, info, related, sitreps, appeals)
        }
    }

    /** "New disasters this week" on Today: one request, at most every 6 hours. */
    suspend fun refreshNewDisasters(force: Boolean = false): List<DisasterInfo> {
        if (!hasAccess) return emptyList()
        return cachedJson(NEW_DISASTERS, config().cacheHours.newDisasters * ONE_HOUR, DISASTERS, force) {
            val since = Instant.now().minusSeconds(7 * 86_400L)
            api { it.disasters(Requests.newDisasters(since)) }.data.map { it.toInfo() }
        }
    }

    fun observeNewDisasters(): Flow<List<DisasterInfo>?> = observeJson(NEW_DISASTERS, DISASTERS)

    /** "Follow a crisis": the disasters that are not over, kept 6 hours. */
    suspend fun loadOpenDisasters(force: Boolean = false): List<DisasterInfo> {
        if (!hasAccess) throw ApiException.NoAppname()
        return cachedJson(OPEN_DISASTERS, SIX_HOURS, DISASTERS, force) {
            api { it.disasters(Requests.openDisasters()) }.data.map { it.toInfo() }
        }
    }

    fun observeOpenDisasters(): Flow<List<DisasterInfo>?> = observeJson(OPEN_DISASTERS, DISASTERS)

    fun observeDisasterInfo(id: Long): Flow<ScopeInfo?> = observeJson(infoKey(Scope.disaster(id)), ScopeInfo.serializer())

    /** Follows a crisis, then fetches its documents of the last 7 days (one request). */
    suspend fun followDisaster(info: DisasterInfo) {
        val now = System.currentTimeMillis()
        if (dao.followedDisaster(info.id) != null) return
        dao.upsertFollowedDisaster(info.toFollowed(now, lastVisitAt = now - SEVEN_DAYS))
        if (hasAccess) {
            runCatching {
                val response = api { it.reports(Requests.backfill(Scope.disaster(info.id), Instant.ofEpochMilli(now - SEVEN_DAYS))) }
                saveReports(response.data)
            }
        }
    }

    suspend fun unfollowDisaster(id: Long) = dao.unfollowDisaster(id)

    // --- Organizations (v0.3, produit/suivre-organisation.md) --------------------------------

    /** `sources/{id}`, then its rhythm in one request of facets: 2 requests, kept 24 hours. */
    private suspend fun fetchSource(id: Long): SourceInfo {
        val item = api { it.source(id) }.fields
        val stats = api { it.reports(Requests.sourceStats(id)) }
        val facets = stats.embedded?.facets.orEmpty()
        fun perWeek(name: String) = facets[name]?.data.orEmpty().sumOf { it.count } / 12.0
        return SourceInfo(
            id = id,
            shortname = item.displayShort,
            name = item.name,
            typeName = item.type?.name,
            countryName = item.country?.name,
            homepage = item.homepage,
            descriptionHtml = item.descriptionHtml,
            perWeek = perWeek(Requests.SOURCE_WEEKS),
            reportsPerWeek = perWeek(Requests.SOURCE_WEEKS_REPORTS),
            latestAt = stats.data.firstOrNull()?.fields?.date?.created?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            mainCountries = facets[Requests.SOURCE_COUNTRIES]?.data.orEmpty().map { it.value }.filter { it.isNotBlank() },
            formats = facets[Requests.SOURCE_FORMATS]?.data.orEmpty().mapNotNull { f -> f.value.toLongOrNull()?.let { it to f.count } }.toMap(),
        )
    }

    /** An organization's page info (24 h): for its header and the sheet before following. */
    suspend fun loadSource(id: Long, force: Boolean = false): SourceInfo? = loadScopeInfo(Scope.source(id), force).source

    /**
     * Follows an organization (P13 §5): its documents, or only its reports, join the feed; the
     * last 7 days are fetched now, as for a country (one request).
     */
    suspend fun followSource(info: SourceInfo, reportsOnly: Boolean) {
        val now = System.currentTimeMillis()
        if (dao.followedSource(info.id) != null) {
            dao.setSourceReportsOnly(info.id, reportsOnly)
            return
        }
        dao.upsertFollowedSource(
            FollowedSourceEntity(info.id, info.shortname, info.name, info.typeName, reportsOnly, followedAt = now, lastVisitAt = now - SEVEN_DAYS),
        )
        if (hasAccess) {
            runCatching {
                val query = Requests.backfill(Scope.source(info.id), Instant.ofEpochMilli(now - SEVEN_DAYS))
                saveReports(api { it.reports(query) }.data)
            }
        }
    }

    suspend fun unfollowSource(id: Long) = dao.unfollowSource(id)

    suspend fun setSourceReportsOnly(id: Long, reportsOnly: Boolean) = dao.setSourceReportsOnly(id, reportsOnly)

    suspend fun markSourceVisited(id: Long) = dao.markSourceVisited(id, System.currentTimeMillis())

    /** "Search organizations": one request per search, from 3 letters (13.2). */
    suspend fun searchSources(text: String): List<SourceInfo> {
        if (text.trim().length < 3) return emptyList()
        return api { it.sources(Requests.searchSources(text)) }.data.map { it.toSourceInfo() }
    }

    /**
     * "Analysis on your countries" (P13 §4.3): who published analyses or assessments on the
     * followed countries in 90 days, with the count; 2 requests, kept 24 hours.
     */
    suspend fun sourceSuggestions(countryIds: List<Long>, exclude: Set<Long>): List<Pair<SourceInfo, Int>> {
        if (countryIds.isEmpty() || !hasAccess) return emptyList()
        val key = "suggestions:" + countryIds.sorted().joinToString(",")
        val found = cachedJson(key, ONE_DAY, SUGGESTIONS, force = false) {
            val facet = api { it.reports(Requests.sourceSuggestions(countryIds)) }.embedded?.facets?.get(Requests.SOURCE_SUGGESTIONS)?.data.orEmpty()
            val counts = facet.mapNotNull { f -> f.value.toLongOrNull()?.let { it to f.count } }.toMap()
            if (counts.isEmpty()) {
                emptyList()
            } else {
                api { it.sources(Requests.sourcesByIds(counts.keys)) }.data.map { it.toSourceInfo() }
                    .map { SuggestedSource(it, counts[it.id] ?: 0) }.sortedByDescending { it.count }
            }
        }
        return found.filter { it.source.id !in exclude }.take(8).map { it.source to it.count }
    }

    suspend fun markDisasterVisited(id: Long) = dao.markDisasterVisited(id, System.currentTimeMillis())

    // --- Most read and most covered (produit/tour-a2.md 5) --------------------------------

    private val urlAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Reports for "Most read" addresses, in their order: from the phone first, by address or
     * node id; the missing ones in one API request, at most every 6 hours for the same list.
     */
    suspend fun reportsByUrls(urls: List<String>): List<ReportEntity> {
        val found = reportsByUrlMap(urls)
        return urls.mapNotNull { found[it] }
    }

    /**
     * The same for many addresses at once (the Most read screen, about 250): the missing ones
     * go to the API by batches of [URL_BATCH], each batch at most every 6 hours.
     */
    suspend fun reportsByUrlMap(urls: List<String>, fetch: Boolean = true): Map<String, ReportEntity> {
        if (urls.isEmpty()) return emptyMap()
        suspend fun lookup(): Map<String, ReportEntity> {
            val result = mutableMapOf<String, ReportEntity>()
            for (chunk in urls.distinct().chunked(500)) {
                val byUrl = dao.reportsByUrls(chunk).associateBy { it.urlAlias }
                val ids = chunk.mapNotNull(MostRead::nodeId)
                val byId = if (ids.isEmpty()) emptyMap() else dao.reportsByIds(ids).associateBy { it.id }
                chunk.forEach { url -> (byUrl[url] ?: MostRead.nodeId(url)?.let { byId[it] })?.let { result[url] = it } }
            }
            return result
        }
        var found = lookup()
        val missing = urls.distinct().filter { it !in found }
        val now = System.currentTimeMillis()
        var fetched = false
        if (fetch && hasAccess) {
            for (batch in missing.chunked(URL_BATCH)) {
                val key = batch.joinToString("\n")
                if (now - (urlAttempts[key] ?: 0L) <= SIX_HOURS) continue
                // A batch counts as tried once it answered or failed; a cancelled one (the screen
                // left or its inputs changed) is tried again next time.
                try {
                    val response = api { it.reports(Requests.byUrls(batch)) }
                    saveReports(response.data)
                    fetched = true
                    urlAttempts[key] = now
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    urlAttempts[key] = now
                }
            }
        }
        if (fetched) found = lookup()
        return found
    }

    /** "Most reports this week": counts by country and by disaster over 7 days, one request. */
    suspend fun fetchMostCovered(): MostCovered {
        val now = System.currentTimeMillis()
        val response = guarded("most-covered") {
            api { it.reports(Requests.mostCovered(Instant.ofEpochMilli(now - SEVEN_DAYS))) }
        }
        val facets = response.embedded?.facets.orEmpty()
        val countryCounts = facets["countries"]?.data.orEmpty().mapNotNull { v -> v.value.toLongOrNull()?.let { it to v.count } }
        val names = dao.countriesByIds(countryCounts.map { it.first }).associate { it.id to it.displayName }
        return MostCovered(
            fetchedAt = now,
            countries = countryCounts.mapNotNull { (id, count) -> names[id]?.let { CountLine(id, it, count) } },
            disasters = facets["disasters"]?.data.orEmpty().filter { it.value.isNotBlank() }.map { CountLine(0, it.value, it.count) },
        )
    }

    // --- Search ------------------------------------------------------------------------

    suspend fun search(params: SearchParams, offset: Int): SearchPage {
        val response = api {
            it.reports(
                Requests.search(
                    text = params.text,
                    countryIds = params.countryIds,
                    formatIds = params.formatIds,
                    languageIds = params.languageIds,
                    sourceIds = params.sourceIds,
                    themeIds = params.themeIds,
                    postedSince = params.postedDays?.let { d -> Instant.now().minusSeconds(d * 86_400L) },
                    sort = params.sort ?: if (params.text.isBlank()) Requests.SearchSort.NEWEST else Requests.SearchSort.RELEVANT,
                    offset = offset,
                    headlinesOnly = params.headlinesOnly,
                ),
            )
        }
        saveReports(response.data, full = false)
        val byId = dao.reportsByIds(response.data.map { it.id }).associateBy { it.id }
        val reports = response.data.mapNotNull { byId[it.id] }
        return SearchPage(
            reports = reports,
            totalCount = response.totalCount,
            facets = response.embedded?.facets?.mapValues { it.value.data } ?: emptyMap(),
        )
    }

    /**
     * Names of organizations and themes for Search's filter sheet: those of the reports on the
     * phone (the latest 500), which name nearly every theme and the busy organizations.
     */
    suspend fun knownTagNames(): Pair<Map<Long, String>, Map<Long, String>> {
        val rows = dao.recentTags(500)
        val sources = rows.flatMap { it.sources }.associate { it.id to it.shortname.ifBlank { it.name } }
        val themes = rows.flatMap { it.themes }.associate { it.id to it.name }
        return sources to themes
    }

    /** Short names of organizations by id, one request for those not known yet, kept 24 hours. */
    suspend fun sourceNames(ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty() || !hasAccess) return emptyMap()
        val key = "source-names:" + ids.sorted().joinToString(",")
        return cachedJson(key, ONE_DAY, SOURCE_NAMES, force = false) {
            api { it.sources(Requests.sourcesByIds(ids)) }.data.map { it.toSourceInfo() }
                .associate { it.id to it.shortname.ifBlank { it.name } }
        }
    }

    // --- Countries -------------------------------------------------------------------

    /** M2: the list of countries, from the API, or bundled with the app when offline. */
    suspend fun refreshCountries(force: Boolean = false) {
        val s = settings.current()
        val now = System.currentTimeMillis()
        if (!force && dao.countryCount() > 0 && now - s.countriesFetchedAt < THIRTY_DAYS) return
        if (hasAccess) {
            try {
                val response = api { it.countries(Requests.countries()) }
                dao.upsertCountries(response.data.mapNotNull { item ->
                    val f = item.fields
                    if (f.name.isBlank()) null else CountryEntity(item.id, f.name, f.shortname, f.iso3, f.status)
                })
                settings.setCountriesFetchedAt(now)
                return
            } catch (e: ApiException) {
                if (dao.countryCount() > 0) throw e
                // Fall back to the bundled list below.
            }
        }
        if (dao.countryCount() == 0) dao.upsertCountries(bundledCountries())
    }

    suspend fun follow(countryId: Long, name: String) {
        val now = System.currentTimeMillis()
        if (dao.followedCountry(countryId) != null) return
        dao.upsertFollowed(FollowedCountryEntity(countryId, name, now, lastVisitAt = now - SEVEN_DAYS))
        if (hasAccess) {
            runCatching {
                val response = api {
                    it.reports(Requests.countryBackfill(countryId, Instant.ofEpochMilli(now - SEVEN_DAYS)))
                }
                saveReports(response.data)
            }
        }
    }

    suspend fun unfollow(countryId: Long) = dao.unfollow(countryId)

    suspend fun markCountryVisited(countryId: Long) =
        dao.markVisited(countryId, System.currentTimeMillis())

    // --- Reading state ----------------------------------------------------------------

    suspend fun opened(reportId: Long) {
        val now = System.currentTimeMillis()
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(
            current.copy(
                firstOpenedAt = current.firstOpenedAt ?: now,
                lastOpenedAt = now,
                readAt = current.readAt ?: now,
                inHistory = true,
                hiddenFromContinue = false,
            ),
        )
    }

    suspend fun savePosition(reportId: Long, index: Int, offset: Int, percent: Int, finished: Boolean) {
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(
            current.copy(
                itemIndex = index,
                itemOffset = offset,
                percent = maxOf(current.percent, percent),
                finishedAt = current.finishedAt ?: if (finished) System.currentTimeMillis() else null,
            ),
        )
    }

    suspend fun setSaved(reportId: Long, saved: Boolean) {
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(current.copy(savedAt = if (saved) System.currentTimeMillis() else null))
    }

    suspend fun setRead(reportId: Long, read: Boolean) {
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(current.copy(readAt = if (read) System.currentTimeMillis() else null))
    }

    suspend fun hideFromContinue(reportId: Long) {
        val current = dao.reading(reportId) ?: return
        dao.upsertReading(current.copy(hiddenFromContinue = true))
    }

    suspend fun removeFromHistory(reportId: Long) {
        val current = dao.reading(reportId) ?: return
        dao.upsertReading(current.copy(inHistory = false))
    }

    suspend fun pdfOpened(reportId: Long) {
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(current.copy(pdfOpenedAt = System.currentTimeMillis(), readAt = current.readAt ?: System.currentTimeMillis()))
    }

    suspend fun savePdfPage(reportId: Long, page: Int) {
        val current = dao.reading(reportId) ?: ReadingEntity(reportId)
        dao.upsertReading(current.copy(pdfPage = page))
    }

    // --- Highlights -------------------------------------------------------------------------

    /** Saves a highlight and returns its id. [pdfPage] is the page (from 1) of a passage of a PDF. */
    suspend fun addHighlight(
        report: ReportEntity,
        blockIndex: Int,
        start: Int,
        end: Int,
        text: String,
        note: String?,
        pdfPage: Int? = null,
    ): Long {
        val highlight = HighlightEntity(
            reportId = report.id,
            reportTitle = report.title,
            countryName = report.primaryCountryName,
            sourceName = report.sourceShort,
            formatName = report.formatName,
            reportDate = report.dateCreated,
            createdAt = System.currentTimeMillis(),
            text = text,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            blockIndex = blockIndex,
            start = start,
            end = end,
            pdfPage = pdfPage,
        )
        val id = dao.insertHighlight(highlight)
        dao.insertHighlightFts(HighlightFtsEntity(id, text, highlight.note.orEmpty(), report.title))
        return id
    }

    /** "Add a note" after highlighting from the selection bar. */
    suspend fun setHighlightNote(id: Long, note: String) {
        val value = note.trim().takeIf { it.isNotEmpty() }
        dao.setHighlightNote(id, value)
        val h = dao.highlight(id) ?: return
        dao.deleteHighlightFts(id)
        dao.insertHighlightFts(HighlightFtsEntity(id, h.text, value.orEmpty(), h.reportTitle))
    }

    suspend fun deleteHighlight(id: Long) {
        dao.deleteHighlight(id)
        dao.deleteHighlightFts(id)
    }

    suspend fun searchHighlights(text: String): Set<Long> {
        val terms = text.split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptySet()
        return dao.searchHighlights(terms.joinToString(" ") { "$it*" }).toSet()
    }

    suspend fun clearHistory() {
        dao.clearHistoryFlags()
        dao.deleteUnusedReading(readCutoff = Long.MAX_VALUE)
    }

    /** Words typed in "Search your library", turned into an FTS query. */
    suspend fun searchLibrary(text: String): Set<Long> {
        val terms = text.split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptySet()
        return dao.searchFts(terms.joinToString(" ") { "$it*" }).toSet()
    }

    // --- Access -------------------------------------------------------------------------

    /** M3 and M12: checks an appname with one call. `candidate == null` checks the current one. */
    suspend fun checkAccess(candidate: String? = null): AccessCheck {
        val name = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: appname() ?: return AccessCheck.REFUSED
        val probe = ReliefWebClient(http, { name }, onCall = ::countCall)
        return try {
            probe.check()
            AccessCheck.OK
        } catch (e: ApiException.AccessDenied) {
            AccessCheck.REFUSED
        } catch (e: ApiException.BadRequest) {
            AccessCheck.REFUSED
        } catch (e: ApiException.Network) {
            AccessCheck.NO_NETWORK
        } catch (e: ApiException) {
            AccessCheck.OTHER
        }
    }

    // --- Daily summary and cleanup -----------------------------------------------------

    data class Digest(val headlines: Int, val countries: List<Pair<String, Int>>) {
        val isEmpty: Boolean get() = headlines == 0 && countries.isEmpty()
    }

    /** Counts for the daily summary: every document, language twins counted once, as the pages show them. */
    suspend fun digestSince(since: Long): Digest {
        val headlines = dao.countHeadlinesSince(fromRss = !hasAccess, since = since)
        val countries = dao.followed().mapNotNull { f ->
            val count = LanguageTwins.group(dao.countryReportsSince(f.countryId, since)).size
            if (count > 0) f.name to count else null
        }.sortedByDescending { it.second }
        return Digest(headlines, countries)
    }

    /** What is new for each followed country and crisis, counted as their pages show it (theme-compose.md 12.1). */
    data class NewCounts(
        val countries: Map<Long, Int> = emptyMap(),
        val crises: Map<Long, Int> = emptyMap(),
        val sources: Map<Long, Int> = emptyMap(),
    )

    /**
     * Every document posted since the last visit, language twins counted once (decision of 8/11):
     * "8 new" is 8 lines with the dot at the top of the page, which opens on All.
     */
    fun observeNewCounts(): Flow<NewCounts> = combine(
        dao.observeNewInFollowedCountries(),
        dao.observeNewInFollowedCrises(),
        dao.observeNewInFollowedSources(),
    ) { countries, crises, sources ->
        NewCounts(
            sources = sources.groupBy({ it.scopeId }, { it.report }).mapValues { (_, list) -> LanguageTwins.group(list).size },
            countries = countries.groupBy { it.primaryCountryId ?: 0L }.mapValues { (_, list) -> LanguageTwins.group(list).size },
            crises = crises.groupBy({ it.scopeId }, { it.report }).mapValues { (_, list) -> LanguageTwins.group(list).size },
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    /** Followed countries with their counts of new documents, the most first. */
    fun observeFollowedRows(): Flow<List<FollowedRow>> = combine(dao.observeFollowedRows(), observeNewCounts()) { rows, n ->
        rows.map { it.copy(newCount = n.countries[it.countryId] ?: 0) }.sortedWith(compareByDescending<FollowedRow> { it.newCount }.thenBy { it.name })
    }

    /** Followed crises with their counts of new documents, the most first. */
    /** Followed organizations with their counts of new documents (what each adds). */
    fun observeFollowedSourceRows(): Flow<List<Pair<FollowedSourceEntity, Int>>> = combine(dao.observeFollowedSources(), observeNewCounts()) { rows, n ->
        rows.map { it to (n.sources[it.sourceId] ?: 0) }
    }

    fun observeFollowedDisasterRows(): Flow<List<FollowedDisasterRow>> = combine(dao.observeFollowedDisasterRows(), observeNewCounts()) { rows, n ->
        rows.map { it.copy(newCount = n.crises[it.disaster.disasterId] ?: 0) }
            .sortedWith(compareByDescending<FollowedDisasterRow> { it.newCount }.thenBy { it.disaster.name })
    }

    /** How many reports of History a keep of [days] removes now (v0.4: Settings apply at once). */
    suspend fun historyOlderThan(days: Int): Int =
        if (days > 0) dao.countHistoryBefore(System.currentTimeMillis() - days * ONE_DAY) else 0

    /** A shorter keep takes effect at once, not at the next daily cleanup. */
    suspend fun expireHistoryOlderThan(days: Int) {
        if (days > 0) dao.expireHistory(System.currentTimeMillis() - days * ONE_DAY)
    }

    suspend fun cleanup(historyDays: Int) {
        val now = System.currentTimeMillis()
        if (historyDays > 0) dao.expireHistory(now - historyDays * ONE_DAY)
        dao.deleteUnusedReading(readCutoff = now - THIRTY_DAYS)
        dao.deleteOldFeedItems(now - THIRTY_DAYS)
        dao.deleteOldReports(now - THIRTY_DAYS)
        dao.deleteOrphanFts()
    }

    companion object {
        const val DAILY_BUDGET = 300
        const val COUNTRY_PAGE = 20
        const val ONE_HOUR = 3_600_000L
        const val ONE_DAY = 24 * ONE_HOUR
        const val THIRTY_MIN = ONE_HOUR / 2
        const val SIX_HOURS = 6 * ONE_HOUR
        /** Addresses per request when resolving Most read lines (the query lists each one). */
        const val URL_BATCH = 50
        const val NEGATIVE_TTL = 60_000L
        const val SIXTY_MIN = ONE_HOUR
        const val SEVEN_DAYS = 7 * ONE_DAY
        const val THIRTY_DAYS = 30 * ONE_DAY
        const val NEW_DISASTERS = "disasters:new"
        const val OPEN_DISASTERS = "disasters:open"
        val FACETS = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer()))
        val DISASTERS = ListSerializer(DisasterInfo.serializer())

        /** Facets stored with a feed: field, then value, then count. */
        fun facetsOf(meta: FeedMetaEntity?): Map<String, Map<String, Int>> =
            meta?.facetsJson?.let { runCatching { RelaisJson.decodeFromString(FACETS, it) }.getOrNull() }.orEmpty()
    }
}

/** Key of a scope in feed keys: "country" (as in v0.1) or "disaster". */
val Scope.prefix: String get() = when (kind) {
    Scope.Kind.COUNTRY -> "country"
    Scope.Kind.DISASTER -> "disaster"
    Scope.Kind.SOURCE -> "source"
}

/** A suggested organization and its analyses on the followed countries in 3 months (P13 §4.3). */
@kotlinx.serialization.Serializable
data class SuggestedSource(val source: SourceInfo, val count: Int)

private val SUGGESTIONS = kotlinx.serialization.builtins.ListSerializer(SuggestedSource.serializer())
private val SOURCE_NAMES = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<Long>(), kotlinx.serialization.serializer<String>())

fun io.relais.api.ApiItem<io.relais.api.SourceFields>.toSourceInfo(): SourceInfo = SourceInfo(
    id = id,
    shortname = fields.displayShort,
    name = fields.name,
    typeName = fields.type?.name,
    countryName = fields.country?.name,
    homepage = fields.homepage,
)

/** What the overview of a country or crisis shows, from the phone. */
data class OverviewData(
    /** The 50 latest documents of call B. */
    val latest: List<ReportEntity> = emptyList(),
    val facets: Map<String, Map<String, Int>> = emptyMap(),
    val fetchedAt: Long? = null,
    val totalCount: Int? = null,
    val info: ScopeInfo? = null,
    val related: List<DisasterInfo>? = null,
    /** Tabs loaded when the 50 documents hold no situation report or no appeal. */
    val sitreps: List<ReportEntity> = emptyList(),
    val appeals: List<ReportEntity> = emptyList(),
)

// --- Mapping ------------------------------------------------------------------------------

/**
 * `full` means the request asked for the full text: an absent body is then stored as "",
 * so the app knows the text is loaded (and empty) and does not ask again.
 */
fun ApiItem<ReportFields>.toEntity(existing: IdBody?, now: Long, full: Boolean): ReportEntity {
    val f = fields
    val headline = f.headline?.takeIf { !it.title.isNullOrBlank() }
    val image = headline?.image ?: f.image
    val primary = f.primaryCountry?.takeIf { it.id != 0L }
    val format = f.format.firstOrNull()
    return ReportEntity(
        id = id,
        title = f.title.ifBlank { headline?.title.orEmpty() },
        headlineTitle = headline?.title,
        headlineSummary = headline?.summary,
        imageUrl = image?.urlLarge ?: image?.url,
        imageCaption = image?.caption,
        imageCopyright = image?.copyright,
        urlAlias = f.urlAlias ?: f.url,
        origin = f.origin?.takeIf { it.isNotBlank() },
        dateCreated = ApiDates.parse(f.date?.created) ?: now,
        dateOriginal = ApiDates.parse(f.date?.original),
        primaryCountryId = primary?.id,
        primaryCountryName = primary?.displayName,
        countries = f.country.filter { it.id != 0L }.map { Ref(it.id, it.displayName) },
        sources = f.source.map { SourceLite(it.id, it.displayShort, it.displayLong) },
        formatId = format?.id,
        formatName = format?.name,
        themes = f.theme.map { Ref(it.id, it.name.orEmpty()) },
        languages = f.language.mapNotNull { it.code ?: it.name },
        files = f.file.mapNotNull { file ->
            val url = file.url ?: return@mapNotNull null
            FileLite(
                url = url,
                filename = file.filename,
                size = file.filesize,
                pages = file.pagecount,
                mimetype = file.mimetype,
                description = file.description,
                previewSmall = file.preview?.urlSmall ?: file.preview?.urlThumb,
                previewLarge = file.preview?.urlLarge ?: file.preview?.url,
            )
        },
        bodyHtml = f.bodyHtml?.takeIf { it.isNotBlank() } ?: existing?.bodyHtml ?: if (full) "" else null,
        isHeadline = headline != null || existing?.isHeadline == true,
        fromRss = false,
        fetchedAt = now,
        disasters = f.disaster.filter { it.id != 0L }.map { Ref(it.id, it.name.orEmpty()) },
    )
}

fun RssItem.toEntity(now: Long): ReportEntity = ReportEntity(
    id = id,
    title = title,
    headlineTitle = title,
    headlineSummary = summary,
    imageUrl = imageUrl,
    imageCaption = imageCaption,
    imageCopyright = imageCopyright,
    urlAlias = link,
    origin = null,
    dateCreated = publishedAt ?: now,
    dateOriginal = null,
    primaryCountryId = null,
    primaryCountryName = countries.firstOrNull(),
    countries = countries.map { Ref(0, it) },
    sources = listOfNotNull(source?.let { SourceLite(0, it, it) }),
    formatId = format?.let { FormatNames.ID_BY_NAME[it] },
    formatName = format,
    themes = themes.map { Ref(0, it) },
    languages = emptyList(),
    files = listOfNotNull(enclosureUrl?.let {
        FileLite(it, it.substringAfterLast('/'), enclosureLength, null, enclosureType, null, previewUrl, null)
    }),
    bodyHtml = null,
    isHeadline = true,
    fromRss = true,
    fetchedAt = now,
)
