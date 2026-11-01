package io.github.michealsizemore1.top25widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import java.util.Locale

// ====================================================================== drawing

private const val CARD_BG = 0xFF0D0D0D.toInt()

/** The field strip: end zones in team colors, yard numbers, ball, and yellow first-down line. */
fun fieldBitmap(l: LiveInfo, awayAbbr: String, homeAbbr: String, scale: Float = 1f, bg: Int = CARD_BG): Bitmap {
    val w = 480f; val h = 64f
    val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(scale, scale)
    c.drawColor(bg)
    c.save()
    c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), 10f, 10f, Path.Direction.CW) })

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF1C2A23.toInt(); c.drawRect(0f, 0f, w, h, paint)
    val ez = w * 0.08f
    val fieldW = w - 2 * ez
    fun xOf(yd: Int) = ez + fieldW * yd / 100f

    // Red zone tint when inside the 20.
    if (l.redZone && l.ballX != null) {
        paint.color = 0x33FF3B3B
        if (l.possessionId.isNotBlank() && l.ballX >= 50) c.drawRect(xOf(80), 0f, xOf(100), h, paint)
        else c.drawRect(xOf(0), 0f, xOf(20), h, paint)
    }

    paint.color = l.awayColor; c.drawRect(0f, 0f, ez, h, paint)
    paint.color = l.homeColor; c.drawRect(w - ez, 0f, w, h, paint)

    // Yard lines and numbers.
    paint.strokeWidth = 1.5f
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A948F.toInt(); textSize = 14f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    for (i in 1..9) {
        val x = xOf(i * 10)
        paint.color = 0xFF2E3D36.toInt(); c.drawLine(x, 0f, x, h, paint)
        val n = if (i <= 5) i * 10 else (10 - i) * 10
        c.drawText("$n", x, h / 2 + 5f, text)
    }

    // Team abbreviations stacked in each end zone.
    fun endZoneText(abbr: String, cx: Float) {
        val letters = abbr.uppercase(Locale.getDefault()).take(5)
        if (letters.isEmpty()) return
        val size = minOf(13f, (h - 6f) / letters.length)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = size; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val top = (h - size * letters.length) / 2 + size * 0.85f
        letters.forEachIndexed { i, ch -> c.drawText(ch.toString(), cx, top + i * size, p) }
    }
    endZoneText(awayAbbr, ez / 2)
    endZoneText(homeAbbr, w - ez / 2)

    // Drive trail from where the drive started to the ball.
    val start = l.driveStartX
    if (start != null && l.ballX != null && start != l.ballX) {
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B7370.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(xOf(start), h / 2, xOf(l.ballX), h / 2, trail)
    }

    // First-down line, then the ball.
    l.firstDownX?.let { fd ->
        paint.color = 0xFFF5D33A.toInt(); paint.strokeWidth = 3.5f
        val x = xOf(fd); c.drawLine(x, 0f, x, h, paint)
    }
    l.ballX?.let { b ->
        val x = xOf(b); val cy = h / 2
        paint.color = 0xFF9A5530.toInt(); paint.style = Paint.Style.FILL
        c.drawOval(RectF(x - 13f, cy - 8f, x + 13f, cy + 8f), paint)
        paint.color = 0xFFFFFFFF.toInt(); paint.strokeWidth = 1.6f
        c.drawLine(x - 5f, cy, x + 5f, cy, paint)
        for (k in -1..1) c.drawLine(x + k * 3f, cy - 2.5f, x + k * 3f, cy + 2.5f, paint)
    }
    c.restore()
    return bmp
}

/** Win-probability bar: away share on the left in the away color. */
fun winProbBitmap(l: LiveInfo, scale: Float = 1f, bg: Int = CARD_BG): Bitmap {
    val w = 480f; val h = 10f
    val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(scale, scale)
    c.drawColor(bg)
    val awayShare = (1.0 - (l.homeWinPct ?: 0.5)).coerceIn(0.0, 1.0).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    c.save()
    c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), 5f, 5f, Path.Direction.CW) })
    paint.color = l.awayColor; c.drawRect(0f, 0f, w * awayShare, h, paint)
    paint.color = l.homeColor; c.drawRect(w * awayShare, 0f, w, h, paint)
    c.restore()
    return bmp
}

