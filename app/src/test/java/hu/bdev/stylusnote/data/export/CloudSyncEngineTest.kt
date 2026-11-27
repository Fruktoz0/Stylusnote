package hu.bdev.stylusnote.data.export

import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.CloudSyncedAssetEntity
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.PageTextFtsEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CoverTexture
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CloudSyncEngineTest {

    private lateinit var deviceADir: File
    private lateinit var deviceBDir: File

    @Before
    fun setUp() {
        deviceADir = Files.createTempDirectory("snot_sync_dev_a_").toFile()
        deviceBDir = Files.createTempDirectory("snot_sync_dev_b_").toFile()
    }

    @After
    fun tearDown() {
        deviceADir.deleteRecursively()
        deviceBDir.deleteRecursively()
    }

    @Test
    fun twoDeviceIncrementalSyncPropagatesDeltasTombstonesAndReplacesUntouchedStarter() = runBlocking {
        val remote = CountingRemoteClient()
        val storeA = InMemorySyncManifestStore(deviceADir)
        val engineA = CloudSyncEngine(deviceADir, storeA, BackupManager(deviceADir))

        val nbId = "nb-study"
        val coverRel = "covers/cover_study.jpg"
        File(deviceADir, coverRel).apply {
            parentFile?.mkdirs()
            writeBytes("fake-jpeg-cover".toByteArray())
        }
        val pdfId = "pdf-slides-01"
        File(deviceADir, "pdfs/$pdfId.pdf").apply {
            parentFile?.mkdirs()
            writeBytes("%PDF-1.4\n%test\n".toByteArray())
        }

        writeSingleStroke(File(deviceADir, "pages/p1"), sampleStroke("s1", 10L))
        File(deviceADir, "pages/p1/images").mkdirs()
        File(deviceADir, "pages/p1/images/img1.png").writeBytes("png-bytes".toByteArray())

        writeSingleStroke(File(deviceADir, "pages/p2"), sampleStroke("s2", 20L))

        val notebook = NotebookEntity(
            id = nbId,
            folderId = null,
            title = "Analízis II",
            cover = NotebookCover(texture = CoverTexture.LEATHER, customImageRelativePath = coverRel),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_100L
        )
        val page1 = PageEntity(
            id = "p1",
            notebookId = nbId,
            chapterId = null,
            pageIndexInNotebook = 0,
            title = "Előadás 1",
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_100L
        )
        val page2 = PageEntity(
            id = "p2",
            notebookId = nbId,
            chapterId = null,
            pageIndexInNotebook = 1,
            title = "Előadás 2",
            pdfAssetId = pdfId,
            pdfPageIndex = 0,
            createdAtEpochMs = 1_050L,
            updatedAtEpochMs = 1_150L
        )
        storeA.manifest = BackupManifest(
            createdAtEpochMs = 1_150L,
            notebooks = listOf(notebook),
            pages = listOf(page1, page2),
            ftsEntries = listOf(PageTextFtsEntity("p1", nbId, "Integrálszámítás", "integralszamitas"))
        )

        val firstSyncA = engineA.syncNow(remote, nowEpochMs = 1_200L)
        assertEquals(4, firstSyncA.uploadedAssets) // pages/p1, pages/p2, pdfs/pdf-slides-01.pdf, covers/...
        assertEquals(0, firstSyncA.downloadedAssets)
        assertTrue(firstSyncA.uploadedManifest)

        // Repeating sync immediately uploads zero files and zero manifest writes.
        val writesBeforeNoOp = remote.writeCount.get()
        val noOpSyncA = engineA.syncNow(remote, nowEpochMs = 1_250L)
        assertEquals(0, noOpSyncA.uploadedAssets)
        assertEquals(0, noOpSyncA.downloadedAssets)
        assertFalse(noOpSyncA.uploadedManifest)
        assertEquals(writesBeforeNoOp, remote.writeCount.get())

        // Device B starts with auto-seeded untouched starter placeholder ("Első jegyzetfüzet").
        val storeB = InMemorySyncManifestStore(deviceBDir)
        storeB.manifest = BackupManifest(
            createdAtEpochMs = 2_000L,
            notebooks = listOf(
                NotebookEntity(
                    id = "starter-nb",
                    folderId = null,
                    title = "Első jegyzetfüzet",
                    createdAtEpochMs = 2_000L,
                    updatedAtEpochMs = 2_000L
                )
            ),
            pages = listOf(
                PageEntity(
                    id = "starter-p1",
                    notebookId = "starter-nb",
                    chapterId = null,
                    pageIndexInNotebook = 0,
                    createdAtEpochMs = 2_000L,
                    updatedAtEpochMs = 2_000L
                )
            )
        )
        val engineB = CloudSyncEngine(deviceBDir, storeB, BackupManager(deviceBDir))

        val summary = engineB.probeRemoteBackup(remote)
        assertTrue(summary.exists)
        assertEquals(1, summary.notebookCount)
        assertEquals(2, summary.pageCount)
        assertEquals(4, summary.assetCount)

        val syncB = engineB.syncNow(remote, nowEpochMs = 2_100L)
        assertTrue(syncB.replacedStarterWorkspace)
        assertEquals(4, syncB.downloadedAssets)
        assertEquals(0, syncB.uploadedAssets)
        assertEquals(listOf(nbId), storeB.manifest.notebooks.map { it.id })
        assertTrue(File(deviceBDir, "pages/p1/images/img1.png").exists())
        assertTrue(File(deviceBDir, "pdfs/$pdfId.pdf").exists())
        assertTrue(File(deviceBDir, coverRel).exists())
        assertEquals("Előadás 2", storeB.manifest.pages.first { it.id == "p2" }.title)

        // Device B edits only page p2 strokes and tombstones page p1.
        writeSingleStroke(File(deviceBDir, "pages/p2"), sampleStroke("s3", 30L))
        storeB.manifest = storeB.manifest.copy(
            pages = listOf(
                storeB.manifest.pages.first { it.id == "p2" }.copy(updatedAtEpochMs = 3_000L)
            ),
            tombstones = storeB.manifest.tombstones + SyncTombstoneEntity(
                compositeKey = SyncTombstoneEntity.key(SyncTombstoneEntity.TYPE_PAGE, "p1"),
                entityType = SyncTombstoneEntity.TYPE_PAGE,
                entityId = "p1",
                deletedAtEpochMs = 3_000L
            )
        )

        val secondSyncB = engineB.syncNow(remote, nowEpochMs = 3_100L)
        assertEquals(1, secondSyncB.uploadedAssets) // Only pages/p2 uploaded!
        assertEquals(1, secondSyncB.deletedRemoteAssets) // pages/p1 remote blob deleted!

        // Device A syncs and receives p2 stroke update + deletes p1 locally.
        val secondSyncA = engineA.syncNow(remote, nowEpochMs = 3_200L)
        assertEquals(1, secondSyncA.downloadedAssets)
        assertEquals(1, secondSyncA.deletedLocalPages)
        assertFalse(File(deviceADir, "pages/p1").exists())
        assertEquals(listOf("p2"), storeA.manifest.pages.map { it.id })
        val mergedStrokesOnA = readStrokes(File(deviceADir, "pages/p2"))
        assertEquals(listOf("s2", "s3"), mergedStrokesOnA.map { it.id })
    }

    @Test
    fun webDavRemoteClientAndEngineSyncOverHttpRoundTrip() = runBlocking {
        MiniWebDavTcpServer(expectedUser = "bela", expectedPass = "titok42").use { server ->
            val badClient = WebDavRemoteClient(
                baseUrl = "http://127.0.0.1:${server.port}/dav",
                username = "bela",
                password = "wrong"
            )
            val authFailed = runCatching { badClient.listFiles() }.exceptionOrNull()
            assertTrue(authFailed is CloudAuthRequiredException)

            val client = WebDavRemoteClient(
                baseUrl = "http://127.0.0.1:${server.port}/dav",
                username = "bela",
                password = "titok42"
            )
            assertNull(client.readFile("missing.bin"))

            val storeA = InMemorySyncManifestStore(deviceADir)
            val engineA = CloudSyncEngine(deviceADir, storeA, BackupManager(deviceADir))
            writeSingleStroke(File(deviceADir, "pages/wd-p1"), sampleStroke("wd-s1", 5L))
            storeA.manifest = BackupManifest(
                createdAtEpochMs = 500L,
                notebooks = listOf(
                    NotebookEntity(
                        id = "wd-nb",
                        folderId = null,
                        title = "NAS Jegyzet",
                        createdAtEpochMs = 400L,
                        updatedAtEpochMs = 500L
                    )
                ),
                pages = listOf(
                    PageEntity(
                        id = "wd-p1",
                        notebookId = "wd-nb",
                        chapterId = null,
                        pageIndexInNotebook = 0,
                        title = "Bevezető",
                        createdAtEpochMs = 400L,
                        updatedAtEpochMs = 500L
                    )
                )
            )
            val push = engineA.syncNow(client, nowEpochMs = 600L)
            assertEquals(1, push.uploadedAssets)
            val listed = client.listFiles()
            assertTrue(listed.contains(REMOTE_MANIFEST_FILE_NAME))
            assertTrue(listed.contains(REMOTE_INDEX_FILE_NAME))

            val storeB = InMemorySyncManifestStore(deviceBDir)
            val engineB = CloudSyncEngine(deviceBDir, storeB, BackupManager(deviceBDir))
            val restore = engineB.syncNow(
                remoteClient = client,
                mode = SyncExecutionMode.RESTORE_FROM_CLOUD,
                nowEpochMs = 700L
            )
            assertEquals(1, restore.downloadedAssets)
            assertEquals("NAS Jegyzet", storeB.manifest.notebooks.single().title)
            val restoredStrokes = readStrokes(File(deviceBDir, "pages/wd-p1"))
            assertEquals(listOf("wd-s1"), restoredStrokes.map { it.id })
        }
    }

    @Test
    fun cloudSyncSettingsDerivesRuntimeStatusAccurately() {
        assertEquals(CloudSyncRuntimeStatus.DISABLED, CloudSyncSettings().deriveRuntimeStatus(false))
        val configuredDrive = CloudSyncSettings(
            providerType = CloudProviderType.GOOGLE_DRIVE,
            googleDriveAccountEmail = "teszt@gmail.com"
        )
        assertEquals(CloudSyncRuntimeStatus.IDLE_SYNCED, configuredDrive.deriveRuntimeStatus(false))
        assertEquals(CloudSyncRuntimeStatus.SYNCING, configuredDrive.deriveRuntimeStatus(true))
        assertEquals(
            CloudSyncRuntimeStatus.PENDING_CHANGES,
            configuredDrive.copy(hasPendingLocalChanges = true).deriveRuntimeStatus(false)
        )
        assertEquals(
            CloudSyncRuntimeStatus.NEEDS_REAUTH,
            configuredDrive.copy(needsReauth = true, hasPendingLocalChanges = true).deriveRuntimeStatus(false)
        )
        assertEquals(
            CloudSyncRuntimeStatus.ERROR,
            configuredDrive.copy(lastErrorMessage = "Hálózati hiba").deriveRuntimeStatus(false)
        )
    }

    private suspend fun writeSingleStroke(pageDir: File, stroke: PersistedStrokeRecord) {
        val journal = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        journal.openAndRecover()
        journal.appendAddStrokes(listOf(stroke))
        journal.compactNow()
        journal.closeBlocking(compactBeforeClose = false)
    }

    private suspend fun readStrokes(pageDir: File): List<PersistedStrokeRecord> {
        val journal = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        return try {
            journal.openAndRecover().strokes
        } finally {
            journal.closeBlocking(compactBeforeClose = false)
        }
    }

    private fun sampleStroke(id: String, zOrder: Long): PersistedStrokeRecord =
        PersistedStrokeRecord(
            id = id,
            layer = StrokeLayerType.INK,
            zOrder = zOrder,
            brushConfig = BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 2.4f,
                colorArgb = 0xFF1A237E.toInt(),
                pressureCurve = PressureCurve.LINEAR
            ),
            canonicalColorArgb = 0xFF1A237E.toInt(),
            sizePt = 2.4f,
            inkEncodedInputs = null,
            samples = listOf(
                StylusPointSample(0f, 0f, 0L, 0.5f, 0f, 0f, false),
                StylusPointSample(40f, 40f, 15L, 0.6f, 0f, 0f, false)
            ),
            bounds = DocRect(0f, 0f, 40f, 40f)
        )

    private class MiniWebDavTcpServer(
        expectedUser: String,
        expectedPass: String
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort
        private val running = AtomicBoolean(true)
        private val collections = ConcurrentHashMap.newKeySet<String>()
        private val blobs = ConcurrentHashMap<String, ByteArray>()
        private val expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString("$expectedUser:$expectedPass".toByteArray(StandardCharsets.UTF_8))

        private val acceptThread = thread(isDaemon = true, name = "mini-webdav-test") {
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val headerLines = readHeaderLines(input)
                        if (headerLines.isEmpty()) return@use
                        val parts = headerLines[0].split(' ')
                        val method = parts.getOrElse(0) { "GET" }
                        val rawUri = parts.getOrElse(1) { "/" }
                        val headers = LinkedHashMap<String, String>()
                        for (i in 1 until headerLines.size) {
                            val colon = headerLines[i].indexOf(':')
                            if (colon > 0) {
                                headers[headerLines[i].substring(0, colon).trim().lowercase()] =
                                    headerLines[i].substring(colon + 1).trim()
                            }
                        }
                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)

                        val out = s.getOutputStream()
                        if (headers["authorization"] != expectedAuth) {
                            writeHttpResponse(out, 401, "Unauthorized", emptyMap(), ByteArray(0))
                            return@use
                        }

                        val relPath = rawUri.substringBefore('?').removePrefix("/dav").trimStart('/')
                        when (method) {
                            "MKCOL" -> {
                                val col = relPath.trimEnd('/')
                                val code = if (collections.add(col)) 201 else 405
                                writeHttpResponse(out, code, "OK", emptyMap(), ByteArray(0))
                            }
                            "PUT" -> {
                                blobs[relPath] = body
                                writeHttpResponse(out, 201, "Created", emptyMap(), ByteArray(0))
                            }
                            "GET" -> {
                                val data = blobs[relPath]
                                if (data == null) {
                                    writeHttpResponse(out, 404, "Not Found", emptyMap(), ByteArray(0))
                                } else {
                                    writeHttpResponse(
                                        out,
                                        200,
                                        "OK",
                                        mapOf("Content-Type" to "application/octet-stream"),
                                        data
                                    )
                                }
                            }
                            "DELETE" -> {
                                val removed = blobs.remove(relPath)
                                val code = if (removed != null) 204 else 404
                                writeHttpResponse(out, code, "OK", emptyMap(), ByteArray(0))
                            }
                            "PROPFIND" -> {
                                val colPrefix = relPath.trimEnd('/') + "/"
                                val items = blobs.keys.filter { it.startsWith(colPrefix) }
                                val xml = buildString {
                                    append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
                                    for (item in items) {
                                        append("<D:response><D:href>/dav/$item</D:href></D:response>")
                                    }
                                    append("</D:multistatus>")
                                }.toByteArray(StandardCharsets.UTF_8)
                                writeHttpResponse(
                                    out,
                                    207,
                                    "Multi-Status",
                                    mapOf("Content-Type" to "application/xml; charset=utf-8"),
                                    xml
                                )
                            }
                            else -> writeHttpResponse(out, 405, "Method Not Allowed", emptyMap(), ByteArray(0))
                        }
                    }
                }
            }
        }

        private fun readHeaderLines(input: BufferedInputStream): List<String> {
            val baos = ByteArrayOutputStream()
            var prev = -1
            var curr: Int
            var endOfHeaders = false
            val lines = ArrayList<String>()
            while (!endOfHeaders) {
                curr = input.read()
                if (curr == -1) break
                if (curr == '\n'.code) {
                    val rawLine = baos.toByteArray()
                    val cleanLen = if (rawLine.isNotEmpty() && rawLine.last() == '\r'.code.toByte()) {
                        rawLine.size - 1
                    } else {
                        rawLine.size
                    }
                    val line = String(rawLine, 0, cleanLen, StandardCharsets.US_ASCII)
                    baos.reset()
                    if (line.isEmpty()) {
                        endOfHeaders = true
                    } else {
                        lines.add(line)
                    }
                } else {
                    baos.write(curr)
                }
                prev = curr
            }
            @Suppress("UNUSED_VARIABLE")
            val ignored = prev
            return lines
        }

        private fun readExactBytes(input: BufferedInputStream, len: Int): ByteArray {
            val data = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val read = input.read(data, offset, len - offset)
                if (read <= 0) break
                offset += read
            }
            return data
        }

        private fun writeHttpResponse(
            out: java.io.OutputStream,
            code: Int,
            reason: String,
            headers: Map<String, String>,
            body: ByteArray
        ) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Connection: close\r\n")
            for ((k, v) in headers) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
            if (body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            runCatching { acceptThread.join(1_000L) }
        }
    }

    private class CountingRemoteClient : CloudSyncRemoteClient {
        private val files = ConcurrentHashMap<String, ByteArray>()
        val writeCount = AtomicInteger(0)

        override suspend fun readFile(remoteName: String): ByteArray? = files[remoteName]?.copyOf()

        override suspend fun writeFile(remoteName: String, bytes: ByteArray, contentType: String) {
            writeCount.incrementAndGet()
            files[remoteName] = bytes.copyOf()
        }

        override suspend fun deleteFile(remoteName: String) {
            files.remove(remoteName)
        }

        override suspend fun listFiles(): List<String> = files.keys.sorted()
    }

    private class InMemorySyncManifestStore(
        private val rootFilesDir: File
    ) : SyncManifestStore {
        var manifest: BackupManifest = BackupManifest()
        private val syncedAssets = LinkedHashMap<String, CloudSyncedAssetEntity>()

        override suspend fun collectManifest(): BackupManifest = manifest

        override suspend fun mergeRemoteManifest(remote: BackupManifest): ManifestMergeResult {
            val result = BackupManager.mergeManifests(local = manifest, remote = remote)
            manifest = result.mergedManifest
            for (deletedPageId in result.deletedPageIds) {
                val pageDir = File(rootFilesDir, "pages/$deletedPageId")
                if (pageDir.exists()) pageDir.deleteRecursively()
                syncedAssets.remove("pages/$deletedPageId")
            }
            return result
        }

        override suspend fun getAllSyncedAssets(): List<CloudSyncedAssetEntity> =
            syncedAssets.values.toList()

        override suspend fun getSyncedAsset(assetKey: String): CloudSyncedAssetEntity? =
            syncedAssets[assetKey]

        override suspend fun upsertSyncedAsset(asset: CloudSyncedAssetEntity) {
            syncedAssets[asset.assetPath] = asset
        }

        override suspend fun deleteSyncedAsset(assetKey: String) {
            syncedAssets.remove(assetKey)
        }

        override suspend fun isUntouchedStarterWorkspace(filesDir: File): Boolean {
            if (manifest.folders.isNotEmpty() || manifest.tags.isNotEmpty() ||
                manifest.bookmarks.isNotEmpty() || manifest.ftsEntries.isNotEmpty() ||
                manifest.tombstones.isNotEmpty() || manifest.notebooks.size != 1
            ) {
                return false
            }
            val nb = manifest.notebooks.single()
            if (nb.title != CloudSyncEngine.STARTER_NOTEBOOK_TITLE) return false
            if (manifest.pages.size != 1) return false
            val p = manifest.pages.single()
            val dir = File(filesDir, "pages/${p.id}")
            return !dir.exists() || !dir.walkTopDown().any { it.isFile }
        }

        override suspend fun clearStarterWorkspaceWithoutTombstone(filesDir: File) {
            manifest = BackupManifest()
        }

        override suspend fun restoreNotebookFromSnapshot(
            snapshot: BackupManifest,
            restoredAtEpochMs: Long
        ) {
            val nbIds = snapshot.notebooks.mapTo(HashSet()) { it.id }
            val chIds = snapshot.chapters.mapTo(HashSet()) { it.id }
            val pIds = snapshot.pages.mapTo(HashSet()) { it.id }
            val bmIds = snapshot.bookmarks.mapTo(HashSet()) { it.id }
            val existingFolderIds = manifest.folders.mapTo(HashSet()) { it.id }

            val cleanedTombstones = manifest.tombstones.filterNot { t ->
                (t.entityType == SyncTombstoneEntity.TYPE_NOTEBOOK && t.entityId in nbIds) ||
                    (t.entityType == SyncTombstoneEntity.TYPE_CHAPTER && t.entityId in chIds) ||
                    (t.entityType == SyncTombstoneEntity.TYPE_PAGE && t.entityId in pIds) ||
                    (t.entityType == SyncTombstoneEntity.TYPE_BOOKMARK && t.entityId in bmIds)
            }
            val restoredNbs = snapshot.notebooks.map { nb ->
                nb.copy(
                    folderId = nb.folderId?.takeIf { it in existingFolderIds },
                    updatedAtEpochMs = restoredAtEpochMs
                )
            }
            val restoredPages = snapshot.pages.map { p -> p.copy(updatedAtEpochMs = restoredAtEpochMs) }
            manifest = manifest.copy(
                notebooks = manifest.notebooks.filterNot { it.id in nbIds } + restoredNbs,
                chapters = manifest.chapters.filterNot { it.id in chIds } + snapshot.chapters,
                pages = manifest.pages.filterNot { it.id in pIds } + restoredPages,
                bookmarks = manifest.bookmarks.filterNot { it.id in bmIds } + snapshot.bookmarks,
                tombstones = cleanedTombstones
            )
        }
    }
}
