// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.api.Display
import io.relais.app.AppContainer
import io.relais.app.BuildConfig
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.AccessCheck
import io.relais.app.data.Settings
import io.relais.app.work.Work
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Request an appname" (produit/textes-connect.md): the Appname section of the API's
 * documentation, which always points to ReliefWeb's current form. A form's own address can
 * change, and an app without maintenance could not follow it.
 */
private const val APPNAME_HELP_URL = "https://apidoc.reliefweb.int/#appname"
private const val TERMS_URL = "https://reliefweb.int/terms-conditions"
private val DIGEST_HOURS = listOf(7, 8, 12, 18)

// --- Appname form, shared by M3 and M12 --------------------------------------------------------

@Composable
private fun accessMessage(result: AccessCheck?): String? = when (result) {
    null -> null
    AccessCheck.OK -> stringResource(R.string.access_success)
    AccessCheck.REFUSED -> stringResource(R.string.access_refused)
    AccessCheck.NO_NETWORK, AccessCheck.OTHER -> stringResource(R.string.access_no_network)
}

/**
 * "Connect to ReliefWeb" (produit/textes-connect.md): the entry of the public APK, which ships
 * without an appname (PLAN.md 0octies). What an appname is, why each reader needs one, the
 * field, and "Continue with headlines only".
 */
@Composable
fun ConnectContent(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("connect_title"))
    Text(stringResource(R.string.access_what), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.access_why), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    AppnameForm(onSaved = onDone, secondaryLabel = stringResource(R.string.access_headlines_only), onSecondary = onDone)
    Text(stringResource(R.string.access_headlines_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
}

/**
 * Asked once after an update to a build without an appname, when this phone has none of its own
 * (an APK with the built-in appname, then the public one): its data stay, and it says so plainly.
 */
@Composable
fun ConnectScreen() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectContent(onDone = { scope.launch { c.settings.setConnectAsked() } })
    }
}

