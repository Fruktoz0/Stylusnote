package hu.bdev.stylusnote.data.local

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import hu.bdev.stylusnote.core.code.CodeLanguage
import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.ClozeTapePattern
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Pure binary encoder/decoder (no JSON) for strokes, geometric [ShapeMeta], affine deltas,
 * and Layer-5 [PageObject] entities carried inside `SNOT` files (Section 2 & 9.2).
 *
 * Uses Jetpack Ink's `ink-storage` gzip-protobuf serializer (`StrokeInputBatch.encode/decode` and
 * `BrushFamily.encode/decode`) when running on Android, while also storing compact binary
 * [StylusPointSample] tuples and guarding native calls so JVM unit tests run cleanly without JNI.
 */
object StrokeBinarySerializer {

    private const val OBJECT_TAG_LATEX: Int = 1
    private const val OBJECT_TAG_FUNCTION_PLOT: Int = 2
    private const val OBJECT_TAG_DERIVATION_CARD: Int = 3
    private const val OBJECT_TAG_PDF_PAGE_REF: Int = 4
    private const val OBJECT_TAG_FORMATTED_TEXT: Int = 5
    private const val OBJECT_TAG_GEOMETRIC_SHAPE: Int = 6
    private const val OBJECT_TAG_IMAGE: Int = 7
    private const val OBJECT_TAG_CODE_BLOCK: Int = 8
    private const val OBJECT_TAG_CLOZE_TAPE: Int = 9
    private const val OBJECT_TAG_TABLE: Int = 10

    fun encodeStrokeRecord(record: PersistedStrokeRecord): ByteArray {
        val baos = ByteArrayOutputStream(256)
        val out = DataOutputStream(baos)

        writeIdentifier(out, record.id)
        out.writeByte(record.layer.code)
        out.writeLong(record.zOrder)

        out.writeInt(FormatMigrator.toStableToolCode(record.brushConfig.toolType))
        out.writeFloat(record.brushConfig.sizePt)
        out.writeFloat(record.brushConfig.opacity)
        out.writeFloat(record.brushConfig.stabilizationPercent)
        out.writeByte(record.brushConfig.pressureCurve.ordinal)
        out.writeBoolean(record.brushConfig.holdToSnapEnabled)
        out.writeFloat(record.brushConfig.eraserRadiusDp)

        out.writeInt(record.canonicalColorArgb)
        out.writeFloat(record.sizePt)

        writeOptionalByteArray(out, record.inkEncodedInputs)
        writeOptionalByteArray(out, record.serializedBrushFamily)

        out.writeInt(record.samples.size)
        for (i in 0 until record.samples.size) {
            val s = record.samples[i]
            out.writeFloat(s.xPt)
            out.writeFloat(s.yPt)
            out.writeLong(s.elapsedTimeMillis)
            out.writeFloat(s.pressure)
            out.writeFloat(s.tiltRadians)
            out.writeFloat(s.orientationRadians)
            out.writeBoolean(s.isEraserTool)
        }

        out.writeFloat(record.bounds.leftPt)
        out.writeFloat(record.bounds.topPt)
        out.writeFloat(record.bounds.rightPt)
        out.writeFloat(record.bounds.bottomPt)

        val meta = record.shapeMeta
        if (meta == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeByte(meta.shapeType.ordinal)
            out.writeFloat(meta.rotationRadians)
            out.writeInt(meta.controlPoints.size)
            for (pt in meta.controlPoints) {
                out.writeFloat(pt.xPt)
                out.writeFloat(pt.yPt)
            }
        }

        val attachedObjectId = record.attachedObjectId
        if (attachedObjectId == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            writeIdentifier(out, attachedObjectId)
        }

        out.flush()
        return baos.toByteArray()
    }

