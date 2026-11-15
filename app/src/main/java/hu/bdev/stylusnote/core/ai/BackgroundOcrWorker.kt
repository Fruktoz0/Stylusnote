package hu.bdev.stylusnote.core.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}

enum class OcrModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/**
 * Manages ML Kit Digital Ink model availability and download progress for supported notebook
 * languages (`hu`, `en-US`) per Section 2 & 7.
 */
object OcrModelDownloadManager {

    private val _modelStatuses = MutableStateFlow(
        mapOf(
            OcrLanguageTag.HUNGARIAN to OcrModelStatus.NOT_DOWNLOADED,
            OcrLanguageTag.ENGLISH_US to OcrModelStatus.NOT_DOWNLOADED
        )
    )
    val modelStatuses: StateFlow<Map<OcrLanguageTag, OcrModelStatus>> = _modelStatuses.asStateFlow()

    fun resolveModel(languageTag: OcrLanguageTag): DigitalInkRecognitionModel {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag.bcp47Tag)
            ?: throw IllegalStateException(
                "Az ML Kit Digital Ink modellazonosító nem található: ${languageTag.bcp47Tag}"
            )
        return DigitalInkRecognitionModel.builder(identifier).build()
    }

    suspend fun isModelDownloaded(languageTag: OcrLanguageTag): Boolean {
        val model = resolveModel(languageTag)
        val downloaded = RemoteModelManager.getInstance().isModelDownloaded(model).awaitTask()
        updateStatus(
            languageTag,
            if (downloaded) OcrModelStatus.DOWNLOADED else OcrModelStatus.NOT_DOWNLOADED
        )
        return downloaded
    }

    suspend fun downloadModelIfNeeded(languageTag: OcrLanguageTag): Boolean {
        if (isModelDownloaded(languageTag)) return true
        updateStatus(languageTag, OcrModelStatus.DOWNLOADING)
        return try {
            val model = resolveModel(languageTag)
            val conditions = DownloadConditions.Builder().build()
            RemoteModelManager.getInstance().download(model, conditions).awaitTask()
            val verified = RemoteModelManager.getInstance().isModelDownloaded(model).awaitTask()
            updateStatus(
                languageTag,
                if (verified) OcrModelStatus.DOWNLOADED else OcrModelStatus.NOT_DOWNLOADED
            )
            verified
        } catch (_: Throwable) {
            updateStatus(languageTag, OcrModelStatus.FAILED)
            false
        }
    }

    suspend fun refreshAllLanguageStatuses() {
        for (lang in OcrLanguageTag.entries) {
            runCatching { isModelDownloaded(lang) }
        }
    }

    private fun updateStatus(languageTag: OcrLanguageTag, status: OcrModelStatus) {
        _modelStatuses.update { current -> current + (languageTag to status) }
    }
}

fun interface LineInkRecognizer {
    suspend fun recognizeLine(
        line: SegmentedOcrLine,
        preContext: String?,
        language: OcrLanguageTag
    ): String?
}

/** On-device ML Kit Digital Ink implementation of [LineInkRecognizer]. */
class MlKitLineInkRecognizer : LineInkRecognizer {
    override suspend fun recognizeLine(
        line: SegmentedOcrLine,
        preContext: String?,
        language: OcrLanguageTag
    ): String? {
        if (line.strokes.isEmpty()) return null
        val model = OcrModelDownloadManager.resolveModel(language)
        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        return try {
            val result = recognizer.recognize(
                line.toMlKitInk(),
                line.buildRecognitionContext(preContext)
            ).awaitTask()
            result.candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            runCatching { recognizer.close() }
        }
    }
}

/**
 * Low-priority WorkManager worker running 100% offline ML Kit Digital Ink OCR on modified pages
 * after save/close debounce and indexing recognized text into Room's FTS4 `page_text_fts` table
 * (Section 7 & Section 9.5: never sends handwriting to Gemini in the background).
 */
class BackgroundOcrWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(applicationContext)
        val pageDao = database.pageDao()
        val notebookDao = database.notebookDao()
        val searchDao = database.pageTextSearchDao()

        val specificPageId = inputData.getString(KEY_PAGE_ID)?.takeIf { it.isNotBlank() }
        val forceRescan = inputData.getBoolean(KEY_FORCE_RESCAN, false)

        val targetPages: List<PageEntity> = if (specificPageId != null) {
            val page = pageDao.getPageById(specificPageId) ?: return@withContext Result.success()
            if (!page.needsOcrSync && !forceRescan) {
                return@withContext Result.success()
            }
            listOf(page)
        } else {
            pageDao.getPagesNeedingOcr()
        }

        if (targetPages.isEmpty()) {
            return@withContext Result.success()
        }

        val segmenter = OcrLineSegmenter()
        val recognizer = MlKitLineInkRecognizer()
        var needsDeferredRetry = false

        for (page in targetPages) {
            val language = notebookDao.getNotebookById(page.notebookId)?.ocrLanguage
                ?: OcrLanguageTag.HUNGARIAN

            val modelReady = runCatching {
                if (OcrModelDownloadManager.isModelDownloaded(language)) {
                    true
                } else {
                    OcrModelDownloadManager.downloadModelIfNeeded(language)
                }
            }.getOrDefault(false)

            if (!modelReady) {
                // Keep needsOcrSync = true and defer until the offline model download finishes.
                needsDeferredRetry = true
                continue
            }

            val pageDir = File(applicationContext.filesDir, "pages/${page.id}")
            if (!pageDir.exists()) {
                searchDao.replacePageOcrText(page.id, page.notebookId, page.title.orEmpty())
                pageDao.markOcrSyncState(page.id, needsSync = false, updatedAtMs = System.currentTimeMillis())
                continue
            }

            val journal = StrokeJournal(pageDir)
            val recovery = try {
                journal.openAndRecover()
            } finally {
                journal.closeBlocking(compactBeforeClose = false)
            }

            val lines = segmenter.segmentIntoLines(
                strokes = recovery.strokes,
                pageObjects = recovery.objects
            )

            val recognizedLines = ArrayList<String>(lines.size)
            var previousLineText: String? = null

            for (line in lines) {
                val text = recognizer.recognizeLine(
                    line = line,
                    preContext = previousLineText,
                    language = language
                )
                if (!text.isNullOrBlank()) {
                    recognizedLines.add(text)
                    previousLineText = text
                }
            }

            val joinedText = SearchTextIndexer.combinePageIndexText(
                ocrLines = recognizedLines,
                objects = recovery.objects,
                pageTitle = page.title
            )
            searchDao.replacePageOcrText(
                pageId = page.id,
                notebookId = page.notebookId,
                recognizedText = joinedText
            )
            pageDao.markOcrSyncState(
                pageId = page.id,
                needsSync = false,
                updatedAtMs = System.currentTimeMillis()
            )
        }

        if (needsDeferredRetry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PAGE_ID = "page_id"
        const val KEY_FORCE_RESCAN = "force_rescan"
        const val DEFAULT_DEBOUNCE_DELAY_MS = 3_500L
        private const val SWEEP_WORK_NAME = "stylusnote_ocr_dirty_sweep"

        /** Schedules debounced offline OCR for a modified/closed page. */
        fun enqueueForPage(
            context: Context,
            pageId: String,
            forceRescan: Boolean = false,
            debounceDelayMs: Long = DEFAULT_DEBOUNCE_DELAY_MS
        ) {
            val request = OneTimeWorkRequestBuilder<BackgroundOcrWorker>()
                .setInitialDelay(debounceDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_PAGE_ID to pageId,
                        KEY_FORCE_RESCAN to forceRescan
                    )
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "stylusnote_ocr_page_$pageId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Enqueues a low-priority sweep for all pages with `needsOcrSync = 1`. */
        fun enqueueDirtyPagesSweep(
            context: Context,
            debounceDelayMs: Long = DEFAULT_DEBOUNCE_DELAY_MS
        ) {
            val request = OneTimeWorkRequestBuilder<BackgroundOcrWorker>()
                .setInitialDelay(debounceDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                SWEEP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

