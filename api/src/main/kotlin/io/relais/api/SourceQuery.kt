// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import java.text.Normalizer

/**
 * The text of "Search organizations" as a ReliefWeb query (founder, 15/11: "solidar", "humanit"
 * and HI found nothing). `query.value` takes Elasticsearch's query_string syntax
 * (UN-OCHA/rwint-api-doc, read/search.md): wildcards (`climat*`), AND, OR, phrases.
 *
 * Each typed word becomes "the word, or a word that starts with it", with and without its
 * accents; the words are joined by AND. query_string's special characters separate words (and
 * are escaped, should one remain), and words are lowercased, so that a typed "and", "or" or
 * "not" stays a word and never becomes an operator.
 */
object SourceQuery {
    /** query_string's reserved characters (a backslash escapes them). */
    private const val RESERVED = "+-=&|><!(){}[]^\"~*?:\\/"

    /** The prefix query, or null when nothing searchable was typed. */
    fun prefix(text: String): String? {
        val words = words(text)
        if (words.isEmpty()) return null
        return words.joinToString(" AND ") { word ->
            val variants = listOf(word, fold(word)).distinct()
            val terms = variants.flatMap { listOf(escape(it), escape(it) + "*") }
            if (terms.size == 1) terms.single() else terms.joinToString(" OR ", "(", ")")
        }
    }

    /** The plain query of the second try: the words, escaped, without wildcards. */
    fun plain(text: String): String? = words(text).takeIf { it.isNotEmpty() }?.joinToString(" ") { escape(it) }

    /** The typed words, lowercased; a reserved character separates words ("Sans-Frontières"). */
    private fun words(text: String): List<String> =
        text.lowercase().map { if (it in RESERVED) ' ' else it }.joinToString("").trim()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** "solidarités" without its accents: "solidarites". */
    internal fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun escape(word: String): String = buildString { word.forEach { c -> if (c in RESERVED) append('\\'); append(c) } }
}
