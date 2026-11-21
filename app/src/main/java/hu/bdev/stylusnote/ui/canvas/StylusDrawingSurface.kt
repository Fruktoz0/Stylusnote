package hu.bdev.stylusnote.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import hu.bdev.stylusnote.core.hardware.AndroidMotionEventPredictorBridge
import hu.bdev.stylusnote.core.hardware.HoverCursorState
import hu.bdev.stylusnote.core.hardware.StylusInputRouter
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.WetStrokeSink
import hu.bdev.stylusnote.core.hardware.onGenericMotionEvent
import hu.bdev.stylusnote.core.hardware.onHoverEvent
import hu.bdev.stylusnote.core.hardware.onTouchEvent
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.OledStrokeRenderCache
import hu.bdev.stylusnote.core.math.OneEuroFilter
import hu.bdev.stylusnote.core.math.PageLayoutSlot
import hu.bdev.stylusnote.core.math.SavedViewportAnchor
import hu.bdev.stylusnote.core.math.ShapeRecognizer
import hu.bdev.stylusnote.core.math.SpatialIndex
import hu.bdev.stylusnote.core.math.toDocRect
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import hu.bdev.stylusnote.ui.theme.StylusIcons
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Contract for storing committed dry strokes and maintaining their [SpatialIndex].
 * Phase 1c uses [InMemoryDryStrokeStore]; Phase 2a+ uses [CanvasStateHolder].
 */
interface DryStrokeStore {
    val spatialIndex: SpatialIndex<IndexedDryStroke>
    fun addCommittedStrokes(strokes: List<IndexedDryStroke>)
    fun removeStroke(id: String): Boolean
    fun clear()
}

/**
 * Contract for appending newly finished strokes to the persistent journal.
 * Phase 1c uses [NoOpJournalCommitSink]; Phase 3a swaps in `StrokeJournal`.
 */
interface JournalCommitSink {
    fun onStrokesCommitted(strokes: List<IndexedDryStroke>)
    fun onStrokesTransformed(strokeIds: List<String>, affine: AffineTransform2D) = Unit
    fun onStrokesRecolored(strokeIds: List<String>, canonicalColorArgb: Int) = Unit
    fun onStrokesDeleted(strokeIds: List<String>) = Unit
    fun onObjectsAdded(objects: List<PageObject>) = Unit
    fun onObjectsUpdated(objects: List<PageObject>) = Unit
    fun onObjectsDeleted(objectIds: List<String>) = Unit
    fun onCanvasActionApplied(action: CanvasAction) = Unit
}

object NoOpJournalCommitSink : JournalCommitSink {
    override fun onStrokesCommitted(strokes: List<IndexedDryStroke>) = Unit
}

class InMemoryDryStrokeStore : DryStrokeStore {
    override val spatialIndex: SpatialIndex<IndexedDryStroke> = SpatialIndex()
    private val strokesById = ConcurrentHashMap<String, IndexedDryStroke>()

    override fun addCommittedStrokes(strokes: List<IndexedDryStroke>) {
        for (i in 0 until strokes.size) {
            val entry = strokes[i]
            strokesById[entry.id] = entry
            spatialIndex.insert(entry.id, entry.bounds, entry)
        }
    }

    override fun removeStroke(id: String): Boolean {
        strokesById.remove(id)
        return spatialIndex.remove(id)
    }

    override fun clear() {
        strokesById.clear()
        spatialIndex.clear()
    }
}

private enum class ActiveGestureMode {
    DRAW_INK,
    ERASER,
    LASSO
}

private data class WetStrokeMeta(
    val isHighlighter: Boolean,
    val canonicalColorArgb: Int,
    val holdToSnapEnabled: Boolean,
    val targetPageId: String? = null
)

private class LassoSelectionRuntime {
    var activePolygon: MutableList<DocPoint> = ArrayList()
    var selectedIds: Set<String> = emptySet()
    var selectedObjectIds: Set<String> = emptySet()
    var selectionBounds: DocRect? = null
    var targetPageId: String? = null
    var targetOriginXPt: Float = 0f
    var targetOriginYPt: Float = 0f
    var isDraggingSelection: Boolean = false
    var dragOriginDoc: DocPoint? = null
    var liveDeltaXPt: Float = 0f
    var liveDeltaYPt: Float = 0f
    var isRotatingSelection: Boolean = false
    var rotationStartAngleRad: Float = 0f
    var liveRotationRadians: Float = 0f
    var rotationCenterXPt: Float = 0f
    var rotationCenterYPt: Float = 0f

    val isInteractingWithSelection: Boolean
        get() = isDraggingSelection || isRotatingSelection
}

private sealed interface EraserWorkCommand {
    data object BeginSession : EraserWorkCommand
    data class CutAtPoints(
        val pointsDocPt: List<DocPoint>,
        val radiusPt: Float,
        val isVectorEraser: Boolean
    ) : EraserWorkCommand
    data object FinishSession : EraserWorkCommand
}

/**
 * Computes the document-space bounding rectangle of an Ink [Stroke] using its [PartitionedMesh]
 * envelope, falling back to scanning input points if the mesh box is empty.
 */
fun computeStrokeDocBounds(stroke: Stroke): DocRect {
    val envelope = BoxAccumulator().apply { add(stroke.shape) }
    val meshBox = envelope.box
    if (meshBox != null) {
        return meshBox.toDocRect()
    }

    val inputs = stroke.inputs
    if (inputs.size == 0) {
        return DocRect(0f, 0f, 0f, 0f)
    }
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    val scratch = StrokeInput()
    for (i in 0 until inputs.size) {
        inputs.populate(i, scratch)
        minX = min(minX, scratch.x)
        minY = min(minY, scratch.y)
        maxX = max(maxX, scratch.x)
        maxY = max(maxY, scratch.y)
    }
    val pad = stroke.brush.size.coerceAtLeast(1f)
    return DocRect(minX - pad, minY - pad, maxX + pad, maxY + pad)
}

internal fun shouldRequestUnbufferedDispatchForEvent(
    actionMasked: Int,
    actionToolType: Int,
    pointerCount: Int,
    allowFingerDrawing: Boolean
): Boolean {
    if (actionMasked != MotionEvent.ACTION_DOWN &&
        actionMasked != MotionEvent.ACTION_POINTER_DOWN
    ) {
        return false
    }
    return actionToolType == MotionEvent.TOOL_TYPE_STYLUS ||
        actionToolType == MotionEvent.TOOL_TYPE_ERASER ||
        (allowFingerDrawing && actionToolType == MotionEvent.TOOL_TYPE_FINGER && pointerCount == 1)
}

/**
 * Dedicated hardware-accelerated Android View hosting the paper + dry-ink layer so pan/zoom
 * invalidations bypass Compose Snapshot observation and root tree redraws.
 */
private class DryCanvasView(context: Context) : View(context) {
    var onDrawCallback: ((Canvas) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        onDrawCallback?.invoke(canvas)
    }
}

