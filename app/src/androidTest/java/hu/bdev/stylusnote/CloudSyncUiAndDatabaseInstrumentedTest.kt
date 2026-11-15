package hu.bdev.stylusnote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.data.export.BackupManager
import hu.bdev.stylusnote.data.export.CloudSyncEngine
import hu.bdev.stylusnote.data.export.CloudSyncRemoteClient
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import hu.bdev.stylusnote.ui.shelf.CloudBackupDialog
import hu.bdev.stylusnote.ui.shelf.CloudSyncStatusBadgeButton
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class CloudSyncUiAndDatabaseInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roomV3DeleteWithTombstoneAndCloudSyncEngineEndToEnd() = runBlocking {
        val dirA = File(context.cacheDir, "cloud_e2e_a_${System.nanoTime()}").apply { mkdirs() }
        val dirB = File(context.cacheDir, "cloud_e2e_b_${System.nanoTime()}").apply { mkdirs() }
        val dbA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        try {
            val remote = InMemoryRemote()
            val nb = NotebookEntity(
                id = "nb-e2e",
                title = "Fizika jegyzet",
                lastOpenedPageId = "p-e2e-1",
                createdAtEpochMs = 100L,
                updatedAtEpochMs = 200L
            )
            val page = PageEntity(
                id = "p-e2e-1",
                notebookId = nb.id,
                pageIndexInNotebook = 0,
                title = "Kinematika",
                createdAtEpochMs = 100L,
                updatedAtEpochMs = 200L
            )
            dbA.notebookDao().upsertNotebook(nb)
            dbA.pageDao().upsertPage(page)

            val pageFileA = File(dirA, "pages/p-e2e-1").apply { mkdirs() }
            val journal = StrokeJournal(pageDirectory = pageFileA, fsyncDebounceMs = 0L)
            journal.openAndRecover()
            journal.compactNow()
            journal.closeBlocking(compactBeforeClose = false)

            val engineA = CloudSyncEngine(dirA, dbA, BackupManager(dirA))
            val engineB = CloudSyncEngine(dirB, dbB, BackupManager(dirB))

            val firstPush = engineA.syncNow(remote, nowEpochMs = 300L)
            assertEquals(1, firstPush.totalNotebooks)

            val restoreOnB = engineB.syncNow(remote, nowEpochMs = 400L)
            assertEquals(1, restoreOnB.totalNotebooks)
            val restoredPages = dbB.pageDao().observePagesForNotebook(nb.id).first()
            assertEquals(listOf("Kinematika"), restoredPages.map { it.title })

            // Delete page on DB A with tombstone, sync to remote, then sync on DB B.
            dbA.deleteWithTombstone(SyncTombstoneEntity.TYPE_PAGE, page.id, deletedAtMs = 500L)
            val deletePush = engineA.syncNow(remote, nowEpochMs = 550L)
            assertEquals(1, deletePush.deletedRemoteAssets)

            val pullDeleteOnB = engineB.syncNow(remote, nowEpochMs = 600L)
            assertEquals(1, pullDeleteOnB.deletedLocalPages)
            assertTrue(dbB.pageDao().observePagesForNotebook(nb.id).first().isEmpty())
            assertFalse(File(dirB, "pages/p-e2e-1").exists())
        } finally {
            dbA.close()
            dbB.close()
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun cloudBadgeLongPressOpensBackupDialogAndCloseDismissesIt() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            composeRule.setContent {
                var open by remember { mutableStateOf(false) }
                StylusNoteTheme {
                    CloudSyncStatusBadgeButton(
                        onOpenSettingsDialog = { open = true }
                    )
                    if (open) {
                        CloudBackupDialog(
                            database = db,
                            onDismiss = { open = false }
                        )
                    }
                }
            }

            composeRule.onNodeWithContentDescription(
                "Felhőmentés nincs beállítva (hosszan: beállítások)"
            ).performTouchInput { longClick() }

            composeRule.onNodeWithText("Felhőszinkron és Biztonsági Mentés").assertIsDisplayed()
            composeRule.onNodeWithText("Okos automata").assertIsDisplayed()
            composeRule.onNodeWithText("Export (.zip)").assertIsDisplayed()

            composeRule.onNodeWithContentDescription("Bezárás (ESC)").performClick()
            composeRule.onNodeWithText("Felhőszinkron és Biztonsági Mentés").assertDoesNotExist()
        } finally {
            db.close()
        }
    }

    private class InMemoryRemote : CloudSyncRemoteClient {
        private val map = ConcurrentHashMap<String, ByteArray>()
        override suspend fun readFile(remoteName: String): ByteArray? = map[remoteName]?.copyOf()
        override suspend fun writeFile(remoteName: String, bytes: ByteArray, contentType: String) {
            map[remoteName] = bytes.copyOf()
        }
        override suspend fun deleteFile(remoteName: String) {
            map.remove(remoteName)
        }
        override suspend fun listFiles(): List<String> = map.keys.sorted()
    }
}
