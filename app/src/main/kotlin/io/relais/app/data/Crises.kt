// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.ApiDates
import io.relais.api.ApiItem
import io.relais.api.DisasterFields
import io.relais.api.Ids
import io.relais.api.MostRead
import io.relais.api.Profile
import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.abs

// Track A of v0.2 (PLAN.md 0quater): crises, overview, language twins, pinned items.

/** A crisis (a ReliefWeb disaster) as the app shows it in lists. */
@Serializable
data class DisasterInfo(
    val id: Long,
    val name: String,
    /** alert, ongoing (or current), past. */
    val status: String? = null,
    val typeName: String? = null,
    val glide: String? = null,
    /** Date of the event, and the date it was listed on ReliefWeb. */
    val eventAt: Long? = null,
    val createdAt: Long? = null,
    val countries: List<Ref> = emptyList(),
    val url: String? = null,
    /** Main country of the crisis, when ReliefWeb gives one. */
    val primaryCountryId: Long? = null,
) {
    val isOpen: Boolean get() = status in OPEN_STATUSES

    fun toFollowed(now: Long, lastVisitAt: Long) = FollowedDisasterEntity(
        disasterId = id,
        name = name,
        followedAt = now,
        lastVisitAt = lastVisitAt,
        glide = glide,
        typeName = typeName,
        status = status,
        countries = countries,
    )

    companion object {
        val OPEN_STATUSES = setOf("alert", "current", "ongoing")
    }
}

fun FollowedDisasterEntity.toInfo() = DisasterInfo(
    id = disasterId,
    name = name,
    status = status,
    typeName = typeName,
    glide = glide,
    countries = countries,
)

fun ApiItem<DisasterFields>.toInfo(): DisasterInfo {
    val f = fields
    return DisasterInfo(
        id = id,
        name = f.name,
        status = f.status,
        typeName = f.typeName,
        glide = f.glide?.takeIf { it.isNotBlank() },
        eventAt = ApiDates.parse(f.date?.event),
        createdAt = ApiDates.parse(f.date?.created),
        countries = f.country.filter { it.id != 0L }.map { Ref(it.id, it.displayName) },
        url = f.urlAlias ?: f.url,
        primaryCountryId = f.primaryCountry?.id?.takeIf { it != 0L },
    )
}

/** A current appeal or plan listed by ReliefWeb's editors on a country or crisis profile. */
@Serializable
data class ProfileAppeal(val title: String, val url: String?, val reportId: Long?)

fun Profile?.appeals(): List<ProfileAppeal> = this?.appeals?.active.orEmpty().mapNotNull { link ->
    val title = link.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    ProfileAppeal(title, link.url, link.url?.let(MostRead::nodeId))
}

/** What the overview knows of its country or crisis (call A, kept 24 h). */
@Serializable
data class ScopeInfo(
    /** The crisis itself, for a crisis page. */
    val disaster: DisasterInfo? = null,
    val descriptionHtml: String? = null,
    val appeals: List<ProfileAppeal> = emptyList(),
    /** The organization itself, for an organization page (v0.3). */
    val source: SourceInfo? = null,
)

/** An organization and its rhythm on ReliefWeb (P13 §1; theme-compose.md 13.1). */
@Serializable
data class SourceInfo(
    val id: Long,
    val shortname: String,
    val name: String,
    val typeName: String? = null,
    val countryName: String? = null,
    val homepage: String? = null,
    val descriptionHtml: String? = null,
    /** Documents a week, the mean of the last 12 weeks; all, then reports only. */
    val perWeek: Double = 0.0,
    val reportsPerWeek: Double = 0.0,
    val latestAt: Long? = null,
    /** Its 3 main countries over 12 months, by short name. */
    val mainCountries: List<String> = emptyList(),
    /** Format id to documents over 12 months: the filter row hides the empty ones (13.1). */
    val formats: Map<Long, Int> = emptyMap(),
) {
    /** Over 10 documents a week, following asks first (P13 §5). */
    val large: Boolean get() = perWeek > LARGE_PER_WEEK

    companion object {
        const val LARGE_PER_WEEK = 10.0
    }
}

