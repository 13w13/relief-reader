// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    ) = """{"id": 1, "tag_name": $tag, "html_url": $url, "draft": $draft, "prerelease": $prerelease, "name": "Re.L 0.5.2", "assets": []}"""

    @Test
    fun aPublishedReleaseIsRead() {
        assertEquals(
            LatestRelease("0.5.2", "https://github.com/13w13/relief-reader/releases/tag/v0.5.2"),
            UpdateCheck.parse(answer()),
        )
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

        val check get() = UpdateCheck(
            enabled = enabled,
            installed = "0.5.1",
            settings = { settings },
            record = { at, found ->
                settings = settings.copy(
                    updateCheckedAt = at,
                    updateVersion = found?.version ?: settings.updateVersion,
                    updateUrl = found?.url ?: settings.updateUrl,
                )
            },
            online = { online },
            fetch = { requests++; reply() },
            now = { now },
        )

        fun open() = runBlocking { check.checkIfDue() }

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
    fun atMostOneRequestEvery24Hours() {
        val phone = Phone(reply = { release })
        phone.open()
        phone.now += UpdateCheck.DAY - 1
        assertFalse(phone.open())
        assertEquals(1, phone.requests)
        phone.now += 1
        assertTrue(phone.open())
        assertEquals(2, phone.requests)
        // Opened many times the same day: still two.
        repeat(5) { phone.now += 60_000; phone.open() }
        assertEquals(2, phone.requests)
    }

    @Test
    fun aClockSetBackDoesNotBlockTheCheck() {
        val phone = Phone(reply = { release })
        phone.settings = phone.settings.copy(updateCheckedAt = phone.now + 3 * UpdateCheck.DAY)
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
    fun notNowHidesThisVersionOnly() {
        val phone = Phone(reply = { release })
        phone.open()
        assertEquals("0.5.2", phone.notice?.version)
        phone.settings = phone.settings.copy(updateDismissed = "0.5.2")
        assertNull(phone.notice)
        // The next day, the same release: still hidden.
        phone.now += UpdateCheck.DAY
        phone.open()
        assertNull(phone.notice)
        // A later release shows again.
        phone.reply = { answer(tag = "\"v0.5.3\"", url = "\"https://github.com/13w13/relief-reader/releases/tag/v0.5.3\"") }
        phone.now += UpdateCheck.DAY
        phone.open()
        assertEquals("0.5.3", phone.notice?.version)
    }

    @Test
    fun theSwitchOffMeansNoRequestAndNoNotice() {
        val phone = Phone(reply = { release })
        phone.open()
        phone.settings = phone.settings.copy(updateCheck = false)
        repeat(3) { phone.now += UpdateCheck.DAY; assertFalse(phone.open()) }
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
        repeat(3) { phone.now += UpdateCheck.DAY; assertFalse(phone.open()) }
        assertEquals(0, phone.requests)
        assertNull(phone.notice)
    }

    @Test
    fun failuresAreSilentAndWaitForTheNextDay() {
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
            phone.settings = Settings(updateCheckedAt = phone.now - UpdateCheck.DAY, updateVersion = "0.5.2", updateUrl = "https://github.com/13w13/relief-reader/releases/tag/v0.5.2")
            assertTrue(phone.open())
            assertEquals(phone.now, phone.settings.updateCheckedAt)
            assertEquals("0.5.2", phone.notice?.version)
            // No new try the same day.
            phone.now += 60 * 60 * 1000L
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
    }
}
