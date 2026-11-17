// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.relais.app.AppContainer
import io.relais.app.R
import io.relais.app.container
import io.relais.app.data.FolderEntity
import io.relais.app.data.FolderRow
import io.relais.app.data.Folders
import io.relais.app.data.HighlightExport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * Folders of the Library (founder, 17/11; equipe/produit/fiche-dossiers-library.md): a name the
 * reader chose, put on saved reports. The tab Folders, a folder's screen, the sheet "Add to
 * folder" and "Add reports".
 */

/** "12 reports · 34 highlights". */
@Composable
private fun folderCounts(reports: Int, highlights: Int): String = stringResource(
    R.string.folder_counts,
    pluralStringResource(R.plurals.export_reports, reports, reports),
    pluralStringResource(R.plurals.export_highlights, highlights, highlights),
)

/** The tab Folders: each folder with its counts, the last changed first. */
@Composable
internal fun FoldersPage(folders: List<FolderRow>, listState: LazyListState, onOpen: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag("page_folders"), state = listState) {
        items(folders, key = { it.id }) { f ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(f.id) }
                    .padding(horizontal = Space.screen, vertical = Space.rowVertical).testTag("folder_${f.id}"),
                verticalArrangement = Arrangement.spacedBy(Space.kickerGap),
            ) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(folderCounts(f.reports, f.highlights), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = Space.screen), thickness = Hairline, color = cs.outlineVariant)
        }
    }
}

/**
 * The content of "Add to folder": each folder with a box, ticked when it holds the report; a
 * tick puts it there at once, with no button to confirm. "New folder" at the end.
 */
@Composable
internal fun FolderSheetContent(folders: List<FolderRow>, checked: Set<Long>, onToggle: (FolderRow, Boolean) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Space.xl).navigationBarsPadding().testTag("folder_sheet")) {
        Text(
            stringResource(R.string.folder_add),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = Space.screen).padding(bottom = Space.s),
        )
        folders.forEach { f ->
            val on = f.id in checked
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggle(f, !on) }
                    .padding(horizontal = Space.s).testTag("folder_check_${f.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = { onToggle(f, it) })
                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onNew).padding(horizontal = Space.screen).testTag("folder_new"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text(stringResource(R.string.folder_new), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * "Add to folder" for one report, from its menu or a row of Saved or History. Putting it in a
 * folder saves it (rule 3): [onMessage] says "Saved and added to …" then, else "Added to …".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToFolderSheet(reportId: Long, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders by remember { c.dao.observeFolders() }.collectAsStateWithLifecycle(emptyList())
    val inFolders by remember(reportId) { c.dao.observeFolderIdsOfReport(reportId) }.collectAsStateWithLifecycle(emptyList())
    var naming by rememberSaveable { mutableStateOf(false) }
    fun added(name: String, saved: Int) {
        onMessage(context.getString(if (saved > 0) R.string.folder_saved_and_added else R.string.folder_added, name))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FolderSheetContent(
            folders,
            inFolders.toSet(),
            onToggle = { folder, on ->
                scope.launch {
                    if (on) added(folder.name, c.folders.add(folder.id, listOf(reportId))) else c.folders.remove(folder.id, reportId)
                }
            },
            onNew = { naming = true },
        )
    }
    if (naming) {
        FolderNameDialog(
            title = stringResource(R.string.folder_new),
            confirm = stringResource(R.string.folder_create),
            initial = "",
            onDismiss = { naming = false },
            submit = { name ->
                val result = c.folders.create(name)
                if (result is Folders.NameCheck.Created) {
                    added(result.name, c.folders.add(result.id, listOf(reportId)))
                    naming = false
                }
                result
            },
        )
    }
}

/**
 * The name of a new folder, or a new name: 1 to 60 characters, never the name of another folder
 * (whatever the capitals). An empty name or a name taken is refused with its message.
 */
@Composable
internal fun FolderNameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, submit: suspend (String) -> Folders.NameCheck) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(Folders.MAX_NAME); error = null },
                label = { Text(stringResource(R.string.folder_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it), modifier = Modifier.testTag("folder_name_error")) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("folder_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    when (submit(name)) {
                        Folders.NameCheck.Empty -> error = R.string.folder_name_empty
                        is Folders.NameCheck.Taken -> error = R.string.folder_name_taken
                        else -> Unit
                    }
                }
            }, modifier = Modifier.testTag("folder_name_confirm")) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.report_cancel)) } },
    )
}

