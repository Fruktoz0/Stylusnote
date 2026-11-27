package hu.bdev.stylusnote.data.export

import android.content.Context
import android.net.Uri
import hu.bdev.stylusnote.data.local.AccentNormalizer
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.BookmarkEntity
import hu.bdev.stylusnote.data.local.ChapterEntity
import hu.bdev.stylusnote.data.local.FolderEntity
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.NotebookTagCrossRef
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.PageFileFormat
import hu.bdev.stylusnote.data.local.PageTextFtsEntity
import hu.bdev.stylusnote.data.local.StylusNoteDatabaseConverters
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import hu.bdev.stylusnote.data.local.TagEntity
import hu.bdev.stylusnote.domain.model.NotebookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure binary archive manifest packaged at `manifest.bin` inside every StylusNote `.zip` backup.
 * Explicitly omits any API keys, DataStore preferences, or Keystore metadata (Section 8).
 */
data class BackupManifest(
    val formatVersion: Int = CURRENT_MANIFEST_VERSION,
    val appVersion: String = DEFAULT_APP_VERSION,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val folders: List<FolderEntity> = emptyList(),
    val notebooks: List<NotebookEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val pages: List<PageEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tagLinks: List<NotebookTagCrossRef> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val ftsEntries: List<PageTextFtsEntity> = emptyList(),
    val tombstones: List<SyncTombstoneEntity> = emptyList()
) {
    companion object {
        const val MANIFEST_VERSION_V1: Int = 1
        const val MANIFEST_VERSION_V2: Int = 2
        const val MANIFEST_VERSION_V3: Int = 3
        const val MANIFEST_VERSION_V4: Int = 4
        const val CURRENT_MANIFEST_VERSION: Int = MANIFEST_VERSION_V4
        const val DEFAULT_APP_VERSION: String = "0.1.0-alpha"
    }
}

data class ManifestMergeResult(
    val mergedManifest: BackupManifest,
    val dirtyRemotePageIds: Set<String>,
    val deletedPageIds: Set<String>
)

data class BackupExportSummary(
    val manifest: BackupManifest,
    val packagedAssetFileCount: Int
)

data class BackupRestoreSummary(
    val manifest: BackupManifest,
    val restoredAssetFileCount: Int
)

/**
 * Binary ZIP backup & restore coordinator supporting local storage, Android Storage Access
 * Framework (Drive / cloud providers via `ACTION_CREATE_DOCUMENT` / `OPEN_DOCUMENT`), and
 * OkHttp WebDAV endpoints (Section 8).
 *
 * Security boundary: strictly enforces [isAllowedBackupEntryPath] so API keys, encrypted
 * Keystore blobs, and `datastore/` files are never exported into or accepted from archives.
 */
