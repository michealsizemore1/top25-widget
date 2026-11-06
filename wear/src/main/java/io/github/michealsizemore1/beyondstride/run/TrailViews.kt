package io.github.michealsizemore1.beyondstride.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import io.github.michealsizemore1.beyondstride.Geo
import io.github.michealsizemore1.beyondstride.Trail
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

/**
 * The trail screen during a run: the trail line around you (north up), the part already done
 * dimmed, you as an arrow, and distance left / next turn / climb left as text.
 */
class TrailMapView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    /** Night-style map: colors inverted and dimmed (light paper → dark ground, lines stay visible). */
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            -0.42f, -0.08f, -0.08f, 0f, 150f,
            -0.08f, -0.42f, -0.08f, 0f, 150f,
            -0.08f, -0.08f, -0.42f, 0f, 155f,
            0f, 0f, 0f, 1f, 0f))
    }

    /** How many meters from the center to the edge of the screen. */
    var radiusM = 300.0

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val nav = Run.nav ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        c.drawColor(Color.BLACK)
        val haveFix = Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 15_000
        // Center on you (or on the whole trail before the first GPS fix).
        val center = if (haveFix) doubleArrayOf(Run.gpsLat, Run.gpsLon) else nav.path[nav.path.size / 2]
        val r = if (haveFix) radiusM else (nav.path.maxOf { Geo.dist(it, center) } * 1.1).coerceAtLeast(200.0)
        val scale = (min(w, h) / 2 * 0.9 / r).toFloat()
        val k = cos(Math.toRadians(center[0])) * 111320.0
        fun x(p: DoubleArray) = cx + ((p[1] - center[1]) * k).toFloat() * scale
        fun y(p: DoubleArray) = cy - ((p[0] - center[0]) * 110540.0).toFloat() * scale

        // Topo map underneath (saved on the watch), darkened so the trail and arrow stand out.
        if (TrailTiles.enabled(context)) {
            val mpp = 1.0 / scale
            val z = TrailTiles.zoomFor(mpp, center[0])
            val halfLon = (w / 2 / scale) / k; val halfLat = (h / 2 / scale) / 110540.0
            val x0 = kotlin.math.floor(TrailTiles.tileX(center[1] - halfLon, z)).toInt()
            val x1 = kotlin.math.floor(TrailTiles.tileX(center[1] + halfLon, z)).toInt()
            val y0 = kotlin.math.floor(TrailTiles.tileY(center[0] + halfLat, z)).toInt()
            val y1 = kotlin.math.floor(TrailTiles.tileY(center[0] - halfLat, z)).toInt()
            if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 36) for (tx in x0..x1) for (ty in y0..y1) {
                val bmp = TrailTiles.bitmap(context, z, tx, ty) ?: continue
                val nw = doubleArrayOf(TrailTiles.latOf(ty.toDouble(), z), TrailTiles.lonOf(tx.toDouble(), z))
                val se = doubleArrayOf(TrailTiles.latOf(ty + 1.0, z), TrailTiles.lonOf(tx + 1.0, z))
                c.drawBitmap(bmp, null, android.graphics.RectF(x(nw), y(nw), x(se), y(se)), mapPaint)
            }
        }

        // Trail: done part dim, the rest bright.
        val done = Path(); val ahead = Path()
        var started = false; var aheadStarted = false
        for (i in nav.path.indices) {
            val p = nav.path[i]
            if (nav.cum[i] <= nav.progress) { if (!started) { done.moveTo(x(p), y(p)); started = true } else done.lineTo(x(p), y(p)) }
            if (nav.cum[i] >= nav.progress || (i + 1 < nav.path.size && nav.cum[i + 1] > nav.progress)) {
                if (!aheadStarted) { ahead.moveTo(x(p), y(p)); aheadStarted = true } else ahead.lineTo(x(p), y(p))
            }
        }
        line.strokeWidth = 5 * density
        line.color = 0xFF3A4440.toInt(); c.drawPath(done, line)
        line.color = if (nav.offRoute) 0xFFE5484D.toInt() else 0xFFD4E635.toInt(); c.drawPath(ahead, line)
        // Start (green) and end/turnaround (white) markers.
        fill.color = 0xFF46C46E.toInt(); c.drawCircle(x(nav.path.first()), y(nav.path.first()), 5 * density, fill)
        if (nav.outAndBack) nav.trail.points.last().let { fill.color = Color.WHITE; c.drawCircle(x(it), y(it), 4 * density, fill) }

        // You.
        if (haveFix) {
            val me = doubleArrayOf(Run.gpsLat, Run.gpsLon)
            val px = x(me); val py = y(me)
            fill.color = 0xFF4FA3E0.toInt()
            if (Run.gpsBearing >= 0) {
                c.save(); c.rotate(Run.gpsBearing, px, py)
                val s = 10 * density
                c.drawPath(Path().apply { moveTo(px, py - s); lineTo(px + s * 0.7f, py + s * 0.7f); lineTo(px, py + s * 0.3f); lineTo(px - s * 0.7f, py + s * 0.7f); close() }, fill)
                c.restore()
            } else c.drawCircle(px, py, 7 * density, fill)
        }

        // Text: status on top, distance left and what's next at the bottom.
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 13 * density
        val top = when {
            !haveFix -> "Finding GPS…"
            nav.offRoute -> "OFF ROUTE · ${feet(nav.offBy)}"
            nav.finished -> "TRAIL COMPLETE"
            else -> nav.trail.name.uppercase(Locale.US).take(22)
        }
        text.color = if (nav.offRoute) 0xFFE5484D.toInt() else 0xFFD4E635.toInt()
        fitted(c, top, cx, h * 0.19f, 13 * density, w, h)
        text.color = Color.WHITE
        fitted(c, String.format(Locale.US, "%.2f mi left", nav.remaining / 1609.344), cx, h * 0.82f, 20 * density, w, h)
        text.color = 0xFF9AA3A0.toInt()
        val sub = buildList {
            nav.nextTurn?.let { (d, dir) -> if (d < 400) add("${dir.replaceFirstChar { it.uppercase() }} in ${feet(d)}") }
            nav.climbLeft().takeIf { it > 5 }?.let { add("${(it * 3.28084).toInt()} ft climb left") }
            if (nav.outAndBack && nav.progress < nav.turnaroundM) add("turn around in " + String.format(Locale.US, "%.2f mi", (nav.turnaroundM - nav.progress) / 1609.344))
        }.take(2).joinToString(" · ")
        fitted(c, sub, cx, h * 0.895f, 12 * density, w, h)
    }

    /** Draws text centered at (x, y), shrinking it so it fits inside the round screen at that height. */
    private fun fitted(c: Canvas, s: String, x: Float, y: Float, size: Float, w: Float, h: Float) {
        if (s.isEmpty()) return
        text.textSize = size
        val r = min(w, h) / 2
        val dy = kotlin.math.abs(y - text.textSize * 0.35f - h / 2) + text.textSize * 0.5f   // the text's edge farthest from center
        val room = 2 * kotlin.math.sqrt(maxOf(0f, r * r - dy * dy)) * 0.9f
        val need = text.measureText(s)
        if (need > room && need > 0) text.textSize = maxOf(size * 0.55f, size * room / need)
        c.drawText(s, x, y, text)
    }

    private fun feet(m: Double) = if (m < 300) "${(m * 3.28084 / 10).toInt() * 10} ft" else String.format(Locale.US, "%.2f mi", m / 1609.344)
}

/** A small elevation profile picture for the trail details screen. */
object TrailProfile {
    fun bitmap(t: Trail, w: Int, h: Int, density: Float): Bitmap? {
        val e = t.elevations ?: return null
        if (e.size < 2) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val lo = e.min(); val hi = e.max().coerceAtLeast(lo + 10)
        fun y(v: Double) = (h - 4 * density - (v - lo) / (hi - lo) * (h - 8 * density)).toFloat()
        val path = Path().apply { moveTo(0f, h.toFloat()); e.forEachIndexed { i, v -> lineTo(w * i / (e.size - 1f), y(v)) }; lineTo(w.toFloat(), h.toFloat()); close() }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5546C46E })
        val top = Path().apply { e.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(w * i / (e.size - 1f), y(v)) } }
        c.drawPath(top, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = 0xFF46C46E.toInt() })
        return bmp
    }
}
