package hu.bdev.stylusnote.data.export

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
const val DRIVE_FULL_SCOPE = "https://www.googleapis.com/auth/drive"
const val CLOUD_BACKUP_FOLDER_NAME = "StylusNote Backups"
const val WEBDAV_BACKUP_SUBDIR = "StylusNoteBackups"
const val REMOTE_MANIFEST_FILE_NAME = "manifest_v2.bin"
const val REMOTE_INDEX_FILE_NAME = "sync-index.json"

data class RemoteAssetEntry(
    val blobName: String,
    val sha256: String
)

data class RemoteSyncIndex(
    val formatVersion: Int = 2,
    val updatedAtMs: Long = 0L,
    val manifestFileName: String = REMOTE_MANIFEST_FILE_NAME,
    val manifestSha256: String = "",
    val assets: Map<String, RemoteAssetEntry> = emptyMap()
) {
    fun encodeToUtf8Bytes(): ByteArray = buildString {
        append('{')
        append("\"formatVersion\":").append(formatVersion).append(',')
        append("\"updatedAtMs\":").append(updatedAtMs).append(',')
        append("\"manifestFileName\":").append(quoteJson(manifestFileName)).append(',')
        append("\"manifestSha256\":").append(quoteJson(manifestSha256)).append(',')
        append("\"assets\":{")
        val sorted = TreeMap(assets)
        var first = true
        for ((key, entry) in sorted) {
            if (!first) append(',')
            first = false
            append(quoteJson(key)).append(":{")
            append("\"blobName\":").append(quoteJson(entry.blobName)).append(',')
            append("\"sha256\":").append(quoteJson(entry.sha256))
            append('}')
        }
        append("}}")
    }.toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decodeFromUtf8Bytes(bytes: ByteArray): RemoteSyncIndex {
            val root = MiniJsonObjectParser(bytes.toString(StandardCharsets.UTF_8)).parseObject()
            val formatVersion = (root["formatVersion"] as? Number)?.toInt() ?: 2
            val updatedAtMs = (root["updatedAtMs"] as? Number)?.toLong() ?: 0L
            val manifestFileName = (root["manifestFileName"] as? String) ?: REMOTE_MANIFEST_FILE_NAME
            val manifestSha256 = (root["manifestSha256"] as? String) ?: ""
            @Suppress("UNCHECKED_CAST")
            val rawAssets = root["assets"] as? Map<String, Any?> ?: emptyMap()
            val assets = LinkedHashMap<String, RemoteAssetEntry>(rawAssets.size)
            for ((assetKey, value) in rawAssets) {
                @Suppress("UNCHECKED_CAST")
                val obj = value as? Map<String, Any?> ?: continue
                val blob = obj["blobName"] as? String ?: continue
                val sha = obj["sha256"] as? String ?: continue
                assets[assetKey] = RemoteAssetEntry(blobName = blob, sha256 = sha)
            }
            return RemoteSyncIndex(
                formatVersion = formatVersion,
                updatedAtMs = updatedAtMs,
                manifestFileName = manifestFileName,
                manifestSha256 = manifestSha256,
                assets = assets
            )
        }

        internal fun quoteJson(value: String): String = buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append("\\u%04x".format(ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }
    }
}