class BackupManager(
    private val filesDir: File,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    fun encodeManifest(manifest: BackupManifest): ByteArray {
        val conv = StylusNoteDatabaseConverters()
        val baos = ByteArrayOutputStream(1024)
        val out = DataOutputStream(baos)

        out.write(MANIFEST_MAGIC)
        out.writeInt(manifest.formatVersion)
        writeUtf(out, manifest.appVersion)
        out.writeLong(manifest.createdAtEpochMs)

        out.writeInt(manifest.folders.size)
        for (f in manifest.folders) {
            writeUtf(out, f.id)
            writeNullableUtf(out, f.parentId)
            writeUtf(out, f.name)
            out.writeInt(f.accentColorArgb)
            out.writeInt(f.sortOrder)
            if (manifest.formatVersion >= BackupManifest.MANIFEST_VERSION_V4) {
                writeNullableUtf(out, f.drivePdfFolderId)
                writeNullableUtf(out, f.drivePdfFolderName)
            }
            out.writeLong(f.createdAtEpochMs)
            out.writeLong(f.updatedAtEpochMs)
        }

        out.writeInt(manifest.notebooks.size)
        for (nb in manifest.notebooks) {
            writeUtf(out, nb.id)
            writeNullableUtf(out, nb.folderId)
            writeUtf(out, nb.title)
            writeUtf(out, conv.fromCoverTexture(nb.cover.texture))
            out.writeInt(nb.cover.primaryColorArgb)
            out.writeInt(nb.cover.spineColorArgb)
            writeUtf(out, conv.fromCoverLabelLayout(nb.cover.labelLayout))
            writeUtf(out, conv.fromCoverTypography(nb.cover.typography))
            writeNullableUtf(out, nb.cover.customImageRelativePath)
            writeUtf(out, nb.cover.subtitle)
            writeUtf(out, conv.fromCanvasMode(nb.defaultCanvasMode))
            writeUtf(out, conv.fromPageSizePreset(nb.defaultPageSizePreset))
            writeUtf(out, conv.fromPaperTemplateType(nb.defaultTemplateType))
            writeUtf(out, conv.fromOcrLanguageTag(nb.ocrLanguage))
            out.writeBoolean(nb.isFavorite)
            writeNullableUtf(out, nb.lastOpenedPageId)
            if (manifest.formatVersion >= BackupManifest.MANIFEST_VERSION_V3) {
                writeNullableUtf(out, nb.lastViewportPageId)
                writeNullableFloat(out, nb.lastViewportCenterXPt)
                writeNullableFloat(out, nb.lastViewportCenterYPt)
                writeNullableFloat(out, nb.lastViewportZoomFactor)
            }
            out.writeLong(nb.createdAtEpochMs)
            out.writeLong(nb.updatedAtEpochMs)
        }

        out.writeInt(manifest.chapters.size)
        for (ch in manifest.chapters) {
            writeUtf(out, ch.id)
            writeUtf(out, ch.notebookId)
            writeUtf(out, ch.title)
            out.writeInt(ch.sortOrder)
            out.writeLong(ch.createdAtEpochMs)
        }

        out.writeInt(manifest.pages.size)
        for (p in manifest.pages) {
            writeUtf(out, p.id)
            writeUtf(out, p.notebookId)
            writeNullableUtf(out, p.chapterId)
            out.writeInt(p.pageIndexInNotebook)
            if (manifest.formatVersion >= BackupManifest.MANIFEST_VERSION_V2) {
                writeNullableUtf(out, p.title)
            }
            writeUtf(out, conv.fromCanvasMode(p.canvasMode))
            writeUtf(out, conv.fromPageSizePreset(p.pageSizePreset))
            writeUtf(out, conv.fromPaperTemplateType(p.templateType))
            writeNullableUtf(out, p.pdfAssetId)
            out.writeBoolean(p.pdfPageIndex != null)
            if (p.pdfPageIndex != null) out.writeInt(p.pdfPageIndex)
            out.writeBoolean(p.invertPdfInOledMode)
            writeUtf(out, p.snapshotRelativePath)
            writeUtf(out, p.journalRelativePath)
            out.writeBoolean(p.needsOcrSync)
            out.writeLong(p.createdAtEpochMs)
            out.writeLong(p.updatedAtEpochMs)
        }

        out.writeInt(manifest.tags.size)
        for (t in manifest.tags) {
            writeUtf(out, t.id)
            writeUtf(out, t.name)
            out.writeInt(t.colorArgb)
        }

        out.writeInt(manifest.tagLinks.size)
        for (link in manifest.tagLinks) {
            writeUtf(out, link.notebookId)
            writeUtf(out, link.tagId)
        }

        out.writeInt(manifest.bookmarks.size)
        for (bm in manifest.bookmarks) {
            writeUtf(out, bm.id)
            writeUtf(out, bm.notebookId)
            writeUtf(out, bm.pageId)
            writeUtf(out, bm.label)
            out.writeInt(bm.colorArgb)
            out.writeLong(bm.createdAtEpochMs)
        }

        out.writeInt(manifest.ftsEntries.size)
        for (fts in manifest.ftsEntries) {
            writeUtf(out, fts.pageId)
            writeUtf(out, fts.notebookId)
            writeUtf(out, fts.rawText)
            writeUtf(out, fts.asciiFoldedText)
        }

        if (manifest.formatVersion >= BackupManifest.MANIFEST_VERSION_V2) {
            out.writeInt(manifest.tombstones.size)
            for (t in manifest.tombstones) {
                writeUtf(out, t.entityType)
                writeUtf(out, t.entityId)
                out.writeLong(t.deletedAtEpochMs)
            }
        }

        out.flush()
        return baos.toByteArray()
    }

    fun decodeManifest(bytes: ByteArray): BackupManifest {
        val conv = StylusNoteDatabaseConverters()
        val input = DataInputStream(bytes.inputStream())

        val magic = ByteArray(MANIFEST_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MANIFEST_MAGIC)) {
            throw IOException("Érvénytelen backup manifest fejléc")
        }
        val formatVersion = input.readInt()
        val appVersion = readUtf(input)
        val createdAt = input.readLong()

        val folderCount = input.readInt().coerceAtLeast(0)
        val folders = ArrayList<FolderEntity>(folderCount)
        repeat(folderCount) {
            folders.add(
                FolderEntity(
                    id = readUtf(input),
                    parentId = readNullableUtf(input),
                    name = readUtf(input),
                    accentColorArgb = input.readInt(),
                    sortOrder = input.readInt(),
                    drivePdfFolderId = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V4) {
                        readNullableUtf(input)
                    } else {
                        null
                    },
                    drivePdfFolderName = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V4) {
                        readNullableUtf(input)
                    } else {
                        null
                    },
                    createdAtEpochMs = input.readLong(),
                    updatedAtEpochMs = input.readLong()
                )
            )
        }

        val nbCount = input.readInt().coerceAtLeast(0)
        val notebooks = ArrayList<NotebookEntity>(nbCount)
        repeat(nbCount) {
            notebooks.add(
                NotebookEntity(
                    id = readUtf(input),
                    folderId = readNullableUtf(input),
                    title = readUtf(input),
                    cover = NotebookCover(
                        texture = conv.toCoverTexture(readUtf(input)),
                        primaryColorArgb = input.readInt(),
                        spineColorArgb = input.readInt(),
                        labelLayout = conv.toCoverLabelLayout(readUtf(input)),
                        typography = conv.toCoverTypography(readUtf(input)),
                        customImageRelativePath = readNullableUtf(input),
                        subtitle = readUtf(input)
                    ),
                    defaultCanvasMode = conv.toCanvasMode(readUtf(input)),
                    defaultPageSizePreset = conv.toPageSizePreset(readUtf(input)),
                    defaultTemplateType = conv.toPaperTemplateType(readUtf(input)),
                    ocrLanguage = conv.toOcrLanguageTag(readUtf(input)),
                    isFavorite = input.readBoolean(),
                    lastOpenedPageId = readNullableUtf(input),
                    lastViewportPageId = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V3) {
                        readNullableUtf(input)
                    } else {
                        null
                    },
                    lastViewportCenterXPt = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V3) {
                        readNullableFloat(input)
                    } else {
                        null
                    },
                    lastViewportCenterYPt = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V3) {
                        readNullableFloat(input)
                    } else {
                        null
                    },
                    lastViewportZoomFactor = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V3) {
                        readNullableFloat(input)
                    } else {
                        null
                    },
                    createdAtEpochMs = input.readLong(),
                    updatedAtEpochMs = input.readLong()
                )
            )
        }

        val chCount = input.readInt().coerceAtLeast(0)
        val chapters = ArrayList<ChapterEntity>(chCount)
        repeat(chCount) {
            chapters.add(
                ChapterEntity(
                    id = readUtf(input),
                    notebookId = readUtf(input),
                    title = readUtf(input),
                    sortOrder = input.readInt(),
                    createdAtEpochMs = input.readLong()
                )
            )
        }

        val pageCount = input.readInt().coerceAtLeast(0)
        val pages = ArrayList<PageEntity>(pageCount)
        repeat(pageCount) {
            val id = readUtf(input)
            val nbId = readUtf(input)
            val chId = readNullableUtf(input)
            val idx = input.readInt()
            val title = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V2) {
                readNullableUtf(input)
            } else {
                null
            }
            val cMode = conv.toCanvasMode(readUtf(input))
            val pPreset = conv.toPageSizePreset(readUtf(input))
            val tpl = conv.toPaperTemplateType(readUtf(input))
            val pdfAssetId = readNullableUtf(input)
            val hasPdfIdx = input.readBoolean()
            val pdfIdx = if (hasPdfIdx) input.readInt() else null
            val invPdf = input.readBoolean()
            val snapPath = readUtf(input)
            val journalPath = readUtf(input)
            val needsOcr = input.readBoolean()
            val cAt = input.readLong()
            val uAt = input.readLong()
            pages.add(
                PageEntity(
                    id = id,
                    notebookId = nbId,
                    chapterId = chId,
                    pageIndexInNotebook = idx,
                    title = title,
                    canvasMode = cMode,
                    pageSizePreset = pPreset,
                    templateType = tpl,
                    pdfAssetId = pdfAssetId,
                    pdfPageIndex = pdfIdx,
                    invertPdfInOledMode = invPdf,
                    snapshotRelativePath = snapPath,
                    journalRelativePath = journalPath,
                    needsOcrSync = needsOcr,
                    createdAtEpochMs = cAt,
                    updatedAtEpochMs = uAt
                )
            )
        }

        val tagCount = input.readInt().coerceAtLeast(0)
        val tags = ArrayList<TagEntity>(tagCount)
        repeat(tagCount) {
            tags.add(TagEntity(id = readUtf(input), name = readUtf(input), colorArgb = input.readInt()))
        }

        val linkCount = input.readInt().coerceAtLeast(0)
        val tagLinks = ArrayList<NotebookTagCrossRef>(linkCount)
        repeat(linkCount) {
            tagLinks.add(NotebookTagCrossRef(notebookId = readUtf(input), tagId = readUtf(input)))
        }

        val bmCount = input.readInt().coerceAtLeast(0)
        val bookmarks = ArrayList<BookmarkEntity>(bmCount)
        repeat(bmCount) {
            bookmarks.add(
                BookmarkEntity(
                    id = readUtf(input),
                    notebookId = readUtf(input),
                    pageId = readUtf(input),
                    label = readUtf(input),
                    colorArgb = input.readInt(),
                    createdAtEpochMs = input.readLong()
                )
            )
        }

        val ftsCount = input.readInt().coerceAtLeast(0)
        val ftsEntries = ArrayList<PageTextFtsEntity>(ftsCount)
        repeat(ftsCount) {
            ftsEntries.add(
                PageTextFtsEntity(
                    pageId = readUtf(input),
                    notebookId = readUtf(input),
                    rawText = readUtf(input),
                    asciiFoldedText = readUtf(input)
                )
            )
        }

        val tombstones = if (formatVersion >= BackupManifest.MANIFEST_VERSION_V2 && input.available() >= 4) {
            val tCount = input.readInt().coerceAtLeast(0)
            ArrayList<SyncTombstoneEntity>(tCount).apply {
                repeat(tCount) {
                    val entityType = readUtf(input)
                    val entityId = readUtf(input)
                    val deletedAt = input.readLong()
                    add(
                        SyncTombstoneEntity(
                            compositeKey = SyncTombstoneEntity.key(entityType, entityId),
                            entityType = entityType,
                            entityId = entityId,
                            deletedAtEpochMs = deletedAt
                        )
                    )
                }
            }
        } else {
            emptyList()
        }

        return BackupManifest(
            formatVersion = formatVersion,
            appVersion = appVersion,
            createdAtEpochMs = createdAt,
            folders = folders,
            notebooks = notebooks,
            chapters = chapters,
            pages = pages,
            tags = tags,
            tagLinks = tagLinks,
            bookmarks = bookmarks,
            ftsEntries = ftsEntries,
            tombstones = tombstones
        )
    }

    /**
     * Packages [manifest] plus every stroke file (`pages/`), imported PDF (`pdfs/`), and custom
     * cover image (`covers/`) under [filesDir] into a ZIP archive written to [outputStream].
     * API keys and DataStore files are strictly excluded via [isAllowedBackupEntryPath].
     */
    suspend fun exportBackupToStream(
        manifest: BackupManifest,
        outputStream: OutputStream
    ): BackupExportSummary = withContext(Dispatchers.IO) {
        var assetCount = 0
        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(encodeManifest(manifest))
            zip.closeEntry()

            for (dirName in listOf("pages", "pdfs", "covers")) {
                val rootDir = File(filesDir, dirName)
                if (!rootDir.exists() || !rootDir.isDirectory) continue
                rootDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relPath = file.relativeTo(filesDir).path.replace('\\', '/')
                        if (isAllowedBackupEntryPath(relPath)) {
                            zip.putNextEntry(ZipEntry(relPath))
                            FileInputStream(file).use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                            assetCount++
                        }
                    }
            }
        }
        BackupExportSummary(manifest = manifest, packagedAssetFileCount = assetCount)
    }

    /**
     * Restores a StylusNote `.zip` backup stream into [filesDir] and returns the parsed [BackupManifest].
     */
    suspend fun importBackupFromStream(
        inputStream: InputStream
    ): BackupRestoreSummary = withContext(Dispatchers.IO) {
        var parsedManifest: BackupManifest? = null
        var restoredCount = 0
        val canonicalRoot = filesDir.canonicalFile

        ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/')
                if (entry.isDirectory || !isAllowedBackupEntryPath(entryName)) {
                    zip.closeEntry()
                    continue
                }

                if (entryName == MANIFEST_ENTRY_NAME) {
                    val bytes = zip.readBytes()
                    parsedManifest = decodeManifest(bytes)
                } else {
                    val target = File(canonicalRoot, entryName).canonicalFile
                    if (target.path.startsWith(canonicalRoot.path + File.separator)) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            zip.copyTo(out)
                        }
                        restoredCount++
                    }
                }
                zip.closeEntry()
            }
        }

        val manifest = parsedManifest ?: throw IOException("Hiányzó manifest.bin a backup archívumban.")
        BackupRestoreSummary(manifest = manifest, restoredAssetFileCount = restoredCount)
    }

    suspend fun collectManifestFromDatabase(database: AppDatabase): BackupManifest =
        withContext(Dispatchers.IO) {
            val folders = database.folderDao().observeAllFolders().first()
            val notebooks = database.notebookDao().observeAllNotebooks().first()
            val chapters = notebooks.flatMap { nb ->
                database.chapterDao().observeChapters(nb.id).first()
            }
            val pages = notebooks.flatMap { nb ->
                database.pageDao().observePagesForNotebook(nb.id).first()
            }
            val tags = database.tagDao().observeAllTags().first()
            val links = database.tagDao().observeAllNotebookTagLinks().first()
            val bookmarks = database.bookmarkDao().observeAllBookmarks().first()
            val ftsEntries = database.pageTextSearchDao().getAllFtsEntries()
            val tombstones = database.syncMetadataDao().getAllTombstones()
            BackupManifest(
                folders = folders,
                notebooks = notebooks,
                chapters = chapters,
                pages = pages,
                tags = tags,
                tagLinks = links,
                bookmarks = bookmarks,
                ftsEntries = ftsEntries,
                tombstones = tombstones
            )
        }

    suspend fun applyManifestToDatabase(
        database: AppDatabase,
        manifest: BackupManifest
    ) = withContext(Dispatchers.IO) {
        val localSnapshot = collectManifestFromDatabase(database)
        val merged = mergeManifests(local = localSnapshot, remote = manifest)
        writeMergedManifestToDatabase(database = database, mergeResult = merged)
    }

    /**
     * Merges [remote] into the live Room database using LWW timestamps + [SyncTombstoneEntity]
     * suppression and cleans up locally deleted page directories.
     */
    suspend fun mergeRemoteManifestIntoDatabase(
        database: AppDatabase,
        remote: BackupManifest
    ): ManifestMergeResult = withContext(Dispatchers.IO) {
        val local = collectManifestFromDatabase(database)
        val merged = mergeManifests(local = local, remote = remote)
        writeMergedManifestToDatabase(database = database, mergeResult = merged)
        for (deletedPageId in merged.deletedPageIds) {
            val pageDir = File(filesDir, "pages/$deletedPageId")
            if (pageDir.exists()) {
                pageDir.deleteRecursively()
            }
            database.syncMetadataDao().deleteSyncedAsset("pages/$deletedPageId")
        }
        merged
    }

    private suspend fun writeMergedManifestToDatabase(
        database: AppDatabase,
        mergeResult: ManifestMergeResult
    ) {
        val manifest = mergeResult.mergedManifest
        if (manifest.tombstones.isNotEmpty()) {
            database.syncMetadataDao().upsertTombstones(manifest.tombstones)
        }
        for (tombstone in manifest.tombstones) {
            when (tombstone.entityType) {
                SyncTombstoneEntity.TYPE_FOLDER ->
                    database.folderDao().deleteFolderById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_NOTEBOOK ->
                    database.notebookDao().deleteNotebookById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_CHAPTER ->
                    database.chapterDao().deleteChapterById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_PAGE ->
                    database.pageDao().deletePageById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_TAG ->
                    database.tagDao().deleteTagById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_BOOKMARK ->
                    database.bookmarkDao().deleteBookmarkById(tombstone.entityId)
                SyncTombstoneEntity.TYPE_TAG_LINK -> {
                    val sep = tombstone.entityId.indexOf('|')
                    if (sep > 0) {
                        database.tagDao().detachTagFromNotebook(
                            notebookId = tombstone.entityId.substring(0, sep),
                            tagId = tombstone.entityId.substring(sep + 1)
                        )
                    }
                }
            }
        }

        // Insert parents before children to satisfy Room foreign keys cleanly.
        val pendingFolders = manifest.folders.toMutableList()
        val insertedFolders = HashSet<String>()
        while (pendingFolders.isNotEmpty()) {
            val ready = pendingFolders.filter { it.parentId == null || it.parentId in insertedFolders }
            if (ready.isEmpty()) {
                pendingFolders.forEach { database.folderDao().upsertFolder(it.copy(parentId = null)) }
                break
            }
            ready.forEach {
                database.folderDao().upsertFolder(it)
                insertedFolders.add(it.id)
            }
            pendingFolders.removeAll(ready.toSet())
        }

        manifest.notebooks.forEach { database.notebookDao().upsertNotebook(it) }
        manifest.chapters.forEach { database.chapterDao().upsertChapter(it) }
        manifest.pages.forEach { database.pageDao().upsertPage(it) }
        manifest.tags.forEach { database.tagDao().upsertTag(it) }
        manifest.tagLinks.forEach { database.tagDao().attachTagToNotebook(it) }
        manifest.bookmarks.forEach { database.bookmarkDao().upsertBookmark(it) }
        manifest.ftsEntries.forEach { fts ->
            database.pageTextSearchDao().replacePageOcrText(
                pageId = fts.pageId,
                notebookId = fts.notebookId,
                recognizedText = fts.rawText.ifBlank { AccentNormalizer.foldToAscii(fts.asciiFoldedText) }
            )
        }
    }

    /**
     * Packages one relative subdirectory under [filesDir] (e.g. `pages/<pageId>`) into a deterministic
     * ZIP payload for incremental per-page cloud sync.
     */
    suspend fun packageRelativeSubdirToZipBytes(relativeSubdir: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val normalizedSubdir = relativeSubdir.replace('\\', '/').trim('/')
            if (!isAllowedBackupEntryPath("$normalizedSubdir/placeholder")) return@withContext null
            val targetDir = File(filesDir, normalizedSubdir)
            if (!targetDir.exists() || !targetDir.isDirectory) return@withContext null
            val files = targetDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(filesDir).path.replace('\\', '/') }
                .toList()
            packageFilesToDeterministicZipBytes(files)
        }

    /**
     * Packages a single relative file under [filesDir] (e.g. `pdfs/<name>.pdf` or `covers/<name>`)
     * into a deterministic ZIP payload for incremental cloud asset sync.
     */
    suspend fun packageRelativeFileToZipBytes(relativeFilePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val normalizedPath = relativeFilePath.replace('\\', '/').trim('/')
            if (!isAllowedBackupEntryPath(normalizedPath)) return@withContext null
            val targetFile = File(filesDir, normalizedPath)
            if (!targetFile.exists() || !targetFile.isFile) return@withContext null
            packageFilesToDeterministicZipBytes(listOf(targetFile))
        }

    private fun packageFilesToDeterministicZipBytes(files: List<File>): ByteArray? {
        if (files.isEmpty()) return null
        val baos = ByteArrayOutputStream()
        var wroteEntry = false
        ZipOutputStream(baos).use { zip ->
            for (file in files) {
                val relPath = file.relativeTo(filesDir).path.replace('\\', '/')
                if (isAllowedBackupEntryPath(relPath)) {
                    val entry = ZipEntry(relPath).apply { time = 0L }
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    wroteEntry = true
                }
            }
        }
        return if (wroteEntry) baos.toByteArray() else null
    }

    /**
     * Extracts a delta asset ZIP under [filesDir], rejecting any entry outside [allowedPrefix].
     */
    suspend fun extractRelativeSubdirZipBytes(
        zipBytes: ByteArray,
        allowedPrefix: String
    ): Int = withContext(Dispatchers.IO) {
        val normPrefix = allowedPrefix.replace('\\', '/').trim('/')
        val canonicalRoot = filesDir.canonicalFile
        var restored = 0
        ZipInputStream(BufferedInputStream(zipBytes.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/')
                val matchesTarget = entryName == normPrefix || entryName.startsWith("$normPrefix/")
                if (entry.isDirectory ||
                    !matchesTarget ||
                    !isAllowedBackupEntryPath(entryName)
                ) {
                    zip.closeEntry()
                    continue
                }
                val target = File(canonicalRoot, entryName).canonicalFile
                if (target.path.startsWith(canonicalRoot.path + File.separator)) {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                    restored++
                }
                zip.closeEntry()
            }
        }
        restored
    }

    suspend fun exportToSafUri(
        context: Context,
        database: AppDatabase,
        destinationUri: Uri
    ): BackupExportSummary {
        val manifest = collectManifestFromDatabase(database)
        return withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(destinationUri)
                ?: throw IOException("Nem nyitható meg a cél SAF fájl írásra.")
            out.use { exportBackupToStream(manifest, it) }
        }
    }

    suspend fun importFromSafUri(
        context: Context,
        database: AppDatabase,
        sourceUri: Uri
    ): BackupRestoreSummary {
        val summary = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Nem nyitható meg a forrás SAF fájl olvasásra.")
            input.use { importBackupFromStream(it) }
        }
        applyManifestToDatabase(database, summary.manifest)
        return summary
    }

    /**
     * Uploads a full `.zip` backup directly to a WebDAV endpoint via OkHttp HTTP `PUT` (Section 8).
     */
    suspend fun uploadToWebDav(
        database: AppDatabase,
        webDavFileUrl: String,
        username: String,
        password: String
    ): BackupExportSummary {
        val manifest = collectManifestFromDatabase(database)
        val zipBytes = ByteArrayOutputStream().use { baos ->
            exportBackupToStream(manifest, baos)
            baos.toByteArray()
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(webDavFileUrl)
                .header("Authorization", Credentials.basic(username, password))
                .put(zipBytes.toRequestBody("application/zip".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("WebDAV feltöltés sikertelen: HTTP ${response.code}")
                }
            }
            BackupExportSummary(manifest = manifest, packagedAssetFileCount = manifest.pages.size)
        }
    }

    suspend fun downloadFromWebDav(
        database: AppDatabase,
        webDavFileUrl: String,
        username: String,
        password: String
    ): BackupRestoreSummary {
        val summary = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(webDavFileUrl)
                .header("Authorization", Credentials.basic(username, password))
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("WebDAV letöltés sikertelen: HTTP ${response.code}")
                }
                val stream = response.body?.byteStream()
                    ?: throw IOException("Üres WebDAV válasz")
                stream.use { importBackupFromStream(it) }
            }
        }
        applyManifestToDatabase(database, summary.manifest)
        return summary
    }

    private fun writeUtf(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readUtf(input: DataInputStream): String {
        val len = input.readInt().coerceAtLeast(0)
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeNullableUtf(out: DataOutputStream, text: String?) {
        if (text == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            writeUtf(out, text)
        }
    }

    private fun readNullableUtf(input: DataInputStream): String? =
        if (input.readBoolean()) readUtf(input) else null

    private fun writeNullableFloat(out: DataOutputStream, value: Float?) {
        if (value == null || !value.isFinite()) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeFloat(value)
        }
    }

    private fun readNullableFloat(input: DataInputStream): Float? =
        if (input.readBoolean()) input.readFloat().takeIf { it.isFinite() } else null

    companion object {
        const val MANIFEST_ENTRY_NAME: String = "manifest.bin"
        private val MANIFEST_MAGIC: ByteArray = byteArrayOf(0x53, 0x4E, 0x42, 0x4B) // "SNBK"

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString(separator = "") { b -> "%02x".format(b) }
        }

        /**
         * Pure LWW + tombstone merge between [local] and [remote] manifests so newer changes win
         * on either device while deleted folders/notebooks/pages/bookmarks stay deleted.
         */
        fun mergeManifests(
            local: BackupManifest,
            remote: BackupManifest
        ): ManifestMergeResult {
            val tombstonesByKey = LinkedHashMap<String, SyncTombstoneEntity>()
            for (t in local.tombstones + remote.tombstones) {
                val existing = tombstonesByKey[t.compositeKey]
                if (existing == null || t.deletedAtEpochMs > existing.deletedAtEpochMs) {
                    tombstonesByKey[t.compositeKey] = t
                }
            }

            fun tombstoneTime(type: String, id: String): Long? =
                tombstonesByKey[SyncTombstoneEntity.key(type, id)]?.deletedAtEpochMs

            fun isSuppressed(type: String, id: String, entityUpdatedAtMs: Long): Boolean {
                val deletedAt = tombstoneTime(type, id) ?: return false
                return deletedAt >= entityUpdatedAtMs
            }

            val folderMap = LinkedHashMap<String, FolderEntity>()
            for (f in local.folders + remote.folders) {
                if (isSuppressed(SyncTombstoneEntity.TYPE_FOLDER, f.id, f.updatedAtEpochMs)) continue
                val existing = folderMap[f.id]
                if (existing == null || f.updatedAtEpochMs > existing.updatedAtEpochMs) {
                    folderMap[f.id] = f
                }
            }

            val notebookMap = LinkedHashMap<String, NotebookEntity>()
            for (nb in local.notebooks + remote.notebooks) {
                if (isSuppressed(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id, nb.updatedAtEpochMs)) continue
                val cleanFolderId = nb.folderId?.takeIf { folderMap.containsKey(it) }
                val normalized = nb.copy(folderId = cleanFolderId)
                val existing = notebookMap[nb.id]
                if (existing == null || normalized.updatedAtEpochMs > existing.updatedAtEpochMs) {
                    notebookMap[nb.id] = normalized
                }
            }

            val chapterMap = LinkedHashMap<String, ChapterEntity>()
            for (ch in local.chapters + remote.chapters) {
                if (!notebookMap.containsKey(ch.notebookId)) continue
                if (isSuppressed(SyncTombstoneEntity.TYPE_CHAPTER, ch.id, ch.createdAtEpochMs)) continue
                chapterMap.putIfAbsent(ch.id, ch)
            }

            val localPagesById = local.pages.associateBy { it.id }
            val dirtyRemotePageIds = LinkedHashSet<String>()
            val pageMap = LinkedHashMap<String, PageEntity>()
            for (p in local.pages + remote.pages) {
                if (!notebookMap.containsKey(p.notebookId)) continue
                if (isSuppressed(SyncTombstoneEntity.TYPE_PAGE, p.id, p.updatedAtEpochMs)) continue
                val existing = pageMap[p.id]
                if (existing == null || p.updatedAtEpochMs > existing.updatedAtEpochMs) {
                    pageMap[p.id] = p
                }
            }
            for ((pageId, chosen) in pageMap) {
                val localPage = localPagesById[pageId]
                if (localPage == null || chosen.updatedAtEpochMs > localPage.updatedAtEpochMs) {
                    dirtyRemotePageIds.add(pageId)
                }
            }

            val deletedPageIds = local.pages
                .map { it.id }
                .filter { !pageMap.containsKey(it) }
                .toSet()

            val tagMap = LinkedHashMap<String, TagEntity>()
            for (t in local.tags + remote.tags) {
                if (tombstoneTime(SyncTombstoneEntity.TYPE_TAG, t.id) != null) continue
                tagMap.putIfAbsent(t.id, t)
            }

            val linkSet = LinkedHashSet<NotebookTagCrossRef>()
            for (link in local.tagLinks + remote.tagLinks) {
                if (!notebookMap.containsKey(link.notebookId) || !tagMap.containsKey(link.tagId)) continue
                val linkKey = SyncTombstoneEntity.tagLinkId(link.notebookId, link.tagId)
                if (tombstoneTime(SyncTombstoneEntity.TYPE_TAG_LINK, linkKey) != null) continue
                linkSet.add(link)
            }

            val bookmarkMap = LinkedHashMap<String, BookmarkEntity>()
            for (bm in local.bookmarks + remote.bookmarks) {
                if (!notebookMap.containsKey(bm.notebookId) || !pageMap.containsKey(bm.pageId)) continue
                if (isSuppressed(SyncTombstoneEntity.TYPE_BOOKMARK, bm.id, bm.createdAtEpochMs)) continue
                bookmarkMap.putIfAbsent(bm.id, bm)
            }

            val ftsByPageId = LinkedHashMap<String, PageTextFtsEntity>()
            for (fts in local.ftsEntries + remote.ftsEntries) {
                if (!pageMap.containsKey(fts.pageId)) continue
                // Prefer remote FTS when remote page won, otherwise keep latest non-blank entry.
                val preferRemote = fts.pageId in dirtyRemotePageIds
                val existing = ftsByPageId[fts.pageId]
                if (existing == null || (preferRemote && remote.ftsEntries.contains(fts))) {
                    ftsByPageId[fts.pageId] = fts
                }
            }

            val mergedManifest = BackupManifest(
                formatVersion = BackupManifest.CURRENT_MANIFEST_VERSION,
                appVersion = remote.appVersion.ifBlank { local.appVersion },
                createdAtEpochMs = maxOf(local.createdAtEpochMs, remote.createdAtEpochMs),
                folders = folderMap.values.toList(),
                notebooks = notebookMap.values.toList(),
                chapters = chapterMap.values.toList(),
                pages = pageMap.values.toList(),
                tags = tagMap.values.toList(),
                tagLinks = linkSet.toList(),
                bookmarks = bookmarkMap.values.toList(),
                ftsEntries = ftsByPageId.values.toList(),
                tombstones = tombstonesByKey.values.toList()
            )
            return ManifestMergeResult(
                mergedManifest = mergedManifest,
                dirtyRemotePageIds = dirtyRemotePageIds,
                deletedPageIds = deletedPageIds
            )
        }

        /**
         * Strict allowlist for backup entries: rejects directory traversal (`..`) and explicitly
         * excludes `datastore/`, `*.preferences_pb`, and any AI API-key files (Section 2 & 8).
         */
        fun isAllowedBackupEntryPath(rawPath: String): Boolean {
            val norm = rawPath.replace('\\', '/').trimStart('/')
            if (norm.isEmpty() || norm.contains("..") || norm.contains('\u0000')) {
                return false
            }
            val lower = norm.lowercase()
            if (lower.startsWith("datastore/") ||
                lower.contains("api_key") ||
                lower.contains("keystore") ||
                lower.endsWith(".preferences_pb")
            ) {
                return false
            }
            return norm == MANIFEST_ENTRY_NAME ||
                norm.startsWith("pages/") ||
                norm.startsWith("pdfs/") ||
                norm.startsWith("covers/")
        }
    }
}

