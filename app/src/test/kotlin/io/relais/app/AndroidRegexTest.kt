// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests run Java's regex engine, the phone runs ICU's. ICU refuses Java-only flags such as
 * (?U) (UNICODE_CHARACTER_CLASS): a Regex with one passed every test, then crashed the app when
 * its object was first used (16bfe5a). Use explicit classes instead: \p{Nd}, \p{L}.
 */
class AndroidRegexTest {
    @Test
    fun noJavaOnlyRegexFlags() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (JAVA_ONLY.containsMatchIn(line) && !line.trimStart().startsWith("//")) "${file.name}:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        val JAVA_ONLY = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*\)|UNICODE_CHARACTER_CLASS""")
    }
}
