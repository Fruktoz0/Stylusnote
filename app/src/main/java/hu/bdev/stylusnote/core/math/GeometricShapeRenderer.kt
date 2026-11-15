package hu.bdev.stylusnote.core.math

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withSave
import hu.bdev.stylusnote.domain.model.GeometricShapeType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared thread-safe vector renderer for [GeometricShapeType] used across:
 * 1) the Shape picker grid previews (`ShapePickerPopover`),
 * 2) interactive page overlays (`GeometricShapeOverlayNode`), and
 * 3) vector PDF export (`PdfExportManager`).
 */
object GeometricShapeRenderer {

    private class RenderScratch {
        val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val solidPath = Path()
        val dashedPath = Path()
        val fillPath = Path()
        val drawRect = RectF()
        val auxRect = RectF()
        var lastDashLength: Float = -1f
    }

    private val threadScratch = ThreadLocal.withInitial { RenderScratch() }

    fun drawShape(
        canvas: Canvas,
        shapeType: GeometricShapeType,
        bounds: RectF,
        strokeWidthPx: Float,
        strokeColorArgb: Int,
        hasFill: Boolean,
        fillColorArgb: Int,
        rotationRadians: Float = 0f
    ) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 1f || h <= 1f) return

        val s = threadScratch.get()!!
        val pad = strokeWidthPx * 0.55f + 0.5f
        val r = s.drawRect
        r.set(
            bounds.left + pad,
            bounds.top + pad,
            max(bounds.left + pad + 1f, bounds.right - pad),
            max(bounds.top + pad + 1f, bounds.bottom - pad)
        )

        val dashLen = max(strokeWidthPx * 2.2f, min(r.width(), r.height()) * 0.055f)
        val dashGap = dashLen * 0.85f

        s.solidPaint.color = strokeColorArgb
        s.solidPaint.strokeWidth = strokeWidthPx
        s.dashedPaint.color = strokeColorArgb
        s.dashedPaint.strokeWidth = max(0.8f, strokeWidthPx * 0.88f)
        if (abs(s.lastDashLength - dashLen) > 0.25f) {
            s.dashedPaint.pathEffect = DashPathEffect(floatArrayOf(dashLen, dashGap), 0f)
            s.lastDashLength = dashLen
        }
        s.fillPaint.color = fillColorArgb

        s.solidPath.reset()
        s.dashedPath.reset()
        s.fillPath.reset()

        buildShapePaths(shapeType, r, s)

        canvas.withSave {
            if (abs(rotationRadians) > 1e-4f) {
                rotate((rotationRadians * 180.0 / PI).toFloat(), bounds.centerX(), bounds.centerY())
            }
            if (hasFill && !s.fillPath.isEmpty) {
                drawPath(s.fillPath, s.fillPaint)
            }
            if (!s.dashedPath.isEmpty) {
                drawPath(s.dashedPath, s.dashedPaint)
            }
            if (!s.solidPath.isEmpty) {
                drawPath(s.solidPath, s.solidPaint)
            }
        }
    }

    private fun buildShapePaths(
        type: GeometricShapeType,
        r: RectF,
        s: RenderScratch
    ) {
        val solid = s.solidPath
        val dashed = s.dashedPath
        val fill = s.fillPath
        val aux = s.auxRect

        fun x(nx: Float): Float = r.left + nx * r.width()
        fun y(ny: Float): Float = r.top + ny * r.height()

        fun addPolygon(pts: List<Pair<Float, Float>>, target: Path, fillToo: Boolean = true) {
            if (pts.isEmpty()) return
            target.moveTo(x(pts[0].first), y(pts[0].second))
            for (i in 1 until pts.size) {
                target.lineTo(x(pts[i].first), y(pts[i].second))
            }
            target.close()
            if (fillToo) {
                fill.addPath(target)
            }
        }

        fun seg(p: Path, x0: Float, y0: Float, x1: Float, y1: Float) {
            p.moveTo(x(x0), y(y0))
            p.lineTo(x(x1), y(y1))
        }

        fun regularPolygon(sides: Int, startDeg: Float, rxNorm: Float = 0.44f, ryNorm: Float = 0.44f): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>(sides)
            for (i in 0 until sides) {
                val rad = (startDeg + i * 360.0 / sides) * PI / 180.0
                out.add(0.5f + rxNorm * cos(rad).toFloat() to 0.5f + ryNorm * sin(rad).toFloat())
            }
            return out
        }

        when (type) {
            GeometricShapeType.TRAPEZOID -> addPolygon(
                listOf(0.18f to 0.22f, 0.72f to 0.22f, 0.94f to 0.78f, 0.06f to 0.78f),
                solid
            )

            GeometricShapeType.PARALLELOGRAM -> addPolygon(
                listOf(0.24f to 0.22f, 0.94f to 0.22f, 0.76f to 0.78f, 0.06f to 0.78f),
                solid
            )

            GeometricShapeType.DIAMOND -> addPolygon(
                listOf(0.50f to 0.08f, 0.92f to 0.50f, 0.50f to 0.92f, 0.08f to 0.50f),
                solid
            )

            GeometricShapeType.PENTAGON -> addPolygon(regularPolygon(5, -90f), solid)

            GeometricShapeType.HEXAGON -> addPolygon(regularPolygon(6, -90f), solid)

            GeometricShapeType.OCTAGON -> addPolygon(regularPolygon(8, -67.5f), solid)

            GeometricShapeType.CIRCLE -> {
                val rad = min(r.width(), r.height()) * 0.44f
                solid.addCircle(r.centerX(), r.centerY(), rad, Path.Direction.CW)
                fill.addCircle(r.centerX(), r.centerY(), rad, Path.Direction.CW)
            }

            GeometricShapeType.ELLIPSE -> {
                aux.set(x(0.06f), y(0.22f), x(0.94f), y(0.78f))
                solid.addOval(aux, Path.Direction.CW)
                fill.addOval(aux, Path.Direction.CW)
            }

            GeometricShapeType.STAR_FIVE -> {
                val pts = ArrayList<Pair<Float, Float>>(10)
                for (i in 0 until 10) {
                    val rad = (-90.0 + i * 36.0) * PI / 180.0
                    val radius = if (i % 2 == 0) 0.45f else 0.19f
                    pts.add(0.5f + radius * cos(rad).toFloat() to 0.52f + radius * sin(rad).toFloat())
                }
                addPolygon(pts, solid)
            }

            GeometricShapeType.CONE -> {
                val apexX = 0.50f
                val apexY = 0.10f
                val leftX = 0.12f
                val rightX = 0.88f
                val baseCy = 0.78f
                val baseRy = 0.11f
                aux.set(x(leftX), y(baseCy - baseRy), x(rightX), y(baseCy + baseRy))
                seg(solid, apexX, apexY, leftX, baseCy)
                seg(solid, apexX, apexY, rightX, baseCy)
                solid.addArc(aux, 0f, 180f)
                dashed.addArc(aux, 180f, 180f)
                fill.moveTo(x(apexX), y(apexY))
                fill.lineTo(x(rightX), y(baseCy))
                fill.arcTo(aux, 0f, 180f, false)
                fill.close()
            }

            GeometricShapeType.TETRAHEDRON -> {
                val apex = 0.50f to 0.10f
                val left = 0.12f to 0.76f
                val front = 0.54f to 0.88f
                val right = 0.88f to 0.72f
                seg(solid, apex.first, apex.second, left.first, left.second)
                seg(solid, apex.first, apex.second, front.first, front.second)
                seg(solid, apex.first, apex.second, right.first, right.second)
                seg(solid, left.first, left.second, front.first, front.second)
                seg(solid, front.first, front.second, right.first, right.second)
                seg(dashed, left.first, left.second, right.first, right.second)
                addPolygon(listOf(apex, right, front, left), fill, fillToo = true)
            }

            GeometricShapeType.SQUARE_PYRAMID -> {
                val apex = 0.50f to 0.10f
                val bl = 0.12f to 0.74f
                val fl = 0.56f to 0.88f
                val fr = 0.88f to 0.70f
                val br = 0.44f to 0.58f
                seg(solid, apex.first, apex.second, bl.first, bl.second)
                seg(solid, apex.first, apex.second, fl.first, fl.second)
                seg(solid, apex.first, apex.second, fr.first, fr.second)
                seg(solid, bl.first, bl.second, fl.first, fl.second)
                seg(solid, fl.first, fl.second, fr.first, fr.second)
                seg(dashed, apex.first, apex.second, br.first, br.second)
                seg(dashed, bl.first, bl.second, br.first, br.second)
                seg(dashed, br.first, br.second, fr.first, fr.second)
                addPolygon(listOf(apex, fr, fl, bl), fill, fillToo = true)
            }

            GeometricShapeType.ROOF_TRIANGULAR_PRISM -> {
                val a1 = 0.12f to 0.48f
                val b1 = 0.12f to 0.80f
                val c1 = 0.58f to 0.80f
                val a2 = 0.46f to 0.20f
                val b2 = 0.46f to 0.52f
                val c2 = 0.88f to 0.52f
                seg(solid, a1.first, a1.second, b1.first, b1.second)
                seg(solid, b1.first, b1.second, c1.first, c1.second)
                seg(solid, c1.first, c1.second, a1.first, a1.second)
                seg(solid, a1.first, a1.second, a2.first, a2.second)
                seg(solid, a2.first, a2.second, c2.first, c2.second)
                seg(solid, c1.first, c1.second, c2.first, c2.second)
                seg(dashed, b1.first, b1.second, b2.first, b2.second)
                seg(dashed, a2.first, a2.second, b2.first, b2.second)
                seg(dashed, b2.first, b2.second, c2.first, c2.second)
                addPolygon(listOf(a1, a2, c2, c1, b1), fill, fillToo = true)
            }

            GeometricShapeType.OBLIQUE_PYRAMID -> {
                val apex = 0.72f to 0.14f
                val v1 = 0.12f to 0.76f
                val v2 = 0.48f to 0.86f
                val v3 = 0.88f to 0.66f
                seg(solid, apex.first, apex.second, v1.first, v1.second)
                seg(solid, apex.first, apex.second, v2.first, v2.second)
                seg(solid, apex.first, apex.second, v3.first, v3.second)
                seg(solid, v1.first, v1.second, v2.first, v2.second)
                seg(solid, v2.first, v2.second, v3.first, v3.second)
                seg(dashed, v1.first, v1.second, v3.first, v3.second)
                addPolygon(listOf(apex, v3, v2, v1), fill, fillToo = true)
            }

            GeometricShapeType.UPRIGHT_TRIANGULAR_PRISM -> {
                val t1 = 0.52f to 0.12f
                val t2 = 0.22f to 0.30f
                val t3 = 0.82f to 0.30f
                val b1 = 0.52f to 0.70f
                val b2 = 0.22f to 0.88f
                val b3 = 0.82f to 0.88f
                addPolygon(listOf(t1, t3, t2), solid, fillToo = false)
                seg(solid, t2.first, t2.second, b2.first, b2.second)
                seg(solid, t3.first, t3.second, b3.first, b3.second)
                seg(solid, b2.first, b2.second, b3.first, b3.second)
                seg(dashed, t1.first, t1.second, b1.first, b1.second)
                seg(dashed, b2.first, b2.second, b1.first, b1.second)
                seg(dashed, b1.first, b1.second, b3.first, b3.second)
                addPolygon(listOf(t1, t3, b3, b2, t2), fill, fillToo = true)
            }

            GeometricShapeType.RECTANGULAR_PRISM -> {
                val flTop = 0.10f to 0.42f
                val frTop = 0.68f to 0.66f
                val frBot = 0.68f to 0.88f
                val flBot = 0.10f to 0.64f
                val blTop = 0.32f to 0.14f
                val brTop = 0.90f to 0.38f
                val brBot = 0.90f to 0.60f
                val blBot = 0.32f to 0.36f
                addPolygon(listOf(flTop, frTop, frBot, flBot), solid, fillToo = false)
                seg(solid, flTop.first, flTop.second, blTop.first, blTop.second)
                seg(solid, frTop.first, frTop.second, brTop.first, brTop.second)
                seg(solid, frBot.first, frBot.second, brBot.first, brBot.second)
                seg(solid, blTop.first, blTop.second, brTop.first, brTop.second)
                seg(solid, brTop.first, brTop.second, brBot.first, brBot.second)
                seg(dashed, blTop.first, blTop.second, blBot.first, blBot.second)
                seg(dashed, flBot.first, flBot.second, blBot.first, blBot.second)
                seg(dashed, blBot.first, blBot.second, brBot.first, brBot.second)
                addPolygon(listOf(flTop, blTop, brTop, brBot, frBot, flBot), fill, fillToo = true)
            }

            GeometricShapeType.CUBE -> {
                val dx = 0.22f
                val dy = -0.18f
                val ftl = 0.14f to 0.32f
                val ftr = 0.66f to 0.32f
                val fbr = 0.66f to 0.86f
                val fbl = 0.14f to 0.86f
                val btl = (ftl.first + dx) to (ftl.second + dy)
                val btr = (ftr.first + dx) to (ftr.second + dy)
                val bbr = (fbr.first + dx) to (fbr.second + dy)
                val bbl = (fbl.first + dx) to (fbl.second + dy)
                addPolygon(listOf(ftl, ftr, fbr, fbl), solid, fillToo = false)
                seg(solid, ftl.first, ftl.second, btl.first, btl.second)
                seg(solid, ftr.first, ftr.second, btr.first, btr.second)
                seg(solid, fbr.first, fbr.second, bbr.first, bbr.second)
                seg(solid, btl.first, btl.second, btr.first, btr.second)
                seg(solid, btr.first, btr.second, bbr.first, bbr.second)
                seg(dashed, fbl.first, fbl.second, bbl.first, bbl.second)
                seg(dashed, btl.first, btl.second, bbl.first, bbl.second)
                seg(dashed, bbl.first, bbl.second, bbr.first, bbr.second)
                addPolygon(listOf(ftl, btl, btr, bbr, fbr, fbl), fill, fillToo = true)
            }

            GeometricShapeType.PENTAGONAL_PRISM -> {
                val top = listOf(
                    0.50f to 0.10f,
                    0.84f to 0.22f,
                    0.70f to 0.36f,
                    0.30f to 0.36f,
                    0.16f to 0.22f
                )
                val drop = 0.54f
                val bot = top.map { it.first to (it.second + drop) }
                addPolygon(top, solid, fillToo = false)
                for (idx in listOf(1, 2, 3, 4)) {
                    seg(solid, top[idx].first, top[idx].second, bot[idx].first, bot[idx].second)
                }
                seg(solid, bot[4].first, bot[4].second, bot[3].first, bot[3].second)
                seg(solid, bot[3].first, bot[3].second, bot[2].first, bot[2].second)
                seg(solid, bot[2].first, bot[2].second, bot[1].first, bot[1].second)
                seg(dashed, top[0].first, top[0].second, bot[0].first, bot[0].second)
                seg(dashed, bot[4].first, bot[4].second, bot[0].first, bot[0].second)
                seg(dashed, bot[0].first, bot[0].second, bot[1].first, bot[1].second)
                addPolygon(listOf(top[0], top[1], bot[1], bot[2], bot[3], bot[4], top[4]), fill, fillToo = true)
            }

            GeometricShapeType.HEXAGONAL_PRISM -> {
                val top = listOf(
                    0.34f to 0.10f,
                    0.66f to 0.10f,
                    0.86f to 0.22f,
                    0.66f to 0.34f,
                    0.34f to 0.34f,
                    0.14f to 0.22f
                )
                val drop = 0.54f
                val bot = top.map { it.first to (it.second + drop) }
                addPolygon(top, solid, fillToo = false)
                for (idx in listOf(2, 3, 4, 5)) {
                    seg(solid, top[idx].first, top[idx].second, bot[idx].first, bot[idx].second)
                }
                seg(solid, bot[5].first, bot[5].second, bot[4].first, bot[4].second)
                seg(solid, bot[4].first, bot[4].second, bot[3].first, bot[3].second)
                seg(solid, bot[3].first, bot[3].second, bot[2].first, bot[2].second)
                seg(dashed, top[0].first, top[0].second, bot[0].first, bot[0].second)
                seg(dashed, top[1].first, top[1].second, bot[1].first, bot[1].second)
                seg(dashed, bot[5].first, bot[5].second, bot[0].first, bot[0].second)
                seg(dashed, bot[0].first, bot[0].second, bot[1].first, bot[1].second)
                seg(dashed, bot[1].first, bot[1].second, bot[2].first, bot[2].second)
                addPolygon(listOf(top[0], top[1], top[2], bot[2], bot[3], bot[4], bot[5], top[5]), fill, fillToo = true)
            }

            GeometricShapeType.CYLINDER -> {
                val lx = 0.16f
                val rx = 0.84f
                val topCy = 0.20f
                val botCy = 0.80f
                val ry = 0.10f
                aux.set(x(lx), y(topCy - ry), x(rx), y(topCy + ry))
                solid.addOval(aux, Path.Direction.CW)
                seg(solid, lx, topCy, lx, botCy)
                seg(solid, rx, topCy, rx, botCy)
                aux.set(x(lx), y(botCy - ry), x(rx), y(botCy + ry))
                solid.addArc(aux, 0f, 180f)
                dashed.addArc(aux, 180f, 180f)
                fill.moveTo(x(lx), y(topCy))
                aux.set(x(lx), y(topCy - ry), x(rx), y(topCy + ry))
                fill.arcTo(aux, 180f, 180f, false)
                fill.lineTo(x(rx), y(botCy))
                aux.set(x(lx), y(botCy - ry), x(rx), y(botCy + ry))
                fill.arcTo(aux, 0f, 180f, false)
                fill.close()
            }

            GeometricShapeType.HOLLOW_CYLINDER -> {
                val lx = 0.16f
                val rx = 0.84f
                val ilx = 0.34f
                val irx = 0.66f
                val topCy = 0.20f
                val botCy = 0.80f
                val ry = 0.10f
                val iry = 0.05f
                aux.set(x(lx), y(topCy - ry), x(rx), y(topCy + ry))
                solid.addOval(aux, Path.Direction.CW)
                aux.set(x(ilx), y(topCy - iry), x(irx), y(topCy + iry))
                solid.addOval(aux, Path.Direction.CW)
                seg(solid, lx, topCy, lx, botCy)
                seg(solid, rx, topCy, rx, botCy)
                seg(dashed, ilx, topCy, ilx, botCy)
                seg(dashed, irx, topCy, irx, botCy)
                aux.set(x(lx), y(botCy - ry), x(rx), y(botCy + ry))
                solid.addArc(aux, 0f, 180f)
                dashed.addArc(aux, 180f, 180f)
                aux.set(x(ilx), y(botCy - iry), x(irx), y(botCy + iry))
                dashed.addOval(aux, Path.Direction.CW)
                fill.moveTo(x(lx), y(topCy))
                aux.set(x(lx), y(topCy - ry), x(rx), y(topCy + ry))
                fill.arcTo(aux, 180f, 180f, false)
                fill.lineTo(x(rx), y(botCy))
                aux.set(x(lx), y(botCy - ry), x(rx), y(botCy + ry))
                fill.arcTo(aux, 0f, 180f, false)
                fill.close()
            }

            GeometricShapeType.FRUSTUM_PYRAMID -> {
                val t = listOf(0.28f to 0.22f, 0.72f to 0.22f, 0.62f to 0.34f, 0.38f to 0.34f)
                val b = listOf(0.10f to 0.72f, 0.90f to 0.72f, 0.68f to 0.88f, 0.32f to 0.88f)
                addPolygon(t, solid, fillToo = false)
                seg(solid, t[0].first, t[0].second, b[0].first, b[0].second)
                seg(solid, t[1].first, t[1].second, b[1].first, b[1].second)
                seg(solid, t[2].first, t[2].second, b[2].first, b[2].second)
                seg(solid, t[3].first, t[3].second, b[3].first, b[3].second)
                seg(solid, b[0].first, b[0].second, b[3].first, b[3].second)
                seg(solid, b[3].first, b[3].second, b[2].first, b[2].second)
                seg(solid, b[2].first, b[2].second, b[1].first, b[1].second)
                seg(dashed, b[0].first, b[0].second, b[1].first, b[1].second)
                addPolygon(listOf(t[0], t[1], b[1], b[2], b[3], b[0]), fill, fillToo = true)
            }

            GeometricShapeType.FRUSTUM_CONE -> {
                val tlx = 0.28f
                val trx = 0.72f
                val blx = 0.14f
                val brx = 0.86f
                val topCy = 0.22f
                val botCy = 0.78f
                val tryRad = 0.08f
                val bryRad = 0.11f
                aux.set(x(tlx), y(topCy - tryRad), x(trx), y(topCy + tryRad))
                solid.addOval(aux, Path.Direction.CW)
                seg(solid, tlx, topCy, blx, botCy)
                seg(solid, trx, topCy, brx, botCy)
                aux.set(x(blx), y(botCy - bryRad), x(brx), y(botCy + bryRad))
                solid.addArc(aux, 0f, 180f)
                dashed.addArc(aux, 180f, 180f)
                fill.moveTo(x(tlx), y(topCy))
                aux.set(x(tlx), y(topCy - tryRad), x(trx), y(topCy + tryRad))
                fill.arcTo(aux, 180f, 180f, false)
                fill.lineTo(x(brx), y(botCy))
                aux.set(x(blx), y(botCy - bryRad), x(brx), y(botCy + bryRad))
                fill.arcTo(aux, 0f, 180f, false)
                fill.close()
            }

            GeometricShapeType.HEMISPHERE -> {
                val lx = 0.10f
                val rx = 0.90f
                val eqY = 0.42f
                val ry = 0.13f
                aux.set(x(lx), y(eqY - ry), x(rx), y(eqY + ry))
                solid.addArc(aux, 0f, 180f)
                dashed.addArc(aux, 180f, 180f)
                aux.set(x(lx), y(0.10f), x(rx), y(0.76f))
                solid.addArc(aux, 0f, 180f)
                fill.moveTo(x(lx), y(eqY))
                aux.set(x(lx), y(eqY - ry), x(rx), y(eqY + ry))
                fill.arcTo(aux, 180f, 180f, false)
                aux.set(x(lx), y(0.10f), x(rx), y(0.76f))
                fill.arcTo(aux, 0f, 180f, false)
                fill.close()
            }

            GeometricShapeType.RECTANGLE -> addPolygon(
                listOf(0.08f to 0.16f, 0.92f to 0.16f, 0.92f to 0.84f, 0.08f to 0.84f),
                solid
            )

            GeometricShapeType.ROUNDED_RECTANGLE -> {
                val rad = min(r.width(), r.height()) * 0.16f
                aux.set(x(0.08f), y(0.16f), x(0.92f), y(0.84f))
                solid.addRoundRect(aux, rad, rad, Path.Direction.CW)
                fill.addRoundRect(aux, rad, rad, Path.Direction.CW)
            }

            GeometricShapeType.TRIANGLE -> addPolygon(
                listOf(0.50f to 0.10f, 0.90f to 0.86f, 0.10f to 0.86f),
                solid
            )

            GeometricShapeType.RIGHT_TRIANGLE -> addPolygon(
                listOf(0.14f to 0.12f, 0.88f to 0.86f, 0.14f to 0.86f),
                solid
            )

            GeometricShapeType.LINE -> seg(solid, 0.10f, 0.50f, 0.90f, 0.50f)

            GeometricShapeType.ARROW -> {
                seg(solid, 0.08f, 0.50f, 0.88f, 0.50f)
                seg(solid, 0.72f, 0.34f, 0.88f, 0.50f)
                seg(solid, 0.72f, 0.66f, 0.88f, 0.50f)
            }

            GeometricShapeType.DOUBLE_ARROW -> {
                seg(solid, 0.10f, 0.50f, 0.90f, 0.50f)
                seg(solid, 0.26f, 0.34f, 0.10f, 0.50f)
                seg(solid, 0.26f, 0.66f, 0.10f, 0.50f)
                seg(solid, 0.74f, 0.34f, 0.90f, 0.50f)
                seg(solid, 0.74f, 0.66f, 0.90f, 0.50f)
            }

            GeometricShapeType.AXES_2D -> {
                // X axis + arrow
                seg(solid, 0.08f, 0.50f, 0.92f, 0.50f)
                seg(solid, 0.84f, 0.43f, 0.92f, 0.50f)
                seg(solid, 0.84f, 0.57f, 0.92f, 0.50f)
                // Y axis + arrow
                seg(solid, 0.50f, 0.92f, 0.50f, 0.08f)
                seg(solid, 0.43f, 0.16f, 0.50f, 0.08f)
                seg(solid, 0.57f, 0.16f, 0.50f, 0.08f)
            }
        }
    }
}
