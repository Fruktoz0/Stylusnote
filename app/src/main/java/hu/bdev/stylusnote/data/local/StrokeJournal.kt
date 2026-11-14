package hu.bdev.stylusnote.data.local

import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.JournalCommitSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

data class PageLoadRecoveryResult(
    val strokes: List<PersistedStrokeRecord>,
    val objects: List<PageObject>,
    val isReadOnly: Boolean,
    val sourceFormatVersion: Int,
    val discardedCorruptRecords: Int,
    val skippedUnknownRecords: Int,
    val usedBrushFallback: Boolean,
    val hadUncompactedJournal: Boolean
)

data class StartupRecoverySummary(
    val scannedPageCount: Int,
    val recoveredPageIds: List<String>,
    val totalDiscardedCorruptRecords: Int,
    val readOnlyPageIds: List<String>
)

/**
 * Crash-safe binary persistence engine for a single page directory (`pages/<pageId>/`),
 * managing `page.snote` (atomic compacted snapshot) and `page.journal` (append-only WAL)
 * per Section 9.1 – 9.4.
 *
 * All disk writes run sequentially through a single-threaded dispatcher ([writerDispatcher]) under
 * [ioMutex] with batched `FileChannel.force(false)` within `<= 1_000 ms`, immediate [flushAndSync]
 * on lifecycle pauses (`onPause`/`onStop`), and atomic compaction (`page.snote.tmp` ->
 * `ATOMIC_MOVE` -> truncated journal).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrokeJournal(
    val pageDirectory: File,
    private val compactionThresholdRecords: Int = DEFAULT_COMPACTION_THRESHOLD_RECORDS,
    private val compactionThresholdBytes: Long = DEFAULT_COMPACTION_THRESHOLD_BYTES,
    private val fsyncDebounceMs: Long = DEFAULT_FSYNC_INTERVAL_MS,
    private val writerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val activeBrushConfigProvider: () -> BrushConfig = { BrushConfig() },
    private val shapeMetaProvider: (String) -> ShapeMeta? = { null }
) : JournalCommitSink {

    val snapshotFile: File get() = File(pageDirectory, SNAPSHOT_FILE_NAME)
    val snapshotTempFile: File get() = File(pageDirectory, SNAPSHOT_TEMP_FILE_NAME)
    val journalFile: File get() = File(pageDirectory, JOURNAL_FILE_NAME)

    private val ioMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private var pendingFsyncJob: Job? = null

    private val liveStrokes = LinkedHashMap<String, PersistedStrokeRecord>()
    private val liveObjects = LinkedHashMap<String, PageObject>()

    private var journalChannel: FileChannel? = null
    private var journalRecordCount: Int = 0
    private var journalUnsynced: Boolean = false

    @Volatile
    var isReadOnly: Boolean = false
        private set

    @Volatile
    var sourceFormatVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION
        private set

    /**
     * Loads `page.snote` (if present), applies [FormatMigrator], replays valid `page.journal`
     * records, and silently drops any truncated or CRC-failed tail record caused by a crash.
     */
    suspend fun openAndRecover(): PageLoadRecoveryResult = withContext(writerDispatcher) {
        ioMutex.withLock {
            closeChannelLocked()
            liveStrokes.clear()
            liveObjects.clear()
            journalRecordCount = 0
            journalUnsynced = false
            isReadOnly = false
            sourceFormatVersion = PageFileFormat.CURRENT_FORMAT_VERSION

            pageDirectory.mkdirs()

            var discardedCorrupt = 0
            var skippedUnknown = 0
            var usedFallback = false
            var hadUncompactedJournal = false

            if (snapshotFile.exists() && snapshotFile.length() > 0L) {
                val snapResult = BufferedInputStream(FileInputStream(snapshotFile)).use { input ->
                    PageFileFormat.readAllValidFrames(input, stopOnCorruptTail = true)
                }
                sourceFormatVersion = snapResult.header.formatVersion
                discardedCorrupt += snapResult.discardedCorruptRecords
                skippedUnknown += snapResult.skippedUnknownRecords

                val inspection = FormatMigrator.inspectHeader(snapResult.header)
                if (inspection is FormatInspectionResult.ReadOnlyHigherVersion) {
                    isReadOnly = true
                }

                val frames = FormatMigrator.migrateFramesIfNeeded(
                    header = snapResult.header,
                    frames = snapResult.frames
                )
                for (frame in frames) {
                    if (applyFrameToMemoryLocked(frame)) {
                        usedFallback = true
                    }
                }
            }

            if (journalFile.exists() && journalFile.length() > 0L) {
                val journalResult = BufferedInputStream(FileInputStream(journalFile)).use { input ->
                    PageFileFormat.readAllValidFrames(input, stopOnCorruptTail = true)
                }
                if (journalResult.header.formatVersion > sourceFormatVersion) {
                    sourceFormatVersion = journalResult.header.formatVersion
                }
                val jInspection = FormatMigrator.inspectHeader(journalResult.header)
                if (jInspection is FormatInspectionResult.ReadOnlyHigherVersion) {
                    isReadOnly = true
                }
                discardedCorrupt += journalResult.discardedCorruptRecords
                skippedUnknown += journalResult.skippedUnknownRecords

                if (journalResult.frames.isNotEmpty() || journalResult.discardedCorruptRecords > 0) {
                    hadUncompactedJournal = true
                }

                val frames = FormatMigrator.migrateFramesIfNeeded(
                    header = journalResult.header,
                    frames = journalResult.frames
                )
                for (frame in frames) {
                    if (applyFrameToMemoryLocked(frame)) {
                        usedFallback = true
                    }
                }
                journalRecordCount = frames.size

                // Trim any corrupt tail from the live journal when safe to mutate.
                if (!isReadOnly && journalResult.discardedCorruptRecords > 0) {
                    rewriteJournalHeaderAndFramesLocked(frames)
                }
            } else if (!isReadOnly) {
                ensureJournalOpenLocked()
            }

            if (!isReadOnly && journalChannel == null) {
                ensureJournalOpenLocked()
            }

            PageLoadRecoveryResult(
                strokes = liveStrokes.values.toList(),
                objects = liveObjects.values.toList(),
                isReadOnly = isReadOnly,
                sourceFormatVersion = sourceFormatVersion,
                discardedCorruptRecords = discardedCorrupt,
                skippedUnknownRecords = skippedUnknown,
                usedBrushFallback = usedFallback || liveStrokes.values.any { it.usedFallbackBrush },
                hadUncompactedJournal = hadUncompactedJournal
            )
        }
    }

    /**
     * [JournalCommitSink] entrypoint invoked directly by `StylusDrawingSurface` when wet ink strokes
     * complete `wet -> dry` handoff on `ACTION_UP`.
     */
    override fun onStrokesCommitted(strokes: List<IndexedDryStroke>) {
        if (strokes.isEmpty()) return
        val activeBrush = activeBrushConfigProvider()
        val persisted = strokes.map { entry ->
            StrokeBinarySerializer.fromIndexedDryStroke(
                entry = entry,
                brushConfig = activeBrush,
                shapeMeta = shapeMetaProvider(entry.id)
            )
        }
        scope.launch {
            appendAddStrokes(persisted)
        }
    }

    suspend fun appendAddStrokes(records: List<PersistedStrokeRecord>) {
        if (records.isEmpty()) return
        val encodedFrames = records.map { rec ->
            rec to PageFileFormat.encodeFrameBytes(
                type = SnotRecordType.ADD_STROKE,
                payload = StrokeBinarySerializer.encodeStrokeRecord(rec)
            )
        }
        appendEncodedBatch(encodedFrames.map { it.second }) {
            for ((rec, _) in encodedFrames) {
                liveStrokes[rec.id] = rec
            }
        }
    }

    suspend fun appendRemoveStrokes(strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        val frames = strokeIds.map { id ->
            PageFileFormat.encodeFrameBytes(
                type = SnotRecordType.REMOVE_STROKE,
                payload = StrokeBinarySerializer.encodeIdPayload(id)
            )
        }
        appendEncodedBatch(frames) {
            for (id in strokeIds) {
                liveStrokes.remove(id)
            }
        }
    }

    suspend fun appendTransformStrokes(strokeIds: List<String>, affine: AffineTransform2D) {
        if (strokeIds.isEmpty()) return
        val frames = strokeIds.map { id ->
            PageFileFormat.encodeFrameBytes(
                type = SnotRecordType.TRANSFORM_STROKE,
                payload = StrokeBinarySerializer.encodeTransformDelta(id, affine)
            )
        }
        appendEncodedBatch(frames) {
            for (id in strokeIds) {
                liveStrokes[id]?.let { existing ->
                    liveStrokes[id] = existing.withTransform(affine)
                }
            }
        }
    }

    suspend fun appendRecolorStrokes(strokeIds: List<String>, canonicalColorArgb: Int) {
        if (strokeIds.isEmpty()) return
        val frames = strokeIds.map { id ->
            PageFileFormat.encodeFrameBytes(
                type = SnotRecordType.RECOLOR_STROKE,
                payload = StrokeBinarySerializer.encodeRecolorDelta(id, canonicalColorArgb)
            )
        }
        appendEncodedBatch(frames) {
            for (id in strokeIds) {
                liveStrokes[id]?.let { existing ->
                    liveStrokes[id] = existing.copy(
                        canonicalColorArgb = canonicalColorArgb,
                        brushConfig = existing.brushConfig.copy(colorArgb = canonicalColorArgb)
                    )
                }
            }
        }
    }

    suspend fun appendAddObject(obj: PageObject) {
        val frame = PageFileFormat.encodeFrameBytes(
            type = SnotRecordType.ADD_OBJECT,
            payload = StrokeBinarySerializer.encodePageObject(obj)
        )
        appendEncodedBatch(listOf(frame)) {
            liveObjects[obj.id] = obj
        }
    }

    suspend fun appendUpdateObject(obj: PageObject) {
        val frame = PageFileFormat.encodeFrameBytes(
            type = SnotRecordType.UPDATE_OBJECT,
            payload = StrokeBinarySerializer.encodePageObject(obj)
        )
        appendEncodedBatch(listOf(frame)) {
            liveObjects[obj.id] = obj
        }
    }

    suspend fun appendRemoveObject(objectId: String) {
        val frame = PageFileFormat.encodeFrameBytes(
            type = SnotRecordType.REMOVE_OBJECT,
            payload = StrokeBinarySerializer.encodeIdPayload(objectId)
        )
        appendEncodedBatch(listOf(frame)) {
            liveObjects.remove(objectId)
        }
    }

    /**
     * Forces an immediate `fsync` of all appended journal bytes (called in `onPause` / `onStop`).
     */
    suspend fun flushAndSync() = withContext(writerDispatcher) {
        ioMutex.withLock {
            pendingFsyncJob?.cancel()
            pendingFsyncJob = null
            if (!isReadOnly && journalUnsynced) {
                journalChannel?.force(false)
                journalUnsynced = false
            }
        }
    }

    /**
     * Atomically writes the full live stroke + object state into `page.snote.tmp`, fsyncs it,
     * replaces `page.snote` via `ATOMIC_MOVE` (Section 9.3), and truncates `page.journal`.
     */
    suspend fun compactNow() = withContext(writerDispatcher) {
        ioMutex.withLock {
            compactLocked()
        }
    }

    /**
     * Synchronous lifecycle helper suitable for Activity `onPause` / `onStop` and cleanup hooks.
     */
    fun flushAndSyncBlocking() {
        runBlocking(writerDispatcher) {
            flushAndSync()
        }
    }

    fun closeBlocking(compactBeforeClose: Boolean = false) {
        runBlocking(writerDispatcher) {
            ioMutex.withLock {
                pendingFsyncJob?.cancel()
                pendingFsyncJob = null
                if (!isReadOnly) {
                    if (compactBeforeClose && journalRecordCount > 0) {
                        compactLocked()
                    } else if (journalUnsynced) {
                        journalChannel?.force(false)
                        journalUnsynced = false
                    }
                }
                closeChannelLocked()
            }
        }
        scope.cancel()
    }

    private suspend fun appendEncodedBatch(
        frames: List<ByteArray>,
        updateMemory: () -> Unit
    ) = withContext(writerDispatcher) {
        ioMutex.withLock {
            if (isReadOnly) {
                throw ReadOnlyPageFormatException(sourceFormatVersion)
            }
            pageDirectory.mkdirs()
            val channel = ensureJournalOpenLocked()

            updateMemory()
            val totalSize = frames.sumOf { it.size }
            val buffer = ByteBuffer.allocate(totalSize)
            for (frame in frames) {
                buffer.put(frame)
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }

            journalRecordCount += frames.size
            journalUnsynced = true

            val currentFileBytes = channel.size()
            if (journalRecordCount >= compactionThresholdRecords ||
                currentFileBytes >= compactionThresholdBytes
            ) {
                compactLocked()
            } else {
                scheduleDebouncedFsyncLocked()
            }
        }
    }

    private fun scheduleDebouncedFsyncLocked() {
        if (fsyncDebounceMs <= 0L) {
            journalChannel?.force(false)
            journalUnsynced = false
            return
        }
        if (pendingFsyncJob?.isActive == true) {
            return
        }
        pendingFsyncJob = scope.launch {
            delay(fsyncDebounceMs.coerceAtMost(MAX_FSYNC_INTERVAL_MS))
            ioMutex.withLock {
                if (!isReadOnly && journalUnsynced) {
                    journalChannel?.force(false)
                    journalUnsynced = false
                }
            }
        }
    }

    private fun compactLocked() {
        if (isReadOnly) {
            throw ReadOnlyPageFormatException(sourceFormatVersion)
        }
        pendingFsyncJob?.cancel()
        pendingFsyncJob = null

        pageDirectory.mkdirs()
        if (snapshotTempFile.exists()) {
            snapshotTempFile.delete()
        }

        FileOutputStream(snapshotTempFile).use { fos ->
            PageFileFormat.writeHeader(
                output = fos,
                header = SnotFileHeader(
                    formatVersion = PageFileFormat.CURRENT_FORMAT_VERSION,
                    minReaderVersion = PageFileFormat.MIN_READER_VERSION,
                    inkVersion = PageFileFormat.INK_VERSION_STRING,
                    flags = PageFileFormat.FLAG_SNAPSHOT
                )
            )
            for (stroke in liveStrokes.values) {
                PageFileFormat.writeFrame(
                    output = fos,
                    type = SnotRecordType.STROKE_SNAPSHOT,
                    payload = StrokeBinarySerializer.encodeStrokeRecord(stroke)
                )
            }
            for (obj in liveObjects.values) {
                PageFileFormat.writeFrame(
                    output = fos,
                    type = SnotRecordType.OBJECT_SNAPSHOT,
                    payload = StrokeBinarySerializer.encodePageObject(obj)
                )
            }
            fos.flush()
            fos.channel.force(true)
        }

        val tmpPath = snapshotTempFile.toPath()
        val targetPath = snapshotFile.toPath()
        try {
            Files.move(
                tmpPath,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmpPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        closeChannelLocked()
        writeFreshEmptyJournalLocked()
        journalRecordCount = 0
        journalUnsynced = false
        sourceFormatVersion = PageFileFormat.CURRENT_FORMAT_VERSION
    }

    private fun ensureJournalOpenLocked(): FileChannel {
        journalChannel?.let { existing ->
            if (existing.isOpen) return existing
        }

        val needsHeader = !journalFile.exists() || journalFile.length() == 0L
        val channel = FileChannel.open(
            journalFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
        if (needsHeader) {
            val headerBytes = encodeJournalHeaderBytes()
            channel.write(ByteBuffer.wrap(headerBytes))
            channel.force(true)
        }
        journalChannel = channel
        return channel
    }

    private fun writeFreshEmptyJournalLocked() {
        val headerBytes = encodeJournalHeaderBytes()
        FileOutputStream(journalFile, false).use { fos ->
            fos.write(headerBytes)
            fos.flush()
            fos.channel.force(true)
        }
        ensureJournalOpenLocked()
    }

    private fun rewriteJournalHeaderAndFramesLocked(frames: List<SnotFrame>) {
        closeChannelLocked()
        FileOutputStream(journalFile, false).use { fos ->
            fos.write(encodeJournalHeaderBytes())
            for (frame in frames) {
                fos.write(PageFileFormat.encodeFrameBytes(frame.rawTypeCode, frame.payload))
            }
            fos.flush()
            fos.channel.force(true)
        }
        ensureJournalOpenLocked()
    }

    private fun encodeJournalHeaderBytes(): ByteArray {
        val baos = ByteArrayOutputStream(32)
        PageFileFormat.writeHeader(
            output = baos,
            header = SnotFileHeader(
                formatVersion = PageFileFormat.CURRENT_FORMAT_VERSION,
                minReaderVersion = PageFileFormat.MIN_READER_VERSION,
                inkVersion = PageFileFormat.INK_VERSION_STRING,
                flags = PageFileFormat.FLAG_JOURNAL
            )
        )
        return baos.toByteArray()
    }

    private fun closeChannelLocked() {
        try {
            journalChannel?.close()
        } catch (_: Throwable) {
            // Best-effort close on file handle cleanup.
        } finally {
            journalChannel = null
        }
    }

    /**
     * Applies one decoded frame to [liveStrokes] / [liveObjects]; returns `true` if a stroke fell
     * back to a default brush.
     */
    private fun applyFrameToMemoryLocked(frame: SnotFrame): Boolean {
        val recordType = frame.knownType ?: return false
        return when (recordType) {
            SnotRecordType.STROKE_SNAPSHOT,
            SnotRecordType.ADD_STROKE -> {
                val rec = StrokeBinarySerializer.decodeStrokeRecord(frame.payload)
                liveStrokes[rec.id] = rec
                rec.usedFallbackBrush
            }
            SnotRecordType.REMOVE_STROKE -> {
                val id = StrokeBinarySerializer.decodeIdPayload(frame.payload)
                liveStrokes.remove(id)
                false
            }
            SnotRecordType.TRANSFORM_STROKE -> {
                val (id, affine) = StrokeBinarySerializer.decodeTransformDelta(frame.payload)
                liveStrokes[id]?.let { existing ->
                    liveStrokes[id] = existing.withTransform(affine)
                }
                false
            }
            SnotRecordType.RECOLOR_STROKE -> {
                val (id, color) = StrokeBinarySerializer.decodeRecolorDelta(frame.payload)
                liveStrokes[id]?.let { existing ->
                    liveStrokes[id] = existing.copy(
                        canonicalColorArgb = color,
                        brushConfig = existing.brushConfig.copy(colorArgb = color)
                    )
                }
                false
            }
            SnotRecordType.OBJECT_SNAPSHOT,
            SnotRecordType.ADD_OBJECT,
            SnotRecordType.UPDATE_OBJECT -> {
                val obj = StrokeBinarySerializer.decodePageObject(frame.payload)
                liveObjects[obj.id] = obj
                false
            }
            SnotRecordType.REMOVE_OBJECT -> {
                val id = StrokeBinarySerializer.decodeIdPayload(frame.payload)
                liveObjects.remove(id)
                false
            }
        }
    }

    companion object {
        const val SNAPSHOT_FILE_NAME: String = "page.snote"
        const val SNAPSHOT_TEMP_FILE_NAME: String = "page.snote.tmp"
        const val JOURNAL_FILE_NAME: String = "page.journal"

        const val DEFAULT_COMPACTION_THRESHOLD_RECORDS: Int = 500
        const val DEFAULT_COMPACTION_THRESHOLD_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_FSYNC_INTERVAL_MS: Long = 900L
        const val MAX_FSYNC_INTERVAL_MS: Long = 1_000L

        /**
         * Scans all `pages/<pageId>/` subdirectories on application startup (Section 9.3),
         * detecting journals with uncompacted records or corrupt crash tails, recovering them, and
         * atomically compacting snapshots for writable pages.
         */
        suspend fun recoverAllPagesOnStartup(
            pagesRootDir: File,
            compactRecoveredPages: Boolean = true
        ): StartupRecoverySummary {
            if (!pagesRootDir.exists() || !pagesRootDir.isDirectory) {
                return StartupRecoverySummary(
                    scannedPageCount = 0,
                    recoveredPageIds = emptyList(),
                    totalDiscardedCorruptRecords = 0,
                    readOnlyPageIds = emptyList()
                )
            }

            val pageDirs = pagesRootDir.listFiles { file -> file.isDirectory }
                ?.sortedBy { it.name }
                ?: emptyList()

            val recoveredIds = ArrayList<String>()
            val readOnlyIds = ArrayList<String>()
            var totalDiscarded = 0

            for (dir in pageDirs) {
                val journal = File(dir, JOURNAL_FILE_NAME)
                val snapshot = File(dir, SNAPSHOT_FILE_NAME)
                val hasJournal = journal.exists() && journal.length() > 0L
                val hasSnapshot = snapshot.exists() && snapshot.length() > 0L
                if (!hasJournal && !hasSnapshot) continue

                val needsRecoveryInspection = hasJournal && (
                    !hasSnapshot || journal.length() > 32L || journal.lastModified() >= snapshot.lastModified()
                    )
                if (!needsRecoveryInspection) continue

                val pageJournal = StrokeJournal(pageDirectory = dir)
                try {
                    val result = pageJournal.openAndRecover()
                    totalDiscarded += result.discardedCorruptRecords
                    if (result.isReadOnly) {
                        readOnlyIds.add(dir.name)
                    } else if (result.hadUncompactedJournal) {
                        recoveredIds.add(dir.name)
                        if (compactRecoveredPages) {
                            pageJournal.compactNow()
                        }
                    }
                } finally {
                    pageJournal.closeBlocking(compactBeforeClose = false)
                }
            }

            return StartupRecoverySummary(
                scannedPageCount = pageDirs.size,
                recoveredPageIds = recoveredIds,
                totalDiscardedCorruptRecords = totalDiscarded,
                readOnlyPageIds = readOnlyIds
            )
        }
    }
}