/**
 * Language twins: the same document posted in two languages, shown once. Rule version 2,
 * measured on 1,167 real reports: 102 documents folded, no wrong merge, reviewed by hand.
 *
 * 1. Candidates: same main country, same organizations, same format, no language in common
 *    ("ot", other language, left out), published at most 3 days apart.
 * 2. A strong signal. S1, numbers of the text: 4 or more on each side and 80 % in common, or 3
 *    or more in common and 50 %. S2, numbers and months of the title, the same set (2 or more),
 *    with the same page count (15 % apart for 10 pages or more). S3, the same file name once the
 *    language marks are removed (6 characters or more), page counts one apart at most.
 * 3. Vetoes: page counts more than 20 % apart (unless S1); a file of more than 10 pages facing
 *    no file; two documents of one language matching the same document (ambiguous).
 * Groups join their pairs. The main version is the headline, else the English one, else the
 * first posted.
 */
data class TwinGroup(val main: ReportEntity, val others: List<ReportEntity>)

object LanguageTwins {
    private val NAMES = mapOf("en" to "English", "fr" to "French", "es" to "Spanish", "ar" to "Arabic", "ru" to "Russian", "pt" to "Portuguese")

    /** "French" for "fr" (the API's language code, as stored), else the value itself. */
    fun languageName(code: String): String = NAMES[code.lowercase()] ?: code

    private const val DAY = 24 * 60 * 60 * 1000L
    const val WINDOW_DAYS = 3

    private val MARKS = Regex("\\p{M}+")

    /** Lower case without accents, as unicodedata NFKD in the Produit's scripts. */
    private fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")

    private fun ReportEntity.publishedDay(): Long = Math.floorDiv(dateOriginal ?: dateCreated, DAY)

    /** Page count of the first file that has one. */
    private fun ReportEntity.pages(): Long? = files.firstNotNullOfOrNull { f -> f.pages?.takeIf { it != 0L } }

    private fun ReportEntity.languageSet(): Set<String> = languages.toSet() - "ot"

    private fun ReportEntity.languageKey(): List<String> = languages.sorted()

    fun isCandidate(a: ReportEntity, b: ReportEntity): Boolean {
        if (a.id == b.id || a.primaryCountryId != b.primaryCountryId || a.formatId != b.formatId) return false
        if (a.sources.map { it.id }.sorted() != b.sources.map { it.id }.sorted()) return false
        val la = a.languageSet()
        val lb = b.languageSet()
        if (la.isEmpty() || lb.isEmpty() || la.any { it in lb }) return false
        return abs(a.publishedDay() - b.publishedDay()) <= WINDOW_DAYS
    }

    private val TAGS = Regex("<[^>]+>")
    // Explicit Unicode classes: Android's regex engine (ICU) refuses the (?U) flag of Java, and
    // Python's \d and \b (the Produit's scripts) are Unicode.
    private val THOUSANDS = Regex("(?<=\\p{Nd})[ ,.\\u00A0\\u202F](?=\\p{Nd}{3}(?![\\p{L}\\p{N}_]))")
    private val DIGITS = Regex("\\p{Nd}+")

    /** Numbers of two digits or more of a text (HTML allowed), thousands separators removed. */
    fun numbers(html: String?): Set<String> {
        if (html.isNullOrEmpty()) return emptySet()
        val text = THOUSANDS.replace(TAGS.replace(html, " "), "")
        return DIGITS.findAll(text).map { it.value }.filter { it.length >= 2 }.toSet()
    }

    private val MONTHS: Map<String, Int> = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6, "juillet" to 7, "aout" to 8,
        "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12,
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8,
        "septiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9,
        "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val TOKEN = Regex("[a-z]+|\\p{Nd}+(?:[.,]\\p{Nd}+)?")

    /** Numbers and months of a title, in order: "27 October" and "27 octobre" give 27, m10. */
    fun titleKey(title: String): List<String> = TOKEN.findAll(norm(title)).mapNotNull { m ->
        val w = m.value
        if (w[0].isDigit()) w.replace(",", "").replace(".", "") else MONTHS[w]?.let { "m$it" }
    }.toList()

    private val PDF = Regex("\\.pdf$")
    private val LANGUAGE_MARK = Regex("(^|[^a-z])(en|eng|english|fr|fre|fra|french|francais|es|esp|spa|spanish|ar|ara|arabic|ru)([^a-z]|$)")
    private val NOT_ALNUM = Regex("[^a-z0-9]")

    /** A file name without ".pdf", its language marks ("EN", "_fr") and its punctuation. */
    fun fileStem(name: String?): String =
        NOT_ALNUM.replace(LANGUAGE_MARK.replace(PDF.replace(norm(name.orEmpty()), ""), "$1$3"), "")

