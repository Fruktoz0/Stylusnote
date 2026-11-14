package hu.bdev.stylusnote.core.hardware

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.OneEuroFilter
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Hardware-independent pressure response curves (Section 4).
 * Computes `p' = clamp(p, 0, 1)^gamma` without assuming any fixed hardware step count.
 */
enum class PressureCurve(
    val gamma: Float,
    val displayName: String
) {
    SOFT(gamma = 0.7f, displayName = "Lágy"),
    LINEAR(gamma = 1.0f, displayName = "Lineáris"),
    HARD(gamma = 1.5f, displayName = "Kemény");

    fun apply(rawPressure: Float): Float = applyPressureCurve(rawPressure, gamma)
}

/**
 * Applies `p' = clamp(p, 0f, 1f)^gamma`, safeguarding against NaN/Infinity values.
 */
fun applyPressureCurve(rawPressure: Float, gamma: Float): Float {
    if (!rawPressure.isFinite() || !gamma.isFinite()) return 0f
    val clamped = rawPressure.coerceIn(0f, 1f)
    if (gamma == 1.0f) return clamped
    return clamped.toDouble().pow(gamma.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * Hover cursor telemetry emitted for [HoverCursorOverlay].
 */
data class HoverCursorState(
    val isVisible: Boolean = false,
    val screenXPx: Float = 0f,
    val screenYPx: Float = 0f,
    val docXPt: Float = 0f,
    val docYPt: Float = 0f,
    val tiltRadians: Float = 0f,
    val orientationRadians: Float = 0f,
    val isEraserTool: Boolean = false
)

/**
 * Document-space stylus sample ready for conversion into Jetpack Ink [StrokeInput] batches.
 */
data class StylusPointSample(
    val xPt: Float,
    val yPt: Float,
    val elapsedTimeMillis: Long,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float,
    val isEraserTool: Boolean
)

/**
 * Raw screen-space sample extracted from a `MotionEvent` or predicted `MotionEvent`.
 */
data class RawScreenSample(
    val xPx: Float,
    val yPx: Float,
    val eventTimeMs: Long,
    val eventTimeNanos: Long,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float
)

/**
 * Converts a sequence of [StylusPointSample] points into a [MutableStrokeInputBatch],
 * enforcing Ink's monotonic time, tilt `[0, PI/2]`, and orientation `[0, 2*PI)` invariants.
 */
fun List<StylusPointSample>.toStrokeInputBatch(
    toolType: InputToolType = InputToolType.STYLUS
): MutableStrokeInputBatch {
    val batch = MutableStrokeInputBatch()
    if (isEmpty()) return batch

    val reusableInput = StrokeInput()
    var lastElapsedMs = 0L
    val maxTilt = (PI * 0.5).toFloat()
    val twoPi = (PI * 2.0).toFloat()

    for (i in 0 until size) {
        val sample = this[i]
        if (!sample.xPt.isFinite() || !sample.yPt.isFinite()) continue
        val safeElapsed = maxOf(lastElapsedMs, sample.elapsedTimeMillis.coerceAtLeast(0L))
        lastElapsedMs = safeElapsed

        val safePressure = if (sample.pressure.isFinite()) sample.pressure.coerceIn(0f, 1f) else 0f
        val safeTilt = if (sample.tiltRadians.isFinite()) sample.tiltRadians.coerceIn(0f, maxTilt) else 0f
        val rawOrient = if (sample.orientationRadians.isFinite()) sample.orientationRadians else 0f
        val wrappedOrientation = ((rawOrient % twoPi) + twoPi) % twoPi
        val safeOrientation = if (wrappedOrientation >= twoPi) 0f else wrappedOrientation

        reusableInput.update(
            x = sample.xPt,
            y = sample.yPt,
            elapsedTimeMillis = safeElapsed,
            toolType = toolType,
            pressure = safePressure,
            tiltRadians = safeTilt,
            orientationRadians = safeOrientation
        )
        batch.add(reusableInput)
    }
    return batch
}

/**
 * Normalized action codes decoupled from `android.view.MotionEvent` for JVM testability.
 */
enum class StylusAction {
    DOWN,
    POINTER_DOWN,
    MOVE,
    UP,
    POINTER_UP,
    CANCEL,
    HOVER_ENTER,
    HOVER_MOVE,
    HOVER_EXIT,
    OTHER
}

/**
 * Single pointer sample inside a [StylusMotionFrame].
 */
data class StylusPointerSnapshot(
    val pointerId: Int,
    val toolType: Int,
    val xPx: Float,
    val yPx: Float,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float
)

/**
 * Complete pointer frame dispatched to [StylusInputRouter].
 */
data class StylusMotionFrame<TEvent>(
    val action: StylusAction,
    val actionIndex: Int,
    val flags: Int,
    val buttonState: Int,
    val eventTimeMs: Long,
    val eventTimeNanos: Long,
    val pointers: List<StylusPointerSnapshot>,
    val historyForActivePointer: List<RawScreenSample> = emptyList(),
    val rawEvent: TEvent? = null
)

/**
 * Bridge over [MotionEventPredictor] guaranteeing raw recording, sample extraction,
 * and mandatory `finally { recycle() }` cleanup.
 */
interface MotionPredictorBridge<TEvent> {
    fun record(event: TEvent)
    fun predict(): TEvent?
    fun extractPredictedSamples(predictedEvent: TEvent, pointerId: Int): List<RawScreenSample>
    fun recycle(predictedEvent: TEvent)
}

/**
 * Android View implementation of [MotionPredictorBridge] backed by Jetpack
 * `androidx.input:input-motionprediction`.
 */
class AndroidMotionEventPredictorBridge(
    view: View
) : MotionPredictorBridge<MotionEvent> {

    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(view)

    override fun record(event: MotionEvent) {
        predictor.record(event)
    }

    override fun predict(): MotionEvent? = predictor.predict()

    override fun extractPredictedSamples(
        predictedEvent: MotionEvent,
        pointerId: Int
    ): List<RawScreenSample> {
        val pointerIdx = predictedEvent.findPointerIndex(pointerId)
        if (pointerIdx < 0) return emptyList()

        val historySize = predictedEvent.historySize
        val samples = ArrayList<RawScreenSample>(historySize + 1)
        for (h in 0 until historySize) {
            val eventMs = predictedEvent.getHistoricalEventTime(h)
            samples.add(
                RawScreenSample(
                    xPx = predictedEvent.getHistoricalX(pointerIdx, h),
                    yPx = predictedEvent.getHistoricalY(pointerIdx, h),
                    eventTimeMs = eventMs,
                    eventTimeNanos = eventMs * 1_000_000L,
                    pressure = predictedEvent.getHistoricalPressure(pointerIdx, h),
                    tiltRadians = predictedEvent.getHistoricalAxisValue(
                        MotionEvent.AXIS_TILT,
                        pointerIdx,
                        h
                    ),
                    orientationRadians = predictedEvent.getHistoricalAxisValue(
                        MotionEvent.AXIS_ORIENTATION,
                        pointerIdx,
                        h
                    )
                )
            )
        }

        val currentMs = predictedEvent.eventTime
        samples.add(
            RawScreenSample(
                xPx = predictedEvent.getX(pointerIdx),
                yPx = predictedEvent.getY(pointerIdx),
                eventTimeMs = currentMs,
                eventTimeNanos = currentMs * 1_000_000L,
                pressure = predictedEvent.getPressure(pointerIdx),
                tiltRadians = predictedEvent.getAxisValue(MotionEvent.AXIS_TILT, pointerIdx),
                orientationRadians = predictedEvent.getAxisValue(
                    MotionEvent.AXIS_ORIENTATION,
                    pointerIdx
                )
            )
        )
        return samples
    }

    override fun recycle(predictedEvent: MotionEvent) {
        predictedEvent.recycle()
    }
}

/**
 * Downstream sink receiving either zero-latency raw `MotionEvent` calls (0% stabilization)
 * or One-Euro filtered document-space batches (>0% stabilization).
 */
interface WetStrokeSink<TEvent> {
    fun onStartRawStroke(event: TEvent, pointerId: Int, isEraserTool: Boolean)
    fun onAddToRawStroke(realEvent: TEvent, predictedEvent: TEvent?, pointerId: Int)
    fun onFinishRawStroke(event: TEvent, pointerId: Int)

    fun onStartBatchStroke(firstSample: StylusPointSample, toolType: InputToolType)
    fun onAddBatchToStroke(
        realSamples: List<StylusPointSample>,
        predictedSamples: List<StylusPointSample>,
        toolType: InputToolType
    )
    fun onFinishBatchStroke(finalSamples: List<StylusPointSample>, toolType: InputToolType)

    fun onCancelActiveStroke()
}

/**
 * Central hardware input router for Xiaomi Pad 8 + Xiaomi Focus Pen Pro (Section 4).
 *
 * Responsibilities:
 * 1. Strict palm rejection (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` only; optional finger mode).
 * 2. Immediate `cancelStroke` on `ACTION_CANCEL` or `FLAG_CANCELED`.
 * 3. Two ink pipelines:
 *    - `stabilization == 0%`: raw `MotionEvent` + prediction directly to `InProgressStrokesView`.
 *    - `stabilization > 0%`: iterates `0 until historySize`, filters real coordinates via
 *      [OneEuroFilter], and keeps predicted samples unfiltered to avoid overshoot hooks.
 * 4. Always records raw events into `MotionEventPredictor` BEFORE filtering and always recycles
 *    predicted events inside a `finally` block.
 * 5. Two-finger pan/zoom navigation locked out whenever a stylus stroke is active.
 * 6. Hover cursor tracking and Focus Pen Pro primary button toggle (`KEYCODE_STYLUS_BUTTON_PRIMARY`
 *    and `BUTTON_STYLUS_PRIMARY`).
 */
class StylusInputRouter<TEvent>(
    private val transformMatrix: CanvasTransformMatrix,
    private val oneEuroFilter: OneEuroFilter,
    private val wetStrokeSink: WetStrokeSink<TEvent>,
    internal val predictorBridge: MotionPredictorBridge<TEvent>? = null,
    private val onTransformChanged: () -> Unit = {},
    private val onHoverStateChanged: (HoverCursorState) -> Unit = {},
    private val onTogglePenEraser: () -> Unit = {}
) {
    var allowFingerDrawing: Boolean = false
    var pressureCurve: PressureCurve = PressureCurve.LINEAR

    internal var activeStrokePointerId: Int? = null
    private var activeStrokeStartTimeMs: Long = 0L
    private var activeStrokeIsStylus: Boolean = false
    private var activeStrokeToolType: InputToolType = InputToolType.STYLUS

    private var twoFingerNavActive: Boolean = false
    private var prevNavPointerId0: Int = -1
    private var prevNavPointerId1: Int = -1
    private var prevNavSpanPx: Float = 0f
    private var prevNavCentroidXPx: Float = 0f
    private var prevNavCentroidYPx: Float = 0f

    private var wasPrimaryStylusButtonPressed: Boolean = false

    val isStylusStrokeActive: Boolean
        get() = activeStrokePointerId != null && activeStrokeIsStylus

    val isStrokeActive: Boolean
        get() = activeStrokePointerId != null

    fun setStabilizationPercent(percent: Float) {
        oneEuroFilter.setStabilizationPercent(percent)
    }

    /**
     * Handles `KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY` presses from the Focus Pen Pro.
     */
    fun onKeyDown(keyCode: Int, repeatCount: Int = 0): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY) {
            if (repeatCount == 0) {
                onTogglePenEraser()
            }
            return true
        }
        return false
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        onKeyDown(keyCode, event?.repeatCount ?: 0)

    /**
     * Processes a touch or stylus [StylusMotionFrame].
     * Returns `true` if the frame was consumed by drawing, cancellation, or two-finger navigation.
     */
    fun processTouchFrame(frame: StylusMotionFrame<TEvent>): Boolean {
        detectPrimaryStylusButtonEdge(frame.buttonState)

        val isCanceled = frame.action == StylusAction.CANCEL ||
            (frame.flags and MotionEvent.FLAG_CANCELED) != 0
        if (isCanceled) {
            val hadState = activeStrokePointerId != null || twoFingerNavActive
            cancelAllActiveGestures()
            return hadState
        }

        // Check if a stylus/eraser pointer is initiating a new stroke (even mid-pan).
        if (frame.action == StylusAction.DOWN || frame.action == StylusAction.POINTER_DOWN) {
            val actionPointer = frame.pointers.getOrNull(frame.actionIndex)
            if (actionPointer != null && isAcceptedDrawingPointer(actionPointer, frame.pointers.size)) {
                val isStylusOrEraser = actionPointer.toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                    actionPointer.toolType == MotionEvent.TOOL_TYPE_ERASER

                // Abort any ongoing two-finger pan/zoom when stylus takes priority.
                if (isStylusOrEraser) {
                    twoFingerNavActive = false
                }

                // If no stroke is currently active, begin a new one.
                if (activeStrokePointerId == null) {
                    startStrokeForPointer(frame, actionPointer, isStylusOrEraser)
                    return true
                }
            } else if (activeStrokePointerId != null && !activeStrokeIsStylus &&
                frame.action == StylusAction.POINTER_DOWN &&
                frame.pointers.count { it.toolType == MotionEvent.TOOL_TYPE_FINGER } >= 2
            ) {
                // Cancel single-finger stroke if user places a second finger to pinch-zoom.
                cancelActiveStrokeOnly()
            }
        }

        // Route updates for the active stroke.
        val currentStrokePointerId = activeStrokePointerId
        if (currentStrokePointerId != null) {
            val activePointer = frame.pointers.firstOrNull { it.pointerId == currentStrokePointerId }
            return when (frame.action) {
                StylusAction.MOVE -> {
                    if (activePointer != null) {
                        addMoveToStroke(frame, activePointer)
                    }
                    true
                }
                StylusAction.UP -> {
                    finishStrokeForPointer(frame, activePointer)
                    true
                }
                StylusAction.POINTER_UP -> {
                    val liftedPointer = frame.pointers.getOrNull(frame.actionIndex)
                    if (liftedPointer?.pointerId == currentStrokePointerId) {
                        finishStrokeForPointer(frame, liftedPointer)
                    }
                    true
                }
                else -> true
            }
        }

        // Ignore all finger gestures while a stylus stroke is active; otherwise allow 2-finger pan/zoom.
        return handleTwoFingerNavigation(frame)
    }

    /**
     * Processes hover events (`ACTION_HOVER_ENTER`, `ACTION_HOVER_MOVE`, `ACTION_HOVER_EXIT`)
     * to position [HoverCursorOverlay] and detect Focus Pen Pro hover button presses.
     */
    fun processHoverFrame(frame: StylusMotionFrame<TEvent>): Boolean {
        detectPrimaryStylusButtonEdge(frame.buttonState)

        if (frame.action == StylusAction.HOVER_EXIT) {
            onHoverStateChanged(HoverCursorState(isVisible = false))
            return true
        }

        if (frame.action != StylusAction.HOVER_ENTER && frame.action != StylusAction.HOVER_MOVE) {
            return false
        }

        val pointer = frame.pointers.firstOrNull {
            it.toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                it.toolType == MotionEvent.TOOL_TYPE_ERASER
        } ?: return false

        val docPt = transformMatrix.screenToDoc(pointer.xPx, pointer.yPx)
        onHoverStateChanged(
            HoverCursorState(
                isVisible = true,
                screenXPx = pointer.xPx,
                screenYPx = pointer.yPx,
                docXPt = docPt.xPt,
                docYPt = docPt.yPt,
                tiltRadians = pointer.tiltRadians,
                orientationRadians = pointer.orientationRadians,
                isEraserTool = pointer.toolType == MotionEvent.TOOL_TYPE_ERASER
            )
        )
        return true
    }

    private fun startStrokeForPointer(
        frame: StylusMotionFrame<TEvent>,
        pointer: StylusPointerSnapshot,
        isStylusOrEraser: Boolean
    ) {
        activeStrokePointerId = pointer.pointerId
        activeStrokeStartTimeMs = frame.eventTimeMs
        activeStrokeIsStylus = isStylusOrEraser
        activeStrokeToolType = when (pointer.toolType) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputToolType.STYLUS
            else -> InputToolType.TOUCH
        }

        oneEuroFilter.reset()
        onHoverStateChanged(HoverCursorState(isVisible = false))

        val isEraser = pointer.toolType == MotionEvent.TOOL_TYPE_ERASER
        if (oneEuroFilter.isBypassed && frame.rawEvent != null) {
            wetStrokeSink.onStartRawStroke(frame.rawEvent, pointer.pointerId, isEraser)
        } else {
            val sample = toFilteredDocSample(
                xPx = pointer.xPx,
                yPx = pointer.yPx,
                eventTimeMs = frame.eventTimeMs,
                eventTimeNanos = frame.eventTimeNanos,
                rawPressure = pointer.pressure,
                tiltRadians = pointer.tiltRadians,
                orientationRadians = pointer.orientationRadians,
                isEraser = isEraser,
                applyFilter = true
            )
            wetStrokeSink.onStartBatchStroke(sample, activeStrokeToolType)
        }
    }

    private fun addMoveToStroke(
        frame: StylusMotionFrame<TEvent>,
        activePointer: StylusPointerSnapshot
    ) {
        val predictedEvent = predictorBridge?.predict()
        try {
            if (oneEuroFilter.isBypassed && frame.rawEvent != null) {
                wetStrokeSink.onAddToRawStroke(
                    realEvent = frame.rawEvent,
                    predictedEvent = predictedEvent,
                    pointerId = activePointer.pointerId
                )
            } else {
                val isEraser = activePointer.toolType == MotionEvent.TOOL_TYPE_ERASER
                val realSamples = ArrayList<StylusPointSample>(frame.historyForActivePointer.size + 1)
                for (i in 0 until frame.historyForActivePointer.size) {
                    val h = frame.historyForActivePointer[i]
                    realSamples.add(
                        toFilteredDocSample(
                            xPx = h.xPx,
                            yPx = h.yPx,
                            eventTimeMs = h.eventTimeMs,
                            eventTimeNanos = h.eventTimeNanos,
                            rawPressure = h.pressure,
                            tiltRadians = h.tiltRadians,
                            orientationRadians = h.orientationRadians,
                            isEraser = isEraser,
                            applyFilter = true
                        )
                    )
                }
                realSamples.add(
                    toFilteredDocSample(
                        xPx = activePointer.xPx,
                        yPx = activePointer.yPx,
                        eventTimeMs = frame.eventTimeMs,
                        eventTimeNanos = frame.eventTimeNanos,
                        rawPressure = activePointer.pressure,
                        tiltRadians = activePointer.tiltRadians,
                        orientationRadians = activePointer.orientationRadians,
                        isEraser = isEraser,
                        applyFilter = true
                    )
                )

                // Predicted points MUST bypass OneEuroFilter to avoid phase-lag overshoot hooks.
                val rawPredicted = if (predictedEvent != null && predictorBridge != null) {
                    predictorBridge.extractPredictedSamples(predictedEvent, activePointer.pointerId)
                } else {
                    emptyList()
                }
                val predictedSamples = if (rawPredicted.isEmpty()) {
                    emptyList()
                } else {
                    ArrayList<StylusPointSample>(rawPredicted.size).apply {
                        for (i in 0 until rawPredicted.size) {
                            val p = rawPredicted[i]
                            add(
                                toFilteredDocSample(
                                    xPx = p.xPx,
                                    yPx = p.yPx,
                                    eventTimeMs = p.eventTimeMs,
                                    eventTimeNanos = p.eventTimeNanos,
                                    rawPressure = p.pressure,
                                    tiltRadians = p.tiltRadians,
                                    orientationRadians = p.orientationRadians,
                                    isEraser = isEraser,
                                    applyFilter = false
                                )
                            )
                        }
                    }
                }

                wetStrokeSink.onAddBatchToStroke(
                    realSamples = realSamples,
                    predictedSamples = predictedSamples,
                    toolType = activeStrokeToolType
                )
            }
        } finally {
            if (predictedEvent != null) {
                predictorBridge?.recycle(predictedEvent)
            }
        }
    }

    private fun finishStrokeForPointer(
        frame: StylusMotionFrame<TEvent>,
        pointer: StylusPointerSnapshot?
    ) {
        val pointerId = activeStrokePointerId ?: return
        val toolType = activeStrokeToolType
        val wasBypassed = oneEuroFilter.isBypassed

        try {
            if (wasBypassed && frame.rawEvent != null) {
                wetStrokeSink.onFinishRawStroke(frame.rawEvent, pointerId)
            } else {
                val finalSamples = if (pointer != null) {
                    val isEraser = pointer.toolType == MotionEvent.TOOL_TYPE_ERASER
                    val out = ArrayList<StylusPointSample>(frame.historyForActivePointer.size + 1)
                    for (i in 0 until frame.historyForActivePointer.size) {
                        val h = frame.historyForActivePointer[i]
                        out.add(
                            toFilteredDocSample(
                                xPx = h.xPx,
                                yPx = h.yPx,
                                eventTimeMs = h.eventTimeMs,
                                eventTimeNanos = h.eventTimeNanos,
                                rawPressure = h.pressure,
                                tiltRadians = h.tiltRadians,
                                orientationRadians = h.orientationRadians,
                                isEraser = isEraser,
                                applyFilter = true
                            )
                        )
                    }
                    out.add(
                        toFilteredDocSample(
                            xPx = pointer.xPx,
                            yPx = pointer.yPx,
                            eventTimeMs = frame.eventTimeMs,
                            eventTimeNanos = frame.eventTimeNanos,
                            rawPressure = pointer.pressure,
                            tiltRadians = pointer.tiltRadians,
                            orientationRadians = pointer.orientationRadians,
                            isEraser = isEraser,
                            applyFilter = true
                        )
                    )
                    out
                } else {
                    emptyList()
                }
                wetStrokeSink.onFinishBatchStroke(finalSamples, toolType)
            }
        } finally {
            activeStrokePointerId = null
            activeStrokeIsStylus = false
            oneEuroFilter.reset()
        }
    }

    private fun toFilteredDocSample(
        xPx: Float,
        yPx: Float,
        eventTimeMs: Long,
        eventTimeNanos: Long,
        rawPressure: Float,
        tiltRadians: Float,
        orientationRadians: Float,
        isEraser: Boolean,
        applyFilter: Boolean
    ): StylusPointSample {
        val docPt = transformMatrix.screenToDoc(xPx, yPx)
        val finalPt = if (applyFilter) {
            val filtered = oneEuroFilter.filterNanos(docPt.xPt, docPt.yPt, eventTimeNanos)
            filtered
        } else {
            null
        }
        val xPt = finalPt?.x ?: docPt.xPt
        val yPt = finalPt?.y ?: docPt.yPt

        return StylusPointSample(
            xPt = xPt,
            yPt = yPt,
            elapsedTimeMillis = (eventTimeMs - activeStrokeStartTimeMs).coerceAtLeast(0L),
            pressure = pressureCurve.apply(rawPressure),
            tiltRadians = tiltRadians,
            orientationRadians = orientationRadians,
            isEraserTool = isEraser
        )
    }

    private fun handleTwoFingerNavigation(frame: StylusMotionFrame<TEvent>): Boolean {
        if (isStylusStrokeActive) {
            twoFingerNavActive = false
            return false
        }

        // Exclude lifted pointer on POINTER_UP/UP and ignore active stylus/eraser tools.
        val activeFingers = frame.pointers.filterIndexed { index, pointer ->
            val isLifting = (frame.action == StylusAction.UP || frame.action == StylusAction.POINTER_UP) &&
                index == frame.actionIndex
            val isStylusOrEraser = pointer.toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                pointer.toolType == MotionEvent.TOOL_TYPE_ERASER
            !isLifting && !isStylusOrEraser
        }

        if (activeFingers.size < 2) {
            val wasActive = twoFingerNavActive
            twoFingerNavActive = false
            prevNavPointerId0 = -1
            prevNavPointerId1 = -1
            return wasActive
        }

        val p0 = activeFingers[0]
        val p1 = activeFingers[1]
        val centroidX = (p0.xPx + p1.xPx) * 0.5f
        val centroidY = (p0.yPx + p1.yPx) * 0.5f
        val span = hypot((p1.xPx - p0.xPx).toDouble(), (p1.yPx - p0.yPx).toDouble()).toFloat()

        val samePointerPair = twoFingerNavActive &&
            prevNavPointerId0 == p0.pointerId &&
            prevNavPointerId1 == p1.pointerId

        if (samePointerPair && prevNavSpanPx > 1f && span > 1f) {
            val zoomFactor = span / prevNavSpanPx
            val panDx = centroidX - prevNavCentroidXPx
            val panDy = centroidY - prevNavCentroidYPx
            // Anchor scaling at the previous centroid so the doc point stays locked under the fingers after applying panDx/panDy.
            transformMatrix.zoomAroundScreenPoint(
                focalXPx = prevNavCentroidXPx,
                focalYPx = prevNavCentroidYPx,
                zoomFactor = zoomFactor,
                panDeltaXPx = panDx,
                panDeltaYPx = panDy
            )
            onTransformChanged()
        }

        prevNavPointerId0 = p0.pointerId
        prevNavPointerId1 = p1.pointerId
        prevNavSpanPx = span
        prevNavCentroidXPx = centroidX
        prevNavCentroidYPx = centroidY
        twoFingerNavActive = true
        return true
    }

    private fun isAcceptedDrawingPointer(
        pointer: StylusPointerSnapshot,
        totalPointerCount: Int
    ): Boolean = when (pointer.toolType) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_FINGER -> allowFingerDrawing && totalPointerCount == 1
        else -> false
    }

    internal fun hasAcceptedToolForPrediction(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val tool = event.getToolType(i)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS ||
                tool == MotionEvent.TOOL_TYPE_ERASER ||
                (allowFingerDrawing && tool == MotionEvent.TOOL_TYPE_FINGER && event.pointerCount == 1)
            ) {
                return true
            }
        }
        return false
    }

    private fun detectPrimaryStylusButtonEdge(buttonState: Int) {
        val isPressed = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (isPressed && !wasPrimaryStylusButtonPressed) {
            onTogglePenEraser()
        }
        wasPrimaryStylusButtonPressed = isPressed
    }

    private fun cancelActiveStrokeOnly() {
        if (activeStrokePointerId != null) {
            wetStrokeSink.onCancelActiveStroke()
            activeStrokePointerId = null
            activeStrokeIsStylus = false
            oneEuroFilter.reset()
        }
    }

    private fun cancelAllActiveGestures() {
        cancelActiveStrokeOnly()
        twoFingerNavActive = false
    }
}

