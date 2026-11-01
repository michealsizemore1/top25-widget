package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.michealsizemore1.top25widget.RunMath
import io.github.michealsizemore1.top25widget.Workouts
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Builds or edits a workout: steps (warm up, run, recover, rest, cool down) with a length
 * (time, distance, or until you press Next Step) and a target (heart-rate zone, power zone,
 * pace range, or custom range), plus repeat blocks.
 * Opened with "id" (library workout), "date" (that day of the plan), or nothing (new workout).
 */
class WorkoutEditActivity : Activity() {

    private lateinit var w: JSONObject
    private var libId: String? = null
    private var date: String? = null
    private lateinit var nameField: EditText
    private lateinit var notesField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libId = intent.getStringExtra("id")
        date = intent.getStringExtra("date")
        w = when {
            date != null -> WorkoutStore.plan(this).optJSONObject(date!!)?.let { JSONObject(it.toString()) }
            libId != null -> WorkoutStore.library(this).firstOrNull { it.optString("id") == libId }?.let { JSONObject(it.toString()) }
            else -> null
        } ?: Workouts.workout("New workout", "", Workouts.step("warmup", "dist", 1609, "hr", 2),
            Workouts.step("run", "dist", 4828, "hr", 2), Workouts.step("cooldown", "dist", 1609, "hr", 2))
        render()
    }

    private fun steps(): JSONArray = w.optJSONArray("steps") ?: JSONArray().also { w.put("steps", it) }

    private fun zonesHint() = Triple(RunSettings.hrMax(this), RunSettings.hrRest(this), RunSettings.cp(this))

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val oldName = if (::nameField.isInitialized) nameField.text.toString() else w.optString("name")
        val oldNotes = if (::notesField.isInitialized) notesField.text.toString() else w.optString("notes")
        w.put("name", oldName); w.put("notes", oldNotes)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        col.addView(RunUi.text(this, if (date != null) "Plan · $date" else if (libId != null) "Edit workout" else "New workout", RunUi.MUTED, 13f))
        nameField = field(w.optString("name"), "Workout name", 22f).also { col.addView(it) }
        notesField = field(w.optString("notes"), "Notes (shown on the watch)", 14f).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        w.optString("planText").takeIf { it.isNotBlank() }?.let {
            col.addView(RunUi.section(this, "FROM YOUR PLAN"))
            col.addView(RunUi.text(this, it, RunUi.DIM, 12f).apply { setPadding(dp(4), dp(4), dp(4), 0) })
        }

        col.addView(RunUi.section(this, "STEPS · tap to change"))
        val (mx, rs, cp) = zonesHint()
        val arr = steps()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            if (s.optString("kind") == "repeat") {
                val inner = s.optJSONArray("steps") ?: JSONArray().also { s.put("steps", it) }
                col.addView(RunUi.card(this).apply {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(RunUi.text(ctx, "Repeat", RunUi.ACCENT, 15f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(RunUi.button(ctx, "−", RunUi.BG, Color.WHITE) { s.put("times", (s.optInt("times") - 1).coerceAtLeast(1)); render() })
                        addView(RunUi.text(ctx, " ${s.optInt("times")}× ", Color.WHITE, 18f, true))
                        addView(RunUi.button(ctx, "+", RunUi.BG, Color.WHITE) { s.put("times", s.optInt("times") + 1); render() })
                    })
                    for (j in 0 until inner.length()) addView(stepRow(inner, j, mx, rs, cp, indent = true))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(6), 0, 0)
                        addView(RunUi.text(ctx, "+ step in repeat", RunUi.ACCENT, 13f, true).apply {
                            setOnClickListener { inner.put(Workouts.step("run", "dist", 400, "hr", 4)); render() }
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(RunUi.text(ctx, "↑", RunUi.MUTED, 18f, true).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { move(arr, i, -1) } })
                        addView(RunUi.text(ctx, "↓", RunUi.MUTED, 18f, true).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { move(arr, i, 1) } })
                        addView(RunUi.text(ctx, "Remove", 0xFFE5484D.toInt(), 13f, true).apply { setOnClickListener { arr.remove(i); render() } })
                    })
                })
            } else col.addView(RunUi.card(this).apply { addView(stepRow(arr, i, mx, rs, cp, indent = false)) })
        }

        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(RunUi.button(ctx, "+ Step", RunUi.CARD, Color.WHITE) {
                arr.put(Workouts.step("run", "dist", 1609, "hr", 2)); render()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(RunUi.button(ctx, "+ Repeat", RunUi.CARD, Color.WHITE) {
                arr.put(Workouts.repeat(4, Workouts.step("run", "dist", 800, "hr", 5), Workouts.step("recover", "dist", 400, "hr", 1))); render()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })

        val m = Workouts.plannedMeters(w)
        if (m > 0) col.addView(RunUi.text(this, String.format(Locale.US, "Planned distance: %.2f mi (plus any open or timed steps)", m / RunMath.METERS_PER_MILE),
            RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(12), 0, 0) })

        col.addView(RunUi.button(this, "Save", RunUi.ACCENT) { save() }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        })
        if (date != null) col.addView(RunUi.text(this, "Also save to my library", RunUi.ACCENT, 14f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            setOnClickListener {
                collect()
                val copy = JSONObject(w.toString()).put("id", "w" + System.nanoTime())
                copy.remove("planText"); copy.remove("edited")
                WorkoutStore.upsertLibrary(ctx, copy)
                Toast.makeText(ctx, "Added to your library", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun stepRow(arr: JSONArray, i: Int, mx: Double, rs: Double, cp: Int, indent: Boolean): View {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val s = arr.getJSONObject(i)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (indent) dp(12) else 0, dp(6), 0, dp(6))
            val color = when (s.optString("kind")) { "warmup", "cooldown" -> 0xFF4FA3E0.toInt(); "recover", "rest" -> RunUi.MUTED; else -> RunUi.ACCENT }
            addView(View(ctx).apply { background = RunUi.rounded(color, dp(3).toFloat()) }, LinearLayout.LayoutParams(dp(5), dp(36)).apply { marginEnd = dp(10) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(RunUi.text(ctx, "${Workouts.kindName(s.optString("kind"))} · ${Workouts.durText(s)}", Color.WHITE, 15f, true))
                addView(RunUi.text(ctx, Workouts.targetText(s, mx, rs, cp), RunUi.MUTED, 13f))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(RunUi.text(ctx, "↑", RunUi.MUTED, 18f, true).apply { setPadding(dp(8), 0, dp(8), 0); setOnClickListener { move(arr, i, -1) } })
            addView(RunUi.text(ctx, "↓", RunUi.MUTED, 18f, true).apply { setPadding(dp(8), 0, dp(8), 0); setOnClickListener { move(arr, i, 1) } })
            setOnClickListener { editStep(arr, i) }
        }
    }

    private fun move(arr: JSONArray, i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= arr.length()) return
        val a = arr.get(i); arr.put(i, arr.get(j)); arr.put(j, a)
        render()
    }

    /** The step editor: kind, length, target. */
    private fun editStep(arr: JSONArray, i: Int) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val s = arr.getJSONObject(i)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun label(t: String) = TextView(ctx).apply { text = t; setPadding(0, dp(10), 0, 0) }
        fun spinner(items: List<String>, sel: Int) = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items.toMutableList()); setSelection(sel.coerceAtLeast(0))
        }

        box.addView(label("Step"))
        val kinds = Workouts.KINDS
        val kindSp = spinner(kinds.map { Workouts.kindName(it) }, kinds.indexOf(s.optString("kind")))
        box.addView(kindSp)

        box.addView(label("Length"))
        val durKeys = listOf("dist", "time", "open")
        val durSp = spinner(listOf("Distance", "Time", "Until I press Next Step"), durKeys.indexOf(s.optString("dur")))
        box.addView(durSp)
        val unitKeys = listOf("mi", "m")
        val value = EditText(ctx); val unitSp = spinner(listOf("miles", "meters"), if (s.optString("dur") == "dist" && s.optInt("value") < 1600) 1 else 0)
        val valueRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(unitSp)
        }
        box.addView(valueRow)
        fun fillValue() {
            val dur = durKeys[durSp.selectedItemPosition]
            valueRow.visibility = if (dur == "open") View.GONE else View.VISIBLE
            unitSp.visibility = if (dur == "dist") View.VISIBLE else View.GONE
            val v = s.optInt("value")
            if (dur == "time") { value.hint = "mm:ss"; value.inputType = InputType.TYPE_CLASS_DATETIME
                value.setText(if (s.optString("dur") == "time") RunMath.clock(v) else "5:00") }
            else if (dur == "dist") { value.hint = "distance"; value.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                value.setText(if (s.optString("dur") != "dist") "1" else if (unitSp.selectedItemPosition == 1) "$v"
                    else String.format(Locale.US, "%.2f", v / RunMath.METERS_PER_MILE).trimEnd('0').trimEnd('.')) }
        }
        durSp.onItemSelectedListener = simple { fillValue() }
        unitSp.onItemSelectedListener = simple {
            // Convert the number when the unit changes.
            val n = value.text.toString().toDoubleOrNull() ?: return@simple
            if (unitSp.tag != null && unitSp.tag != unitSp.selectedItemPosition) {
                value.setText(if (unitSp.selectedItemPosition == 1) "${(n * RunMath.METERS_PER_MILE).toInt()}"
                    else String.format(Locale.US, "%.2f", n / RunMath.METERS_PER_MILE))
            }
            unitSp.tag = unitSp.selectedItemPosition
        }
        fillValue()

        box.addView(label("Target"))
        val tKeys = listOf("none", "hrz", "pwz", "pace", "hr", "power")
        val curT = when (s.optString("target")) {
            "hr" -> if (s.optInt("zone") in 1..5) "hrz" else "hr"
            "power" -> if (s.optInt("zone") in 1..5) "pwz" else "power"
            "pace" -> "pace"; else -> "none"
        }
        val tSp = spinner(listOf("No target", "Heart-rate zone", "Power zone", "Pace range", "Custom heart rate", "Custom power"), tKeys.indexOf(curT))
        box.addView(tSp)
        val (mx, rs, cp) = zonesHint()
        val hb = RunMath.hrBounds(mx, rs); val pb = RunMath.powerBounds(cp)
        val zoneSp = spinner((1..5).map { "Z$it" }, (s.optInt("zone", 2) - 1).coerceIn(0, 4))
        val lo = EditText(ctx); val hi = EditText(ctx)
        val rangeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(TextView(ctx).apply { text = "  to  " })
            addView(hi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        box.addView(zoneSp); box.addView(rangeRow)
        fun fillTarget() {
            val t = tKeys[tSp.selectedItemPosition]
            zoneSp.visibility = if (t == "hrz" || t == "pwz") View.VISIBLE else View.GONE
            rangeRow.visibility = if (t == "pace" || t == "hr" || t == "power") View.VISIBLE else View.GONE
            (zoneSp.adapter as ArrayAdapter<String>).apply {
                clear(); addAll((1..5).map { z ->
                    if (t == "pwz") "Z$z ${RunMath.POWER_ZONE_NAMES[z - 1]} · ${pb[z - 1]}${if (z < 5) "–${pb[z] - 1}" else "+"} W"
                    else "Z$z ${RunMath.HR_ZONE_NAMES[z - 1]} · ${hb[z - 1]}${if (z < 5) "–${hb[z] - 1}" else "+"} bpm"
                }); notifyDataSetChanged()
            }
            val sameKind = curT == t
            when (t) {
                "pace" -> { lo.hint = "fast 7:30"; hi.hint = "slow 7:50"; lo.inputType = InputType.TYPE_CLASS_DATETIME; hi.inputType = InputType.TYPE_CLASS_DATETIME
                    lo.setText(if (sameKind) RunMath.clock(s.optInt("lo")) else ""); hi.setText(if (sameKind) RunMath.clock(s.optInt("hi")) else "") }
                "hr", "power" -> { lo.hint = "low"; hi.hint = "high"; lo.inputType = InputType.TYPE_CLASS_NUMBER; hi.inputType = InputType.TYPE_CLASS_NUMBER
                    lo.setText(if (sameKind) "${s.optInt("lo")}" else ""); hi.setText(if (sameKind) "${s.optInt("hi")}" else "") }
            }
        }
        tSp.onItemSelectedListener = simple { fillTarget() }
        fillTarget()

        AlertDialog.Builder(this).setTitle("Step").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ ->
                val n = JSONObject().put("kind", kinds[kindSp.selectedItemPosition])
                val dur = durKeys[durSp.selectedItemPosition]
                n.put("dur", dur)
                val raw = value.text.toString().trim()
                n.put("value", when (dur) {
                    "time" -> parseClock(raw) ?: 300
                    "dist" -> (raw.toDoubleOrNull() ?: 1.0).let { if (unitSp.selectedItemPosition == 1) it.toInt() else (it * RunMath.METERS_PER_MILE).toInt() }
                    else -> 0
                })
                when (val t = tKeys[tSp.selectedItemPosition]) {
                    "none" -> n.put("target", "none")
                    "hrz" -> n.put("target", "hr").put("zone", zoneSp.selectedItemPosition + 1)
                    "pwz" -> n.put("target", "power").put("zone", zoneSp.selectedItemPosition + 1)
                    "pace" -> {
                        var a = parseClock(lo.text.toString()) ?: 0; var b = parseClock(hi.text.toString()) ?: 0
                        if (a > b) { val x = a; a = b; b = x }
                        n.put("target", "pace").put("zone", 0).put("lo", a).put("hi", b)
                    }
                    else -> n.put("target", t).put("zone", 0)
                        .put("lo", lo.text.toString().toIntOrNull() ?: 0).put("hi", hi.text.toString().toIntOrNull() ?: 0)
                }
                arr.put(i, n); render()
            }
            .setNeutralButton("Delete step") { _, _ -> arr.remove(i); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun parseClock(t: String): Int? {
        val p = t.split(":").map { it.trim().toIntOrNull() ?: return null }
        return when (p.size) { 1 -> p[0] * 60; 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> null }
    }

    private fun simple(f: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun field(text: String, hint: String, size: Float) = EditText(this).apply {
        setText(text); this.hint = hint; textSize = size
        setTextColor(Color.WHITE); setHintTextColor(RunUi.DIM)
        background = RunUi.rounded(RunUi.CARD, RunUi.dp(context, 12).toFloat())
        setPadding(RunUi.dp(context, 14), RunUi.dp(context, 10), RunUi.dp(context, 14), RunUi.dp(context, 10))
    }

    private fun collect() {
        w.put("name", nameField.text.toString().trim().ifBlank { "Workout" })
        w.put("notes", notesField.text.toString().trim())
    }

    private fun save() {
        collect()
        if (steps().length() == 0) { Toast.makeText(this, "Add at least one step", Toast.LENGTH_SHORT).show(); return }
        when {
            date != null -> WorkoutStore.setDay(this, date!!, w.put("edited", true))
            else -> WorkoutStore.upsertLibrary(this, w)
        }
        Toast.makeText(this, "Saved — sent to your watch", Toast.LENGTH_SHORT).show()
        finish()
    }
}
