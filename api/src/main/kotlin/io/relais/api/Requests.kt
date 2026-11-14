// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import java.time.Instant

/**
 * What a page or a followed item is about: a country (its main country), a crisis (a disaster)
 * or, since v0.3, an organization (a ReliefWeb source).
 */
data class Scope(val kind: Kind, val id: Long) {
    enum class Kind(val field: String) {
        COUNTRY("primary_country.id"),
        DISASTER("disaster.id"),
        SOURCE("source.id"),
    }

    companion object {
        fun country(id: Long) = Scope(Kind.COUNTRY, id)
        fun disaster(id: Long) = Scope(Kind.DISASTER, id)
        fun source(id: Long) = Scope(Kind.SOURCE, id)
    }
}

/**
 * Every request the app sends, in one place, so tests and the fixtures workflow
 * can check them. Each one names the screen that sends it (M5 Today, M9 Search...).
 */
object Requests {
    /** M5 Today, manual refresh: latest headlines with their full text. */
    fun headlines(limit: Int = 30): ApiQuery = ApiQuery(
        limit = limit,
        filter = Filters.exists("headline"),
        fields = Fields.REPORT_FULL,
    )

    /** "Reports only" (produit/suivre-organisation.md, today-fraicheur.md): reports you read. */
    val REPORT_FORMATS = listOf(Ids.FORMAT_SITREP, Ids.FORMAT_ANALYSIS, Ids.FORMAT_ASSESSMENT, Ids.FORMAT_APPEAL, Ids.FORMAT_EVALUATION)