/** One folder: its name and its reports, the last added first, with the reading of each. */
class FolderViewModel(private val c: AppContainer, private val folderId: Long) : ViewModel() {
    val folder: StateFlow<FolderEntity?> = c.dao.observeFolder(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<LibraryItem>?> = combine(c.dao.observeFolderReadings(folderId), c.pdfs.downloaded) { rows, pdfs ->
        rows.mapNotNull { row -> row.report?.let { LibraryItem(row, it, it.files.any { f -> f.url in pdfs }) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(reportId: Long) = viewModelScope.launch { c.folders.remove(folderId, reportId) }

    suspend fun rename(name: String) = c.folders.rename(folderId, name)

    suspend fun delete() = c.folders.delete(folderId)

    /** "Export folder": its reports and their highlights, as the export of highlights. */
    suspend fun export(name: String): HighlightExport.Result =
        HighlightExport.buildFolder(name, c.dao.folderReports(folderId), c.dao.folderHighlights(folderId), System.currentTimeMillis())
}

/** A folder: its reports as in Saved; in the menu Add reports, Export folder, Rename, Delete folder. */
@Composable
fun FolderScreen(folderId: Long, nav: Nav) {
    val c = LocalContext.current.container
    val vm = viewModel(key = "folder-$folderId") { FolderViewModel(c, folderId) }
    val folder by vm.folder.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<HighlightExport.Result?>(null) }
    val name = folder?.name.orEmpty()
    val empty = items?.isEmpty() == true
    ScreenScaffold(
        title = name,
        onBack = { nav.back() },
        snackbarHostState = snackbar,
        actions = {
            OverflowMenu { close ->
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_add_reports)) }, onClick = { close(); nav.addReports(folderId) }, modifier = Modifier.testTag("folder_menu_add"))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folder_export)) },
                    enabled = !empty,
                    onClick = { close(); scope.launch { exportResult = vm.export(name) } },
                    modifier = Modifier.testTag("folder_menu_export"),
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_rename)) }, onClick = { close(); renaming = true }, modifier = Modifier.testTag("folder_menu_rename"))
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_delete)) }, onClick = { close(); deleting = true }, modifier = Modifier.testTag("folder_menu_delete"))
            }
        },
    ) { padding ->
        FolderList(items, Modifier.padding(padding), onOpen = nav::report, onRemove = vm::remove, onAddReports = { nav.addReports(folderId) })
    }

    if (renaming) {
        FolderNameDialog(
            title = stringResource(R.string.folder_rename),
            confirm = stringResource(R.string.folder_save),
            initial = name,
            onDismiss = { renaming = false },
            submit = { new -> vm.rename(new).also { if (it is Folders.NameCheck.Ok) renaming = false } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            text = { Text(stringResource(R.string.folder_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        vm.delete()
                        nav.back()
                    }
                }, modifier = Modifier.testTag("folder_delete_confirm")) { Text(stringResource(R.string.folder_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.report_cancel)) } },
        )
    }
    exportResult?.let { result -> ExportSheet(result, onDismiss = { exportResult = null }, title = stringResource(R.string.folder_export)) }
}

/** A folder's reports, each with the status of Saved and "Remove from this folder". */
@Composable
internal fun FolderList(items: List<LibraryItem>?, modifier: Modifier = Modifier, onOpen: (Long) -> Unit, onRemove: (Long) -> Unit, onAddReports: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().testTag("folder_reports")) {
        if (items?.isEmpty() == true) {
            item { EmptyState(stringResource(R.string.folder_empty), stringResource(R.string.folder_add_reports), onAddReports) }
        }
        items(items.orEmpty(), key = { it.report.id }) { item ->
            DocumentRow(item.report, onClick = { onOpen(item.report.id) }, status = savedStatus(item), pdfPreview = true) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folder_remove_report)) },
                    onClick = { close(); onRemove(item.report.id) },
                    modifier = Modifier.testTag("folder_remove_report"),
                )
            }
        }
    }
}

/**
 * "Add reports": the reports of Saved with a box each, the Library's search and filters above, and
 * "Add (n)" for those ticked. The way to fill a folder at once: filter Saved on Chad, tick, Add.
 * Reports already in the folder show ticked and cannot be unticked here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReportsScreen(folderId: Long, nav: Nav) {
    val c = LocalContext.current.container
    val vm = viewModel(key = "add-reports-$folderId") { LibraryViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val inFolder by remember(folderId) { c.dao.observeFolderReadings(folderId) }.collectAsStateWithLifecycle(emptyList())
    val already = inFolder.map { it.reading.reportId }.toSet()
    var picked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var openFacet by remember { mutableStateOf<LibraryFacet?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.setView(LibraryView.SAVED) }
    ScreenScaffold(
        title = stringResource(R.string.folder_add_reports),
        onBack = { nav.back() },
        actions = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    scope.launch {
                        c.folders.add(folderId, picked)
                        nav.back()
                    }
                },
                modifier = Modifier.testTag("add_reports_confirm"),
            ) { Text(stringResource(R.string.folder_add_selected, picked.size)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val active = state.filters.values.count { it.isNotEmpty() }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.setQuery(it) },
                label = { Text(stringResource(R.string.library_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    val label = if (active > 0) stringResource(R.string.results_filters_count, active) else stringResource(R.string.results_filters)
                    IconButton(onClick = { filterSheet = true }, modifier = Modifier.testTag("add_reports_filters")) {
                        Icon(Icons.Outlined.FilterList, contentDescription = label)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("add_reports_search"),
            )
            val rows = state.saved.groups.flatMap { it.second }.distinctBy { it.report.id }
            LazyColumn(Modifier.fillMaxSize().testTag("add_reports_list")) {
                if (state.loaded && state.saved.all.isEmpty()) {
                    item { EmptyState(stringResource(R.string.folder_no_saved)) }
                }
                items(rows, key = { it.report.id }) { item ->
                    val id = item.report.id
                    val inside = id in already
                    val on = inside || id in picked
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !inside) { picked = if (on) picked - id else picked + id }
                            .padding(end = Space.screen, top = 4.dp, bottom = 4.dp).testTag("pick_$id"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, enabled = !inside, onCheckedChange = { picked = if (it) picked + id else picked - id })
                        Column(Modifier.weight(1f)) {
                            Text(item.report.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (inside) stringResource(R.string.folder_already_in) else listOfNotNull(item.report.sourceShort.ifBlank { null }, item.report.primaryCountryName).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = Space.screen), thickness = Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            LibraryFilterSheet(state, onFacet = { filterSheet = false; openFacet = it }, onGroupBy = vm::setGroupBy, onClear = { vm.clearFilters(); vm.setGroupBy(GroupBy.DATE) })
        }
    }
    openFacet?.let { facet ->
        FacetDialog(state, facet, onApply = { vm.setFilter(facet, it); openFacet = null }, onDismiss = { openFacet = null })
    }
}
