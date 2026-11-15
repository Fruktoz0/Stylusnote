package hu.bdev.stylusnote.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.ui.canvas.TileLruTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thrown when importing an encrypted / password-protected PDF file (Section 8).
 */
class PasswordProtectedPdfException(
    message: String = "A jelszóvédett PDF fájlok nem támogatottak."
) : IllegalArgumentException(message)

data class ImportedPdfDocument(
    val pdfAssetId: String,
    val relativeFilePath: String,
    val pageCount: Int,
    val firstPageDimensionsPt: PageDimensionsPt
)

data class PdfAspectFitPlacement(
    val scalePdfToDoc: Float,
    val offsetXDocPt: Float,
    val offsetYDocPt: Float,
    val fittedWidthDocPt: Float,
    val fittedHeightDocPt: Float
)

data class PdfRenderMatrixParams(
    val scaleX: Float,
    val scaleY: Float,
    val translateXPx: Float,
    val translateYPx: Float
)

data class PdfDetailTileSpec(
    val cropDocRect: DocRect,
    val outputWidthPx: Int,
    val outputHeightPx: Int
)

private data class PdfTileKey(
    val pdfAssetId: String,
    val pageIndex: Int,
    val leftMilliPt: Int,
    val topMilliPt: Int,
    val rightMilliPt: Int,
    val bottomMilliPt: Int,
    val docWidthCentiPt: Int,
    val docHeightCentiPt: Int,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Layer-2 tiled PDF renderer backed by Android's [PdfRenderer] (Section 8).
 *
 * Enforces Android's strict single-open-page invariant by serializing every `openPage` + `render` +
 * `close` cycle under [pdfMutex] on a single-threaded dispatcher ([pdfDispatcher]), supports
 * aspect-fitted live canvas background + high-DPI zoom tile rendering without stalling 144 Hz UI
 * draws, and provides an optional OLED [ColorMatrix] inversion [Paint].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfTileRenderer(
    private val filesDir: File,
    maxTileCacheBytes: Long = DEFAULT_PDF_TILE_CACHE_BYTES,
    private val pdfDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    private val pdfMutex = Mutex()
    private val renderScope = CoroutineScope(SupervisorJob() + pdfDispatcher)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<PdfTileKey>()
    private val failedKeys = ConcurrentHashMap.newKeySet<PdfTileKey>()
    private val defaultBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scratchDestRect = ThreadLocal.withInitial { RectF() }

    // Never call Bitmap.recycle() in LRU eviction because concurrent UI onDraw passes may still
    // reference a recently evicted bitmap; NativeAllocationRegistry frees native memory once GC'd.
    private val tileCache = TileLruTracker<PdfTileKey, Bitmap>(
        maxBytes = maxTileCacheBytes
    )

    val oledInversionPaint: Paint by lazy {
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(invertMatrix)
        }
    }

    fun resolvePdfFile(pdfAssetId: String): File =
        File(File(filesDir, PDF_SUBDIR), "$pdfAssetId.pdf")

    /**
     * Copies [inputStream] into internal storage (`pdfs/<pdfAssetId>.pdf`) and verifies via
     * [PdfRenderer] that the document is readable and not password-protected.
     */
    suspend fun importPdf(
        inputStream: InputStream,
        pdfAssetId: String = UUID.randomUUID().toString()
    ): ImportedPdfDocument = withContext(pdfDispatcher) {
        pdfMutex.withLock {
            val pdfDir = File(filesDir, PDF_SUBDIR).apply { mkdirs() }
            val destFile = File(pdfDir, "$pdfAssetId.pdf")
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }

            try {
                openRendererLocked(destFile) { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount <= 0) {
                        destFile.delete()
                        throw IllegalArgumentException("A PDF dokumentum üres.")
                    }
                    val firstDims = renderer.openPage(0).use { page ->
                        when {
                            page.width > page.height -> PageDimensionsPt.LANDSCAPE_16_10
                            page.width <= 520 -> PageDimensionsPt.B5_ISO_PORTRAIT
                            else -> PageDimensionsPt.A4_PORTRAIT
                        }
                    }
                    ImportedPdfDocument(
                        pdfAssetId = pdfAssetId,
                        relativeFilePath = "$PDF_SUBDIR/$pdfAssetId.pdf",
                        pageCount = pageCount,
                        firstPageDimensionsPt = firstDims
                    )
                }
            } catch (sec: SecurityException) {
                destFile.delete()
                throw PasswordProtectedPdfException().apply { initCause(sec) }
            } catch (t: Throwable) {
                destFile.delete()
                throw t
            }
        }
    }

    /**
     * Returns an already-cached base bitmap synchronously for immediate UI/thumbnail compositing,
     * without touching disk or acquiring [pdfMutex].
     */
    fun getCachedBaseBitmap(
        pdfAssetId: String,
        pageIndex: Int,
        docPageWidthPt: Float,
        docPageHeightPt: Float
    ): Bitmap? {
        val (baseW, baseH) = computeBaseBitmapSizePx(docPageWidthPt, docPageHeightPt)
        val baseRect = DocRect(0f, 0f, docPageWidthPt.coerceAtLeast(1f), docPageHeightPt.coerceAtLeast(1f))
        val key = makeTileKey(
            pdfAssetId = pdfAssetId,
            pageIndex = pageIndex,
            cropDocRect = baseRect,
            docPageWidthPt = docPageWidthPt,
            docPageHeightPt = docPageHeightPt,
            widthPx = baseW,
            heightPx = baseH
        )
        return tileCache.get(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Renders or retrieves the canonical aspect-fitted base-resolution bitmap for a PDF page.
     */
    suspend fun prewarmBasePage(
        pdfAssetId: String,
        pageIndex: Int,
        docPageWidthPt: Float,
        docPageHeightPt: Float
    ): Bitmap? {
        val (baseW, baseH) = computeBaseBitmapSizePx(docPageWidthPt, docPageHeightPt)
        val baseRect = DocRect(0f, 0f, docPageWidthPt.coerceAtLeast(1f), docPageHeightPt.coerceAtLeast(1f))
        return renderTile(
            pdfAssetId = pdfAssetId,
            pageIndex = pageIndex,
            cropDocRect = baseRect,
            outputWidthPx = baseW,
            outputHeightPx = baseH,
            docPageWidthPt = docPageWidthPt,
            docPageHeightPt = docPageHeightPt
        )
    }

    /** Draws a cached/prewarmed PDF [bitmap] into [destRect] applying OLED inversion when needed. */
    fun drawPrewarmedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        destRect: RectF,
        isOledMode: Boolean
    ) {
        if (bitmap.isRecycled) return
        val paint = if (isOledMode) oledInversionPaint else defaultBitmapPaint
        canvas.drawBitmap(bitmap, null, destRect, paint)
    }

    /**
     * Draws a cached PDF page (and sharp zoomed detail tiles when settled past base resolution)
     * synchronously in the current page coordinate space `0..docPageWidthPt x 0..docPageHeightPt`.
     * Never blocks the UI draw thread; missing bitmaps are enqueued asynchronously and invoke
     * [onTileReady] once rasterized.
     */
    fun drawPdfPageForCanvasSyncOrRequest(
        canvas: Canvas,
        pdfAssetId: String,
        pageIndex: Int,
        docPageWidthPt: Float,
        docPageHeightPt: Float,
        visibleLocalDocRect: DocRect,
        currentScale: Float,
        isOledMode: Boolean,
        isGestureInProgress: Boolean,
        onTileReady: () -> Unit
    ) {
        val safeW = docPageWidthPt.coerceAtLeast(1f)
        val safeH = docPageHeightPt.coerceAtLeast(1f)
        val (baseW, baseH) = computeBaseBitmapSizePx(safeW, safeH)
        val fullDocRect = DocRect(0f, 0f, safeW, safeH)
        val baseKey = makeTileKey(
            pdfAssetId = pdfAssetId,
            pageIndex = pageIndex,
            cropDocRect = fullDocRect,
            docPageWidthPt = safeW,
            docPageHeightPt = safeH,
            widthPx = baseW,
            heightPx = baseH
        )
        val paint = if (isOledMode) oledInversionPaint else defaultBitmapPaint
        val destRect = scratchDestRect.get()!!

        val baseBitmap = tileCache.get(baseKey)?.takeIf { !it.isRecycled }
        if (baseBitmap != null) {
            destRect.set(0f, 0f, safeW, safeH)
            canvas.drawBitmap(baseBitmap, null, destRect, paint)
        } else {
            enqueueTileRenderAsync(
                key = baseKey,
                pdfAssetId = pdfAssetId,
                pageIndex = pageIndex,
                cropDocRect = fullDocRect,
                outputWidthPx = baseW,
                outputHeightPx = baseH,
                docPageWidthPt = safeW,
                docPageHeightPt = safeH,
                onTileReady = onTileReady
            )
        }

        if (baseBitmap != null && !isGestureInProgress) {
            val detailSpecs = computeVisibleDetailTiles(
                docPageWidthPt = safeW,
                docPageHeightPt = safeH,
                visibleLocalDocRect = visibleLocalDocRect,
                currentScale = currentScale,
                baseMaxEdgePx = BASE_PAGE_MAX_EDGE_PX
            )
            for (i in 0 until detailSpecs.size) {
                val spec = detailSpecs[i]
                val detailKey = makeTileKey(
                    pdfAssetId = pdfAssetId,
                    pageIndex = pageIndex,
                    cropDocRect = spec.cropDocRect,
                    docPageWidthPt = safeW,
                    docPageHeightPt = safeH,
                    widthPx = spec.outputWidthPx,
                    heightPx = spec.outputHeightPx
                )
                val detailBitmap = tileCache.get(detailKey)?.takeIf { !it.isRecycled }
                if (detailBitmap != null) {
                    destRect.set(
                        spec.cropDocRect.leftPt,
                        spec.cropDocRect.topPt,
                        spec.cropDocRect.rightPt,
                        spec.cropDocRect.bottomPt
                    )
                    canvas.drawBitmap(detailBitmap, null, destRect, paint)
                } else {
                    enqueueTileRenderAsync(
                        key = detailKey,
                        pdfAssetId = pdfAssetId,
                        pageIndex = pageIndex,
                        cropDocRect = spec.cropDocRect,
                        outputWidthPx = spec.outputWidthPx,
                        outputHeightPx = spec.outputHeightPx,
                        docPageWidthPt = safeW,
                        docPageHeightPt = safeH,
                        onTileReady = onTileReady
                    )
                }
            }
        }
    }

    private fun enqueueTileRenderAsync(
        key: PdfTileKey,
        pdfAssetId: String,
        pageIndex: Int,
        cropDocRect: DocRect,
        outputWidthPx: Int,
        outputHeightPx: Int,
        docPageWidthPt: Float,
        docPageHeightPt: Float,
        onTileReady: () -> Unit
    ) {
        if (failedKeys.contains(key)) return
        if (!inFlightKeys.add(key)) return

        renderScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = renderTile(
                    pdfAssetId = pdfAssetId,
                    pageIndex = pageIndex,
                    cropDocRect = cropDocRect,
                    outputWidthPx = outputWidthPx,
                    outputHeightPx = outputHeightPx,
                    docPageWidthPt = docPageWidthPt,
                    docPageHeightPt = docPageHeightPt
                )
                if (rendered == null) {
                    if (failedKeys.size > MAX_FAILED_KEYS) failedKeys.clear()
                    failedKeys.add(key)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (failedKeys.size > MAX_FAILED_KEYS) failedKeys.clear()
                failedKeys.add(key)
            } finally {
                inFlightKeys.remove(key)
            }
            if (rendered != null) {
                onTileReady()
            }
        }
    }

    /**
     * Renders only the visible document-space region [cropDocRect] of [pageIndex] into a sharp
     * `[outputWidthPx x outputHeightPx]` bitmap, returning a cached copy when available.
     * PDF native bounds are aspect-fitted centered inside `[docPageWidthPt x docPageHeightPt]`.
     */
    suspend fun renderTile(
        pdfAssetId: String,
        pageIndex: Int,
        cropDocRect: DocRect,
        outputWidthPx: Int,
        outputHeightPx: Int,
        docPageWidthPt: Float = PageDimensionsPt.A4_PORTRAIT.widthPt,
        docPageHeightPt: Float = PageDimensionsPt.A4_PORTRAIT.heightPt
    ): Bitmap? = withContext(pdfDispatcher) {
        if (outputWidthPx <= 0 || outputHeightPx <= 0 || cropDocRect.isEmpty) {
            return@withContext null
        }

        val clampedW = outputWidthPx.coerceIn(1, MAX_TILE_DIMENSION_PX)
        val clampedH = outputHeightPx.coerceIn(1, MAX_TILE_DIMENSION_PX)
        val safeDocW = docPageWidthPt.coerceAtLeast(1f)
        val safeDocH = docPageHeightPt.coerceAtLeast(1f)
        val key = makeTileKey(
            pdfAssetId = pdfAssetId,
            pageIndex = pageIndex,
            cropDocRect = cropDocRect,
            docPageWidthPt = safeDocW,
            docPageHeightPt = safeDocH,
            widthPx = clampedW,
            heightPx = clampedH
        )

        tileCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

        val pdfFile = resolvePdfFile(pdfAssetId)
        if (!pdfFile.exists()) return@withContext null

        pdfMutex.withLock {
            tileCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withLock it }

            openRendererLocked(pdfFile) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@openRendererLocked null

                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(clampedW, clampedH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    val matrixParams = computePdfRenderMatrixParams(
                        pdfPageWidth = page.width,
                        pdfPageHeight = page.height,
                        docPageWidthPt = safeDocW,
                        docPageHeightPt = safeDocH,
                        cropDocRect = cropDocRect,
                        outputWidthPx = clampedW,
                        outputHeightPx = clampedH
                    )
                    val renderMatrix = Matrix().apply {
                        setScale(matrixParams.scaleX, matrixParams.scaleY)
                        postTranslate(matrixParams.translateXPx, matrixParams.translateYPx)
                    }

                    page.render(
                        bitmap,
                        null,
                        renderMatrix,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    tileCache.put(key, bitmap, bitmap.allocationByteCount.toLong())
                    bitmap
                }
            }
        }
    }

    /**
     * Renders a full PDF page synchronously onto an export [Canvas] at canonical light colors.
     */
    suspend fun renderPageForExport(
        pdfAssetId: String,
        pageIndex: Int,
        targetCanvas: Canvas,
        targetWidthPt: Float,
        targetHeightPt: Float,
        dpiScale: Float = EXPORT_RASTER_SCALE
    ): Boolean = withContext(pdfDispatcher) {
        val pdfFile = resolvePdfFile(pdfAssetId)
        if (!pdfFile.exists()) return@withContext false

        val safeTargetW = targetWidthPt.coerceAtLeast(1f)
        val safeTargetH = targetHeightPt.coerceAtLeast(1f)
        val pxW = (safeTargetW * dpiScale).roundToInt().coerceIn(64, MAX_EXPORT_BITMAP_PX)
        val pxH = (safeTargetH * dpiScale).roundToInt().coerceIn(64, MAX_EXPORT_BITMAP_PX)

        val rendered = pdfMutex.withLock {
            openRendererLocked(pdfFile) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@openRendererLocked null
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val matrixParams = computePdfRenderMatrixParams(
                        pdfPageWidth = page.width,
                        pdfPageHeight = page.height,
                        docPageWidthPt = safeTargetW,
                        docPageHeightPt = safeTargetH,
                        cropDocRect = DocRect(0f, 0f, safeTargetW, safeTargetH),
                        outputWidthPx = pxW,
                        outputHeightPx = pxH
                    )
                    val matrix = Matrix().apply {
                        setScale(matrixParams.scaleX, matrixParams.scaleY)
                        postTranslate(matrixParams.translateXPx, matrixParams.translateYPx)
                    }
                    page.render(
                        bitmap,
                        null,
                        matrix,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    )
                    bitmap
                }
            }
        } ?: return@withContext false

        try {
            val destRect = RectF(0f, 0f, safeTargetW, safeTargetH)
            targetCanvas.drawBitmap(rendered, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
            true
        } finally {
            rendered.recycle()
        }
    }

    fun clearCache() {
        inFlightKeys.clear()
        failedKeys.clear()
        tileCache.clear()
    }

    fun close() {
        renderScope.cancel()
        clearCache()
    }

    private fun makeTileKey(
        pdfAssetId: String,
        pageIndex: Int,
        cropDocRect: DocRect,
        docPageWidthPt: Float,
        docPageHeightPt: Float,
        widthPx: Int,
        heightPx: Int
    ): PdfTileKey = PdfTileKey(
        pdfAssetId = pdfAssetId,
        pageIndex = pageIndex,
        leftMilliPt = (cropDocRect.leftPt * 1000f).roundToInt(),
        topMilliPt = (cropDocRect.topPt * 1000f).roundToInt(),
        rightMilliPt = (cropDocRect.rightPt * 1000f).roundToInt(),
        bottomMilliPt = (cropDocRect.bottomPt * 1000f).roundToInt(),
        docWidthCentiPt = (docPageWidthPt * 100f).roundToInt(),
        docHeightCentiPt = (docPageHeightPt * 100f).roundToInt(),
        widthPx = widthPx,
        heightPx = heightPx
    )

    private inline fun <T> openRendererLocked(
        pdfFile: File,
        block: (PdfRenderer) -> T
    ): T {
        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return fd.use { pfd ->
            PdfRenderer(pfd).use(block)
        }
    }

    companion object {
        const val PDF_SUBDIR: String = "pdfs"
        const val DEFAULT_PDF_TILE_CACHE_BYTES: Long = 64L * 1024L * 1024L
        const val BASE_PAGE_MAX_EDGE_PX: Int = 1440
        private const val DETAIL_TILE_DOC_PT: Float = 300f
        private const val DETAIL_ZOOM_THRESHOLD: Float = 1.35f
        private const val MAX_DETAIL_SCALE: Float = 4.0f
        private const val MAX_DETAIL_TILE_PX: Int = 1024
        private const val MAX_DETAIL_TILES_PER_FRAME: Int = 16
        private const val MAX_FAILED_KEYS: Int = 256
        private const val MAX_TILE_DIMENSION_PX: Int = 2048
        private const val MAX_EXPORT_BITMAP_PX: Int = 3600
        private const val EXPORT_RASTER_SCALE: Float = 2.0f

        /**
         * Uniformly aspect-fits native PDF coordinates `(0..pdfPageWidth, 0..pdfPageHeight)` centered
         * inside `[0..docPageWidthPt, 0..docPageHeightPt]` so non-A4 PDFs never stretch or crop.
         */
        fun computePdfAspectFitPlacement(
            pdfPageWidth: Int,
            pdfPageHeight: Int,
            docPageWidthPt: Float,
            docPageHeightPt: Float
        ): PdfAspectFitPlacement {
            val srcW = pdfPageWidth.coerceAtLeast(1).toFloat()
            val srcH = pdfPageHeight.coerceAtLeast(1).toFloat()
            val safeDocW = docPageWidthPt.coerceAtLeast(1f)
            val safeDocH = docPageHeightPt.coerceAtLeast(1f)
            val fitScale = min(safeDocW / srcW, safeDocH / srcH)
            val fittedW = srcW * fitScale
            val fittedH = srcH * fitScale
            return PdfAspectFitPlacement(
                scalePdfToDoc = fitScale,
                offsetXDocPt = (safeDocW - fittedW) * 0.5f,
                offsetYDocPt = (safeDocH - fittedH) * 0.5f,
                fittedWidthDocPt = fittedW,
                fittedHeightDocPt = fittedH
            )
        }

        /**
         * Computes the exact scale and pixel translation needed for `PdfRenderer.Page.render`
         * so [cropDocRect] in document points maps onto `[0..outputWidthPx, 0..outputHeightPx]`.
         */
        fun computePdfRenderMatrixParams(
            pdfPageWidth: Int,
            pdfPageHeight: Int,
            docPageWidthPt: Float,
            docPageHeightPt: Float,
            cropDocRect: DocRect,
            outputWidthPx: Int,
            outputHeightPx: Int
        ): PdfRenderMatrixParams {
            val fit = computePdfAspectFitPlacement(
                pdfPageWidth = pdfPageWidth,
                pdfPageHeight = pdfPageHeight,
                docPageWidthPt = docPageWidthPt,
                docPageHeightPt = docPageHeightPt
            )
            val cropW = cropDocRect.widthPt.coerceAtLeast(0.001f)
            val cropH = cropDocRect.heightPt.coerceAtLeast(0.001f)
            val outScaleX = outputWidthPx.toFloat() / cropW
            val outScaleY = outputHeightPx.toFloat() / cropH
            return PdfRenderMatrixParams(
                scaleX = fit.scalePdfToDoc * outScaleX,
                scaleY = fit.scalePdfToDoc * outScaleY,
                translateXPx = (fit.offsetXDocPt - cropDocRect.leftPt) * outScaleX,
                translateYPx = (fit.offsetYDocPt - cropDocRect.topPt) * outScaleY
            )
        }

        fun computeBaseBitmapSizePx(
            docPageWidthPt: Float,
            docPageHeightPt: Float,
            maxEdgePx: Int = BASE_PAGE_MAX_EDGE_PX
        ): Pair<Int, Int> {
            val safeW = docPageWidthPt.coerceAtLeast(1f)
            val safeH = docPageHeightPt.coerceAtLeast(1f)
            val longest = max(safeW, safeH)
            val scale = maxEdgePx.toFloat() / longest
            return Pair(
                (safeW * scale).roundToInt().coerceIn(64, maxEdgePx),
                (safeH * scale).roundToInt().coerceIn(64, maxEdgePx)
            )
        }

        fun computeVisibleDetailTiles(
            docPageWidthPt: Float,
            docPageHeightPt: Float,
            visibleLocalDocRect: DocRect,
            currentScale: Float,
            baseMaxEdgePx: Int = BASE_PAGE_MAX_EDGE_PX
        ): List<PdfDetailTileSpec> {
            val safeW = docPageWidthPt.coerceAtLeast(1f)
            val safeH = docPageHeightPt.coerceAtLeast(1f)
            val baseScale = baseMaxEdgePx.toFloat() / max(safeW, safeH)
            if (currentScale <= baseScale * DETAIL_ZOOM_THRESHOLD) return emptyList()

            val left = max(0f, visibleLocalDocRect.leftPt)
            val top = max(0f, visibleLocalDocRect.topPt)
            val right = min(safeW, visibleLocalDocRect.rightPt)
            val bottom = min(safeH, visibleLocalDocRect.bottomPt)
            if (right <= left || bottom <= top) return emptyList()

            val quantizedScale = (ceil(currentScale * 2f) * 0.5f)
                .coerceIn(baseScale * 1.25f, MAX_DETAIL_SCALE)
            val colStart = floor(left / DETAIL_TILE_DOC_PT).toInt().coerceAtLeast(0)
            val colEnd = floor((right - 0.01f) / DETAIL_TILE_DOC_PT).toInt().coerceAtLeast(colStart)
            val rowStart = floor(top / DETAIL_TILE_DOC_PT).toInt().coerceAtLeast(0)
            val rowEnd = floor((bottom - 0.01f) / DETAIL_TILE_DOC_PT).toInt().coerceAtLeast(rowStart)

            val cols = colEnd - colStart + 1
            val rows = rowEnd - rowStart + 1
            if (cols * rows > MAX_DETAIL_TILES_PER_FRAME) return emptyList()

            val result = ArrayList<PdfDetailTileSpec>(cols * rows)
            for (row in rowStart..rowEnd) {
                val cellTop = row * DETAIL_TILE_DOC_PT
                val cellBottom = min(safeH, (row + 1) * DETAIL_TILE_DOC_PT)
                if (cellBottom <= cellTop) continue
                val cellH = cellBottom - cellTop
                for (col in colStart..colEnd) {
                    val cellLeft = col * DETAIL_TILE_DOC_PT
                    val cellRight = min(safeW, (col + 1) * DETAIL_TILE_DOC_PT)
                    if (cellRight <= cellLeft) continue
                    val cellW = cellRight - cellLeft
                    val outW = (cellW * quantizedScale).roundToInt().coerceIn(64, MAX_DETAIL_TILE_PX)
                    val outH = (cellH * quantizedScale).roundToInt().coerceIn(64, MAX_DETAIL_TILE_PX)
                    result.add(
                        PdfDetailTileSpec(
                            cropDocRect = DocRect(cellLeft, cellTop, cellRight, cellBottom),
                            outputWidthPx = outW,
                            outputHeightPx = outH
                        )
                    )
                }
            }
            return result
        }
    }
}

