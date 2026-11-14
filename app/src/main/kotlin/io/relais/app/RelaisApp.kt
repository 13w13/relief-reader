// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.relais.api.ApiResponse
import io.relais.api.CountryFields
import io.relais.api.RelaisJson
import io.relais.app.data.CountryEntity
import io.relais.app.data.PdfDownloader
import io.relais.app.data.Popular
import io.relais.app.data.RelaisDatabase
import io.relais.app.data.Repository
import io.relais.app.data.SettingsStore
import io.relais.app.pdf.PdfTextStore
import io.relais.app.work.Work
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RelaisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        Work.schedulePeriodicSync(this)
        container.scope.launch { io.relais.app.work.PreloadWorker.schedule(this@RelaisApp, container.settings.current().preloadPdfs) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Work.DIGEST_CHANNEL,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // A channel of its own, so that the reader can mute alerts and keep the daily summary.
        val alerts = NotificationChannel(Work.ALERT_CHANNEL, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(alerts)
    }
}

val Context.container: AppContainer get() = (applicationContext as RelaisApp).container

/** Manual dependency injection: one instance of each service for the whole app. */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userAgent = "Re.L/${BuildConfig.VERSION_NAME} (Android; unofficial ReliefWeb reader)"

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val http = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
        install(UserAgent) { agent = userAgent }
        expectSuccess = false
    }

    private val database = RelaisDatabase.create(context)
    val dao = database.dao()
    val settings = SettingsStore(context)

    /** The app's tuning (sync and cache times, features): built-in values, nothing hosted (PLAN.md 0octies). */
    val remoteConfig = io.relais.app.data.RemoteConfigStore()

    val repository = Repository(
        dao = dao,
        settings = settings,
        config = { remoteConfig.current },
        http = http,
        builtInAppname = BuildConfig.RELIEFWEB_APPNAME,
        bundledCountries = ::loadBundledCountries,
        transaction = { block -> database.withTransaction { block() } },
    )

    /** Text of the downloaded PDFs (pdf/), in its own database, pdftext.db. */
    val pdfText = PdfTextStore(context, scope)

    val pdfs = PdfDownloader(
        context, dao, okHttp, scope, userAgent,
        onSaved = { url, reportId, file -> pdfText.extractLater(url, reportId, file) },
        onDeleted = { url -> pdfText.forget(url) },
        capBytes = { io.relais.app.data.PdfCache.capBytes(settings.current().pdfCapMb) },
        // Saved and highlighted reports keep their PDF, whatever the cap.
        protectedReports = {
            dao.observeSaved().first().map { it.reading.reportId }.toSet() +
                dao.observeAllHighlights().first().map { it.reportId }.toSet()
        },
    )

    val popular = Popular(context, okHttp, userAgent, repository, dao, config = { remoteConfig.current })

    /** A Library view to open, by its index (Today's "See all" opens History). */
    val libraryRequest = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** A screen to open from a notification: a report id, or -1 for Following (theme-compose.md 10.15). */
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** The last headline read from the pile: Today stands on its card when the reader comes back (v0.4). */
    val headlineFocus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    init {
        // Read the appname entered on the phone before the first request, then follow changes.
        repository.userAppname = runBlocking { settings.current().userAppname }
        scope.launch {
            // An appname added on the phone (Settings, Connect, onboarding): sync at once, so the
            // followed countries fill in now and not at the next background sync.
            var had = repository.hasAccess
            settings.flow.collect {
                repository.userAppname = it.userAppname
                val has = repository.hasAccess
                if (has && !had) scope.launch { runCatching { repository.syncAfterAccess() } }
                had = has
            }
        }
        repository.alertSink = { plan -> io.relais.app.work.Work.notifyAlerts(context, plan) }
    }

    /** Country list shipped with the app (assets/countries.json), for a first launch offline. */
    private fun loadBundledCountries(): List<CountryEntity> = runCatching {
        val text = context.assets.open("countries.json").bufferedReader().use { it.readText() }
        RelaisJson.decodeFromString(ApiResponse.serializer(CountryFields.serializer()), text).data.mapNotNull {
            val f = it.fields
            if (f.name.isBlank()) null else CountryEntity(it.id, f.name, f.shortname, f.iso3, f.status)
        }
    }.getOrDefault(emptyList())
}
