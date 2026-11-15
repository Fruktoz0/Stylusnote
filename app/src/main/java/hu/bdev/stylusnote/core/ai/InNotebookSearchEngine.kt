package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.AccentNormalizer
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class SearchMatchSource {
    HANDWRITING_OCR,
    FORMATTED_TEXT,
    LATEX_FORMULA,
    DERIVATION_CARD,
    PAGE_TITLE
}

enum class SearchIndexState {
    IDLE,
    CHECKING_MODEL,
    DOWNLOADING_MODEL,
    INDEXING_HANDWRITING,
    INDEXING_WITH_AI,
    READY
}

data class NotebookSearchMatch(
    val pageId: String,
    val pageIndexOneBased: Int,
    val pageTitle: String,
    val matchedSnippet: String,
    val highlightDocRect: DocRect?,
    val source: SearchMatchSource
)

data class IndexedSearchFragment(
    val text: String,
    val docRect: DocRect?,
    val source: SearchMatchSource,
    val strokeBoxes: List<DocRect> = emptyList()
) {
    val foldedForOffset: String = SearchTextIndexer.foldKeepingWordSpacing(text)
}

data class SearchExecutionResult(
    val matches: List<NotebookSearchMatch>,
    val state: SearchIndexState,
    val statusNoticeHu: String? = null
)

/**
 * Pure-Kotlin searchable text extraction, accent-insensitive matching, and word-level [DocRect]
 * bounding estimation shared across live search, background OCR, and unit tests.
 */
object SearchTextIndexer {

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

