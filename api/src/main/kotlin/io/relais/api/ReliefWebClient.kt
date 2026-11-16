// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Errors from the ReliefWeb API. Messages never contain the request URL,
 * because the URL carries the appname.
 */
sealed class ApiException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    /** No appname: built-in one missing and none entered in Settings. */
    class NoAppname : ApiException("No ReliefWeb appname configured")
    class BadRequest(message: String) : ApiException(message, 400)
    class AccessDenied(message: String) : ApiException(message, 403)
    class NotFound : ApiException("Not found", 404)
    class RateLimited(val retryAfterSeconds: Long?) : ApiException("Rate limited", 429)
    class Server(status: Int, message: String) : ApiException(message, status) {
        /** The answer came, but not as JSON of the expected shape. */
        val unreadable: Boolean get() = message.orEmpty().startsWith(UNREADABLE)

        companion object {
            const val UNREADABLE = "Unreadable response"
        }
    }
    /** An HTML page instead of JSON: the request was probably blocked upstream. */
    class Blocked : ApiException("ReliefWeb returned a web page instead of data")
    /**
     * Only the class of the cause is kept, never the cause itself: an exception of the HTTP client
     * carries the full address, appname included (security audit of 15/11).
     */
    class Network(cause: Throwable) : ApiException("Network error: ${cause::class.simpleName}")
}

/**
 * Small hand-written client for the ReliefWeb API v2.
 * Lists use POST with a JSON body; a single document uses GET.
 *
 * @param appname returns the appname to use, or null. Called on every request.
 * @param onCall called once per HTTP request sent, to count calls against the daily budget.
 */
