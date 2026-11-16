// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
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

/**
 * The latest published release: its version without the "v" ("0.5.2"), its page on GitHub, and
 * the address of its APK when the release has exactly one that [UpdateCheck.apkLink] accepts.
 */
data class LatestRelease(val version: String, val url: String, val apk: String? = null)

/**
 * "Check now" in Settings > About (founder, 16/11): its state, shown under the button. Unlike the
 * automatic check, a failure is said, with its reason ("no connection", "HTTP 403").
 */
sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Checking : ManualCheck
    /** The latest release is not newer than [installed]. */
    data class UpToDate(val installed: String) : ManualCheck
    data class Available(val release: LatestRelease) : ManualCheck
    data class Failed(val reason: String) : ManualCheck
}

/**
 * "A new version is available" while Re.L is not on F-Droid (founder, 15/11). When the app comes
 * to the foreground with a network, at most once every [INTERVAL] (an hour since 16/11: the
 * founder's colleagues should not stay on old versions): GET [LATEST_URL], with no token, no
 * appname and a generic User-Agent. Every failure is silent and waits for the next interval; a
 * quota refusal (403, 429) waits until the time GitHub gives, if later. [checkNow] asks at once,
 * when the reader taps "Check now" (founder, 16/11), and says its result.
 * Nothing is downloaded or installed by the app: the release page and the APK open in the browser.
 *
 * @param enabled the UPDATE_CHECK build flag: false (an F-Droid build) means no request, no
 *   notice and no switch.
 * @param installed the installed versionName.
 * @param settings the reader's settings: the switch, the last check, the release found, the
 *   version dismissed with "Not now" and when, the time before which GitHub asked not to come back.
 * @param record notes a check: its time, and the release it found, if any.
 * @param online whether the phone has a network now.
 * @param fetch the body of a 200 answer of [LATEST_URL], or null; may throw, for another status
 *   with [HttpStatusException], for a quota refusal with [RateLimitedException].
 * @param hold notes the time before which no automatic request goes (a quota refusal).
 * @param forgetNotNow clears "Not now": a Check now that finds a newer version shows it on Today
 *   too (review of PR 44), since the reader just asked.
 */