    internal fun foldKeepingWordSpacing(input: String): String {
        if (input.isEmpty()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return COMBINING_MARKS_REGEX.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    /**
     * Converts live dry strokes held in RAM into lightweight [PersistedStrokeRecord]s for
     * [OcrLineSegmenter] without touching binary serializers or disk I/O.
     */
    fun buildSearchablePersistedRecords(entries: List<IndexedDryStroke>): List<PersistedStrokeRecord> {
        if (entries.isEmpty()) return emptyList()
        return entries.map { entry ->
            val samples = try {
                CanvasStateHolder.extractStylusPoints(entry.stroke)
            } catch (_: Throwable) {
                emptyList()
            }
            PersistedStrokeRecord(
                id = entry.id,
                layer = if (entry.isHighlighter) StrokeLayerType.HIGHLIGHTER else StrokeLayerType.INK,
                zOrder = entry.zIndex,
                brushConfig = BrushConfig(),
                canonicalColorArgb = entry.stroke.brush.colorIntArgb,
                sizePt = entry.stroke.brush.size,
                samples = samples,
                bounds = entry.bounds
            )
        }
    }

    fun extractSearchableObjectFragments(
        objects: List<PageObject>,
        pageTitle: String? = null
    ): List<IndexedSearchFragment> {
        val out = ArrayList<IndexedSearchFragment>()
        val cleanTitle = pageTitle?.trim().orEmpty()
        if (cleanTitle.isNotEmpty()) {
            out.add(
                IndexedSearchFragment(
                    text = cleanTitle,
                    docRect = null,
                    source = SearchMatchSource.PAGE_TITLE
                )
            )
        }
        val sortedObjects = objects.sortedWith(compareBy({ it.bounds.topPt }, { it.bounds.leftPt }))
        for (obj in sortedObjects) {
            when (obj) {
                is PageObject.FormattedTextObject -> {
                    val lines = obj.text.lines()
                    val nonBlankCount = lines.count { it.isNotBlank() }.coerceAtLeast(1)
                    val totalLines = lines.size.coerceAtLeast(1)
                    val bandHeight = obj.bounds.heightPt / totalLines
                    lines.forEachIndexed { idx, line ->
                        val clean = line.trim()
                        if (clean.isNotEmpty()) {
                            val lineRect = if (nonBlankCount <= 1) {
                                obj.bounds
                            } else {
                                DocRect(
                                    leftPt = obj.bounds.leftPt,
                                    topPt = obj.bounds.topPt + idx * bandHeight,
                                    rightPt = obj.bounds.rightPt,
                                    bottomPt = obj.bounds.topPt + (idx + 1) * bandHeight
                                )
                            }
                            out.add(
                                IndexedSearchFragment(
                                    text = clean,
                                    docRect = lineRect,
                                    source = SearchMatchSource.FORMATTED_TEXT
                                )
                            )
                        }
                    }
                }
                is PageObject.LatexObject -> {
                    val latex = obj.latexSource.trim()
                    if (latex.isNotEmpty()) {
                        out.add(
                            IndexedSearchFragment(
                                text = latex,
                                docRect = obj.bounds,
                                source = SearchMatchSource.LATEX_FORMULA
                            )
                        )
                    }
                }
                is PageObject.DerivationCardObject -> {
                    val cardTitle = obj.title.trim()
                    if (cardTitle.isNotEmpty()) {
                        out.add(
                            IndexedSearchFragment(
                                text = cardTitle,
                                docRect = obj.bounds,
                                source = SearchMatchSource.DERIVATION_CARD
                            )
                        )
                    }
                    val steps = obj.stepsMarkdownLatex.trim()
                    if (steps.isNotEmpty()) {
                        out.add(
                            IndexedSearchFragment(
                                text = steps,
                                docRect = obj.bounds,
                                source = SearchMatchSource.DERIVATION_CARD
                            )
                        )
                    }
                }
                is PageObject.FunctionPlotObject -> {
                    val expr = obj.expression.trim()
                    if (expr.isNotEmpty()) {
                        out.add(
                            IndexedSearchFragment(
                                text = expr,
                                docRect = obj.bounds,
                                source = SearchMatchSource.LATEX_FORMULA
                            )
                        )
                    }
                }
                is PageObject.PdfPageRefObject,
                is PageObject.GeometricShapeObject,
                is PageObject.ImageObject -> Unit
            }
        }
        return out
    }

    /**
     * Combines line-level handwriting OCR strings with vector objects and optional [pageTitle] into
     * one normalized document stored inside `page_text_fts`.
     */
    fun combinePageIndexText(
        ocrLines: List<String>,
        objects: List<PageObject>,
        pageTitle: String? = null
    ): String {
        val parts = LinkedHashSet<String>()
        for (line in ocrLines) {
            val clean = line.trim()
            if (clean.isNotEmpty()) parts.add(clean)
        }
        for (fragment in extractSearchableObjectFragments(objects, pageTitle)) {
            if (fragment.text.isNotEmpty()) parts.add(fragment.text)
        }
        return parts.joinToString(separator = "\n")
    }

    /**
     * Estimates a tight [DocRect] highlight around a matched substring at [occurrenceStart] inside
     * [fragment], clamping to constituent ink stroke bounds when stroke centers lie inside the
     * character-proportion band.
     */
    fun estimateWordHighlightRect(
        fragment: IndexedSearchFragment,
        occurrenceStart: Int,
        foldedNeedleLength: Int
    ): DocRect? {
        val bounds = fragment.docRect ?: return null
        val totalLen = fragment.foldedForOffset.length.coerceAtLeast(1)
        val width = bounds.widthPt.coerceAtLeast(12f)
        if (foldedNeedleLength >= totalLen) return bounds.inflate(4f)

        val startFrac = (occurrenceStart.toFloat() / totalLen).coerceIn(0f, 1f)
        val endFrac = ((occurrenceStart + foldedNeedleLength).toFloat() / totalLen).coerceIn(startFrac, 1f)
        val estLeft = bounds.leftPt + startFrac * width
        val estRight = max(estLeft + 24f, bounds.leftPt + endFrac * width).coerceAtMost(bounds.rightPt + 8f)

        val pad = max(14f, bounds.heightPt * 0.55f)
        val matchingStrokeBoxes = fragment.strokeBoxes.filter { box ->
            val cx = box.centerXPt
            cx >= estLeft - pad && cx <= estRight + pad
        }
        if (matchingStrokeBoxes.isNotEmpty()) {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (box in matchingStrokeBoxes) {
                left = min(left, box.leftPt)
                top = min(top, box.topPt)
                right = max(right, box.rightPt)
                bottom = max(bottom, box.bottomPt)
            }
            return DocRect(
                leftPt = left - 5f,
                topPt = top - 4f,
                rightPt = right + 5f,
                bottomPt = bottom + 4f
            )
        }

        return DocRect(
            leftPt = (estLeft - 4f).coerceAtLeast(bounds.leftPt - 4f),
            topPt = bounds.topPt - 4f,
            rightPt = estRight + 4f,
            bottomPt = bounds.bottomPt + 4f
        )
    }

    fun matchPageFragments(
        pageId: String,
        pageIndexOneBased: Int,
        pageTitle: String,
        fragments: List<IndexedSearchFragment>,
        rawQuery: String
    ): List<NotebookSearchMatch> {
        val foldedQuery = AccentNormalizer.foldToAscii(rawQuery)
        if (foldedQuery.isEmpty() || fragments.isEmpty()) return emptyList()

        val matches = ArrayList<NotebookSearchMatch>()
        for (fragment in fragments) {
            val haystack = fragment.foldedForOffset
            if (haystack.isEmpty()) continue
            var searchFrom = 0
            while (searchFrom < haystack.length) {
                val idx = haystack.indexOf(foldedQuery, startIndex = searchFrom)
                if (idx < 0) break
                val snippet = extractSnippet(fragment.text, idx, foldedQuery.length)
                val rect = estimateWordHighlightRect(fragment, idx, foldedQuery.length)
                matches.add(
                    NotebookSearchMatch(
                        pageId = pageId,
                        pageIndexOneBased = pageIndexOneBased,
                        pageTitle = pageTitle,
                        matchedSnippet = snippet,
                        highlightDocRect = rect,
                        source = fragment.source
                    )
                )
                searchFrom = idx + foldedQuery.length.coerceAtLeast(1)
            }
        }

        if (matches.isNotEmpty()) return matches

        // Multi-word fallback across separate lines on the same page
        val tokens = foldedQuery.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (tokens.size <= 1) return emptyList()

        val fullPageFolded = fragments.joinToString("\n") { it.foldedForOffset }
        if (!tokens.all { fullPageFolded.contains(it) }) return emptyList()

        val firstToken = tokens.first()
        for (fragment in fragments) {
            val idx = fragment.foldedForOffset.indexOf(firstToken)
            if (idx >= 0) {
                matches.add(
                    NotebookSearchMatch(
                        pageId = pageId,
                        pageIndexOneBased = pageIndexOneBased,
                        pageTitle = pageTitle,
                        matchedSnippet = extractSnippet(fragment.text, idx, firstToken.length),
                        highlightDocRect = estimateWordHighlightRect(fragment, idx, firstToken.length),
                        source = fragment.source
                    )
                )
                break
            }
        }
        return matches
    }

    private fun extractSnippet(rawText: String, approxStart: Int, approxLen: Int): String {
        val singleLine = rawText.replace('\n', ' ').trim()
        if (singleLine.length <= 54) return singleLine
        val start = (approxStart - 16).coerceIn(0, singleLine.length)
        val end = (approxStart + approxLen + 26).coerceIn(start, singleLine.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < singleLine.length) "…" else ""
        return prefix + singleLine.substring(start, end).trim() + suffix
    }
}

/**
 * Coordinates live in-notebook search across the active open canvas (unsaved RAM strokes and vector
 * objects) and all sibling pages in the notebook (on-device ML Kit Digital Ink + Room FTS4 +
 * optional manual BYOK AI deep-indexing), returning ordered [NotebookSearchMatch] entries with
 * document-space [DocRect] highlight anchors.
 */
class InNotebookSearchCoordinator(
    private val database: AppDatabase,
    private val filesDir: File,
    private val lineRecognizer: LineInkRecognizer = MlKitLineInkRecognizer(),
    private val lineSegmenter: OcrLineSegmenter = OcrLineSegmenter(),
    private val modelAlreadyDownloadedCheck: suspend (OcrLanguageTag) -> Boolean = { lang ->
        OcrModelDownloadManager.isModelDownloaded(lang)
    },
    private val modelDownloader: suspend (OcrLanguageTag) -> Boolean = { lang ->
        OcrModelDownloadManager.isModelDownloaded(lang) ||
            OcrModelDownloadManager.downloadModelIfNeeded(lang)
    },
    private val aiTextBoxExtractor: (suspend (List<PersistedStrokeRecord>, OcrLanguageTag) -> List<Pair<String, DocRect>>)? = null
) {

    private data class CachedPageIndex(
        val signature: String,
        val updatedAtMs: Long,
        val hasExactHandwritingOcr: Boolean,
        val hasAiDeepIndex: Boolean,
        val fragments: List<IndexedSearchFragment>
    )

    private val indexMutex = Mutex()
    private val cachedPages = LinkedHashMap<String, CachedPageIndex>()
    private var lastModelWarningHu: String? = null

    fun clearCache() {
        cachedPages.clear()
        lastModelWarningHu = null
    }

    suspend fun prepareAndSearch(
        notebookId: String,
        ocrLanguage: OcrLanguageTag,
        pages: List<PageEntity>,
        currentPageId: String,
        liveStrokes: List<PersistedStrokeRecord>,
        liveObjects: List<PageObject>,
        livePageTitle: String?,
        query: String,
        onStateChanged: (SearchIndexState, String?) -> Unit = { _, _ -> }
    ): SearchExecutionResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || pages.isEmpty()) {
            return@withContext SearchExecutionResult(emptyList(), SearchIndexState.READY, lastModelWarningHu)
        }

        indexMutex.withLock {
            val orderedPages = pages.sortedBy { it.pageIndexInNotebook }
            onStateChanged(SearchIndexState.CHECKING_MODEL, null)

            var modelReady = runCatching { modelAlreadyDownloadedCheck(ocrLanguage) }.getOrDefault(false)
            val hasAnyDirtyOrLiveHandwriting = liveStrokes.any { lineSegmenter.isCandidateHandwritingStroke(it) } ||
                orderedPages.any { it.needsOcrSync }

            if (!modelReady && hasAnyDirtyOrLiveHandwriting) {
                onStateChanged(
                    SearchIndexState.DOWNLOADING_MODEL,
                    "Kézírás-felismerő modell (${ocrLanguage.displayName}) letöltése…"
                )
                modelReady = runCatching { modelDownloader(ocrLanguage) }.getOrDefault(false)
                lastModelWarningHu = if (modelReady) {
                    null
                } else {
                    "Offline/sikertelen OCR-modell letöltés (${ocrLanguage.displayName}): a begépelt szöveg és címek kereshetők."
                }
            } else if (modelReady) {
                lastModelWarningHu = null
            }

            onStateChanged(SearchIndexState.INDEXING_HANDWRITING, lastModelWarningHu)

            // 1. Index active open page directly from live RAM state (even before disk save).
            val currentPageEntity = orderedPages.firstOrNull { it.id == currentPageId }
            val effectiveLiveTitle = livePageTitle ?: currentPageEntity?.title
            val liveSig = computeLiveSignature(liveStrokes, liveObjects, effectiveLiveTitle, modelReady)
            val existingOpenCache = cachedPages[currentPageId]
            val shouldPreserveAiFragments = existingOpenCache != null &&
                existingOpenCache.hasAiDeepIndex &&
                existingOpenCache.signature.substringBefore('|') == liveSig.substringBefore('|')

            if (!shouldPreserveAiFragments &&
                (existingOpenCache == null || existingOpenCache.signature != liveSig)
            ) {
                val (fragments, ocrLines) = indexStrokesAndObjects(
                    strokes = liveStrokes,
                    objects = liveObjects,
                    pageTitle = effectiveLiveTitle,
                    ocrLanguage = ocrLanguage,
                    runHandwritingOcr = modelReady
                )
                cachedPages[currentPageId] = CachedPageIndex(
                    signature = liveSig,
                    updatedAtMs = currentPageEntity?.updatedAtEpochMs ?: 0L,
                    hasExactHandwritingOcr = modelReady,
                    hasAiDeepIndex = false,
                    fragments = fragments
                )
                if (currentPageEntity != null && modelReady) {
                    val combinedText = SearchTextIndexer.combinePageIndexText(
                        ocrLines = ocrLines,
                        objects = liveObjects,
                        pageTitle = effectiveLiveTitle
                    )
                    database.pageTextSearchDao().replacePageOcrText(
                        pageId = currentPageId,
                        notebookId = notebookId,
                        recognizedText = combinedText
                    )
                }
            }

            // 2. Index closed sibling pages from disk when needed, otherwise reuse cached/FTS entries.
            val ftsByPageId = database.pageTextSearchDao()
                .getPageEntriesForNotebook(notebookId)
                .associateBy { it.pageId }

            for (page in orderedPages) {
                if (page.id == currentPageId) continue
                val cached = cachedPages[page.id]
                val needsRefresh = cached == null ||
                    page.needsOcrSync ||
                    cached.updatedAtMs != page.updatedAtEpochMs ||
                    (modelReady && !cached.hasExactHandwritingOcr)

                if (needsRefresh) {
                    if (page.needsOcrSync || ftsByPageId[page.id] == null) {
                        val diskResult = loadClosedPageFromDisk(
                            page = page,
                            ocrLanguage = ocrLanguage,
                            runHandwritingOcr = modelReady
                        )
                        if (diskResult != null) {
                            val (fragments, combinedText) = diskResult
                            cachedPages[page.id] = CachedPageIndex(
                                signature = "disk:${page.updatedAtEpochMs}:$modelReady",
                                updatedAtMs = page.updatedAtEpochMs,
                                hasExactHandwritingOcr = modelReady,
                                hasAiDeepIndex = false,
                                fragments = fragments
                            )
                            database.pageTextSearchDao().replacePageOcrText(
                                pageId = page.id,
                                notebookId = notebookId,
                                recognizedText = combinedText
                            )
                            if (modelReady && page.needsOcrSync) {
                                database.pageDao().markOcrSyncState(
                                    pageId = page.id,
                                    needsSync = false,
                                    updatedAtMs = page.updatedAtEpochMs
                                )
                            }
                            continue
                        }
                    }

                    // Page already indexed in SQLite FTS from a prior session: reuse FTS lines
                    // instantly; when user jumps to it, live page indexing computes exact DocRects.
                    val ftsText = ftsByPageId[page.id]?.rawText.orEmpty()
                    val fallbackFragments = buildList {
                        val title = page.title?.trim().orEmpty()
                        if (title.isNotEmpty()) {
                            add(
                                IndexedSearchFragment(
                                    text = title,
                                    docRect = null,
                                    source = SearchMatchSource.PAGE_TITLE
                                )
                            )
                        }
                        ftsText.lines().forEach { line ->
                            val clean = line.trim()
                            if (clean.isNotEmpty()) {
                                add(
                                    IndexedSearchFragment(
                                        text = clean,
                                        docRect = null,
                                        source = SearchMatchSource.HANDWRITING_OCR
                                    )
                                )
                            }
                        }
                    }
                    cachedPages[page.id] = CachedPageIndex(
                        signature = "fts:${page.updatedAtEpochMs}",
                        updatedAtMs = page.updatedAtEpochMs,
                        hasExactHandwritingOcr = true,
                        hasAiDeepIndex = false,
                        fragments = fallbackFragments
                    )
                }
            }

            // 3. Collect ordered matches across all notebook pages.
            val allMatches = ArrayList<NotebookSearchMatch>()
            orderedPages.forEachIndexed { idx, page ->
                val displayTitle = page.title?.takeIf { it.isNotBlank() } ?: "${idx + 1}. oldal"
                val fragments = cachedPages[page.id]?.fragments.orEmpty()
                allMatches.addAll(
                    SearchTextIndexer.matchPageFragments(
                        pageId = page.id,
                        pageIndexOneBased = idx + 1,
                        pageTitle = displayTitle,
                        fragments = fragments,
                        rawQuery = cleanQuery
                    )
                )
            }

            onStateChanged(SearchIndexState.READY, lastModelWarningHu)
            SearchExecutionResult(
                matches = allMatches,
                state = SearchIndexState.READY,
                statusNoticeHu = lastModelWarningHu
            )
        }
    }