    /** The strong signals of a candidate pair: "S1", "S2", "S3", in that order. */
    fun signals(a: ReportEntity, b: ReportEntity, numbers: (ReportEntity) -> Set<String>): List<String> {
        val out = ArrayList<String>(3)
        val na = numbers(a)
        val nb = numbers(b)
        val shared = na.count { it in nb }
        val jaccard = if (na.isEmpty() || nb.isEmpty()) 0.0 else shared.toDouble() / (na + nb).size
        if ((na.size >= 4 && nb.size >= 4 && jaccard >= 0.8) || (shared >= 3 && jaccard >= 0.5)) out += "S1"
        val pa = a.pages()
        val pb = b.pages()
        val pagesOk = pa == null || pb == null || pa == pb || (maxOf(pa, pb) >= 10 && abs(pa - pb) <= 0.15 * maxOf(pa, pb))
        val ta = titleKey(a.title)
        if (ta.size >= 2 && ta.sorted() == titleKey(b.title).sorted() && pagesOk) out += "S2"
        val stems = a.files.map { fileStem(it.filename) }.toSet().intersect(b.files.map { fileStem(it.filename) }.toSet())
        val near = pa == null || pb == null || abs(pa - pb) <= 1 || pagesOk
        if (stems.any { it.length >= 6 } && near) out += "S3"
        return out
    }

    fun vetoed(a: ReportEntity, b: ReportEntity, signals: List<String>): Boolean {
        val pa = a.pages()
        val pb = b.pages()
        if (pa != null && pb != null && abs(pa - pb) > 0.2 * maxOf(pa, pb) && "S1" !in signals) return true
        // A long report is not the translation of a text with no file.
        return (pa != null && pa > 10 && pb == null) || (pb != null && pb > 10 && pa == null)
    }

    data class TwinPair(val a: ReportEntity, val b: ReportEntity, val signals: List<String>)

    /** The pairs to merge in [reports], ambiguous ones removed. [bodyHtml] gives a report's text. */
    fun pairs(reports: List<ReportEntity>, bodyHtml: (ReportEntity) -> String? = { it.bodyHtml }): List<TwinPair> {
        val cache = HashMap<Long, Set<String>>()
        val numbersOf: (ReportEntity) -> Set<String> = { r -> cache.getOrPut(r.id) { numbers(bodyHtml(r)) } }
        val edges = ArrayList<TwinPair>()
        for (sameCountry in reports.distinctBy { it.id }.groupBy { it.primaryCountryId }.values) {
            for (i in sameCountry.indices) for (j in i + 1 until sameCountry.size) {
                val a = sameCountry[i]
                val b = sameCountry[j]
                if (!isCandidate(a, b)) continue
                val s = signals(a, b, numbersOf)
                if (s.isNotEmpty() && !vetoed(a, b, s)) edges += TwinPair(a, b, s)
            }
        }
        val partners = HashMap<Long, MutableList<ReportEntity>>()
        edges.forEach { e ->
            partners.getOrPut(e.a.id) { mutableListOf() } += e.b
            partners.getOrPut(e.b.id) { mutableListOf() } += e.a
        }
        fun ambiguous(u: ReportEntity, w: ReportEntity) = partners[u.id].orEmpty().count { it.languageKey() == w.languageKey() } > 1
        return edges.filter { e -> !ambiguous(e.a, e.b) && !ambiguous(e.b, e.a) }
    }

    /** Groups in list order: a group takes the place of its first document. */
    fun group(reports: List<ReportEntity>, bodyHtml: (ReportEntity) -> String? = { it.bodyHtml }): List<TwinGroup> {
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            return r
        }
        pairs(reports, bodyHtml).forEach { e -> parent.putIfAbsent(e.a.id, e.a.id); parent.putIfAbsent(e.b.id, e.b.id); parent[find(e.a.id)] = find(e.b.id) }
        val members = reports.distinctBy { it.id }.groupBy { if (it.id in parent) find(it.id) else -it.id - 1 }
        val emitted = HashSet<Long>()
        val out = ArrayList<TwinGroup>()
        for (r in reports) {
            val root = if (r.id in parent) find(r.id) else -r.id - 1
            if (!emitted.add(root)) continue
            val group = members.getValue(root)
            val main = group.firstOrNull { it.isHeadline }
                ?: group.firstOrNull { g -> g.languages.any { it.equals("en", true) || it.equals("English", true) } }
                ?: group.minBy { it.dateCreated }
            out += TwinGroup(main, group.filter { it.id != main.id })
        }
        return out
    }
}

