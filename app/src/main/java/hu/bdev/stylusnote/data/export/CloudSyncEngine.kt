package hu.bdev.stylusnote.data.export

import androidx.room.withTransaction
import hu.bdev.stylusnote.data.local.AccentNormalizer
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.CloudSyncedAssetEntity
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.TreeMap

enum class SyncExecutionMode {
    BIDIRECTIONAL_MERGE,
    RESTORE_FROM_CLOUD
}

data class RemoteCloudSummary(
    val exists: Boolean,
    val notebookCount: Int = 0,
    val pageCount: Int = 0,
    val updatedAtEpochMs: Long = 0L,
    val assetCount: Int = 0
)

data class CloudSyncReport(
    val syncedAtEpochMs: Long,
    val totalNotebooks: Int,
    val totalPages: Int,
    val downloadedAssets: Int,
    val uploadedAssets: Int,
    val deletedRemoteAssets: Int,
    val deletedLocalPages: Int,
    val replacedStarterWorkspace: Boolean,
    val uploadedManifest: Boolean
)

/**
 * Storage abstraction used by [CloudSyncEngine] so production callers persist via Room
 * ([DatabaseSyncManifestStore]) while pure-JVM unit tests can simulate multiple devices cleanly.
 */
interface SyncManifestStore {
    suspend fun collectManifest(): BackupManifest
    suspend fun mergeRemoteManifest(remote: BackupManifest): ManifestMergeResult
    suspend fun getAllSyncedAssets(): List<CloudSyncedAssetEntity>
    suspend fun getSyncedAsset(assetKey: String): CloudSyncedAssetEntity?
    suspend fun upsertSyncedAsset(asset: CloudSyncedAssetEntity)
    suspend fun deleteSyncedAsset(assetKey: String)
    suspend fun isUntouchedStarterWorkspace(filesDir: File): Boolean
    suspend fun clearStarterWorkspaceWithoutTombstone(filesDir: File)
    suspend fun restoreNotebookFromSnapshot(snapshot: BackupManifest, restoredAtEpochMs: Long)
}

