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
 * Custom StarNote-inspired icon set (IMG_2724 / IMG_2725 / IMG_2727):
 * - Consistent 24x24 viewport with rounded caps & joins.
 * - Toolbar icons carry multi-tone StarNote colors (rendered via `Color.Unspecified`);
 *   [StarFourPoint], [BallpointPen], [Pencil], and [OcrText] preserve SolidColor(Color.Black) for tinting.
 */
object StylusIcons {

    private val strokeBrush = SolidColor(Color.Black)
    private val inkOutlineBrush = SolidColor(Color(0xFF334155))
    private val inkDarkBrush = SolidColor(Color(0xFF1E293B))
    private val sleeveFillBrush = SolidColor(Color(0xFFF1F5F9))
    private val fountainCollarBrush = SolidColor(Color(0xFF3B82F6))
    private val highlighterBodyBrush = SolidColor(Color(0xFFFDE68A))
    private val highlighterTipBrush = SolidColor(Color(0xFFF59E0B))
    private val highlighterSwipeBrush = SolidColor(Color(0xFFFBBF24))
    private val eraserCoralBrush = SolidColor(Color(0xFFE25C5C))
    private val eraserRuleBrush = SolidColor(Color(0xFF64748B))
    private val vectorSweepBrush = SolidColor(Color(0xFF0EA5E9))
    private val lassoPrimaryBrush = SolidColor(Color(0xFF0EA5E9))
    private val lassoAccentBrush = SolidColor(Color(0xFF2563EB))

