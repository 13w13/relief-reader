// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.IOException

/**
 * Bodies read with a ceiling (security audit of 15/11): the announced length is checked first,
 * then the bytes are counted as they come, so a server that announces nothing, or less, is
 * stopped at the limit all the same.
 */
object Bodies {
    class TooLarge(val limit: Long) : IOException("Body over $limit bytes")

    /** The body as UTF-8 text, or [TooLarge] beyond [maxBytes]. */
    suspend fun text(response: HttpResponse, maxBytes: Long): String {
        val announced = response.contentLength()
        if (announced != null && announced > maxBytes) throw TooLarge(maxBytes)
        val bytes = response.bodyAsChannel().readRemaining(maxBytes + 1).readByteArray()
        if (bytes.size > maxBytes) throw TooLarge(maxBytes)
        return bytes.decodeToString()
    }
}