/** Field for a ReliefWeb appname. The value is checked with one call, then kept only on the phone. */
@Composable
fun AppnameForm(
    onSaved: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    /** "Save" next to the field, in Settings (v0.4); "Check and continue" under it otherwise. */
    inline: Boolean = false,
) {
    // The container is read on "Check and continue" only: the form then draws in a screenshot
    // test, without the app (README, v0.3.1).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // Kept across a rotation while the form stays open (review of PR 13).
    var value by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AccessCheck?>(null) }

    // The keyboard's action key saves as the button does: nothing waits for another gesture.
    fun submit() {
        if (value.isBlank() || checking) return
        scope.launch {
            val c = context.container
            checking = true
            val check = c.repository.checkAccess(value)
            result = check
            checking = false
            if (check == AccessCheck.OK) {
                c.settings.setUserAppname(value)
                c.repository.userAppname = value.trim()
                value = ""
                onSaved()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; result = null },
                label = { Text(stringResource(R.string.access_field)) },
                supportingText = { Text(accessMessage(result) ?: stringResource(R.string.access_field_help)) },
                isError = result == AccessCheck.REFUSED,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).testTag("appname_field"),
            )
            if (inline) {
                Button(enabled = value.isNotBlank() && !checking, onClick = ::submit, modifier = Modifier.testTag("appname_save")) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
        TextButton(onClick = { safeOpen(uriHandler, APPNAME_HELP_URL) }, modifier = Modifier.testTag("access_request")) {
            Text(stringResource(R.string.access_help_link))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!inline) {
                Button(enabled = value.isNotBlank() && !checking, onClick = ::submit) { Text(stringResource(R.string.access_check)) }
            }
            if (checking) CircularProgressIndicator(Modifier.padding(start = 8.dp))
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

// --- M12 Settings ----------------------------------------------------------------------------

data class SettingsState(
    val settings: Settings = Settings(),
    val followedCount: Int = 0,
    val pdfCount: Int = 0,
    val pdfBytes: Long = 0,
    val savedIds: Set<Long> = emptySet(),
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsState> = combine(
        c.settings.flow, c.dao.observeFollowed(), c.dao.observePdfs(), c.dao.observeSaved(),
    ) { s, followed, pdfs, saved ->
        SettingsState(s, followed.size, pdfs.size, pdfs.sumOf { it.bytes }, saved.map { it.reading.reportId }.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    val hasBuiltIn = c.repository.hasBuiltInAccess

    suspend fun check(): AccessCheck = c.repository.checkAccess()

    fun removeOwn() = viewModelScope.launch {
        c.settings.setUserAppname(null)
        c.repository.userAppname = null
    }

    fun setDigest(context: android.content.Context, enabled: Boolean, hour: Int, minute: Int) = viewModelScope.launch {
        c.settings.setDigest(enabled, hour, minute)
        Work.scheduleDigest(context, enabled, hour, minute)
    }

    suspend fun historyToRemove(days: Int): Int = c.repository.historyOlderThan(days)

    /** The keep applies at once: History loses what is older right away (Produit, 14/11). */
    fun setHistoryDays(days: Int) = viewModelScope.launch {
        c.settings.setHistoryDays(days)
        c.repository.expireHistoryOlderThan(days)
    }
    fun setReadingSize(index: Int) = viewModelScope.launch { c.settings.setReadingSize(index) }
    fun setDisplay(d: DisplaySize) = viewModelScope.launch { c.settings.setDisplay(d.name, d.reportStep.ordinal) }
    fun setAlerts(on: Boolean) = viewModelScope.launch { c.settings.setAlerts(on) }
    fun clearHistory() = viewModelScope.launch { c.repository.clearHistory() }
    fun deletePdfs(keep: Set<Long>) = viewModelScope.launch { c.pdfs.deleteAll(keep) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val vm = viewModel { SettingsViewModel(context.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<AccessCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var editingAppname by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val appnameSaved = stringResource(R.string.settings_appname_saved)
    var confirmClear by remember { mutableStateOf(false) }
    var shortenHistory by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showWhatsNew by remember { mutableStateOf<WhatsNew.Release?>(null) }
    var confirmPdfs by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setDigest(context, true, s.digestHour, s.digestMinute)
    }
    val alertPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setAlerts(true)
    }

    fun enableDigest(hour: Int, minute: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Work.canNotify(context)) {
            vm.setDigest(context, false, hour, minute)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setDigest(context, true, hour, minute)
        }
    }

    // Every setting applies as soon as it changes (the founder, 14/11): switches, chips and
    // sizes write at once; the appname saves from its button or the keyboard's key.
    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = { nav.back() }, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader(stringResource(R.string.settings_access))
            val accessLabel = when {
                s.userAppname != null -> stringResource(R.string.settings_own)
                vm.hasBuiltIn -> stringResource(R.string.settings_builtin)
                else -> stringResource(R.string.settings_none)
            }
            SettingText(accessLabel)
            accessMessage(checkResult)?.let { SettingText(it, secondary = true) }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !checking && (s.userAppname != null || vm.hasBuiltIn),
                    onClick = { scope.launch { checking = true; checkResult = vm.check(); checking = false } },
                ) { Text(stringResource(R.string.settings_check)) }
                if (checking) CircularProgressIndicator(Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { editingAppname = !editingAppname }, modifier = Modifier.padding(horizontal = 8.dp).testTag("settings_use_own")) {
                Text(stringResource(R.string.settings_use_own))
            }
            if (editingAppname) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    AppnameForm(
                        onSaved = {
                            editingAppname = false
                            checkResult = AccessCheck.OK
                            scope.launch { snackbar.showSnackbar(appnameSaved) }
                        },
                        inline = true,
                    )
                }
            }
            if (s.userAppname != null) {
                TextButton(onClick = { vm.removeOwn(); checkResult = null }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.settings_remove_own))
                }
            }
            HorizontalDivider()

            // No Countries section since v0.4: Following > Manage has them, with crises and
            // organizations (revue-densite-v0.3.md, change 12).
            SectionHeader(stringResource(R.string.settings_digest))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_digest_around, "%02d:%02d".format(s.digestHour, s.digestMinute)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = s.digestEnabled, onCheckedChange = { on ->
                    if (on) enableDigest(s.digestHour, s.digestMinute) else vm.setDigest(context, false, s.digestHour, s.digestMinute)
                })
            }
            // The hours only while the summary is on (change 12).
            if (s.digestEnabled) FlowRow(Modifier.padding(horizontal = 16.dp).testTag("digest_hours"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DIGEST_HOURS.forEach { hour ->
                    FilterChip(
                        selected = s.digestHour == hour && s.digestMinute == 0,
                        onClick = { enableDigest(hour, 0) },
                        label = { Text("%02d:00".format(hour)) },
                    )
                }
                FilterChip(
                    selected = s.digestHour !in DIGEST_HOURS || s.digestMinute != 0,
                    onClick = {
                        TimePickerDialog(context, { _, h, m -> enableDigest(h, m) }, s.digestHour, s.digestMinute, true).show()
                    },
                    label = { Text(stringResource(R.string.digest_other_time)) },
                )
            }
            // Optional alerts (passe-v0.2.md 6): off by default, on their own Android channel.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_alerts), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.alerts,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Work.canNotify(context)) {
                            alertPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setAlerts(on)
                        }
                    },
                    modifier = Modifier.testTag("alerts_switch"),
                )
            }
            SettingText(stringResource(R.string.settings_alerts_help), secondary = true)
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionHeader(stringResource(R.string.settings_reading))
            // "Display" for the whole app, then the report text (theme-compose.md 12.6).
            TextSizeControl(
                s.readingSize,
                sample = null,
                onChange = vm::setReadingSize,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onDisplay = vm::setDisplay,
                preview = true,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionHeader(stringResource(R.string.settings_library))
            SettingText(stringResource(R.string.settings_keep_history))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90 to R.string.keep_90, 365 to R.string.keep_365, -1 to R.string.keep_forever).forEach { (days, label) ->
                    FilterChip(selected = s.historyDays == days, onClick = {
                        // A shorter keep that removes reports asks first, with their number.
                        scope.launch {
                            val removed = vm.historyToRemove(days)
                            if (removed > 0) shortenHistory = days to removed else vm.setHistoryDays(days)
                        }
                    }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("keep_history_$days"))
                }
            }
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.settings_clear_history))
            }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_storage))
            // The space the PDFs take, their cap and the Wi-Fi preload (ui/PdfStorage.kt).
            PdfStorageSection()
            TextButton(
                enabled = state.pdfCount > 0,
                onClick = { confirmPdfs = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_delete_pdfs)) }
            HorizontalDivider()

            AboutSection(onLicences = nav::licences, onWhatsNew = { showWhatsNew = it })
        }
    }

    showWhatsNew?.let { release -> WhatsNewSheet(release, onDismiss = { showWhatsNew = null }) }

    shortenHistory?.let { (days, removed) ->
        AlertDialog(
            onDismissRequest = { shortenHistory = null },
            text = { Text(pluralStringResource(R.plurals.settings_history_shorten, removed, removed, days)) },
            confirmButton = {
                TextButton(onClick = { vm.setHistoryDays(days); shortenHistory = null }, modifier = Modifier.testTag("keep_history_confirm")) {
                    Text(stringResource(R.string.settings_remove))
                }
            },
            dismissButton = { TextButton(onClick = { shortenHistory = null }) { Text(stringResource(R.string.report_cancel)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.settings_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text(stringResource(R.string.settings_clear_history)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.report_cancel)) } },
        )
    }
    if (confirmPdfs) {
        AlertDialog(
            onDismissRequest = { confirmPdfs = false },
            text = { Text(stringResource(R.string.settings_delete_pdfs_confirm, state.pdfCount, Display.size(state.pdfBytes) ?: "0 KB")) },
            confirmButton = {
                TextButton(onClick = { vm.deletePdfs(state.savedIds); confirmPdfs = false }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmPdfs = false }) { Text(stringResource(R.string.report_cancel)) } },
        )
    }
}