internal class MiniJsonObjectParser(private val src: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWs()
        expect('{')
        skipWs()
        val result = LinkedHashMap<String, Any?>()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            result[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return result
                }
                else -> throw IOException("Hibás sync-index.json szintaxis ($pos)")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        skipWs()
        expect('[')
        skipWs()
        val result = ArrayList<Any?>()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            skipWs()
            result.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return result
                }
                else -> throw IOException("Hibás JSON tömb szintaxis ($pos)")
            }
        }
    }

    private fun parseValue(): Any? {
        skipWs()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                pos += 4
                true
            }
            'f' -> {
                pos += 5
                false
            }
            'n' -> {
                pos += 4
                null
            }
            '-', in '0'..'9' -> parseLongOrDouble()
            else -> throw IOException("Váratlan karakter sync-index.json-ban: '$ch'")
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw IOException("Lezáratlan escape karakter")
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(pos, (pos + 4).coerceAtMost(src.length))
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IOException("Lezáratlan string sync-index.json-ban")
    }

    private fun parseLongOrDouble(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        val isFloat = pos < src.length && (src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E')
        if (isFloat) {
            while (pos < src.length && src[pos] in "0123456789.eE+-") pos++
            return src.substring(start, pos).toDouble()
        }
        return src.substring(start, pos).toLong()
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char =
        if (pos < src.length) src[pos] else '\u0000'

    private fun expect(expected: Char) {
        if (pos >= src.length || src[pos] != expected) {
            throw IOException("Elvárt '$expected' karakter a sync-index.json $pos pozícióban")
        }
        pos++
    }
}

class CloudAuthRequiredException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class CloudSyncRemoteException(
    message: String,
    val httpCode: Int = -1,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Abstract remote storage backend for StylusNote incremental backup blobs (`manifest_v2.bin`,
 * `sync-index.json`, and flat per-asset delta ZIP blobs).
 */
interface CloudSyncRemoteClient {
    suspend fun readFile(remoteName: String): ByteArray?
    suspend fun writeFile(remoteName: String, bytes: ByteArray, contentType: String)
    suspend fun deleteFile(remoteName: String)
    suspend fun listFiles(): List<String>
}

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class NeedsResolution(val pendingIntent: PendingIntent) : DriveAuthOutcome
}

object GoogleDriveAuthorizationHelper {
    suspend fun authorize(context: Context): DriveAuthOutcome {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FULL_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(context)
            .authorize(request)
            .awaitGmsTask()
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: throw CloudAuthRequiredException("Google Drive engedélyezés szükséges.")
            return DriveAuthOutcome.NeedsResolution(pendingIntent)
        }
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            throw CloudAuthRequiredException("Üres Google Drive hozzáférési token érkezett.")
        }
        return DriveAuthOutcome.Authorized(token)
    }

    suspend fun requireSilentAccessToken(context: Context): String {
        return when (val outcome = authorize(context)) {
            is DriveAuthOutcome.Authorized -> outcome.accessToken
            is DriveAuthOutcome.NeedsResolution -> throw CloudAuthRequiredException(
                "A Google Drive kapcsolat lejárt vagy újraengedélyezést igényel."
            )
        }
    }

    suspend fun fetchDriveAccountEmail(
        accessToken: String,
        okHttpClient: OkHttpClient = OkHttpClient()
    ): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val user = JSONObject(body).optJSONObject("user") ?: return@use null
                user.optString("emailAddress").takeIf { it.isNotBlank() }
                    ?: user.optString("displayName").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}

/**
 * Google Drive REST v3 client constrained to `drive.file` scope inside a single app-created
 * [CLOUD_BACKUP_FOLDER_NAME] folder.
 */