class ReliefWebClient(
    private val http: HttpClient,
    private val appname: () -> String?,
    private val baseUrl: String = "https://api.reliefweb.int/v2",
    private val onCall: suspend () -> Unit = {},
) {
    suspend fun reports(query: ApiQuery): ApiResponse<ReportFields> =
        post("reports", query, ReportFields.serializer())

    suspend fun countries(query: ApiQuery): ApiResponse<CountryFields> =
        post("countries", query, CountryFields.serializer())

    suspend fun disasters(query: ApiQuery): ApiResponse<DisasterFields> =
        post("disasters", query, DisasterFields.serializer())

    /** A country with its editors' profile (current appeals and plans). */
    suspend fun country(id: Long): ApiItem<CountryFields> = one("countries", id, CountryFields.serializer())

    /** A disaster with its description and profile. */
    suspend fun disaster(id: Long): ApiItem<DisasterFields> = one("disasters", id, DisasterFields.serializer())

    /** Organizations (v0.3): a search by name, or some by id. */
    suspend fun sources(query: ApiQuery): ApiResponse<SourceFields> =
        post("sources", query, SourceFields.serializer())

    /** An organization with its description and country (v0.3). */
    suspend fun source(id: Long): ApiItem<SourceFields> = one("sources", id, SourceFields.serializer())

    /**
     * Regional crises (topics, lot 3): the whole answer as raw JSON, read item by item by
     * [Topics], so that an item without `fields` or of another shape is skipped, not the list.
     */
    suspend fun topics(query: ApiQuery): kotlinx.serialization.json.JsonElement {
        val name = requireAppname()
        val body = query.toJson().toString()
        val answer = send {
            http.preparePost("$baseUrl/topics") {
                parameter("appname", name)
                header(HttpHeaders.Accept, "application/json")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return raw(answer)
    }

    /** One topic with its introduction and its lists (rivers), as raw JSON. */
    suspend fun topic(id: Long): kotlinx.serialization.json.JsonElement {
        val name = requireAppname()
        val answer = send {
            http.prepareGet("$baseUrl/topics/$id") {
                parameter("appname", name)
                parameter("profile", "full")
                parameter("slim", 1)
                header(HttpHeaders.Accept, "application/json")
            }
        }
        return raw(answer)
    }

    /** One document of [resource] with every field (`profile=full`), or throws [ApiException.NotFound]. */
    private suspend fun <T> one(resource: String, id: Long, serializer: KSerializer<T>): ApiItem<T> {
        val name = requireAppname()
        val answer = send {
            http.prepareGet("$baseUrl/$resource/$id") {
                parameter("appname", name)
                parameter("profile", "full")
                parameter("slim", 1)
                header(HttpHeaders.Accept, "application/json")
            }
        }
        return decode(answer, serializer).data.firstOrNull() ?: throw ApiException.NotFound()
    }

    /** One report with its full text, or throws [ApiException.NotFound]. */
    suspend fun report(id: Long, fields: List<String> = Fields.REPORT_FULL): ApiItem<ReportFields> {
        val name = requireAppname()
        val answer = send {
            http.prepareGet("$baseUrl/reports/$id") {
                parameter("appname", name)
                parameter("slim", 1)
                fields.forEach { parameter("fields[include][]", it) }
                header(HttpHeaders.Accept, "application/json")
            }
        }
        val parsed = decode(answer, ReportFields.serializer())
        return parsed.data.firstOrNull() ?: throw ApiException.NotFound()
    }

    /** Checks the appname with a call that returns no documents. Returns the total count. */
    suspend fun check(): Int = reports(ApiQuery(limit = 0, sort = emptyList(), fields = listOf("id"))).totalCount

    private suspend fun <T> post(resource: String, query: ApiQuery, serializer: KSerializer<T>): ApiResponse<T> {
        val name = requireAppname()
        val body = query.toJson().toString()
        val answer = send {
            http.preparePost("$baseUrl/$resource") {
                parameter("appname", name)
                header(HttpHeaders.Accept, "application/json")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return decode(answer, serializer)
    }

    private fun requireAppname(): String =
        appname()?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiException.NoAppname()

    /** A body read within its limit, with the status it came with. */
    private class Answer(val status: Int, val text: String)

    /**
     * Sends the request and reads its body while it streams in, at most [MAX_ANSWER_BYTES] (review
     * of PR 26: a plain get or post holds the whole body in memory before it can be counted).
     */
    private suspend fun send(statement: suspend () -> HttpStatement): Answer {
        onCall()
        return try {
            statement().execute { response -> Answer(response.status.value, text(response)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // Do not pass the original message on: it may contain the URL.
            throw ApiException.Network(e)
        }
    }

    /** The answer as JSON, with the same errors as [decode]. */
    private fun raw(answer: Answer): kotlinx.serialization.json.JsonElement =
        try {
            RelaisJson.parseToJsonElement(answer.text)
        } catch (e: Exception) {
            throw ApiException.Server(answer.status, "${ApiException.Server.UNREADABLE}: ${e::class.simpleName}")
        }

    private fun <T> decode(answer: Answer, serializer: KSerializer<T>): ApiResponse<T> =
        try {
            RelaisJson.decodeFromString(ApiResponse.serializer(serializer), answer.text)
        } catch (e: Exception) {
            throw ApiException.Server(answer.status, "${ApiException.Server.UNREADABLE}: ${e::class.simpleName}")
        }

    /** The body of a 200 answer, or the error it says. At most [MAX_ANSWER_BYTES]. */
    private suspend fun text(response: HttpResponse): String {
        val text = try {
            Bodies.text(response, MAX_ANSWER_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Bodies.TooLarge) {
            throw ApiException.Server(response.status.value, "Answer over ${MAX_ANSWER_BYTES / (1024 * 1024)} MB")
        } catch (e: Exception) {
            throw ApiException.Network(e)
        }
        val status = response.status.value
        if (text.trimStart().startsWith("<")) throw ApiException.Blocked()
        when {
            status == 200 -> return text
            status == 400 -> throw ApiException.BadRequest(errorMessage(text) ?: "Bad request")
            status == 403 -> throw ApiException.AccessDenied(errorMessage(text) ?: "Access denied")
            status == 404 -> throw ApiException.NotFound()
            status == 429 -> throw ApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
            else -> throw ApiException.Server(status, errorMessage(text) ?: "HTTP $status")
        }
    }

    /**
     * Reads `error.message` from an error body such as `{"status":400,"error":{"message":"…"}}`,
     * without the appname if the server repeats it (security audit of 15/11).
     */
    private fun errorMessage(text: String): String? = runCatching {
        RelaisJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.let { redact(it, appname()) }

    companion object {
        /**
         * The largest answer read: a page of 200 reports with their full text is a few MB; more
         * is not an answer of the API (security audit of 15/11). 16 MB since the review of PR 26:
         * the body, its text and the parsed objects held about 100 MB at the worst with 32.
         */
        const val MAX_ANSWER_BYTES = 16L * 1024 * 1024

        /** [text] with every occurrence of [appname] replaced by "[appname]", whatever its case. */
        fun redact(text: String, appname: String?): String {
            val name = appname?.trim()?.takeIf { it.isNotEmpty() } ?: return text
            return text.replace(name, "[appname]", ignoreCase = true)
        }
    }
}
