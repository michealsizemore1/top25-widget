package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved runs on the watch: a list, then each run's totals and mile splits. */
class RunHistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showList()
    }

    private fun showList() {
        val files = RunStore.list(this)
        val col = RunViews.column(this)
        col.addView(RunViews.label(this, "History", Color.WHITE, 18f, true))
        if (files.isEmpty()) {
            col.addView(RunViews.label(this, "No runs yet", RunViews.MUTED, 14f).apply { setPadding(0, RunViews.dp(this@RunHistoryActivity, 10), 0, 0) })
        }
        files.forEach { f ->
            val json = RunStore.read(f) ?: return@forEach
            // Same card as the phone's run list: title, date, miles / time / pace, HR / power.
            col.addView(RunViews.card(this).apply {
                addView(RunViews.label(context, RunViews.title(json), Color.WHITE, 15f, true))
                addView(RunViews.label(context, RunViews.date(json.optLong("start")), RunViews.MUTED, 10f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, RunViews.dp(context, 4), 0, 0)
                    fun stat(v: String, l: String, c: Int) = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(RunViews.label(context, v, c, 14f, true)); addView(RunViews.label(context, l, RunViews.MUTED, 9f))
                    }
                    addView(stat(Run.miles(json.optDouble("distance")), "miles", RunViews.ACCENT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(stat(Run.clock(json.optInt("movingSeconds")), "time", Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(stat(Run.paceFor(json.optInt("movingSeconds"), json.optDouble("distance")), "pace", Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
                val extra = listOfNotNull(
                    json.optInt("avgHr").takeIf { it > 0 }?.let { "$it bpm" },
                    json.optInt("avgPower").takeIf { it > 0 }?.let { "$it W" },
                    json.optJSONObject("workout")?.optString("name"),
                ).joinToString(" · ")
                if (extra.isNotEmpty()) addView(RunViews.label(context, extra, RunViews.MUTED, 10f).apply { setPadding(0, RunViews.dp(context, 3), 0, 0) })
                setOnClickListener { showRun(f, json) }
            })
        }
        setContentView(RunViews.scroll(this, col))
    }

    private fun showRun(file: File, json: JSONObject) {
        val view = RunViews.summary(this, json, onDone = { showList() }, onDelete = {
            RunStore.delete(file)
            RunSync.deleteEverywhere(this, file.nameWithoutExtension.toLongOrNull() ?: 0L)   // phone too
            showList()
        })
        setContentView(view)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back from a run's details returns to the list first.
        if (window.decorView.findViewWithTag<View>("summary") != null) showList()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}

/** Shared view helpers for the run screens. */
object RunViews {
    const val ACCENT = 0xFFD4E635.toInt()
    const val MUTED = 0xFF9AA3A0.toInt()
    const val CARD = 0xFF1C2421.toInt()
    const val PAUSE_COLOR = 0xFFFFB547.toInt()
    const val STOP = 0xFFE5484D.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** "Morning Run", "Evening Run"… — the same names the phone uses. */
    fun title(json: JSONObject): String {
        // Planned workout's name (e.g. "Easy Run 7 Miles"), same as in your plan.
        json.optJSONObject("workout")?.optString("name")?.takeIf { it.isNotBlank() && it != "Free run" }?.let { w ->
            json.optJSONObject("trail")?.optString("name")?.takeIf { it.isNotBlank() }?.let { return "$w · $it" }
            return w
        }
        val h = java.util.Calendar.getInstance().apply { timeInMillis = json.optLong("start") }.get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 4..11 -> "Morning Run"; in 12..16 -> "Afternoon Run"; in 17..20 -> "Evening Run"; else -> "Night Run" }
    }

    fun date(ms: Long): String = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(ms))

    /** Text size setting: small text is scaled up; big numbers stay as they are so they still fit. */
    fun scaled(ctx: Context, size: Float): Float {
        val k = ctx.getSharedPreferences("run", 0).getFloat("textScale", 1.25f).coerceIn(1f, 1.6f)
        return if (size < 20f) minOf(size * k, 22f) else size
    }

    fun label(ctx: Context, text: String, color: Int, size: Float, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, scaled(ctx, size))
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val w = ctx.resources.displayMetrics.widthPixels
        setPadding(w / 8, w / 7, w / 8, w / 4)
    }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(ctx, 14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 6) }
    }

    fun scroll(ctx: Context, content: View) = ScrollView(ctx).apply {
        setBackgroundColor(0xFF121A17.toInt())   // same background as the phone app
        isVerticalScrollBarEnabled = false
        addView(content)
        requestFocus()   // bezel scrolls
    }

    /** Time in each zone as colored bars. */
    private fun zoneCard(ctx: Context, col: LinearLayout, title: String, zs: org.json.JSONArray?, names: List<String>) {
        if (zs == null) return
        val total = (0 until zs.length()).sumOf { zs.optInt(it) }
        if (total <= 0) return
        col.addView(label(ctx, title, MUTED, 12f, true).apply { setPadding(0, dp(ctx, 10), 0, 0) })
        col.addView(card(ctx).apply {
            for (i in 0 until minOf(5, zs.length())) {
                val sec = zs.optInt(i)
                val color = Run.ZONE_COLORS[i]
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
                    addView(label(ctx, "Z${i + 1}", color, 12f, true).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(ctx, 26), WRAP_CONTENT)
                    })
                    addView(LinearLayout(ctx).apply {
                        addView(View(ctx).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(ctx, 3).toFloat() } },
                            LinearLayout.LayoutParams(0, dp(ctx, 8), sec.toFloat().coerceAtLeast(0.001f)))
                        addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 8), (total - sec).toFloat().coerceAtLeast(0.001f)))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(label(ctx, Run.clock(sec), Color.WHITE, 12f).apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(dp(ctx, 46), WRAP_CONTENT)
                    })
                })
            }
            addView(label(ctx, names.take(5).mapIndexed { i, n -> "Z${i + 1} $n" }.joinToString(" · "), MUTED, 9f).apply {
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        })
    }

    /** Totals, averages, and mile splits for a finished run. */
    fun summary(ctx: Context, json: JSONObject, onDone: () -> Unit, onDelete: (() -> Unit)? = null): View {
        val col = column(ctx)
        val sec = json.optInt("movingSeconds")
        val meters = json.optDouble("distance")
        col.addView(label(ctx, title(json), Color.WHITE, 18f, true))
        if (json.optBoolean("recovered")) col.addView(label(ctx, "recovered after a restart", PAUSE_COLOR, 10f))
        col.addView(label(ctx, date(json.optLong("start")), MUTED, 11f))
        json.optJSONObject("workout")?.let { w ->
            col.addView(label(ctx, w.optString("name") + if (w.optBoolean("completed")) " ✓" else " (partial)", ACCENT, 12f, true))
        }
        col.addView(label(ctx, Run.miles(meters), Color.WHITE, 40f, true))
        col.addView(label(ctx, "miles", MUTED, 11f))

        fun stat(name: String, value: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(ctx, name, MUTED, 13f).apply { gravity = Gravity.START; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(label(ctx, value, Color.WHITE, 14f, true).apply { gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) })
        }
        col.addView(card(ctx).apply {
            addView(stat("Time", Run.clock(sec)))
            addView(stat("Avg pace", Run.paceFor(sec, meters) + " /mi"))
            json.optInt("avgHr").takeIf { it > 0 }?.let { addView(stat("Avg HR", "$it bpm")) }
            json.optInt("maxHr").takeIf { it > 0 }?.let { addView(stat("Max HR", "$it bpm")) }
            json.optInt("avgPower").takeIf { it > 0 }?.let { addView(stat("Avg power", "$it W")) }
            json.optInt("avgCadence").takeIf { it > 0 }?.let { addView(stat("Avg cadence", "$it spm")) }
            addView(stat("Source", if (json.optString("source") == "gps") "GPS" else "Stryd"))
            if (json.has("hrSource")) addView(stat("Heart rate", if (json.optString("hrSource") == "strap") "Chest strap" else "Wrist"))
        })

        zoneCard(ctx, col, "HEART-RATE ZONES", json.optJSONArray("hrZoneSeconds"), Run.HR_ZONE_NAMES)
        io.github.michealsizemore1.top25widget.Decoupling.of(json)?.let { d ->
            val m = d.main ?: return@let
            col.addView(label(ctx, "AEROBIC DECOUPLING", MUTED, 12f, true).apply { setPadding(0, dp(ctx, 10), 0, 0) })
            col.addView(card(ctx).apply {
                addView(label(ctx, io.github.michealsizemore1.top25widget.Decoupling.text(m), io.github.michealsizemore1.top25widget.Decoupling.color(m), 26f, true))
                addView(label(ctx, io.github.michealsizemore1.top25widget.Decoupling.rating(m) + if (!d.steady) " · uneven effort" else "", Color.WHITE, 12f, true))
                d.pwHr?.let { addView(stat("Power : HR", io.github.michealsizemore1.top25widget.Decoupling.text(it))) }
                d.paHr?.let { addView(stat("Pace : HR", io.github.michealsizemore1.top25widget.Decoupling.text(it))) }
                addView(stat("Heart rate", "${d.hr1} → ${d.hr2}"))
                addView(label(ctx, "Under 5% = strong aerobic base. Best judged on steady easy/long runs.", MUTED, 9f))
            })
        }
        json.optDouble("avgStride").takeIf { !it.isNaN() && it > 0 }?.let { st ->
            col.addView(card(ctx).apply { addView(stat("Avg stride", String.format(Locale.US, "%.2f m", st))) })
        }

        // Power: normalized power, intensity, stress, and time in each Stryd zone.
        val np = json.optInt("np"); val cp = json.optInt("cp")
        if (np > 0 || cp > 0) {
            col.addView(label(ctx, "POWER", MUTED, 12f, true).apply { setPadding(0, dp(ctx, 10), 0, 0) })
            col.addView(card(ctx).apply {
                if (np > 0) addView(stat("Normalized", "$np W"))
                json.optInt("maxPower").takeIf { it > 0 }?.let { addView(stat("Max power", "$it W")) }
                if (cp > 0) {
                    addView(stat("Critical Power", "$cp W"))
                    addView(stat("Intensity", String.format(Locale.US, "%.2f", json.optDouble("intensity"))))
                    addView(stat("Stress (est.)", "${json.optInt("stress")}"))
                }
            })
        }
        zoneCard(ctx, col, "POWER ZONES", json.optJSONArray("zoneSeconds"), Run.ZONES.map { it.name })

        val laps = json.optJSONArray("laps")
        if (laps != null && laps.length() > 0) {
            col.addView(label(ctx, "SPLITS", MUTED, 12f, true).apply { setPadding(0, dp(ctx, 10), 0, 0) })
            col.addView(card(ctx).apply {
                for (i in 0 until laps.length()) {
                    val l = laps.getJSONObject(i)
                    val lsec = l.optInt("sec"); val lm = l.optDouble("m")
                    addView(stat(
                        (l.optString("name").takeIf { it.isNotBlank() } ?: "${l.optInt("n")}") +
                            if (lm < Run.METERS_PER_MILE * 0.95) " (${Run.miles(lm)})" else "",
                        Run.paceFor(lsec, lm) +
                            (l.optInt("pw").takeIf { it > 0 }?.let { " · ${it}W" } ?: "") +
                            (l.optInt("hr").takeIf { it > 0 }?.let { " · $it" } ?: ""),
                    ))
                }
            })
        }

        json.optJSONArray("track")?.takeIf { it.length() >= 10 }?.let {
            val id = "run${json.optLong("start")}"
            col.addView(label(ctx, if (io.github.michealsizemore1.top25widget.Courses.isSaved(ctx, id)) "★ Saved as course" else "☆ Save route as course", 0xFFD4E635.toInt(), 13f, true).apply {
                setPadding(0, dp(ctx, 12), 0, 0)
                setOnClickListener {
                    if (io.github.michealsizemore1.top25widget.Courses.isSaved(ctx, id)) return@setOnClickListener
                    val c = io.github.michealsizemore1.top25widget.Courses.fromRun(json, title(json))
                    if (c == null) text = "Not enough GPS points" else { io.github.michealsizemore1.top25widget.Courses.save(ctx, c, "watch"); text = "★ Saved as course" }
                }
            })
        }
        col.addView(label(ctx, "Done", Color.BLACK, 16f, true).apply {
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(ctx, 24).toFloat() }
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 12) }
            setOnClickListener { onDone() }
        })
        if (onDelete != null) {
            var armed = false
            col.addView(label(ctx, "Delete run", STOP, 13f, true).apply {
                setPadding(0, dp(ctx, 12), 0, 0)
                setOnClickListener {
                    if (armed) onDelete() else { armed = true; text = "Tap again to delete" }
                }
            })
        }
        return scroll(ctx, col).apply { tag = "summary" }
    }
}
