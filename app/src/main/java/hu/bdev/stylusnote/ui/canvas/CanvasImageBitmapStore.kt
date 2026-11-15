package hu.bdev.stylusnote.ui.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.withSave
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.PageObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs

/**
 * Snapshot of an active object drag/resize/rotate interaction before pointer release commits it
 * atomically into Undo/Redo history and the binary stroke journal.
 */
data class LiveObjectInteractionPreview(
    val pageId: String?,
    val objectId: String,
    val beforeObject: PageObject,
    val previewObject: PageObject,
    val attachedAffine: AffineTransform2D
)

/**
 * Lightweight 144 Hz bridge allowing [PageObjectsOverlayLayer] drag/resize/rotate gestures to
 * notify [StylusDrawingSurface]'s `DryCanvasView` immediately without rebuilding Compose or
 * writing intermediate Undo frames.
 */
class LiveObjectInteractionController {
    @Volatile
    var activePreview: LiveObjectInteractionPreview? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun updatePreview(preview: LiveObjectInteractionPreview?) {
        activePreview = preview
        for (i in 0 until listeners.size) {
            listeners[i].invoke()
        }
    }
}

/**
 * Shared decoded bitmap LRU store for under-ink [PageObject.ImageObject] rendering across the live
 * 144 Hz canvas and background page thumbnail compositor.
 *
 * Bitmaps are never recycled synchronously on LRU eviction to avoid draw-thread vs eviction races.
 */
object CanvasImageBitmapStore {
    private const val CACHE_KB = 32 * 1024
    const val DEFAULT_MAX_EDGE_PX: Int = 1600
    private const val MAX_FAILED_KEYS = 128

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
    }

    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scratchDestRect = ThreadLocal.withInitial { RectF() }

    private fun cacheKey(filesDir: File, relativePath: String): String =
        "${filesDir.absolutePath}::$relativePath"

    fun getCached(filesDir: File, relativePath: String): Bitmap? {
        val bmp = cache.get(cacheKey(filesDir, relativePath)) ?: return null
        return if (!bmp.isRecycled) bmp else null
    }

    suspend fun getOrDecodeBitmap(
        filesDir: File,
        relativePath: String,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX
    ): Bitmap? {
        val key = cacheKey(filesDir, relativePath)
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            decodeAndCache(filesDir = filesDir, relativePath = relativePath, maxEdgePx = maxEdgePx)
        }
    }

    /**
     * Synchronously returns a cached bitmap or decodes it into the shared LRU cache.
     * Intended for worker threads (`Dispatchers.IO`) such as background thumbnail compositors.
     */
    fun getOrDecodeBitmapSync(
        filesDir: File,
        relativePath: String,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX
    ): Bitmap? {
        val key = cacheKey(filesDir, relativePath)
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        return decodeAndCache(filesDir = filesDir, relativePath = relativePath, maxEdgePx = maxEdgePx)
    }

    private fun decodeAndCache(
        filesDir: File,
        relativePath: String,
        maxEdgePx: Int
    ): Bitmap? {
        val file = File(filesDir, relativePath)
        if (!file.isFile) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val rawMax = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        if (rawMax <= 0) return null
        var sampleSize = 1
        while (rawMax / sampleSize > maxEdgePx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        cache.put(cacheKey(filesDir, relativePath), decoded)
        return decoded
    }

    /**
     * Draws all [PageObject.ImageObject] instances on a page inside the current document-space
     * [canvas] (Layer 2b — beneath highlighters and ink). Missing bitmaps are decoded
     * asynchronously off the UI thread and trigger [onImageReady].
     */
    fun drawImageObjectsSyncOrRequest(
        canvas: Canvas,
        filesDir: File,
        pageId: String?,
        pageObjects: List<PageObject>,
        livePreview: LiveObjectInteractionPreview? = null,
        lassoPreviewObjectIds: Set<String> = emptySet(),
        lassoLiveAffine: AffineTransform2D? = null,
        isOledMode: Boolean = false,
        onImageReady: () -> Unit = {}
    ) {
        if (pageObjects.isEmpty()) return
        var images: MutableList<PageObject.ImageObject>? = null
        for (i in 0 until pageObjects.size) {
            val obj = pageObjects[i]
            if (obj is PageObject.ImageObject) {
                if (images == null) images = ArrayList(4)
                images.add(obj)
            }
        }
        val imageList = images ?: return
        if (imageList.size > 1) {
            imageList.sortWith { a, b -> a.zOrder.compareTo(b.zOrder) }
        }

        val destRect = scratchDestRect.get()!!
        placeholderPaint.color = if (isOledMode) 0x33FFFFFF else 0x1F64748B
        placeholderPaint.style = Paint.Style.FILL

        for (i in 0 until imageList.size) {
            val rawObj = imageList[i]
            val effective = when {
                livePreview != null && livePreview.pageId == pageId && livePreview.objectId == rawObj.id ->
                    (livePreview.previewObject as? PageObject.ImageObject) ?: rawObj
                lassoLiveAffine != null && rawObj.id in lassoPreviewObjectIds ->
                    (rawObj.transform(lassoLiveAffine) as? PageObject.ImageObject) ?: rawObj
                else -> rawObj
            }

            val bmp = getCached(filesDir, effective.imageAssetRelativePath)
            destRect.set(
                effective.bounds.leftPt,
                effective.bounds.topPt,
                effective.bounds.rightPt,
                effective.bounds.bottomPt
            )
            val deg = (effective.rotationRadians * 180.0 / PI).toFloat()

            canvas.withSave {
                if (abs(deg) > 0.01f) {
                    rotate(deg, destRect.centerX(), destRect.centerY())
                }
                if (bmp != null) {
                    drawBitmap(bmp, null, destRect, bitmapPaint)
                } else {
                    drawRect(destRect, placeholderPaint)
                    enqueueDecodeAsync(
                        filesDir = filesDir,
                        relativePath = effective.imageAssetRelativePath,
                        onImageReady = onImageReady
                    )
                }
            }
        }
    }

    private fun enqueueDecodeAsync(
        filesDir: File,
        relativePath: String,
        onImageReady: () -> Unit
    ) {
        val key = cacheKey(filesDir, relativePath)
        if (failedKeys.contains(key)) return
        if (!inFlightKeys.add(key)) return

        decodeScope.launch {
            var decoded: Bitmap? = null
            try {
                decoded = decodeAndCache(filesDir, relativePath, DEFAULT_MAX_EDGE_PX)
                if (decoded == null) {
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
            if (decoded != null) {
                onImageReady()
            }
        }
    }
}
