// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import io.relais.api.Ids

/** What an alert announces (produit/passe-v0.2.md, 6.1). */
enum class AlertKind { APPEAL, FLASH_UPDATE }

/** One alert: the followed country or crisis ([scopeKey], [where]) and its newest matching report. */
data class AlertItem(val scopeKey: String, val where: String, val kind: AlertKind, val report: ReportEntity)

/** What to send after a sync: single alerts, or one grouped notification counting [grouped] items. */
data class AlertPlan(val single: List<AlertItem>, val grouped: Int) {
    val isEmpty: Boolean get() = single.isEmpty() && grouped == 0
}

/**
 * "Alerts for new appeals and flash updates" (passe-v0.2.md, 6), off by default: a new appeal
 * (published in the last 30 days) or a document whose title says "flash update" ("flash alert"
 * is left out: the DTM publishes them in series), posted since the last sync, in a followed
 * country or crisis. At most one alert a day per followed item, three a day in all; beyond,
 * one grouped notification.
 */
object Alerts {
    const val PER_DAY = 3
    private const val DAY = 24 * 60 * 60 * 1000L

    fun kindOf(r: ReportEntity, now: Long): AlertKind? = when {
        r.formatId == Ids.FORMAT_APPEAL && now - (r.dateOriginal ?: r.dateCreated) <= 30 * DAY -> AlertKind.APPEAL
        Pinned.isFlashUpdate(r) -> AlertKind.FLASH_UPDATE
        else -> null
    }

    /** The newest matching report of each followed country ("country:220") and crisis ("disaster:52586"). */
    fun candidates(
        reports: List<ReportEntity>,
        since: Long,
        now: Long,
        countries: Map<Long, String>,
        crises: Map<Long, String>,
    ): List<AlertItem> {
        val found = LinkedHashMap<String, AlertItem>()
        for (r in reports.filter { it.dateCreated > since && !it.fromRss }.sortedByDescending { it.dateCreated }) {
            val kind = kindOf(r, now) ?: continue
            r.primaryCountryId?.let { id ->
                countries[id]?.let { name -> found.putIfAbsent("country:$id", AlertItem("country:$id", name, kind, r)) }
            }
            r.disasters.orEmpty().forEach { d ->
                crises[d.id]?.let { name -> found.putIfAbsent("disaster:${d.id}", AlertItem("disaster:${d.id}", name, kind, r)) }
            }
        }
        // One report followed twice (its country and its crisis) is announced once.
        return found.values.distinctBy { it.report.id }
    }

    /** [sentToday] counts the alerts of the day; a grouped notification fills the day. */
    fun plan(candidates: List<AlertItem>, alertedToday: Set<String>, sentToday: Int): AlertPlan {
        val fresh = candidates.filter { it.scopeKey !in alertedToday }
        val room = PER_DAY - sentToday
        return when {
            fresh.isEmpty() || sentToday > PER_DAY -> AlertPlan(emptyList(), 0)
            fresh.size <= room -> AlertPlan(fresh, 0)
            else -> AlertPlan(emptyList(), fresh.size)
        }
    }
}
