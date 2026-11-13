// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
    class Server(status: Int, message: String) : ApiException(message, status)
    /** An HTML page instead of JSON: the request was probably blocked upstream. */
    class Blocked : ApiException("ReliefWeb returned a web page instead of data")
    class Network(cause: Throwable) : ApiException("Network error: ${cause::class.simpleName}", null, cause)
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

    /** One document of [resource] with every field (`profile=full`), or throws [ApiException.NotFound]. */
    private suspend fun <T> one(resource: String, id: Long, serializer: KSerializer<T>): ApiItem<T> {
        val name = requireAppname()
        val response = send {
            http.get("$baseUrl/$resource/$id") {
                parameter("appname", name)
                parameter("profile", "full")
                parameter("slim", 1)
                header(HttpHeaders.Accept, "application/json")
            }
        }
        return decode(response, serializer).data.firstOrNull() ?: throw ApiException.NotFound()
    }

    /** One report with its full text, or throws [ApiException.NotFound]. */
    suspend fun report(id: Long, fields: List<String> = Fields.REPORT_FULL): ApiItem<ReportFields> {
        val name = requireAppname()
        val response = send {
            http.get("$baseUrl/reports/$id") {
                parameter("appname", name)
                parameter("slim", 1)
                fields.forEach { parameter("fields[include][]", it) }
                header(HttpHeaders.Accept, "application/json")
            }
        }
        val parsed = decode(response, ReportFields.serializer())
        return parsed.data.firstOrNull() ?: throw ApiException.NotFound()
    }

    /** Checks the appname with a call that returns no documents. Returns the total count. */
    suspend fun check(): Int = reports(ApiQuery(limit = 0, sort = emptyList(), fields = listOf("id"))).totalCount

    private suspend fun <T> post(resource: String, query: ApiQuery, serializer: KSerializer<T>): ApiResponse<T> {
        val name = requireAppname()
        val body = query.toJson().toString()
        val response = send {
            http.post("$baseUrl/$resource") {
                parameter("appname", name)
                header(HttpHeaders.Accept, "application/json")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        return decode(response, serializer)
    }

    private fun requireAppname(): String =
        appname()?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiException.NoAppname()

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse {
        onCall()
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // Do not pass the original message on: it may contain the URL.
            throw ApiException.Network(e)
        }
    }

    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): ApiResponse<T> {
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Network(e)
        }
        val status = response.status.value
        if (text.trimStart().startsWith("<")) throw ApiException.Blocked()
        when {
            status == 200 -> return try {
                RelaisJson.decodeFromString(ApiResponse.serializer(serializer), text)
            } catch (e: Exception) {
                throw ApiException.Server(status, "Unreadable response: ${e::class.simpleName}")
            }
            status == 400 -> throw ApiException.BadRequest(errorMessage(text) ?: "Bad request")
            status == 403 -> throw ApiException.AccessDenied(errorMessage(text) ?: "Access denied")
            status == 404 -> throw ApiException.NotFound()
            status == 429 -> throw ApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
            else -> throw ApiException.Server(status, errorMessage(text) ?: "HTTP $status")
        }
    }

    /** Reads `error.message` from an error body such as `{"status":400,"error":{"message":"…"}}`. */
    private fun errorMessage(text: String): String? = runCatching {
        RelaisJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
