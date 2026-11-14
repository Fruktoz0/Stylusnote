package hu.bdev.stylusnote.ui.canvas

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.domain.model.BrushConfig
import java.util.UUID

/**
 * Immutable presentation trail drawn on Layer 6 ([LaserOverlay]).
 * Never persisted to files or added to the Undo stack.
 */
data class LaserTrail(
    val id: String = UUID.randomUUID().toString(),
    val pointsDocPt: List<DocPoint>,
    val colorArgb: Int = BrushConfig.DEFAULT_LASER_COLOR_ARGB,
    val widthPt: Float = 4f,
    val finishedAtRealtimeMs: Long? = null
)

/**
 * Thread-safe ephemeral holder for active and fading laser pointer trails.
 * Finished trails decay linearly over [fadeDurationMs] (default 1500 ms) after `ACTION_UP`.
 */
class LaserTrailStateHolder(
    val fadeDurationMs: Long = LASER_FADE_DURATION_MS
) {
    private val lock = Any()
    private var activeTrail: MutableList<DocPoint>? = null
    private var activeColorArgb: Int = BrushConfig.DEFAULT_LASER_COLOR_ARGB
    private var activeWidthPt: Float = 4f
    private val fadingTrails = ArrayList<LaserTrail>()

    fun startTrail(firstPoint: DocPoint, colorArgb: Int, widthPt: Float) {
        synchronized(lock) {
            activeColorArgb = colorArgb
            activeWidthPt = widthPt.coerceAtLeast(1f)
            activeTrail = ArrayList<DocPoint>(64).apply { add(firstPoint) }
        }
    }

    fun appendPoints(points: List<DocPoint>) {
        if (points.isEmpty()) return
        synchronized(lock) {
            val trail = activeTrail ?: ArrayList<DocPoint>(points.size).also { activeTrail = it }
            trail.addAll(points)
        }
    }

    fun finishTrail(
        finalPoints: List<DocPoint> = emptyList(),
        nowRealtimeMs: Long = SystemClock.elapsedRealtime()
    ) {
        synchronized(lock) {
            val trail = activeTrail
            activeTrail = null
            if (trail != null) {
                trail.addAll(finalPoints)
                if (trail.isNotEmpty()) {
                    fadingTrails.add(
                        LaserTrail(
                            pointsDocPt = trail,
                            colorArgb = activeColorArgb,
                            widthPt = activeWidthPt,
                            finishedAtRealtimeMs = nowRealtimeMs
                        )
                    )
                }
            }
        }
    }

    fun cancelActiveTrail() {
        synchronized(lock) {
            activeTrail = null
        }
    }

    fun clearAll() {
        synchronized(lock) {
            activeTrail = null
            fadingTrails.clear()
        }
    }

    fun hasAnyTrails(): Boolean = synchronized(lock) {
        activeTrail != null || fadingTrails.isNotEmpty()
    }

    /**
     * Prunes expired fading trails (`age >= fadeDurationMs`) and returns a safe snapshot
     * of active + visible trails for the Compose draw pass.
     */
    fun snapshotAndPrune(nowRealtimeMs: Long = SystemClock.elapsedRealtime()): List<LaserTrail> {
        synchronized(lock) {
            if (fadingTrails.isNotEmpty()) {
                val iter = fadingTrails.iterator()
                while (iter.hasNext()) {
                    val item = iter.next()
                    val finishedAt = item.finishedAtRealtimeMs ?: continue
                    if (nowRealtimeMs - finishedAt >= fadeDurationMs) {
                        iter.remove()
                    }
                }
            }

            val result = ArrayList<LaserTrail>(fadingTrails.size + 1)
            result.addAll(fadingTrails)
            val current = activeTrail
            if (current != null && current.isNotEmpty()) {
                result.add(
                    LaserTrail(
                        id = "active-laser",
                        pointsDocPt = current.toList(),
                        colorArgb = activeColorArgb,
                        widthPt = activeWidthPt,
                        finishedAtRealtimeMs = null
                    )
                )
            }
            return result
        }
    }

    companion object {
        const val LASER_FADE_DURATION_MS: Long = 1500L
    }
}

/**
 * Layer 6 ephemeral presentation overlay rendered above dry/wet ink and below hover cursor & UI.
 * Fades completed trails out over 1500 ms and drops frame ticks completely when idle.
 */
@Composable
fun LaserOverlay(
    stateHolder: LaserTrailStateHolder,
    transformMatrix: CanvasTransformMatrix,
    activityTick: State<Long>,
    modifier: Modifier = Modifier
) {
    val animationTick = remember { mutableLongStateOf(0L) }
    val triggerValue = activityTick.value

    // Drive frame-accurate fade decay only while at least one trail is active or fading out.
    LaunchedEffect(triggerValue) {
        while (stateHolder.hasAnyTrails()) {
            withFrameNanos { frameNanos ->
                animationTick.longValue = frameNanos
            }
            if (!stateHolder.hasAnyTrails()) break
        }
    }

    val reusablePath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        activityTick.value
        animationTick.longValue

        val nowMs = SystemClock.elapsedRealtime()
        val visibleTrails = stateHolder.snapshotAndPrune(nowMs)
        if (visibleTrails.isEmpty()) return@Canvas

        for (i in 0 until visibleTrails.size) {
            val trail = visibleTrails[i]
            val fadeFactor = when (val finishedAt = trail.finishedAtRealtimeMs) {
                null -> 1.0f
                else -> (1f - ((nowMs - finishedAt).toFloat() / stateHolder.fadeDurationMs.toFloat()))
                    .coerceIn(0f, 1f)
            }
            if (fadeFactor <= 0.01f) continue

            val baseColor = Color(trail.colorArgb)
            val strokePx = transformMatrix.docSizeToScreenPx(trail.widthPt).coerceAtLeast(3f)

            if (trail.pointsDocPt.size == 1) {
                val pt = transformMatrix.docToScreen(trail.pointsDocPt[0].xPt, trail.pointsDocPt[0].yPt)
                drawCircle(
                    color = baseColor.copy(alpha = 0.28f * fadeFactor),
                    radius = strokePx * 1.4f,
                    center = androidx.compose.ui.geometry.Offset(pt.xPx, pt.yPx)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f * fadeFactor),
                    radius = strokePx * 0.45f,
                    center = androidx.compose.ui.geometry.Offset(pt.xPx, pt.yPx)
                )
                continue
            }

            reusablePath.reset()
            val first = transformMatrix.docToScreen(trail.pointsDocPt[0].xPt, trail.pointsDocPt[0].yPt)
            reusablePath.moveTo(first.xPx, first.yPx)
            for (p in 1 until trail.pointsDocPt.size) {
                val screen = transformMatrix.docToScreen(
                    trail.pointsDocPt[p].xPt,
                    trail.pointsDocPt[p].yPt
                )
                reusablePath.lineTo(screen.xPx, screen.yPx)
            }

            // Outer glowing halo
            drawPath(
                path = reusablePath,
                color = baseColor.copy(alpha = 0.30f * fadeFactor),
                style = Stroke(
                    width = strokePx * 2.4f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // Main laser body
            drawPath(
                path = reusablePath,
                color = baseColor.copy(alpha = 0.92f * fadeFactor),
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // Hot white center core
            drawPath(
                path = reusablePath,
                color = Color.White.copy(alpha = 0.85f * fadeFactor),
                style = Stroke(
                    width = (strokePx * 0.38f).coerceAtLeast(1.5f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