    fun decodeStrokeRecord(payload: ByteArray): PersistedStrokeRecord {
        val input = DataInputStream(ByteArrayInputStream(payload))

        val id = readIdentifier(input)
        val layerCode = input.readByte().toInt() and 0xFF
        val layer = StrokeLayerType.fromCode(layerCode) ?: StrokeLayerType.INK
        val zOrder = input.readLong()

        val rawToolCode = input.readInt()
        val (toolType, fallbackFromToolCode) = FormatMigrator.resolveBrushToolWithFallback(rawToolCode)
        val cfgSizePt = input.readFloat()
        val cfgOpacity = input.readFloat()
        val cfgStab = input.readFloat()
        val curveOrdinal = input.readByte().toInt() and 0xFF
        val pressureCurve = PressureCurve.entries.getOrElse(curveOrdinal) { PressureCurve.LINEAR }
        val holdToSnap = input.readBoolean()
        val eraserRadiusDp = input.readFloat()

        val canonicalColorArgb = input.readInt()
        val sizePt = input.readFloat()

        val inkEncodedInputs = readOptionalByteArray(input)
        val serializedBrushFamily = readOptionalByteArray(input)

        val sampleCount = input.readInt().coerceAtLeast(0)
        val samples = ArrayList<StylusPointSample>(sampleCount)
        for (i in 0 until sampleCount) {
            samples.add(
                StylusPointSample(
                    xPt = input.readFloat(),
                    yPt = input.readFloat(),
                    elapsedTimeMillis = input.readLong(),
                    pressure = input.readFloat(),
                    tiltRadians = input.readFloat(),
                    orientationRadians = input.readFloat(),
                    isEraserTool = input.readBoolean()
                )
            )
        }

        val bounds = DocRect(
            leftPt = input.readFloat(),
            topPt = input.readFloat(),
            rightPt = input.readFloat(),
            bottomPt = input.readFloat()
        )

        val hasShapeMeta = input.readBoolean()
        val shapeMeta = if (!hasShapeMeta) {
            null
        } else {
            val shapeOrdinal = input.readByte().toInt() and 0xFF
            val shapeType = RecognizedShapeType.entries.getOrElse(shapeOrdinal) {
                RecognizedShapeType.LINE
            }
            val rotation = input.readFloat()
            val ptCount = input.readInt().coerceAtLeast(0)
            val ctrlPts = ArrayList<DocPoint>(ptCount)
            for (i in 0 until ptCount) {
                ctrlPts.add(DocPoint(input.readFloat(), input.readFloat()))
            }
            ShapeMeta(
                shapeType = shapeType,
                controlPoints = ctrlPts,
                rotationRadians = rotation
            )
        }

        val brushConfig = BrushConfig(
            toolType = toolType,
            sizePt = cfgSizePt,
            colorArgb = canonicalColorArgb,
            opacity = cfgOpacity,
            stabilizationPercent = cfgStab,
            pressureCurve = pressureCurve,
            holdToSnapEnabled = holdToSnap,
            eraserRadiusDp = eraserRadiusDp
        )

        val attachedObjectId = if (input.available() > 0 && input.readBoolean()) {
            readIdentifier(input)
        } else {
            null
        }

        return PersistedStrokeRecord(
            id = id,
            layer = layer,
            zOrder = zOrder,
            brushConfig = brushConfig,
            canonicalColorArgb = canonicalColorArgb,
            sizePt = sizePt,
            inkEncodedInputs = inkEncodedInputs,
            samples = samples,
            bounds = bounds,
            shapeMeta = shapeMeta,
            serializedBrushFamily = serializedBrushFamily,
            usedFallbackBrush = fallbackFromToolCode,
            attachedObjectId = attachedObjectId
        )
    }

    fun encodeTransformDelta(strokeId: String, affine: AffineTransform2D): ByteArray {
        val baos = ByteArrayOutputStream(64)
        val out = DataOutputStream(baos)
        writeIdentifier(out, strokeId)
        out.writeFloat(affine.scaleX)
        out.writeFloat(affine.shearX)
        out.writeFloat(affine.translateXPt)
        out.writeFloat(affine.shearY)
        out.writeFloat(affine.scaleY)
        out.writeFloat(affine.translateYPt)
        out.flush()
        return baos.toByteArray()
    }

