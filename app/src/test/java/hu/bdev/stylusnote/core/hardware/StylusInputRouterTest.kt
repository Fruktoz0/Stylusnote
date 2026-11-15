package hu.bdev.stylusnote.core.hardware

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.OneEuroFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StylusInputRouterTest {

    private class FakeEvent(val label: String) {
        var recycled: Boolean = false
    }

    private class FakePredictorBridge : MotionPredictorBridge<FakeEvent> {
        val recorded = ArrayList<FakeEvent>()
        val createdPredictions = ArrayList<FakeEvent>()
        var nextPredictedSamples: List<RawScreenSample> = emptyList()

        override fun record(event: FakeEvent) {
            recorded.add(event)
        }

        override fun predict(): FakeEvent? {
            if (nextPredictedSamples.isEmpty()) return null
            return FakeEvent("pred-${createdPredictions.size}").also { createdPredictions.add(it) }
        }

        override fun extractPredictedSamples(
            predictedEvent: FakeEvent,
            pointerId: Int
        ): List<RawScreenSample> = nextPredictedSamples

        override fun recycle(predictedEvent: FakeEvent) {
            predictedEvent.recycled = true
        }
    }

    private class FakeWetStrokeSink : WetStrokeSink<FakeEvent> {
        var rawStartCount = 0
        var rawMoveCount = 0
        var rawFinishCount = 0
        var batchStartSamples = ArrayList<StylusPointSample>()
        var batchRealMoves = ArrayList<StylusPointSample>()
        var batchPredictedMoves = ArrayList<StylusPointSample>()
        var batchFinishCount = 0
        var cancelCount = 0
        var throwOnMove = false

        override fun onStartRawStroke(event: FakeEvent, pointerId: Int, isEraserTool: Boolean) {
            rawStartCount++
        }

        override fun onAddToRawStroke(realEvent: FakeEvent, predictedEvent: FakeEvent?, pointerId: Int) {
            rawMoveCount++
            if (throwOnMove) throw IllegalStateException("Simulated downstream failure")
        }

        override fun onFinishRawStroke(event: FakeEvent, pointerId: Int) {
            rawFinishCount++
        }

        override fun onStartBatchStroke(firstSample: StylusPointSample, toolType: InputToolType) {
            batchStartSamples.add(firstSample)
        }

        override fun onAddBatchToStroke(
            realSamples: List<StylusPointSample>,
            predictedSamples: List<StylusPointSample>,
            toolType: InputToolType
        ) {
            batchRealMoves.addAll(realSamples)
            batchPredictedMoves.addAll(predictedSamples)
            if (throwOnMove) throw IllegalStateException("Simulated downstream failure")
        }

        override fun onFinishBatchStroke(finalSamples: List<StylusPointSample>, toolType: InputToolType) {
            batchFinishCount++
        }

        override fun onCancelActiveStroke() {
            cancelCount++
        }
    }

    @Test
    fun pressureCurvesApplyGammaAndClampInputs() {
        assertEquals(0f, PressureCurve.SOFT.apply(-0.5f), 1e-6f)
        assertEquals(1f, PressureCurve.HARD.apply(1.5f), 1e-6f)
        assertEquals(0f, PressureCurve.LINEAR.apply(Float.NaN), 1e-6f)

        val mid = 0.5f
        assertTrue(PressureCurve.SOFT.apply(mid) > PressureCurve.LINEAR.apply(mid))
        assertTrue(PressureCurve.LINEAR.apply(mid) > PressureCurve.HARD.apply(mid))
        assertEquals(0.5f, PressureCurve.LINEAR.apply(mid), 1e-6f)
    }

    @Test
    fun palmRejectionIgnoresSingleFingerUnlessFingerDrawingEnabled() {
        val transform = CanvasTransformMatrix()
        val filter = OneEuroFilter(0f)
        val sink = FakeWetStrokeSink()
        val router = StylusInputRouter(transform, filter, sink)

        val fingerDown = StylusMotionFrame(
            action = StylusAction.DOWN,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 100L,
            eventTimeNanos = 100_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 50f, 50f, 0.5f, 0f, 0f)
            ),
            rawEvent = FakeEvent("finger-down")
        )

        assertFalse(router.processTouchFrame(fingerDown))
        assertEquals(0, sink.rawStartCount)
        assertFalse(router.isStrokeActive)

        router.allowFingerDrawing = true
        assertTrue(router.processTouchFrame(fingerDown))
        assertEquals(1, sink.rawStartCount)
        assertTrue(router.isStrokeActive)
        assertFalse(router.isStylusStrokeActive)
    }

    @Test
    fun actionCancelAndFlagCanceledImmediatelyCancelStroke() {
        val routerA = StylusInputRouter(CanvasTransformMatrix(), OneEuroFilter(0f), FakeWetStrokeSink())
        val sinkA = FakeWetStrokeSink()
        val router = StylusInputRouter(CanvasTransformMatrix(), OneEuroFilter(0f), sinkA)

        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 10L,
                eventTimeNanos = 10_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(7, MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, 0.8f, 0f, 0f)
                ),
                rawEvent = FakeEvent("stylus-down")
            )
        )
        assertTrue(router.isStylusStrokeActive)

        // Cancel via FLAG_CANCELED on MOVE (palm rejection flag from Android framework)
        assertTrue(
            router.processTouchFrame(
                StylusMotionFrame(
                    action = StylusAction.MOVE,
                    actionIndex = 0,
                    flags = MotionEvent.FLAG_CANCELED,
                    buttonState = 0,
                    eventTimeMs = 14L,
                    eventTimeNanos = 14_000_000L,
                    pointers = listOf(
                        StylusPointerSnapshot(7, MotionEvent.TOOL_TYPE_STYLUS, 110f, 110f, 0.8f, 0f, 0f)
                    ),
                    rawEvent = FakeEvent("stylus-flag-cancel")
                )
            )
        )
        assertEquals(1, sinkA.cancelCount)
        assertFalse(router.isStrokeActive)
        assertFalse(routerA.isStrokeActive)
    }

    @Test
    fun activeStylusStrokeBlocksTwoFingerPanAndZoom() {
        val transform = CanvasTransformMatrix(initialScale = 1.0f, initialPanXPx = 0f, initialPanYPx = 0f)
        val sink = FakeWetStrokeSink()
        val router = StylusInputRouter(transform, OneEuroFilter(0f), sink)

        // Two-finger pinch works while idle
        val twoFingerFrame1 = StylusMotionFrame<FakeEvent>(
            action = StylusAction.MOVE,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 10L,
            eventTimeNanos = 10_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 100f, 100f, 1f, 0f, 0f),
                StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 200f, 100f, 1f, 0f, 0f)
            )
        )
        val twoFingerFrame2 = StylusMotionFrame<FakeEvent>(
            action = StylusAction.MOVE,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 14L,
            eventTimeNanos = 14_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 50f, 100f, 1f, 0f, 0f),
                StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 250f, 100f, 1f, 0f, 0f)
            )
        )
        assertTrue(router.processTouchFrame(twoFingerFrame1))
        assertTrue(router.processTouchFrame(twoFingerFrame2))
        assertEquals(2.0f, transform.scale, 1e-4f)

        // Start stylus stroke -> further finger events must NOT change transform
        val scaleBeforeStylus = transform.scale
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 20L,
                eventTimeNanos = 20_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(9, MotionEvent.TOOL_TYPE_STYLUS, 300f, 300f, 0.5f, 0f, 0f)
                ),
                rawEvent = FakeEvent("down")
            )
        )
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.MOVE,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 24L,
                eventTimeNanos = 24_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(9, MotionEvent.TOOL_TYPE_STYLUS, 310f, 300f, 0.5f, 0f, 0f),
                    StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 20f, 100f, 1f, 0f, 0f),
                    StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 400f, 100f, 1f, 0f, 0f)
                ),
                rawEvent = FakeEvent("move-with-fingers")
            )
        )
        assertEquals(scaleBeforeStylus, transform.scale, 1e-6f)
    }

    @Test
    fun stabilizedRouteSmoothsRealHistoryAndLeavesPredictionUnfilteredWhileAlwaysRecycling() {
        val transform = CanvasTransformMatrix(initialScale = 1.0f)
        val filter = OneEuroFilter(initialStabilizationPercent = 80f)
        val sink = FakeWetStrokeSink()
        val predictor = FakePredictorBridge()
        val router = StylusInputRouter(transform, filter, sink, predictor)

        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 100L,
                eventTimeNanos = 100_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(3, MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, 0.5f, 0.2f, 0.5f)
                ),
                rawEvent = FakeEvent("down")
            )
        )

        predictor.nextPredictedSamples = listOf(
            RawScreenSample(
                xPx = 150f,
                yPx = 160f,
                eventTimeMs = 112L,
                eventTimeNanos = 112_000_000L,
                pressure = 0.5f,
                tiltRadians = 0.2f,
                orientationRadians = 0.5f
            )
        )

        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.MOVE,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 108L,
                eventTimeNanos = 108_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(3, MotionEvent.TOOL_TYPE_STYLUS, 112f, 118f, 0.5f, 0.2f, 0.5f)
                ),
                historyForActivePointer = listOf(
                    RawScreenSample(106f, 109f, 104L, 104_000_000L, 0.5f, 0.2f, 0.5f)
                ),
                rawEvent = FakeEvent("move")
            )
        )

        // Real historical + current sample were smoothed (not equal to raw jump 112, 118)
        assertEquals(2, sink.batchRealMoves.size)
        val lastReal = sink.batchRealMoves.last()
        assertTrue(abs(lastReal.xPt - 112f) > 0.1f)

        // Predicted point bypassed OneEuroFilter (exact document coordinate 150, 160)
        assertEquals(1, sink.batchPredictedMoves.size)
        assertEquals(150f, sink.batchPredictedMoves.first().xPt, 1e-4f)
        assertEquals(160f, sink.batchPredictedMoves.first().yPt, 1e-4f)
        assertTrue(predictor.createdPredictions.single().recycled)

        // Even if sink throws, predicted MotionEvent is still recycled via finally
        sink.throwOnMove = true
        var threw = false
        try {
            router.processTouchFrame(
                StylusMotionFrame(
                    action = StylusAction.MOVE,
                    actionIndex = 0,
                    flags = 0,
                    buttonState = 0,
                    eventTimeMs = 116L,
                    eventTimeNanos = 116_000_000L,
                    pointers = listOf(
                        StylusPointerSnapshot(3, MotionEvent.TOOL_TYPE_STYLUS, 120f, 125f, 0.5f, 0.2f, 0.5f)
                    ),
                    rawEvent = FakeEvent("move-fail")
                )
            )
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(predictor.createdPredictions.all { it.recycled })
    }

    @Test
    fun focusPenButtonKeyAndHoverEventsTriggerToggleAndCursorState() {
        var toggleCount = 0
        var lastHover = HoverCursorState()
        val router = StylusInputRouter<FakeEvent>(
            transformMatrix = CanvasTransformMatrix(initialScale = 2.0f),
            oneEuroFilter = OneEuroFilter(0f),
            wetStrokeSink = FakeWetStrokeSink(),
            onHoverStateChanged = { lastHover = it },
            onTogglePenEraser = { toggleCount++ }
        )

        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, repeatCount = 0))
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, repeatCount = 1))
        assertEquals(1, toggleCount)

        router.processHoverFrame(
            StylusMotionFrame(
                action = StylusAction.HOVER_MOVE,
                actionIndex = 0,
                flags = 0,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
                eventTimeMs = 50L,
                eventTimeNanos = 50_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_STYLUS, 200f, 400f, 0f, 0.3f, 1.1f)
                )
            )
        )
        assertEquals(2, toggleCount)
        assertFalse(lastHover.isVisible)
        assertEquals(100f, lastHover.docXPt, 1e-4f)
        assertEquals(200f, lastHover.docYPt, 1e-4f)

        router.processHoverFrame(
            StylusMotionFrame(
                action = StylusAction.HOVER_EXIT,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 60L,
                eventTimeNanos = 60_000_000L,
                pointers = emptyList()
            )
        )
        assertFalse(lastHover.isVisible)
    }

    @Test
    fun twoFingerPinchZoomAndPanUpdatesTransformWhenFingerDrawingDisabled() {
        val transform = CanvasTransformMatrix(initialScale = 1.0f)
        val sink = FakeWetStrokeSink()
        var transformChangedCount = 0
        val router = StylusInputRouter(
            transformMatrix = transform,
            oneEuroFilter = OneEuroFilter(0f),
            wetStrokeSink = sink,
            onTransformChanged = { transformChangedCount++ }
        )

        val finger0Down = StylusMotionFrame(
            action = StylusAction.DOWN,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 10L,
            eventTimeNanos = 10_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 100f, 100f, 1f, 0f, 0f)
            ),
            rawEvent = FakeEvent("f0-down")
        )
        assertFalse(router.processTouchFrame(finger0Down))

        val finger1Down = StylusMotionFrame(
            action = StylusAction.POINTER_DOWN,
            actionIndex = 1,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 20L,
            eventTimeNanos = 20_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 100f, 100f, 1f, 0f, 0f),
                StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 200f, 100f, 1f, 0f, 0f)
            ),
            rawEvent = FakeEvent("f1-down")
        )
        assertTrue(router.processTouchFrame(finger1Down))

        val pinchZoomAndPanMove = StylusMotionFrame(
            action = StylusAction.MOVE,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 30L,
            eventTimeNanos = 30_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 75f, 130f, 1f, 0f, 0f),
                StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_FINGER, 275f, 130f, 1f, 0f, 0f)
            ),
            rawEvent = FakeEvent("pinch-move")
        )
        assertTrue(router.processTouchFrame(pinchZoomAndPanMove))
        assertEquals(0, sink.rawStartCount)
        assertTrue(transformChangedCount >= 1)
        assertEquals(2.0f, transform.scale, 1e-3f)
        val mapped = transform.docToScreen(150f, 100f)
        assertEquals(175f, mapped.xPx, 1e-2f)
        assertEquals(130f, mapped.yPx, 1e-2f)

        val finger1Up = StylusMotionFrame(
            action = StylusAction.POINTER_UP,
            actionIndex = 1,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 40L,
            eventTimeNanos = 40_000_000L,
            pointers = listOf(
                StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 75f, 130f, 1f, 0f, 0f)
            ),
            rawEvent = FakeEvent("f1-up")
        )
        assertTrue(router.processTouchFrame(finger1Up))
        assertFalse(router.isStrokeActive)
    }

    @Test
    fun singleFingerPansWithoutZoomingWhenFingerDrawingDisabled() {
        val transform = CanvasTransformMatrix(initialScale = 2.0f, initialPanXPx = 10f, initialPanYPx = 20f)
        val sink = FakeWetStrokeSink()
        var transformChanges = 0
        var gestureEndedCount = 0
        val router = StylusInputRouter(
            transformMatrix = transform,
            oneEuroFilter = OneEuroFilter(0f),
            wetStrokeSink = sink,
            onTransformChanged = { transformChanges++ },
            onNavigationGestureEnd = { gestureEndedCount++ }
        )

        val down = StylusMotionFrame(
            action = StylusAction.DOWN,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 10L,
            eventTimeNanos = 10_000_000L,
            pointers = listOf(StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 200f, 300f, 1f, 0f, 0f)),
            rawEvent = FakeEvent("down")
        )
        assertFalse(router.processTouchFrame(down))

        val move = StylusMotionFrame(
            action = StylusAction.MOVE,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 18L,
            eventTimeNanos = 18_000_000L,
            pointers = listOf(StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 260f, 220f, 1f, 0f, 0f)),
            rawEvent = FakeEvent("move")
        )
        assertTrue(router.processTouchFrame(move))
        assertEquals(0, sink.rawStartCount)
        assertEquals(1, transformChanges)
        assertEquals(2.0f, transform.scale, 1e-6f)
        // Single-finger pan must remain strictly vertical (panXPx unchanged, only panYPx moves).
        assertEquals(10f, transform.panXPx, 1e-4f)
        assertEquals(-60f, transform.panYPx, 1e-4f)

        val up = StylusMotionFrame(
            action = StylusAction.UP,
            actionIndex = 0,
            flags = 0,
            buttonState = 0,
            eventTimeMs = 26L,
            eventTimeNanos = 26_000_000L,
            pointers = listOf(StylusPointerSnapshot(0, MotionEvent.TOOL_TYPE_FINGER, 260f, 220f, 1f, 0f, 0f)),
            rawEvent = FakeEvent("up")
        )
        assertTrue(router.processTouchFrame(up))
        assertEquals(1, gestureEndedCount)
    }

    @Test
    fun palmTouchDuringHoverGraceWindowOrLargeContactDoesNotPanCanvas() {
        val transform = CanvasTransformMatrix(initialScale = 1.0f, initialPanXPx = 0f, initialPanYPx = 0f)
        val sink = FakeWetStrokeSink()
        val router = StylusInputRouter(transform, OneEuroFilter(0f), sink)

        // 1. Stylus lifts from paper at t = 1000ms -> starts 650ms palm grace window
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 950L,
                eventTimeNanos = 950_000_000L,
                pointers = listOf(StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_STYLUS, 300f, 300f, 0.5f, 0f, 0f)),
                rawEvent = FakeEvent("s-down")
            )
        )
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.UP,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 1000L,
                eventTimeNanos = 1_000_000_000L,
                pointers = listOf(StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_STYLUS, 310f, 300f, 0f, 0f, 0f)),
                rawEvent = FakeEvent("s-up")
            )
        )

        // 2. Hand rests / shifts vertically at t = 1200ms (inside 650ms grace window) -> must NOT pan
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 1200L,
                eventTimeNanos = 1_200_000_000L,
                pointers = listOf(StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 400f, 500f, 1f, 0f, 0f)),
                rawEvent = FakeEvent("palm-down")
            )
        )
        assertFalse(
            router.processTouchFrame(
                StylusMotionFrame(
                    action = StylusAction.MOVE,
                    actionIndex = 0,
                    flags = 0,
                    buttonState = 0,
                    eventTimeMs = 1250L,
                    eventTimeNanos = 1_250_000_000L,
                    pointers = listOf(StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 400f, 400f, 1f, 0f, 0f)),
                    rawEvent = FakeEvent("palm-move")
                )
            )
        )
        assertEquals(0f, transform.panYPx, 1e-4f)
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.UP,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 1280L,
                eventTimeNanos = 1_280_000_000L,
                pointers = listOf(StylusPointerSnapshot(2, MotionEvent.TOOL_TYPE_FINGER, 400f, 400f, 1f, 0f, 0f)),
                rawEvent = FakeEvent("palm-up")
            )
        )

        // 3. After grace window expires (t = 2000ms), an oversized palm patch still must NOT pan
        router.processTouchFrame(
            StylusMotionFrame(
                action = StylusAction.DOWN,
                actionIndex = 0,
                flags = 0,
                buttonState = 0,
                eventTimeMs = 2000L,
                eventTimeNanos = 2_000_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(3, MotionEvent.TOOL_TYPE_FINGER, 400f, 500f, 1f, 0f, 0f, touchMajorPx = 140f)
                ),
                rawEvent = FakeEvent("big-palm-down")
            )
        )
        assertFalse(
            router.processTouchFrame(
                StylusMotionFrame(
                    action = StylusAction.MOVE,
                    actionIndex = 0,
                    flags = 0,
                    buttonState = 0,
                    eventTimeMs = 2020L,
                    eventTimeNanos = 2_020_000_000L,
                    pointers = listOf(
                        StylusPointerSnapshot(3, MotionEvent.TOOL_TYPE_FINGER, 400f, 420f, 1f, 0f, 0f, touchMajorPx = 140f)
                    ),
                    rawEvent = FakeEvent("big-palm-move")
                )
            )
        )
        assertEquals(0f, transform.panYPx, 1e-4f)
    }
}

