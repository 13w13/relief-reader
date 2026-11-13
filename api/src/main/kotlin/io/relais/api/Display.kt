// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How dates, counts and names are written on screen. English only for now. */
object Display {
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val WEEKDAY_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private const val SEVEN_DAYS_MS = 7L * 24 * 3600 * 1000

    /** "852 KB" under 1 MB, "5.3 MB" above. Decimal units, as Android's storage settings. */
    fun size(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        return if (bytes < 1_000_000) {
            "${maxOf(1, Math.round(bytes / 1000.0))} KB"
        } else {
            String.format(Locale.ENGLISH, "%.1f MB", bytes / 1_000_000.0)
        }
    }

    /** Short format name for cards; full API name on the report page. */
    fun shortFormat(formatId: Long?, apiName: String?): String {
        val id = formatId ?: apiName?.let { FormatNames.ID_BY_NAME[it] }
        return when (id) {
            Ids.FORMAT_SITREP -> "Situation report"
            Ids.FORMAT_ANALYSIS -> "Analysis"
            Ids.FORMAT_NEWS -> "News release"
            Ids.FORMAT_MAP -> "Map"
            Ids.FORMAT_INFOGRAPHIC -> "Infographic"
            Ids.FORMAT_APPEAL -> "Appeal"
            Ids.FORMAT_ASSESSMENT -> "Assessment"
            Ids.FORMAT_EVALUATION -> "Evaluation"
            Ids.FORMAT_GUIDELINE -> "Guideline"
            Ids.FORMAT_UN_DOCUMENT -> "UN document"
            Ids.FORMAT_INTERACTIVE -> "Interactive"
            Ids.FORMAT_OTHER -> "Other"
            else -> apiName ?: ""
        }
    }

