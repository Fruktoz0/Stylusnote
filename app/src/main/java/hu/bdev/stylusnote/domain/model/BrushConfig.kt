package hu.bdev.stylusnote.domain.model

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import hu.bdev.stylusnote.core.hardware.PressureCurve
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Tool modes exposed in the StylusNote toolbar and consumed by [BrushConfig] and
 * [hu.bdev.stylusnote.domain.repository.CanvasStateHolder].
 */
enum class BrushToolType(val displayName: String) {
    FOUNTAIN_PEN("Töltőtoll"),
    BALLPOINT_PEN("Golyóstoll"),
    PENCIL("Ceruza"),
    HIGHLIGHTER("Kiemelő"),
    LASER("Lézer"),
    VECTOR_ERASER("Vektoros radír"),
    PRECISION_ERASER("Precíziós radír"),
    LASSO("Lasszó");

    val isDrawingBrush: Boolean
        get() = when (this) {
            FOUNTAIN_PEN, BALLPOINT_PEN, PENCIL, HIGHLIGHTER, LASER -> true
            VECTOR_ERASER, PRECISION_ERASER, LASSO -> false
        }

    val isEraser: Boolean
        get() = this == VECTOR_ERASER || this == PRECISION_ERASER

    val isHighlighter: Boolean
        get() = this == HIGHLIGHTER

    val isLaser: Boolean
        get() = this == LASER
}

/**
 * Immutable brush and input-pipeline configuration for the active stylus tool.
 *
 * Always stores [colorArgb] in canonical light-mode color space so saved strokes and PDF
 * exports remain theme-independent; OLED smart luminance inversion happens at render time only.
 */