// ====================================================================== widget panel

private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)

@Composable
fun LivePanel(game: Game, l: LiveInfo) {
    val away = game.away.abbr.ifBlank { game.away.name }
    val home = game.home.abbr.ifBlank { game.home.name }

    // Drive header + down & distance
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "LIVE DRIVE", style = style(Palette.accent, 12.sp, bold = true),
                modifier = GlanceModifier.defaultWeight())
            if (l.drive.isNotBlank()) Text(text = l.drive, style = style(Palette.muted, 12.sp), maxLines = 1)
        }
        val spot = l.possessionText.takeIf { it.isNotBlank() }?.let { " · ON THE ${it.uppercase(Locale.getDefault())}" }.orEmpty()
        if (l.downText.isNotBlank() || spot.isNotBlank()) {
            Text(text = l.downText + spot, style = style(size = 16.sp, bold = true), maxLines = 1,
                modifier = GlanceModifier.padding(top = 2.dp))
        }
    }

    // Field
    if (l.ballX != null) {
        Image(
            provider = ImageProvider(fieldBitmap(l, away, home)),
            contentDescription = "Ball on the ${l.possessionText}",
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxWidth().height(44.dp).padding(top = 6.dp),
        )
    }

    // Score by quarter
    val periods = maxOf(l.awayLines.size, l.homeLines.size)
    if (periods > 0) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
            QuarterRow("", (0 until periods).map { p -> if (p < 4) "${p + 1}" else if (p == 4) "OT" else "${p - 3}OT" }, "T", header = true)
            QuarterRow(away, (0 until periods).map { l.awayLines.getOrElse(it) { "-" } }, game.away.score, header = false)
            QuarterRow(home, (0 until periods).map { l.homeLines.getOrElse(it) { "-" } }, game.home.score, header = false)
        }
    }

    // Last play
    if (l.lastPlay.isNotBlank()) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(text = l.lastPlay, style = style(Palette.muted, 14.sp), maxLines = 3)
            if (l.athlete.isNotBlank()) Text(text = "▸ ${l.athlete}", style = style(size = 13.sp, bold = true), maxLines = 1)
        }
    }

    // Win probability
    l.homeWinPct?.let { hwp ->
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
            Image(
                provider = ImageProvider(winProbBitmap(l)),
                contentDescription = "Win probability",
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            )
            Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(text = "$away ${pct(1 - hwp)}", style = style(size = 12.sp, bold = true))
                Text(text = "WIN PROBABILITY", style = style(Palette.dim, 11.sp, align = TextAlign.Center),
                    modifier = GlanceModifier.defaultWeight())
                Text(text = "$home ${pct(hwp)}", style = style(size = 12.sp, bold = true, align = TextAlign.End))
            }
        }
    }
}

@Composable
private fun QuarterRow(label: String, values: List<String>, total: String, header: Boolean) {
    val color = if (header) Palette.dim else Palette.text
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = style(color, 13.sp, bold = !header), maxLines = 1, modifier = GlanceModifier.width(52.dp))
        values.take(6).forEach { v ->
            Text(text = v, style = style(color, 13.sp, align = TextAlign.Center), modifier = GlanceModifier.defaultWeight())
        }
        Text(text = total, style = style(if (header) Palette.dim else Palette.accent, 13.sp, bold = true, align = TextAlign.End),
            modifier = GlanceModifier.width(32.dp))
    }
}

/** Timeouts remaining for a team, or -1. */
fun LiveInfo.timeoutsFor(team: TeamLine, game: Game): Int =
    if (team.id == game.away.id) awayTimeouts else homeTimeouts