/**
 * Settings > About Re.L: what's new, what the app is, its licences and links, its version, then
 * how to say thanks or report something, on GitHub (no server, no tracking).
 */
@Composable
fun AboutSection(onLicences: () -> Unit, onWhatsNew: (WhatsNew.Release) -> Unit) {
    val uriHandler = LocalUriHandler.current
    SectionHeader(stringResource(R.string.settings_about))
    // "What's new" (theme-compose.md 13.4): the lines of the latest version that has some.
    WhatsNew.latest(BuildConfig.VERSION_CODE)?.let { release ->
        Column(
            Modifier.fillMaxWidth().clickable { onWhatsNew(release) }
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings_whats_new"),
        ) {
            Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.whats_new_version, release.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SettingText(stringResource(R.string.settings_about_text))
    // T19: the licences always; the source code and feedback once their link exists.
    TextButton(onClick = onLicences, modifier = Modifier.padding(horizontal = 8.dp).testTag("settings_licences")) {
        Text(stringResource(R.string.licences_title))
    }
    if (BuildConfig.SOURCE_URL.isNotBlank()) {
        TextButton(onClick = { safeOpen(uriHandler, BuildConfig.SOURCE_URL) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.about_source_code))
        }
    }
    if (BuildConfig.FEEDBACK_URL.isNotBlank()) {
        TextButton(onClick = { safeOpen(uriHandler, BuildConfig.FEEDBACK_URL) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("settings_feedback")) {
            Text(stringResource(R.string.about_send_feedback))
        }
    }
    TextButton(onClick = { safeOpen(uriHandler, TERMS_URL) }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.settings_terms))
    }
    SettingText(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), secondary = true)
    // The founder's request (v0.3.1): a way to say thanks and to give feedback without a server
    // or any tracking. A star and an issue on GitHub; nothing that opens by itself, no reminder.
    SettingText(stringResource(R.string.about_made_with), secondary = true)
    if (BuildConfig.SOURCE_URL.isNotBlank()) {
        TextButton(onClick = { safeOpen(uriHandler, BuildConfig.SOURCE_URL) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("about_star")) {
            Text(stringResource(R.string.about_star))
        }
    }
    if (BuildConfig.ISSUES_URL.isNotBlank()) {
        TextButton(onClick = { safeOpen(uriHandler, BuildConfig.ISSUES_URL) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("about_issue")) {
            Text(stringResource(R.string.about_open_issue))
        }
    }
}

@Composable
private fun SettingText(text: String, secondary: Boolean = false) {
    Text(
        text,
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// --- M1 to M4: first launch --------------------------------------------------------------------

private enum class Step { WELCOME, COUNTRIES, ACCESS, DIGEST }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingFlow() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var hour by rememberSaveable { mutableStateOf(8) }
    var minute by rememberSaveable { mutableStateOf(0) }
    var refused by rememberSaveable { mutableStateOf(false) }

    fun finish(digest: Boolean) {
        scope.launch {
            c.settings.setDigest(digest, hour, minute)
            Work.scheduleDigest(context, digest, hour, minute)
            c.settings.setOnboardingDone()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) finish(true) else refused = true
    }

    // Without an appname, "Connect to ReliefWeb" comes before the countries: the reader learns
    // what an appname opens before choosing what to follow (first launch review, 13/11).
    fun afterWelcome() {
        step = if (c.repository.hasAccess) Step.COUNTRIES else Step.ACCESS
    }

    fun afterCountries() {
        step = Step.DIGEST
    }

    when (step) {
        Step.WELCOME -> Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.welcome_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onClick = ::afterWelcome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_button)) }
        }

        Step.COUNTRIES -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PickCountriesScreen(onDone = ::afterCountries, onboarding = true, onSkip = ::afterCountries)
        }

        Step.ACCESS -> Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectContent(onDone = { step = Step.COUNTRIES })
        }

        Step.DIGEST -> Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.digest_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.digest_text), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DIGEST_HOURS.forEach { h ->
                    FilterChip(selected = hour == h && minute == 0, onClick = { hour = h; minute = 0 }, label = { Text("%02d:00".format(h)) })
                }
                FilterChip(
                    selected = hour !in DIGEST_HOURS || minute != 0,
                    onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() },
                    label = { Text(stringResource(R.string.digest_other_time)) },
                )
            }
            if (refused) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.digest_refused), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    if (refused) {
                        finish(false)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Work.canNotify(context)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        finish(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (refused) R.string.pick_continue else R.string.digest_turn_on)) }
            TextButton(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.digest_not_now)) }
        }
    }
}
