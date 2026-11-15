package hu.bdev.stylusnote.data.export

import androidx.room.withTransaction
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
        val activeAssetKeys = collectActiveAssetKeys(mergedManifest)
        val remoteAssets = remoteIndex?.assets ?: emptyMap()
        val localSyncedMap = manifestStore.getAllSyncedAssets().associateBy { it.assetPath }

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
            } else {
                remoteClient.deleteFile(remoteEntry.blobName)
                manifestStore.deleteSyncedAsset(remoteKey)
                deletedRemoteAssets++
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