data class BrushConfig(
    val toolType: BrushToolType = BrushToolType.FOUNTAIN_PEN,
    val sizePt: Float = DEFAULT_BRUSH_SIZE_PT,
    val colorArgb: Int = DEFAULT_INK_COLOR_ARGB,
    val opacity: Float = 1.0f,
    val stabilizationPercent: Float = DEFAULT_STABILIZATION_PERCENT,
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    val holdToSnapEnabled: Boolean = true,
    val eraserRadiusDp: Float = DEFAULT_ERASER_RADIUS_DP
) {
    val clampedSizePt: Float
        get() = sizePt.coerceIn(MIN_BRUSH_SIZE_PT, MAX_BRUSH_SIZE_PT)

    val clampedStabilizationPercent: Float
        get() = stabilizationPercent.coerceIn(0f, 100f)

    /**
     * Effective stroke opacity. Highlighter enforces translucent 0.35 alpha so overlapping strokes
     * rely on [SelfOverlap.DISCARD] without darkening onto themselves.
     */
    fun effectiveOpacity(): Float = if (toolType == BrushToolType.HIGHLIGHTER) {
        HIGHLIGHTER_OPACITY
    } else {
        opacity.coerceIn(MIN_OPACITY, 1.0f)
    }

    /**
     * Returns [baseColorArgb] with its alpha channel scaled by [effectiveOpacity].
     */
    fun effectiveColorArgb(baseColorArgb: Int = colorArgb): Int {
        val baseAlpha = (baseColorArgb ushr 24) and 0xFF
        val effectiveAlpha = if (toolType == BrushToolType.HIGHLIGHTER) {
            (HIGHLIGHTER_OPACITY * 255f).roundToInt().coerceIn(0, 255)
        } else {
            val baseNorm = if (baseAlpha == 0) 255 else baseAlpha
            (baseNorm * effectiveOpacity()).roundToInt().coerceIn(1, 255)
        }
        return (effectiveAlpha shl 24) or (baseColorArgb and 0x00FFFFFF)
    }

    /**
     * Builds the Jetpack Ink [Brush] instance for the configured [toolType].
     * When [overrideColorArgb] is provided (e.g. OLED luminance-inverted display color),
     * its RGB channels are used with [effectiveOpacity] applied.
     */
    fun toInkBrush(
        overrideColorArgb: Int? = null,
        epsilon: Float = DEFAULT_BRUSH_EPSILON
    ): Brush {
        val family = resolveBrushFamily(toolType)
        val colorWithAlpha = effectiveColorArgb(overrideColorArgb ?: colorArgb)
        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = colorWithAlpha,
            size = clampedSizePt,
            epsilon = epsilon.coerceAtLeast(MIN_BRUSH_EPSILON)
        )
    }

    companion object {
        const val MIN_BRUSH_SIZE_PT: Float = 0.5f
        const val MAX_BRUSH_SIZE_PT: Float = 40.0f
        const val DEFAULT_BRUSH_SIZE_PT: Float = 2.5f
        const val DEFAULT_STABILIZATION_PERCENT: Float = 25f
        const val DEFAULT_ERASER_RADIUS_DP: Float = 18f
        const val HIGHLIGHTER_OPACITY: Float = 0.35f
        const val MIN_OPACITY: Float = 0.10f
        const val DEFAULT_BRUSH_EPSILON: Float = 0.05f
        private const val MIN_BRUSH_EPSILON: Float = 0.01f
        const val DEFAULT_INK_COLOR_ARGB: Int = 0xFF14171C.toInt()
        const val DEFAULT_HIGHLIGHTER_COLOR_ARGB: Int = 0xFFFFEB3B.toInt()
        const val DEFAULT_LASER_COLOR_ARGB: Int = 0xFFFF2D55.toInt()

        private val cachedPencilFamily: BrushFamily by lazy { buildTiltSensitivePencilFamily() }

        /**
         * Resolves the Jetpack Ink [BrushFamily] for [toolType].
         * Non-drawing modes (erasers / lasso) fall back to a neutral marker family.
         */
        fun resolveBrushFamily(toolType: BrushToolType): BrushFamily = when (toolType) {
            BrushToolType.FOUNTAIN_PEN -> StockBrushes.pressurePen()
            BrushToolType.BALLPOINT_PEN -> StockBrushes.marker()
            BrushToolType.PENCIL -> cachedPencilFamily
            BrushToolType.HIGHLIGHTER -> StockBrushes.highlighter(selfOverlap = SelfOverlap.DISCARD)
            BrushToolType.LASER -> StockBrushes.marker()
            BrushToolType.VECTOR_ERASER,
            BrushToolType.PRECISION_ERASER,
            BrushToolType.LASSO -> StockBrushes.marker()
        }

        /**
         * Programmatic Pencil [BrushFamily] driven by stylus tilt (`TILT_IN_RADIANS`) so tilting
         * the Focus Pen Pro widens the stroke for shading while pressure modulates graphite density.
         */
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private fun buildTiltSensitivePencilFamily(): BrushFamily {
            return try {
                val maxTiltRadians = (PI * 0.5).toFloat()
                val tiltWideningBehavior = BrushBehavior(
                    terminalNode = TargetNode(
                        target = TargetNode.Target.SIZE_MULTIPLIER,
                        targetModifierRangeStart = 1.0f,
                        targetModifierRangeEnd = 2.6f,
                        input = SourceNode(
                            source = SourceNode.Source.TILT_IN_RADIANS,
                            sourceValueRangeStart = 0.0f,
                            sourceValueRangeEnd = maxTiltRadians,
                            sourceOutOfRangeBehavior = OutOfRange.CLAMP
                        )
                    )
                )
                val pressureShadingBehavior = BrushBehavior(
                    terminalNode = TargetNode(
                        target = TargetNode.Target.OPACITY_MULTIPLIER,
                        targetModifierRangeStart = 0.35f,
                        targetModifierRangeEnd = 1.0f,
                        input = SourceNode(
                            source = SourceNode.Source.NORMALIZED_PRESSURE,
                            sourceValueRangeStart = 0.0f,
                            sourceValueRangeEnd = 1.0f,
                            sourceOutOfRangeBehavior = OutOfRange.CLAMP
                        )
                    )
                )
                BrushFamily(
                    coats = listOf(
                        BrushCoat(
                            tip = BrushTip(
                                scaleX = 1.0f,
                                scaleY = 1.0f,
                                cornerRounding = 0.85f,
                                behaviors = listOf(tiltWideningBehavior, pressureShadingBehavior)
                            )
                        )
                    )
                )
            } catch (_: Throwable) {
                StockBrushes.pressurePen()
            }
        }
    }
}