    /**
     * Explicit user-initiated BYOK Gemini pass to deeply index hard-to-read handwritten strokes on
     * [currentPageId] without modifying or erasing any strokes on the canvas.
     */
    suspend fun runManualAiDeepIndex(
        notebookId: String,
        ocrLanguage: OcrLanguageTag,
        pages: List<PageEntity>,
        currentPageId: String,
        liveStrokes: List<PersistedStrokeRecord>,
        liveObjects: List<PageObject>,
        livePageTitle: String?,
        query: String,
        onStateChanged: (SearchIndexState, String?) -> Unit = { _, _ -> }
    ): SearchExecutionResult = withContext(Dispatchers.IO) {
        val extractor = aiTextBoxExtractor
            ?: return@withContext SearchExecutionResult(
                matches = emptyList(),
                state = SearchIndexState.READY,
                statusNoticeHu = "AI mélyindexelés nem konfigurált."
            )

        if (liveStrokes.isEmpty()) {
            return@withContext prepareAndSearch(
                notebookId = notebookId,
                ocrLanguage = ocrLanguage,
                pages = pages,
                currentPageId = currentPageId,
                liveStrokes = liveStrokes,
                liveObjects = liveObjects,
                livePageTitle = livePageTitle,
                query = query,
                onStateChanged = onStateChanged
            )
        }

        indexMutex.withLock {
            onStateChanged(SearchIndexState.INDEXING_WITH_AI, "AI mélyindexelés folyamatban…")
            val aiBoxes = extractor(liveStrokes, ocrLanguage)
            val orderedPages = pages.sortedBy { it.pageIndexInNotebook }
            val currentPageEntity = orderedPages.firstOrNull { it.id == currentPageId }
            val effectiveTitle = livePageTitle ?: currentPageEntity?.title
            val objectFragments = SearchTextIndexer.extractSearchableObjectFragments(liveObjects, effectiveTitle)
            val aiFragments = aiBoxes.mapNotNull { (txt, rect) ->
                val clean = txt.trim()
                if (clean.isEmpty()) {
                    null
                } else {
                    IndexedSearchFragment(
                        text = clean,
                        docRect = rect,
                        source = SearchMatchSource.HANDWRITING_OCR
                    )
                }
            }
            val combinedFragments = aiFragments + objectFragments
            val sig = computeLiveSignature(liveStrokes, liveObjects, effectiveTitle, modelReady = true)
            cachedPages[currentPageId] = CachedPageIndex(
                signature = sig,
                updatedAtMs = currentPageEntity?.updatedAtEpochMs ?: 0L,
                hasExactHandwritingOcr = true,
                hasAiDeepIndex = true,
                fragments = combinedFragments
            )
            val combinedText = SearchTextIndexer.combinePageIndexText(
                ocrLines = aiFragments.map { it.text },
                objects = liveObjects,
                pageTitle = effectiveTitle
            )
            database.pageTextSearchDao().replacePageOcrText(
                pageId = currentPageId,
                notebookId = notebookId,
                recognizedText = combinedText
            )
        }

        prepareAndSearch(
            notebookId = notebookId,
            ocrLanguage = ocrLanguage,
            pages = pages,
            currentPageId = currentPageId,
            liveStrokes = liveStrokes,
            liveObjects = liveObjects,
            livePageTitle = livePageTitle,
            query = query,
            onStateChanged = onStateChanged
        )
    }