/**
 * Synchronized 144 Hz drawing surface uniting all coordinate-locked layers:
 * 1. Vector paper template & page mode ([PaperBackgroundRenderer])
 * 2/3/4a. Dry ink tiled raster cache ([DryInkRasterCache] + [OledStrokeRenderCache])
 * 4b. Wet ink low-latency front-buffer ([InProgressStrokesView])
 * 6. Lasso / Hold-to-Snap selection overlay
 * 7. Hover cursor preview ([HoverCursorOverlay])
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun StylusDrawingSurface(
    transformMatrix: CanvasTransformMatrix,
    paperConfig: PagePaperConfig = PagePaperConfig(),
    brushConfig: BrushConfig = BrushConfig(),
    lastEraserTool: BrushToolType = BrushToolType.PRECISION_ERASER,
    allowFingerDrawing: Boolean = false,
    dryStrokeStore: DryStrokeStore = remember { CanvasStateHolder() },
    dryInkRasterCache: DryInkRasterCache = remember { DryInkRasterCache() },
    oledStrokeCache: OledStrokeRenderCache = remember { OledStrokeRenderCache() },
    journalCommitSink: JournalCommitSink = NoOpJournalCommitSink,
    onTogglePenEraser: () -> Unit = {},
    onCanvasStateMutated: () -> Unit = {},
    onViewportSizeChanged: (IntSize) -> Unit = {},
    onViewportTransformChanged: () -> Unit = {},
    onLiveViewportTransformChanged: () -> Unit = {},
    onCanvasTouchStart: () -> Unit = {},
    onNavigationGestureEnd: () -> Unit = {},
    onAiAutoFormatLassoSelection: (selectedIds: Set<String>, onFormatted: (clearSelection: Boolean) -> Unit) -> Unit = { _, _ -> },
    onLassoSelectedObject: (String) -> Unit = {},
    onLassoObjectPreviewChanged: (
        selectedObjectIds: Set<String>,
        deltaXPt: Float,
        deltaYPt: Float,
        rotationRadians: Float,
        rotationCenter: DocPoint?
    ) -> Unit = { _, _, _, _, _ -> },
    currentPageIndexOneBased: Int = 1,
    totalPages: Int = 1,
    initialPagePaddingPx: Float = 48f,
    externalDrawVersionTick: Long = 0L,
    multiPageSessionManager: MultiPageSessionManager? = null,
    multiPageLayout: ContinuousPageLayout? = null,
    initialPageId: String? = null,
    hydrationVersionTick: Long = 0L,
    scrollToPageRequestId: String? = null,
    scrollToPageTargetLocalRect: DocRect? = null,
    scrollToPageRequestTick: Long = 0L,
    onActiveSlotChanged: (PageLayoutSlot) -> Unit = {},
    onPageMutated: (pageId: String) -> Unit = { _ -> onCanvasStateMutated() },
    onLassoSelectedObjectInPage: ((pageId: String?, objectId: String) -> Unit)? = null,
    onAiAutoFormatLassoSelectionInPage: ((pageId: String?, selectedIds: Set<String>, onFormatted: (clearSelection: Boolean) -> Unit) -> Unit)? = null,
    onLassoObjectPreviewChangedInPage: ((
        pageId: String?,
        selectedObjectIds: Set<String>,
        deltaXPt: Float,
        deltaYPt: Float,
        rotationRadians: Float,
        rotationCenter: DocPoint?
    ) -> Unit)? = null,
    pdfTileRenderer: PdfTileRenderer? = null,
    singlePagePdfAssetId: String? = null,
    singlePagePdfPageIndex: Int = 0,
    liveObjectInteractionController: LiveObjectInteractionController? = null,
    isViewportMetadataReady: Boolean = true,
    savedViewportAnchor: SavedViewportAnchor? = null,
    restoreSavedViewportOnInit: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalStylusSurfacePalette.current
    val context = LocalContext.current
    val appFilesDir = remember(context) { context.applicationContext.filesDir }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val oneEuroFilter = remember {
        OneEuroFilter(initialStabilizationPercent = brushConfig.clampedStabilizationPercent)
    }

    val dryDrawTick = remember { mutableLongStateOf(0L) }
    val hoverDrawTick = remember { mutableLongStateOf(0L) }
    val lassoDrawTick = remember { mutableLongStateOf(0L) }
    // Incremented only when selection membership or drag visibility changes, avoiding 144 Hz recomposition on pan/zoom.
    val lassoSelectionVersion = remember { mutableLongStateOf(0L) }

    val hoverStateHolder = remember {
        object {
            var current: HoverCursorState = HoverCursorState()
        }
    }
    val lassoRuntime = remember { LassoSelectionRuntime() }
    val liveStrokeRenderer = remember { CanvasStrokeRenderer.create() }
    val livePreviewMatrix = remember { Matrix() }
    val liveObjectAffineMatrix = remember { Matrix() }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var hasInitializedViewport by remember(transformMatrix) { mutableStateOf(false) }
    var activeRebakeJob by remember { mutableStateOf<Job?>(null) }

    val eraserRadiusPx = with(density) { brushConfig.eraserRadiusDp.dp.toPx() }
    val canonicalInkBrush = remember(
        brushConfig.toolType,
        brushConfig.clampedSizePt,
        brushConfig.colorArgb,
        brushConfig.opacity
    ) {
        brushConfig.toInkBrush()
    }
    val wetDisplayBrush = remember(
        canonicalInkBrush,
        brushConfig.colorArgb,
        palette.isOledDeepBlack
    ) {
        if (palette.isOledDeepBlack) {
            val invertedArgb = ColorLuminanceInverter.invertArgbLuminance(brushConfig.colorArgb)
            brushConfig.toInkBrush(overrideColorArgb = invertedArgb)
        } else {
            canonicalInkBrush
        }
    }

    val pendingWetIdsToRemove = remember { ConcurrentHashMap.newKeySet<InProgressStrokeId>() }
    val wetStrokeMetaMap = remember { ConcurrentHashMap<InProgressStrokeId, WetStrokeMeta>() }
    val nextFallbackZ = remember { AtomicLong(CanvasStateHolder.INK_Z_ORDER_BASE) }
    var inProgressViewRef by remember { mutableStateOf<InProgressStrokesView?>(null) }
    var dryCanvasViewRef by remember { mutableStateOf<DryCanvasView?>(null) }
    val backgroundDrawMatrix = remember { Matrix() }
    val uiMainHandler = remember { Handler(Looper.getMainLooper()) }
    val asyncAssetInvalidationPosted = remember { AtomicBoolean(false) }
    val notifyAsyncAssetReady: () -> Unit = remember {
        {
            if (asyncAssetInvalidationPosted.compareAndSet(false, true)) {
                uiMainHandler.post {
                    asyncAssetInvalidationPosted.set(false)
                    dryCanvasViewRef?.invalidate()
                }
            }
        }
    }

    DisposableEffect(liveObjectInteractionController) {
        val listener: () -> Unit = { dryCanvasViewRef?.invalidate() }
        liveObjectInteractionController?.addListener(listener)
        onDispose {
            liveObjectInteractionController?.removeListener(listener)
        }
    }

    fun drawPdfBackgroundForRect(
        canvas: Canvas,
        pdfRenderer: PdfTileRenderer,
        pdfAssetId: String,
        pdfPageIndex: Int,
        pageOriginXPt: Float,
        pageOriginYPt: Float,
        docWidthPt: Float,
        docHeightPt: Float,
        visibleLocalDocRect: DocRect,
        isOledMode: Boolean,
        isGestureInProgress: Boolean,
        surfacePalette: StylusSurfacePalette
    ) {
        val currentScale = transformMatrix.snapshot().scale
        transformMatrix.populateDocToScreenMatrix(backgroundDrawMatrix)
        canvas.withSave {
            concat(backgroundDrawMatrix)
            if (pageOriginXPt != 0f || pageOriginYPt != 0f) {
                translate(pageOriginXPt, pageOriginYPt)
            }
            clipRect(0f, 0f, docWidthPt, docHeightPt)
            pdfRenderer.drawPdfPageForCanvasSyncOrRequest(
                canvas = this,
                pdfAssetId = pdfAssetId,
                pageIndex = pdfPageIndex,
                docPageWidthPt = docWidthPt,
                docPageHeightPt = docHeightPt,
                visibleLocalDocRect = visibleLocalDocRect,
                currentScale = currentScale,
                isOledMode = isOledMode,
                isGestureInProgress = isGestureInProgress,
                onTileReady = notifyAsyncAssetReady
            )
            PaperBackgroundRenderer.drawSheetBorderInCurrentDocSpace(
                canvas = this,
                pageWidthPt = docWidthPt,
                pageHeightPt = docHeightPt,
                scale = currentScale,
                palette = surfacePalette
            )
        }
    }

    fun drawUnderInkImagesForPage(
        canvas: Canvas,
        pageId: String?,
        pageObjects: List<PageObject>,
        originXPt: Float,
        originYPt: Float,
        docWidthPt: Float,
        docHeightPt: Float,
        clipToSheet: Boolean,
        livePreview: LiveObjectInteractionPreview?,
        lassoObjectIds: Set<String>,
        lassoAffine: AffineTransform2D?,
        isOledMode: Boolean
    ) {
        if (pageObjects.none { it is PageObject.ImageObject }) return
        transformMatrix.populateDocToScreenMatrix(backgroundDrawMatrix)
        canvas.withSave {
            concat(backgroundDrawMatrix)
            if (originXPt != 0f || originYPt != 0f) {
                translate(originXPt, originYPt)
            }
            if (clipToSheet && docWidthPt > 0f && docHeightPt > 0f) {
                clipRect(0f, 0f, docWidthPt, docHeightPt)
            }
            CanvasImageBitmapStore.drawImageObjectsSyncOrRequest(
                canvas = this,
                filesDir = appFilesDir,
                pageId = pageId,
                pageObjects = pageObjects,
                livePreview = livePreview,
                lassoPreviewObjectIds = lassoObjectIds,
                lassoLiveAffine = lassoAffine,
                isOledMode = isOledMode,
                onImageReady = notifyAsyncAssetReady
            )
        }
    }

    fun drawLiveTransformedStrokes(
        canvas: Canvas,
        holder: CanvasStateHolder,
        oledCache: OledStrokeRenderCache,
        strokeIds: Collection<String>,
        originXPt: Float,
        originYPt: Float,
        isOled: Boolean,
        configureLocalTransform: (Matrix) -> Unit
    ) {
        if (strokeIds.isEmpty()) return
        transformMatrix.populateDocToScreenMatrix(livePreviewMatrix)
        if (originXPt != 0f || originYPt != 0f) {
            livePreviewMatrix.preTranslate(originXPt, originYPt)
        }
        configureLocalTransform(livePreviewMatrix)
        for (id in strokeIds) {
            val entry = holder.getStroke(id) ?: continue
            val renderStroke = oledCache.adaptStroke(entry, isOled)
            canvas.withSave {
                // Hardware CanvasStrokeRenderer also requires transform pre-concatenated.
                concat(livePreviewMatrix)
                liveStrokeRenderer.draw(
                    canvas = this,
                    stroke = renderStroke,
                    strokeToScreenTransform = livePreviewMatrix
                )
            }
        }
    }

    val routerConfigHolder = remember {
        object {
            var config: BrushConfig = brushConfig
            var wetBrush = wetDisplayBrush
            var canonicalBrushColorArgb: Int = brushConfig.effectiveColorArgb()
            var fallbackEraserTool: BrushToolType = lastEraserTool
            var eraserRadiusPxValue: Float = eraserRadiusPx
            var latestViewportSize: IntSize = viewportSize
            var latestIsOledMode: Boolean = palette.isOledDeepBlack
            var latestPaperConfig: PagePaperConfig = paperConfig
            var latestPalette: StylusSurfacePalette = palette
            var latestCurrentPageOneBased: Int = currentPageIndexOneBased
            var latestTotalPages: Int = totalPages
            var latestMultiPageSession: MultiPageSessionManager? = multiPageSessionManager
            var latestMultiPageLayout: ContinuousPageLayout? = multiPageLayout
            var latestInitialPageId: String? = initialPageId
            var latestPdfTileRenderer: PdfTileRenderer? = pdfTileRenderer
            var latestSinglePagePdfAssetId: String? = singlePagePdfAssetId
            var latestSinglePagePdfPageIndex: Int = singlePagePdfPageIndex
            var latestLiveObjectController: LiveObjectInteractionController? = liveObjectInteractionController
            var hasInitializedViewportFlag: Boolean = hasInitializedViewport
            var latestIsViewportMetadataReady: Boolean = isViewportMetadataReady
            var latestSavedViewportAnchor: SavedViewportAnchor? = savedViewportAnchor
            var latestRestoreSavedViewport: Boolean = restoreSavedViewportOnInit
            var lastReportedActivePageId: String? = initialPageId
            var onTogglePenEraserCallback: () -> Unit = onTogglePenEraser
            var onMutatedCallback: () -> Unit = onCanvasStateMutated
            var onPageMutatedCallback: (String) -> Unit = onPageMutated
            var onActiveSlotChangedCallback: (PageLayoutSlot) -> Unit = onActiveSlotChanged
            var onViewportTransformChangedCallback: () -> Unit = onViewportTransformChanged
            var onLiveViewportTransformChangedCallback: () -> Unit = onLiveViewportTransformChanged
            var onCanvasTouchStartCallback: () -> Unit = onCanvasTouchStart
            var onNavigationGestureEndCallback: () -> Unit = onNavigationGestureEnd
            var onLassoSelectedObjectCallback: (String) -> Unit = onLassoSelectedObject
            var onLassoSelectedObjectInPageCallback: ((String?, String) -> Unit)? = onLassoSelectedObjectInPage
            var onLassoObjectPreviewChangedCallback: (Set<String>, Float, Float, Float, DocPoint?) -> Unit =
                onLassoObjectPreviewChanged
            var onLassoObjectPreviewChangedInPageCallback: ((String?, Set<String>, Float, Float, Float, DocPoint?) -> Unit)? =
                onLassoObjectPreviewChangedInPage
        }
    }.apply {
        config = brushConfig
        wetBrush = wetDisplayBrush
        canonicalBrushColorArgb = brushConfig.effectiveColorArgb()
        fallbackEraserTool = if (lastEraserTool.isEraser) lastEraserTool else BrushToolType.PRECISION_ERASER
        eraserRadiusPxValue = eraserRadiusPx
        latestViewportSize = viewportSize
        latestIsOledMode = palette.isOledDeepBlack
        latestPaperConfig = paperConfig
        latestPalette = palette
        latestCurrentPageOneBased = currentPageIndexOneBased
        latestTotalPages = totalPages
        latestMultiPageSession = multiPageSessionManager
        latestMultiPageLayout = multiPageLayout
        latestInitialPageId = initialPageId
        latestPdfTileRenderer = pdfTileRenderer
        latestSinglePagePdfAssetId = singlePagePdfAssetId
        latestSinglePagePdfPageIndex = singlePagePdfPageIndex
        latestLiveObjectController = liveObjectInteractionController
        hasInitializedViewportFlag = hasInitializedViewport
        latestIsViewportMetadataReady = isViewportMetadataReady
        latestSavedViewportAnchor = savedViewportAnchor
        latestRestoreSavedViewport = restoreSavedViewportOnInit
        onTogglePenEraserCallback = onTogglePenEraser
        onMutatedCallback = onCanvasStateMutated
        onPageMutatedCallback = onPageMutated
        onActiveSlotChangedCallback = onActiveSlotChanged
        onViewportTransformChangedCallback = onViewportTransformChanged
        onLiveViewportTransformChangedCallback = onLiveViewportTransformChanged
        onCanvasTouchStartCallback = onCanvasTouchStart
        onNavigationGestureEndCallback = onNavigationGestureEnd
        onLassoSelectedObjectCallback = onLassoSelectedObject
        onLassoSelectedObjectInPageCallback = onLassoSelectedObjectInPage
        onLassoObjectPreviewChangedCallback = onLassoObjectPreviewChanged
        onLassoObjectPreviewChangedInPageCallback = onLassoObjectPreviewChangedInPage
    }

    val eraserCommandChannel = remember(dryStrokeStore, multiPageSessionManager) {
        Channel<EraserWorkCommand>(Channel.UNLIMITED)
    }
    LaunchedEffect(dryStrokeStore, dryInkRasterCache, journalCommitSink, multiPageSessionManager) {
        val multiDirtyRuntimes = LinkedHashMap<String, NotebookPageRuntime>()
        val singleStateHolder = dryStrokeStore as? CanvasStateHolder
        for (cmd in eraserCommandChannel) {
            val session = routerConfigHolder.latestMultiPageSession
            val layout = routerConfigHolder.latestMultiPageLayout
            if (session != null && layout != null) {
                when (cmd) {
                    EraserWorkCommand.BeginSession -> multiDirtyRuntimes.clear()
                    is EraserWorkCommand.CutAtPoints -> {
                        val dirtyByPage = LinkedHashMap<String, DocRect>()
                        withContext(Dispatchers.Default) {
                            for (i in 0 until cmd.pointsDocPt.size) {
                                val wsPt = cmd.pointsDocPt[i]
                                val slot = layout.findSlotAtWorkspacePoint(wsPt.xPt, wsPt.yPt) ?: continue
                                val runtime = session.getReadyRuntime(slot.pageId) ?: continue
                                if (runtime.isReadOnly) continue
                                if (multiDirtyRuntimes.putIfAbsent(slot.pageId, runtime) == null) {
                                    runtime.canvasStateHolder.beginEraserSession()
                                }
                                val localPt = slot.workspaceToLocalPoint(wsPt.xPt, wsPt.yPt)
                                val rect = erasePointsOnHolder(
                                    holder = runtime.canvasStateHolder,
                                    pt = localPt,
                                    radiusPt = cmd.radiusPt,
                                    isVectorEraser = cmd.isVectorEraser
                                )
                                if (rect != null) {
                                    dirtyByPage[slot.pageId] = mergeDocRects(dirtyByPage[slot.pageId], rect)
                                }
                            }
                        }
                        if (dirtyByPage.isNotEmpty()) {
                            for ((pageId, dirty) in dirtyByPage) {
                                multiDirtyRuntimes[pageId]?.dryInkRasterCache?.invalidateDocRect(dirty)
                            }
                            dryDrawTick.longValue++
                        }
                    }
                    EraserWorkCommand.FinishSession -> {
                        if (multiDirtyRuntimes.isNotEmpty()) {
                            val finishedPages = ArrayList<String>(multiDirtyRuntimes.size)
                            withContext(Dispatchers.Default) {
                                for ((pageId, runtime) in multiDirtyRuntimes) {
                                    val action = runtime.canvasStateHolder.finishEraserSession()
                                    if (action != null) {
                                        runtime.strokeJournal.onCanvasActionApplied(action)
                                        finishedPages.add(pageId)
                                    }
                                }
                            }
                            multiDirtyRuntimes.clear()
                            if (finishedPages.isNotEmpty()) {
                                dryDrawTick.longValue++
                                finishedPages.forEach { routerConfigHolder.onPageMutatedCallback(it) }
                            }
                        }
                    }
                }
            } else if (singleStateHolder != null) {
                when (cmd) {
                    EraserWorkCommand.BeginSession -> singleStateHolder.beginEraserSession()
                    is EraserWorkCommand.CutAtPoints -> {
                        val dirtyRect = withContext(Dispatchers.Default) {
                            var combined: DocRect? = null
                            for (i in 0 until cmd.pointsDocPt.size) {
                                val rect = erasePointsOnHolder(
                                    holder = singleStateHolder,
                                    pt = cmd.pointsDocPt[i],
                                    radiusPt = cmd.radiusPt,
                                    isVectorEraser = cmd.isVectorEraser
                                )
                                if (rect != null) {
                                    combined = mergeDocRects(combined, rect)
                                }
                            }
                            combined
                        }
                        if (dirtyRect != null) {
                            dryInkRasterCache.invalidateDocRect(dirtyRect)
                            dryDrawTick.longValue++
                        }
                    }
                    EraserWorkCommand.FinishSession -> {
                        val action = withContext(Dispatchers.Default) {
                            singleStateHolder.finishEraserSession()
                        }
                        if (action != null) {
                            journalCommitSink.onCanvasActionApplied(action)
                            dryDrawTick.longValue++
                            routerConfigHolder.onMutatedCallback()
                        }
                    }
                }
            }
        }
    }

    fun emitLiveLassoObjectPreview() {
        val objIds = lassoRuntime.selectedObjectIds
        if (objIds.isEmpty()) return
        val center = if (lassoRuntime.isRotatingSelection) {
            DocPoint(lassoRuntime.rotationCenterXPt, lassoRuntime.rotationCenterYPt)
        } else {
            null
        }
        val pageCallback = routerConfigHolder.onLassoObjectPreviewChangedInPageCallback
        if (pageCallback != null) {
            pageCallback(
                lassoRuntime.targetPageId,
                objIds,
                lassoRuntime.liveDeltaXPt,
                lassoRuntime.liveDeltaYPt,
                lassoRuntime.liveRotationRadians,
                center
            )
        } else {
            routerConfigHolder.onLassoObjectPreviewChangedCallback(
                objIds,
                lassoRuntime.liveDeltaXPt,
                lassoRuntime.liveDeltaYPt,
                lassoRuntime.liveRotationRadians,
                center
            )
        }
    }

    fun clearLassoObjectPreview() {
        val pageCallback = routerConfigHolder.onLassoObjectPreviewChangedInPageCallback
        if (pageCallback != null) {
            pageCallback(lassoRuntime.targetPageId, emptySet(), 0f, 0f, 0f, null)
        } else {
            routerConfigHolder.onLassoObjectPreviewChangedCallback(emptySet(), 0f, 0f, 0f, null)
        }
    }

    fun shiftDocPointToLassoLocal(wsPt: DocPoint): DocPoint = DocPoint(
        xPt = wsPt.xPt - lassoRuntime.targetOriginXPt,
        yPt = wsPt.yPt - lassoRuntime.targetOriginYPt
    )

    fun notifyLassoPageMutated() {
        val pageId = lassoRuntime.targetPageId
        if (pageId != null) {
            routerConfigHolder.onPageMutatedCallback(pageId)
        } else {
            routerConfigHolder.onMutatedCallback()
        }
    }

    fun startLassoInteraction(firstWorkspacePt: DocPoint) {
        val firstPt = shiftDocPointToLassoLocal(firstWorkspacePt)
        val bounds = lassoRuntime.selectionBounds
        val hasSelection = lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.selectedObjectIds.isNotEmpty()
        val distToRotateHandlePx = if (bounds != null) {
            hypot(
                (firstPt.xPt - bounds.rightPt) * transformMatrix.scale,
                (firstPt.yPt - bounds.topPt) * transformMatrix.scale
            )
        } else {
            Float.MAX_VALUE
        }
        val rotateHandleHitPx = with(density) { 28.dp.toPx() }
        if (bounds != null && hasSelection && distToRotateHandlePx <= rotateHandleHitPx) {
            val cx = bounds.centerXPt
            val cy = bounds.centerYPt
            lassoRuntime.isRotatingSelection = true
            lassoRuntime.rotationCenterXPt = cx
            lassoRuntime.rotationCenterYPt = cy
            lassoRuntime.rotationStartAngleRad = atan2(firstPt.yPt - cy, firstPt.xPt - cx)
            lassoRuntime.liveRotationRadians = 0f
            lassoRuntime.isDraggingSelection = false
            lassoRuntime.dragOriginDoc = null
            lassoRuntime.liveDeltaXPt = 0f
            lassoRuntime.liveDeltaYPt = 0f
            dryCanvasViewRef?.invalidate()
        } else if (bounds != null && hasSelection && bounds.inflate(18f).contains(firstPt.xPt, firstPt.yPt)) {
            lassoRuntime.isDraggingSelection = true
            lassoRuntime.dragOriginDoc = firstPt
            lassoRuntime.liveDeltaXPt = 0f
            lassoRuntime.liveDeltaYPt = 0f
            lassoRuntime.isRotatingSelection = false
            lassoRuntime.liveRotationRadians = 0f
            dryCanvasViewRef?.invalidate()
        } else {
            lassoRuntime.selectedIds = emptySet()
            lassoRuntime.selectedObjectIds = emptySet()
            lassoRuntime.selectionBounds = null
            lassoRuntime.isDraggingSelection = false
            lassoRuntime.isRotatingSelection = false
            lassoRuntime.liveRotationRadians = 0f
            clearLassoObjectPreview()

            val session = routerConfigHolder.latestMultiPageSession
            val layout = routerConfigHolder.latestMultiPageLayout
            val targetSlot = if (session != null && layout != null) {
                layout.findSlotAtWorkspacePoint(firstWorkspacePt.xPt, firstWorkspacePt.yPt)
            } else {
                null
            }
            lassoRuntime.targetPageId = targetSlot?.pageId
            lassoRuntime.targetOriginXPt = targetSlot?.leftPt ?: 0f
            lassoRuntime.targetOriginYPt = targetSlot?.topPt ?: 0f

            val localStart = shiftDocPointToLassoLocal(firstWorkspacePt)
            lassoRuntime.activePolygon = ArrayList<DocPoint>(64).apply { add(localStart) }
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    fun moveLassoInteraction(workspacePoints: List<DocPoint>) {
        if (workspacePoints.isEmpty()) return
        val localPoints = workspacePoints.map(::shiftDocPointToLassoLocal)
        if (lassoRuntime.isRotatingSelection) {
            val last = localPoints.last()
            val currentAngle = atan2(
                last.yPt - lassoRuntime.rotationCenterYPt,
                last.xPt - lassoRuntime.rotationCenterXPt
            )
            lassoRuntime.liveRotationRadians = currentAngle - lassoRuntime.rotationStartAngleRad
            emitLiveLassoObjectPreview()
            dryCanvasViewRef?.invalidate()
        } else if (lassoRuntime.isDraggingSelection) {
            val origin = lassoRuntime.dragOriginDoc ?: return
            val last = localPoints.last()
            lassoRuntime.liveDeltaXPt = last.xPt - origin.xPt
            lassoRuntime.liveDeltaYPt = last.yPt - origin.yPt
            emitLiveLassoObjectPreview()
            dryCanvasViewRef?.invalidate()
        } else {
            lassoRuntime.activePolygon.addAll(localPoints)
        }
        lassoDrawTick.longValue++
    }

    fun finishLassoInteraction(finalWorkspacePoints: List<DocPoint>) {
        val localFinalPoints = finalWorkspacePoints.map(::shiftDocPointToLassoLocal)
        val session = routerConfigHolder.latestMultiPageSession
        val targetRuntime = if (session != null) {
            lassoRuntime.targetPageId?.let { session.getReadyRuntime(it) }
        } else {
            null
        }
        val stateHolder = if (session != null) {
            targetRuntime?.canvasStateHolder
        } else {
            dryStrokeStore as? CanvasStateHolder
        }
        val targetCache = targetRuntime?.dryInkRasterCache ?: dryInkRasterCache
        val targetOled = targetRuntime?.oledStrokeCache ?: oledStrokeCache
        val targetSink: JournalCommitSink = targetRuntime?.strokeJournal ?: journalCommitSink

        if (lassoRuntime.isRotatingSelection) {
            if (localFinalPoints.isNotEmpty()) {
                val endPt = localFinalPoints.last()
                val currentAngle = atan2(
                    endPt.yPt - lassoRuntime.rotationCenterYPt,
                    endPt.xPt - lassoRuntime.rotationCenterXPt
                )
                lassoRuntime.liveRotationRadians = currentAngle - lassoRuntime.rotationStartAngleRad
            }
            val angleRad = lassoRuntime.liveRotationRadians
            val cx = lassoRuntime.rotationCenterXPt
            val cy = lassoRuntime.rotationCenterYPt
            val selectedStrokes = lassoRuntime.selectedIds
            val selectedObjects = lassoRuntime.selectedObjectIds
            val oldBounds = lassoRuntime.selectionBounds
            lassoRuntime.isRotatingSelection = false
            lassoRuntime.rotationStartAngleRad = 0f
            lassoRuntime.liveRotationRadians = 0f
            clearLassoObjectPreview()

            if (stateHolder != null && targetRuntime?.isReadOnly != true &&
                (selectedStrokes.isNotEmpty() || selectedObjects.isNotEmpty()) && abs(angleRad) > 0.005f
            ) {
                val rotation = AffineTransform2D.rotationAround(angleRad, cx, cy)
                val res = stateHolder.transformSelection(selectedStrokes, selectedObjects, rotation)
                if (res.affectedBounds != null) {
                    for (id in selectedStrokes) {
                        targetOled.invalidate(id)
                    }
                    oldBounds?.let { targetCache.invalidateDocRect(it) }
                    targetCache.invalidateDocRect(res.affectedBounds)
                    lassoRuntime.selectionBounds = stateHolder.computeCombinedSelectionBounds(
                        selectedStrokes,
                        selectedObjects
                    )
                    if (selectedStrokes.isNotEmpty()) {
                        targetSink.onStrokesTransformed(selectedStrokes.toList(), rotation)
                    }
                    if (res.updatedObjects.isNotEmpty()) {
                        targetSink.onObjectsUpdated(res.updatedObjects)
                    }
                    dryDrawTick.longValue++
                    notifyLassoPageMutated()
                }
            }
            dryCanvasViewRef?.invalidate()
        } else if (lassoRuntime.isDraggingSelection) {
            val origin = lassoRuntime.dragOriginDoc
            if (origin != null && localFinalPoints.isNotEmpty()) {
                val endPt = localFinalPoints.last()
                lassoRuntime.liveDeltaXPt = endPt.xPt - origin.xPt
                lassoRuntime.liveDeltaYPt = endPt.yPt - origin.yPt
            }
            val dx = lassoRuntime.liveDeltaXPt
            val dy = lassoRuntime.liveDeltaYPt
            val selectedStrokes = lassoRuntime.selectedIds
            val selectedObjects = lassoRuntime.selectedObjectIds
            val oldBounds = lassoRuntime.selectionBounds
            lassoRuntime.isDraggingSelection = false
            lassoRuntime.dragOriginDoc = null
            lassoRuntime.liveDeltaXPt = 0f
            lassoRuntime.liveDeltaYPt = 0f
            clearLassoObjectPreview()

            // Rebuild immutable Stroke & PageObject instances ONLY on ACTION_UP if actually moved.
            if (stateHolder != null && targetRuntime?.isReadOnly != true &&
                (selectedStrokes.isNotEmpty() || selectedObjects.isNotEmpty()) && hypot(dx, dy) > 0.5f
            ) {
                val translation = AffineTransform2D.translation(dx, dy)
                val res = stateHolder.transformSelection(selectedStrokes, selectedObjects, translation)
                if (res.affectedBounds != null) {
                    for (id in selectedStrokes) {
                        targetOled.invalidate(id)
                    }
                    oldBounds?.let { targetCache.invalidateDocRect(it) }
                    targetCache.invalidateDocRect(res.affectedBounds)
                    lassoRuntime.selectionBounds = stateHolder.computeCombinedSelectionBounds(
                        selectedStrokes,
                        selectedObjects
                    )
                    if (selectedStrokes.isNotEmpty()) {
                        targetSink.onStrokesTransformed(selectedStrokes.toList(), translation)
                    }
                    if (res.updatedObjects.isNotEmpty()) {
                        targetSink.onObjectsUpdated(res.updatedObjects)
                    }
                    dryDrawTick.longValue++
                    notifyLassoPageMutated()
                }
            }
            dryCanvasViewRef?.invalidate()
        } else {
            lassoRuntime.activePolygon.addAll(localFinalPoints)
            val poly = lassoRuntime.activePolygon
            if (stateHolder != null && poly.size >= 3) {
                val strokeHits = stateHolder.selectStrokesByLasso(poly)
                val objectHits = stateHolder.selectPageObjectsByLasso(poly)
                if (strokeHits.isEmpty() && objectHits.size == 1) {
                    // Single selected object activates full corner resize/rotate + restore controls immediately.
                    val matchedPageId = lassoRuntime.targetPageId
                    lassoRuntime.selectedIds = emptySet()
                    lassoRuntime.selectedObjectIds = emptySet()
                    lassoRuntime.selectionBounds = null
                    lassoRuntime.activePolygon = ArrayList()
                    clearLassoObjectPreview()
                    routerConfigHolder.onLassoSelectedObjectInPageCallback
                        ?.invoke(matchedPageId, objectHits.first().id)
                        ?: routerConfigHolder.onLassoSelectedObjectCallback(objectHits.first().id)
                    lassoSelectionVersion.longValue++
                    lassoDrawTick.longValue++
                    return
                }
                val mergedStrokeIds = LinkedHashSet<String>(strokeHits.size)
                for (i in 0 until strokeHits.size) {
                    mergedStrokeIds.add(strokeHits[i].id)
                }
                for (i in 0 until objectHits.size) {
                    mergedStrokeIds.addAll(stateHolder.strokeIdsAttachedToObject(objectHits[i].id))
                }
                lassoRuntime.selectedIds = mergedStrokeIds
                lassoRuntime.selectedObjectIds = objectHits.map { it.id }.toSet()
                lassoRuntime.selectionBounds = stateHolder.computeCombinedSelectionBounds(
                    lassoRuntime.selectedIds,
                    lassoRuntime.selectedObjectIds
                )
            } else {
                lassoRuntime.selectedIds = emptySet()
                lassoRuntime.selectedObjectIds = emptySet()
                lassoRuntime.selectionBounds = null
            }
            lassoRuntime.activePolygon = ArrayList()
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    fun cancelLassoInteraction() {
        val wasTransforming = lassoRuntime.isInteractingWithSelection
        lassoRuntime.isDraggingSelection = false
        lassoRuntime.dragOriginDoc = null
        lassoRuntime.liveDeltaXPt = 0f
        lassoRuntime.liveDeltaYPt = 0f
        lassoRuntime.isRotatingSelection = false
        lassoRuntime.rotationStartAngleRad = 0f
        lassoRuntime.liveRotationRadians = 0f
        lassoRuntime.activePolygon = ArrayList()
        clearLassoObjectPreview()
        if (wasTransforming) {
            dryCanvasViewRef?.invalidate()
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    val wetStrokeSink = remember(transformMatrix, eraserCommandChannel) {
        object : WetStrokeSink<MotionEvent> {
            private var activeMode: ActiveGestureMode = ActiveGestureMode.DRAW_INK
            private var activeToolType: BrushToolType = BrushToolType.FOUNTAIN_PEN
            private var activeStrokeId: InProgressStrokeId? = null
            private var activeStrokeRejected: Boolean = false
            private var activeBatchOriginXPt: Float = 0f
            private var activeBatchOriginYPt: Float = 0f
            private var lastBatchSample: StylusPointSample? = null
            private val screenToDocMatrix = Matrix()
            private val docToScreenMatrix = Matrix()
            private val identityMatrix = Matrix()

            private fun resolveToolForGesture(isHardwareEraser: Boolean): BrushToolType {
                val configured = routerConfigHolder.config.toolType
                return if (isHardwareEraser) {
                    if (configured.isEraser) configured else routerConfigHolder.fallbackEraserTool
                } else {
                    configured
                }
            }

            private fun modeForTool(tool: BrushToolType): ActiveGestureMode = when {
                tool.isEraser -> ActiveGestureMode.ERASER
                tool == BrushToolType.LASSO -> ActiveGestureMode.LASSO
                else -> ActiveGestureMode.DRAW_INK
            }

            private fun currentEraserRadiusPt(): Float =
                transformMatrix.screenPxToDocSize(routerConfigHolder.eraserRadiusPxValue)

            private fun extractRawDocPoints(
                event: MotionEvent,
                pointerId: Int
            ): List<DocPoint> {
                val pointerIdx = event.findPointerIndex(pointerId)
                if (pointerIdx < 0) return emptyList()
                val histSize = event.historySize
                val pts = ArrayList<DocPoint>(histSize + 1)
                for (h in 0 until histSize) {
                    pts.add(
                        transformMatrix.screenToDoc(
                            event.getHistoricalX(pointerIdx, h),
                            event.getHistoricalY(pointerIdx, h)
                        )
                    )
                }
                pts.add(
                    transformMatrix.screenToDoc(
                        event.getX(pointerIdx),
                        event.getY(pointerIdx)
                    )
                )
                return pts
            }

            private fun shiftSingleSample(sample: StylusPointSample): StylusPointSample {
                val ox = activeBatchOriginXPt
                val oy = activeBatchOriginYPt
                if (ox == 0f && oy == 0f) return sample
                return sample.copy(xPt = sample.xPt - ox, yPt = sample.yPt - oy)
            }

            override fun onStartRawStroke(
                event: MotionEvent,
                pointerId: Int,
                isEraserTool: Boolean
            ) {
                activeStrokeRejected = false
                activeToolType = resolveToolForGesture(isEraserTool)
                activeMode = modeForTool(activeToolType)

                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        val view = inProgressViewRef ?: return
                        transformMatrix.populateScreenToDocMatrix(screenToDocMatrix)
                        var targetPageId: String? = null
                        val session = routerConfigHolder.latestMultiPageSession
                        val layout = routerConfigHolder.latestMultiPageLayout
                        if (session != null && layout != null) {
                            val pointerIdx = event.findPointerIndex(pointerId)
                            if (pointerIdx < 0) {
                                activeStrokeRejected = true
                                return
                            }
                            val wsDoc = transformMatrix.screenToDoc(
                                event.getX(pointerIdx),
                                event.getY(pointerIdx)
                            )
                            val slot = layout.findSlotAtWorkspacePoint(wsDoc.xPt, wsDoc.yPt)
                            val runtime = slot?.let { session.getReadyRuntime(it.pageId) }
                            if (slot == null || runtime == null || runtime.isReadOnly) {
                                activeStrokeRejected = true
                                return
                            }
                            targetPageId = slot.pageId
                            if (slot.leftPt != 0f || slot.topPt != 0f) {
                                screenToDocMatrix.postTranslate(-slot.leftPt, -slot.topPt)
                            }
                        }

                        val strokeId = view.startStroke(
                            event = event,
                            pointerId = pointerId,
                            brush = routerConfigHolder.wetBrush,
                            motionEventToWorldTransform = screenToDocMatrix,
                            strokeToWorldTransform = identityMatrix
                        )
                        activeStrokeId = strokeId
                        wetStrokeMetaMap[strokeId] = WetStrokeMeta(
                            isHighlighter = activeToolType.isHighlighter,
                            canonicalColorArgb = routerConfigHolder.canonicalBrushColorArgb,
                            holdToSnapEnabled = routerConfigHolder.config.holdToSnapEnabled,
                            targetPageId = targetPageId
                        )
                    }
                    ActiveGestureMode.ERASER -> {
                        eraserCommandChannel.trySend(EraserWorkCommand.BeginSession)
                        val pts = extractRawDocPoints(event, pointerId)
                        if (pts.isNotEmpty()) {
                            eraserCommandChannel.trySend(
                                EraserWorkCommand.CutAtPoints(
                                    pointsDocPt = pts,
                                    radiusPt = currentEraserRadiusPt(),
                                    isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                                )
                            )
                        }
                    }
                    ActiveGestureMode.LASSO -> {
                        val pts = extractRawDocPoints(event, pointerId)
                        if (pts.isNotEmpty()) {
                            startLassoInteraction(pts.first())
                            if (pts.size > 1) {
                                moveLassoInteraction(pts.drop(1))
                            }
                        }
                    }
                }
            }

            override fun onAddToRawStroke(
                realEvent: MotionEvent,
                predictedEvent: MotionEvent?,
                pointerId: Int
            ) {
                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        if (activeStrokeRejected) return
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return
                        view.addToStroke(
                            event = realEvent,
                            pointerId = pointerId,
                            strokeId = strokeId,
                            prediction = predictedEvent
                        )
                    }
                    ActiveGestureMode.ERASER -> {
                        val pts = extractRawDocPoints(realEvent, pointerId)
                        if (pts.isNotEmpty()) {
                            eraserCommandChannel.trySend(
                                EraserWorkCommand.CutAtPoints(
                                    pointsDocPt = pts,
                                    radiusPt = currentEraserRadiusPt(),
                                    isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                                )
                            )
                        }
                    }
                    ActiveGestureMode.LASSO -> {
                        moveLassoInteraction(extractRawDocPoints(realEvent, pointerId))
                    }
                }
            }

            override fun onFinishRawStroke(event: MotionEvent, pointerId: Int) {
                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        if (activeStrokeRejected) {
                            activeStrokeRejected = false
                            activeStrokeId = null
                            return
                        }
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return
                        view.finishStroke(event = event, pointerId = pointerId, strokeId = strokeId)
                        activeStrokeId = null
                    }
                    ActiveGestureMode.ERASER -> {
                        val pts = extractRawDocPoints(event, pointerId)
                        if (pts.isNotEmpty()) {
                            eraserCommandChannel.trySend(
                                EraserWorkCommand.CutAtPoints(
                                    pointsDocPt = pts,
                                    radiusPt = currentEraserRadiusPt(),
                                    isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                                )
                            )
                        }
                        eraserCommandChannel.trySend(EraserWorkCommand.FinishSession)
                    }
                    ActiveGestureMode.LASSO -> {
                        finishLassoInteraction(extractRawDocPoints(event, pointerId))
                    }
                }
            }

            override fun onStartBatchStroke(
                firstSample: StylusPointSample,
                toolType: InputToolType
            ) {
                activeStrokeRejected = false
                activeBatchOriginXPt = 0f
                activeBatchOriginYPt = 0f
                activeToolType = resolveToolForGesture(firstSample.isEraserTool)
                activeMode = modeForTool(activeToolType)

                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        val view = inProgressViewRef ?: return
                        transformMatrix.populateDocToScreenMatrix(docToScreenMatrix)
                        var targetPageId: String? = null
                        val session = routerConfigHolder.latestMultiPageSession
                        val layout = routerConfigHolder.latestMultiPageLayout
                        val localFirstSample = if (session != null && layout != null) {
                            val slot = layout.findSlotAtWorkspacePoint(firstSample.xPt, firstSample.yPt)
                            val runtime = slot?.let { session.getReadyRuntime(it.pageId) }
                            if (slot == null || runtime == null || runtime.isReadOnly) {
                                activeStrokeRejected = true
                                return
                            }
                            targetPageId = slot.pageId
                            activeBatchOriginXPt = slot.leftPt
                            activeBatchOriginYPt = slot.topPt
                            if (slot.leftPt != 0f || slot.topPt != 0f) {
                                docToScreenMatrix.preTranslate(slot.leftPt, slot.topPt)
                            }
                            firstSample.copy(
                                xPt = firstSample.xPt - slot.leftPt,
                                yPt = firstSample.yPt - slot.topPt
                            )
                        } else {
                            firstSample
                        }
                        lastBatchSample = localFirstSample
                        val input = localFirstSample.toSingleStrokeInput(toolType)
                        val strokeId = view.startStroke(
                            input = input,
                            brush = routerConfigHolder.wetBrush,
                            strokeToViewTransform = docToScreenMatrix
                        )
                        activeStrokeId = strokeId
                        wetStrokeMetaMap[strokeId] = WetStrokeMeta(
                            isHighlighter = activeToolType.isHighlighter,
                            canonicalColorArgb = routerConfigHolder.canonicalBrushColorArgb,
                            holdToSnapEnabled = routerConfigHolder.config.holdToSnapEnabled,
                            targetPageId = targetPageId
                        )
                    }
                    ActiveGestureMode.ERASER -> {
                        eraserCommandChannel.trySend(EraserWorkCommand.BeginSession)
                        eraserCommandChannel.trySend(
                            EraserWorkCommand.CutAtPoints(
                                pointsDocPt = listOf(DocPoint(firstSample.xPt, firstSample.yPt)),
                                radiusPt = currentEraserRadiusPt(),
                                isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                            )
                        )
                    }
                    ActiveGestureMode.LASSO -> {
                        startLassoInteraction(DocPoint(firstSample.xPt, firstSample.yPt))
                    }
                }
            }

            override fun onAddBatchToStroke(
                realSamples: List<StylusPointSample>,
                predictedSamples: List<StylusPointSample>,
                toolType: InputToolType
            ) {
                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        if (activeStrokeRejected) return
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return
                        if (realSamples.isEmpty() && predictedSamples.isEmpty()) return

                        realSamples.lastOrNull()?.let { latest ->
                            lastBatchSample = shiftSingleSample(latest)
                        }
                        val ox = -activeBatchOriginXPt
                        val oy = -activeBatchOriginYPt
                        val realBatch = realSamples.toStrokeInputBatch(toolType, offsetXPt = ox, offsetYPt = oy)
                        val predBatch = if (predictedSamples.isEmpty()) {
                            ImmutableStrokeInputBatch.EMPTY
                        } else {
                            predictedSamples.toStrokeInputBatch(toolType, offsetXPt = ox, offsetYPt = oy)
                        }
                        view.addToStroke(
                            inputs = realBatch,
                            strokeId = strokeId,
                            prediction = predBatch
                        )
                    }
                    ActiveGestureMode.ERASER -> {
                        if (realSamples.isNotEmpty()) {
                            eraserCommandChannel.trySend(
                                EraserWorkCommand.CutAtPoints(
                                    pointsDocPt = realSamples.map { DocPoint(it.xPt, it.yPt) },
                                    radiusPt = currentEraserRadiusPt(),
                                    isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                                )
                            )
                        }
                    }
                    ActiveGestureMode.LASSO -> {
                        moveLassoInteraction(realSamples.map { DocPoint(it.xPt, it.yPt) })
                    }
                }
            }

            override fun onFinishBatchStroke(
                finalSamples: List<StylusPointSample>,
                toolType: InputToolType
            ) {
                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        if (activeStrokeRejected) {
                            activeStrokeRejected = false
                            activeBatchOriginXPt = 0f
                            activeBatchOriginYPt = 0f
                            activeStrokeId = null
                            lastBatchSample = null
                            return
                        }
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return

                        val ox = -activeBatchOriginXPt
                        val oy = -activeBatchOriginYPt
                        if (finalSamples.size > 1) {
                            val intermediateBatch = finalSamples
                                .subList(0, finalSamples.size - 1)
                                .toStrokeInputBatch(toolType, offsetXPt = ox, offsetYPt = oy)
                            view.addToStroke(
                                inputs = intermediateBatch,
                                strokeId = strokeId,
                                prediction = ImmutableStrokeInputBatch.EMPTY
                            )
                        }
                        val terminalSample = finalSamples.lastOrNull()?.let(::shiftSingleSample) ?: lastBatchSample
                        if (terminalSample != null) {
                            view.finishStroke(
                                input = terminalSample.toSingleStrokeInput(toolType),
                                strokeId = strokeId
                            )
                        } else {
                            wetStrokeMetaMap.remove(strokeId)
                            view.cancelStroke(strokeId, null)
                        }
                        activeStrokeId = null
                        lastBatchSample = null
                        activeBatchOriginXPt = 0f
                        activeBatchOriginYPt = 0f
                    }
                    ActiveGestureMode.ERASER -> {
                        if (finalSamples.isNotEmpty()) {
                            eraserCommandChannel.trySend(
                                EraserWorkCommand.CutAtPoints(
                                    pointsDocPt = finalSamples.map { DocPoint(it.xPt, it.yPt) },
                                    radiusPt = currentEraserRadiusPt(),
                                    isVectorEraser = activeToolType == BrushToolType.VECTOR_ERASER
                                )
                            )
                        }
                        eraserCommandChannel.trySend(EraserWorkCommand.FinishSession)
                        lastBatchSample = null
                    }
                    ActiveGestureMode.LASSO -> {
                        finishLassoInteraction(finalSamples.map { DocPoint(it.xPt, it.yPt) })
                        lastBatchSample = null
                    }
                }
            }

            override fun onCancelActiveStroke() {
                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        val view = inProgressViewRef
                        val strokeId = activeStrokeId
                        if (strokeId != null) {
                            wetStrokeMetaMap.remove(strokeId)
                            if (view != null) {
                                view.cancelStroke(strokeId, null)
                            }
                        }
                    }
                    ActiveGestureMode.ERASER -> {
                        eraserCommandChannel.trySend(EraserWorkCommand.FinishSession)
                    }
                    ActiveGestureMode.LASSO -> cancelLassoInteraction()
                }
                activeStrokeId = null
                lastBatchSample = null
            }
        }
    }

    var inputRouter by remember { mutableStateOf<StylusInputRouter<MotionEvent>?>(null) }

    LaunchedEffect(brushConfig.clampedStabilizationPercent) {
        inputRouter?.setStabilizationPercent(brushConfig.clampedStabilizationPercent)
    }
    LaunchedEffect(brushConfig.pressureCurve) {
        inputRouter?.pressureCurve = brushConfig.pressureCurve
    }
    LaunchedEffect(allowFingerDrawing) {
        inputRouter?.allowFingerDrawing = allowFingerDrawing
    }
    suspend fun prewarmContinuousVisibleSlots(
        vpWidth: Int,
        vpHeight: Int,
        layout: ContinuousPageLayout,
        session: MultiPageSessionManager,
        paletteSnap: StylusSurfacePalette
    ) {
        if (vpWidth <= 0 || vpHeight <= 0) return
        val visibleWs = transformMatrix.screenRectToDocRect(0f, 0f, vpWidth.toFloat(), vpHeight.toFloat())
        val slots = layout.resolveVisibleSlots(visibleWs, paddingPt = 160f)
        val isOled = paletteSnap.isOledDeepBlack
        val pdfRenderer = routerConfigHolder.latestPdfTileRenderer
        for (i in 0 until slots.size) {
            val slot = slots[i]
            val slotPdfId = slot.pdfAssetId
            if (!slotPdfId.isNullOrBlank() && pdfRenderer != null) {
                val basePdf = pdfRenderer.prewarmBasePage(
                    pdfAssetId = slotPdfId,
                    pageIndex = slot.pdfPageIndex,
                    docPageWidthPt = slot.widthPt,
                    docPageHeightPt = slot.heightPt
                )
                if (basePdf != null) {
                    notifyAsyncAssetReady()
                }
            }
            val runtime = session.getReadyRuntime(slot.pageId) ?: continue
            withContext(Dispatchers.Default) {
                runtime.dryInkRasterCache.prewarmVisibleTiles(
                    viewportWidthPx = vpWidth,
                    viewportHeightPx = vpHeight,
                    transformMatrix = transformMatrix,
                    spatialIndex = runtime.canvasStateHolder.spatialIndex,
                    isOledMode = isOled,
                    pageWidthPt = slot.widthPt,
                    pageHeightPt = slot.heightPt,
                    paperConfig = slot.config,
                    palette = paletteSnap,
                    pageOriginXPt = slot.leftPt,
                    pageOriginYPt = slot.topPt,
                    strokeAdapter = { entry -> runtime.oledStrokeCache.adaptStroke(entry, isOled) }
                )
            }
        }
    }

    suspend fun initializeViewportCameraAndCaches(size: IntSize) {
        if (hasInitializedViewport ||
            !routerConfigHolder.latestIsViewportMetadataReady ||
            size.width <= 0 ||
            size.height <= 0
        ) return
        val session = routerConfigHolder.latestMultiPageSession
        val layout = routerConfigHolder.latestMultiPageLayout
        val initPaperConfig = routerConfigHolder.latestPaperConfig
        val initPalette = routerConfigHolder.latestPalette
        val isOled = initPalette.isOledDeepBlack
        val savedAnchor = if (routerConfigHolder.latestRestoreSavedViewport) {
            routerConfigHolder.latestSavedViewportAnchor
        } else {
            null
        }

        if (layout != null && !layout.isEmpty) {
            val startSlot = savedAnchor?.pageId?.let { layout.slotForPageId(it) }
                ?: routerConfigHolder.latestInitialPageId?.let { layout.slotForPageId(it) }
                ?: layout.slotAt(0)!!
            val restored = if (savedAnchor != null && savedAnchor.pageId == startSlot.pageId) {
                transformMatrix.applySavedPageLocalViewport(
                    pageOriginXPt = startSlot.leftPt,
                    pageOriginYPt = startSlot.topPt,
                    pageWidthPt = startSlot.widthPt,
                    pageHeightPt = startSlot.heightPt,
                    localCenterXPt = savedAnchor.localCenterXPt,
                    localCenterYPt = savedAnchor.localCenterYPt,
                    relativeZoomFactor = savedAnchor.relativeZoomFactor,
                    viewportWidthPx = size.width.toFloat(),
                    viewportHeightPx = size.height.toFloat(),
                    paddingPx = initialPagePaddingPx
                )
            } else {
                false
            }
            if (!restored) {
                transformMatrix.fitPageToViewport(
                    pageWidthPt = startSlot.widthPt,
                    pageHeightPt = startSlot.heightPt,
                    viewportWidthPx = size.width.toFloat(),
                    viewportHeightPx = size.height.toFloat(),
                    paddingPx = initialPagePaddingPx
                )
                val (panX, panY) = layout.computeCameraPanForSlot(
                    slot = startSlot,
                    scale = transformMatrix.scale,
                    viewportWidthPx = size.width.toFloat(),
                    viewportHeightPx = size.height.toFloat(),
                    verticalPaddingPx = initialPagePaddingPx
                )
                transformMatrix.setTransform(scale = transformMatrix.scale, panXPx = panX, panYPx = panY)
            }
            session?.updateActiveFocus(startSlot.pageIndex)
            routerConfigHolder.lastReportedActivePageId = startSlot.pageId
            routerConfigHolder.onActiveSlotChangedCallback(startSlot)
            hasInitializedViewport = true
            routerConfigHolder.hasInitializedViewportFlag = true
            if (session != null) {
                prewarmContinuousVisibleSlots(size.width, size.height, layout, session, initPalette)
            }
        } else {
            val initialPageIdSnap = routerConfigHolder.latestInitialPageId
            val restoredSingle = if (savedAnchor != null &&
                (initialPageIdSnap == null || savedAnchor.pageId == initialPageIdSnap)
            ) {
                transformMatrix.applySavedPageLocalViewport(
                    pageOriginXPt = 0f,
                    pageOriginYPt = 0f,
                    pageWidthPt = initPaperConfig.pageDimensions.widthPt,
                    pageHeightPt = initPaperConfig.pageDimensions.heightPt,
                    localCenterXPt = savedAnchor.localCenterXPt,
                    localCenterYPt = savedAnchor.localCenterYPt,
                    relativeZoomFactor = savedAnchor.relativeZoomFactor,
                    viewportWidthPx = size.width.toFloat(),
                    viewportHeightPx = size.height.toFloat(),
                    paddingPx = initialPagePaddingPx
                )
            } else {
                false
            }
            if (!restoredSingle) {
                transformMatrix.fitPageToViewport(
                    pageWidthPt = initPaperConfig.pageDimensions.widthPt,
                    pageHeightPt = initPaperConfig.pageDimensions.heightPt,
                    viewportWidthPx = size.width.toFloat(),
                    viewportHeightPx = size.height.toFloat(),
                    paddingPx = initialPagePaddingPx
                )
            }
            dryInkRasterCache.syncInitialScale(transformMatrix.scale)
            hasInitializedViewport = true
            routerConfigHolder.hasInitializedViewportFlag = true
            if (dryStrokeStore.spatialIndex.size > 0 || initPaperConfig.canvasMode == CanvasMode.FIXED_PAGE) {
                val singlePdfId = routerConfigHolder.latestSinglePagePdfAssetId?.takeIf { it.isNotBlank() }
                val pdfRenderer = routerConfigHolder.latestPdfTileRenderer
                if (singlePdfId != null && pdfRenderer != null) {
                    pdfRenderer.prewarmBasePage(
                        pdfAssetId = singlePdfId,
                        pageIndex = routerConfigHolder.latestSinglePagePdfPageIndex,
                        docPageWidthPt = initPaperConfig.pageDimensions.widthPt,
                        docPageHeightPt = initPaperConfig.pageDimensions.heightPt
                    )
                }
                withContext(Dispatchers.Default) {
                    dryInkRasterCache.prewarmVisibleTiles(
                        viewportWidthPx = size.width,
                        viewportHeightPx = size.height,
                        transformMatrix = transformMatrix,
                        spatialIndex = dryStrokeStore.spatialIndex,
                        isOledMode = isOled,
                        pageWidthPt = initPaperConfig.pageDimensions.widthPt,
                        pageHeightPt = initPaperConfig.pageDimensions.heightPt,
                        paperConfig = initPaperConfig,
                        palette = initPalette,
                        strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) }
                    )
                }
            }
        }
        dryDrawTick.longValue++
        dryCanvasViewRef?.invalidate()
        routerConfigHolder.onViewportTransformChangedCallback()
        routerConfigHolder.onLiveViewportTransformChangedCallback()
    }

    LaunchedEffect(brushConfig.toolType) {
        if (brushConfig.toolType != BrushToolType.LASSO &&
            (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.selectedObjectIds.isNotEmpty())
        ) {
            lassoRuntime.selectedIds = emptySet()
            lassoRuntime.selectedObjectIds = emptySet()
            lassoRuntime.selectionBounds = null
            clearLassoObjectPreview()
            lassoSelectionVersion.longValue++
            lassoDrawTick.longValue++
        }
    }
    LaunchedEffect(palette.isOledDeepBlack) {
        oledStrokeCache.clear()
        dryInkRasterCache.clearAll()
        multiPageSessionManager?.activePageIdsSnapshot()?.forEach { pageId ->
            multiPageSessionManager.getRuntime(pageId)?.let { rt ->
                rt.oledStrokeCache.clear()
                rt.dryInkRasterCache.clearAll()
            }
        }
        val vp = viewportSize
        val layout = routerConfigHolder.latestMultiPageLayout
        val session = routerConfigHolder.latestMultiPageSession
        if (vp.width > 0 && vp.height > 0 && session != null && layout != null) {
            prewarmContinuousVisibleSlots(vp.width, vp.height, layout, session, palette)
        }
        dryDrawTick.longValue++
        dryCanvasViewRef?.invalidate()
    }
    LaunchedEffect(paperConfig) {
        dryDrawTick.longValue++
    }
    LaunchedEffect(
        isViewportMetadataReady,
        transformMatrix,
        paperConfig.pageDimensions,
        multiPageLayout,
        viewportSize
    ) {
        if (!hasInitializedViewport &&
            isViewportMetadataReady &&
            viewportSize.width > 0 &&
            viewportSize.height > 0
        ) {
            initializeViewportCameraAndCaches(viewportSize)
        }
    }
    LaunchedEffect(scrollToPageRequestTick) {
        if (scrollToPageRequestTick <= 0L) return@LaunchedEffect
        val vp = viewportSize
        val session = routerConfigHolder.latestMultiPageSession ?: return@LaunchedEffect
        val layout = routerConfigHolder.latestMultiPageLayout ?: return@LaunchedEffect
        val targetId = scrollToPageRequestId ?: return@LaunchedEffect
        val slot = layout.slotForPageId(targetId) ?: return@LaunchedEffect
        if (vp.width <= 0 || vp.height <= 0) return@LaunchedEffect

        val localTarget = scrollToPageTargetLocalRect
        if (localTarget != null) {
            val wsRect = slot.localRectToWorkspaceRect(localTarget)
            transformMatrix.centerOnDocRectIfNeeded(
                targetRect = wsRect,
                viewportWidthPx = vp.width.toFloat(),
                viewportHeightPx = vp.height.toFloat(),
                forceCenter = true
            )
        } else {
            val curScale = transformMatrix.scale
            val targetScale = if (curScale < transformMatrix.baseFitScale * 0.85f) {
                transformMatrix.baseFitScale
            } else {
                curScale
            }
            val (panX, panY) = layout.computeCameraPanForSlot(
                slot = slot,
                scale = targetScale,
                viewportWidthPx = vp.width.toFloat(),
                viewportHeightPx = vp.height.toFloat(),
                verticalPaddingPx = initialPagePaddingPx
            )
            transformMatrix.setTransform(scale = targetScale, panXPx = panX, panYPx = panY)
        }
        session.updateActiveFocus(slot.pageIndex)
        routerConfigHolder.lastReportedActivePageId = slot.pageId
        prewarmContinuousVisibleSlots(vp.width, vp.height, layout, session, routerConfigHolder.latestPalette)
        dryDrawTick.longValue++
        dryCanvasViewRef?.invalidate()
        routerConfigHolder.onViewportTransformChangedCallback()
        routerConfigHolder.onLiveViewportTransformChangedCallback()
    }
    LaunchedEffect(dryDrawTick.longValue, externalDrawVersionTick, hydrationVersionTick, paperConfig, palette.isOledDeepBlack) {
        dryCanvasViewRef?.invalidate()
    }
    LaunchedEffect(hydrationVersionTick) {
        if (hydrationVersionTick > 0L) {
            val vp = viewportSize
            val session = routerConfigHolder.latestMultiPageSession
            val layout = routerConfigHolder.latestMultiPageLayout
            if (vp.width > 0 && vp.height > 0 && session != null && layout != null) {
                prewarmContinuousVisibleSlots(vp.width, vp.height, layout, session, routerConfigHolder.latestPalette)
                dryDrawTick.longValue++
                dryCanvasViewRef?.invalidate()
            }
        }
    }
    LaunchedEffect(externalDrawVersionTick) {
        if (externalDrawVersionTick > 0L) {
            val vpW = viewportSize.width
            val vpH = viewportSize.height
            val isOled = palette.isOledDeepBlack
            val session = routerConfigHolder.latestMultiPageSession
            val layout = routerConfigHolder.latestMultiPageLayout
            if (session != null && layout != null) {
                if (vpW > 0 && vpH > 0) {
                    prewarmContinuousVisibleSlots(vpW, vpH, layout, session, palette)
                }
            } else {
                dryInkRasterCache.onGestureSettled(transformMatrix.scale)
                if (dryInkRasterCache.cachedTileCount == 0 &&
                    vpW > 0 &&
                    vpH > 0 &&
                    (dryStrokeStore.spatialIndex.size > 0 || paperConfig.canvasMode == CanvasMode.FIXED_PAGE)
                ) {
                    withContext(Dispatchers.Default) {
                        dryInkRasterCache.prewarmVisibleTiles(
                            viewportWidthPx = vpW,
                            viewportHeightPx = vpH,
                            transformMatrix = transformMatrix,
                            spatialIndex = dryStrokeStore.spatialIndex,
                            isOledMode = isOled,
                            pageWidthPt = paperConfig.pageDimensions.widthPt,
                            pageHeightPt = paperConfig.pageDimensions.heightPt,
                            paperConfig = paperConfig,
                            palette = palette,
                            strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) }
                        )
                    }
                }
            }
            dryDrawTick.longValue++
            dryCanvasViewRef?.invalidate()
        }
    }

    fun commitFinishedStrokesToTarget(
        strokes: List<Pair<InProgressStrokeId, Stroke>>,
        holder: CanvasStateHolder?,
        strokeStore: DryStrokeStore,
        rasterCache: DryInkRasterCache,
        commitSink: JournalCommitSink
    ) {
        val committed = ArrayList<IndexedDryStroke>(strokes.size)
        val snapCandidates = ArrayList<Pair<IndexedDryStroke, WetStrokeMeta>>()
        val underInkObjectsSnapshot = if (holder != null && holder.hasUnderInkObjects()) {
            holder.allPageObjects()
        } else {
            emptyList()
        }

        for ((strokeId, wetStroke) in strokes) {
            val meta = wetStrokeMetaMap.remove(strokeId)
                ?: WetStrokeMeta(
                    isHighlighter = false,
                    canonicalColorArgb = wetStroke.brush.colorIntArgb,
                    holdToSnapEnabled = false
                )

            // Always persist canonical light-mode brush color even when wet stroke rendered inverted in OLED mode.
            val canonicalStroke = if (wetStroke.brush.colorIntArgb != meta.canonicalColorArgb) {
                wetStroke.copy(brush = wetStroke.brush.copyWithColorIntArgb(meta.canonicalColorArgb))
            } else {
                wetStroke
            }

            val zIndex = holder?.allocateZIndex(meta.isHighlighter)
                ?: nextFallbackZ.getAndIncrement()
            val docBounds = computeStrokeDocBounds(canonicalStroke)
            val attachedObjectId = if (underInkObjectsSnapshot.isNotEmpty()) {
                val points = CanvasStateHolder.extractStylusPoints(canonicalStroke)
                    .map { DocPoint(it.xPt, it.yPt) }
                ObjectAttachmentGeometry.findTopmostImageForStroke(
                    points = points,
                    objects = underInkObjectsSnapshot
                )?.id
            } else {
                null
            }
            val entry = IndexedDryStroke(
                id = UUID.randomUUID().toString(),
                stroke = canonicalStroke,
                bounds = docBounds,
                zIndex = zIndex,
                isHighlighter = meta.isHighlighter,
                attachedObjectId = attachedObjectId
            )
            committed.add(entry)
            rasterCache.invalidateDocRect(docBounds)
            if (meta.holdToSnapEnabled && holder != null) {
                snapCandidates.add(entry to meta)
            }
        }

        strokeStore.addCommittedStrokes(committed)

        val finalJournalEntries = ArrayList<IndexedDryStroke>(committed.size)
        finalJournalEntries.addAll(committed)

        // Hold-to-Snap runs after freehand commit so first Undo cleanly restores the freehand stroke.
        for ((freehandEntry, meta) in snapCandidates) {
            val samples = CanvasStateHolder.extractStylusPoints(freehandEntry.stroke)
            val snapResult = ShapeRecognizer.recognize(
                samples = samples,
                requireHoldAtEnd = true,
                lineOnly = meta.isHighlighter
            ) ?: continue

            val toolType = CanvasStateHolder.extractToolType(freehandEntry.stroke)
            val snappedStroke = Stroke(
                brush = freehandEntry.stroke.brush,
                inputs = snapResult.snappedSamples.toStrokeInputBatch(toolType)
            )
            val snappedEntry = holder?.replaceStrokeWithSnappedShape(
                freehandStrokeId = freehandEntry.id,
                snappedStroke = snappedStroke,
                shapeMeta = snapResult.shapeMeta
            )
            if (snappedEntry != null) {
                rasterCache.invalidateDocRect(freehandEntry.bounds)
                rasterCache.invalidateDocRect(snappedEntry.bounds)
                finalJournalEntries.remove(freehandEntry)
                finalJournalEntries.add(snappedEntry)
            }
        }

        commitSink.onStrokesCommitted(finalJournalEntries)
    }

    val finishedListener = remember(dryStrokeStore, dryInkRasterCache, journalCommitSink, multiPageSessionManager) {
        object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                if (strokes.isEmpty()) return

                val session = routerConfigHolder.latestMultiPageSession
                if (session != null) {
                    val entriesByPage = LinkedHashMap<String, MutableList<Pair<InProgressStrokeId, Stroke>>>()
                    val orphanIds = ArrayList<InProgressStrokeId>()
                    for ((strokeId, wetStroke) in strokes) {
                        val targetId = wetStrokeMetaMap[strokeId]?.targetPageId
                        if (targetId != null) {
                            entriesByPage.getOrPut(targetId) { ArrayList() }.add(strokeId to wetStroke)
                        } else {
                            wetStrokeMetaMap.remove(strokeId)
                            orphanIds.add(strokeId)
                        }
                    }
                    val mutatedPages = LinkedHashSet<String>()
                    for ((pageId, pageStrokes) in entriesByPage) {
                        val runtime = session.getRuntime(pageId)
                        if (runtime == null || runtime.isReadOnly) {
                            pageStrokes.forEach { (id, _) -> wetStrokeMetaMap.remove(id) }
                            continue
                        }
                        commitFinishedStrokesToTarget(
                            strokes = pageStrokes,
                            holder = runtime.canvasStateHolder,
                            strokeStore = runtime.canvasStateHolder,
                            rasterCache = runtime.dryInkRasterCache,
                            commitSink = runtime.strokeJournal
                        )
                        mutatedPages.add(pageId)
                    }
                    pendingWetIdsToRemove.addAll(strokes.keys)
                    dryDrawTick.longValue++
                    for (pageId in mutatedPages) {
                        routerConfigHolder.onPageMutatedCallback(pageId)
                    }
                    return
                }

                commitFinishedStrokesToTarget(
                    strokes = strokes.entries.map { it.key to it.value },
                    holder = dryStrokeStore as? CanvasStateHolder,
                    strokeStore = dryStrokeStore,
                    rasterCache = dryInkRasterCache,
                    commitSink = journalCommitSink
                )
                pendingWetIdsToRemove.addAll(strokes.keys)
                dryDrawTick.longValue++
                routerConfigHolder.onMutatedCallback()
            }
        }
    }

    DisposableEffect(inProgressViewRef, finishedListener) {
        val view = inProgressViewRef
        view?.addFinishedStrokesListener(finishedListener)
        onDispose {
            view?.removeFinishedStrokesListener(finishedListener)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.deskBackground)
            .onSizeChanged { size ->
                viewportSize = size
                onViewportSizeChanged(size)
            }
    ) {
        // Layers 1-4a (DryCanvasView) + Layer 4b (InProgressStrokesView wet front-buffer)
        key(transformMatrix, dryStrokeStore, dryInkRasterCache, journalCommitSink) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val dryCanvasView = DryCanvasView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val wetView = InProgressStrokesView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    motionEventToViewTransform = Matrix()
                    eagerInit()
                    val predictorBridge = AndroidMotionEventPredictorBridge(this)
                    val settleRunnable = Runnable {
                        val currentScale = transformMatrix.scale
                        val vpSize = routerConfigHolder.latestViewportSize
                        val paperCfg = routerConfigHolder.latestPaperConfig
                        val pal = routerConfigHolder.latestPalette
                        val isOled = pal.isOledDeepBlack
                        val session = routerConfigHolder.latestMultiPageSession
                        val layout = routerConfigHolder.latestMultiPageLayout
                        activeRebakeJob?.cancel()
                        activeRebakeJob = coroutineScope.launch {
                            withContext(Dispatchers.Default) {
                                if (session != null && layout != null && vpSize.width > 0 && vpSize.height > 0) {
                                    val visWs = transformMatrix.screenRectToDocRect(
                                        0f,
                                        0f,
                                        vpSize.width.toFloat(),
                                        vpSize.height.toFloat()
                                    )
                                    val visibleSlots = layout.resolveVisibleSlots(visWs, paddingPt = 160f)
                                    for (i in 0 until visibleSlots.size) {
                                        val slot = visibleSlots[i]
                                        val runtime = session.getReadyRuntime(slot.pageId) ?: continue
                                        runtime.dryInkRasterCache.rebakeSettledScale(
                                            targetScale = currentScale,
                                            viewportWidthPx = vpSize.width,
                                            viewportHeightPx = vpSize.height,
                                            transformMatrix = transformMatrix,
                                            spatialIndex = runtime.canvasStateHolder.spatialIndex,
                                            isOledMode = isOled,
                                            paperConfig = slot.config,
                                            palette = pal,
                                            pageOriginXPt = slot.leftPt,
                                            pageOriginYPt = slot.topPt,
                                            pageClipBoundsLocal = DocRect(0f, 0f, slot.widthPt, slot.heightPt),
                                            strokeAdapter = { entry -> runtime.oledStrokeCache.adaptStroke(entry, isOled) }
                                        )
                                    }
                                } else {
                                    dryInkRasterCache.rebakeSettledScale(
                                        targetScale = currentScale,
                                        viewportWidthPx = vpSize.width,
                                        viewportHeightPx = vpSize.height,
                                        transformMatrix = transformMatrix,
                                        spatialIndex = dryStrokeStore.spatialIndex,
                                        isOledMode = isOled,
                                        paperConfig = paperCfg,
                                        palette = pal,
                                        strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) }
                                    )
                                }
                            }
                            dryCanvasView.invalidate()
                            routerConfigHolder.onViewportTransformChangedCallback()
                        }
                    }
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                v.requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            v.removeCallbacks(settleRunnable)
                        }
                    })

                    val router = StylusInputRouter(
                        transformMatrix = transformMatrix,
                        oneEuroFilter = oneEuroFilter,
                        wetStrokeSink = wetStrokeSink,
                        predictorBridge = predictorBridge,
                        onTransformChanged = {
                            val session = routerConfigHolder.latestMultiPageSession
                            val layout = routerConfigHolder.latestMultiPageLayout
                            if (session != null && layout != null) {
                                session.activePageIdsSnapshot().forEach { id ->
                                    session.getRuntime(id)?.dryInkRasterCache?.onGestureTransformChanged()
                                }
                                val vpSize = routerConfigHolder.latestViewportSize
                                if (vpSize.width > 0 && vpSize.height > 0) {
                                    val centerDoc = transformMatrix.screenToDoc(
                                        vpSize.width * 0.5f,
                                        vpSize.height * 0.5f
                                    )
                                    val activeSlot = layout.findNearestSlotForViewportCenter(centerDoc.yPt)
                                    if (activeSlot != null) {
                                        session.updateActiveFocus(activeSlot.pageIndex)
                                        if (activeSlot.pageId != routerConfigHolder.lastReportedActivePageId) {
                                            routerConfigHolder.lastReportedActivePageId = activeSlot.pageId
                                            routerConfigHolder.onActiveSlotChangedCallback(activeSlot)
                                        }
                                    }
                                }
                            } else {
                                dryInkRasterCache.onGestureTransformChanged()
                            }
                            dryCanvasView.invalidate()
                            routerConfigHolder.onLiveViewportTransformChangedCallback()
                            if (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.activePolygon.isNotEmpty()) {
                                lassoDrawTick.longValue++
                            }
                            // Debounce settle rebake without allocating coroutine Jobs per 7 ms MOVE frame.
                            removeCallbacks(settleRunnable)
                            postDelayed(settleRunnable, DryInkRasterCache.SETTLE_DEBOUNCE_MS)
                        },
                        onHoverStateChanged = { state ->
                            if (state.isVisible || hoverStateHolder.current.isVisible) {
                                hoverStateHolder.current = state
                                hoverDrawTick.longValue++
                            } else {
                                hoverStateHolder.current = state
                            }
                        },
                        onTogglePenEraser = {
                            routerConfigHolder.onTogglePenEraserCallback()
                        },
                        onCanvasTouchStart = {
                            routerConfigHolder.onCanvasTouchStartCallback()
                        },
                        onNavigationGestureEnd = {
                            routerConfigHolder.onViewportTransformChangedCallback()
                            routerConfigHolder.onNavigationGestureEndCallback()
                        }
                    ).also { createdRouter ->
                        createdRouter.setStabilizationPercent(brushConfig.clampedStabilizationPercent)
                        createdRouter.pressureCurve = brushConfig.pressureCurve
                        createdRouter.allowFingerDrawing = allowFingerDrawing
                        inputRouter = createdRouter
                    }

                    setOnTouchListener { view, event ->
                        // Always claim ACTION_DOWN/POINTER_DOWN so Android delivers 2nd-finger pinch/pan events,
                        // and opt drawing pointers into unbuffered dispatch while keeping navigation VSYNC-synced.
                        val actionMasked = event.actionMasked
                        if (actionMasked == MotionEvent.ACTION_DOWN ||
                            actionMasked == MotionEvent.ACTION_POINTER_DOWN
                        ) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            val actionIdx = event.actionIndex
                            val actionTool = if (actionIdx in 0 until event.pointerCount) {
                                event.getToolType(actionIdx)
                            } else {
                                MotionEvent.TOOL_TYPE_UNKNOWN
                            }
                            if (shouldRequestUnbufferedDispatchForEvent(
                                    actionMasked = actionMasked,
                                    actionToolType = actionTool,
                                    pointerCount = event.pointerCount,
                                    allowFingerDrawing = router.allowFingerDrawing
                                )
                            ) {
                                view.requestUnbufferedDispatch(event)
                            }
                        }
                        router.onTouchEvent(event)
                        true
                    }
                    setOnHoverListener { _, event ->
                        router.onHoverEvent(event)
                    }
                    setOnGenericMotionListener { _, event ->
                        router.onGenericMotionEvent(event)
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, keyCode, event ->
                        if (event?.action == KeyEvent.ACTION_DOWN) {
                            router.onKeyDown(keyCode, event)
                        } else {
                            false
                        }
                    }
                }

                dryCanvasView.onDrawCallback = drawCallback@{ canvas ->
                    val paperCfg = routerConfigHolder.latestPaperConfig
                    val pal = routerConfigHolder.latestPalette
                    val isOled = pal.isOledDeepBlack
                    val vpW = dryCanvasView.width
                    val vpH = dryCanvasView.height
                    if (vpW > 0 && vpH > 0) {
                        if (!routerConfigHolder.hasInitializedViewportFlag) {
                            canvas.drawColor(pal.deskBackground.toArgb())
                            return@drawCallback
                        }
                        val session = routerConfigHolder.latestMultiPageSession
                        val layout = routerConfigHolder.latestMultiPageLayout
                        val pdfRenderer = routerConfigHolder.latestPdfTileRenderer
                        val activeLivePreview = routerConfigHolder.latestLiveObjectController?.activePreview
                        val activeLivePageId = activeLivePreview?.pageId
                        val livePreviewHolder: CanvasStateHolder? = if (activeLivePreview != null) {
                            if (session != null) {
                                activeLivePageId?.let { session.getReadyRuntime(it)?.canvasStateHolder }
                            } else {
                                dryStrokeStore as? CanvasStateHolder
                            }
                        } else {
                            null
                        }
                        val liveAttachedEntries = if (activeLivePreview != null && livePreviewHolder != null) {
                            livePreviewHolder.strokesAttachedToObject(activeLivePreview.objectId)
                        } else {
                            emptyList()
                        }
                        val liveAttachedIds = if (liveAttachedEntries.isNotEmpty()) {
                            liveAttachedEntries.mapTo(HashSet(liveAttachedEntries.size)) { it.id }
                        } else {
                            emptySet()
                        }
                        val liveAttachedBounds: DocRect? = if (liveAttachedEntries.isNotEmpty()) {
                            var minL = activeLivePreview!!.beforeObject.bounds.leftPt
                            var minT = activeLivePreview.beforeObject.bounds.topPt
                            var maxR = activeLivePreview.beforeObject.bounds.rightPt
                            var maxB = activeLivePreview.beforeObject.bounds.bottomPt
                            for (k in 0 until liveAttachedEntries.size) {
                                val b = liveAttachedEntries[k].bounds
                                minL = min(minL, b.leftPt)
                                minT = min(minT, b.topPt)
                                maxR = max(maxR, b.rightPt)
                                maxB = max(maxB, b.bottomPt)
                            }
                            DocRect(minL, minT, maxR, maxB)
                        } else {
                            null
                        }
                        val lassoLiveAffine: AffineTransform2D? = if (
                            lassoRuntime.isInteractingWithSelection &&
                            lassoRuntime.selectedObjectIds.isNotEmpty()
                        ) {
                            when {
                                lassoRuntime.isRotatingSelection -> AffineTransform2D.rotationAround(
                                    lassoRuntime.liveRotationRadians,
                                    lassoRuntime.rotationCenterXPt,
                                    lassoRuntime.rotationCenterYPt
                                )
                                abs(lassoRuntime.liveDeltaXPt) > 1e-4f || abs(lassoRuntime.liveDeltaYPt) > 1e-4f ->
                                    AffineTransform2D.translation(lassoRuntime.liveDeltaXPt, lassoRuntime.liveDeltaYPt)
                                else -> null
                            }
                        } else {
                            null
                        }

                        val hasDeferredTiles: Boolean = if (session != null && layout != null && !layout.isEmpty) {
                            val visibleWs = transformMatrix.screenRectToDocRect(0f, 0f, vpW.toFloat(), vpH.toFloat())
                            val visibleSlots = layout.resolveVisibleSlots(visibleWs)
                            val lassoPageId = lassoRuntime.targetPageId
                            var anyDeferred = false
                            for (i in 0 until visibleSlots.size) {
                                val slot = visibleSlots[i]
                                val runtime = session.getReadyRuntime(slot.pageId)
                                val slotObjects = runtime?.canvasStateHolder?.allPageObjects() ?: emptyList()
                                val hasImageObjects = slotObjects.any { it is PageObject.ImageObject }
                                val slotPdfId = slot.pdfAssetId?.takeIf { it.isNotBlank() }
                                val hasPdfBackground = slotPdfId != null && pdfRenderer != null
                                val lassoActiveOnSlot = lassoRuntime.isInteractingWithSelection &&
                                    (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.selectedObjectIds.isNotEmpty()) &&
                                    lassoPageId == slot.pageId
                                val liveObjectOnSlot = activeLivePreview != null && activeLivePageId == slot.pageId
                                val usedComposite = runtime != null &&
                                    !lassoActiveOnSlot &&
                                    !liveObjectOnSlot &&
                                    !hasPdfBackground &&
                                    !hasImageObjects &&
                                    runtime.dryInkRasterCache.drawCompositePageIfAvailable(
                                        canvas = canvas,
                                        transformMatrix = transformMatrix,
                                        paperConfig = slot.config,
                                        isOledMode = isOled,
                                        pageOriginXPt = slot.leftPt,
                                        pageOriginYPt = slot.topPt
                                    )
                                if (!usedComposite) {
                                    PaperBackgroundRenderer.drawContinuousSlotBackground(
                                        canvas = canvas,
                                        viewportWidthPx = vpW,
                                        viewportHeightPx = vpH,
                                        transformMatrix = transformMatrix,
                                        slot = slot,
                                        palette = pal
                                    )
                                    if (hasPdfBackground) {
                                        val visibleLocal = DocRect(
                                            leftPt = max(0f, visibleWs.leftPt - slot.leftPt),
                                            topPt = max(0f, visibleWs.topPt - slot.topPt),
                                            rightPt = min(slot.widthPt, visibleWs.rightPt - slot.leftPt),
                                            bottomPt = min(slot.heightPt, visibleWs.bottomPt - slot.topPt)
                                        )
                                        drawPdfBackgroundForRect(
                                            canvas = canvas,
                                            pdfRenderer = pdfRenderer!!,
                                            pdfAssetId = slotPdfId!!,
                                            pdfPageIndex = slot.pdfPageIndex,
                                            pageOriginXPt = slot.leftPt,
                                            pageOriginYPt = slot.topPt,
                                            docWidthPt = slot.widthPt,
                                            docHeightPt = slot.heightPt,
                                            visibleLocalDocRect = visibleLocal,
                                            isOledMode = isOled,
                                            isGestureInProgress = runtime?.dryInkRasterCache?.isGestureInProgress ?: false,
                                            surfacePalette = pal
                                        )
                                    }
                                    if (hasImageObjects) {
                                        drawUnderInkImagesForPage(
                                            canvas = canvas,
                                            pageId = slot.pageId,
                                            pageObjects = slotObjects,
                                            originXPt = slot.leftPt,
                                            originYPt = slot.topPt,
                                            docWidthPt = slot.widthPt,
                                            docHeightPt = slot.heightPt,
                                            clipToSheet = true,
                                            livePreview = if (liveObjectOnSlot) activeLivePreview else null,
                                            lassoObjectIds = if (lassoActiveOnSlot) lassoRuntime.selectedObjectIds else emptySet(),
                                            lassoAffine = if (lassoActiveOnSlot) lassoLiveAffine else null,
                                            isOledMode = isOled
                                        )
                                    }
                                    if (runtime != null) {
                                        val (excludedIds, excludedRect) = resolveExcludedStrokesForDraw(
                                            lassoActive = lassoActiveOnSlot,
                                            lassoIds = lassoRuntime.selectedIds,
                                            lassoBounds = lassoRuntime.selectionBounds,
                                            liveActive = liveObjectOnSlot,
                                            liveIds = liveAttachedIds,
                                            liveBounds = liveAttachedBounds
                                        )
                                        val deferred = runtime.dryInkRasterCache.drawVisibleTiles(
                                            canvas = canvas,
                                            viewportWidthPx = vpW,
                                            viewportHeightPx = vpH,
                                            transformMatrix = transformMatrix,
                                            spatialIndex = runtime.canvasStateHolder.spatialIndex,
                                            isOledMode = isOled,
                                            strokeAdapter = { entry -> runtime.oledStrokeCache.adaptStroke(entry, isOled) },
                                            excludedStrokeIds = excludedIds,
                                            excludedBounds = excludedRect,
                                            pageOriginXPt = slot.leftPt,
                                            pageOriginYPt = slot.topPt,
                                            pageClipBoundsLocal = DocRect(0f, 0f, slot.widthPt, slot.heightPt)
                                        )
                                        if (deferred) anyDeferred = true
                                    }
                                }
                                PaperBackgroundRenderer.drawContinuousPageGapLabel(
                                    canvas = canvas,
                                    viewportWidthPx = vpW,
                                    viewportHeightPx = vpH,
                                    transformMatrix = transformMatrix,
                                    slot = slot,
                                    totalPages = layout.pageCount,
                                    gapPt = layout.gapPt,
                                    palette = pal
                                )
                            }
                            anyDeferred
                        } else {
                            val singleHolder = dryStrokeStore as? CanvasStateHolder
                            val singleObjects = singleHolder?.allPageObjects() ?: emptyList()
                            val hasImageObjects = singleObjects.any { it is PageObject.ImageObject }
                            val singlePageId = routerConfigHolder.latestInitialPageId
                            val singlePdfId = routerConfigHolder.latestSinglePagePdfAssetId?.takeIf { it.isNotBlank() }
                            val hasPdfBackground = singlePdfId != null && pdfRenderer != null
                            val lassoActiveOnSingle = lassoRuntime.isInteractingWithSelection &&
                                (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.selectedObjectIds.isNotEmpty())
                            val liveObjectOnSingle = activeLivePreview != null &&
                                (activeLivePageId == null || activeLivePageId == singlePageId)
                            val usedComposite = !lassoActiveOnSingle &&
                                !liveObjectOnSingle &&
                                !hasPdfBackground &&
                                !hasImageObjects &&
                                dryInkRasterCache.drawCompositePageIfAvailable(
                                    canvas = canvas,
                                    transformMatrix = transformMatrix,
                                    paperConfig = paperCfg,
                                    isOledMode = isOled
                                )
                            val deferredSingle = if (usedComposite) {
                                false
                            } else {
                                PaperBackgroundRenderer.drawPaperLayer(
                                    canvas = canvas,
                                    viewportWidthPx = vpW,
                                    viewportHeightPx = vpH,
                                    transformMatrix = transformMatrix,
                                    paperConfig = paperCfg,
                                    palette = pal
                                )
                                if (hasPdfBackground) {
                                    val visibleLocal = transformMatrix.screenRectToDocRect(
                                        0f,
                                        0f,
                                        vpW.toFloat(),
                                        vpH.toFloat()
                                    )
                                    drawPdfBackgroundForRect(
                                        canvas = canvas,
                                        pdfRenderer = pdfRenderer!!,
                                        pdfAssetId = singlePdfId!!,
                                        pdfPageIndex = routerConfigHolder.latestSinglePagePdfPageIndex,
                                        pageOriginXPt = 0f,
                                        pageOriginYPt = 0f,
                                        docWidthPt = paperCfg.pageDimensions.widthPt,
                                        docHeightPt = paperCfg.pageDimensions.heightPt,
                                        visibleLocalDocRect = visibleLocal,
                                        isOledMode = isOled,
                                        isGestureInProgress = dryInkRasterCache.isGestureInProgress,
                                        surfacePalette = pal
                                    )
                                }
                                if (hasImageObjects) {
                                    drawUnderInkImagesForPage(
                                        canvas = canvas,
                                        pageId = singlePageId,
                                        pageObjects = singleObjects,
                                        originXPt = 0f,
                                        originYPt = 0f,
                                        docWidthPt = paperCfg.pageDimensions.widthPt,
                                        docHeightPt = paperCfg.pageDimensions.heightPt,
                                        clipToSheet = paperCfg.canvasMode == CanvasMode.FIXED_PAGE,
                                        livePreview = if (liveObjectOnSingle) activeLivePreview else null,
                                        lassoObjectIds = if (lassoActiveOnSingle) lassoRuntime.selectedObjectIds else emptySet(),
                                        lassoAffine = if (lassoActiveOnSingle) lassoLiveAffine else null,
                                        isOledMode = isOled
                                    )
                                }
                                val (singleExcludedIds, singleExcludedRect) = resolveExcludedStrokesForDraw(
                                    lassoActive = lassoActiveOnSingle,
                                    lassoIds = lassoRuntime.selectedIds,
                                    lassoBounds = lassoRuntime.selectionBounds,
                                    liveActive = liveObjectOnSingle,
                                    liveIds = liveAttachedIds,
                                    liveBounds = liveAttachedBounds
                                )
                                dryInkRasterCache.drawVisibleTiles(
                                    canvas = canvas,
                                    viewportWidthPx = vpW,
                                    viewportHeightPx = vpH,
                                    transformMatrix = transformMatrix,
                                    spatialIndex = dryStrokeStore.spatialIndex,
                                    isOledMode = isOled,
                                    strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) },
                                    excludedStrokeIds = singleExcludedIds,
                                    excludedBounds = singleExcludedRect
                                )
                            }
                            if (
                                paperCfg.canvasMode == CanvasMode.FIXED_PAGE &&
                                routerConfigHolder.latestTotalPages > 1
                            ) {
                                PaperBackgroundRenderer.drawAdjacentFixedPagesIfVisible(
                                    canvas = canvas,
                                    viewportWidthPx = vpW,
                                    viewportHeightPx = vpH,
                                    transformMatrix = transformMatrix,
                                    paperConfig = paperCfg,
                                    palette = pal,
                                    currentPageIndexOneBased = routerConfigHolder.latestCurrentPageOneBased,
                                    totalPages = routerConfigHolder.latestTotalPages
                                )
                            }
                            deferredSingle
                        }

                        // Live Lasso move/rotate preview rendered directly with transformed matrix without rebuilding Strokes.
                        if (lassoRuntime.isInteractingWithSelection && lassoRuntime.selectedIds.isNotEmpty()) {
                            val lassoRuntimeTarget = if (session != null) {
                                lassoRuntime.targetPageId?.let { session.getReadyRuntime(it) }
                            } else {
                                null
                            }
                            val holder = if (session != null) {
                                lassoRuntimeTarget?.canvasStateHolder
                            } else {
                                dryStrokeStore as? CanvasStateHolder
                            }
                            val previewOled = lassoRuntimeTarget?.oledStrokeCache ?: oledStrokeCache
                            if (holder != null) {
                                drawLiveTransformedStrokes(
                                    canvas = canvas,
                                    holder = holder,
                                    oledCache = previewOled,
                                    strokeIds = lassoRuntime.selectedIds,
                                    originXPt = lassoRuntime.targetOriginXPt,
                                    originYPt = lassoRuntime.targetOriginYPt,
                                    isOled = isOled
                                ) { matrix ->
                                    if (lassoRuntime.isRotatingSelection) {
                                        val angleDeg = (lassoRuntime.liveRotationRadians * (180.0 / PI)).toFloat()
                                        matrix.preRotate(
                                            angleDeg,
                                            lassoRuntime.rotationCenterXPt,
                                            lassoRuntime.rotationCenterYPt
                                        )
                                    } else {
                                        matrix.preTranslate(
                                            lassoRuntime.liveDeltaXPt,
                                            lassoRuntime.liveDeltaYPt
                                        )
                                    }
                                }
                            }
                        }

                        // Live interactive object drag/resize/rotate preview for strokes attached to an ImageObject.
                        if (activeLivePreview != null && liveAttachedIds.isNotEmpty() && livePreviewHolder != null) {
                            val liveSlot = if (session != null && layout != null && activeLivePageId != null) {
                                layout.slotForPageId(activeLivePageId)
                            } else {
                                null
                            }
                            val liveOled = if (session != null && activeLivePageId != null) {
                                session.getReadyRuntime(activeLivePageId)?.oledStrokeCache ?: oledStrokeCache
                            } else {
                                oledStrokeCache
                            }
                            val affine = activeLivePreview.attachedAffine
                            liveObjectAffineMatrix.setValues(
                                floatArrayOf(
                                    affine.scaleX,
                                    affine.shearX,
                                    affine.translateXPt,
                                    affine.shearY,
                                    affine.scaleY,
                                    affine.translateYPt,
                                    0f,
                                    0f,
                                    1f
                                )
                            )
                            drawLiveTransformedStrokes(
                                canvas = canvas,
                                holder = livePreviewHolder,
                                oledCache = liveOled,
                                strokeIds = liveAttachedIds,
                                originXPt = liveSlot?.leftPt ?: 0f,
                                originYPt = liveSlot?.topPt ?: 0f,
                                isOled = isOled
                            ) { matrix ->
                                matrix.preConcat(liveObjectAffineMatrix)
                            }
                        }

                        if (pendingWetIdsToRemove.isNotEmpty()) {
                            val toRemove = pendingWetIdsToRemove.toSet()
                            pendingWetIdsToRemove.removeAll(toRemove)
                            wetView.postOnAnimation {
                                wetView.removeFinishedStrokes(toRemove)
                            }
                        }
                        if (hasDeferredTiles) {
                            dryCanvasView.postOnAnimation {
                                dryCanvasView.invalidate()
                            }
                        }
                    }
                }

                dryCanvasViewRef = dryCanvasView
                inProgressViewRef = wetView
                FrameLayout(context).apply {
                    addView(dryCanvasView)
                    addView(wetView)
                }
            }
        )
        }

        // Layer 6: Lasso trace & selection marquee
        lassoSelectionVersion.longValue
        if (lassoRuntime.selectedIds.isNotEmpty() ||
            lassoRuntime.selectedObjectIds.isNotEmpty() ||
            lassoRuntime.activePolygon.isNotEmpty()
        ) {
            LassoSelectionCanvasOverlay(
                lassoRuntime = lassoRuntime,
                lassoTick = lassoDrawTick,
                transformMatrix = transformMatrix
            )
        }

        // Layer 7: Hover cursor outline intentionally disabled while stylus proximity & barrel button stay active.

        // Floating selection actions bar when Lasso has active strokes and/or objects selected
        lassoSelectionVersion.longValue
        val selectedCount = lassoRuntime.selectedIds.size + lassoRuntime.selectedObjectIds.size
        val lassoTargetRuntime = if (multiPageSessionManager != null) {
            lassoRuntime.targetPageId?.let { multiPageSessionManager.getReadyRuntime(it) }
        } else {
            null
        }
        val stateHolder = if (multiPageSessionManager != null) {
            lassoTargetRuntime?.canvasStateHolder
        } else {
            dryStrokeStore as? CanvasStateHolder
        }
        val targetCache = lassoTargetRuntime?.dryInkRasterCache ?: dryInkRasterCache
        val targetOled = lassoTargetRuntime?.oledStrokeCache ?: oledStrokeCache
        val targetSink: JournalCommitSink = lassoTargetRuntime?.strokeJournal ?: journalCommitSink
        val targetIsReadOnly = lassoTargetRuntime?.isReadOnly == true
        if (selectedCount > 0 && stateHolder != null && !targetIsReadOnly && !lassoRuntime.isInteractingWithSelection) {
            LassoSelectionActionBar(
                selectedCount = selectedCount,
                canAiAutoFormat = lassoRuntime.selectedIds.isNotEmpty(),
                onAiAutoFormatSelection = {
                    val activeSelectedIds = lassoRuntime.selectedIds.toSet()
                    if (activeSelectedIds.isEmpty()) return@LassoSelectionActionBar
                    val previousBounds = lassoRuntime.selectionBounds
                    val targetPageId = lassoRuntime.targetPageId
                    val onFormattedHandler: (Boolean) -> Unit = { clearSelection ->
                        if (clearSelection) {
                            for (id in activeSelectedIds) targetOled.invalidate(id)
                            previousBounds?.let { targetCache.invalidateDocRect(it) }
                            lassoRuntime.selectedIds = emptySet()
                            lassoRuntime.selectedObjectIds = emptySet()
                            lassoRuntime.selectionBounds = null
                            lassoRuntime.targetPageId = null
                            lassoRuntime.targetOriginXPt = 0f
                            lassoRuntime.targetOriginYPt = 0f
                            clearLassoObjectPreview()
                            dryCanvasViewRef?.invalidate()
                            dryDrawTick.longValue++
                            lassoSelectionVersion.longValue++
                            lassoDrawTick.longValue++
                            if (targetPageId != null) {
                                routerConfigHolder.onPageMutatedCallback(targetPageId)
                            } else {
                                onCanvasStateMutated()
                            }
                        }
                    }
                    val pageFormatter = onAiAutoFormatLassoSelectionInPage
                    if (pageFormatter != null) {
                        pageFormatter(targetPageId, activeSelectedIds, onFormattedHandler)
                    } else {
                        onAiAutoFormatLassoSelection(activeSelectedIds, onFormattedHandler)
                    }
                },
                onTransformSelection = { transform ->
                    val res = stateHolder.transformSelection(
                        strokeIds = lassoRuntime.selectedIds,
                        objectIds = lassoRuntime.selectedObjectIds,
                        transform = transform
                    )
                    if (res.affectedBounds != null) {
                        for (id in lassoRuntime.selectedIds) targetOled.invalidate(id)
                        targetCache.invalidateDocRect(res.affectedBounds)
                        lassoRuntime.selectionBounds = stateHolder.computeCombinedSelectionBounds(
                            lassoRuntime.selectedIds,
                            lassoRuntime.selectedObjectIds
                        )
                        if (lassoRuntime.selectedIds.isNotEmpty()) {
                            targetSink.onStrokesTransformed(lassoRuntime.selectedIds.toList(), transform)
                        }
                        if (res.updatedObjects.isNotEmpty()) {
                            targetSink.onObjectsUpdated(res.updatedObjects)
                        }
                        dryCanvasViewRef?.invalidate()
                        dryDrawTick.longValue++
                        lassoSelectionVersion.longValue++
                        lassoDrawTick.longValue++
                        notifyLassoPageMutated()
                    }
                },
                onRecolorSelection = {
                    val targetColor = brushConfig.effectiveColorArgb()
                    val res = stateHolder.recolorSelection(
                        strokeIds = lassoRuntime.selectedIds,
                        objectIds = lassoRuntime.selectedObjectIds,
                        newColorArgb = targetColor
                    )
                    if (res.affectedBounds != null) {
                        for (id in lassoRuntime.selectedIds) targetOled.invalidate(id)
                        targetCache.invalidateDocRect(res.affectedBounds)
                        if (lassoRuntime.selectedIds.isNotEmpty()) {
                            targetSink.onStrokesRecolored(lassoRuntime.selectedIds.toList(), targetColor)
                        }
                        if (res.updatedObjects.isNotEmpty()) {
                            targetSink.onObjectsUpdated(res.updatedObjects)
                        }
                        dryCanvasViewRef?.invalidate()
                        dryDrawTick.longValue++
                        notifyLassoPageMutated()
                    }
                },
                onDuplicateSelection = {
                    val res = stateHolder.duplicateSelection(
                        strokeIds = lassoRuntime.selectedIds,
                        objectIds = lassoRuntime.selectedObjectIds
                    )
                    if (res.duplicatedStrokes.isNotEmpty() || res.duplicatedObjects.isNotEmpty()) {
                        res.affectedBounds?.let { targetCache.invalidateDocRect(it) }
                        if (res.duplicatedStrokes.isNotEmpty()) {
                            targetSink.onStrokesCommitted(res.duplicatedStrokes)
                        }
                        if (res.duplicatedObjects.isNotEmpty()) {
                            targetSink.onObjectsAdded(res.duplicatedObjects)
                        }
                        lassoRuntime.selectedIds = res.duplicatedStrokes.map { it.id }.toSet()
                        lassoRuntime.selectedObjectIds = res.duplicatedObjects.map { it.id }.toSet()
                        lassoRuntime.selectionBounds = stateHolder.computeCombinedSelectionBounds(
                            lassoRuntime.selectedIds,
                            lassoRuntime.selectedObjectIds
                        )
                        dryCanvasViewRef?.invalidate()
                        dryDrawTick.longValue++
                        lassoSelectionVersion.longValue++
                        lassoDrawTick.longValue++
                        notifyLassoPageMutated()
                    }
                },
                onDeleteSelection = {
                    val toDeleteStrokes = lassoRuntime.selectedIds.toList()
                    val toDeleteObjects = lassoRuntime.selectedObjectIds.toList()
                    val dirty = stateHolder.deleteSelection(lassoRuntime.selectedIds, lassoRuntime.selectedObjectIds)
                    if (dirty != null) {
                        for (id in toDeleteStrokes) targetOled.invalidate(id)
                        targetCache.invalidateDocRect(dirty)
                        if (toDeleteStrokes.isNotEmpty()) targetSink.onStrokesDeleted(toDeleteStrokes)
                        if (toDeleteObjects.isNotEmpty()) targetSink.onObjectsDeleted(toDeleteObjects)
                    }
                    notifyLassoPageMutated()
                    lassoRuntime.selectedIds = emptySet()
                    lassoRuntime.selectedObjectIds = emptySet()
                    lassoRuntime.selectionBounds = null
                    lassoRuntime.targetPageId = null
                    lassoRuntime.targetOriginXPt = 0f
                    lassoRuntime.targetOriginYPt = 0f
                    clearLassoObjectPreview()
                    dryCanvasViewRef?.invalidate()
                    dryDrawTick.longValue++
                    lassoSelectionVersion.longValue++
                    lassoDrawTick.longValue++
                },
                onClearSelection = {
                    lassoRuntime.selectedIds = emptySet()
                    lassoRuntime.selectedObjectIds = emptySet()
                    lassoRuntime.selectionBounds = null
                    lassoRuntime.targetPageId = null
                    lassoRuntime.targetOriginXPt = 0f
                    lassoRuntime.targetOriginYPt = 0f
                    clearLassoObjectPreview()
                    lassoSelectionVersion.longValue++
                    lassoDrawTick.longValue++
                },
                selectionBoundsProvider = { lassoRuntime.selectionBounds },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun LassoSelectionCanvasOverlay(
    lassoRuntime: LassoSelectionRuntime,
    lassoTick: androidx.compose.runtime.State<Long>,
    transformMatrix: CanvasTransformMatrix
) {
    val tracePath = remember { Path() }
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        lassoTick.value

        val originXPt = lassoRuntime.targetOriginXPt
        val originYPt = lassoRuntime.targetOriginYPt
        val poly = lassoRuntime.activePolygon
        if (poly.size >= 2) {
            tracePath.reset()
            val first = transformMatrix.docToScreen(poly[0].xPt + originXPt, poly[0].yPt + originYPt)
            tracePath.moveTo(first.xPx, first.yPx)
            for (i in 1 until poly.size) {
                val p = transformMatrix.docToScreen(poly[i].xPt + originXPt, poly[i].yPt + originYPt)
                tracePath.lineTo(p.xPx, p.yPx)
            }
            drawPath(
                path = tracePath,
                color = Color(0xFF1A56DB),
                style = ComposeStroke(width = 2.5f, pathEffect = dashEffect)
            )
        }

        val bounds = lassoRuntime.selectionBounds
        if (bounds != null && (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.selectedObjectIds.isNotEmpty())) {
            val dx = (if (lassoRuntime.isDraggingSelection) lassoRuntime.liveDeltaXPt else 0f) + originXPt
            val dy = (if (lassoRuntime.isDraggingSelection) lassoRuntime.liveDeltaYPt else 0f) + originYPt
            val shifted = DocRect(
                leftPt = bounds.leftPt + dx,
                topPt = bounds.topPt + dy,
                rightPt = bounds.rightPt + dx,
                bottomPt = bounds.bottomPt + dy
            )
            val screenRect = transformMatrix.docRectToScreenRect(shifted)
            val center = Offset(
                (screenRect.leftPt + screenRect.rightPt) * 0.5f,
                (screenRect.topPt + screenRect.bottomPt) * 0.5f
            )
            val angleDeg = if (lassoRuntime.isRotatingSelection) {
                (lassoRuntime.liveRotationRadians * (180.0 / PI)).toFloat()
            } else {
                0f
            }
            rotate(degrees = angleDeg, pivot = center) {
                drawRect(
                    color = Color(0xFF1A56DB).copy(alpha = 0.08f),
                    topLeft = Offset(screenRect.leftPt, screenRect.topPt),
                    size = Size(screenRect.widthPt, screenRect.heightPt)
                )
                drawRect(
                    color = Color(0xFF1A56DB),
                    topLeft = Offset(screenRect.leftPt, screenRect.topPt),
                    size = Size(screenRect.widthPt, screenRect.heightPt),
                    style = ComposeStroke(width = 2f, pathEffect = dashEffect)
                )
                // Free-rotation handle in the top-right corner of the Lasso selection marquee.
                val handleCenter = Offset(screenRect.rightPt, screenRect.topPt)
                val handleRadiusPx = 14.dp.toPx()
                drawCircle(
                    color = Color.White,
                    radius = handleRadiusPx,
                    center = handleCenter
                )
                drawCircle(
                    color = Color(0xFF1A56DB),
                    radius = handleRadiusPx,
                    center = handleCenter,
                    style = ComposeStroke(width = 2.2f)
                )
                val iconRadius = handleRadiusPx * 0.48f
                drawArc(
                    color = Color(0xFF1A56DB),
                    startAngle = -55f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(handleCenter.x - iconRadius, handleCenter.y - iconRadius),
                    size = Size(iconRadius * 2f, iconRadius * 2f),
                    style = ComposeStroke(width = 2f, cap = StrokeCap.Round)
                )
                val arrowTip = Offset(
                    handleCenter.x + iconRadius * 0.62f,
                    handleCenter.y - iconRadius * 0.80f
                )
                drawLine(
                    color = Color(0xFF1A56DB),
                    start = Offset(arrowTip.x - iconRadius * 0.42f, arrowTip.y - iconRadius * 0.08f),
                    end = arrowTip,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFF1A56DB),
                    start = Offset(arrowTip.x + iconRadius * 0.05f, arrowTip.y + iconRadius * 0.42f),
                    end = arrowTip,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun LassoSelectionActionBar(
    selectedCount: Int,
    canAiAutoFormat: Boolean,
    selectionBoundsProvider: () -> DocRect?,
    onAiAutoFormatSelection: () -> Unit,
    onTransformSelection: (AffineTransform2D) -> Unit,
    onRecolorSelection: () -> Unit,
    onDuplicateSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$selectedCount kijelölve",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 4.dp)
            )
            if (canAiAutoFormat) {
                FilledTonalButton(
                    onClick = onAiAutoFormatSelection,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = StylusIcons.StarFourPoint,
                        contentDescription = "AI felismerés és formázás",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AI", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(
                onClick = {
                    val b = selectionBoundsProvider() ?: return@IconButton
                    val cx = (b.leftPt + b.rightPt) * 0.5f
                    val cy = (b.topPt + b.bottomPt) * 0.5f
                    onTransformSelection(AffineTransform2D.scalingAround(1.15f, 1.15f, cx, cy))
                }
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Nagyítás")
            }
            IconButton(
                onClick = {
                    val b = selectionBoundsProvider() ?: return@IconButton
                    val cx = (b.leftPt + b.rightPt) * 0.5f
                    val cy = (b.topPt + b.bottomPt) * 0.5f
                    onTransformSelection(AffineTransform2D.scalingAround(0.87f, 0.87f, cx, cy))
                }
            ) {
                Icon(Icons.Filled.ZoomOut, contentDescription = "Kicsinyítés")
            }
            IconButton(
                onClick = {
                    val b = selectionBoundsProvider() ?: return@IconButton
                    val cx = (b.leftPt + b.rightPt) * 0.5f
                    val cy = (b.topPt + b.bottomPt) * 0.5f
                    onTransformSelection(
                        AffineTransform2D.rotationAround((15.0 * PI / 180.0).toFloat(), cx, cy)
                    )
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Forgatás +15°")
            }
            IconButton(onClick = onRecolorSelection) {
                Icon(Icons.Filled.FormatColorFill, contentDescription = "Átszínezés")
            }
            IconButton(onClick = onDuplicateSelection) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Másolás")
            }
            IconButton(onClick = onDeleteSelection) {
                Icon(Icons.Filled.Delete, contentDescription = "Törlés")
            }
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Kijelölés megszüntetése")
            }
        }
    }
}

private fun StylusPointSample.toSingleStrokeInput(toolType: InputToolType): StrokeInput {
    val maxTilt = (Math.PI * 0.5).toFloat()
    val twoPi = (Math.PI * 2.0).toFloat()
    val safeTilt = if (tiltRadians.isFinite()) tiltRadians.coerceIn(0f, maxTilt) else 0f
    val rawOrient = if (orientationRadians.isFinite()) orientationRadians else 0f
    val wrappedOrient = ((rawOrient % twoPi) + twoPi) % twoPi
    val safeOrient = if (wrappedOrient >= twoPi) 0f else wrappedOrient
    val safePressure = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f

    return StrokeInput().apply {
        update(
            x = xPt,
            y = yPt,
            elapsedTimeMillis = elapsedTimeMillis.coerceAtLeast(0L),
            toolType = toolType,
            pressure = safePressure,
            tiltRadians = safeTilt,
            orientationRadians = safeOrient
        )
    }
}

private fun erasePointsOnHolder(
    holder: CanvasStateHolder,
    pt: DocPoint,
    radiusPt: Float,
    isVectorEraser: Boolean
): DocRect? = if (isVectorEraser) {
    holder.eraseWholeStrokesAt(
        centerXPt = pt.xPt,
        centerYPt = pt.yPt,
        radiusPt = radiusPt
    )
} else {
    holder.erasePartialStrokesAt(
        centerXPt = pt.xPt,
        centerYPt = pt.yPt,
        radiusPt = radiusPt
    ).invalidatedBounds
}

private fun mergeDocRects(a: DocRect?, b: DocRect): DocRect = if (a == null) {
    b
} else {
    DocRect(
        leftPt = min(a.leftPt, b.leftPt),
        topPt = min(a.topPt, b.topPt),
        rightPt = max(a.rightPt, b.rightPt),
        bottomPt = max(a.bottomPt, b.bottomPt)
    )
}

private fun resolveExcludedStrokesForDraw(
    lassoActive: Boolean,
    lassoIds: Set<String>,
    lassoBounds: DocRect?,
    liveActive: Boolean,
    liveIds: Set<String>,
    liveBounds: DocRect?
): Pair<Set<String>, DocRect?> = when {
    lassoActive && liveActive && liveIds.isNotEmpty() -> {
        val merged = HashSet<String>(lassoIds.size + liveIds.size).apply {
            addAll(lassoIds)
            addAll(liveIds)
        }
        val mergedRect = if (liveBounds != null) mergeDocRects(lassoBounds, liveBounds) else lassoBounds
        merged to mergedRect
    }
    lassoActive -> lassoIds to lassoBounds
    liveActive && liveIds.isNotEmpty() -> liveIds to liveBounds
    else -> emptySet<String>() to null
}
