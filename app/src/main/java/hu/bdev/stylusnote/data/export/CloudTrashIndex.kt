package hu.bdev.stylusnote.data.export

import java.nio.charset.StandardCharsets
import kotlin.math.ceil

const val REMOTE_TRASH_FILE_NAME = "cloud-trash.json"
const val TRASH_RETENTION_DAYS = 7L
const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L

/**
 * Retained cloud backup snapshot of a locally deleted notebook kept on Google Drive / WebDAV
 * for [TRASH_RETENTION_DAYS] days so accidental deletions can be restored without interfering with
 * normal bidirectional sync.
 */
data class TrashedNotebookRecord(
    val notebookId: String,
    val title: String,
    val folderId: String?,
    val pageCount: Int,
    val deletedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val manifestSnapshotBase64: String,
    val assetEntries: Map<String, RemoteAssetEntry> = emptyMap()
) {
    fun isExpiredAt(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    fun remainingMs(nowEpochMs: Long): Long = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun remainingDaysRoundedUp(nowEpochMs: Long): Int {
        val rem = remainingMs(nowEpochMs)
        if (rem <= 0L) return 0
        return ceil(rem.toDouble() / (24.0 * 60.0 * 60.0 * 1000.0)).toInt().coerceAtLeast(1)
    }

    fun formatRemainingTimeHu(nowEpochMs: Long): String {
        val rem = remainingMs(nowEpochMs)
        if (rem <= 0L) return "Lejárt"
        val totalHours = ceil(rem.toDouble() / (60.0 * 60.0 * 1000.0)).toLong().coerceAtLeast(1L)
        val days = totalHours / 24L
        val hours = totalHours % 24L
        return when {
            days > 0L && hours > 0L -> "$days nap $hours óra múlva törlődik"
            days > 0L -> "$days nap múlva törlődik"
            else -> "$totalHours óra múlva törlődik"
        }
    }
}

/**
 * Remote JSON ledger (`cloud-trash.json`) tracking soft-deleted notebooks and their protected
 * asset blobs during the 7-day grace window.
 */
data class RemoteTrashIndex(
    val formatVersion: Int = 1,
    val updatedAtMs: Long = 0L,
    val entries: List<TrashedNotebookRecord> = emptyList()
) {
    fun protectedAssetKeys(nowEpochMs: Long): Set<String> {
        val keys = LinkedHashSet<String>()
        for (entry in entries) {
            if (!entry.isExpiredAt(nowEpochMs)) {
                keys.addAll(entry.assetEntries.keys)
            }
        }
        return keys
    }

    fun encodeToUtf8Bytes(): ByteArray = buildString {
        append('{')
        append("\"formatVersion\":").append(formatVersion).append(',')
        append("\"updatedAtMs\":").append(updatedAtMs).append(',')
        append("\"entries\":[")
        val sortedEntries = entries.sortedByDescending { it.deletedAtEpochMs }
        sortedEntries.forEachIndexed { idx, rec ->
            if (idx > 0) append(',')
            append('{')
            append("\"notebookId\":").append(RemoteSyncIndex.quoteJson(rec.notebookId)).append(',')
            append("\"title\":").append(RemoteSyncIndex.quoteJson(rec.title)).append(',')
            append("\"folderId\":")
            if (rec.folderId != null) append(RemoteSyncIndex.quoteJson(rec.folderId)) else append("null")
            append(',')
            append("\"pageCount\":").append(rec.pageCount).append(',')
            append("\"deletedAtEpochMs\":").append(rec.deletedAtEpochMs).append(',')
            append("\"expiresAtEpochMs\":").append(rec.expiresAtEpochMs).append(',')
            append("\"manifestSnapshotBase64\":").append(RemoteSyncIndex.quoteJson(rec.manifestSnapshotBase64)).append(',')
            append("\"assetEntries\":{")
            var firstAsset = true
            for ((assetKey, asset) in rec.assetEntries.toSortedMap()) {
                if (!firstAsset) append(',')
                firstAsset = false
                append(RemoteSyncIndex.quoteJson(assetKey)).append(":{")
                append("\"blobName\":").append(RemoteSyncIndex.quoteJson(asset.blobName)).append(',')
                append("\"sha256\":").append(RemoteSyncIndex.quoteJson(asset.sha256))
                append('}')
            }
            append("}}")
        }
        append("]}")
    }.toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decodeFromUtf8Bytes(bytes: ByteArray): RemoteTrashIndex {
            val root = MiniJsonObjectParser(bytes.toString(StandardCharsets.UTF_8)).parseObject()
            val formatVersion = (root["formatVersion"] as? Number)?.toInt() ?: 1
            val updatedAtMs = (root["updatedAtMs"] as? Number)?.toLong() ?: 0L
            val rawEntries = root["entries"] as? List<*> ?: emptyList<Any?>()
            val list = ArrayList<TrashedNotebookRecord>(rawEntries.size)
            for (item in rawEntries) {
                @Suppress("UNCHECKED_CAST")
                val obj = item as? Map<String, Any?> ?: continue
                val nbId = obj["notebookId"] as? String ?: continue
                val snapshotB64 = obj["manifestSnapshotBase64"] as? String ?: continue
                if (nbId.isBlank() || snapshotB64.isBlank()) continue
                val folderId = (obj["folderId"] as? String)?.takeIf { it.isNotBlank() }
                @Suppress("UNCHECKED_CAST")
                val rawAssets = obj["assetEntries"] as? Map<String, Any?> ?: emptyMap()
                val assets = LinkedHashMap<String, RemoteAssetEntry>()
                for ((assetKey, assetVal) in rawAssets) {
                    @Suppress("UNCHECKED_CAST")
                    val aObj = assetVal as? Map<String, Any?> ?: continue
                    val blob = aObj["blobName"] as? String ?: continue
                    val sha = aObj["sha256"] as? String ?: continue
                    if (blob.isNotBlank() && sha.isNotBlank()) {
                        assets[assetKey] = RemoteAssetEntry(blobName = blob, sha256 = sha)
                    }
                }
                list.add(
                    TrashedNotebookRecord(
                        notebookId = nbId,
                        title = (obj["title"] as? String) ?: "Jegyzetfüzet",
                        folderId = folderId,
                        pageCount = (obj["pageCount"] as? Number)?.toInt() ?: 0,
                        deletedAtEpochMs = (obj["deletedAtEpochMs"] as? Number)?.toLong() ?: 0L,
                        expiresAtEpochMs = (obj["expiresAtEpochMs"] as? Number)?.toLong() ?: 0L,
                        manifestSnapshotBase64 = snapshotB64,
                        assetEntries = assets
                    )
                )
            }
            return RemoteTrashIndex(
                formatVersion = formatVersion,
                updatedAtMs = updatedAtMs,
                entries = list
            )
        }
    }
}
