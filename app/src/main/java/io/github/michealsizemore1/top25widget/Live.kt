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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Live-game details shown under each in-progress game (the "Live Drive" panel):
 * possession, down & distance, ball spot, timeouts, score by quarter, last play,
 * and win probability. Comes from the scoreboard's `situation` block.
 */
data class LiveInfo(
    val possessionId: String,
    val possessionText: String,   // "STAN 25"
    val downText: String,         // "1st & 10"
    val ballX: Int?,              // 0..100 measured from the AWAY team's goal line (left)
    val firstDownX: Int?,
    val redZone: Boolean,
    val awayTimeouts: Int,        // -1 = unknown
    val homeTimeouts: Int,
    val drive: String,            // "4 plays, 30 yards, 2:01"
    val lastPlay: String,
    val athlete: String,          // "Nick Keller · PK · #98"
    val homeWinPct: Double?,      // 0..1
    val awayColor: Int,
    val homeColor: Int,
    val awayLines: List<String>,
    val homeLines: List<String>,
) {
    companion object {
        private val DEFAULT_AWAY = 0xFF8A1C1C.toInt()
        private val DEFAULT_HOME = 0xFF1C4E8A.toInt()

        private fun color(hex: String?, fallback: Int): Int = try {
            val h = hex.orEmpty().trim().removePrefix("#")
            if (h.length == 6) (0xFF000000 or h.toLong(16)).toInt() else fallback
        } catch (e: Exception) {
            fallback
        }

        private fun lines(c: JSONObject): List<String> {
            val a = c.optJSONArray("linescores") ?: return emptyList()
            return (0 until a.length()).map { i ->
                val o = a.optJSONObject(i)
                o?.optString("displayValue")?.takeIf { it.isNotBlank() }
                    ?: o?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()?.toString()
                    ?: "-"
            }
        }

        /** Build from a scoreboard competition. [awayC]/[homeC] are the raw competitor objects. */
        fun from(comp: JSONObject, awayC: JSONObject, homeC: JSONObject, away: TeamLine, home: TeamLine): LiveInfo {
            val sit = comp.optJSONObject("situation") ?: JSONObject()
            val possessionId = sit.optString("possession")
            val possessionText = sit.optString("possessionText")
            val down = sit.optString("shortDownDistanceText").ifBlank {
                sit.optString("downDistanceText").substringBefore(" at ")
            }

            // Ball spot: "STAN 25" -> 25 yards from Stanford's goal line.
            var ballX: Int? = null
            Regex("^(\\S+)\\s+(\\d{1,2})$").find(possessionText.trim())?.let { m ->
                val side = m.groupValues[1]
                val yd = m.groupValues[2].toInt()
                ballX = when {
                    side.equals(away.abbr, true) -> yd
                    side.equals(home.abbr, true) -> 100 - yd
                    else -> null
                }
            }
            if (ballX == null && possessionText.trim() == "50") ballX = 50

            // First-down marker: the offense moves toward the other team's goal.
            val distance = sit.optInt("distance", 0)
            val goalToGo = down.contains("goal", true)
            val towardHome = possessionId.isNotBlank() && possessionId == away.id
            val firstDownX = ballX?.takeIf { distance > 0 && !goalToGo && possessionId.isNotBlank() }?.let {
                (if (towardHome) it + distance else it - distance).coerceIn(0, 100)
            }

            val last = sit.optJSONObject("lastPlay")
            val ath = last?.optJSONArray("athletesInvolved")?.optJSONObject(0)
            val athlete = ath?.let {
                listOf(
                    it.optString("displayName").ifBlank { it.optString("shortName") },
                    it.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                    it.optString("jersey").takeIf { j -> j.isNotBlank() }?.let { j -> "#$j" }.orEmpty(),
                ).filter { s -> s.isNotBlank() }.joinToString(" · ")
            }.orEmpty()
            val prob = last?.optJSONObject("probability")?.optDouble("homeWinPercentage")?.takeIf { !it.isNaN() }

            return LiveInfo(
                possessionId = possessionId,
                possessionText = possessionText,
                downText = down,
                ballX = ballX,
                firstDownX = firstDownX,
                redZone = sit.optBoolean("isRedZone", false),
                awayTimeouts = sit.optInt("awayTimeouts", -1),
                homeTimeouts = sit.optInt("homeTimeouts", -1),
                drive = last?.optJSONObject("drive")?.optString("description").orEmpty(),
                lastPlay = last?.optString("text").orEmpty(),
                athlete = athlete,
                homeWinPct = prob,
                awayColor = color(awayC.optJSONObject("team")?.optString("color"), DEFAULT_AWAY),
                homeColor = color(homeC.optJSONObject("team")?.optString("color"), DEFAULT_HOME),
                awayLines = lines(awayC),
                homeLines = lines(homeC),
            )
        }

        fun toJson(l: LiveInfo) = JSONObject().apply {
            put("pos", l.possessionId); put("posText", l.possessionText); put("down", l.downText)
            put("ballX", l.ballX ?: -1); put("fdX", l.firstDownX ?: -1); put("rz", l.redZone)
            put("ato", l.awayTimeouts); put("hto", l.homeTimeouts); put("drive", l.drive)
            put("last", l.lastPlay); put("ath", l.athlete); put("hwp", l.homeWinPct ?: -1.0)
            put("ac", l.awayColor); put("hc", l.homeColor)
            put("al", JSONArray(l.awayLines)); put("hl", JSONArray(l.homeLines))
        }

        fun fromJson(o: JSONObject?): LiveInfo? {
            if (o == null) return null
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
            return LiveInfo(
                possessionId = o.optString("pos"), possessionText = o.optString("posText"),
                downText = o.optString("down"),
                ballX = o.optInt("ballX", -1).takeIf { it >= 0 }, firstDownX = o.optInt("fdX", -1).takeIf { it >= 0 },
                redZone = o.optBoolean("rz"), awayTimeouts = o.optInt("ato", -1), homeTimeouts = o.optInt("hto", -1),
                drive = o.optString("drive"), lastPlay = o.optString("last"), athlete = o.optString("ath"),
                homeWinPct = o.optDouble("hwp", -1.0).takeIf { it >= 0 },
                awayColor = o.optInt("ac", DEFAULT_AWAY), homeColor = o.optInt("hc", DEFAULT_HOME),
                awayLines = strs(o.optJSONArray("al")), homeLines = strs(o.optJSONArray("hl")),
            )
        }
    }
}

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
