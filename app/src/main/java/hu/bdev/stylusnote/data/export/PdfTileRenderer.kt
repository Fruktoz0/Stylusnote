package hu.bdev.stylusnote.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.ui.canvas.TileLruTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
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

private data class PdfTileKey(
    val pdfAssetId: String,
    val pageIndex: Int,
    val leftMilliPt: Int,
    val topMilliPt: Int,
    val rightMilliPt: Int,
    val bottomMilliPt: Int,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Layer-2 tiled PDF renderer backed by Android's [PdfRenderer] (Section 8).
 *
 * Enforces Android's strict single-open-page invariant by serializing every `openPage` + `render` +
 * `close` cycle under [pdfMutex] on a single-threaded dispatcher ([pdfDispatcher]), supports
 * cropped high-DPI zoom tile rendering, and provides an optional OLED [ColorMatrix] inversion [Paint].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfTileRenderer(
    private val filesDir: File,
    maxTileCacheBytes: Long = DEFAULT_PDF_TILE_CACHE_BYTES,
    private val pdfDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    private val pdfMutex = Mutex()
    private val tileCache = TileLruTracker<PdfTileKey, Bitmap>(
        maxBytes = maxTileCacheBytes,
        onEvict = { _, bmp -> if (!bmp.isRecycled) bmp.recycle() }
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
     * Renders only the visible document-space region [cropDocRect] of [pageIndex] into a sharp
     * `[outputWidthPx x outputHeightPx]` bitmap, returning a cached copy when available.
     */
    suspend fun renderTile(
        pdfAssetId: String,
        pageIndex: Int,
        cropDocRect: DocRect,
        outputWidthPx: Int,
        outputHeightPx: Int
    ): Bitmap? = withContext(pdfDispatcher) {
        if (outputWidthPx <= 0 || outputHeightPx <= 0 || cropDocRect.isEmpty) {
            return@withContext null
        }

        val clampedW = outputWidthPx.coerceIn(1, MAX_TILE_DIMENSION_PX)
        val clampedH = outputHeightPx.coerceIn(1, MAX_TILE_DIMENSION_PX)
        val key = PdfTileKey(
            pdfAssetId = pdfAssetId,
            pageIndex = pageIndex,
            leftMilliPt = (cropDocRect.leftPt * 1000f).roundToInt(),
            topMilliPt = (cropDocRect.topPt * 1000f).roundToInt(),
            rightMilliPt = (cropDocRect.rightPt * 1000f).roundToInt(),
            bottomMilliPt = (cropDocRect.bottomPt * 1000f).roundToInt(),
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

                    val scaleX = clampedW.toFloat() / cropDocRect.widthPt
                    val scaleY = clampedH.toFloat() / cropDocRect.heightPt
                    val renderMatrix = Matrix().apply {
                        postTranslate(-cropDocRect.leftPt, -cropDocRect.topPt)
                        postScale(scaleX, scaleY)
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

        val pxW = (targetWidthPt * dpiScale).roundToInt().coerceIn(64, MAX_EXPORT_BITMAP_PX)
        val pxH = (targetHeightPt * dpiScale).roundToInt().coerceIn(64, MAX_EXPORT_BITMAP_PX)

        val rendered = pdfMutex.withLock {
            openRendererLocked(pdfFile) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@openRendererLocked null
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setScale(
                            pxW.toFloat() / page.width.coerceAtLeast(1).toFloat(),
                            pxH.toFloat() / page.height.coerceAtLeast(1).toFloat()
                        )
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
            val destRect = android.graphics.RectF(0f, 0f, targetWidthPt, targetHeightPt)
            targetCanvas.drawBitmap(rendered, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
            true
        } finally {
            rendered.recycle()
        }
    }

    fun clearCache() {
        tileCache.clear()
    }

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
        const val DEFAULT_PDF_TILE_CACHE_BYTES: Long = 48L * 1024L * 1024L
        private const val MAX_TILE_DIMENSION_PX: Int = 2048
        private const val MAX_EXPORT_BITMAP_PX: Int = 3600
        private const val EXPORT_RASTER_SCALE: Float = 2.0f
    }
}

