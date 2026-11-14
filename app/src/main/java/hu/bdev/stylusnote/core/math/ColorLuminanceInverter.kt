package hu.bdev.stylusnote.core.math

import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin Smart Luminance Inversion for OLED Deep Black mode (Section 8).
 *
 * Converts an ARGB color into HSL, transforms `L_new = 1.0 - L_old` while preserving
 * Hue, Saturation, and Alpha, and converts back to ARGB. Canonical document files and
 * PDF exports always retain the original light-mode color.
 */
object ColorLuminanceInverter {

    fun invertArgbLuminance(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val hsl = rgbToHsl(r, g, b)
        hsl[2] = (1.0f - hsl[2]).coerceIn(0f, 1f)
        val (outR, outG, outB) = hslToRgb(hsl[0], hsl[1], hsl[2])

        val ri = (outR * 255f).roundToInt().coerceIn(0, 255)
        val gi = (outG * 255f).roundToInt().coerceIn(0, 255)
        val bi = (outB * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * Converts normalized RGB `[0..1]` into HSL `[hueDeg 0..360, sat 0..1, light 0..1]`.
     */
    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val rc = r.coerceIn(0f, 1f)
        val gc = g.coerceIn(0f, 1f)
        val bc = b.coerceIn(0f, 1f)

        val maxVal = max(rc, max(gc, bc))
        val minVal = min(rc, min(gc, bc))
        val delta = maxVal - minVal
        val l = (maxVal + minVal) * 0.5f

        if (delta <= 1e-6f) {
            return floatArrayOf(0f, 0f, l)
        }

        val s = if (l > 0.5f) {
            delta / (2f - maxVal - minVal)
        } else {
            delta / (maxVal + minVal)
        }.coerceIn(0f, 1f)

        var h = when (maxVal) {
            rc -> ((gc - bc) / delta) + (if (gc < bc) 6f else 0f)
            gc -> ((bc - rc) / delta) + 2f
            else -> ((rc - gc) / delta) + 4f
        } * 60f

        if (h < 0f) h += 360f
        if (h >= 360f) h -= 360f
        return floatArrayOf(h, s, l)
    }

    /**
     * Converts HSL `[hueDeg 0..360, sat 0..1, light 0..1]` back into normalized RGB Triple `[0..1]`.
     */
    fun hslToRgb(hueDeg: Float, saturation: Float, lightness: Float): Triple<Float, Float, Float> {
        val hNorm = (((hueDeg % 360f) + 360f) % 360f) / 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)

        if (s <= 1e-6f) {
            return Triple(l, l, l)
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        val r = hueToChannel(p, q, hNorm + 1f / 3f)
        val g = hueToChannel(p, q, hNorm)
        val b = hueToChannel(p, q, hNorm - 1f / 3f)
        return Triple(r, g, b)
    }

    private fun hueToChannel(p: Float, q: Float, tRaw: Float): Float {
        var t = tRaw
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }.coerceIn(0f, 1f)
    }
}

/**
 * Render-time cache producing inverted-luminance [Stroke] copies for [DryInkRasterCache]
 * whenever OLED Deep Black theme is active. Original light-mode strokes remain untouched.
 */
class OledStrokeRenderCache {
    private data class CachedEntry(
        val sourceStroke: Stroke,
        val sourceColorArgb: Int,
        val invertedStroke: Stroke
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    fun adaptStroke(entry: IndexedDryStroke, isOledMode: Boolean): Stroke {
        if (!isOledMode) return entry.stroke
        val sourceColor = entry.stroke.brush.colorIntArgb
        val existing = cache[entry.id]
        if (existing != null &&
            existing.sourceStroke === entry.stroke &&
            existing.sourceColorArgb == sourceColor
        ) {
            return existing.invertedStroke
        }

        val invertedColor = ColorLuminanceInverter.invertArgbLuminance(sourceColor)
        val invertedBrush = entry.stroke.brush.copyWithColorIntArgb(invertedColor)
        val invertedStroke = entry.stroke.copy(brush = invertedBrush)
        cache[entry.id] = CachedEntry(
            sourceStroke = entry.stroke,
            sourceColorArgb = sourceColor,
            invertedStroke = invertedStroke
        )
        return invertedStroke
    }

    fun invalidate(strokeId: String) {
        cache.remove(strokeId)
    }

    fun clear() {
        cache.clear()
    }
}

