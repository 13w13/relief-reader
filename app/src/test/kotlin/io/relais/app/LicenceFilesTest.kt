// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The licence texts shown in Settings > About (assets/licenses/) are the files of the repository
 * (T19): a change to one without the other fails here. Unit tests run in the app/ directory.
 */
class LicenceFilesTest {
    private fun same(repo: String, asset: String) =
        assertEquals(repo, File(repo).readText(), File("src/main/assets/licenses/$asset").readText())

    @Test
    fun assetsMatchTheRepository() {
        same("../LICENSE", "LICENSE.txt")
        same("../THIRD_PARTY_NOTICES", "THIRD_PARTY_NOTICES.txt")
        listOf("Literata-OFL.txt", "Inter-OFL.txt", "IBMPlexSansCondensed-OFL.txt", "LiberationSans-OFL.txt").forEach { same("../licenses/fonts/$it", it) }
        listOf("Apache-2.0.txt", "jsoup-MIT.txt").forEach { same("../licenses/libraries/$it", it) }
    }

    @Test
    fun pdfBoxNoticeIsReproduced() {
        // Apache-2.0 4(d): the NOTICE of PdfBox-Android goes with the APK (final audit, 13/11).
        val notices = File("../THIRD_PARTY_NOTICES").readText()
        listOf("Apache PDFBox", "Atos Worldline SAS", "Adobe Glyph List", "Adobe Font Metrics (AFM)", "Liberation Sans").forEach {
            assertTrue("THIRD_PARTY_NOTICES lacks: $it", it in notices)
        }
    }

    @Test
    fun everySourceFileSaysItsLicence() {
        // The app and api modules and the root build files; other folders have their own rules.
        val roots = listOf(File("../app"), File("../api")) + File("..").listFiles().orEmpty().filter { it.name.endsWith(".kts") }
        val missing = roots.asSequence().flatMap { it.walkTopDown().onEnter { d -> d.name != "build" && !d.name.startsWith(".") } }
            .filter { it.isFile && (it.extension == "kt" || it.name.endsWith(".kts")) }
            .filter { "SPDX-License-Identifier: GPL-3.0-or-later" !in it.readText() }
            .map { it.path }.toList()
        assertTrue("No SPDX line: $missing", missing.isEmpty())
    }
}
