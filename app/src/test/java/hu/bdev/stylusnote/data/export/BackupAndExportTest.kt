package hu.bdev.stylusnote.data.export

import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.BookmarkEntity
import hu.bdev.stylusnote.data.local.ChapterEntity
import hu.bdev.stylusnote.data.local.FolderEntity
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.NotebookTagCrossRef
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.PageTextFtsEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.data.local.TagEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.CoverLabelLayout
import hu.bdev.stylusnote.domain.model.CoverTexture
import hu.bdev.stylusnote.domain.model.CoverTypography
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupAndExportTest {

    private lateinit var sourceFilesDir: File
    private lateinit var targetFilesDir: File

    @Before
    fun setUp() {
        sourceFilesDir = Files.createTempDirectory("snot_backup_src_").toFile()
        targetFilesDir = Files.createTempDirectory("snot_backup_dst_").toFile()
    }

    @After
    fun tearDown() {
        sourceFilesDir.deleteRecursively()
        targetFilesDir.deleteRecursively()
    }

    @Test
    fun backupZipRoundTripsManifestAndStrokeJournalFilesWithoutApiKeys() = runBlocking {
        val pageId = UUID.randomUUID().toString()
        val nbId = UUID.randomUUID().toString()
        val folderId = UUID.randomUUID().toString()
        val strokeId = UUID.randomUUID().toString()

        val originalStroke = PersistedStrokeRecord(
            id = strokeId,
            layer = StrokeLayerType.INK,
            zOrder = 1_000_000_001L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 3.2f,
                colorArgb = 0xFF0B3EA8.toInt(),
                pressureCurve = PressureCurve.SOFT
            ),
            canonicalColorArgb = 0xFF0B3EA8.toInt(),
            sizePt = 3.2f,
            inkEncodedInputs = null,
            samples = listOf(
                StylusPointSample(20f, 30f, 0L, 0.4f, 0.1f, 0f, false),
                StylusPointSample(120f, 90f, 18L, 0.8f, 0.2f, 0f, false)
            ),
            bounds = DocRect(20f, 30f, 120f, 90f)
        )

        val journal = StrokeJournal(
            pageDirectory = File(sourceFilesDir, "pages/$pageId"),
            fsyncDebounceMs = 0L
        )
        journal.openAndRecover()
        journal.appendAddStrokes(listOf(originalStroke))
        journal.compactNow()
        journal.closeBlocking(compactBeforeClose = false)

        // Create a sensitive API-key DataStore file that MUST NEVER enter the zip backup.
        val datastoreDir = File(sourceFilesDir, "datastore").apply { mkdirs() }
        File(datastoreDir, "ai_keys.preferences_pb").writeText("SECRET_GEMINI_API_KEY")

        val manifest = BackupManifest(
            createdAtEpochMs = 1700000000000L,
            folders = listOf(
                FolderEntity(folderId, null, "Egyetem", 0xFF1A56DB.toInt(), 0, 100L, 200L)
            ),
            notebooks = listOf(
                NotebookEntity(
                    id = nbId,
                    folderId = folderId,
                    title = "Analízis II",
                    cover = NotebookCover(
                        texture = CoverTexture.BLUEPRINT,
                        primaryColorArgb = 0xFF005F73.toInt(),
                        spineColorArgb = 0xFF0A2E36.toInt(),
                        labelLayout = CoverLabelLayout.CENTER_PLAQUE,
                        typography = CoverTypography.MONOSPACE,
                        subtitle = "2026"
                    ),
                    defaultCanvasMode = CanvasMode.FIXED_PAGE,
                    defaultPageSizePreset = PageSizePreset.A4_PORTRAIT,
                    defaultTemplateType = PaperTemplateType.GRID_5MM,
                    ocrLanguage = OcrLanguageTag.HUNGARIAN,
                    isFavorite = true,
                    lastOpenedPageId = pageId,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 300L
                )
            ),
            chapters = listOf(ChapterEntity("ch-1", nbId, "Integrálszámítás", 0, 100L)),
            pages = listOf(PageEntity(pageId, nbId, "ch-1", 0, createdAtEpochMs = 100L, updatedAtEpochMs = 300L)),
            tags = listOf(TagEntity("tag-1", "Matek", 0xFF1E88E5.toInt())),
            tagLinks = listOf(NotebookTagCrossRef(nbId, "tag-1")),
            bookmarks = listOf(BookmarkEntity("bm-1", nbId, pageId, "Fontos tétel", createdAtEpochMs = 150L)),
            ftsEntries = listOf(PageTextFtsEntity(pageId, nbId, "Tükörfúrógép", "tukorfurogep"))
        )

        val srcBackup = BackupManager(filesDir = sourceFilesDir)
        val zipBytes = ByteArrayOutputStream().use { out ->
            srcBackup.exportBackupToStream(manifest, out)
            out.toByteArray()
        }

        assertFalse(
            "Sensitive API key bytes must never be packaged in backup zip",
            String(zipBytes, Charsets.UTF_8).contains("SECRET_GEMINI_API_KEY")
        )

        val dstBackup = BackupManager(filesDir = targetFilesDir)
        val restored = dstBackup.importBackupFromStream(ByteArrayInputStream(zipBytes))
        assertEquals(manifest, restored.manifest)

        val restoredJournal = StrokeJournal(
            pageDirectory = File(targetFilesDir, "pages/$pageId"),
            fsyncDebounceMs = 0L
        )
        val loaded = restoredJournal.openAndRecover()
        restoredJournal.closeBlocking(compactBeforeClose = false)

        assertEquals(1, loaded.strokes.size)
        assertEquals(originalStroke, loaded.strokes.first())
    }

    @Test
    fun backupAllowlistRejectsTraversalAndSecretsAndSkipsDisallowedZipEntries() = runBlocking {
        assertTrue(BackupManager.isAllowedBackupEntryPath("manifest.bin"))
        assertTrue(BackupManager.isAllowedBackupEntryPath("pages/p1/page.snote"))
        assertTrue(BackupManager.isAllowedBackupEntryPath("pdfs/doc.pdf"))
        assertTrue(BackupManager.isAllowedBackupEntryPath("covers/img.jpg"))

        assertFalse(BackupManager.isAllowedBackupEntryPath("datastore/ai_keys.preferences_pb"))
        assertFalse(BackupManager.isAllowedBackupEntryPath("keys/gemini_api_key.bin"))
        assertFalse(BackupManager.isAllowedBackupEntryPath("../outside/evil.txt"))

        val mgr = BackupManager(filesDir = targetFilesDir)
        val validManifestBytes = mgr.encodeManifest(BackupManifest())
        val craftedZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry(BackupManager.MANIFEST_ENTRY_NAME))
                zip.write(validManifestBytes)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("datastore/ai_keys.preferences_pb"))
                zip.write("DO_NOT_RESTORE".toByteArray())
                zip.closeEntry()
            }
            baos.toByteArray()
        }

        mgr.importBackupFromStream(ByteArrayInputStream(craftedZip))
        assertFalse(File(targetFilesDir, "datastore/ai_keys.preferences_pb").exists())
    }

    @Test
    fun analyticalOutlineExtractorBuildsClosedPolygonForVectorPdfExport() {
        val samples = listOf(
            StylusPointSample(10f, 50f, 0L, 0.3f, 0f, 0f, false),
            StylusPointSample(80f, 50f, 15L, 0.9f, 0f, 0f, false),
            StylusPointSample(160f, 95f, 30L, 0.6f, 0f, 0f, false)
        )
        val loops = StrokeOutlineExtractor.buildAnalyticalStrokeOutlineLoops(
            samples = samples,
            baseSizePt = 4.0f,
            toolType = BrushToolType.FOUNTAIN_PEN
        )
        assertEquals(1, loops.size)
        val polygon = loops.first()
        assertTrue("Expected closed polygon with >= 6 vertices", polygon.size >= 6)
        assertTrue(polygon.all { it.xPt.isFinite() && it.yPt.isFinite() })
    }
}