/** What a pinned slot holds (produit/passe-v0.2.md, 3). */
enum class PinKind { OCHA, FLASH_UPDATE, APPEAL }

data class Pin(val kind: PinKind, val report: ReportEntity)

/**
 * Pinned above the documents of a country or crisis (produit/passe-v0.2.md 3.1, checked on Chad
 * and Senegal), computed on the 50 latest documents of the overview (call B):
 * - "Latest from OCHA": OCHA's latest situation report, or a document of OCHA whose title says
 *   flash update, situation report, rapport de situation, situation update, humanitarian
 *   update, aperçu de la situation or informe de situación, posted in the last 30 days; else
 *   "Latest flash update": the latest document of any organization whose title says "flash
 *   update" or "flash alert", posted in the last 14 days.
 * - "New appeal": up to two appeals posted in the last 14 days and published in the last 30 days.
 */
object Pinned {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val MARKS = Regex("\\p{M}+")
    private val FLASH_UPDATE = Regex("flash[ -]update", RegexOption.IGNORE_CASE)
    private val FLASH_ANY = Regex("flash[ -](update|alert)", RegexOption.IGNORE_CASE)
    private val OCHA_TITLES = listOf(
        "flash update", "situation report", "rapport de situation", "situation update",
        "humanitarian update", "apercu de la situation", "informe de situacion",
    )

    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "").replace('-', ' ')

    fun isOchaUpdate(r: ReportEntity): Boolean {
        if (r.sources.none { it.shortname.equals("OCHA", ignoreCase = true) }) return false
        if (r.formatId == Ids.FORMAT_SITREP) return true
        val title = plain(r.title)
        return OCHA_TITLES.any { it in title }
    }

    /** A flash update, for the alerts (passe-v0.2.md 6.1); "flash alert" is left out there. */
    fun isFlashUpdate(r: ReportEntity): Boolean = FLASH_UPDATE.containsMatchIn(r.title)

    /** Words of a crisis's short name that say what it is: "ebola" in "Ebola Outbreak". */
    private val GENERIC = setOf(
        "outbreak", "outbreaks", "flood", "floods", "flash", "epidemic", "earthquake", "storm", "storms", "cyclone",
        "landslide", "landslides", "and", "crisis", "tropical", "drought", "fire", "fires", "wildfires", "cold", "wave",
    )

    /**
     * On a crisis page, OCHA's update and the flash update are pinned only if they are about the
     * crisis: its title names it, or its main country is the crisis's (revue-lecteur-v0.2.md,
     * defect 5: a general update on South Sudan sat on top of the Ebola page).
     */
    fun aboutCrisis(r: ReportEntity, crisis: DisasterInfo): Boolean {
        if (crisis.primaryCountryId != null && r.primaryCountryId == crisis.primaryCountryId) return true
        val short = plain(crisis.name.replace(Regex("^[^:]+:\\s*"), "").replace(Regex("\\s*[-\u2013]\\s*[A-Za-z]{3,9}\\.? \\d{4}$"), ""))
        val words = short.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in GENERIC }
        val title = plain(r.title)
        return words.any { it in title }
    }

    fun pick(reports: List<ReportEntity>, now: Long, hidden: Set<Long> = emptySet(), crisis: DisasterInfo? = null): List<Pin> {
        val sorted = reports.filter { it.id !in hidden }.sortedByDescending { it.dateCreated }
        val relevant: (ReportEntity) -> Boolean = { r -> crisis == null || aboutCrisis(r, crisis) }
        val first = sorted.firstOrNull { now - it.dateCreated <= 30 * DAY && isOchaUpdate(it) && relevant(it) }?.let { Pin(PinKind.OCHA, it) }
            ?: sorted.firstOrNull { now - it.dateCreated <= 14 * DAY && FLASH_ANY.containsMatchIn(it.title) && relevant(it) }
                ?.let { Pin(PinKind.FLASH_UPDATE, it) }
        val appeals = sorted.filter {
            it.formatId == Ids.FORMAT_APPEAL && it.id != first?.report?.id &&
                now - it.dateCreated <= 14 * DAY && now - (it.dateOriginal ?: it.dateCreated) <= 30 * DAY
        }.take(2).map { Pin(PinKind.APPEAL, it) }
        return listOfNotNull(first) + appeals
    }
}