class DatabaseSyncManifestStore(
    private val database: AppDatabase,
    private val backupManager: BackupManager
) : SyncManifestStore {

    override suspend fun collectManifest(): BackupManifest =
        backupManager.collectManifestFromDatabase(database)

    override suspend fun mergeRemoteManifest(remote: BackupManifest): ManifestMergeResult =
        backupManager.mergeRemoteManifestIntoDatabase(database, remote)

    override suspend fun getAllSyncedAssets(): List<CloudSyncedAssetEntity> =
        database.syncMetadataDao().getAllSyncedAssets()

    override suspend fun getSyncedAsset(assetKey: String): CloudSyncedAssetEntity? =
        database.syncMetadataDao().getSyncedAsset(assetKey)

    override suspend fun upsertSyncedAsset(asset: CloudSyncedAssetEntity) {
        database.syncMetadataDao().upsertSyncedAsset(asset)
    }

    override suspend fun deleteSyncedAsset(assetKey: String) {
        database.syncMetadataDao().deleteSyncedAsset(assetKey)
    }

    override suspend fun isUntouchedStarterWorkspace(filesDir: File): Boolean =
        withContext(Dispatchers.IO) {
            val folders = database.folderDao().observeAllFolders().first()
            val notebooks = database.notebookDao().observeAllNotebooks().first()
            val tags = database.tagDao().observeAllTags().first()
            val bookmarks = database.bookmarkDao().observeAllBookmarks().first()
            val fts = database.pageTextSearchDao().getAllFtsEntries()
            val tombstones = database.syncMetadataDao().getAllTombstones()
            if (folders.isNotEmpty() || tags.isNotEmpty() || bookmarks.isNotEmpty() ||
                fts.isNotEmpty() || tombstones.isNotEmpty() || notebooks.size != 1
            ) {
                return@withContext false
            }
            val nb = notebooks[0]
            if (nb.title != CloudSyncEngine.STARTER_NOTEBOOK_TITLE ||
                nb.cover.customImageRelativePath != null
            ) {
                return@withContext false
            }
            val chapters = database.chapterDao().observeChapters(nb.id).first()
            val pages = database.pageDao().observePagesForNotebook(nb.id).first()
            if (chapters.isNotEmpty() || pages.size != 1) return@withContext false
            val page = pages[0]
            if (page.pdfAssetId != null || !page.title.isNullOrBlank()) return@withContext false
            val pageDir = File(filesDir, "pages/${page.id}")
            val hasFiles = pageDir.exists() && pageDir.walkTopDown().any { it.isFile }
            !hasFiles
        }

    override suspend fun clearStarterWorkspaceWithoutTombstone(filesDir: File) =
        withContext(Dispatchers.IO) {
            val notebooks = database.notebookDao().observeAllNotebooks().first()
            if (notebooks.size != 1) return@withContext
            val nb = notebooks[0]
            val pages = database.pageDao().observePagesForNotebook(nb.id).first()
            database.withTransaction {
                for (page in pages) {
                    database.pageDao().deletePageById(page.id)
                    database.syncMetadataDao().deleteTombstone(
                        SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_PAGE, page.id)
                    )
                }
                database.notebookDao().deleteNotebookById(nb.id)
                database.syncMetadataDao().deleteTombstone(
                    SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id)
                )
            }
            for (page in pages) {
                val dir = File(filesDir, "pages/${page.id}")
                if (dir.exists()) dir.deleteRecursively()
            }
        }

    override suspend fun restoreNotebookFromSnapshot(
        snapshot: BackupManifest,
        restoredAtEpochMs: Long
    ) {
        database.withTransaction {
            val tagDao = database.tagDao()
            val syncDao = database.syncMetadataDao()
            for (tag in snapshot.tags) {
                syncDao.deleteTombstone(SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_TAG, tag.id))
                tagDao.upsertTag(tag)
            }
            for (nb in snapshot.notebooks) {
                val validFolderId = nb.folderId?.takeIf { database.folderDao().getFolderById(it) != null }
                syncDao.deleteTombstone(SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id))
                database.notebookDao().upsertNotebook(
                    nb.copy(
                        folderId = validFolderId,
                        updatedAtEpochMs = restoredAtEpochMs
                    )
                )
            }
            for (ch in snapshot.chapters) {
                syncDao.deleteTombstone(SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_CHAPTER, ch.id))
                database.chapterDao().upsertChapter(ch)
            }
            for (p in snapshot.pages) {
                syncDao.deleteTombstone(SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_PAGE, p.id))
                database.pageDao().upsertPage(p.copy(updatedAtEpochMs = restoredAtEpochMs))
            }
            for (link in snapshot.tagLinks) {
                syncDao.deleteTombstone(
                    SyncTombstoneEntity.key(
                        SyncTombstoneEntity.TYPE_TAG_LINK,
                        SyncTombstoneEntity.tagLinkId(link.notebookId, link.tagId)
                    )
                )
                tagDao.attachTagToNotebook(link)
            }
            for (bm in snapshot.bookmarks) {
                syncDao.deleteTombstone(SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_BOOKMARK, bm.id))
                database.bookmarkDao().upsertBookmark(bm)
            }
            for (fts in snapshot.ftsEntries) {
                val raw = fts.rawText.ifBlank { AccentNormalizer.foldToAscii(fts.asciiFoldedText) }
                database.pageTextSearchDao().replacePageOcrText(
                    pageId = fts.pageId,
                    notebookId = fts.notebookId,
                    recognizedText = raw
                )
            }
        }
    }
}

/**
 * Coordinates two-way incremental LWW metadata merging and SHA-256 deduplicated asset sync
 * (`pages/<pageId>`, `pdfs/<pdfAssetId>.pdf`, `covers/<file>`) over any [CloudSyncRemoteClient].
 */
