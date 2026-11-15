package hu.bdev.stylusnote.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.data.export.CanvasPageVectorRenderer
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Immutable in-memory snapshot of a live page's strokes and embedded objects so thumbnail
 * generation never blocks on journal I/O or UI locks for currently open pages.
 */
data class PageThumbnailSourceSnapshot(
    val strokes: List<PersistedStrokeRecord>,
    val objects: List<PageObject>,
    val liveInkStrokesById: Map<String, Stroke> = emptyMap()
)

/**
 * Background compositor and LRU cache for fixed-page miniature previews in [LeftPageDrawerOverlay].
 *
 * Composites Layer 1 (paper template), Layer 2 (optional PDF backdrop), Layer 2b (under-ink images),
 * Layers 3–4 (highlighters & dry ink strokes), and Layer 5 (objects) off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageThumbnailRenderer(
    private val context: Context,
    private val pdfTileRenderer: PdfTileRenderer? = null,
    maxCacheBytes: Int = DEFAULT_CACHE_BYTES
) {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val pageVersions = ConcurrentHashMap<String, AtomicLong>()

    private val cache = object : LruCache<String, Bitmap>((maxCacheBytes / 1024).coerceAtLeast(1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun getCached(
        page: PageEntity,
        isOledMode: Boolean,
        versionToken: Long = versionFor(page.id),
        maxEdgePx: Int = DEFAULT_THUMBNAIL_MAX_EDGE_PX
    ): Bitmap? {
        val key = buildCacheKey(page, isOledMode, versionToken, maxEdgePx)
        return cache.get(key)?.takeIf { !it.isRecycled }
    }

    fun versionFor(pageId: String): Long = pageVersions[pageId]?.get() ?: 0L

    fun invalidatePage(pageId: String) {
        pageVersions.getOrPut(pageId) { AtomicLong(0L) }.incrementAndGet()
        val prefix = "$pageId::"
        for (key in cache.snapshot().keys) {
            if (key.startsWith(prefix)) {
                cache.remove(key)
            }
        }
    }

    fun invalidateAll() {
        for (key in pageVersions.keys) {
            pageVersions[key]?.incrementAndGet()
        }
        cache.evictAll()
    }

    suspend fun getOrRenderThumbnail(
        page: PageEntity,
        palette: StylusSurfacePalette,
        versionToken: Long = versionFor(page.id),
        liveSnapshot: PageThumbnailSourceSnapshot? = null,
        maxEdgePx: Int = DEFAULT_THUMBNAIL_MAX_EDGE_PX
    ): Bitmap? {
        val isOledMode = palette.isOledDeepBlack
        val key = buildCacheKey(page, isOledMode, versionToken, maxEdgePx)
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        return withContext(renderDispatcher) {
            cache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            renderPageBitmap(
                page = page,
                palette = palette,
                liveSnapshot = liveSnapshot,
                maxEdgePx = maxEdgePx
            )?.also { rendered ->
                cache.put(key, rendered)
            }
        }
    }

    private suspend fun renderPageBitmap(
        page: PageEntity,
        palette: StylusSurfacePalette,
        liveSnapshot: PageThumbnailSourceSnapshot?,
        maxEdgePx: Int
    ): Bitmap? {
        return try {
            val paperCfg = page.pageSizePreset.toPagePaperConfig(
                canvasMode = CanvasMode.FIXED_PAGE,
                templateType = page.templateType
            )
            val dims = paperCfg.pageDimensions
            val (thumbW, thumbH) = computeThumbnailDimensionsPx(
                pageWidthPt = dims.widthPt,
                pageHeightPt = dims.heightPt,
                maxEdgePx = maxEdgePx
            )
            val pageScale = thumbW.toFloat() / dims.widthPt.coerceAtLeast(1f)
            val isOledMode = palette.isOledDeepBlack

            val snapshot = liveSnapshot ?: readPersistedSnapshotFromDisk(page.id)

            val pdfAssetId = page.pdfAssetId?.takeIf { it.isNotBlank() }
            val pdfBitmap = if (pdfAssetId != null && pdfTileRenderer != null) {
                pdfTileRenderer.prewarmBasePage(
                    pdfAssetId = pdfAssetId,
                    pageIndex = page.pdfPageIndex ?: 0,
                    docPageWidthPt = dims.widthPt,
                    docPageHeightPt = dims.heightPt
                )
            } else {
                null
            }

            // Pre-decode any embedded images on this IO thread into the shared bitmap store so drawing stays pure.
            val decodedImages = HashMap<String, Bitmap>()
            for (obj in snapshot.objects) {
                if (obj is PageObject.ImageObject) {
                    val bmp = CanvasImageBitmapStore.getOrDecodeBitmapSync(
                        filesDir = filesDir,
                        relativePath = obj.imageAssetRelativePath,
                        maxEdgePx = 512
                    )
                    if (bmp != null) {
                        decodedImages[obj.id] = bmp
                    }
                }
            }

            val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            PaperBackgroundRenderer.drawFixedPageIntoBitmapCanvas(
                canvas = canvas,
                pageScale = pageScale,
                paperConfig = paperCfg,
                palette = palette
            )

            canvas.save()
            canvas.scale(pageScale, pageScale)
            if (pdfBitmap != null && pdfTileRenderer != null) {
                pdfTileRenderer.drawPrewarmedBitmap(
                    canvas = canvas,
                    bitmap = pdfBitmap,
                    destRect = RectF(0f, 0f, dims.widthPt, dims.heightPt),
                    isOledMode = isOledMode
                )
                // Re-draw crisp border around PDF backdrop just like live Fixed Page rendering.
                PaperBackgroundRenderer.drawSheetBorderInCurrentDocSpace(
                    canvas = canvas,
                    pageWidthPt = dims.widthPt,
                    pageHeightPt = dims.heightPt,
                    scale = pageScale,
                    palette = palette
                )
            }

            if (snapshot.strokes.isNotEmpty() || snapshot.objects.isNotEmpty()) {
                CanvasPageVectorRenderer.drawPageForegroundLayers(
                    canvas = canvas,
                    strokes = snapshot.strokes,
                    objects = snapshot.objects,
                    liveInkStrokesById = snapshot.liveInkStrokesById,
                    isOledMode = isOledMode,
                    context = appContext,
                    imageBitmapResolver = { imgObj -> decodedImages[imgObj.id] },
                    recycleResolvedImageBitmaps = false
                )
            }
            canvas.restore()
            bitmap
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun readPersistedSnapshotFromDisk(pageId: String): PageThumbnailSourceSnapshot {
        val pageDir = File(filesDir, "pages/$pageId")
        if (!pageDir.isDirectory) {
            return PageThumbnailSourceSnapshot(emptyList(), emptyList())
        }
        val journal = StrokeJournal(pageDirectory = pageDir)
        return try {
            val recovery = journal.openAndRecover()
            PageThumbnailSourceSnapshot(
                strokes = recovery.strokes,
                objects = recovery.objects
            )
        } finally {
            journal.close(compactBeforeClose = false)
        }
    }

    private fun buildCacheKey(
        page: PageEntity,
        isOledMode: Boolean,
        versionToken: Long,
        maxEdgePx: Int
    ): String =
        "${page.id}::$versionToken::${page.updatedAtEpochMs}::$isOledMode::" +
            "${page.templateType.name}::${page.pageSizePreset.name}::" +
            "${page.pdfAssetId ?: ""}:${page.pdfPageIndex ?: -1}::$maxEdgePx"

    companion object {
        const val DEFAULT_THUMBNAIL_MAX_EDGE_PX: Int = 280
        private const val DEFAULT_CACHE_BYTES: Int = 16 * 1024 * 1024

        fun computeThumbnailDimensionsPx(
            pageWidthPt: Float,
            pageHeightPt: Float,
            maxEdgePx: Int = DEFAULT_THUMBNAIL_MAX_EDGE_PX
        ): Pair<Int, Int> {
            val safeW = pageWidthPt.coerceAtLeast(1f)
            val safeH = pageHeightPt.coerceAtLeast(1f)
            val safeMax = maxEdgePx.coerceAtLeast(32)
            val scale = safeMax.toFloat() / max(safeW, safeH)
            val widthPx = (safeW * scale).roundToInt().coerceAtLeast(16)
            val heightPx = (safeH * scale).roundToInt().coerceAtLeast(16)
            return widthPx to heightPx
        }
    }
}
