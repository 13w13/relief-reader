// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

/**
 * The site's "Most read" file (produit/tour-a2.md 5.1): one line per page, `key,"url1,url2"`,
 * no header. Keys: `front` (home page), country ids, disaster ids. It measures what visitors
 * of reliefweb.int read, never how serious a crisis is.
 */
object MostRead {
    const val URL = "https://reliefweb.int/sites/default/files/most-read/most-read.csv"
    const val FRONT = "front"
    private const val SITE = "https://reliefweb.int"
    private val NODE = Regex("/node/(\\d+)/?$")

    /** The key and the addresses of one line, or null when it does not have two columns. */
    fun parseLine(line: String): Pair<String, List<String>>? {
        val comma = line.indexOf(',')
        if (comma <= 0) return null
        val key = line.substring(0, comma).trim().removeSurrounding("\"")
        val urls = line.substring(comma + 1).trim().removeSurrounding("\"")
            .split(',')
            .map { it.trim().replace("\"", "") }
            .filter { it.startsWith("/") || it.startsWith("http") }
            .map(::absolute)
        return if (key.isEmpty() || urls.isEmpty()) null else key to urls
    }

    fun absolute(url: String): String = if (url.startsWith("/")) SITE + url else url

    /** The report id of a `/node/{id}` address. */
    fun nodeId(url: String): Long? = NODE.find(url)?.groupValues?.get(1)?.toLongOrNull()

    /** Search term for one address: `id:123` for a node, `url_alias:"…"` otherwise. */
    fun term(url: String): String = nodeId(url)?.let { "id:$it" } ?: "url_alias:\"$url\""
}
