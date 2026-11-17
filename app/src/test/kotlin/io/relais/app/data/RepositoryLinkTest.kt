// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.relais.app.data.ReliefWebLinks.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The report of a link with the real Repository (review of PR 47): the request it sends and what
 * it keeps from the answer. ReliefWeb is a MockEngine, never the network; the database is in
 * memory, the settings in a file of their own. Made-up reports; "test-app" is a placeholder.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RepositoryLinkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: RelaisDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: Repository

    /** The query of each request sent, in order. */
    private val queries = mutableListOf<String>()

    /** What "ReliefWeb" answers: these reports. */
    private var answer: List<Pair<Long, String>> = emptyList()

    private val address = "https://reliefweb.int/report/noravia/noravia-test-floods-situation-report-no-3"

    private fun body(reports: List<Pair<Long, String>>): String = reports.joinToString(
        prefix = """{"totalCount": ${reports.size}, "count": ${reports.size}, "data": [""",
        postfix = "]}",
    ) { (id, alias) ->
        """{"id": "$id", "score": 1, "fields": {"id": $id, "title": "Noravia: Test report $id", "url_alias": "$alias",
          "date": {"created": "2026-11-16T00:00:00+00:00"},
          "primary_country": {"id": 990, "name": "Noravia", "shortname": "Noravia"},
          "country": [{"id": 990, "name": "Noravia"}],
          "source": {"id": 990101, "name": "Noravia Coordination Office", "shortname": "NCO"},
          "format": [{"id": 10, "name": "Situation Report"}],
          "body-html": "<p>Water trucking reached 12,400 people.</p>"}}"""
    }

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RelaisDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        val engine = MockEngine { request ->
            val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            queries += sent["query"]!!.jsonObject["value"]!!.jsonPrimitive.content
            respond(body(answer), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        repository = Repository(
            dao = db.dao(),
            settings = SettingsStore(context, store),
            http = HttpClient(engine),
            builtInAppname = "test-app",
            bundledCountries = { emptyList() },
        )
    }

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    @Test
    fun aReportLinkAsksForItsAddressAndKeepsOnlyThatReport() = runBlocking<Unit> {
        val link = ReliefWebLinks.fromUrl(address) as Link.Report
        // ReliefWeb answers with a close address only: "not found", nothing saved.
        answer = listOf(990000331L to "$address-1")
        assertNull(repository.fetchReportForLink(link))
        assertEquals("url_alias:\"$address\"", queries.single())
        assertNull(db.dao().report(990000331))
        assertNull(repository.reportOnPhone(link))

        // The report at this address, among others: that one is saved and opens from the phone.
        answer = listOf(990000331L to "$address-1", 990000300L to address)
        assertEquals(990000300L, repository.fetchReportForLink(link))
        assertEquals(address, db.dao().report(990000300)!!.urlAlias)
        assertNull(db.dao().report(990000331))
        assertEquals(990000300L, repository.reportOnPhone(link))
        assertEquals(2, queries.size)
    }

    @Test
    fun capitalsDoNotMatterInTheAddress() = runBlocking<Unit> {
        val link = ReliefWebLinks.fromUrl(address) as Link.Report
        answer = listOf(990000300L to address.replace("noravia-test-floods", "Noravia-Test-Floods"))
        assertEquals(990000300L, repository.fetchReportForLink(link))
    }

    @Test
    fun aNodeLinkAsksForItsIdAndKeepsOnlyThatReport() = runBlocking<Unit> {
        val link = ReliefWebLinks.fromUrl("https://reliefweb.int/node/990000300") as Link.Node
        answer = listOf(990000301L to "$address-1")
        assertNull(repository.fetchReportForLink(link))
        assertEquals("id:990000300", queries.single())
        assertNull(repository.reportOnPhone(link))

        answer = listOf(990000300L to address)
        assertEquals(990000300L, repository.fetchReportForLink(link))
        // On the phone now, by its id whatever its address.
        assertEquals(990000300L, repository.reportOnPhone(link))
    }

    @Test
    fun onlyANodeLinkIsAskedById() = runBlocking<Unit> {
        // A report whose last word is a number is still asked by its address.
        val link = ReliefWebLinks.fromUrl("https://reliefweb.int/report/noravia/990000300") as Link.Report
        answer = emptyList()
        assertNull(repository.fetchReportForLink(link))
        assertEquals("url_alias:\"https://reliefweb.int/report/noravia/990000300\"", queries.single())
    }
}