    fun decodeTransformDelta(payload: ByteArray): Pair<String, AffineTransform2D> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val id = readIdentifier(input)
        val affine = AffineTransform2D(
            scaleX = input.readFloat(),
            shearX = input.readFloat(),
            translateXPt = input.readFloat(),
            shearY = input.readFloat(),
            scaleY = input.readFloat(),
            translateYPt = input.readFloat()
        )
        return id to affine
    }

    fun encodeRecolorDelta(strokeId: String, canonicalColorArgb: Int): ByteArray {
        val baos = ByteArrayOutputStream(32)
        val out = DataOutputStream(baos)
        writeIdentifier(out, strokeId)
        out.writeInt(canonicalColorArgb)
        out.flush()
        return baos.toByteArray()
    }

    fun decodeRecolorDelta(payload: ByteArray): Pair<String, Int> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val id = readIdentifier(input)
        val color = input.readInt()
        return id to color
    }

    fun encodeIdPayload(id: String): ByteArray {
        val baos = ByteArrayOutputStream(32)
        val out = DataOutputStream(baos)
        writeIdentifier(out, id)
        out.flush()
        return baos.toByteArray()
    }

    fun decodeIdPayload(payload: ByteArray): String {
        val input = DataInputStream(ByteArrayInputStream(payload))
        return readIdentifier(input)
    }

    fun encodePageObject(obj: PageObject): ByteArray {
        val baos = ByteArrayOutputStream(256)
        val out = DataOutputStream(baos)
        val tag = when (obj) {
            is PageObject.LatexObject -> OBJECT_TAG_LATEX
            is PageObject.FunctionPlotObject -> OBJECT_TAG_FUNCTION_PLOT
            is PageObject.DerivationCardObject -> OBJECT_TAG_DERIVATION_CARD
            is PageObject.PdfPageRefObject -> OBJECT_TAG_PDF_PAGE_REF
            is PageObject.FormattedTextObject -> OBJECT_TAG_FORMATTED_TEXT
            is PageObject.GeometricShapeObject -> OBJECT_TAG_GEOMETRIC_SHAPE
            is PageObject.ImageObject -> OBJECT_TAG_IMAGE
            is PageObject.CodeBlockObject -> OBJECT_TAG_CODE_BLOCK
            is PageObject.ClozeTapeObject -> OBJECT_TAG_CLOZE_TAPE
            is PageObject.TableObject -> OBJECT_TAG_TABLE
        }
        out.writeByte(tag)
        writeIdentifier(out, obj.id)
        out.writeLong(obj.zOrder)
        out.writeFloat(obj.bounds.leftPt)
        out.writeFloat(obj.bounds.topPt)
        out.writeFloat(obj.bounds.rightPt)
        out.writeFloat(obj.bounds.bottomPt)

        when (obj) {
            is PageObject.LatexObject -> {
                writeUtfString(out, obj.latexSource)
                out.writeFloat(obj.fontSizePt)
                out.writeInt(obj.canonicalColorArgb)
                out.writeFloat(obj.rotationRadians)
                out.writeInt(obj.sourceStrokeIds.size)
                for (srcId in obj.sourceStrokeIds) {
                    writeIdentifier(out, srcId)
                }
                writeEmbeddedStrokeList(out, obj.originalStrokes)
            }
            is PageObject.FunctionPlotObject -> {
                writeUtfString(out, obj.expression)
                out.writeFloat(obj.domainMinX)
                out.writeFloat(obj.domainMaxX)
                out.writeFloat(obj.rangeMinY)
                out.writeFloat(obj.rangeMaxY)
                out.writeInt(obj.canonicalColorArgb)
                out.writeFloat(obj.strokeWidthPt)
                out.writeBoolean(obj.showGrid)
            }
            is PageObject.DerivationCardObject -> {
                writeUtfString(out, obj.title)
                writeUtfString(out, obj.stepsMarkdownLatex)
                out.writeBoolean(obj.relatedLatexObjectId != null)
                if (obj.relatedLatexObjectId != null) {
                    writeIdentifier(out, obj.relatedLatexObjectId)
                }
                out.writeBoolean(obj.isCollapsed)
            }
            is PageObject.PdfPageRefObject -> {
                writeUtfString(out, obj.pdfAssetRelativePath)
                out.writeInt(obj.pageIndexZeroBased)
                out.writeBoolean(obj.invertInOledMode)
            }
            is PageObject.FormattedTextObject -> {
                writeUtfString(out, obj.text)
                out.writeFloat(obj.fontSizePt)
                out.writeInt(obj.canonicalColorArgb)
                out.writeFloat(obj.rotationRadians)
                out.writeInt(obj.sourceStrokeIds.size)
                for (srcId in obj.sourceStrokeIds) {
                    writeIdentifier(out, srcId)
                }
                writeEmbeddedStrokeList(out, obj.originalStrokes)
            }
            is PageObject.GeometricShapeObject -> {
                writeUtfString(out, obj.shapeType.name)
                out.writeFloat(obj.strokeWidthPt)
                out.writeInt(obj.canonicalStrokeColorArgb)
                out.writeBoolean(obj.hasFill)
                out.writeInt(obj.canonicalFillColorArgb)
                out.writeFloat(obj.rotationRadians)
            }
            is PageObject.ImageObject -> {
                writeUtfString(out, obj.imageAssetRelativePath)
                out.writeFloat(obj.rotationRadians)
            }
            is PageObject.CodeBlockObject -> {
                writeUtfString(out, obj.language.name)
                writeUtfString(out, obj.codeText)
                out.writeFloat(obj.fontSizePt)
                writeUtfString(out, obj.title)
                out.writeBoolean(obj.showLineNumbers)
                out.writeFloat(obj.rotationRadians)
            }
            is PageObject.ClozeTapeObject -> {
                out.writeInt(obj.canonicalColorArgb)
                writeUtfString(out, obj.patternStyle.name)
                out.writeFloat(obj.rotationRadians)
                writeUtfString(out, obj.label)
            }
            is PageObject.TableObject -> {
                out.writeInt(obj.rows)
                out.writeInt(obj.columns)
                out.writeFloat(obj.borderStrokeWidthPt)
                out.writeInt(obj.canonicalBorderColorArgb)
                out.writeBoolean(obj.hasHeaderRow)
            }
        }

        out.flush()
        return baos.toByteArray()
    }

    fun decodePageObject(payload: ByteArray): PageObject {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val tag = input.readByte().toInt() and 0xFF
        val id = readIdentifier(input)
        val zOrder = input.readLong()
        val bounds = DocRect(
            leftPt = input.readFloat(),
            topPt = input.readFloat(),
            rightPt = input.readFloat(),
            bottomPt = input.readFloat()
        )
        return when (tag) {
            OBJECT_TAG_LATEX -> {
                val latex = readUtfString(input)
                val fontSize = input.readFloat()
                val color = input.readInt()
                val rotation = input.readFloat()
                val srcCount = input.readInt().coerceAtLeast(0)
                val srcIds = ArrayList<String>(srcCount)
                for (i in 0 until srcCount) {
                    srcIds.add(readIdentifier(input))
                }
                val originalStrokes = if (input.available() > 0) readEmbeddedStrokeList(input) else emptyList()
                PageObject.LatexObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    latexSource = latex,
                    fontSizePt = fontSize,
                    canonicalColorArgb = color,
                    rotationRadians = rotation,
                    sourceStrokeIds = srcIds,
                    originalStrokes = originalStrokes
                )
            }
            OBJECT_TAG_FUNCTION_PLOT -> PageObject.FunctionPlotObject(
                id = id,
                zOrder = zOrder,
                bounds = bounds,
                expression = readUtfString(input),
                domainMinX = input.readFloat(),
                domainMaxX = input.readFloat(),
                rangeMinY = input.readFloat(),
                rangeMaxY = input.readFloat(),
                canonicalColorArgb = input.readInt(),
                strokeWidthPt = input.readFloat(),
                showGrid = input.readBoolean()
            )
            OBJECT_TAG_DERIVATION_CARD -> {
                val title = readUtfString(input)
                val steps = readUtfString(input)
                val hasRel = input.readBoolean()
                val relatedId = if (hasRel) readIdentifier(input) else null
                val collapsed = input.readBoolean()
                PageObject.DerivationCardObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    title = title,
                    stepsMarkdownLatex = steps,
                    relatedLatexObjectId = relatedId,
                    isCollapsed = collapsed
                )
            }
            OBJECT_TAG_PDF_PAGE_REF -> PageObject.PdfPageRefObject(
                id = id,
                zOrder = zOrder,
                bounds = bounds,
                pdfAssetRelativePath = readUtfString(input),
                pageIndexZeroBased = input.readInt(),
                invertInOledMode = input.readBoolean()
            )
            OBJECT_TAG_FORMATTED_TEXT -> {
                val text = readUtfString(input)
                val fontSize = input.readFloat()
                val color = input.readInt()
                val rotation = input.readFloat()
                val srcCount = input.readInt().coerceAtLeast(0)
                val srcIds = ArrayList<String>(srcCount)
                for (i in 0 until srcCount) {
                    srcIds.add(readIdentifier(input))
                }
                val originalStrokes = if (input.available() > 0) readEmbeddedStrokeList(input) else emptyList()
                PageObject.FormattedTextObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    text = text,
                    fontSizePt = fontSize,
                    canonicalColorArgb = color,
                    rotationRadians = rotation,
                    sourceStrokeIds = srcIds,
                    originalStrokes = originalStrokes
                )
            }
            OBJECT_TAG_GEOMETRIC_SHAPE -> {
                val shapeName = readUtfString(input)
                val shapeType = runCatching {
                    hu.bdev.stylusnote.domain.model.GeometricShapeType.valueOf(shapeName)
                }.getOrDefault(hu.bdev.stylusnote.domain.model.GeometricShapeType.RECTANGLE)
                val strokeWidthPt = input.readFloat()
                val canonicalStrokeColorArgb = input.readInt()
                val hasFill = input.readBoolean()
                val canonicalFillColorArgb = input.readInt()
                val rotationRadians = input.readFloat()
                PageObject.GeometricShapeObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    shapeType = shapeType,
                    strokeWidthPt = strokeWidthPt,
                    canonicalStrokeColorArgb = canonicalStrokeColorArgb,
                    hasFill = hasFill,
                    canonicalFillColorArgb = canonicalFillColorArgb,
                    rotationRadians = rotationRadians
                )
            }
            OBJECT_TAG_IMAGE -> {
                val assetPath = readUtfString(input)
                val rotationRadians = input.readFloat()
                PageObject.ImageObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    imageAssetRelativePath = assetPath,
                    rotationRadians = rotationRadians
                )
            }
            OBJECT_TAG_CODE_BLOCK -> {
                val languageName = readUtfString(input)
                val language = CodeLanguage.fromNameOrNull(languageName) ?: CodeLanguage.PYTHON
                val codeText = readUtfString(input)
                val fontSizePt = input.readFloat()
                val title = readUtfString(input)
                val showLineNumbers = input.readBoolean()
                val rotationRadians = input.readFloat()
                PageObject.CodeBlockObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    language = language,
                    codeText = codeText,
                    fontSizePt = fontSizePt,
                    title = title,
                    showLineNumbers = showLineNumbers,
                    rotationRadians = rotationRadians
                )
            }
            OBJECT_TAG_CLOZE_TAPE -> {
                val canonicalColorArgb = input.readInt()
                val patternName = readUtfString(input)
                val patternStyle = runCatching {
                    ClozeTapePattern.valueOf(patternName)
                }.getOrDefault(ClozeTapePattern.DIAGONAL_STRIPES)
                val rotationRadians = input.readFloat()
                val label = readUtfString(input)
                PageObject.ClozeTapeObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    canonicalColorArgb = canonicalColorArgb,
                    patternStyle = patternStyle,
                    rotationRadians = rotationRadians,
                    label = label
                )
            }
            OBJECT_TAG_TABLE -> {
                val rows = input.readInt().coerceIn(PageObject.TableObject.MIN_ROWS, PageObject.TableObject.MAX_ROWS)
                val cols = input.readInt().coerceIn(PageObject.TableObject.MIN_COLS, PageObject.TableObject.MAX_COLS)
                val strokeWidthPt = input.readFloat().coerceIn(0.4f, 6f)
                val borderColor = input.readInt()
                val hasHeader = input.readBoolean()
                PageObject.TableObject(
                    id = id,
                    zOrder = zOrder,
                    bounds = bounds,
                    rows = rows,
                    columns = cols,
                    borderStrokeWidthPt = strokeWidthPt,
                    canonicalBorderColorArgb = borderColor,
                    hasHeaderRow = hasHeader
                )
            }
            else -> throw IOException("Unknown PageObject tag: $tag")
        }
    }

    /**
     * Converts a live Jetpack Ink [IndexedDryStroke] into a [PersistedStrokeRecord], serializing
     * `StrokeInputBatch` and `BrushFamily` via `androidx.ink.storage`.
     */
    fun fromIndexedDryStroke(
        entry: IndexedDryStroke,
        brushConfig: BrushConfig,
        shapeMeta: ShapeMeta? = null
    ): PersistedStrokeRecord {
        val samples = try {
            CanvasStateHolder.extractStylusPoints(entry.stroke)
        } catch (_: Throwable) {
            emptyList()
        }
        val encodedInputs = encodeInkInputsSafely(entry.stroke.inputs)
        val encodedFamily = encodeBrushFamilySafely(entry.stroke.brush.family)
        val canonicalColor = brushConfig.colorArgb
        val effectiveSize = entry.stroke.brush.size

        return PersistedStrokeRecord(
            id = entry.id,
            layer = if (entry.isHighlighter) StrokeLayerType.HIGHLIGHTER else StrokeLayerType.INK,
            zOrder = entry.zIndex,
            brushConfig = brushConfig.copy(sizePt = effectiveSize, colorArgb = canonicalColor),
            canonicalColorArgb = canonicalColor,
            sizePt = effectiveSize,
            inkEncodedInputs = encodedInputs,
            samples = samples,
            bounds = entry.bounds,
            shapeMeta = shapeMeta,
            serializedBrushFamily = encodedFamily,
            usedFallbackBrush = false,
            attachedObjectId = entry.attachedObjectId
        )
    }

    /**
     * Reconstructs a live [IndexedDryStroke] from a [PersistedStrokeRecord], falling back cleanly
     * to the nearest stock [BrushFamily] if a serialized custom family cannot be decoded (Section 9.4).
     */
    fun toIndexedDryStroke(record: PersistedStrokeRecord): Pair<IndexedDryStroke, Boolean> {
        var usedFallback = record.usedFallbackBrush
        val decodedFamily = record.serializedBrushFamily?.let { bytes ->
            val family = decodeBrushFamilySafely(bytes)
            if (family == null) {
                usedFallback = true
            }
            family
        }
        val family = decodedFamily ?: BrushConfig.resolveBrushFamily(record.brushConfig.toolType)

        val effectiveColor = record.brushConfig
            .copy(colorArgb = record.canonicalColorArgb, sizePt = record.sizePt)
            .effectiveColorArgb(record.canonicalColorArgb)

        val inkBrush = Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = effectiveColor,
            size = record.sizePt.coerceIn(BrushConfig.MIN_BRUSH_SIZE_PT, BrushConfig.MAX_BRUSH_SIZE_PT),
            epsilon = BrushConfig.DEFAULT_BRUSH_EPSILON
        )

        val batch = record.inkEncodedInputs?.let(::decodeInkInputsSafely)
            ?: record.samples.toStrokeInputBatch(toolType = InputToolType.STYLUS)

        val stroke = Stroke(brush = inkBrush, inputs = batch)
        val bounds = computeStrokeDocBounds(stroke)
        return IndexedDryStroke(
            id = record.id,
            stroke = stroke,
            bounds = bounds,
            zIndex = record.zOrder,
            isHighlighter = record.layer == StrokeLayerType.HIGHLIGHTER,
            attachedObjectId = record.attachedObjectId
        ) to usedFallback
    }

    private fun encodeInkInputsSafely(inputs: StrokeInputBatch): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            inputs.encode(out)
            out.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeInkInputsSafely(bytes: ByteArray): StrokeInputBatch? = try {
        ByteArrayInputStream(bytes).use { input ->
            StrokeInputBatch.decode(input)
        }
    } catch (_: Throwable) {
        null
    }

    private fun encodeBrushFamilySafely(family: BrushFamily): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            family.encode(out)
            out.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeBrushFamilySafely(bytes: ByteArray): BrushFamily? = try {
        ByteArrayInputStream(bytes).use { input ->
            BrushFamily.decode(input)
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeIdentifier(out: DataOutputStream, id: String) {
        val parsed = tryParseUuid(id)
        if (parsed != null) {
            out.writeByte(1)
            out.writeLong(parsed.mostSignificantBits)
            out.writeLong(parsed.leastSignificantBits)
        } else {
            out.writeByte(0)
            writeUtfString(out, id)
        }
    }

    private fun readIdentifier(input: DataInputStream): String {
        val kind = input.readByte().toInt()
        return if (kind == 1) {
            val msb = input.readLong()
            val lsb = input.readLong()
            UUID(msb, lsb).toString()
        } else {
            readUtfString(input)
        }
    }

    private fun tryParseUuid(id: String): UUID? = try {
        if (id.length == 36 && id[8] == '-' && id[13] == '-' && id[18] == '-' && id[23] == '-') {
            UUID.fromString(id)
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun writeUtfString(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readUtfString(input: DataInputStream): String {
        val len = input.readInt().coerceAtLeast(0)
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeOptionalByteArray(out: DataOutputStream, bytes: ByteArray?) {
        if (bytes == null) {
            out.writeInt(-1)
        } else {
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun readOptionalByteArray(input: DataInputStream): ByteArray? {
        val len = input.readInt()
        if (len < 0) return null
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return bytes
    }

    private fun writeEmbeddedStrokeList(
        out: DataOutputStream,
        strokes: List<PersistedStrokeRecord>
    ) {
        out.writeInt(strokes.size)
        for (stroke in strokes) {
            val encoded = encodeStrokeRecord(stroke)
            out.writeInt(encoded.size)
            out.write(encoded)
        }
    }

    private fun readEmbeddedStrokeList(input: DataInputStream): List<PersistedStrokeRecord> {
        val count = input.readInt().coerceAtLeast(0)
        val result = ArrayList<PersistedStrokeRecord>(count)
        for (i in 0 until count) {
            val len = input.readInt().coerceAtLeast(0)
            val bytes = ByteArray(len)
            input.readFully(bytes)
            result.add(decodeStrokeRecord(bytes))
        }
        return result
    }
}