    /**
     * Background sync: headlines and everything followed in one request, with full text so that
     * it can be read offline. Organizations followed in full ([followedSourceIds]) bring all
     * their documents, those on "Reports only" ([reportsOnlySourceIds]) their reports.
     * [latestReports] adds the reports of every country, for "Latest reports on ReliefWeb" on
     * Today (v0.3): no request of its own (budget-appels.md).
     */
    fun sync(
        followedCountryIds: Collection<Long>,
        since: Instant,
        offset: Int = 0,
        limit: Int = 200,
        followedDisasterIds: Collection<Long> = emptyList(),
        followedSourceIds: Collection<Long> = emptyList(),
        reportsOnlySourceIds: Collection<Long> = emptyList(),
        latestReports: Boolean = false,
    ): ApiQuery {
        val reports = Filters.anyOf("format.id", REPORT_FORMATS)
        val who = Filters.or(
            Filters.exists("headline"),
            followedCountryIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("primary_country.id", it) },
            followedDisasterIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("disaster.id", it) },
            followedSourceIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("source.id", it) },
            reportsOnlySourceIds.takeIf { it.isNotEmpty() && !latestReports }?.let { Filters.and(Filters.anyOf("source.id", it), reports) },
            if (latestReports) reports else null,
        )
        return ApiQuery(
            limit = limit,
            offset = offset,
            filter = Filters.and(who, Filters.dateRange("date.created", from = since)),
            fields = Fields.REPORT_FULL,
        )
    }

    /** Recent documents of one followed country, fetched when it is followed. */
    fun countryBackfill(countryId: Long, since: Instant, limit: Int = 50): ApiQuery =
        backfill(Scope.country(countryId), since, limit)

    /** Recent documents of a followed country or crisis, fetched when it is followed. */
    fun backfill(scope: Scope, since: Instant, limit: Int = 50): ApiQuery = ApiQuery(
        limit = limit,
        filter = Filters.and(
            Filters.eq(scope.kind.field, scope.id),
            Filters.dateRange("date.created", from = since),
        ),
        fields = Fields.REPORT_FULL,
    )

    /** M8 Country page, one tab. Facets give the counts of every tab. */
    fun country(
        countryId: Long,
        tab: CountryTab,
        filter: CountryFilter = CountryFilter(),
        before: Instant? = null,
        limit: Int = 20,
        withFacets: Boolean = before == null,
        now: Instant = Instant.now(),
    ): ApiQuery = feed(Scope.country(countryId), tab, filter, before, limit, withFacets, now)

    /** One tab of a country or crisis page. Facets give the counts of every tab. */
    fun feed(
        scope: Scope,
        tab: CountryTab,
        filter: CountryFilter = CountryFilter(),
        before: Instant? = null,
        limit: Int = 20,
        withFacets: Boolean = before == null,
        now: Instant = Instant.now(),
    ): ApiQuery = ApiQuery(
        limit = limit,
        filter = Filters.and(
            Filters.eq(scope.kind.field, scope.id),
            tabFilter(tab),
            filter.sources.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("source.id", it) },
            filter.languages.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("language.id", it) },
            filter.themes.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("theme.id", it) },
            filter.postedDays?.let { Filters.dateRange("date.created", from = now.minusSeconds(it * 86_400L)) },
            before?.let { Filters.dateRange("date.created", to = it) },
        ),
        // Full text, like the sync: a document opened from a page needs no request of its own (T16).
        fields = Fields.REPORT_FULL,
        // The first page also brings the values of the filter sheet, with their counts.
        facets = if (withFacets) COUNTRY_FACETS else emptyList(),
    )

    val COUNTRY_FACETS = listOf(
        Facet("format.id", limit = 20),
        Facet("source.id", limit = 10),
        Facet("language.id", limit = 6),
        Facet("theme.id", limit = 20),
    )

    /** Formats of a tab; Key documents also keep every editors' headline. */
    fun tabFilter(tab: CountryTab): Filter? = when {
        tab.formatIds.isEmpty() -> null
        tab.includesHeadlines -> Filters.or(Filters.anyOf("format.id", tab.formatIds), Filters.exists("headline"))
        else -> Filters.anyOf("format.id", tab.formatIds)
    }

    enum class SearchSort { RELEVANT, NEWEST }

    /** M10 Search results. */
    fun search(
        text: String,
        countryIds: Collection<Long> = emptyList(),
        formatIds: Collection<Long> = emptyList(),
        languageIds: Collection<Long> = emptyList(),
        sourceIds: Collection<Long> = emptyList(),
        themeIds: Collection<Long> = emptyList(),
        postedSince: Instant? = null,
        sort: SearchSort = if (text.isBlank()) SearchSort.NEWEST else SearchSort.RELEVANT,
        offset: Int = 0,
        limit: Int = 20,
        headlinesOnly: Boolean = false,
    ): ApiQuery = ApiQuery(
        limit = limit,
        offset = offset,
        query = text.takeIf { it.isNotBlank() },
        sort = if (sort == SearchSort.RELEVANT) Sorts.RELEVANT else Sorts.NEWEST,
        filter = Filters.and(
            if (headlinesOnly) Filters.exists("headline") else null,
            countryIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("primary_country.id", it) },
            formatIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("format.id", it) },
            languageIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("language.id", it) },
            // Organizations and themes (lot 3, fiche-recherche-filtres.md): any of the values
            // in one field, all of the fields together, as on a country page.
            sourceIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("source.id", it) },
            themeIds.takeIf { it.isNotEmpty() }?.let { Filters.anyOf("theme.id", it) },
            postedSince?.let { Filters.dateRange("date.created", from = it) },
        ),
        fields = Fields.REPORT_CARD,
        // The first page brings the values of the filter sheet, with their counts: no request more.
        facets = if (offset == 0) SEARCH_FACETS else emptyList(),
    )

    /** Organizations: the 10 first show, the others behind "Show all"; about twenty themes exist. */
    val SEARCH_FACETS = listOf(
        Facet("primary_country.id", limit = 10),
        Facet("format.id", limit = 20),
        Facet("language.id", limit = 10),
        Facet("source.id", limit = 30),
        Facet("theme.id", limit = 25),
    )

    /**
     * The regional crises to choose from (topics, lot 3). No filter on `status` and no sort: the
     * values and sortable fields are not known without a real answer; the app sorts by title.
     */
    fun topics(): ApiQuery = ApiQuery(
        preset = null,
        limit = 200,
        sort = emptyList(),
        fields = listOf("id", "title", "featured", "url_alias", "date.changed"),
    )

    /**
     * The reports of a topic's list, or of a followed topic ([filter], the OR of its lists), the
     * newest first; [since] for the sync and the follow (7 days), with the full text then.
     */
    fun topicFeed(filter: Filter?, query: String? = null, since: Instant? = null, limit: Int = 30, full: Boolean = false): ApiQuery = ApiQuery(
        limit = limit,
        query = query?.takeIf { it.isNotBlank() },
        sort = Sorts.NEWEST,
        filter = Filters.and(filter, since?.let { Filters.dateRange("date.created", from = it) }),
        fields = if (full) Fields.REPORT_FULL else Fields.REPORT_CARD,
    )

    /** M2 Pick countries: the whole list, sorted by name. */
    fun countries(): ApiQuery = ApiQuery(
        preset = null,
        limit = 300,
        sort = listOf("name:asc"),
        fields = Fields.COUNTRY_ROW,
    )

    /** "World" is a primary country in the API, not a country. */
    const val WORLD = 254L

    /**
     * "Most reports this week" (produit/tour-a2.md 5.3): one request, no document, the counts
     * by primary country (World left out) and by disaster name, posted since [since].
     */
    fun mostCovered(since: Instant): ApiQuery = ApiQuery(
        limit = 0,
        sort = emptyList(),
        filter = Filters.dateRange("date.created", from = since),
        facets = listOf(
            Facet("primary_country.id", name = "countries", limit = 8, filter = Filters.not(Filters.eq("primary_country.id", WORLD))),
            Facet("disaster.name", name = "disasters", limit = 5),
        ),
    )

    /**
     * Overview of a country or crisis (produit/tour-a2.md 6.2, call B): the 50 latest documents
     * and, in the same request, the counts by format, the most active organizations of the last
     * 30 days, two rolling 7-day windows (not calendar weeks, 6.3) and, for a country, its crises
     * of the last 30 days; for a crisis, its most covered countries.
     */
    fun overview(scope: Scope, now: Instant = Instant.now()): ApiQuery {
        val d7 = now.minusSeconds(7 * DAY)
        val d14 = now.minusSeconds(14 * DAY)
        val d30 = now.minusSeconds(30 * DAY)
        return ApiQuery(
            limit = 50,
            filter = Filters.eq(scope.kind.field, scope.id),
            fields = Fields.REPORT_CARD,
            facets = listOf(
                Facet("format.id", name = OVERVIEW_FORMATS, limit = 20),
                Facet("source.shortname", name = OVERVIEW_ORGS, limit = 5, filter = Filters.dateRange("date.created", from = d30)),
                Facet("format.id", name = OVERVIEW_LAST_7D, limit = 20, filter = Filters.dateRange("date.created", from = d7)),
                Facet("format.id", name = OVERVIEW_PREV_7D, limit = 20, filter = Filters.dateRange("date.created", from = d14, to = d7)),
                when (scope.kind) {
                    Scope.Kind.COUNTRY -> Facet("disaster.id", name = OVERVIEW_DISASTERS, limit = 5, filter = Filters.dateRange("date.created", from = d30))
                    Scope.Kind.DISASTER, Scope.Kind.SOURCE -> Facet("primary_country.id", name = OVERVIEW_COUNTRIES, limit = 8)
                },
            ),
        )
    }

    const val OVERVIEW_FORMATS = "formats"
    const val OVERVIEW_ORGS = "orgs_30d"
    const val OVERVIEW_LAST_7D = "last_7d"
    const val OVERVIEW_PREV_7D = "prev_7d"
    const val OVERVIEW_DISASTERS = "disasters_30d"
    const val OVERVIEW_COUNTRIES = "countries"

    /** Statuses of a disaster that is not over. "current" is the older name of "ongoing". */
    val OPEN_STATUSES = listOf("alert", "current", "ongoing")

    /**
     * Crises linked to a country, or sharing a crisis's countries (call C): alert or ongoing,
     * the latest event first. [excludeId] leaves out the crisis whose page is open.
     */
    fun relatedDisasters(countryIds: Collection<Long>, excludeId: Long? = null, limit: Int = 10): ApiQuery = ApiQuery(
        preset = null,
        limit = limit,
        sort = listOf("date.event:desc"),
        filter = Filters.and(
            Filters.anyOf("country.id", countryIds),
            Filters.anyOfText("status", OPEN_STATUSES),
            excludeId?.let { Filters.not(Filters.eq("id", it)) },
        ),
        fields = Fields.DISASTER_CARD,
    )

    /** "New disasters this week" (tour-a2.md 6.3): alert or ongoing, recorded since [since]. */
    fun newDisasters(since: Instant, limit: Int = 5): ApiQuery = ApiQuery(
        preset = null,
        limit = limit,
        sort = listOf("date.created:desc"),
        filter = Filters.and(
            Filters.anyOfText("status", OPEN_STATUSES),
            Filters.dateRange("date.created", from = since),
        ),
        fields = Fields.DISASTER_CARD,
    )

    /** "Follow a crisis": every disaster that is not over, the latest first. */
    fun openDisasters(limit: Int = 200): ApiQuery = ApiQuery(
        preset = null,
        limit = limit,
        sort = listOf("date.created:desc"),
        filter = Filters.anyOfText("status", OPEN_STATUSES),
        fields = Fields.DISASTER_CARD,
    )

    private const val DAY = 86_400L

    /** Reports by their ReliefWeb address, as the site resolves its "Most read" lists. */
    fun byUrls(urls: List<String>): ApiQuery = ApiQuery(
        limit = urls.size,
        sort = emptyList(),
        query = urls.joinToString(" OR ") { MostRead.term(it) },
        fields = Fields.REPORT_FULL,
    )

    // --- Organizations (v0.3, produit/suivre-organisation.md) -------------------------------

    const val SOURCE_WEEKS = "weeks"
    const val SOURCE_WEEKS_REPORTS = "weeks_reports"
    const val SOURCE_COUNTRIES = "countries_12m"
    const val SOURCE_FORMATS = "formats_12m"
    const val SOURCE_SUGGESTIONS = "sources"

    /**
     * The rhythm of an organization (P13 §1): documents per week over 12 weeks, all and reports
     * only; its main countries and its formats over 12 months; and its latest document.
     */
    fun sourceStats(sourceId: Long, now: Instant = Instant.now()): ApiQuery {
        val weeks12 = now.minusSeconds(84 * 86_400L)
        val year = now.minusSeconds(365 * 86_400L)
        val inWeeks = Filters.dateRange("date.created", from = weeks12)
        return ApiQuery(
            limit = 1,
            filter = Filters.eq("source.id", sourceId),
            fields = listOf("id", "date"),
            facets = listOf(
                Facet("date.created", name = SOURCE_WEEKS, interval = "week", filter = inWeeks),
                Facet("date.created", name = SOURCE_WEEKS_REPORTS, interval = "week", filter = Filters.and(inWeeks, Filters.anyOf("format.id", REPORT_FORMATS))),
                Facet("primary_country.shortname", name = SOURCE_COUNTRIES, limit = 3, filter = Filters.dateRange("date.created", from = year)),
                Facet("format.id", name = SOURCE_FORMATS, limit = 20, filter = Filters.dateRange("date.created", from = year)),
            ),
        )
    }

    /** "Follow an organization": organizations whose name, short name or long name match [text]. */
    fun searchSources(text: String, limit: Int = 10): ApiQuery = ApiQuery(
        preset = null,
        limit = limit,
        query = text.trim(),
        queryFields = listOf("name", "shortname", "longname"),
        sort = emptyList(),
        fields = SOURCE_CARD,
    )

    /** Organizations by id, for the names of the suggestions. */
    fun sourcesByIds(ids: Collection<Long>): ApiQuery = ApiQuery(
        preset = null,
        limit = ids.size,
        filter = Filters.anyOf("id", ids),
        sort = emptyList(),
        fields = SOURCE_CARD,
    )

    /**
     * Suggestions (P13 §4.3): the organizations that published analyses or assessments on the
     * followed countries in the last 90 days, as a facet.
     */
    fun sourceSuggestions(countryIds: Collection<Long>, now: Instant = Instant.now(), limit: Int = 16): ApiQuery = ApiQuery(
        limit = 0,
        filter = Filters.and(
            Filters.anyOf("primary_country.id", countryIds),
            Filters.anyOf("format.id", listOf(Ids.FORMAT_ANALYSIS, Ids.FORMAT_ASSESSMENT)),
            Filters.dateRange("date.created", from = now.minusSeconds(90 * 86_400L)),
        ),
        facets = listOf(Facet("source.id", name = SOURCE_SUGGESTIONS, limit = limit)),
    )

    val SOURCE_CARD = listOf("id", "name", "shortname", "longname", "type", "country", "homepage", "status")

    /** Weekly revalidation of saved reports. */
    fun byIds(ids: Collection<Long>): ApiQuery = ApiQuery(
        limit = ids.size.coerceAtMost(1000),
        filter = Filters.anyOf("id", ids),
        fields = Fields.REPORT_FULL,
    )
}