    /** "Today", "Yesterday", "6 Nov", or "6 Nov 2025" outside the current year. */
    fun day(epochMillis: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> DAY_MONTH.format(date)
            else -> DAY_MONTH_YEAR.format(date)
        }
    }

    /** Full date for the report page: "4 Nov 2026". */
    fun fullDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DAY_MONTH_YEAR.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "Saturday 7 November". */
    fun longDate(date: LocalDate): String = LONG_DATE.format(date)

    /** "Thu 12 Nov", for "The latest is from Thu 12 Nov." on Today (P14). */
    fun weekdayDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        WEEKDAY_DAY.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * The date line of a card: "6 Nov", or "Published 10 Aug, posted 6 Nov" when the original
     * publication is more than 7 days older than the posting on ReliefWeb.
     */
    fun cardDate(
        created: Long,
        original: Long?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (original != null && created - original > SEVEN_DAYS_MS) {
            return "Published ${day(original, now, zone)}, posted ${day(created, now, zone)}"
        }
        return day(created, now, zone)
    }

    /** "Sudan +7" for a primary country and seven others. */
    fun countryContext(primary: String?, otherCount: Int): String = when {
        primary.isNullOrBlank() -> ""
        otherCount > 0 -> "$primary +$otherCount"
        else -> primary
    }

    /** Domain of a URL without "www.", for "Read the original on msf.org". */
    fun domain(url: String?): String? = runCatching {
        java.net.URI(url ?: return null).host?.removePrefix("www.")
    }.getOrNull()

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    private val MOIS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private val SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val COVERS_FORMATS = setOf(Ids.FORMAT_SITREP, Ids.FORMAT_INFOGRAPHIC, Ids.FORMAT_MAP)

    private fun month(word: String): Int = MONTHS.indexOf(word).takeIf { it >= 0 } ?: MOIS.indexOf(word)

    /**
     * The period a situation report, map or infographic covers, read from its title
     * (port of covers() in the art director's html/app2.js). "1 to 31 August 2026" gives
     * "Covers 1–31 Aug"; "January – September 2026" gives "Covers Jan–Sep 2026";
     * "as of 27 October 2026" gives "Covers up to 27 Oct". Null when the title gives no period.
     */
    fun covers(title: String, formatId: Long?, formatName: String?): String? {
        val id = formatId ?: formatName?.let { FormatNames.ID_BY_NAME[it] }
        if (id !in COVERS_FORMATS) return null
        val t = title.lowercase()
            .replace(Regex("publication date:.*$"), "")
            .replace(Regex("publi[ée]e? le .*$"), "")
        val w = "[a-zéû]+"
        Regex("(\\d{1,2}) (?:to|au) (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[3])
            if (m >= 0) return "Covers ${r.groupValues[1]}–${r.groupValues[2]} ${SHORT[m]}"
        }
        Regex("du (\\d{1,2}) au (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[3])
            if (m >= 0) return "Covers ${r.groupValues[1]}–${r.groupValues[2]} ${SHORT[m]}"
        }
        Regex("($w) ?[–-] ?($w) (\\d{4})").find(t)?.let { r ->
            val a = month(r.groupValues[1])
            val b = month(r.groupValues[2])
            if (a >= 0 && b >= 0) return "Covers ${SHORT[a]}–${SHORT[b]} ${r.groupValues[3]}"
        }
        Regex("(?:as of|au) (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[2])
            if (m >= 0) return "Covers up to ${r.groupValues[1]} ${SHORT[m]}"
        }
        Regex("(?:^|[^\\d])(\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[2])
            if (m >= 0) return "Covers up to ${r.groupValues[1]} ${SHORT[m]}"
        }
        Regex("($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[1])
            if (m >= 0) return "Covers ${SHORT[m]} ${r.groupValues[2]}"
        }
        return null
    }

    /**
     * Last day of the period a title says it covers, as [covers] reads it: "1 to 31 August 2026"
     * gives 31 Aug 2026, "July 2026" gives 31 Jul 2026. Null when the title gives no period.
     */
    fun coverageEnd(title: String, formatId: Long?, formatName: String?): LocalDate? {
        val id = formatId ?: formatName?.let { FormatNames.ID_BY_NAME[it] }
        if (id !in COVERS_FORMATS) return null
        val t = title.lowercase()
            .replace(Regex("publication date:.*$"), "")
            .replace(Regex("publi[ée]e? le .*$"), "")
        val w = "[a-zéû]+"
        fun date(y: String, m: Int, d: String?): LocalDate? = runCatching {
            val month = LocalDate.of(y.toInt(), m + 1, 1)
            if (d == null) month.withDayOfMonth(month.lengthOfMonth()) else month.withDayOfMonth(d.toInt())
        }.getOrNull()
        Regex("(\\d{1,2}) (?:to|au) (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[3])
            if (m >= 0) return date(r.groupValues[4], m, r.groupValues[2])
        }
        Regex("du (\\d{1,2}) au (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[3])
            if (m >= 0) return date(r.groupValues[4], m, r.groupValues[2])
        }
        Regex("($w) ?[–-] ?($w) (\\d{4})").find(t)?.let { r ->
            val a = month(r.groupValues[1])
            val b = month(r.groupValues[2])
            if (a >= 0 && b >= 0) return date(r.groupValues[3], b, null)
        }
        Regex("(?:as of|au) (\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[2])
            if (m >= 0) return date(r.groupValues[3], m, r.groupValues[1])
        }
        Regex("(?:^|[^\\d])(\\d{1,2}) ($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[2])
            if (m >= 0) return date(r.groupValues[3], m, r.groupValues[1])
        }
        Regex("($w) (\\d{4})").find(t)?.let { r ->
            val m = month(r.groupValues[1])
            if (m >= 0) return date(r.groupValues[2], m, null)
        }
        return null
    }

    /**
     * "Covers Jul 2026, 4 months ago" when the period a title covers ended more than a month ago
     * (revue-lecteur-v0.2.md, defect 1): an old period said plainly. Null otherwise.
     */
    fun oldCoverage(title: String, formatId: Long?, formatName: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val end = coverageEnd(title, formatId, formatName) ?: return null
        val today = now.atZone(zone).toLocalDate()
        if (!end.isBefore(today.minusDays(31))) return null
        val months = maxOf(1L, ChronoUnit.MONTHS.between(end, today))
        val covers = covers(title, formatId, formatName) ?: return null
        return "$covers, $months ${if (months == 1L) "month" else "months"} ago"
    }

    /** Kicker time: "08:12" today, "Yesterday", else "29 Oct". */
    fun kickerTime(epochMillis: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return if (date == now.atZone(zone).toLocalDate()) time(epochMillis, zone) else day(epochMillis, now, zone)
    }

    fun daysBetween(fromMillis: Long, toMillis: Long): Long =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(fromMillis), Instant.ofEpochMilli(toMillis))
}
