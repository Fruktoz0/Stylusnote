package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.hardware.StylusButtonGestureAction
import hu.bdev.stylusnote.core.hardware.StylusButtonGestureStateMachine
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.BackupManifest
import hu.bdev.stylusnote.data.export.BackupManager
import hu.bdev.stylusnote.data.export.CategoryPdfSyncEngine
import hu.bdev.stylusnote.data.export.CloudSyncEngine
import hu.bdev.stylusnote.data.export.CloudSyncRemoteClient
import hu.bdev.stylusnote.data.export.ManifestMergeResult
import hu.bdev.stylusnote.data.export.SyncManifestStore
import hu.bdev.stylusnote.data.export.TRASH_RETENTION_MS
import hu.bdev.stylusnote.data.local.CloudSyncedAssetEntity
import hu.bdev.stylusnote.data.local.FolderEntity
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.ui.components.StylusPieMenuTriggerMode
import hu.bdev.stylusnote.ui.components.computeClampedPieCenterPx
import hu.bdev.stylusnote.ui.components.resolvePieMenuSliceIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class XvPhaseValidationUnitTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("xv_unit_test_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private class FakeRemoteClient : CloudSyncRemoteClient {
        val blobs = ConcurrentHashMap<String, ByteArray>()
        override suspend fun readFile(remoteName: String): ByteArray? = blobs[remoteName]?.copyOf()
        override suspend fun writeFile(remoteName: String, bytes: ByteArray, contentType: String) {
            blobs[remoteName] = bytes.copyOf()
        }
        override suspend fun deleteFile(remoteName: String) {
            blobs.remove(remoteName)
        }
        override suspend fun listFiles(): List<String> = blobs.keys.sorted()
    }

    private class MemoryManifestStore(
        private val filesDir: File
    ) : SyncManifestStore {
        var manifest: BackupManifest = BackupManifest()
        private val synced = LinkedHashMap<String, CloudSyncedAssetEntity>()

        override suspend fun collectManifest(): BackupManifest = manifest
        override suspend fun mergeRemoteManifest(remote: BackupManifest): ManifestMergeResult {
            val res = BackupManager.mergeManifests(manifest, remote)
            manifest = res.mergedManifest
            for (pid in res.deletedPageIds) {
                File(filesDir, "pages/$pid").deleteRecursively()
                synced.remove("pages/$pid")
            }
            return res
        }
        override suspend fun getAllSyncedAssets(): List<CloudSyncedAssetEntity> = synced.values.toList()
        override suspend fun getSyncedAsset(assetKey: String): CloudSyncedAssetEntity? = synced[assetKey]
        override suspend fun upsertSyncedAsset(asset: CloudSyncedAssetEntity) { synced[asset.assetPath] = asset }
        override suspend fun deleteSyncedAsset(assetKey: String) { synced.remove(assetKey) }
        override suspend fun isUntouchedStarterWorkspace(filesDir: File): Boolean = false
        override suspend fun clearStarterWorkspaceWithoutTombstone(filesDir: File) { manifest = BackupManifest() }
        override suspend fun restoreNotebookFromSnapshot(snapshot: BackupManifest, restoredAtEpochMs: Long) {
            val nbIds = snapshot.notebooks.mapTo(HashSet()) { it.id }
            val pIds = snapshot.pages.mapTo(HashSet()) { it.id }
            manifest = manifest.copy(
                notebooks = manifest.notebooks.filterNot { it.id in nbIds } +
                    snapshot.notebooks.map { it.copy(updatedAtEpochMs = restoredAtEpochMs) },
                pages = manifest.pages.filterNot { it.id in pIds } +
                    snapshot.pages.map { it.copy(updatedAtEpochMs = restoredAtEpochMs) },
                tombstones = manifest.tombstones.filterNot {
                    (it.entityType == SyncTombstoneEntity.TYPE_NOTEBOOK && it.entityId in nbIds) ||
                        (it.entityType == SyncTombstoneEntity.TYPE_PAGE && it.entityId in pIds)
                }
            )
        }
    }

    @Test
    fun cloudTrashPreservesDeletedNotebookFor7DaysRestoresCleanlyAndPurgesAfterExpiry() = runBlocking {
        val remote = FakeRemoteClient()
        val store = MemoryManifestStore(tempDir)
        val engine = CloudSyncEngine(tempDir, store, BackupManager(tempDir))

        val pageDir = File(tempDir, "pages/pg-trash-1").apply { mkdirs() }
        val journal = StrokeJournal(pageDir)
        journal.openAndRecover()
        journal.appendAddStrokes(
            listOf(
                PersistedStrokeRecord(
                    id = "s-trash-1",
                    layer = StrokeLayerType.INK,
                    zOrder = 10L,
                    brushConfig = BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN, sizePt = 2f),
                    canonicalColorArgb = 0xFF14171C.toInt(),
                    sizePt = 2f,
                    inkEncodedInputs = ByteArray(0),
                    samples = listOf(
                        StylusPointSample(10f, 10f, 0L, 0.7f, 0f, 0f, false),
                        StylusPointSample(40f, 30f, 15L, 0.7f, 0f, 0f, false)
                    ),
                    bounds = DocRect(10f, 10f, 40f, 30f)
                )
            )
        )
        journal.closeBlocking(compactBeforeClose = false)

        val nb = NotebookEntity(
            id = "nb-trash-1",
            title = "Fizika Gyakorlat",
            createdAtEpochMs = 1_000_000L,
            updatedAtEpochMs = 1_000_000L
        )
        val pg = PageEntity(
            id = "pg-trash-1",
            notebookId = nb.id,
            chapterId = null,
            pageIndexInNotebook = 0,
            createdAtEpochMs = 1_000_000L,
            updatedAtEpochMs = 1_000_000L
        )
        store.manifest = BackupManifest(
            createdAtEpochMs = 1_000_000L,
            notebooks = listOf(nb),
            pages = listOf(pg)
        )

        val initial = engine.syncNow(remote, nowEpochMs = 1_000_500L)
        assertEquals(1, initial.uploadedAssets)
        val pageBlobKey = CloudSyncEngine.remoteBlobNameForAssetKey("pages/pg-trash-1")
        assertTrue(remote.blobs.containsKey(pageBlobKey))

        // Delete notebook locally & tombstone it -> enters 7-day cloud trash instead of deleting blob
        val deletedAt = 2_000_000L
        pageDir.deleteRecursively()
        store.manifest = store.manifest.copy(
            notebooks = emptyList(),
            pages = emptyList(),
            tombstones = listOf(
                SyncTombstoneEntity(
                    compositeKey = SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id),
                    entityType = SyncTombstoneEntity.TYPE_NOTEBOOK,
                    entityId = nb.id,
                    deletedAtEpochMs = deletedAt
                )
            )
        )
        val deleteSync = engine.syncNow(remote, nowEpochMs = deletedAt + 100L)
        assertEquals(0, deleteSync.deletedRemoteAssets)
        assertTrue("Deleted notebook blob must stay protected on Drive for 7 days", remote.blobs.containsKey(pageBlobKey))

        val day5 = deletedAt + 5L * 24L * 60L * 60L * 1000L
        engine.syncNow(remote, nowEpochMs = day5)
        val trashed = engine.listTrashedNotebooks(remote, nowEpochMs = day5)
        assertEquals(1, trashed.size)
        assertEquals("Fizika Gyakorlat", trashed[0].title)
        assertEquals(2, trashed[0].remainingDaysRoundedUp(day5))

        // Restore before 7-day expiry brings notebook + local page stroke back
        val restored = engine.restoreTrashedNotebook(remote, nb.id, nowEpochMs = day5 + 1_000L)
        assertNotNull(restored)
        assertEquals(listOf(nb.id), store.manifest.notebooks.map { it.id })
        assertTrue(engine.listTrashedNotebooks(remote, nowEpochMs = day5 + 2_000L).isEmpty())
        assertTrue(File(tempDir, "pages/pg-trash-1").exists())

        // Delete again and advance past 7 days -> purged cleanly from both trash ledger and remote storage
        val secondDeleteAt = day5 + 10_000L
        File(tempDir, "pages/pg-trash-1").deleteRecursively()
        store.manifest = store.manifest.copy(
            notebooks = emptyList(),
            pages = emptyList(),
            tombstones = listOf(
                SyncTombstoneEntity(
                    compositeKey = SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id),
                    entityType = SyncTombstoneEntity.TYPE_NOTEBOOK,
                    entityId = nb.id,
                    deletedAtEpochMs = secondDeleteAt
                )
            )
        )
        engine.syncNow(remote, nowEpochMs = secondDeleteAt + 100L)
        assertTrue(remote.blobs.containsKey(pageBlobKey))

        val afterSevenDays = secondDeleteAt + TRASH_RETENTION_MS + 60_000L
        val purgeSync = engine.syncNow(remote, nowEpochMs = afterSevenDays)
        assertTrue(purgeSync.deletedRemoteAssets >= 1)
        assertFalse(remote.blobs.containsKey(pageBlobKey))
        assertTrue(engine.listTrashedNotebooks(remote, nowEpochMs = afterSevenDays).isEmpty())
    }

    @Test
    fun datedPdfFileNameFormatsDateAndSanitizesTitleWhileBackupManifestV4RoundTripsDriveMapping() {
        val fixedTs = 1_795_776_000_000L // deterministic calendar instant
        val expectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(fixedTs))
        val fileName = CategoryPdfSyncEngine.buildDatedPdfFileName("Analízis: 2/B * jegyzet", fixedTs)
        assertEquals("Analízis_2_B_jegyzet_${expectedDate}.pdf", fileName)
        assertEquals("Jegyzet_${expectedDate}.pdf", CategoryPdfSyncEngine.buildDatedPdfFileName("   ", fixedTs))

        val manager = BackupManager(tempDir)
        val manifest = BackupManifest(
            formatVersion = BackupManifest.MANIFEST_VERSION_V4,
            folders = listOf(
                FolderEntity(
                    id = "fld-uni",
                    name = "Egyetem",
                    drivePdfFolderId = "drive-fld-42",
                    drivePdfFolderName = "Egyetem PDF Mentések",
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
            )
        )
        val decoded = manager.decodeManifest(manager.encodeManifest(manifest))
        assertEquals(1, decoded.folders.size)
        assertEquals("drive-fld-42", decoded.folders[0].drivePdfFolderId)
        assertEquals("Egyetem PDF Mentések", decoded.folders[0].drivePdfFolderName)
    }

    @Test
    fun stylusButtonGestureStateMachineHandlesLongPressDoublePressAndOffModes() {
        val sm = StylusButtonGestureStateMachine(longPressThresholdMs = 320L, doublePressWindowMs = 340L)

        // LONG_PRESS short tap (< 320 ms) -> toggles eraser cleanly
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonDown(1000L, StylusPieMenuTriggerMode.LONG_PRESS))
        assertEquals(StylusButtonGestureAction.TOGGLE_ERASER, sm.onButtonUp(1120L, StylusPieMenuTriggerMode.LONG_PRESS))

        // LONG_PRESS held >= 320 ms -> timer opens Pie Menu, UP commits active slice
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonDown(2000L, StylusPieMenuTriggerMode.LONG_PRESS))
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonDown(2005L, StylusPieMenuTriggerMode.LONG_PRESS)) // dup ignored
        assertEquals(StylusButtonGestureAction.OPEN_PIE_MENU, sm.onTimerElapsed(2330L, StylusPieMenuTriggerMode.LONG_PRESS))
        assertEquals(StylusButtonGestureAction.COMMIT_PIE_MENU_ON_RELEASE, sm.onButtonUp(2550L, StylusPieMenuTriggerMode.LONG_PRESS))

        // DOUBLE_PRESS 1 click waits window then toggles eraser
        sm.reset()
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonDown(3000L, StylusPieMenuTriggerMode.DOUBLE_PRESS))
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonUp(3060L, StylusPieMenuTriggerMode.DOUBLE_PRESS))
        assertEquals(StylusButtonGestureAction.TOGGLE_ERASER, sm.onTimerElapsed(3410L, StylusPieMenuTriggerMode.DOUBLE_PRESS))

        // DOUBLE_PRESS 2 quick clicks -> opens Pie Menu immediately on 2nd DOWN without eraser flicker
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonDown(4000L, StylusPieMenuTriggerMode.DOUBLE_PRESS))
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonUp(4050L, StylusPieMenuTriggerMode.DOUBLE_PRESS))
        assertEquals(StylusButtonGestureAction.OPEN_PIE_MENU, sm.onButtonDown(4200L, StylusPieMenuTriggerMode.DOUBLE_PRESS))
        assertEquals(StylusButtonGestureAction.NONE, sm.onButtonUp(4240L, StylusPieMenuTriggerMode.DOUBLE_PRESS))

        // OFF mode toggles immediately on DOWN
        sm.reset()
        assertEquals(StylusButtonGestureAction.TOGGLE_ERASER, sm.onButtonDown(5000L, StylusPieMenuTriggerMode.OFF))
    }

    @Test
    fun radialPieMenuGeometryResolvesAll8SectorsDeadzoneAndViewportClamping() {
        // Center deadzone & beyond max radius -> null
        assertNull(resolvePieMenuSliceIndex(10f, -15f, innerDeadzonePx = 36f))
        assertNull(resolvePieMenuSliceIndex(400f, 0f, innerDeadzonePx = 36f, outerMaxRadiusPx = 260f))

        // All 8 sectors clockwise from 12 o'clock (-90 deg)
        for (expectedSector in 0 until 8) {
            val angleRad = Math.toRadians(-90.0 + expectedSector * 45.0)
            val dx = (110.0 * cos(angleRad)).toFloat()
            val dy = (110.0 * sin(angleRad)).toFloat()
            assertEquals("Sector $expectedSector mismatch", expectedSector, resolvePieMenuSliceIndex(dx, dy))
        }

        // Clamping keeps 128 px outer ring comfortably inside 1920x1200 viewport
        val clampedBottomRight = computeClampedPieCenterPx(
            anchorXPx = 1910f,
            anchorYPx = 1190f,
            outerRadiusPx = 128f,
            viewportWidthPx = 1920,
            viewportHeightPx = 1200,
            marginPx = 16f
        )
        assertTrue(clampedBottomRight.x + 128f <= 1920f - 16f + 0.01f)
        assertTrue(clampedBottomRight.y + 128f <= 1200f - 16f + 0.01f)
    }
}