    private suspend fun indexStrokesAndObjects(
        strokes: List<PersistedStrokeRecord>,
        objects: List<PageObject>,
        pageTitle: String?,
        ocrLanguage: OcrLanguageTag,
        runHandwritingOcr: Boolean
    ): Pair<List<IndexedSearchFragment>, List<String>> {
        val ocrFragments = ArrayList<IndexedSearchFragment>()
        val ocrLines = ArrayList<String>()
        if (runHandwritingOcr && strokes.isNotEmpty()) {
            val segmented = lineSegmenter.segmentIntoLines(strokes = strokes, pageObjects = objects)
            var previousText: String? = null
            for (line in segmented) {
                val recognized = runCatching {
                    lineRecognizer.recognizeLine(
                        line = line,
                        preContext = previousText,
                        language = ocrLanguage
                    )?.trim()
                }.getOrNull()
                if (!recognized.isNullOrEmpty()) {
                    previousText = recognized
                    ocrLines.add(recognized)
                    ocrFragments.add(
                        IndexedSearchFragment(
                            text = recognized,
                            docRect = line.bounds,
                            source = SearchMatchSource.HANDWRITING_OCR,
                            strokeBoxes = line.strokes.map { it.bounds }
                        )
                    )
                }
            }
        }
        val objectFragments = SearchTextIndexer.extractSearchableObjectFragments(objects, pageTitle)
        return (ocrFragments + objectFragments) to ocrLines
    }

