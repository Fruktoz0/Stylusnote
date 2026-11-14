package hu.bdev.stylusnote.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.layout.onSizeChanged
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
import hu.bdev.stylusnote.core.hardware.onHoverEvent
import hu.bdev.stylusnote.core.hardware.onTouchEvent
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.OledStrokeRenderCache
import hu.bdev.stylusnote.core.math.OneEuroFilter
import hu.bdev.stylusnote.core.math.ShapeRecognizer
import hu.bdev.stylusnote.core.math.SpatialIndex
import hu.bdev.stylusnote.core.math.toDocRect
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
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
    LASER,
    LASSO
}

private data class WetStrokeMeta(
    val isHighlighter: Boolean,
    val canonicalColorArgb: Int,
    val holdToSnapEnabled: Boolean
)

private class LassoSelectionRuntime {
    var activePolygon: MutableList<DocPoint> = ArrayList()
    var selectedIds: Set<String> = emptySet()
    var selectionBounds: DocRect? = null
    var isDraggingSelection: Boolean = false
    var dragOriginDoc: DocPoint? = null
    var liveDeltaXPt: Float = 0f
    var liveDeltaYPt: Float = 0f
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
 * 6. Ephemeral laser presentation overlay ([LaserOverlay]) + Lasso / Hold-to-Snap selection overlay
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
    laserStateHolder: LaserTrailStateHolder = remember { LaserTrailStateHolder() },
    journalCommitSink: JournalCommitSink = NoOpJournalCommitSink,
    onTogglePenEraser: () -> Unit = {},
    onCanvasStateMutated: () -> Unit = {},
    onViewportSizeChanged: (IntSize) -> Unit = {},
    onViewportTransformChanged: () -> Unit = {},
    externalDrawVersionTick: Long = 0L,
    modifier: Modifier = Modifier
) {
    val palette = LocalStylusSurfacePalette.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val oneEuroFilter = remember {
        OneEuroFilter(initialStabilizationPercent = brushConfig.clampedStabilizationPercent)
    }

    val dryDrawTick = remember { mutableLongStateOf(0L) }
    val laserDrawTick = remember { mutableLongStateOf(0L) }
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

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var hasInitializedViewport by remember { mutableStateOf(false) }
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

    val eraserCommandChannel = remember(dryStrokeStore) {
        Channel<EraserWorkCommand>(Channel.UNLIMITED)
    }
    LaunchedEffect(dryStrokeStore, dryInkRasterCache) {
        val stateHolder = dryStrokeStore as? CanvasStateHolder ?: return@LaunchedEffect
        for (cmd in eraserCommandChannel) {
            when (cmd) {
                EraserWorkCommand.BeginSession -> stateHolder.beginEraserSession()
                is EraserWorkCommand.CutAtPoints -> {
                    val dirtyRect = withContext(Dispatchers.Default) {
                        var combined: DocRect? = null
                        for (i in 0 until cmd.pointsDocPt.size) {
                            val pt = cmd.pointsDocPt[i]
                            val rect = if (cmd.isVectorEraser) {
                                stateHolder.eraseWholeStrokesAt(pt.xPt, pt.yPt, cmd.radiusPt)
                            } else {
                                stateHolder.erasePartialStrokesAt(pt.xPt, pt.yPt, cmd.radiusPt)
                                    .invalidatedBounds
                            }
                            if (rect != null) {
                                combined = combined?.let {
                                    DocRect(
                                        min(it.leftPt, rect.leftPt),
                                        min(it.topPt, rect.topPt),
                                        max(it.rightPt, rect.rightPt),
                                        max(it.bottomPt, rect.bottomPt)
                                    )
                                } ?: rect
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
                        stateHolder.finishEraserSession()
                    }
                    if (action != null) {
                        dryDrawTick.longValue++
                        onCanvasStateMutated()
                    }
                }
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
            var onTogglePenEraserCallback: () -> Unit = onTogglePenEraser
            var onMutatedCallback: () -> Unit = onCanvasStateMutated
            var onViewportTransformChangedCallback: () -> Unit = onViewportTransformChanged
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
        onTogglePenEraserCallback = onTogglePenEraser
        onMutatedCallback = onCanvasStateMutated
        onViewportTransformChangedCallback = onViewportTransformChanged
    }

    fun startLassoInteraction(firstPt: DocPoint) {
        val bounds = lassoRuntime.selectionBounds
        if (bounds != null && lassoRuntime.selectedIds.isNotEmpty() && bounds.inflate(18f).contains(firstPt.xPt, firstPt.yPt)) {
            lassoRuntime.isDraggingSelection = true
            lassoRuntime.dragOriginDoc = firstPt
            lassoRuntime.liveDeltaXPt = 0f
            lassoRuntime.liveDeltaYPt = 0f
            dryCanvasViewRef?.invalidate()
        } else {
            lassoRuntime.selectedIds = emptySet()
            lassoRuntime.selectionBounds = null
            lassoRuntime.isDraggingSelection = false
            lassoRuntime.activePolygon = ArrayList<DocPoint>(64).apply { add(firstPt) }
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    fun moveLassoInteraction(points: List<DocPoint>) {
        if (points.isEmpty()) return
        if (lassoRuntime.isDraggingSelection) {
            val origin = lassoRuntime.dragOriginDoc ?: return
            val last = points.last()
            lassoRuntime.liveDeltaXPt = last.xPt - origin.xPt
            lassoRuntime.liveDeltaYPt = last.yPt - origin.yPt
            dryCanvasViewRef?.invalidate()
        } else {
            lassoRuntime.activePolygon.addAll(points)
        }
        lassoDrawTick.longValue++
    }

    fun finishLassoInteraction(finalPoints: List<DocPoint>) {
        val stateHolder = dryStrokeStore as? CanvasStateHolder
        if (lassoRuntime.isDraggingSelection) {
            val origin = lassoRuntime.dragOriginDoc
            if (origin != null && finalPoints.isNotEmpty()) {
                val endPt = finalPoints.last()
                lassoRuntime.liveDeltaXPt = endPt.xPt - origin.xPt
                lassoRuntime.liveDeltaYPt = endPt.yPt - origin.yPt
            }
            val dx = lassoRuntime.liveDeltaXPt
            val dy = lassoRuntime.liveDeltaYPt
            val selected = lassoRuntime.selectedIds
            lassoRuntime.isDraggingSelection = false
            lassoRuntime.dragOriginDoc = null
            lassoRuntime.liveDeltaXPt = 0f
            lassoRuntime.liveDeltaYPt = 0f
            dryCanvasViewRef?.invalidate()

            // Rebuild immutable Stroke instances ONLY on ACTION_UP if actually moved.
            if (stateHolder != null && selected.isNotEmpty() && hypot(dx, dy) > 0.5f) {
                val dirty = stateHolder.transformStrokes(selected, AffineTransform2D.translation(dx, dy))
                if (dirty != null) {
                    for (id in selected) {
                        oledStrokeCache.invalidate(id)
                    }
                    dryInkRasterCache.invalidateDocRect(dirty)
                    lassoRuntime.selectionBounds = stateHolder.computeSelectionBounds(selected)
                    dryDrawTick.longValue++
                    routerConfigHolder.onMutatedCallback()
                }
            }
        } else {
            lassoRuntime.activePolygon.addAll(finalPoints)
            val poly = lassoRuntime.activePolygon
            if (stateHolder != null && poly.size >= 3) {
                val hits = stateHolder.selectStrokesByLasso(poly)
                lassoRuntime.selectedIds = hits.map { it.id }.toSet()
                lassoRuntime.selectionBounds = stateHolder.computeSelectionBounds(lassoRuntime.selectedIds)
            } else {
                lassoRuntime.selectedIds = emptySet()
                lassoRuntime.selectionBounds = null
            }
            lassoRuntime.activePolygon = ArrayList()
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    fun cancelLassoInteraction() {
        val wasDragging = lassoRuntime.isDraggingSelection
        lassoRuntime.isDraggingSelection = false
        lassoRuntime.dragOriginDoc = null
        lassoRuntime.liveDeltaXPt = 0f
        lassoRuntime.liveDeltaYPt = 0f
        lassoRuntime.activePolygon = ArrayList()
        if (wasDragging) {
            dryCanvasViewRef?.invalidate()
        }
        lassoSelectionVersion.longValue++
        lassoDrawTick.longValue++
    }

    val wetStrokeSink = remember(transformMatrix, eraserCommandChannel, laserStateHolder) {
        object : WetStrokeSink<MotionEvent> {
            private var activeMode: ActiveGestureMode = ActiveGestureMode.DRAW_INK
            private var activeToolType: BrushToolType = BrushToolType.FOUNTAIN_PEN
            private var activeStrokeId: InProgressStrokeId? = null
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
                tool.isLaser -> ActiveGestureMode.LASER
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

            override fun onStartRawStroke(
                event: MotionEvent,
                pointerId: Int,
                isEraserTool: Boolean
            ) {
                activeToolType = resolveToolForGesture(isEraserTool)
                activeMode = modeForTool(activeToolType)

                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        val view = inProgressViewRef ?: return
                        transformMatrix.populateScreenToDocMatrix(screenToDocMatrix)
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
                            holdToSnapEnabled = routerConfigHolder.config.holdToSnapEnabled
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
                    ActiveGestureMode.LASER -> {
                        val pts = extractRawDocPoints(event, pointerId)
                        if (pts.isNotEmpty()) {
                            laserStateHolder.startTrail(
                                firstPoint = pts.first(),
                                colorArgb = routerConfigHolder.config.colorArgb,
                                widthPt = routerConfigHolder.config.clampedSizePt.coerceAtLeast(3f)
                            )
                            if (pts.size > 1) {
                                laserStateHolder.appendPoints(pts.drop(1))
                            }
                            laserDrawTick.longValue++
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
                    ActiveGestureMode.LASER -> {
                        val pts = extractRawDocPoints(realEvent, pointerId)
                        if (pts.isNotEmpty()) {
                            laserStateHolder.appendPoints(pts)
                            laserDrawTick.longValue++
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
                    ActiveGestureMode.LASER -> {
                        laserStateHolder.finishTrail(extractRawDocPoints(event, pointerId))
                        laserDrawTick.longValue++
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
                activeToolType = resolveToolForGesture(firstSample.isEraserTool)
                activeMode = modeForTool(activeToolType)

                when (activeMode) {
                    ActiveGestureMode.DRAW_INK -> {
                        val view = inProgressViewRef ?: return
                        transformMatrix.populateDocToScreenMatrix(docToScreenMatrix)
                        lastBatchSample = firstSample
                        val input = firstSample.toSingleStrokeInput(toolType)
                        val strokeId = view.startStroke(
                            input = input,
                            brush = routerConfigHolder.wetBrush,
                            strokeToViewTransform = docToScreenMatrix
                        )
                        activeStrokeId = strokeId
                        wetStrokeMetaMap[strokeId] = WetStrokeMeta(
                            isHighlighter = activeToolType.isHighlighter,
                            canonicalColorArgb = routerConfigHolder.canonicalBrushColorArgb,
                            holdToSnapEnabled = routerConfigHolder.config.holdToSnapEnabled
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
                    ActiveGestureMode.LASER -> {
                        laserStateHolder.startTrail(
                            firstPoint = DocPoint(firstSample.xPt, firstSample.yPt),
                            colorArgb = routerConfigHolder.config.colorArgb,
                            widthPt = routerConfigHolder.config.clampedSizePt.coerceAtLeast(3f)
                        )
                        laserDrawTick.longValue++
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
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return
                        if (realSamples.isEmpty() && predictedSamples.isEmpty()) return

                        if (realSamples.isNotEmpty()) {
                            lastBatchSample = realSamples.last()
                        }
                        val realBatch = realSamples.toStrokeInputBatch(toolType)
                        val predBatch = if (predictedSamples.isEmpty()) {
                            ImmutableStrokeInputBatch.EMPTY
                        } else {
                            predictedSamples.toStrokeInputBatch(toolType)
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
                    ActiveGestureMode.LASER -> {
                        if (realSamples.isNotEmpty()) {
                            laserStateHolder.appendPoints(realSamples.map { DocPoint(it.xPt, it.yPt) })
                            laserDrawTick.longValue++
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
                        val view = inProgressViewRef ?: return
                        val strokeId = activeStrokeId ?: return

                        if (finalSamples.size > 1) {
                            val intermediateBatch = finalSamples.dropLast(1).toStrokeInputBatch(toolType)
                            view.addToStroke(
                                inputs = intermediateBatch,
                                strokeId = strokeId,
                                prediction = ImmutableStrokeInputBatch.EMPTY
                            )
                        }
                        val terminalSample = finalSamples.lastOrNull() ?: lastBatchSample
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
                    ActiveGestureMode.LASER -> {
                        laserStateHolder.finishTrail(finalSamples.map { DocPoint(it.xPt, it.yPt) })
                        laserDrawTick.longValue++
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
                    ActiveGestureMode.LASER -> {
                        laserStateHolder.cancelActiveTrail()
                        laserDrawTick.longValue++
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
    LaunchedEffect(brushConfig.toolType) {
        if (brushConfig.toolType != BrushToolType.LASSO && lassoRuntime.selectedIds.isNotEmpty()) {
            lassoRuntime.selectedIds = emptySet()
            lassoRuntime.selectionBounds = null
            lassoSelectionVersion.longValue++
            lassoDrawTick.longValue++
        }
    }
    LaunchedEffect(palette.isOledDeepBlack) {
        oledStrokeCache.clear()
        dryInkRasterCache.clearAll()
        dryDrawTick.longValue++
    }
    LaunchedEffect(paperConfig) {
        dryDrawTick.longValue++
    }
    LaunchedEffect(dryDrawTick.longValue, externalDrawVersionTick, paperConfig, palette.isOledDeepBlack) {
        dryCanvasViewRef?.invalidate()
    }
    LaunchedEffect(externalDrawVersionTick) {
        if (externalDrawVersionTick > 0L) {
            dryInkRasterCache.onGestureSettled(transformMatrix.scale)
            val vpW = viewportSize.width
            val vpH = viewportSize.height
            val isOled = palette.isOledDeepBlack
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
            dryDrawTick.longValue++
            dryCanvasViewRef?.invalidate()
        }
    }

    val finishedListener = remember(dryStrokeStore, dryInkRasterCache, journalCommitSink) {
        object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                if (strokes.isEmpty()) return

                val holder = dryStrokeStore as? CanvasStateHolder
                val committed = ArrayList<IndexedDryStroke>(strokes.size)
                val snapCandidates = ArrayList<Pair<IndexedDryStroke, WetStrokeMeta>>()

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
                    val entry = IndexedDryStroke(
                        id = UUID.randomUUID().toString(),
                        stroke = canonicalStroke,
                        bounds = docBounds,
                        zIndex = zIndex,
                        isHighlighter = meta.isHighlighter
                    )
                    committed.add(entry)
                    dryInkRasterCache.invalidateDocRect(docBounds)
                    if (meta.holdToSnapEnabled && holder != null) {
                        snapCandidates.add(entry to meta)
                    }
                }

                dryStrokeStore.addCommittedStrokes(committed)

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
                        dryInkRasterCache.invalidateDocRect(freehandEntry.bounds)
                        dryInkRasterCache.invalidateDocRect(snappedEntry.bounds)
                        finalJournalEntries.remove(freehandEntry)
                        finalJournalEntries.add(snappedEntry)
                    }
                }

                journalCommitSink.onStrokesCommitted(finalJournalEntries)

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
                if (!hasInitializedViewport && size.width > 0 && size.height > 0) {
                    transformMatrix.fitPageToViewport(
                        pageWidthPt = paperConfig.pageDimensions.widthPt,
                        pageHeightPt = paperConfig.pageDimensions.heightPt,
                        viewportWidthPx = size.width.toFloat(),
                        viewportHeightPx = size.height.toFloat()
                    )
                    dryInkRasterCache.syncInitialScale(transformMatrix.scale)
                    val initPaperConfig = routerConfigHolder.latestPaperConfig
                    val initPalette = routerConfigHolder.latestPalette
                    val isOled = initPalette.isOledDeepBlack
                    if (dryStrokeStore.spatialIndex.size > 0 || initPaperConfig.canvasMode == CanvasMode.FIXED_PAGE) {
                        coroutineScope.launch {
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
                            dryDrawTick.longValue++
                            dryCanvasViewRef?.invalidate()
                        }
                    } else {
                        dryDrawTick.longValue++
                        dryCanvasViewRef?.invalidate()
                    }
                    hasInitializedViewport = true
                    routerConfigHolder.onViewportTransformChangedCallback()
                }
            }
    ) {
        // Layers 1-4a (DryCanvasView) + Layer 4b (InProgressStrokesView wet front-buffer)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val dryCanvasView = DryCanvasView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val hintSession = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(PerformanceHintManager::class.java)
                        ?.createHintSession(intArrayOf(Process.myTid()), 6_944_444L)
                } else {
                    null
                }
                val wetView = InProgressStrokesView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    motionEventToViewTransform = Matrix()
                    val predictorBridge = AndroidMotionEventPredictorBridge(this)
                    val settleRunnable = Runnable {
                        val currentScale = transformMatrix.scale
                        val vpSize = routerConfigHolder.latestViewportSize
                        val paperCfg = routerConfigHolder.latestPaperConfig
                        val pal = routerConfigHolder.latestPalette
                        val isOled = pal.isOledDeepBlack
                        activeRebakeJob?.cancel()
                        activeRebakeJob = coroutineScope.launch {
                            withContext(Dispatchers.Default) {
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
                            dryCanvasView.invalidate()
                            routerConfigHolder.onViewportTransformChangedCallback()
                        }
                    }
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = Unit
                        override fun onViewDetachedFromWindow(v: View) {
                            v.removeCallbacks(settleRunnable)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                hintSession?.close()
                            }
                        }
                    })

                    val router = StylusInputRouter(
                        transformMatrix = transformMatrix,
                        oneEuroFilter = oneEuroFilter,
                        wetStrokeSink = wetStrokeSink,
                        predictorBridge = predictorBridge,
                        onTransformChanged = {
                            val wasInGesture = dryInkRasterCache.isGestureInProgress
                            if (!wasInGesture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                hintSession?.reportActualWorkDuration(6_900_000L)
                            }
                            dryInkRasterCache.onGestureTransformChanged()
                            dryCanvasView.invalidate()
                            if (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.activePolygon.isNotEmpty()) {
                                lassoDrawTick.longValue++
                            }
                            // Debounce settle rebake without allocating coroutine Jobs per 7 ms MOVE frame.
                            removeCallbacks(settleRunnable)
                            postDelayed(settleRunnable, DryInkRasterCache.SETTLE_DEBOUNCE_MS)
                        },
                        onHoverStateChanged = { state ->
                            hoverStateHolder.current = state
                            hoverDrawTick.longValue++
                        },
                        onTogglePenEraser = {
                            routerConfigHolder.onTogglePenEraserCallback()
                        }
                    ).also { createdRouter ->
                        createdRouter.setStabilizationPercent(brushConfig.clampedStabilizationPercent)
                        createdRouter.pressureCurve = brushConfig.pressureCurve
                        createdRouter.allowFingerDrawing = allowFingerDrawing
                        inputRouter = createdRouter
                    }

                    setOnTouchListener { view, event ->
                        // Always claim ACTION_DOWN/POINTER_DOWN so Android delivers 2nd-finger pinch/pan events.
                        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                        ) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        router.onTouchEvent(event)
                        true
                    }
                    setOnHoverListener { _, event ->
                        router.onHoverEvent(event)
                    }
                }

                dryCanvasView.onDrawCallback = { canvas ->
                    val drawStartNs = System.nanoTime()
                    val paperCfg = routerConfigHolder.latestPaperConfig
                    val pal = routerConfigHolder.latestPalette
                    val isOled = pal.isOledDeepBlack
                    val vpW = dryCanvasView.width
                    val vpH = dryCanvasView.height
                    if (vpW > 0 && vpH > 0) {
                        val usedComposite = !lassoRuntime.isDraggingSelection &&
                            dryInkRasterCache.drawCompositePageIfAvailable(
                                canvas = canvas,
                                transformMatrix = transformMatrix,
                                paperConfig = paperCfg,
                                isOledMode = isOled
                            )
                        val hasDeferredTiles = if (usedComposite) {
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
                            dryInkRasterCache.drawVisibleTiles(
                                canvas = canvas,
                                viewportWidthPx = vpW,
                                viewportHeightPx = vpH,
                                transformMatrix = transformMatrix,
                                spatialIndex = dryStrokeStore.spatialIndex,
                                isOledMode = isOled,
                                strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) }
                            )
                        }

                        // Live Lasso drag preview rendered directly with translated transform without rebuilding Strokes.
                        if (lassoRuntime.isDraggingSelection && lassoRuntime.selectedIds.isNotEmpty()) {
                            val holder = dryStrokeStore as? CanvasStateHolder
                            if (holder != null) {
                                transformMatrix.populateDocToScreenMatrix(livePreviewMatrix)
                                livePreviewMatrix.preTranslate(
                                    lassoRuntime.liveDeltaXPt,
                                    lassoRuntime.liveDeltaYPt
                                )
                                for (id in lassoRuntime.selectedIds) {
                                    val entry = holder.getStroke(id) ?: continue
                                    val renderStroke = oledStrokeCache.adaptStroke(entry, isOled)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            hintSession?.reportActualWorkDuration(
                                (System.nanoTime() - drawStartNs).coerceAtLeast(1_000_000L)
                            )
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

        // Layer 6a: Ephemeral laser overlay
        if (brushConfig.toolType.isLaser || laserDrawTick.longValue > 0L) {
            LaserOverlay(
                stateHolder = laserStateHolder,
                transformMatrix = transformMatrix,
                activityTick = laserDrawTick
            )
        }

        // Layer 6b: Lasso trace & selection marquee
        lassoSelectionVersion.longValue
        if (lassoRuntime.selectedIds.isNotEmpty() || lassoRuntime.activePolygon.isNotEmpty()) {
            LassoSelectionCanvasOverlay(
                lassoRuntime = lassoRuntime,
                lassoTick = lassoDrawTick,
                transformMatrix = transformMatrix
            )
        }

        // Layer 7: Hover cursor outline
        if (hoverDrawTick.longValue > 0L && hoverStateHolder.current.isVisible) {
            HoverCursorOverlay(
                hoverStateProvider = { hoverStateHolder.current },
                hoverTick = hoverDrawTick,
                transformMatrix = transformMatrix,
                brushSizePt = brushConfig.clampedSizePt,
                brushColor = Color(brushConfig.effectiveColorArgb()),
                isEraserMode = brushConfig.toolType.isEraser,
                eraserRadiusPx = eraserRadiusPx
            )
        }

        // Floating selection actions bar when Lasso has active strokes selected
        lassoSelectionVersion.longValue
        val selectedCount = lassoRuntime.selectedIds.size
        val stateHolder = dryStrokeStore as? CanvasStateHolder
        if (selectedCount > 0 && stateHolder != null && !lassoRuntime.isDraggingSelection) {
            LassoSelectionActionBar(
                selectedCount = selectedCount,
                onTransformSelection = { transform ->
                    val dirty = stateHolder.transformStrokes(lassoRuntime.selectedIds, transform)
                    if (dirty != null) {
                        for (id in lassoRuntime.selectedIds) oledStrokeCache.invalidate(id)
                        dryInkRasterCache.invalidateDocRect(dirty)
                        lassoRuntime.selectionBounds = stateHolder.computeSelectionBounds(lassoRuntime.selectedIds)
                        dryDrawTick.longValue++
                        lassoSelectionVersion.longValue++
                        lassoDrawTick.longValue++
                        onCanvasStateMutated()
                    }
                },
                onRecolorSelection = {
                    val dirty = stateHolder.recolorStrokes(
                        strokeIds = lassoRuntime.selectedIds,
                        newColorArgb = brushConfig.effectiveColorArgb()
                    )
                    if (dirty != null) {
                        for (id in lassoRuntime.selectedIds) oledStrokeCache.invalidate(id)
                        dryInkRasterCache.invalidateDocRect(dirty)
                        dryDrawTick.longValue++
                        onCanvasStateMutated()
                    }
                },
                onDuplicateSelection = {
                    val copies = stateHolder.duplicateStrokes(lassoRuntime.selectedIds)
                    if (copies.isNotEmpty()) {
                        for (copy in copies) dryInkRasterCache.invalidateDocRect(copy.bounds)
                        lassoRuntime.selectedIds = copies.map { it.id }.toSet()
                        lassoRuntime.selectionBounds = stateHolder.computeSelectionBounds(lassoRuntime.selectedIds)
                        dryDrawTick.longValue++
                        lassoSelectionVersion.longValue++
                        lassoDrawTick.longValue++
                        onCanvasStateMutated()
                    }
                },
                onDeleteSelection = {
                    val dirty = stateHolder.deleteStrokes(lassoRuntime.selectedIds)
                    if (dirty != null) {
                        for (id in lassoRuntime.selectedIds) oledStrokeCache.invalidate(id)
                        dryInkRasterCache.invalidateDocRect(dirty)
                    }
                    lassoRuntime.selectedIds = emptySet()
                    lassoRuntime.selectionBounds = null
                    dryDrawTick.longValue++
                    lassoSelectionVersion.longValue++
                    lassoDrawTick.longValue++
                    onCanvasStateMutated()
                },
                onClearSelection = {
                    lassoRuntime.selectedIds = emptySet()
                    lassoRuntime.selectionBounds = null
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

        val poly = lassoRuntime.activePolygon
        if (poly.size >= 2) {
            tracePath.reset()
            val first = transformMatrix.docToScreen(poly[0].xPt, poly[0].yPt)
            tracePath.moveTo(first.xPx, first.yPx)
            for (i in 1 until poly.size) {
                val p = transformMatrix.docToScreen(poly[i].xPt, poly[i].yPt)
                tracePath.lineTo(p.xPx, p.yPx)
            }
            drawPath(
                path = tracePath,
                color = Color(0xFF1A56DB),
                style = ComposeStroke(width = 2.5f, pathEffect = dashEffect)
            )
        }

        val bounds = lassoRuntime.selectionBounds
        if (bounds != null && lassoRuntime.selectedIds.isNotEmpty()) {
            val dx = if (lassoRuntime.isDraggingSelection) lassoRuntime.liveDeltaXPt else 0f
            val dy = if (lassoRuntime.isDraggingSelection) lassoRuntime.liveDeltaYPt else 0f
            val shifted = DocRect(
                leftPt = bounds.leftPt + dx,
                topPt = bounds.topPt + dy,
                rightPt = bounds.rightPt + dx,
                bottomPt = bounds.bottomPt + dy
            )
            val screenRect = transformMatrix.docRectToScreenRect(shifted)
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
        }
    }
}

@Composable
private fun LassoSelectionActionBar(
    selectedCount: Int,
    selectionBoundsProvider: () -> DocRect?,
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
                modifier = Modifier.padding(end = 6.dp)
            )
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
                Icon(Icons.Filled.RotateRight, contentDescription = "Forgatás +15°")
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
