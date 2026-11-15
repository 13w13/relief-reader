// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * What the app leaves behind or reads without end (security audit of 15/11): exports in the
 * cache, the device-transfer rules, cleartext traffic, and a capped read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LeftoversTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exportsGoAtLaunchAndOldOnesOnReturn() {
        val dir = Exports.dir(context).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val old = File(dir, "rel-library-2026-11-01.json").apply { writeText("{}"); setLastModified(now - Exports.KEEP_MS - 60_000) }
        val fresh = File(dir, "rel-highlights.md").apply { writeText("# Highlights") }
        assertEquals(1, Exports.clearOld(context, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        Exports.clear(context)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun nothingGoesToABackupOrANewPhoneAndNoCleartext() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            listOf("root", "file", "database", "sharedpref", "external").forEach { domain ->
                assertTrue("$section $domain", body.contains("""<exclude domain="$domain" path="." />"""))
            }
            assertFalse(body.contains("<include"))
        }
        val old = File("src/main/res/xml/backup_rules.xml").readText()
        listOf("root", "file", "database", "sharedpref", "external").forEach { assertTrue(it, old.contains("""<exclude domain="$it" path="." />""")) }
    }

    @Test
    fun aCappedReadStopsAtItsLimit() {
        val under = CappedInputStream(ByteArrayInputStream(ByteArray(100)), 100).readBytes()
        assertEquals(100, under.size)
        try {
            CappedInputStream(ByteArrayInputStream(ByteArray(101)), 100).readBytes()
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