class UpdateCheck(
    private val enabled: Boolean,
    private val installed: String,
    private val settings: suspend () -> Settings,
    private val record: suspend (at: Long, found: LatestRelease?) -> Unit,
    private val online: () -> Boolean,
    private val fetch: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val hold: suspend (until: Long) -> Unit = {},
    private val forgetNotNow: suspend () -> Unit = {},
) {
    /** One request at a time, the daily one and "Check now" together. */
    private val mutex = Mutex()

    /** The time and the result of the last request sent, daily or "Check now"; in memory only. */
    private var last: Pair<Long, ManualCheck>? = null

    private val _manual = MutableStateFlow<ManualCheck>(ManualCheck.Idle)

    /** When the result of "Check now" was given, for [forgetOldResult]. */
    private var resultAt = 0L

    /** The state of "Check now", for Settings > About. */
    val manual: StateFlow<ManualCheck> = _manual.asStateFlow()

    /**
     * A result older than [RESULT_FOR] goes back to Idle (review of PR 44): Settings opened hours
     * later does not show "is available" from the morning. Called when Settings comes on screen;
     * a rotation, a few seconds later, keeps it. A check under way is left alone.
     */
    fun forgetOldResult() {
        val state = _manual.value
        if (state != ManualCheck.Idle && state != ManualCheck.Checking && now() - resultAt !in 0L until RESULT_FOR) {
            _manual.value = ManualCheck.Idle
        }
    }

    private fun show(result: ManualCheck) {
        resultAt = now()
        _manual.value = result
    }

    /**
     * Sends the request if it is due: the build and the switch allow it, the last check is
     * [INTERVAL] old or more (or in the future, after a clock change), GitHub's quota time has
     * passed, and there is a network. The time is noted whatever the answer, so a failure waits
     * for the next interval; offline, nothing is noted. Returns whether a request was sent.
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
        if (since in 0L until INTERVAL) return false
        // A quota refusal: not before the time GitHub gave; a time more than a day away (a clock
        // change, an odd header) is ignored.
        if (at < s.updateHeldUntil && s.updateHeldUntil - at <= DAY) return false
        if (!online()) return false
        request(at)
        return true
    }

    /**
     * "Check now": asks at once, without the interval and even with the switch off, since a tap
     * is an explicit request. Never more than one request every [MIN_GAP] (GitHub allows 60 an
     * hour per address): a tap sooner gets the result of the last request again. A tap while a
     * request is under way waits for it and gets its result. Offline, it fails without a request.
     * The build flag off (F-Droid) means no request at all, and no button.
     */
    suspend fun checkNow(): ManualCheck {
        if (!enabled) return ManualCheck.Idle
        _manual.value = ManualCheck.Checking
        val result = try {
            mutex.withLock {
                val at = now()
                val previous = last
                when {
                    previous != null && at - previous.first in 0L until MIN_GAP -> previous.second
                    !online() -> ManualCheck.Failed(NO_CONNECTION)
                    else -> request(at)
                }
            }.also { if (it is ManualCheck.Available) forgetNotNow() }
        } catch (e: CancellationException) {
            _manual.value = ManualCheck.Idle
            throw e
        } catch (e: Exception) {
            // Network failures are answered inside request(): what comes here is the settings
            // store that could not be written (a full disk). Said, never "Checking…" for good
            // (review of PR 44), and never as "no connection".
            ManualCheck.Failed(NOT_SAVED)
        }
        show(result)
        return result
    }

    /**
     * One request: its time noted whatever the answer, the release noted when one is read (so
     * Today's notice follows), and the result kept for [checkNow].
     */
    private suspend fun request(at: Long): ManualCheck {
        var found: LatestRelease? = null
        val result = try {
            found = fetch()?.let { parse(it) }
            val release = found
            when {
                release == null -> ManualCheck.Failed(UNREADABLE)
                AppVersion.isNewer(release.version, installed) -> ManualCheck.Available(release)
                else -> ManualCheck.UpToDate(installed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            // Never a retry loop: the next automatic request waits for GitHub's time, or the interval.
            hold(e.until ?: (at + INTERVAL))
            ManualCheck.Failed(failureReason(e))
        } catch (e: Exception) {
            ManualCheck.Failed(failureReason(e))
        }
        record(at, found)
        last = at to result
        return result
    }

    /** The release to announce on Today, or null. */
    fun notice(s: Settings): LatestRelease? = notice(enabled, s, installed, now())

    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR

        /** The automatic check: at most one request in this time (founder, 16/11; a day before). */
        const val INTERVAL = HOUR

        /** "Not now" hides the notice of this version for this time; a newer version shows at once. */
        const val NOT_NOW_FOR = 3 * DAY

        /** "Check now": at most one request in this time, however often the button is tapped. */
        const val MIN_GAP = 30_000L

        /** How long the result of "Check now" stays under the button (see forgetOldResult). */
        const val RESULT_FOR = 5 * 60 * 1000L

        const val NO_CONNECTION = "no connection"
        const val NOT_SAVED = "could not save the result"
        const val UNREADABLE = "unreadable answer"
        const val LATEST_URL = "https://api.github.com/repos/13w13/relief-reader/releases/latest"

        /** A release answer is a few kilobytes; more than this is not one. */
        const val MAX_BYTES = 512L * 1024

        /**
         * A release from the answer of /releases/latest: a readable tag, a page that
         * [releasePage] accepts, not a draft, not a pre-release. Anything else gives null. The
         * APK is read from its assets by [apkAsset]; a release without a valid one has none.
         * Read by hand from the JSON tree, with no @Serializable class: nothing for R8 to keep.
         */
        fun parse(text: String): LatestRelease? {
            val obj = runCatching { io.relais.api.RelaisJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            fun flag(name: String) = (obj[name] as? JsonPrimitive)?.booleanOrNull == true
            if (flag("draft") || flag("prerelease")) return null
            val tag = obj.string("tag_name")?.trim() ?: return null
            if (AppVersion.parse(tag) == null) return null
            val page = obj.string("html_url")?.let { releasePage(it) } ?: return null
            val version = tag.removePrefix("v").removePrefix("V")
            return LatestRelease(version, page, apkAsset(obj["assets"], version))
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * The APK of a release: among its assets, the files whose name or address ends in ".apk".
         * Exactly one, and [apkLink] must accept its browser_download_url; otherwise null. The
         * mapping (.txt) and the checksum (.apk.sha256) are never taken.
         */
        fun apkAsset(assets: kotlinx.serialization.json.JsonElement?, version: String): String? {
            val list = assets as? JsonArray ?: return null
            val apks = list.mapNotNull { it as? JsonObject }.filter { asset ->
                val name = asset.string("name").orEmpty()
                val link = asset.string("browser_download_url").orEmpty()
                name.lowercase().endsWith(".apk") || link.substringBefore('?').substringBefore('#').lowercase().endsWith(".apk")
            }
            val only = apks.singleOrNull() ?: return null
            return apkLink(only.string("browser_download_url"), version)
        }

        /**
         * The address of the APK of [version] when it has exactly the form
         * https://github.com/13w13/relief-reader/releases/download/vX.Y.Z/rel-X.Y.Z.apk, with the
         * same X.Y.Z as the version. As strict as [releasePage]: https, the authority exactly
         * github.com, no query, no fragment, no encoded character. Checked when read from the
         * answer, again when read from the settings, and again when tapped. Otherwise null.
         */
        fun apkLink(url: String?, version: String): String? {
            val link = url?.trim() ?: return null
            // A readable version is digits and dots only, so it goes in the path as it is.
            AppVersion.parse(version) ?: return null
            if (version.startsWith("v") || version.startsWith("V")) return null
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.rawAuthority != "github.com") return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.rawPath != "/13w13/relief-reader/releases/download/v$version/rel-$version.apk") return null
            return link
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
         * the installed version, it was not dismissed with "Not now" less than [NOT_NOW_FOR] ago
         * (a newer version shows at once), and its page is safe. Its APK only when the stored
         * address is still valid for this version; a value stored by 0.6.x has none, so no
         * "Download" until the next check.
         */
        fun notice(enabled: Boolean, s: Settings, installed: String, now: Long): LatestRelease? {
            if (!enabled || !s.updateCheck) return null
            val version = s.updateVersion ?: return null
            if (version == s.updateDismissed && now - s.updateDismissedAt in 0L until NOT_NOW_FOR) return null
            if (!AppVersion.isNewer(version, installed)) return null
            val page = s.updateUrl?.let { releasePage(it) } ?: return null
            return LatestRelease(version, page, apkLink(s.updateApk, version))
        }
    }
}

/**
 * GitHub refused the request for its quota (403 or 429; 60 requests an hour per address, shared by
 * everyone behind the same office Wi-Fi). [until] is the time it gave, or null.
 */
internal class RateLimitedException(code: Int, val until: Long?) : HttpStatusException(code)

internal object RateLimit {
    /** Longer than any wait GitHub gives; a larger number is not read (no overflow). */
    private const val MAX_SECONDS = 400L * 24 * 60 * 60

    /**
     * The time GitHub asks to wait for, from its headers: retry-after (seconds, or an HTTP date)
     * and x-ratelimit-reset (epoch seconds), the later of the two readable ones, when it is later
     * than [now]. Null without one. Numbers are bounded before being turned into milliseconds.
     */
    fun until(retryAfter: String?, rateLimitReset: String?, now: Long): Long? {
        val after = retryAfter?.trim()?.let { text ->
            text.toLongOrNull()?.takeIf { it in 0..MAX_SECONDS }?.let { now + it * 1000 }
                ?: runCatching { java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }
        val reset = rateLimitReset?.trim()?.toLongOrNull()?.let { seconds ->
            runCatching { Math.multiplyExact(seconds, 1000L) }.getOrNull()?.takeIf { seconds > 0 }
        }
        return listOfNotNull(after, reset).filter { it > now }.maxOrNull()
    }
}
