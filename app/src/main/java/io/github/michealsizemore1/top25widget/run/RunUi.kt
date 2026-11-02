package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.michealsizemore1.top25widget.RunMath

/** Look and building blocks for the phone's Running screens. */
object RunUi {
    const val BG = 0xFF121A17.toInt()
    const val CARD = 0xFF1C2421.toInt()
    const val ACCENT = 0xFFD4E635.toInt()
    const val MUTED = 0xFF9AA3A0.toInt()
    const val DIM = 0xFF5E6663.toInt()
    const val HR = 0xFFFF6B6B.toInt()
    const val POWER = 0xFFFFB547.toInt()
    const val CC = 0xFF7B61FF.toInt()
    const val STRYD = 0xFF50555E.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, color: Int = Color.WHITE, size: Float = 15f, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 16).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) }
    }

    fun button(ctx: Context, label: String, color: Int, textColor: Int = Color.BLACK, onClick: () -> Unit) =
        text(ctx, label, textColor, 14f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, dp(ctx, 22).toFloat())
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun section(ctx: Context, title: String) = text(ctx, title, MUTED, 12f, true).apply {
        letterSpacing = 0.1f; setPadding(dp(ctx, 4), dp(ctx, 18), 0, 0)
    }

    /** A grid of big numbers with small labels, 3 per row. */
    fun statGrid(ctx: Context, stats: List<Triple<String, String, Int>>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        stats.chunked(3).forEach { rowStats ->
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
                rowStats.forEach { (value, label, color) ->
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(ctx, value, color, 22f, true))
                        addView(text(ctx, label, MUTED, 12f))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                repeat(3 - rowStats.size) { addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

    /** Time-in-zone bars. */
    fun zoneBars(ctx: Context, secs: org.json.JSONArray, names: List<String>, ranges: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val total = (0 until secs.length()).sumOf { secs.optInt(it) }.coerceAtLeast(1)
        for (i in 0 until minOf(5, secs.length())) {
            val sec = secs.optInt(i)
            val color = RunMath.ZONE_COLORS[i]
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
                addView(text(ctx, "Z${i + 1}", color, 13f, true), LinearLayout.LayoutParams(dp(ctx, 30), WRAP_CONTENT))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(ctx, "${names[i]}  ${ranges.getOrNull(i).orEmpty()}", MUTED, 11f))
                    addView(LinearLayout(ctx).apply {
                        addView(View(ctx).apply { background = rounded(color, dp(ctx, 4).toFloat()) },
                            LinearLayout.LayoutParams(0, dp(ctx, 10), sec.toFloat().coerceAtLeast(0.001f)))
                        addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 10), (total - sec).toFloat().coerceAtLeast(0.001f)))
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(ctx, RunMath.clock(sec) + "\n${sec * 100 / total}%", Color.WHITE, 12f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(ctx, 64), WRAP_CONTENT))
            })
        }
    }

    /**
     * Line chart of one value over the run. [bands] colors the background by zone
     * (lower bounds), so you can see which zone each part of the run was in.
     */
    fun chart(
        ctx: Context, xs: FloatArray, ys: FloatArray, color: Int,
        invert: Boolean = false, bands: IntArray? = null, label: (Float) -> String,
    ): View {
        val w = ctx.resources.displayMetrics.widthPixels - dp(ctx, 64)
        val h = dp(ctx, 140)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pts = xs.indices.filter { ys[it] > 0 }
        if (pts.size < 2) return text(ctx, "No data", DIM, 12f)
        // Ignore the extreme 2% so a spike doesn't flatten the chart.
        val sorted = pts.map { ys[it] }.sorted()
        var lo = sorted[(sorted.size * 0.02).toInt()]; var hi = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        if (hi - lo < 1e-3) { hi += 1; lo -= 1 }
        val pad = (hi - lo) * 0.1f; lo -= pad; hi += pad
        val left = dp(ctx, 40).toFloat(); val top = dp(ctx, 6).toFloat(); val bottom = h - dp(ctx, 6).toFloat()
        val xMax = xs.last().coerceAtLeast(1f)
        fun px(x: Float) = left + (w - left) * x / xMax
        fun py(y: Float): Float { val f = ((y - lo) / (hi - lo)).coerceIn(0f, 1f); return if (invert) top + (bottom - top) * f else bottom - (bottom - top) * f }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        bands?.forEachIndexed { i, b ->
            val next = bands.getOrNull(i + 1)?.toFloat() ?: Float.MAX_VALUE
            val y1 = py(b.toFloat()); val y2 = py(next)
            paint.color = RunMath.ZONE_COLORS[i]; paint.alpha = 40
            c.drawRect(left, minOf(y1, y2), w.toFloat(), maxOf(y1, y2), paint)
        }
        paint.color = DIM; paint.alpha = 255; paint.textSize = dp(ctx, 10).toFloat()
        listOf(hi - pad, (hi + lo) / 2, lo + pad).forEach { v -> c.drawText(label(v), 0f, py(v) + dp(ctx, 4), paint) }

        val path = Path()
        // Light smoothing: 5-point average.
        pts.forEachIndexed { k, i ->
            val win = pts.subList(maxOf(0, k - 2), minOf(pts.size, k + 3)).map { ys[it] }
            val y = py(win.average().toFloat())
            if (k == 0) path.moveTo(px(xs[i]), y) else path.lineTo(px(xs[i]), y)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(ctx, 2).toFloat(); paint.color = color
        c.drawPath(path, paint)
        return ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
    }

    /** The GPS route, drawn as a line (no map tiles). */
    fun route(ctx: Context, track: org.json.JSONArray): View {
        val w = ctx.resources.displayMetrics.widthPixels - dp(ctx, 64)
        val lats = (0 until track.length()).map { track.getJSONArray(it).optDouble(0) }
        val lons = (0 until track.length()).map { track.getJSONArray(it).optDouble(1) }
        val midLat = Math.toRadians(lats.average())
        val xs = lons.map { it * Math.cos(midLat) }
        val minX = xs.min(); val maxX = xs.max(); val minY = lats.min(); val maxY = lats.max()
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val h = (w * ((maxY - minY) / span).coerceIn(0.4, 1.0)).toInt() + dp(ctx, 20)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = dp(ctx, 10).toFloat()
        val scale = (minOf((w - 2 * m) / (maxX - minX).coerceAtLeast(1e-9), (h - 2 * m) / (maxY - minY).coerceAtLeast(1e-9))).toFloat()
        fun x(i: Int) = m + ((xs[i] - minX) * scale).toFloat()
        fun y(i: Int) = h - m - ((lats[i] - minY) * scale).toFloat()
        val path = Path().apply { moveTo(x(0), y(0)); for (i in 1 until xs.size) lineTo(x(i), y(i)) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(ctx, 3).toFloat(); color = ACCENT; strokeJoin = Paint.Join.ROUND }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF46C46E.toInt(); c.drawCircle(x(0), y(0), dp(ctx, 5).toFloat(), p)
        p.color = 0xFFE5484D.toInt(); c.drawCircle(x(xs.size - 1), y(xs.size - 1), dp(ctx, 5).toFloat(), p)
        return ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
    }
}
