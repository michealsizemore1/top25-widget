package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.michealsizemore1.top25widget.Workouts
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Training plan (by date) and workout library. Everything here is sent to the watch. */
class WorkoutsActivity : Activity() {

    private var tab = "plan"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = intent.getStringExtra("tab") ?: "plan"
    }

    override fun onResume() { super.onResume(); render() }

    private fun outline(w: JSONObject) = Workouts.outline(w, RunSettings.hrMax(this), RunSettings.hrRest(this), RunSettings.cp(this))

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        col.addView(RunUi.text(this, "Workouts", Color.WHITE, 28f, true))

        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            listOf("plan" to "Plan", "library" to "Library").forEachIndexed { i, (key, name) ->
                addView(RunUi.button(ctx, name, if (tab == key) RunUi.ACCENT else RunUi.CARD, if (tab == key) Color.BLACK else Color.WHITE) {
                    tab = key; render()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i == 0) marginEnd = dp(8) })
            }
        })

        if (tab == "plan") renderPlan(col) else renderLibrary(col)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun renderPlan(col: LinearLayout) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val plan = WorkoutStore.plan(this)
        col.addView(RunUi.text(this,
            "Today's workout loads on the watch automatically. To bring in your Command Center plan, open it and tap " +
            "“Send plan to Running app” under Upcoming / Active Training Plan.", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        col.addView(RunUi.button(this, "+ Add a workout to a date", RunUi.CARD, Color.WHITE) { pickDateThenWorkout() }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        })

        val cal = Calendar.getInstance()
        val day = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        var shown = 0
        var weekHeader = ""
        repeat(120) {
            val key = WorkoutStore.dayFmt.format(cal.time)
            val w = plan.optJSONObject(key)
            if (w != null) {
                val wk = SimpleDateFormat("'Week of' MMM d", Locale.getDefault()).format(Calendar.getInstance().apply {
                    time = cal.time; set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.time)
                if (wk != weekHeader) { weekHeader = wk; col.addView(RunUi.section(ctx, wk.uppercase())) }
                col.addView(dayCard(key, if (shown == 0 && it == 0) "Today" else day.format(cal.time), w))
                shown++
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (shown == 0) col.addView(RunUi.text(this, "Nothing planned yet.", RunUi.DIM, 14f).apply { setPadding(dp(4), dp(16), 0, 0) })
    }

    private fun dayCard(date: String, label: String, w: JSONObject) = RunUi.card(this).apply {
        val ctx = this@WorkoutsActivity
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(RunUi.text(ctx, label, RunUi.ACCENT, 13f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val m = Workouts.plannedMeters(w)
            if (m > 0) addView(RunUi.text(ctx, String.format(Locale.US, "%.1f mi", m / 1609.344), RunUi.MUTED, 13f))
        })
        addView(RunUi.text(ctx, w.optString("name"), Color.WHITE, 17f, true))
        addView(RunUi.text(ctx, outline(w).joinToString("\n"), RunUi.MUTED, 13f))
        setOnClickListener {
            startActivity(Intent(ctx, WorkoutEditActivity::class.java).putExtra("date", date))
        }
        setOnLongClickListener {
            AlertDialog.Builder(ctx).setTitle(label)
                .setItems(arrayOf("Edit", "Replace with a library workout", "Make it a rest day")) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(ctx, WorkoutEditActivity::class.java).putExtra("date", date))
                        1 -> chooseLibrary { lw -> WorkoutStore.setDay(ctx, date, JSONObject(lw.toString()).put("edited", true)); render() }
                        2 -> { WorkoutStore.setDay(ctx, date, null); render() }
                    }
                }.show()
            true
        }
    }

    private fun renderLibrary(col: LinearLayout) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        col.addView(RunUi.button(this, "+ New workout", RunUi.ACCENT) {
            startActivity(Intent(ctx, WorkoutEditActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) } })
        col.addView(RunUi.text(this, "Tap to edit · hold to schedule or delete. Every workout here is on your watch under Workout.",
            RunUi.DIM, 12f).apply { setPadding(dp(4), dp(8), 0, 0) })
        WorkoutStore.library(this).forEach { w ->
            col.addView(RunUi.card(this).apply {
                addView(RunUi.text(ctx, w.optString("name"), Color.WHITE, 17f, true))
                w.optString("notes").takeIf { it.isNotBlank() }?.let { addView(RunUi.text(ctx, it, RunUi.DIM, 12f)) }
                addView(RunUi.text(ctx, outline(w).joinToString("\n"), RunUi.MUTED, 13f))
                setOnClickListener { startActivity(Intent(ctx, WorkoutEditActivity::class.java).putExtra("id", w.optString("id"))) }
                setOnLongClickListener {
                    AlertDialog.Builder(ctx).setTitle(w.optString("name"))
                        .setItems(arrayOf("Schedule on a date", "Duplicate", "Delete")) { _, which ->
                            when (which) {
                                0 -> pickDate { d -> WorkoutStore.setDay(ctx, d, JSONObject(w.toString()).put("edited", true)); tab = "plan"; render() }
                                1 -> { WorkoutStore.upsertLibrary(ctx, JSONObject(w.toString()).put("id", "w" + System.nanoTime())
                                        .put("name", w.optString("name") + " copy")); render() }
                                2 -> { WorkoutStore.deleteLibrary(ctx, w.optString("id")); render() }
                            }
                        }.show()
                    true
                }
            })
        }
    }

    private fun pickDate(then: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            then(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun chooseLibrary(then: (JSONObject) -> Unit) {
        val lib = WorkoutStore.library(this)
        if (lib.isEmpty()) { Toast.makeText(this, "Your library is empty", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Choose a workout")
            .setItems(lib.map { it.optString("name") }.toTypedArray()) { _, i -> then(lib[i]) }.show()
    }

    private fun pickDateThenWorkout() = pickDate { d ->
        chooseLibrary { w -> WorkoutStore.setDay(this, d, JSONObject(w.toString()).put("edited", true)); render() }
    }
}
