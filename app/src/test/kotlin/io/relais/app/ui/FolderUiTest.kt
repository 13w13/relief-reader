// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relais.app.data.FolderRow
import io.relais.app.data.Folders
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Folders of the Library (17/11): the tabs, the sheet, the name, an empty folder. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FolderUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val folders = listOf(FolderRow(1, "Chad 2027", 0, 2, 12, 34), FolderRow(2, "ECHO proposal", 0, 1, 0, 0))

    @Test
    fun theTabFoldersShowsFromTheFirstFolder() {
        assertEquals(listOf(LibraryView.SAVED, LibraryView.HISTORY, LibraryView.HIGHLIGHTS), libraryViews(hasFolders = false))
        assertEquals(listOf(LibraryView.SAVED, LibraryView.FOLDERS, LibraryView.HISTORY, LibraryView.HIGHLIGHTS), libraryViews(hasFolders = true))
    }

    @Test
    fun aTickPutsTheReportThereAtOnce() {
        val toggles = mutableListOf<Pair<Long, Boolean>>()
        var news = 0
        compose.setContent {
            RelaisTheme(darkTheme = false) { FolderSheetContent(folders, checked = setOf(1L), onToggle = { f, on -> toggles += f.id to on }, onNew = { news++ }) }
        }
        compose.onNodeWithTag("folder_check_2").performClick()
        compose.onNodeWithTag("folder_check_1").performClick()
        compose.onNodeWithTag("folder_new").performClick()
        assertEquals(listOf(2L to true, 1L to false), toggles)
        assertEquals(1, news)
    }

    @Test
    fun anEmptyOrTakenNameIsRefusedWithItsMessage() {
        var answer: Folders.NameCheck = Folders.NameCheck.Empty
        val asked = mutableListOf<String>()
        compose.setContent {
            RelaisTheme(darkTheme = false) {
                FolderNameDialog(title = "New folder", confirm = "Create", initial = "", onDismiss = {}, submit = { asked += it; answer })
            }
        }
        compose.onNodeWithTag("folder_name_confirm").performClick()
        compose.onNodeWithText("Give the folder a name.").assertIsDisplayed()
        answer = Folders.NameCheck.Taken(1)
        compose.onNodeWithTag("folder_name_field").performTextInput("chad 2027")
        compose.onNodeWithTag("folder_name_confirm").performClick()
        compose.onNodeWithText("You already have a folder with this name.").assertIsDisplayed()
        assertEquals(listOf("", "chad 2027"), asked)
    }

    @Test
    fun anEmptyFolderSaysHowToFillIt() {
        var adds = 0
        compose.setContent {
            RelaisTheme(darkTheme = false) { FolderList(emptyList(), onOpen = {}, onRemove = {}, onAddReports = { adds++ }) }
        }
        compose.onNodeWithText("No reports in this folder yet. Tap Add reports, or open a report's menu and tap Add to folder.").assertIsDisplayed()
        compose.onNodeWithText("Add reports").performClick()
        assertEquals(1, adds)
    }
}