    val FountainPen: ImageVector by lazy {
        ImageVector.Builder(
            name = "FountainPen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Nib metallic body & outline
            path(
                fill = sleeveFillBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.4f, 20.6f)
                lineTo(5.8f, 12.2f)
                lineTo(12.0f, 6.0f)
                lineTo(18.0f, 12.0f)
                lineTo(11.8f, 18.2f)
                close()
            }
            // Royal-blue StarNote collar & grip section
            path(
                fill = fountainCollarBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11.8f, 6.2f)
                lineTo(14.8f, 3.2f)
                lineTo(20.8f, 9.2f)
                lineTo(17.8f, 12.2f)
                close()
            }
            // Nib split slit
            path(
                fill = null,
                stroke = inkDarkBrush,
                strokeLineWidth = 1.45f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.4f, 20.6f)
                lineTo(9.8f, 14.2f)
            }
            // Breather hole dot
            path(fill = inkDarkBrush) {
                moveTo(11.4f, 12.9f)
                arcTo(1.2f, 1.2f, 0f, true, true, 9.0f, 12.9f)
                arcTo(1.2f, 1.2f, 0f, true, true, 11.4f, 12.9f)
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
            // Glowing amber underline swipe
            path(
                fill = null,
                stroke = highlighterSwipeBrush,
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(3.6f, 20.6f)
                lineTo(15.2f, 20.6f)
            }
            // Wide marker barrel
            path(
                fill = highlighterBodyBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14.8f, 3.2f)
                lineTo(20.8f, 9.2f)
                lineTo(11.4f, 18.6f)
                lineTo(5.4f, 12.6f)
                close()
            }
            // Amber chisel tip
            path(
                fill = highlighterTipBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5.6f, 12.8f)
                lineTo(3.4f, 17.6f)
                lineTo(6.6f, 20.2f)
                lineTo(11.2f, 18.4f)
                close()
            }
        }.build()
    }

    /**
     * StarNote two-tone coral & white block eraser for precision pixel/split erasing.
     */
    val PrecisionEraser: ImageVector by lazy {
        ImageVector.Builder(
            name = "PrecisionEraser",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Lower white paper sleeve
            path(
                fill = sleeveFillBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.0f, 13.8f)
                lineTo(8.9f, 8.9f)
                lineTo(15.0f, 15.0f)
                lineTo(10.5f, 19.5f)
                lineTo(5.6f, 19.5f)
                lineTo(4.0f, 17.9f)
                close()
            }
            // Coral red rubber eraser top
            path(
                fill = eraserCoralBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.9f, 8.9f)
                lineTo(13.6f, 4.2f)
                lineTo(19.8f, 10.4f)
                lineTo(15.0f, 15.0f)
                close()
            }
            // Clean precision baseline under the eraser
            path(
                fill = null,
                stroke = eraserRuleBrush,
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(13.0f, 19.7f)
                lineTo(20.0f, 19.7f)
            }
        }.build()
    }

    /**
     * Whole-stroke eraser: StarNote coral block eraser plus sky-blue dashed erased stroke cue.
     */
    val VectorEraser: ImageVector by lazy {
        ImageVector.Builder(
            name = "VectorEraser",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Lower paper sleeve
            path(
                fill = sleeveFillBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.8f, 12.6f)
                lineTo(8.3f, 8.1f)
                lineTo(14.2f, 14.0f)
                lineTo(10.2f, 18.0f)
                lineTo(5.4f, 18.0f)
                lineTo(3.8f, 16.4f)
                close()
            }
            // Coral rubber head
            path(
                fill = eraserCoralBrush,
                stroke = inkOutlineBrush,
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.3f, 8.1f)
                lineTo(12.8f, 3.6f)
                lineTo(18.7f, 9.5f)
                lineTo(14.2f, 14.0f)
                close()
            }
            // Sky-blue dashed vector stroke fragments below
            path(
                fill = null,
                stroke = vectorSweepBrush,
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4.0f, 20.6f)
                lineTo(8.0f, 20.6f)
                moveTo(11.0f, 20.6f)
                lineTo(15.0f, 20.6f)
                moveTo(18.0f, 20.6f)
                lineTo(20.4f, 20.6f)
            }
        }.build()
    }

    /**
     * True dashed freeform loop lasso in sky-blue/indigo matching StarNote IMG_2724.
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
                stroke = lassoPrimaryBrush,
                strokeLineWidth = 1.85f,
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
            }
            // Deep indigo lasso tail & knot dot
            path(
                fill = null,
                stroke = lassoAccentBrush,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(8.0f, 17.0f)
                quadTo(8.5f, 20.5f, 6.0f, 21.4f)
            }
            path(fill = lassoAccentBrush) {
                moveTo(9.4f, 16.7f)
                arcTo(1.5f, 1.5f, 0f, true, true, 6.4f, 16.7f)
                arcTo(1.5f, 1.5f, 0f, true, true, 9.4f, 16.7f)
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

    /**
     * StarNote 2x2 multi-colored geometric shapes palette icon (circle, diamond, triangle, square).
     */
    val ShapesTool: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShapesTool",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Top-left coral circle
            path(
                fill = SolidColor(Color(0xFFFEE2E2)),
                stroke = SolidColor(Color(0xFFEF4444)),
                strokeLineWidth = 1.55f
            ) {
                moveTo(10.8f, 7.4f)
                arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = false, 4.0f, 7.4f)
                arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = false, 10.8f, 7.4f)
                close()
            }
            // Top-right emerald diamond
            path(
                fill = SolidColor(Color(0xFFD1FAE5)),
                stroke = SolidColor(Color(0xFF10B981)),
                strokeLineWidth = 1.55f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16.6f, 3.8f)
                lineTo(20.2f, 7.4f)
                lineTo(16.6f, 11.0f)
                lineTo(13.0f, 7.4f)
                close()
            }
            // Bottom-left blue triangle
            path(
                fill = SolidColor(Color(0xFFDBEAFE)),
                stroke = SolidColor(Color(0xFF3B82F6)),
                strokeLineWidth = 1.55f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7.4f, 13.0f)
                lineTo(11.0f, 19.8f)
                lineTo(3.8f, 19.8f)
                close()
            }
            // Bottom-right amber square
            path(
                fill = SolidColor(Color(0xFFFEF3C7)),
                stroke = SolidColor(Color(0xFFF59E0B)),
                strokeLineWidth = 1.55f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(13.4f, 13.4f)
                lineTo(19.8f, 13.4f)
                lineTo(19.8f, 19.8f)
                lineTo(13.4f, 19.8f)
                close()
            }
        }.build()
    }

    /**
     * StarNote colored gallery photo-insert icon (framed landscape with sun & mountains).
     */
    val InsertImage: ImageVector by lazy {
        ImageVector.Builder(
            name = "InsertImage",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Card sky background
            path(
                fill = SolidColor(Color(0xFFF8FAFC))
            ) {
                moveTo(3.5f, 4.5f)
                lineTo(20.5f, 4.5f)
                lineTo(20.5f, 19.5f)
                lineTo(3.5f, 19.5f)
                close()
            }
            // Warm sun circle
            path(fill = SolidColor(Color(0xFFF59E0B))) {
                moveTo(10.0f, 9.0f)
                arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = true, isPositiveArc = false, 6.2f, 9.0f)
                arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = true, isPositiveArc = false, 10.0f, 9.0f)
                close()
            }
            // Distant emerald mountain
            path(fill = SolidColor(Color(0xFF10B981))) {
                moveTo(10.2f, 19.0f)
                lineTo(15.6f, 11.2f)
                lineTo(20.2f, 19.0f)
                close()
            }
            // Foreground teal mountain
            path(fill = SolidColor(Color(0xFF0D9488))) {
                moveTo(3.8f, 19.0f)
                lineTo(8.6f, 13.4f)
                lineTo(13.8f, 19.0f)
                close()
            }
            // Crisp slate border frame
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF475569)),
                strokeLineWidth = 1.65f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 4.5f)
                lineTo(20.5f, 4.5f)
                lineTo(20.5f, 19.5f)
                lineTo(3.5f, 19.5f)
                close()
            }
        }.build()
    }

    /**
     * Whole-notebook handwriting-to-text OCR icon (`A` + scan corner brackets).
     */
    val OcrText: ImageVector by lazy {
        ImageVector.Builder(
            name = "OcrText",
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
                // Corner viewfinder brackets
                moveTo(3.5f, 7.5f)
                lineTo(3.5f, 4f)
                lineTo(7f, 4f)
                moveTo(17f, 4f)
                lineTo(20.5f, 4f)
                lineTo(20.5f, 7.5f)
                moveTo(3.5f, 16.5f)
                lineTo(3.5f, 20f)
                lineTo(7f, 20f)
                moveTo(17f, 20f)
                lineTo(20.5f, 20f)
                lineTo(20.5f, 16.5f)
                // Central stylized 'A' glyph
                moveTo(8.5f, 16.5f)
                lineTo(12f, 7.5f)
                lineTo(15.5f, 16.5f)
                moveTo(9.7f, 13.6f)
                lineTo(14.3f, 13.6f)
            }
        }.build()
    }
}