class GoogleDriveRestClient(
    private val accessTokenProvider: suspend () -> String,
    initialFolderId: String? = null,
    private val onFolderIdResolved: suspend (String) -> Unit = {},
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : CloudSyncRemoteClient {

    private val lookupMutex = Mutex()
    private var resolvedFolderId: String? = initialFolderId?.takeIf { it.isNotBlank() }
    private val fileIdCache = LinkedHashMap<String, String>()

    override suspend fun readFile(remoteName: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()
        val folderId = ensureFolderIdLocked(token)
        val fileId = findFileIdLocked(token, folderId, remoteName) ?: return@withContext null
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> {
                    lookupMutex.withLock { fileIdCache.remove(remoteName) }
                    null
                }
                401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                in 200..299 -> response.body?.bytes() ?: ByteArray(0)
                else -> throw CloudSyncRemoteException(
                    "Google Drive letöltés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun writeFile(
        remoteName: String,
        bytes: ByteArray,
        contentType: String
    ): Unit = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()
        val folderId = ensureFolderIdLocked(token)
        val existingId = findFileIdLocked(token, folderId, remoteName)
        if (existingId != null) {
            val patchRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media")
                .header("Authorization", "Bearer $token")
                .patch(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            okHttpClient.newCall(patchRequest).execute().use { response ->
                when (response.code) {
                    in 200..299 -> return@withContext
                    401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                    404 -> lookupMutex.withLock { fileIdCache.remove(remoteName) }
                    else -> throw CloudSyncRemoteException(
                        "Google Drive frissítés sikertelen ($remoteName): HTTP ${response.code}",
                        httpCode = response.code
                    )
                }
            }
        }

        val metadataJson = JSONObject()
            .put("name", remoteName)
            .put("parents", org.json.JSONArray().put(folderId))
            .toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(bytes.toRequestBody(contentType.toMediaType()))
            .build()

        val createRequest = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()

        okHttpClient.newCall(createRequest).execute().use { response ->
            when (response.code) {
                401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                in 200..299 -> {
                    val createdId = response.body?.string()?.let { body ->
                        runCatching { JSONObject(body).optString("id") }.getOrNull()
                    }
                    if (!createdId.isNullOrBlank()) {
                        lookupMutex.withLock { fileIdCache[remoteName] = createdId }
                    }
                }
                else -> throw CloudSyncRemoteException(
                    "Google Drive feltöltés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun deleteFile(remoteName: String): Unit = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()
        val folderId = ensureFolderIdLocked(token)
        val fileId = findFileIdLocked(token, folderId, remoteName) ?: return@withContext
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                404, in 200..299 -> lookupMutex.withLock { fileIdCache.remove(remoteName) }
                else -> throw CloudSyncRemoteException(
                    "Google Drive törlés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()
        val folderId = ensureFolderIdLocked(token)
        val result = ArrayList<String>()
        var pageToken: String? = null
        do {
            val urlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${escapeDriveQuery(folderId)}' in parents and trashed = false")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,name)")
                .addQueryParameter("pageSize", "100")
            if (!pageToken.isNullOrBlank()) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }
            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                }
                if (!response.isSuccessful) {
                    throw CloudSyncRemoteException(
                        "Google Drive fájllistázás sikertelen: HTTP ${response.code}",
                        httpCode = response.code
                    )
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val files = json.optJSONArray("files")
                if (files != null) {
                    for (i in 0 until files.length()) {
                        val item = files.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val name = item.optString("name")
                        if (name.isNotBlank()) {
                            result.add(name)
                            if (id.isNotBlank()) {
                                lookupMutex.withLock { fileIdCache[name] = id }
                            }
                        }
                    }
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
        } while (pageToken != null)
        result
    }

    private suspend fun ensureFolderIdLocked(token: String): String = lookupMutex.withLock {
        val current = resolvedFolderId
        if (!current.isNullOrBlank() && verifyFolderActive(token, current)) {
            return@withLock current
        }

        val query = "name = '${escapeDriveQuery(CLOUD_BACKUP_FOLDER_NAME)}' and " +
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val searchUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "5")
            .build()
        val searchRequest = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        var foundId: String? = null
        okHttpClient.newCall(searchRequest).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) {
                throw CloudSyncRemoteException(
                    "Google Drive mappa keresése sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files")
            if (files != null && files.length() > 0) {
                foundId = files.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }

        val finalId = foundId ?: createBackupFolder(token)
        resolvedFolderId = finalId
        onFolderIdResolved(finalId)
        finalId
    }

    private fun verifyFolderActive(token: String, folderId: String): Boolean {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$folderId?fields=id,trashed")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        okHttpClient.newCall(req).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            return !JSONObject(body).optBoolean("trashed", false)
        }
    }

    private fun createBackupFolder(token: String): String {
        val payload = JSONObject()
            .put("name", CLOUD_BACKUP_FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) {
                throw CloudSyncRemoteException(
                    "Google Drive biztonsági mappa létrehozása sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val id = JSONObject(response.body?.string().orEmpty()).optString("id")
            if (id.isBlank()) {
                throw CloudSyncRemoteException("Google Drive nem adott vissza mappa-azonosítót.")
            }
            return id
        }
    }

    private suspend fun findFileIdLocked(
        token: String,
        folderId: String,
        remoteName: String
    ): String? = lookupMutex.withLock {
        fileIdCache[remoteName]?.let { return@withLock it }
        val q = "name = '${escapeDriveQuery(remoteName)}' and " +
            "'${escapeDriveQuery(folderId)}' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) {
                throw CloudSyncRemoteException(
                    "Google Drive fájlellenőrzés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files")
            val found = files?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            if (found != null) {
                fileIdCache[remoteName] = found
            }
            return@withLock found
        }
    }

    private fun escapeDriveQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}

data class GoogleDriveFolderItem(
    val id: String,
    val name: String,
    val parentId: String? = null
)

/**
 * Full-Drive REST v3 helper (`drive` scope) powering interactive category folder browsing,
 * readable per-category PDF upsert/overwrite without creating duplicates, and folder trashing.
 */
class GoogleDriveFolderClient(
    private val accessTokenProvider: suspend () -> String,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    suspend fun listSubfolders(parentFolderId: String = "root"): List<GoogleDriveFolderItem> =
        withContext(Dispatchers.IO) {
            val token = accessTokenProvider()
            val folders = ArrayList<GoogleDriveFolderItem>()
            var pageToken: String? = null
            do {
                val safeParent = escapeDriveQuery(parentFolderId)
                val q = "'$safeParent' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
                val urlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                    .addQueryParameter("q", q)
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("fields", "nextPageToken,files(id,name,parents)")
                    .addQueryParameter("orderBy", "name_natural")
                    .addQueryParameter("pageSize", "100")
                if (!pageToken.isNullOrBlank()) {
                    urlBuilder.addQueryParameter("pageToken", pageToken)
                }
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                    }
                    if (!response.isSuccessful) {
                        throw CloudSyncRemoteException(
                            "Google Drive mappák listázása sikertelen: HTTP ${response.code}",
                            httpCode = response.code
                        )
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val files = json.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val obj = files.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val name = obj.optString("name")
                            if (id.isBlank() || name.isBlank()) continue
                            val parent = obj.optJSONArray("parents")?.optString(0)?.takeIf { it.isNotBlank() }
                            folders.add(GoogleDriveFolderItem(id = id, name = name, parentId = parent))
                        }
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                }
            } while (pageToken != null)
            folders
        }

    suspend fun createSubfolder(
        parentFolderId: String = "root",
        folderName: String
    ): GoogleDriveFolderItem = withContext(Dispatchers.IO) {
        val cleanName = folderName.trim().ifEmpty { "StylusNote Mappa" }
        val token = accessTokenProvider()
        val payload = JSONObject()
            .put("name", cleanName)
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", org.json.JSONArray().put(parentFolderId))
            .toString()
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id,name,parents")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) {
                throw CloudSyncRemoteException(
                    "Google Drive mappa létrehozása sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val obj = JSONObject(response.body?.string().orEmpty())
            val id = obj.optString("id")
            if (id.isBlank()) {
                throw CloudSyncRemoteException("Google Drive nem adott vissza mappa-azonosítót.")
            }
            GoogleDriveFolderItem(
                id = id,
                name = obj.optString("name", cleanName),
                parentId = parentFolderId
            )
        }
    }

    /**
     * Overwrites & renames [existingFileId] in-place inside [folderId] via multipart `PATCH` so
     * updated notebooks get the new date suffix without leaving duplicate PDFs; falls back to
     * creating a new PDF when [existingFileId] is null or missing (HTTP 404).
     */
    suspend fun uploadOrReplacePdfInFolder(
        folderId: String,
        existingFileId: String?,
        fileName: String,
        pdfBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()
        if (!existingFileId.isNullOrBlank()) {
            val metaJson = JSONObject().put("name", fileName).toString()
            val patchMultipart = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metaJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(pdfBytes.toRequestBody("application/pdf".toMediaType()))
                .build()
            val patchReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id,name,trashed")
                .header("Authorization", "Bearer $token")
                .patch(patchMultipart)
                .build()
            okHttpClient.newCall(patchReq).execute().use { response ->
                when (response.code) {
                    in 200..299 -> {
                        val parsed = JSONObject(response.body?.string().orEmpty())
                        if (!parsed.optBoolean("trashed", false)) {
                            return@withContext parsed.optString("id").takeIf { it.isNotBlank() } ?: existingFileId
                        }
                    }
                    401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                    404 -> Unit // Create a fresh PDF below
                    else -> throw CloudSyncRemoteException(
                        "Google Drive PDF frissítés sikertelen ($fileName): HTTP ${response.code}",
                        httpCode = response.code
                    )
                }
            }
        }

        val createMeta = JSONObject()
            .put("name", fileName)
            .put("parents", org.json.JSONArray().put(folderId))
            .toString()
        val createMultipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(createMeta.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(pdfBytes.toRequestBody("application/pdf".toMediaType()))
            .build()
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name")
            .header("Authorization", "Bearer $token")
            .post(createMultipart)
            .build()
        okHttpClient.newCall(createReq).execute().use { response ->
            if (response.code == 401) {
                throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
            }
            if (!response.isSuccessful) {
                throw CloudSyncRemoteException(
                    "Google Drive PDF feltöltés sikertelen ($fileName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val id = JSONObject(response.body?.string().orEmpty()).optString("id")
            if (id.isBlank()) {
                throw CloudSyncRemoteException("Google Drive nem adott vissza fájl-azonosítót a PDF-hez.")
            }
            id
        }
    }

    /** Moves [folderId] to Google Drive Trash (HTTP 404 treated as already deleted). */
    suspend fun trashFolder(folderId: String): Unit = withContext(Dispatchers.IO) {
        if (folderId.isBlank()) return@withContext
        val token = accessTokenProvider()
        val payload = JSONObject().put("trashed", true).toString()
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$folderId")
            .header("Authorization", "Bearer $token")
            .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw CloudAuthRequiredException("Google Drive jogosultság lejárt (HTTP 401).")
                404, in 200..299 -> Unit
                else -> throw CloudSyncRemoteException(
                    "Google Drive mappa törlése sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    private fun escapeDriveQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}

/**
 * WebDAV (Nextcloud / OwnCloud / Synology / Apache mod_dav) client storing flat delta blobs under
 * `<baseUrl>/StylusNoteBackups/` (or directly in `<baseUrl>` if it already ends with that segment).
 */
class WebDavRemoteClient(
    baseUrl: String,
    username: String,
    password: String,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : CloudSyncRemoteClient {

    private val authHeader: String = Credentials.basic(username, password, StandardCharsets.UTF_8)
    private val collectionBaseUrl: String = normalizeCollectionUrl(baseUrl)
    private val collectionMutex = Mutex()
    private var collectionEnsured = false

    override suspend fun readFile(remoteName: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(fileUrl(remoteName))
            .header("Authorization", authHeader)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> null
                401, 403 -> throw CloudAuthRequiredException(
                    "WebDAV hitelesítés sikertelen (HTTP ${response.code})."
                )
                in 200..299 -> response.body?.bytes() ?: ByteArray(0)
                else -> throw CloudSyncRemoteException(
                    "WebDAV letöltés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun writeFile(
        remoteName: String,
        bytes: ByteArray,
        contentType: String
    ): Unit = withContext(Dispatchers.IO) {
        ensureCollectionExists()
        val request = Request.Builder()
            .url(fileUrl(remoteName))
            .header("Authorization", authHeader)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw CloudAuthRequiredException(
                    "WebDAV hitelesítés sikertelen (HTTP ${response.code})."
                )
                in 200..299 -> Unit
                else -> throw CloudSyncRemoteException(
                    "WebDAV feltöltés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun deleteFile(remoteName: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(fileUrl(remoteName))
            .header("Authorization", authHeader)
            .delete()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw CloudAuthRequiredException(
                    "WebDAV hitelesítés sikertelen (HTTP ${response.code})."
                )
                404, in 200..299 -> Unit
                else -> throw CloudSyncRemoteException(
                    "WebDAV törlés sikertelen ($remoteName): HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$collectionBaseUrl/")
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", ByteArray(0).toRequestBody("application/xml".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> return@withContext emptyList()
                401, 403 -> throw CloudAuthRequiredException(
                    "WebDAV hitelesítés sikertelen (HTTP ${response.code})."
                )
                !in 200..299 -> throw CloudSyncRemoteException(
                    "WebDAV listázás sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
            val xml = response.body?.string().orEmpty()
            parsePropfindChildNames(xml)
        }
    }

    private suspend fun ensureCollectionExists() = collectionMutex.withLock {
        if (collectionEnsured) return@withLock
        val request = Request.Builder()
            .url("$collectionBaseUrl/")
            .header("Authorization", authHeader)
            .method("MKCOL", null)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw CloudAuthRequiredException(
                    "WebDAV hitelesítés sikertelen (HTTP ${response.code})."
                )
                in 200..299, 405 -> collectionEnsured = true
                else -> throw CloudSyncRemoteException(
                    "WebDAV mappa létrehozása sikertelen: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        }
    }

    private fun fileUrl(remoteName: String): String {
        val encodedName = URLEncoder.encode(remoteName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "$collectionBaseUrl/$encodedName"
    }

    companion object {
        internal fun normalizeCollectionUrl(rawBaseUrl: String): String {
            val trimmed = rawBaseUrl.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "A WebDAV URL-nek http:// vagy https:// előtaggal kell kezdődnie."
            }
            return if (trimmed.endsWith("/$WEBDAV_BACKUP_SUBDIR", ignoreCase = true)) {
                trimmed
            } else {
                "$trimmed/$WEBDAV_BACKUP_SUBDIR"
            }
        }

        internal fun parsePropfindChildNames(xml: String): List<String> {
            val regex = Regex("""<(?:\w+:)?href[^>]*>([^<]+)</(?:\w+:)?href>""")
            return regex.findAll(xml)
                .mapNotNull { match ->
                    val rawHref = match.groupValues[1].trim()
                    if (rawHref.endsWith("/")) return@mapNotNull null
                    val lastSeg = rawHref.substringAfterLast('/')
                    if (lastSeg.isBlank() || lastSeg == WEBDAV_BACKUP_SUBDIR) {
                        null
                    } else {
                        URLDecoder.decode(lastSeg, StandardCharsets.UTF_8.name())
                    }
                }
                .distinct()
                .toList()
        }
    }
}

internal suspend fun <T> Task<T>.awaitGmsTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}