private fun MotionEvent.toStylusMotionFrame(trackedPointerId: Int?): StylusMotionFrame<MotionEvent> {
    val mappedAction = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> StylusAction.DOWN
        MotionEvent.ACTION_POINTER_DOWN -> StylusAction.POINTER_DOWN
        MotionEvent.ACTION_MOVE -> StylusAction.MOVE
        MotionEvent.ACTION_UP -> StylusAction.UP
        MotionEvent.ACTION_POINTER_UP -> StylusAction.POINTER_UP
        MotionEvent.ACTION_CANCEL -> StylusAction.CANCEL
        MotionEvent.ACTION_HOVER_ENTER -> StylusAction.HOVER_ENTER
        MotionEvent.ACTION_HOVER_MOVE -> StylusAction.HOVER_MOVE
        MotionEvent.ACTION_HOVER_EXIT -> StylusAction.HOVER_EXIT
        else -> StylusAction.OTHER
    }

    val pointers = ArrayList<StylusPointerSnapshot>(pointerCount)
    for (i in 0 until pointerCount) {
        val toolType = getToolType(i)
        val isStylusPointer =
            toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        pointers.add(
            StylusPointerSnapshot(
                pointerId = getPointerId(i),
                toolType = toolType,
                xPx = getX(i),
                yPx = getY(i),
                pressure = getPressure(i),
                tiltRadians = if (isStylusPointer) getAxisValue(MotionEvent.AXIS_TILT, i) else 0f,
                orientationRadians = if (isStylusPointer) {
                    getAxisValue(MotionEvent.AXIS_ORIENTATION, i)
                } else {
                    0f
                }
            )
        )
    }

    val historySamples = if (trackedPointerId != null && historySize > 0) {
        val idx = findPointerIndex(trackedPointerId)
        if (idx >= 0) {
            ArrayList<RawScreenSample>(historySize).apply {
                for (h in 0 until historySize) {
                    val hTimeMs = getHistoricalEventTime(h)
                    add(
                        RawScreenSample(
                            xPx = getHistoricalX(idx, h),
                            yPx = getHistoricalY(idx, h),
                            eventTimeMs = hTimeMs,
                            eventTimeNanos = hTimeMs * 1_000_000L,
                            pressure = getHistoricalPressure(idx, h),
                            tiltRadians = getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
                            orientationRadians = getHistoricalAxisValue(
                                MotionEvent.AXIS_ORIENTATION,
                                idx,
                                h
                            )
                        )
                    )
                }
            }
        } else {
            emptyList()
        }
    } else {
        emptyList()
    }

    return StylusMotionFrame(
        action = mappedAction,
        actionIndex = actionIndex,
        flags = flags,
        buttonState = buttonState,
        eventTimeMs = eventTime,
        eventTimeNanos = eventTime * 1_000_000L,
        pointers = pointers,
        historyForActivePointer = historySamples,
        rawEvent = this
    )
}

/**
 * Type-safe Android `MotionEvent` entry points for [StylusInputRouter]<[MotionEvent]>.
 */
fun StylusInputRouter<MotionEvent>.onTouchEvent(event: MotionEvent): Boolean {
    // Record raw MotionEvent BEFORE any filtering when it involves a drawing pointer.
    val hasAcceptedTool = hasAcceptedToolForPrediction(event)
    if (hasAcceptedTool &&
        event.actionMasked != MotionEvent.ACTION_CANCEL &&
        (event.flags and MotionEvent.FLAG_CANCELED) == 0
    ) {
        predictorBridge?.record(event)
    }
    val frame = event.toStylusMotionFrame(activeStrokePointerId)
    return processTouchFrame(frame)
}

fun StylusInputRouter<MotionEvent>.onHoverEvent(event: MotionEvent): Boolean {
    val frame = event.toStylusMotionFrame(trackedPointerId = null)
    return processHoverFrame(frame)
}
