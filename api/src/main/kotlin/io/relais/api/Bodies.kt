// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Bodies read with a ceiling (security audit of 15/11): the announced length is checked first,
 * then the bytes are counted as they come, so a server that announces nothing, or less, is
 * stopped at the limit all the same. Use it inside a streaming call (prepareGet/preparePost and
 * execute { }), or the client holds the whole body in memory before it is counted.
 */
object Bodies {
    class TooLarge(val limit: Long) : IOException("Body over $limit bytes")

    /**
     * The body as UTF-8 text, or [TooLarge] beyond [maxBytes]. Read in a loop of its own, not with
     * readRemaining(max), which waits for more content after its last read; on [TooLarge] the
     * channel is cancelled first, so the call ends at once instead of waiting on a body nobody reads
     * (review of PR 26: an endless body sometimes hung the call after the limit).
     */
    suspend fun text(response: HttpResponse, maxBytes: Long): String {
        val channel = response.bodyAsChannel()
        val announced = response.contentLength()
        if (announced != null && announced > maxBytes) {
            channel.cancel(TooLarge(maxBytes))
            throw TooLarge(maxBytes)
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (out.size() + n > maxBytes) {
                channel.cancel(TooLarge(maxBytes))
                throw TooLarge(maxBytes)
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray().decodeToString()
    }
}
