package hu.bdev.stylusnote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.IntSize
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.data.export.CloudProviderType
import hu.bdev.stylusnote.data.export.CloudSyncPreferencesStore
import hu.bdev.stylusnote.data.export.CloudSyncSettings
import hu.bdev.stylusnote.data.export.TRASH_RETENTION_MS
import hu.bdev.stylusnote.data.export.TrashedNotebookRecord
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.CategoryPdfSyncRecordEntity
import hu.bdev.stylusnote.data.local.FolderEntity
import hu.bdev.stylusnote.data.local.PendingDriveFolderDeletionEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.ui.components.RadialPieMenuOverlay
import hu.bdev.stylusnote.ui.shelf.CloudTrashRestoreDialog
import hu.bdev.stylusnote.ui.shelf.DeleteCategoryConfirmDialog
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XvPhaseValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migration4To5PreservesFoldersAndAddsDrivePdfSyncAndDeletionTables() = runBlocking {
        val dbName = "migration-v4-v5-xvphase.db"
        context.deleteDatabase(dbName)
        migrationHelper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                INSERT INTO folders (id, name, accentColorArgb, sortOrder, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('fld-v4', 'Analízis', -13936540, 0, 1000, 2000)
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AppDatabase.MIGRATION_4_5
        )
        migrated.query(
            "SELECT name, drivePdfFolderId, drivePdfFolderName FROM folders WHERE id = 'fld-v4'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Analízis", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            roomDb.folderDao().updateFolderDrivePdfMapping("fld-v4", "drv-fld-1", "Analízis Drive", 3000L)
            val mapped = roomDb.folderDao().getFoldersWithDrivePdfMapping()
            assertEquals(1, mapped.size)
            assertEquals("drv-fld-1", mapped[0].drivePdfFolderId)

            roomDb.categoryPdfSyncDao().upsert(
                CategoryPdfSyncRecordEntity(
                    notebookId = "nb-pdf-1",
                    folderId = "fld-v4",
                    driveFolderId = "drv-fld-1",
                    remoteDriveFileId = "drv-file-9",
                    remoteFileName = "Analizis_2026-11-27.pdf",
                    lastExportedUpdatedAtMs = 3000L,
                    lastSyncedEpochMs = 4000L
                )
            )
            assertNotNull(roomDb.categoryPdfSyncDao().getByNotebookId("nb-pdf-1"))

            roomDb.pendingDriveFolderDeletionDao().upsert(
                PendingDriveFolderDeletionEntity("drv-del-1", "Régi Mappa", 5000L)
            )
            assertEquals(1, roomDb.pendingDriveFolderDeletionDao().getAll().size)
            roomDb.pendingDriveFolderDeletionDao().deleteById("drv-del-1")
            assertTrue(roomDb.pendingDriveFolderDeletionDao().getAll().isEmpty())
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cloudTrashRestoreDialogListsDeletedNotebookTriggersRestoreAndDismissesOnEscAndBackdrop() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefsStore = CloudSyncPreferencesStore(context)
        val now = System.currentTimeMillis()
        val sampleEntry = TrashedNotebookRecord(
            notebookId = "nb-trash-ui",
            title = "Kémia ZH",
            folderId = null,
            pageCount = 4,
            deletedAtEpochMs = now - 3_600_000L,
            expiresAtEpochMs = now - 3_600_000L + TRASH_RETENTION_MS,
            manifestSnapshotBase64 = "AAAA"
        )
        var showDialog by mutableStateOf(true)
        var restoredTitle by mutableStateOf<String?>(null)

        try {
            composeRule.setContent {
                StylusNoteTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showDialog) {
                            CloudTrashRestoreDialog(
                                database = db,
                                settings = CloudSyncSettings(
                                    providerType = CloudProviderType.GOOGLE_DRIVE,
                                    googleDriveAccountEmail = "teszt@example.com"
                                ),
                                prefsStore = prefsStore,
                                onRestoredNotebook = { title -> restoredTitle = title },
                                onDismiss = { showDialog = false },
                                preloadedEntriesForPreview = listOf(sampleEntry),
                                onRestoreActionOverride = {}
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("cloud_trash_item_nb-trash-ui").assertIsDisplayed()
            composeRule.onNodeWithTag("cloud_trash_restore_nb-trash-ui").performClick()
            composeRule.waitForIdle()
            assertEquals("Kémia ZH", restoredTitle)

            // Backdrop click dismisses cleanly
            composeRule.onNodeWithTag("cloud_trash_backdrop").performTouchInput { click(Offset(8f, 8f)) }
            composeRule.waitForIdle()
            assertFalse(showDialog)

            // Reopen and press ESC (Rule 11)
            composeRule.runOnIdle { showDialog = true }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("cloud_trash_backdrop").performKeyInput { pressKey(Key.Escape) }
            composeRule.waitForIdle()
            assertFalse(showDialog)
        } finally {
            db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun deleteCategoryConfirmDialogDisplaysDriveWarningAndDismissesOnEscAndBackdrop() {
        val folder = FolderEntity(
            id = "fld-warn",
            name = "Matek",
            drivePdfFolderId = "drv-matek",
            drivePdfFolderName = "Matek PDF",
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 200L
        )
        var showDialog by mutableStateOf(true)
        var confirmed by mutableStateOf(false)

        composeRule.setContent {
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showDialog) {
                        DeleteCategoryConfirmDialog(
                            folder = folder,
                            onConfirmDelete = {
                                confirmed = true
                                showDialog = false
                            },
                            onDismiss = { showDialog = false }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("delete_category_drive_warning").assertIsDisplayed()
        composeRule.onNodeWithTag("delete_category_confirm").performClick()
        composeRule.waitForIdle()
        assertTrue(confirmed)
        assertFalse(showDialog)

        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete_category_backdrop").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.waitForIdle()
        assertFalse(showDialog)

        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete_category_backdrop").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()
        assertFalse(showDialog)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun radialPieMenuOverlaySelectsToolsColorsAndDismissesOnEscAndBackdrop() {
        var showPie by mutableStateOf(true)
        var selectedTool by mutableStateOf<BrushToolType?>(null)
        var selectedColor by mutableStateOf<Int?>(null)
        var sizeCycled by mutableStateOf(false)

        composeRule.setContent {
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showPie) {
                        RadialPieMenuOverlay(
                            anchorScreenPx = Offset(500f, 400f),
                            viewportSize = IntSize(1200, 900),
                            brushConfig = BrushConfig(),
                            isOledMode = false,
                            commitTick = 0L,
                            onSelectTool = { tool -> selectedTool = tool },
                            onSelectInkColor = { color -> selectedColor = color },
                            onCyclePenSize = { sizeCycled = true },
                            onDismiss = { showPie = false }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Slice 1 -> Highlighter
        composeRule.onNodeWithTag("radial_pie_menu_ring").assertIsDisplayed()
        composeRule.onNodeWithTag("pie_slice_1").performClick()
        composeRule.waitForIdle()
        assertEquals(BrushToolType.HIGHLIGHTER, selectedTool)
        assertFalse(showPie)

        // Slice 6 -> Red ink
        composeRule.runOnIdle { showPie = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pie_slice_6").performClick()
        composeRule.waitForIdle()
        assertEquals(0xFFDC2626.toInt(), selectedColor)
        assertFalse(showPie)

        // Slice 3 -> Cycle width
        composeRule.runOnIdle { showPie = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pie_slice_3").performClick()
        composeRule.waitForIdle()
        assertTrue(sizeCycled)
        assertFalse(showPie)

        // Center cancel hub, backdrop & ESC dismiss cleanly
        composeRule.runOnIdle { showPie = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("radial_pie_menu_center_cancel").performClick()
        composeRule.waitForIdle()
        assertFalse(showPie)

        composeRule.runOnIdle { showPie = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("radial_pie_menu_backdrop").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.waitForIdle()
        assertFalse(showPie)

        composeRule.runOnIdle { showPie = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("radial_pie_menu_backdrop").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()
        assertFalse(showPie)
    }
}
