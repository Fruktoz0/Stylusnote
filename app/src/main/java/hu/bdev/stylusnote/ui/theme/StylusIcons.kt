package hu.bdev.stylusnote.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom StarNote-inspired minimalist stroke icon set (IMG_2724 / IMG_2725 / IMG_2727):
 * - Consistent 24x24 viewport, 1.75f stroke weight, rounded caps & joins.
 * - SolidColor(Color.Black) allows Material [androidx.compose.material3.Icon] tinting across themes.
 */
object StylusIcons {

    private val strokeBrush = SolidColor(Color.Black)

    val FountainPen: ImageVector by lazy {
        ImageVector.Builder(
            name = "FountainPen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Nib tip triangle & shoulders oriented bottom-left -> top-right
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 20.5f)
                lineTo(6.2f, 12.2f)
                lineTo(13.8f, 4.6f)
                lineTo(19.4f, 10.2f)
                lineTo(11.8f, 17.8f)
                close()
            }
            // Collar band
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12.2f, 6.2f)
                lineTo(17.8f, 11.8f)
                // Barrel extension
                moveTo(15.2f, 3.2f)
                lineTo(20.8f, 8.8f)
                // Nib split slit
                moveTo(3.5f, 20.5f)
                lineTo(9.8f, 14.2f)
            }
            // Breather hole dot
            path(fill = strokeBrush) {
                moveTo(11.2f, 12.8f)
                arcTo(1.2f, 1.2f, 0f, true, true, 8.8f, 12.8f)
                arcTo(1.2f, 1.2f, 0f, true, true, 11.2f, 12.8f)
            }
        }.build()
    }

    val BallpointPen: ImageVector by lazy {
        ImageVector.Builder(
            name = "BallpointPen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16.8f, 3.6f)
                lineTo(20.4f, 7.2f)
                lineTo(8.8f, 18.8f)
                lineTo(3.8f, 20.2f)
                lineTo(5.2f, 15.2f)
                close()
                // Tip seam line
                moveTo(5.6f, 14.8f)
                lineTo(9.2f, 18.4f)
                // Clip & cap ring
                moveTo(14.3f, 6.1f)
                lineTo(17.9f, 9.7f)
            }
        }.build()
    }

    val Pencil: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pencil",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17.5f, 3.5f)
                lineTo(20.5f, 6.5f)
                lineTo(8.5f, 18.5f)
                lineTo(3.5f, 20.5f)
                lineTo(5.5f, 15.5f)
                close()
                // Graphite lead tip mark
                moveTo(4.4f, 18.2f)
                lineTo(5.8f, 19.6f)
                // Fluted body centerline
                moveTo(7.0f, 17.0f)
                lineTo(19.0f, 5.0f)
            }
        }.build()
    }

    val Highlighter: ImageVector by lazy {
        ImageVector.Builder(
            name = "Highlighter",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Wide marker body
                moveTo(14.8f, 3.2f)
                lineTo(20.8f, 9.2f)
                lineTo(11.4f, 18.6f)
                lineTo(5.4f, 12.6f)
                close()
                // Chisel nib trap
                moveTo(5.6f, 12.8f)
                lineTo(3.4f, 17.6f)
                lineTo(6.6f, 20.2f)
                lineTo(11.2f, 18.4f)
                // Marker base underline swipe
                moveTo(3.5f, 20.5f)
                lineTo(12.5f, 20.5f)
            }
        }.build()
    }

    /**
     * Tilted rectangular block eraser outline replacing the legacy scissors icon.
     */
    val PrecisionEraser: ImageVector by lazy {
        ImageVector.Builder(
            name = "PrecisionEraser",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Tilted eraser block (approx -42 deg)
                moveTo(13.6f, 4.2f)
                lineTo(19.8f, 10.4f)
                lineTo(10.6f, 19.6f)
                lineTo(5.6f, 19.6f)
                lineTo(4.0f, 18.0f)
                lineTo(4.0f, 13.8f)
                close()
                // Mid-sleeve divider distinguishing top & tip
                moveTo(9.0f, 8.8f)
                lineTo(15.2f, 15.0f)
                // Clean baseline under the eraser corner
                moveTo(12.8f, 19.6f)
                lineTo(20.0f, 19.6f)
            }
        }.build()
    }

    /**
     * Whole-stroke eraser: block eraser plus dashed deleted segment cue.
     */
    val VectorEraser: ImageVector by lazy {
        ImageVector.Builder(
            name = "VectorEraser",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Smaller shifted eraser silhouette
                moveTo(12.8f, 3.8f)
                lineTo(18.6f, 9.6f)
                lineTo(10.2f, 18.0f)
                lineTo(5.4f, 18.0f)
                lineTo(3.8f, 16.4f)
                lineTo(3.8f, 12.8f)
                close()
                moveTo(8.3f, 8.3f)
                lineTo(14.1f, 14.1f)
                // Dashed erased path segments below
                moveTo(4.0f, 20.5f)
                lineTo(8.0f, 20.5f)
                moveTo(11.0f, 20.5f)
                lineTo(15.0f, 20.5f)
                moveTo(18.0f, 20.5f)
                lineTo(20.5f, 20.5f)
            }
        }.build()
    }

    /**
     * True dashed freeform loop lasso matching StarNote IMG_2724.
     */
    val Lasso: ImageVector by lazy {
        ImageVector.Builder(
            name = "Lasso",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = strokeBrush,
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Top arc dashes
                moveTo(6.8f, 5.8f)
                quadTo(11.8f, 3.0f, 16.8f, 5.6f)
                // Right arc dashes
                moveTo(19.2f, 8.2f)
                quadTo(21.0f, 11.8f, 18.2f, 14.8f)
                // Bottom arc dashes
                moveTo(15.4f, 16.5f)
                quadTo(10.5f, 18.0f, 6.2f, 15.8f)
                // Left arc dashes
                moveTo(4.0f, 13.4f)
                quadTo(2.8f, 10.0f, 4.6f, 7.6f)
                // Lasso knot loop & tail at bottom-left/bottom-center
                moveTo(8.0f, 17.0f)
                quadTo(8.5f, 20.5f, 6.0f, 21.4f)
            }
        }.build()
    }

    /**
     * Four-point sparkle/star emblem seen on StarNote notebook belly-bands (IMG_2726/IMG_2727).
     */
    val StarFourPoint: ImageVector by lazy {
        ImageVector.Builder(
            name = "StarFourPoint",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = strokeBrush,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 2f)
                quadTo(13.2f, 10.8f, 22f, 12f)
                quadTo(13.2f, 13.2f, 12f, 22f)
                quadTo(10.8f, 13.2f, 2f, 12f)
                quadTo(10.8f, 10.8f, 12f, 2f)
                close()
            }
        }.build()
    }
}
