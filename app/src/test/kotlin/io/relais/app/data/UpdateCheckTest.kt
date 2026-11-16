// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "A new version is available" (founder, 15/11). Made-up answers; no request leaves the test. */
class UpdateCheckTest {
    // --- Versions ---------------------------------------------------------------------------------

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppVersion.isNewer("v0.5.2", "0.5.1"))
        assertTrue(AppVersion.isNewer("1.0", "0.99.99"))
        assertTrue(AppVersion.isNewer("0.5.1.1", "0.5.1"))
        assertTrue(AppVersion.isNewer(" V0.6.0 ", "0.5.9"))
        assertFalse(AppVersion.isNewer("0.9.0", "0.10.0"))
        assertFalse(AppVersion.isNewer("v0.5.1", "0.5.1"))
        // A missing part counts as 0.
        assertFalse(AppVersion.isNewer("0.5", "0.5.0"))
        assertFalse(AppVersion.isNewer("0.5.0", "0.5"))
        assertFalse(AppVersion.isNewer("0.4.9", "0.5.1"))
    }

    @Test
    fun anUnreadableVersionNeverShowsANotice() {
        listOf(null, "", "v", "latest", "v0.5.2-alpha", "0.6.0-rc1", "0..1", ".1", "1.", "0.5.x", "1.2.3.4.5", "1234567.0", "-1.0", "0,6")
            .forEach { tag ->
                assertNull(tag, AppVersion.parse(tag))
                assertFalse(tag, AppVersion.isNewer(tag, "0.5.1"))
            }
        // An unreadable installed version, as well.
        assertFalse(AppVersion.isNewer("9.0.0", "0.5.1-debug"))
    }

    // --- The answer of /releases/latest -----------------------------------------------------------

    private fun answer(
        tag: String = "\"v0.5.2\"",
        url: String = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.2\"",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = "[]",
    ) = """{"id": 1, "tag_name": $tag, "html_url": $url, "draft": $draft, "prerelease": $prerelease, "name": "Re.L 0.5.2", "assets": $assets}"""

    /** An asset as GitHub lists it: its name, its download address, and a few other fields. */
    private fun asset(name: String, link: String) =
        """{"url": "https://api.github.com/repos/13w13/relief-reader/releases/assets/1", "name": "$name", "content_type": "application/octet-stream", "state": "uploaded", "size": 6526328, "browser_download_url": "$link"}"""

    private val apk052 = "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk"

    /** The three files of a release (release.yml): the APK, its checksum and R8's mapping. */
    private val releaseAssets = "[" + listOf(
        asset("rel-0.5.2.apk", apk052),
        asset("rel-0.5.2.apk.sha256", "$apk052.sha256"),
        asset("rel-0.5.2-mapping.txt", "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2-mapping.txt"),
    ).joinToString() + "]"

    @Test
    fun aPublishedReleaseIsRead() {
        assertEquals(
            LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2"),
            UpdateCheck.parse(answer()),
        )
    }

    // --- The APK among the assets (founder, 16/11) -------------------------------------------------

    @Test
    fun theOnlyValidApkIsTakenNeverTheMappingOrTheChecksum() {
        assertEquals(apk052, UpdateCheck.parse(answer(assets = releaseAssets))?.apk)
        // The checksum and the mapping alone: no APK.
        val withoutApk = "[" + asset("rel-0.5.2.apk.sha256", "$apk052.sha256") + ", " +
            asset("rel-0.5.2-mapping.txt", "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2-mapping.txt") + "]"
        val release = UpdateCheck.parse(answer(assets = withoutApk))
        assertEquals("0.5.2", release?.version)
        assertNull(release?.apk)
    }

    @Test
    fun noApkTwoApksOrOddAssetsGiveNoDownload() {
        listOf(
            "[]",
            "null",
            "{}",
            "\"rel-0.5.2.apk\"",
            "[\"$apk052\"]",
            // Two APKs, even if one is valid.
            "[" + asset("rel-0.5.2.apk", apk052) + ", " + asset("rel-0.5.2-debug.apk", "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2-debug.apk") + "]",
            // An APK named otherwise, or at an address that ends otherwise: still counted as an APK.
            "[" + asset("rel-0.5.2.apk", apk052) + ", " + asset("other.bin", "https://github.com/13w13/relief-reader/releases/download/v0.5.2/other.apk") + "]",
            // A name without an address.
            """[{"name": "rel-0.5.2.apk"}]""",
        ).forEach { assets ->
            val release = UpdateCheck.parse(answer(assets = assets))
            assertEquals(assets, "0.5.2", release?.version)
            assertNull(assets, release?.apk)
        }
    }

    @Test
    fun onlyTheApkOfThisRepositoryAndThisVersionOpens() {
        listOf(
            // Another repository, another owner, another host.
            "https://github.com/someone/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            "https://github.com/13w13/other/releases/download/v0.5.2/rel-0.5.2.apk",
            "https://objects.githubusercontent.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            "https://github.com.example.org/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            // Another version than the tag, in the folder or in the name.
            "https://github.com/13w13/relief-reader/releases/download/v0.5.1/rel-0.5.1.apk",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.1.apk",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.1/rel-0.5.2.apk",
            "https://github.com/13w13/relief-reader/releases/download/0.5.2/rel-0.5.2.apk",
            // http, a port, user info.
            "http://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            "https://github.com:443/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            "https://x@github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            // Encoded characters, a query, a fragment, a path that leaves the folder.
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel%2D0.5.2.apk",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/%2e%2e/rel-0.5.2.apk",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk?x=1",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk#x",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/../v0.5.2/rel-0.5.2.apk",
            "https://GITHUB.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk",
            "javascript:alert(1)",
            "not a url",
        ).forEach { link ->
            assertNull(link, UpdateCheck.apkLink(link, "0.5.2"))
            assertNull(link, UpdateCheck.parse(answer(assets = "[" + asset("rel-0.5.2.apk", link) + "]"))?.apk)
        }
        assertEquals(apk052, UpdateCheck.apkLink(" $apk052 ", "0.5.2")?.trim())
        // An unreadable version never gives an address.
        listOf("v0.5.2", "0.5.2-beta", "", "0.5.2/../x").forEach { assertNull(it, UpdateCheck.apkLink(apk052, it)) }
        assertNull(UpdateCheck.apkLink(null, "0.5.2"))
    }

    @Test
    fun draftsPreReleasesAndOddAnswersAreIgnored() {
        assertNull(UpdateCheck.parse(answer(draft = true)))
        assertNull(UpdateCheck.parse(answer(prerelease = true)))
        assertNull(UpdateCheck.parse(answer(tag = "\"v0.6.0-beta\"")))
        assertNull(UpdateCheck.parse(answer(tag = "0.6")))
        assertNull(UpdateCheck.parse(answer(tag = "null")))
        assertNull(UpdateCheck.parse(answer(url = "null")))
        assertNull(UpdateCheck.parse("""{"message": "Not Found", "documentation_url": "https://docs.github.com"}"""))
        assertNull(UpdateCheck.parse("""[{"tag_name": "v0.5.2"}]"""))
        assertNull(UpdateCheck.parse("<html>rate limited</html>"))
        assertNull(UpdateCheck.parse(""))
    }

    @Test
    fun onlyPagesOfTheRepositoryOnGithubOpen() {
        listOf(
            "http://github.com/13w13/relief-reader/releases/tag/v0.5.2",
            "https://github.com.example.org/13w13/relief-reader/releases",
            "https://example.org/13w13/relief-reader/releases",
            "https://github.com/13w13/relief-reader-fork/releases",
            "https://github.com/13w13/relief-reader/../other/releases",
            "https://github.com/../13w13/relief-reader/releases",
            "https://someone@github.com/13w13/relief-reader/releases",
            "https://github.com:8443/13w13/relief-reader/releases",
            // Encoded segments: a browser decodes them and leaves the repository (review of PR 23).
            "https://github.com/13w13/relief-reader/%2e%2e/other",
            "https://github.com/13w13/relief-reader/%2E%2E/%2E%2E/other/releases",
            "https://github.com/13w13/relief-reader/releases/tag/%2e%2e",
            "https://github.com/13w13/relief-reader/releases/tag/v0.5.2%2F..%2F..",
            "https://github.com/13w13/relief-reader/releases/tag/../../../other",
            "https://github.com/13w13/relief-reader/releases/tag/.hidden",
            "https://github.com/13w13/relief-reader/releases/tag/",
            "https://github.com/13w13/relief-reader/releases/tag/v0.5.2/extra",
            "https://github.com/13w13/relief-reader/releases?after=v0.5.2",
            "https://github.com/13w13/relief-reader/releases/tag/v0.5.2#assets",
            "https://github.com/13w13/relief-reader/issues",
            "https://github.com/13w13/relief-reader/",
            "https://GitHub.com/13w13/relief-reader/releases",
            "javascript:alert(1)",
            "intent://github.com/13w13/relief-reader/#Intent;end",
            "not a url",
        ).forEach { url ->
            assertNull(url, UpdateCheck.releasePage(url))
            assertNull(url, UpdateCheck.parse(answer(url = "\"$url\"")))
        }
        assertEquals(
            "https://github.com/13w13/relief-reader/releases/tag/v0.5.2",
            UpdateCheck.releasePage(" https://github.com/13w13/relief-reader/releases/tag/v0.5.2 "),
        )
        assertEquals("https://github.com/13w13/relief-reader/releases", UpdateCheck.releasePage("https://github.com/13w13/relief-reader/releases"))
        assertEquals(
            "https://github.com/13w13/relief-reader/releases/tag/v1.0.0_final-2",
            UpdateCheck.releasePage("https://github.com/13w13/relief-reader/releases/tag/v1.0.0_final-2"),
        )
    }

    // --- When it asks -----------------------------------------------------------------------------

    /** The settings in memory, the clock, the network and GitHub's answer, with the requests counted. */
    private class Phone(
        var settings: Settings = Settings(),
        var now: Long = 1_794_700_000_000L,
        var online: Boolean = true,
        var enabled: Boolean = true,
        var reply: () -> String? = { null },
    ) {
        var requests = 0

        /** One instance, as in the app: "Check now" keeps its last result in memory. */
        val check by lazy {
            UpdateCheck(
                enabled = enabled,
                installed = "0.5.1",
                settings = { settings },
                // As Settings.recordUpdateCheck: the APK goes with the release found.
                record = { at, found ->
                    settings = settings.copy(
                        updateCheckedAt = at,
                        updateVersion = found?.version ?: settings.updateVersion,
                        updateUrl = found?.url ?: settings.updateUrl,
                        updateApk = if (found != null) found.apk else settings.updateApk,
                    )
                },
                online = { online },
                fetch = { requests++; reply() },
                now = { now },
                hold = { until -> settings = settings.copy(updateHeldUntil = until) },
            )
        }

        fun open() = runBlocking { check.checkIfDue() }

        fun tap() = runBlocking { check.checkNow() }

        val notice get() = check.notice(settings)
    }

    private val release = answer()

    @Test
    fun aNewerReleaseShowsItsNotice() {
        val phone = Phone(reply = { release })
        assertTrue(phone.open())
        assertEquals(1, phone.requests)
        assertEquals(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2"), phone.notice)
    }

    @Test
    fun theSameOrAnOlderReleaseShowsNothing() {
        val same = Phone(reply = { answer(tag = "\"v0.5.1\"", url = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.1\"") })
        same.open()
        assertNull(same.notice)
        val older = Phone(reply = { answer(tag = "\"v0.5.0\"") })
        older.open()
        assertNull(older.notice)
    }

    @Test
    fun atMostOneRequestAnHour() {
        assertEquals(60 * 60 * 1000L, UpdateCheck.INTERVAL)
        val phone = Phone(reply = { release })
        phone.open()
        // 59 min 59 s later: no request; an hour: one.
        phone.now += UpdateCheck.INTERVAL - 1_000
        assertFalse(phone.open())
        assertEquals(1, phone.requests)
        phone.now += 1_000
        assertTrue(phone.open())
        assertEquals(2, phone.requests)
        // Back to the foreground many times in the hour (another app, the share menu): still two.
        repeat(10) { phone.now += 60_000; phone.open() }
        assertEquals(2, phone.requests)
    }

    @Test
    fun aClockSetBackDoesNotBlockTheCheck() {
        val phone = Phone(reply = { release })
        phone.settings = phone.settings.copy(updateCheckedAt = phone.now + 3 * UpdateCheck.INTERVAL)
        assertTrue(phone.open())
        assertEquals(1, phone.requests)
    }

    @Test
    fun offlineNoRequestAndNoDayLost() {
        val phone = Phone(online = false, reply = { release })
        assertFalse(phone.open())
        assertEquals(0, phone.requests)
        assertEquals(0L, phone.settings.updateCheckedAt)
        // Back online, an hour later: the check runs.
        phone.online = true
        phone.now += 60 * 60 * 1000L
        assertTrue(phone.open())
        assertEquals(1, phone.requests)
    }

    @Test
    fun notNowHidesThisVersionForThreeDays() {
        assertEquals(3 * UpdateCheck.DAY, UpdateCheck.NOT_NOW_FOR)
        val phone = Phone(reply = { release })
        phone.open()
        assertEquals("0.5.2", phone.notice?.version)
        phone.settings = phone.settings.copy(updateDismissed = "0.5.2", updateDismissedAt = phone.now)
        assertNull(phone.notice)
        // The next day, the same release: still hidden.
        phone.now += UpdateCheck.DAY
        phone.open()
        assertNull(phone.notice)
        // Just under 3 days: hidden; 3 days: back.
        phone.now += 2 * UpdateCheck.DAY - 1
        assertNull(phone.notice)
        phone.now += 1
        assertEquals("0.5.2", phone.notice?.version)
    }

    @Test
    fun aNewerVersionShowsDespiteARecentNotNow() {
        val phone = Phone(reply = { answer(tag = "\"v0.5.3\"", url = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.3\"") })
        phone.settings = Settings(updateDismissed = "0.5.2", updateDismissedAt = phone.now - 60_000)
        phone.open()
        assertEquals("0.5.3", phone.notice?.version)
    }

    @Test
    fun aNotNowDatedInTheFutureHidesNothing() {
        // A clock set back after the tap: the notice is not hidden for longer than 3 days.
        val phone = Phone(reply = { release })
        phone.open()
        phone.settings = phone.settings.copy(updateDismissed = "0.5.2", updateDismissedAt = phone.now + UpdateCheck.DAY)
        assertEquals("0.5.2", phone.notice?.version)
    }

    @Test
    fun theSwitchOffMeansNoRequestAndNoNotice() {
        val phone = Phone(reply = { release })
        phone.open()
        phone.settings = phone.settings.copy(updateCheck = false)
        repeat(3) { phone.now += UpdateCheck.INTERVAL; assertFalse(phone.open()) }
        assertEquals(1, phone.requests)
        assertNull(phone.notice)
        // Back on: the notice found before shows, and the check runs again.
        phone.settings = phone.settings.copy(updateCheck = true)
        assertEquals("0.5.2", phone.notice?.version)
        assertTrue(phone.open())
        assertEquals(2, phone.requests)
    }

    @Test
    fun theBuildFlagOffMeansNoRequestNoNotice() {
        val phone = Phone(enabled = false, reply = { release })
        // Even with a newer release noted before (an APK from GitHub replaced by a build without the check).
        phone.settings = Settings(updateVersion = "0.6.0", updateUrl = "https://github.com/13w13/relief-reader/releases/tag/v0.6.0")
        repeat(3) { phone.now += UpdateCheck.INTERVAL; assertFalse(phone.open()) }
        assertEquals(0, phone.requests)
        assertNull(phone.notice)
    }

    @Test
    fun failuresAreSilentAndWaitForTheNextInterval() {
        val failures = listOf<() -> String?>(
            { throw java.io.IOException("offline") },
            { null }, // a 403 of the hourly quota, a 404, a 500: the fetch gives no body
            { """{"message": "API rate limit exceeded"}""" },
            { "<html></html>" },
            { throw IllegalStateException("odd") },
        )
        for (failure in failures) {
            val phone = Phone(reply = failure)
            // A release found the day before stays.
            phone.settings = Settings(updateCheckedAt = phone.now - UpdateCheck.INTERVAL, updateVersion = "0.5.2", updateUrl = "https://github.com/13w13/relief-reader/releases/tag/v0.5.2")
            assertTrue(phone.open())
            assertEquals(phone.now, phone.settings.updateCheckedAt)
            assertEquals("0.5.2", phone.notice?.version)
            // No new try within the interval.
            phone.now += UpdateCheck.INTERVAL - 1
            assertFalse(phone.open())
            assertEquals(1, phone.requests)
        }
        // Without anything found before: no notice, no error.
        val first = Phone(reply = { throw java.io.IOException() })
        assertTrue(first.open())
        assertNull(first.notice)
    }

    @Test
    fun aCancelledCheckIsNotNoted() {
        val phone = Phone(reply = { throw CancellationException("app closed") })
        try {
            phone.open()
            fail("the cancellation should be thrown")
        } catch (e: CancellationException) {
            assertEquals("app closed", e.message)
        }
        assertEquals(0L, phone.settings.updateCheckedAt)
    }

    @Test
    fun anUnsafeStoredPageShowsNothing() {
        val phone = Phone()
        phone.settings = Settings(updateVersion = "0.6.0", updateUrl = "https://example.org/13w13/relief-reader/")
        assertNull(phone.notice)
        phone.settings = Settings(updateVersion = "0.6.0", updateUrl = "https://github.com/13w13/relief-reader/%2e%2e/other")
        assertNull(phone.notice)
    }

    // --- Check now (founder, 16/11) ---------------------------------------------------------------

    @Test
    fun checkNowAsksAtOnceEvenWithTheSwitchOff() {
        val phone = Phone(reply = { answer(assets = releaseAssets) })
        phone.settings = phone.settings.copy(updateCheck = false, updateCheckedAt = phone.now - 60_000)
        // The daily check would not ask (switch off, checked a minute ago); a tap does.
        assertFalse(phone.open())
        assertEquals(0, phone.requests)
        val result = phone.tap()
        assertEquals(1, phone.requests)
        assertEquals(ManualCheck.Available(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2", apk052)), result)
        assertEquals(result, phone.check.manual.value)
        // The time and the release are noted; Today's notice waits for the switch.
        assertEquals(phone.now, phone.settings.updateCheckedAt)
        assertEquals(apk052, phone.settings.updateApk)
        assertNull(phone.notice)
        phone.settings = phone.settings.copy(updateCheck = true)
        assertEquals(apk052, phone.notice?.apk)
    }

    @Test
    fun theBuildFlagOffMeansNoCheckNow() {
        val phone = Phone(enabled = false, reply = { release })
        assertEquals(ManualCheck.Idle, phone.tap())
        assertEquals(0, phone.requests)
    }

    @Test
    fun atMostOneRequestEvery30SecondsHoweverOftenItIsTapped() {
        val phone = Phone(reply = { release })
        val first = phone.tap()
        repeat(29) { phone.now += 1_000; assertEquals(first, phone.tap()) }
        phone.now += 999
        phone.tap()
        assertEquals(1, phone.requests)
        phone.now += 1
        phone.tap()
        assertEquals(2, phone.requests)
        // The daily check counts too: a tap just after it sends nothing more.
        val daily = Phone(reply = { release })
        assertTrue(daily.open())
        daily.now += 5_000
        assertEquals(ManualCheck.Available(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2")), daily.tap())
        assertEquals(1, daily.requests)
    }

    @Test
    fun aSecondTapDuringACheckSendsNoSecondRequest() = runBlocking {
        val gate = CompletableDeferred<String?>()
        var requests = 0
        val check = UpdateCheck(
            enabled = true, installed = "0.5.1", settings = { Settings() }, record = { _, _ -> },
            online = { true }, fetch = { requests++; gate.await() }, now = { 1_794_700_000_000L },
        )
        val first = async { check.checkNow() }
        yield()
        assertEquals(ManualCheck.Checking, check.manual.value)
        val second = async { check.checkNow() }
        yield()
        gate.complete(answer())
        assertEquals(first.await(), second.await())
        assertEquals(1, requests)
        assertTrue(check.manual.value is ManualCheck.Available)
    }

    @Test
    fun eachResultSaysWhatHappened() {
        assertEquals(ManualCheck.UpToDate("0.5.1"), Phone(reply = { answer(tag = "\"v0.5.1\"", url = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.1\"") }).tap())
        assertEquals(ManualCheck.UpToDate("0.5.1"), Phone(reply = { answer(tag = "\"v0.5.0\"") }).tap())
        assertEquals("0.5.2", (Phone(reply = { release }).tap() as ManualCheck.Available).release.version)
        listOf<Pair<() -> String?, String>>(
            { throw HttpStatusException(403) } to "HTTP 403",
            { throw HttpStatusException(500) } to "HTTP 500",
            { throw java.io.IOException("reset") } to "no connection",
            { throw java.net.UnknownHostException("api.github.com") } to "address not found",
            { throw java.net.SocketTimeoutException() } to "timed out",
            { throw javax.net.ssl.SSLHandshakeException("bad") } to "secure connection failed",
            { """{"message": "API rate limit exceeded"}""" } to "unreadable answer",
            { "<html></html>" } to "unreadable answer",
            { null } to "unreadable answer",
            { throw IllegalStateException("odd") } to "unexpected error",
        ).forEach { (reply, reason) ->
            val phone = Phone(reply = reply)
            assertEquals(reason, ManualCheck.Failed(reason), phone.tap())
            // A failure notes the time, as the daily check does.
            assertEquals(reason, phone.now, phone.settings.updateCheckedAt)
        }
        // Offline: said at once, without a request, and the next tap may ask.
        val offline = Phone(online = false, reply = { release })
        assertEquals(ManualCheck.Failed("no connection"), offline.tap())
        assertEquals(0, offline.requests)
        offline.online = true
        assertTrue(offline.tap() is ManualCheck.Available)
        assertEquals(1, offline.requests)
    }

    @Test
    fun aCancelledCheckNowGoesBackToIdle() {
        val phone = Phone(reply = { throw CancellationException("app closed") })
        try {
            phone.tap()
            fail("the cancellation should be thrown")
        } catch (e: CancellationException) {
            assertEquals(ManualCheck.Idle, phone.check.manual.value)
        }
    }

    // --- The stored APK ---------------------------------------------------------------------------

    @Test
    fun aValueStoredBy06WithoutApkShowsTheNoticeWithoutDownload() {
        val phone = Phone()
        phone.settings = Settings(updateVersion = "0.5.2", updateUrl = "https://github.com/13w13/relief-reader/releases/tag/v0.5.2")
        assertEquals(LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2", apk = null), phone.notice)
    }

    @Test
    fun theStoredApkIsCheckedAgainOnReading() {
        val phone = Phone()
        val page = "https://github.com/13w13/relief-reader/releases/tag/v0.5.2"
        phone.settings = Settings(updateVersion = "0.5.2", updateUrl = page, updateApk = apk052)
        assertEquals(apk052, phone.notice?.apk)
        listOf(
            "https://github.com/13w13/relief-reader/releases/download/v0.5.1/rel-0.5.1.apk",
            "https://example.org/rel-0.5.2.apk",
            "https://github.com/13w13/relief-reader/releases/download/v0.5.2/rel-0.5.2.apk?x",
        ).forEach { stored ->
            phone.settings = Settings(updateVersion = "0.5.2", updateUrl = page, updateApk = stored)
            assertEquals(stored, "0.5.2", phone.notice?.version)
            assertNull(stored, phone.notice?.apk)
        }
    }

    @Test
    fun aNewReleaseWithoutApkForgetsThePreviousOne() {
        val phone = Phone(reply = { answer(assets = releaseAssets) })
        phone.tap()
        assertEquals(apk052, phone.settings.updateApk)
        phone.reply = { answer(tag = "\"v0.5.3\"", url = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.3\"") }
        phone.now += UpdateCheck.MIN_GAP
        phone.tap()
        assertEquals("0.5.3", phone.settings.updateVersion)
        assertNull(phone.settings.updateApk)
        assertNull(phone.notice?.apk)
    }

    // --- GitHub's quota (16/11) -------------------------------------------------------------------

    @Test
    fun aQuotaRefusalWaitsForTheTimeGitHubGives() {
        val phone = Phone(reply = { throw RateLimitedException(403, phone0 + 3 * UpdateCheck.HOUR) })
        phone.open()
        assertEquals(1, phone.requests)
        assertEquals(phone0 + 3 * UpdateCheck.HOUR, phone.settings.updateHeldUntil)
        // After the interval, still before GitHub's time: no request, and no loop.
        phone.now += UpdateCheck.INTERVAL
        assertFalse(phone.open())
        phone.now += UpdateCheck.HOUR
        assertFalse(phone.open())
        assertEquals(1, phone.requests)
        // GitHub's time reached: one request.
        phone.reply = { release }
        phone.now = phone0 + 3 * UpdateCheck.HOUR
        assertTrue(phone.open())
        assertEquals(2, phone.requests)
    }

    @Test
    fun aQuotaRefusalWithoutAHeaderWaitsAnHour() {
        listOf(403, 429).forEach { code ->
            val phone = Phone(reply = { throw RateLimitedException(code, null) })
            assertTrue(phone.open())
            assertEquals(phone0 + UpdateCheck.INTERVAL, phone.settings.updateHeldUntil)
            phone.now += UpdateCheck.INTERVAL - 1
            assertFalse(phone.open())
            phone.now += 1
            assertTrue(phone.open())
            assertEquals("$code", 2, phone.requests)
        }
    }

    @Test
    fun aQuotaTimeMoreThanADayAwayIsIgnored() {
        // A clock set back, or an odd header: never blocked for more than a day.
        val phone = Phone(reply = { release })
        phone.settings = Settings(updateHeldUntil = phone.now + 2 * UpdateCheck.DAY)
        assertTrue(phone.open())
    }

    @Test
    fun theQuotaHeadersAreRead() {
        val now = 1_794_700_000_000L
        assertEquals(now + 120_000, RateLimit.until("120", null, now))
        assertEquals(1_794_703_600_000L, RateLimit.until(null, "1794703600", now))
        // retry-after first.
        assertEquals(now + 60_000, RateLimit.until("60", "1794703600", now))
        assertEquals(
            java.time.ZonedDateTime.parse("Mon, 16 Nov 2026 22:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),
            RateLimit.until("Mon, 16 Nov 2026 22:00:00 GMT", null, 1_794_000_000_000L),
        )
        // Nothing readable, or a time already past: null, so an hour.
        listOf<Pair<String?, String?>>(null to null, "soon" to null, "-5" to null, null to "x", null to "0", null to "1700000000").forEach { (after, reset) ->
            assertNull("$after $reset", RateLimit.until(after, reset, now))
        }
    }

    @Test
    fun checkNowAfterAQuotaRefusalSaysItAndHoldsTheAutomaticCheck() {
        val phone = Phone(reply = { throw RateLimitedException(429, null) })
        assertEquals(ManualCheck.Failed("HTTP 429"), phone.tap())
        assertEquals(phone0 + UpdateCheck.INTERVAL, phone.settings.updateHeldUntil)
    }

    private val phone0 = 1_794_700_000_000L
}
