// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class RelaisApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Exports shared earlier (backup, highlights) go once ten minutes old: Android may start the
        // app cold for the FileProvider while the receiving app still reads one (review of PR 26).
        container.scope.launch(Dispatchers.IO) { io.relais.app.data.Exports.clearOld(this@RelaisApp) }
        createNotificationChannel()
        Work.schedulePeriodicSync(this)
        container.scope.launch { io.relais.app.work.PreloadWorker.schedule(this@RelaisApp, container.settings.current().preloadPdfs) }
    }

    /**
     * Images (Coil): every address through [io.relais.app.data.SafeImages], fetched by the app's
     * OkHttp client with the same User-Agent as the rest (security audit of 15/11).
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components {
                add(io.relais.app.data.SafeImages)
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { container.imageHttp }))
            }
            .build()

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

    /** One User-Agent for every request (issue 36): see [io.relais.app.data.HttpClients]. */
    val userAgent = io.relais.app.data.HttpClients.USER_AGENT

    /** The base client: the User-Agent stamped on every request, no redirect from https to http. */
    val okHttp: OkHttpClient = io.relais.app.data.HttpClients.base()

    /** For Coil: redirects and addresses checked, public addresses only, no proxy. */
    val imageHttp: OkHttpClient = io.relais.app.data.HttpClients.images(okHttp)

    /** The API and the RSS feed. */
    private val http = io.relais.app.data.HttpClients.ktor(okHttp)

    private val database = RelaisDatabase.create(context)
    val dao = database.dao()
    val settings = SettingsStore(context)

    /** The settings at launch, read once before the first request and the first frame. */
    private val startSettings = runBlocking { settings.current() }

    /**
     * "Appearance" (v0.6), known from the first frame: the activity colours its window and its
     * system bars before Compose draws, then follows each change at once.
     */
    val appearance: kotlinx.coroutines.flow.StateFlow<io.relais.app.data.Appearance> = settings.flow
        .map { io.relais.app.data.Appearance.of(it.appearance) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, io.relais.app.data.Appearance.of(startSettings.appearance))

    /**
     * Android 12 and later keep the app's night mode, and draw the launch window with it: no white
     * flash for a reader who chose Dark on a light phone. Called at each launch with the stored
     * choice, so that Android and DataStore never disagree, and when the reader changes it.
     * System gives MODE_NIGHT_AUTO, which UiModeManagerService turns into UI_MODE_NIGHT_UNDEFINED:
     * no night mode of the app's own, so the app follows the phone again.
     */
    fun applyNightMode(appearance: io.relais.app.data.Appearance) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        runCatching { context.getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(appearance.nightMode) }
    }

    /**
     * "Appearance", from Settings or the welcome screen (16/11): saved at once, then given to
     * Android, the same way from both, so the colours change without a flash.
     */
    suspend fun setAppearance(appearance: io.relais.app.data.Appearance) {
        settings.setAppearance(appearance)
        applyNightMode(appearance)
    }

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

    /** Online or not, mobile data or not: "Load images" and the rows dimmed offline (lot 3, C). */
    val network = io.relais.app.data.NetworkMonitor(context, scope)

    /** The Library in one file: export and restore (lot 3). */
    val libraryBackup = io.relais.app.data.LibraryBackupStore(dao) { block -> database.withTransaction { block() } }

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

    /**
     * "A new version is available" (founder, 15/11), while Re.L is not on F-Droid: one GET at most
     * every hour (16/11), when the app comes to the foreground, to GitHub's API, with no appname
     * and a User-Agent that is the same for everyone.
     */
    val updateCheck = io.relais.app.data.UpdateCheck(
        enabled = BuildConfig.UPDATE_CHECK,
        installed = BuildConfig.VERSION_NAME,
        settings = { settings.current() },
        record = { at, found -> settings.recordUpdateCheck(at, found) },
        online = { network.isOnline() },
        fetch = { io.relais.app.data.HttpClients.latestRelease(okHttp) },
        hold = { until -> settings.holdUpdateCheck(until) },
        releaseDate = BuildConfig.RELEASE_DATE,
    )

    /** A Library view to open, by its index (Today's "See all" opens History). */
    val libraryRequest = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** A screen to open from a notification: a report id, or -1 for Following (theme-compose.md 10.15). */
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** The last headline read from the pile: Today stands on its card when the reader comes back (v0.4). */
    val headlineFocus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    init {
        // Read the appname entered on the phone before the first request, then follow changes.
        repository.userAppname = startSettings.userAppname
        applyNightMode(io.relais.app.data.Appearance.of(startSettings.appearance))
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