    private suspend fun loadClosedPageFromDisk(
        page: PageEntity,
        ocrLanguage: OcrLanguageTag,
        runHandwritingOcr: Boolean
    ): Pair<List<IndexedSearchFragment>, String>? {
        val pageDir = File(filesDir, "pages/${page.id}")
        if (!pageDir.exists()) return null
        val journal = StrokeJournal(pageDirectory = pageDir)
        val recovery = try {
            journal.openAndRecover()
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { journal.closeBlocking(compactBeforeClose = false) }
        }
        val (fragments, ocrLines) = indexStrokesAndObjects(
            strokes = recovery.strokes,
            objects = recovery.objects,
            pageTitle = page.title,
            ocrLanguage = ocrLanguage,
            runHandwritingOcr = runHandwritingOcr
        )
        val combinedText = SearchTextIndexer.combinePageIndexText(
            ocrLines = ocrLines,
            objects = recovery.objects,
            pageTitle = page.title
        )
        return fragments to combinedText
    }

    private fun computeLiveSignature(
        strokes: List<PersistedStrokeRecord>,
        objects: List<PageObject>,
        pageTitle: String?,
        modelReady: Boolean
    ): String {
        var h = 17L
        for (s in strokes) {
            h = h * 31L + s.id.hashCode().toLong()
            h = h * 31L + s.bounds.leftPt.toBits().toLong()
            h = h * 31L + s.bounds.topPt.toBits().toLong()
        }
        for (o in objects) {
            h = h * 31L + o.id.hashCode().toLong()
            h = h * 31L + o.bounds.leftPt.toBits().toLong()
            h = h * 31L + o.bounds.topPt.toBits().toLong()
            when (o) {
                is PageObject.FormattedTextObject -> h = h * 31L + o.text.hashCode().toLong()
                is PageObject.LatexObject -> h = h * 31L + o.latexSource.hashCode().toLong()
                is PageObject.DerivationCardObject -> {
                    h = h * 31L + o.title.hashCode().toLong()
                    h = h * 31L + o.stepsMarkdownLatex.hashCode().toLong()
                }
                is PageObject.FunctionPlotObject -> h = h * 31L + o.expression.hashCode().toLong()
                else -> Unit
            }
        }
        h = h * 31L + (pageTitle?.hashCode()?.toLong() ?: 0L)
        return "$h|$modelReady"
    }
}
