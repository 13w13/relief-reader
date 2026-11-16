// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * Version numbers as Re.L writes them: "0.5.1", or a tag "v0.5.1". Compared number by number,
 * so 0.10.0 is newer than 0.9.0. Anything else (a suffix such as "-alpha", letters, an empty
 * part) is unreadable, and an unreadable version never shows a notice.
 */
object AppVersion {
    fun parse(text: String?): List<Int>? {
        val t = text?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (t.isEmpty()) return null
        val parts = t.split('.')
        if (parts.size > 4) return null
        return parts.map { p ->
            if (p.isEmpty() || p.length > 6 || !p.all { it in '0'..'9' }) return null
            p.toInt()
        }
    }

    /** Negative, zero or positive, as for compareTo; a missing part counts as 0 (0.5 = 0.5.0). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Whether [candidate] is a readable version newer than the readable [installed] one. */
    fun isNewer(candidate: String?, installed: String?): Boolean {
        val c = parse(candidate) ?: return false
        val i = parse(installed) ?: return false
        return compare(c, i) > 0
    }
}

/** The latest published release: its version without the "v" ("0.5.2") and its page on GitHub. */
data class LatestRelease(val version: String, val url: String)

/**
 * "A new version is available" while Re.L is not on F-Droid (founder, 15/11). At most one
 * request a day, when the app comes to the foreground with a network: GET [LATEST_URL], with no
 * token, no appname and a generic User-Agent. Every failure is silent and waits for the next day.
 * Nothing is downloaded or installed: the notice opens the release page in the browser.
 *
 * @param enabled the UPDATE_CHECK build flag: false (an F-Droid build) means no request, no
 *   notice and no switch.
 * @param installed the installed versionName.
 * @param settings the reader's settings: the switch, the last check, the release found, the
 *   version dismissed with "Not now".
 * @param record notes a check: its time, and the release it found, if any.
 * @param online whether the phone has a network now.
 * @param fetch the body of a 200 answer of [LATEST_URL], or null; may throw.
 */
class UpdateCheck(
    private val enabled: Boolean,
    private val installed: String,
    private val settings: suspend () -> Settings,
    private val record: suspend (at: Long, found: LatestRelease?) -> Unit,
    private val online: () -> Boolean,
    private val fetch: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Sends the request if it is due: the build and the switch allow it, the last check is 24 hours
     * old or more (or in the future, after a clock change), and there is a network. The time is
     * noted whatever the answer, so a failure waits for the next day. Returns whether a request
     * was sent.
     */
    suspend fun checkIfDue(): Boolean {
        if (!enabled) return false
        return mutex.withLock { due() }
    }

    private suspend fun due(): Boolean {
        val s = settings()
        if (!s.updateCheck) return false
        val at = now()
        val since = at - s.updateCheckedAt
        if (since in 0L until DAY) return false
        if (!online()) return false
        val found = try {
            fetch()?.let { parse(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        record(at, found)
        return true
    }

    /** The release to announce on Today, or null. */
    fun notice(s: Settings): LatestRelease? = notice(enabled, s, installed)

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val LATEST_URL = "https://api.github.com/repos/13w13/relief-reader/releases/latest"

        /** A release answer is a few kilobytes; more than this is not one. */
        const val MAX_BYTES = 512L * 1024

        /**
         * A release from the answer of /releases/latest: a readable tag, a page that
         * [releasePage] accepts, not a draft, not a pre-release. Anything else gives null.
         */
        fun parse(text: String): LatestRelease? {
            val obj = runCatching { io.relais.api.RelaisJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            fun flag(name: String) = (obj[name] as? JsonPrimitive)?.booleanOrNull == true
            if (flag("draft") || flag("prerelease")) return null
            val tag = (obj["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim() ?: return null
            if (AppVersion.parse(tag) == null) return null
            val page = (obj["html_url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { releasePage(it) } ?: return null
            return LatestRelease(tag.removePrefix("v").removePrefix("V"), page)
        }

        /**
         * The address when it is the releases page of the repository or the page of one release,
         * in a strict form: https, github.com, no user info, no port, no query, no fragment, and a
         * path that is exactly /13w13/relief-reader/releases or …/releases/tag/<tag>, without any
         * encoded character. Otherwise null: "…/relief-reader/%2e%2e/other" is refused (review of
         * PR 23), since a browser would decode it and leave the repository.
         */
        fun releasePage(url: String): String? {
            val link = url.trim()
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.rawAuthority != "github.com") return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (!RELEASE_PATH.matches(uri.rawPath.orEmpty())) return null
            return link
        }

        /** The releases page, or the page of one tag that starts with a letter or a digit. */
        private val RELEASE_PATH = Regex("^/13w13/relief-reader/releases(/tag/[A-Za-z0-9][A-Za-z0-9._-]*)?$")

        /**
         * The notice: the build and the switch allow it, a release was found, it is newer than
         * the installed version, it is not the one dismissed with "Not now", and its page is safe.
         */
        fun notice(enabled: Boolean, s: Settings, installed: String): LatestRelease? {
            if (!enabled || !s.updateCheck) return null
            val version = s.updateVersion ?: return null
            if (version == s.updateDismissed) return null
            if (!AppVersion.isNewer(version, installed)) return null
            val page = s.updateUrl?.let { releasePage(it) } ?: return null
            return LatestRelease(version, page)
        }
    }
}