class CloudSyncEngine internal constructor(
    private val filesDir: File,
    private val manifestStore: SyncManifestStore,
    private val backupManager: BackupManager = BackupManager(filesDir)
) {
    constructor(
        filesDir: File,
        database: AppDatabase,
        backupManager: BackupManager = BackupManager(filesDir)
    ) : this(
        filesDir = filesDir,
        manifestStore = DatabaseSyncManifestStore(database, backupManager),
        backupManager = backupManager
    )

    private val syncMutex = Mutex()

    suspend fun probeRemoteBackup(remoteClient: CloudSyncRemoteClient): RemoteCloudSummary {
        val indexBytes = remoteClient.readFile(REMOTE_INDEX_FILE_NAME)
        val manifestBytes = remoteClient.readFile(REMOTE_MANIFEST_FILE_NAME)
            ?: return RemoteCloudSummary(exists = false)
        val manifest = backupManager.decodeManifest(manifestBytes)
        val index = indexBytes?.let {
            runCatching { RemoteSyncIndex.decodeFromUtf8Bytes(it) }.getOrNull()
        }
        return RemoteCloudSummary(
            exists = true,
            notebookCount = manifest.notebooks.size,
            pageCount = manifest.pages.size,
            updatedAtEpochMs = index?.updatedAtMs?.takeIf { it > 0L } ?: manifest.createdAtEpochMs,
            assetCount = index?.assets?.size ?: 0
        )
    }

    suspend fun syncNow(
        remoteClient: CloudSyncRemoteClient,
        mode: SyncExecutionMode = SyncExecutionMode.BIDIRECTIONAL_MERGE,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CloudSyncReport = syncMutex.withLock {
        val remoteIndexBytes = remoteClient.readFile(REMOTE_INDEX_FILE_NAME)
        val remoteIndex = remoteIndexBytes?.let {
            runCatching { RemoteSyncIndex.decodeFromUtf8Bytes(it) }.getOrNull()
        }
        val remoteManifestBytes = remoteClient.readFile(REMOTE_MANIFEST_FILE_NAME)
        val remoteManifest = remoteManifestBytes?.let { backupManager.decodeManifest(it) }

        if (mode == SyncExecutionMode.RESTORE_FROM_CLOUD && remoteManifest == null) {
            throw IOException("Nem található visszaállítható mentés a beállított felhőmappában.")
        }

        var replacedStarter = false
        if (remoteManifest != null &&
            remoteManifest.notebooks.isNotEmpty() &&
            manifestStore.isUntouchedStarterWorkspace(filesDir)
        ) {
            manifestStore.clearStarterWorkspaceWithoutTombstone(filesDir)
            replacedStarter = true
        }

        val mergeResult = if (remoteManifest != null) {
            manifestStore.mergeRemoteManifest(remoteManifest)
        } else {
            ManifestMergeResult(
                mergedManifest = manifestStore.collectManifest(),
                dirtyRemotePageIds = emptySet(),
                deletedPageIds = emptySet()
            )
        }

        val mergedManifest = mergeResult.mergedManifest
        val activeNotebookIds = mergedManifest.notebooks.mapTo(HashSet()) { it.id }
        val activeAssetKeys = collectActiveAssetKeys(mergedManifest)
        val remoteAssets = remoteIndex?.assets ?: emptyMap()
        val localSyncedMap = manifestStore.getAllSyncedAssets().associateBy { it.assetPath }

        // Read 7-day cloud trash ledger, purge expired items, and retain newly deleted notebooks
        val remoteTrash = remoteClient.readFile(REMOTE_TRASH_FILE_NAME)?.let {
            runCatching { RemoteTrashIndex.decodeFromUtf8Bytes(it) }.getOrNull()
        } ?: RemoteTrashIndex()
        val trashMap = LinkedHashMap<String, TrashedNotebookRecord>()
        val expiredTrashEntries = ArrayList<TrashedNotebookRecord>()
        var trashDirty = false
        for (entry in remoteTrash.entries) {
            when {
                entry.isExpiredAt(nowEpochMs) -> {
                    expiredTrashEntries.add(entry)
                    trashDirty = true
                }
                entry.notebookId in activeNotebookIds -> {
                    trashDirty = true
                }
                else -> trashMap[entry.notebookId] = entry
            }
        }
        if (remoteManifest != null && mode != SyncExecutionMode.RESTORE_FROM_CLOUD) {
            for (remoteNb in remoteManifest.notebooks) {
                if (remoteNb.id !in activeNotebookIds && !trashMap.containsKey(remoteNb.id)) {
                    val deletedAt = mergedManifest.tombstones
                        .firstOrNull {
                            it.entityType == SyncTombstoneEntity.TYPE_NOTEBOOK && it.entityId == remoteNb.id
                        }?.deletedAtEpochMs ?: nowEpochMs
                    val expiresAt = deletedAt + TRASH_RETENTION_MS
                    if (nowEpochMs < expiresAt) {
                        val nbChapters = remoteManifest.chapters.filter { it.notebookId == remoteNb.id }
                        val nbPages = remoteManifest.pages.filter { it.notebookId == remoteNb.id }
                        val nbBookmarks = remoteManifest.bookmarks.filter { it.notebookId == remoteNb.id }
                        val nbTagLinks = remoteManifest.tagLinks.filter { it.notebookId == remoteNb.id }
                        val tagIds = nbTagLinks.mapTo(HashSet()) { it.tagId }
                        val nbTags = remoteManifest.tags.filter { it.id in tagIds }
                        val nbFts = remoteManifest.ftsEntries.filter { it.notebookId == remoteNb.id }
                        val snapshotManifest = BackupManifest(
                            createdAtEpochMs = nowEpochMs,
                            notebooks = listOf(remoteNb),
                            chapters = nbChapters,
                            pages = nbPages,
                            tags = nbTags,
                            tagLinks = nbTagLinks,
                            bookmarks = nbBookmarks,
                            ftsEntries = nbFts
                        )
                        val snapshotB64 = Base64.getEncoder().encodeToString(
                            backupManager.encodeManifest(snapshotManifest)
                        )
                        val nbAssetEntries = LinkedHashMap<String, RemoteAssetEntry>()
                        for (assetKey in collectActiveAssetKeys(snapshotManifest)) {
                            remoteAssets[assetKey]?.let { nbAssetEntries[assetKey] = it }
                        }
                        trashMap[remoteNb.id] = TrashedNotebookRecord(
                            notebookId = remoteNb.id,
                            title = remoteNb.title,
                            folderId = remoteNb.folderId,
                            pageCount = nbPages.size,
                            deletedAtEpochMs = deletedAt,
                            expiresAtEpochMs = expiresAt,
                            manifestSnapshotBase64 = snapshotB64,
                            assetEntries = nbAssetEntries
                        )
                        trashDirty = true
                    }
                }
            }
        }
        val protectedTrashAssets = HashSet<String>()
        for (entry in trashMap.values) {
            protectedTrashAssets.addAll(entry.assetEntries.keys)
        }

        var downloadedAssets = 0
        for (assetKey in activeAssetKeys) {
            val remoteEntry = remoteAssets[assetKey] ?: continue
            val pageId = if (assetKey.startsWith("pages/")) assetKey.removePrefix("pages/") else null
            val localZipBytes = packageAssetKeyToZipBytes(assetKey)
            val localSha = localZipBytes?.let { BackupManager.sha256Hex(it) }
            val lastSyncedSha = localSyncedMap[assetKey]?.contentHashSha256

            val shouldDownload = when {
                localSha == remoteEntry.sha256 -> false
                mode == SyncExecutionMode.RESTORE_FROM_CLOUD -> true
                localZipBytes == null -> true
                pageId != null && pageId in mergeResult.dirtyRemotePageIds -> true
                lastSyncedSha != null && localSha == lastSyncedSha && remoteEntry.sha256 != lastSyncedSha -> true
                else -> false
            }

            if (shouldDownload) {
                val blobBytes = remoteClient.readFile(remoteEntry.blobName) ?: continue
                backupManager.extractRelativeSubdirZipBytes(blobBytes, allowedPrefix = assetKey)
                val updatedLocalSha = packageAssetKeyToZipBytes(assetKey)
                    ?.let { BackupManager.sha256Hex(it) }
                    ?: remoteEntry.sha256
                manifestStore.upsertSyncedAsset(
                    CloudSyncedAssetEntity(
                        assetPath = assetKey,
                        contentHashSha256 = updatedLocalSha,
                        lastSyncedEpochMs = nowEpochMs
                    )
                )
                downloadedAssets++
            } else if (localSha != null && lastSyncedSha != localSha) {
                manifestStore.upsertSyncedAsset(
                    CloudSyncedAssetEntity(
                        assetPath = assetKey,
                        contentHashSha256 = localSha,
                        lastSyncedEpochMs = nowEpochMs
                    )
                )
            }
        }

        val nextRemoteAssets = TreeMap<String, RemoteAssetEntry>()
        var deletedRemoteAssets = 0
        for ((remoteKey, remoteEntry) in remoteAssets) {
            if (remoteKey in activeAssetKeys) {
                nextRemoteAssets[remoteKey] = remoteEntry
            } else if (remoteKey !in protectedTrashAssets) {
                remoteClient.deleteFile(remoteEntry.blobName)
                manifestStore.deleteSyncedAsset(remoteKey)
                deletedRemoteAssets++
            }
        }
        for (expired in expiredTrashEntries) {
            for ((assetKey, assetEntry) in expired.assetEntries) {
                if (assetKey !in activeAssetKeys &&
                    assetKey !in protectedTrashAssets &&
                    !nextRemoteAssets.containsKey(assetKey)
                ) {
                    remoteClient.deleteFile(assetEntry.blobName)
                    manifestStore.deleteSyncedAsset(assetKey)
                    deletedRemoteAssets++
                }
            }
        }

        var uploadedAssets = 0
        for (assetKey in activeAssetKeys) {
            val localZipBytes = packageAssetKeyToZipBytes(assetKey) ?: continue
            val localSha = BackupManager.sha256Hex(localZipBytes)
            val existingRemote = nextRemoteAssets[assetKey]
            if (existingRemote?.sha256 != localSha) {
                val blobName = existingRemote?.blobName ?: remoteBlobNameForAssetKey(assetKey)
                remoteClient.writeFile(blobName, localZipBytes, "application/zip")
                nextRemoteAssets[assetKey] = RemoteAssetEntry(
                    blobName = blobName,
                    sha256 = localSha
                )
                manifestStore.upsertSyncedAsset(
                    CloudSyncedAssetEntity(
                        assetPath = assetKey,
                        contentHashSha256 = localSha,
                        lastSyncedEpochMs = nowEpochMs
                    )
                )
                uploadedAssets++
            } else {
                val tracked = manifestStore.getSyncedAsset(assetKey)
                if (tracked == null || tracked.contentHashSha256 != localSha) {
                    manifestStore.upsertSyncedAsset(
                        CloudSyncedAssetEntity(
                            assetPath = assetKey,
                            contentHashSha256 = localSha,
                            lastSyncedEpochMs = nowEpochMs
                        )
                    )
                }
            }
        }

        val finalManifest = manifestStore.collectManifest().copy(createdAtEpochMs = nowEpochMs)
        val shouldUploadManifest = remoteManifest == null ||
            uploadedAssets > 0 ||
            deletedRemoteAssets > 0 ||
            remoteIndex == null ||
            manifestContentDiffers(finalManifest, remoteManifest)

        if (shouldUploadManifest) {
            val encodedManifest = backupManager.encodeManifest(finalManifest)
            val manifestSha = BackupManager.sha256Hex(encodedManifest)
            remoteClient.writeFile(
                remoteName = REMOTE_MANIFEST_FILE_NAME,
                bytes = encodedManifest,
                contentType = "application/octet-stream"
            )
            val nextIndex = RemoteSyncIndex(
                formatVersion = 2,
                updatedAtMs = nowEpochMs,
                manifestFileName = REMOTE_MANIFEST_FILE_NAME,
                manifestSha256 = manifestSha,
                assets = nextRemoteAssets
            )
            remoteClient.writeFile(
                remoteName = REMOTE_INDEX_FILE_NAME,
                bytes = nextIndex.encodeToUtf8Bytes(),
                contentType = "application/json"
            )
        }

        if (trashDirty) {
            val nextTrash = RemoteTrashIndex(
                formatVersion = 1,
                updatedAtMs = nowEpochMs,
                entries = trashMap.values.sortedByDescending { it.deletedAtEpochMs }
            )
            remoteClient.writeFile(
                remoteName = REMOTE_TRASH_FILE_NAME,
                bytes = nextTrash.encodeToUtf8Bytes(),
                contentType = "application/json"
            )
        }

        return@withLock CloudSyncReport(
            syncedAtEpochMs = nowEpochMs,
            totalNotebooks = finalManifest.notebooks.size,
            totalPages = finalManifest.pages.size,
            downloadedAssets = downloadedAssets,
            uploadedAssets = uploadedAssets,
            deletedRemoteAssets = deletedRemoteAssets,
            deletedLocalPages = mergeResult.deletedPageIds.size,
            replacedStarterWorkspace = replacedStarter,
            uploadedManifest = shouldUploadManifest
        )
    }

    /**
     * Lists non-expired notebooks currently preserved in the 7-day remote trash (`cloud-trash.json`).
     */
    suspend fun listTrashedNotebooks(
        remoteClient: CloudSyncRemoteClient,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<TrashedNotebookRecord> {
        val raw = remoteClient.readFile(REMOTE_TRASH_FILE_NAME) ?: return emptyList()
        val parsed = runCatching { RemoteTrashIndex.decodeFromUtf8Bytes(raw) }.getOrNull() ?: return emptyList()
        return parsed.entries
            .filter { !it.isExpiredAt(nowEpochMs) }
            .sortedByDescending { it.deletedAtEpochMs }
    }

    /**
     * Restores a 7-day-retained notebook from [REMOTE_TRASH_FILE_NAME] back into the local workspace,
     * downloads its protected blobs, clears tombstones, updates `cloud-trash.json`, and syncs.
     */
    suspend fun restoreTrashedNotebook(
        remoteClient: CloudSyncRemoteClient,
        notebookId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TrashedNotebookRecord? {
        val restoredRecord = syncMutex.withLock {
            val raw = remoteClient.readFile(REMOTE_TRASH_FILE_NAME) ?: return@withLock null
            val currentTrash = runCatching { RemoteTrashIndex.decodeFromUtf8Bytes(raw) }.getOrNull()
                ?: return@withLock null
            val target = currentTrash.entries.firstOrNull {
                it.notebookId == notebookId && !it.isExpiredAt(nowEpochMs)
            } ?: return@withLock null

            val snapshotBytes = Base64.getDecoder().decode(target.manifestSnapshotBase64)
            val snapshotManifest = backupManager.decodeManifest(snapshotBytes)
            for ((assetKey, remoteEntry) in target.assetEntries) {
                val localZip = packageAssetKeyToZipBytes(assetKey)
                val localSha = localZip?.let { BackupManager.sha256Hex(it) }
                if (localSha != remoteEntry.sha256) {
                    val blob = remoteClient.readFile(remoteEntry.blobName) ?: continue
                    backupManager.extractRelativeSubdirZipBytes(blob, allowedPrefix = assetKey)
                }
            }
            manifestStore.restoreNotebookFromSnapshot(snapshotManifest, restoredAtEpochMs = nowEpochMs)

            val remaining = currentTrash.entries.filter {
                it.notebookId != notebookId && !it.isExpiredAt(nowEpochMs)
            }
            val updatedTrash = RemoteTrashIndex(
                formatVersion = 1,
                updatedAtMs = nowEpochMs,
                entries = remaining
            )
            remoteClient.writeFile(
                remoteName = REMOTE_TRASH_FILE_NAME,
                bytes = updatedTrash.encodeToUtf8Bytes(),
                contentType = "application/json"
            )
            target
        } ?: return null

        syncNow(remoteClient, mode = SyncExecutionMode.BIDIRECTIONAL_MERGE, nowEpochMs = nowEpochMs + 1L)
        return restoredRecord
    }

    /**
     * Permanently removes a soft-deleted notebook from the 7-day cloud trash and deletes any
     * orphaned remote blobs no longer referenced by active or other trashed notebooks.
     */
    suspend fun permanentlyDeleteTrashedNotebook(
        remoteClient: CloudSyncRemoteClient,
        notebookId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = syncMutex.withLock {
        val raw = remoteClient.readFile(REMOTE_TRASH_FILE_NAME) ?: return@withLock false
        val currentTrash = runCatching { RemoteTrashIndex.decodeFromUtf8Bytes(raw) }.getOrNull()
            ?: return@withLock false
        val removed = currentTrash.entries.filter {
            it.notebookId == notebookId || it.isExpiredAt(nowEpochMs)
        }
        if (removed.none { it.notebookId == notebookId }) return@withLock false

        val kept = currentTrash.entries.filter {
            it.notebookId != notebookId && !it.isExpiredAt(nowEpochMs)
        }
        val activeKeys = collectActiveAssetKeys(manifestStore.collectManifest()).toSet()
        val remoteIndexKeys = remoteClient.readFile(REMOTE_INDEX_FILE_NAME)?.let {
            runCatching { RemoteSyncIndex.decodeFromUtf8Bytes(it).assets.keys }.getOrNull()
        } ?: emptySet()
        val stillProtected = HashSet<String>()
        kept.forEach { stillProtected.addAll(it.assetEntries.keys) }

        for (item in removed) {
            for ((assetKey, remoteEntry) in item.assetEntries) {
                if (assetKey !in activeKeys &&
                    assetKey !in remoteIndexKeys &&
                    assetKey !in stillProtected
                ) {
                    remoteClient.deleteFile(remoteEntry.blobName)
                    manifestStore.deleteSyncedAsset(assetKey)
                }
            }
        }

        val updatedTrash = RemoteTrashIndex(
            formatVersion = 1,
            updatedAtMs = nowEpochMs,
            entries = kept
        )
        remoteClient.writeFile(
            remoteName = REMOTE_TRASH_FILE_NAME,
            bytes = updatedTrash.encodeToUtf8Bytes(),
            contentType = "application/json"
        )
        true
    }

    private suspend fun packageAssetKeyToZipBytes(assetKey: String): ByteArray? =
        if (assetKey.startsWith("pages/")) {
            backupManager.packageRelativeSubdirToZipBytes(assetKey)
        } else {
            backupManager.packageRelativeFileToZipBytes(assetKey)
        }

    companion object {
        internal const val STARTER_NOTEBOOK_TITLE = "Első jegyzetfüzet"

        internal fun collectActiveAssetKeys(manifest: BackupManifest): List<String> {
            val keys = LinkedHashSet<String>()
            for (page in manifest.pages) {
                keys.add("pages/${page.id}")
                val pdfId = page.pdfAssetId?.trim()?.takeIf { it.isNotEmpty() }
                if (pdfId != null) {
                    val pdfKey = normalizePdfAssetKey(pdfId)
                    if (BackupManager.isAllowedBackupEntryPath(pdfKey)) {
                        keys.add(pdfKey)
                    }
                }
            }
            for (nb in manifest.notebooks) {
                val coverPath = nb.cover.customImageRelativePath?.trim()?.takeIf { it.isNotEmpty() }
                if (coverPath != null) {
                    val coverKey = normalizeCoverAssetKey(coverPath)
                    if (BackupManager.isAllowedBackupEntryPath(coverKey)) {
                        keys.add(coverKey)
                    }
                }
            }
            return keys.sorted()
        }

        internal fun normalizePdfAssetKey(rawPdfId: String): String {
            val clean = rawPdfId.replace('\\', '/').trimStart('/')
            return when {
                clean.startsWith("pdfs/") -> clean
                clean.endsWith(".pdf", ignoreCase = true) -> "pdfs/$clean"
                else -> "pdfs/$clean.pdf"
            }
        }

        internal fun normalizeCoverAssetKey(rawCoverPath: String): String {
            val clean = rawCoverPath.replace('\\', '/').trimStart('/')
            return if (clean.startsWith("covers/")) clean else "covers/$clean"
        }

        internal fun remoteBlobNameForAssetKey(assetKey: String): String {
            val prefix = assetKey.substringBefore('/', "asset")
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .take(10)
            val slug = assetKey.substringAfter('/', assetKey)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(36)
            val digest = BackupManager.sha256Hex(assetKey.toByteArray(StandardCharsets.UTF_8))
                .take(12)
            return "${prefix}_${slug}_${digest}.zip"
        }

        internal fun manifestContentDiffers(a: BackupManifest, b: BackupManifest): Boolean {
            return a.folders.toSet() != b.folders.toSet() ||
                a.notebooks.toSet() != b.notebooks.toSet() ||
                a.chapters.toSet() != b.chapters.toSet() ||
                a.pages.toSet() != b.pages.toSet() ||
                a.tags.toSet() != b.tags.toSet() ||
                a.tagLinks.toSet() != b.tagLinks.toSet() ||
                a.bookmarks.toSet() != b.bookmarks.toSet() ||
                a.ftsEntries.toSet() != b.ftsEntries.toSet() ||
                a.tombstones.toSet() != b.tombstones.toSet()
        }
    }
}
