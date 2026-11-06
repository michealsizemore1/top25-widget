package io.github.michealsizemore1.beyondstride.run

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import io.github.michealsizemore1.beyondstride.CoroutineTileService
import io.github.michealsizemore1.beyondstride.RunMath
import io.github.michealsizemore1.beyondstride.TileUi
import io.github.michealsizemore1.beyondstride.Workouts
import java.util.Calendar
import java.util.Locale

/** Running tile: today's workout, this week's miles, and a Start button (one tap starts the run). */
class RunTileService : CoroutineTileService() {
    companion object {
        const val EXTRA_START = "tileStart"
        fun refresh(ctx: android.content.Context) {
            try { TileService.getUpdater(ctx).requestUpdate(RunTileService::class.java) } catch (e: Exception) { }
        }
    }

    private fun open(id: String, start: Boolean): ModifiersBuilders.Clickable {
        val a = ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(RunActivity::class.java.name)
        if (start) a.addKeyToExtraMapping(EXTRA_START, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
        return ModifiersBuilders.Clickable.Builder().setId(id)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(a.build()).build()).build()
    }

    private fun week(): Pair<Double, Int> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }
        var m = 0.0; var n = 0
        RunStore.list(this).forEach { f ->
            if ((f.nameWithoutExtension.toLongOrNull() ?: 0L) < cal.timeInMillis) return@forEach
            val head = f.bufferedReader().use { r -> CharArray(400).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } }
            Regex("\"distance\":([0-9.]+)").find(head)?.groupValues?.get(1)?.toDoubleOrNull()?.let { m += it; n++ }
        }
        return m to n
    }

    private fun startButton(label: String, color: Int, start: Boolean): LayoutElement =
        Box.Builder().setWidth(dp(120f)).setHeight(dp(40f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build()).build())
                .setClickable(open(if (start) "start" else "open_run", start)).build())
            .addContent(TileUi.text(label, 16f, 0xFF000000.toInt(), bold = true)).build()

    override suspend fun buildTile(): TileBuilders.Tile {
        val p = getSharedPreferences("run", 0)
        val col = LayoutElementBuilders.Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileUi.text("BEYONDSTRIDE", 12f, TileUi.ACCENT, bold = true))
            .addContent(TileUi.spacer(4f))

        if (Run.isActive) {
            col.addContent(TileUi.text(Run.miles(Run.distance) + " mi", 22f, TileUi.WHITE, bold = true))
                .addContent(TileUi.text(RunMath.clock(Run.movingSeconds) + " · run in progress", 11f, TileUi.MUTED))
                .addContent(TileUi.spacer(8f))
                .addContent(startButton("Open", TileUi.ACCENT, false))
            return TileUi.tile(TileUi.screen(col.build()), 60_000L)
        }

        // Today's workout (same one the app picks).
        val w = WatchWorkouts.today(this)
        val name = w?.optString("name")?.takeIf { it.isNotBlank() } ?: "Free run"
        val meters = w?.let { Workouts.plannedMeters(it) } ?: 0
        val sub = buildList {
            if (meters > 0) add(String.format(Locale.US, "%.1f mi", meters / RunMath.METERS_PER_MILE))
            val mode = p.getString("hrCap", "easy")
            if (mode != "off" && (w == null && mode == "all" || w != null && Regex("easy|recovery|long", RegexOption.IGNORE_CASE).containsMatchIn(name))) {
                val z = p.getInt("hrCapZone", 2)
                val b = RunMath.hrBounds(p.getFloat("hrMax", 185f).toDouble(), p.getFloat("hrRest", 43.5f).toDouble())
                add("HR under ${if (z in 1..4) b[z] - 1 else 185}")
            }
        }.joinToString(" · ")
        col.addContent(Box.Builder().setWidth(expand()).setHeight(wrap())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open("open_today", false)).build())
            .addContent(TileUi.column(
                TileUi.text("TODAY", 10f, TileUi.MUTED, bold = true),
                TileUi.text(name, 15f, TileUi.WHITE, bold = true, maxLines = 2),
                TileUi.text(sub, 11f, 0xFF46C46E.toInt()),
            )).build())
        col.addContent(TileUi.spacer(8f))
        col.addContent(startButton("START", TileUi.ACCENT, true))
        col.addContent(TileUi.spacer(8f))
        val (m, n) = week()
        col.addContent(TileUi.text(String.format(Locale.US, "%.1f mi · %d run%s this week", m / RunMath.METERS_PER_MILE, n, if (n == 1) "" else "s"), 11f, TileUi.MUTED))
        if (p.getBoolean("liveOn", false)) col.addContent(TileUi.text("● LiveTrack on", 10f, 0xFF4FA3E0.toInt(), bold = true))
        return TileUi.tile(TileUi.screen(col.build()), 30 * 60_000L)
    }
}
