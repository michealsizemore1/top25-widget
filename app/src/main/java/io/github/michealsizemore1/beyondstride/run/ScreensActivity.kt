package io.github.michealsizemore1.beyondstride.run

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.michealsizemore1.beyondstride.RunFields
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sets up the watch's data screens during a run, like Gear Tracker II: pick a layout for each
 * screen and the metric in each spot. Swipe between them on the watch.
 */
class ScreensActivity : Activity() {

    private lateinit var screens: JSONArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screens = JSONArray(RunSettings.screens(this).toString())
        render()
    }

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        col.addView(RunUi.text(this, "Watch data screens", Color.WHITE, 26f, true))
        col.addView(RunUi.text(this, "These are the screens you swipe through on the watch during a run (after the " +
            "controls and the workout step screen). Tap a spot to choose what it shows.", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        for (i in 0 until screens.length()) {
            val sc = screens.getJSONObject(i)
            val layout = RunFields.layout(sc.optString("layout", "6"))
            val fields = sc.optJSONArray("fields") ?: JSONArray().also { sc.put("fields", it) }
            while (fields.length() < layout.rows.sum()) fields.put("time")
            col.addView(RunUi.section(this, "SCREEN ${i + 1}"))
            col.addView(RunUi.card(this).apply {
                // Layout picker.
                addView(RunUi.text(ctx, "Layout: ${layout.name}  ›", RunUi.ACCENT, 14f, true).apply {
                    setOnClickListener {
                        AlertDialog.Builder(ctx).setTitle("Layout")
                            .setItems(RunFields.LAYOUTS.map { it.name }.toTypedArray()) { _, which ->
                                sc.put("layout", RunFields.LAYOUTS[which].id); render()
                            }.show()
                    }
                })
                // A picture of the screen: one box per spot, in its row.
                val preview = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = RunUi.rounded(Color.BLACK, dp(90).toFloat())
                    setPadding(dp(30), dp(22), dp(30), dp(22))
                }
                var k = 0
                layout.rows.forEach { count ->
                    preview.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        repeat(count) {
                            val idx = k++
                            val key = fields.optString(idx)
                            addView(RunUi.text(ctx, RunFields.name(key), Color.WHITE, 13f, true).apply {
                                gravity = Gravity.CENTER
                                background = RunUi.rounded(RunUi.CARD, dp(8).toFloat())
                                setPadding(dp(4), dp(12), dp(4), dp(12))
                                setOnClickListener { pickMetric(fields, idx) }
                            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                        }
                    })
                }
                addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(RunUi.text(ctx, "↑ Move up", RunUi.MUTED, 13f, true).apply { setOnClickListener { move(i, -1) } },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(RunUi.text(ctx, "↓ Move down", RunUi.MUTED, 13f, true).apply { setOnClickListener { move(i, 1) } },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(RunUi.text(ctx, "Remove", 0xFFE5484D.toInt(), 13f, true).apply {
                        setOnClickListener {
                            if (screens.length() <= 1) Toast.makeText(ctx, "Keep at least one screen", Toast.LENGTH_SHORT).show()
                            else { screens.remove(i); render() }
                        }
                    })
                })
            })
        }

        col.addView(RunUi.button(this, "+ Add a screen", RunUi.CARD, Color.WHITE) {
            screens.put(JSONObject().put("layout", "4").put("fields", JSONArray(listOf("time", "pace", "hr", "distance")))); render()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) } })
        col.addView(RunUi.button(this, "Save — send to watch", RunUi.ACCENT) {
            // Trim extra fields if a layout got smaller.
            for (i in 0 until screens.length()) {
                val sc = screens.getJSONObject(i)
                val n = RunFields.size(sc.optString("layout", "6"))
                val f = sc.optJSONArray("fields") ?: JSONArray()
                sc.put("fields", JSONArray((0 until n).map { f.optString(it).ifBlank { "time" } }))
            }
            RunSettings.saveScreens(ctx, screens)
            Toast.makeText(ctx, "Sent to your watch", Toast.LENGTH_SHORT).show()
            finish()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })
        col.addView(RunUi.text(this, "Reset to the starting screens", RunUi.MUTED, 13f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
            setOnClickListener { screens = RunFields.defaults(); render() }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun pickMetric(fields: JSONArray, idx: Int) {
        AlertDialog.Builder(this).setTitle("Show in this spot")
            .setItems(RunFields.METRICS.map { it.second }.toTypedArray()) { _, which ->
                fields.put(idx, RunFields.METRICS[which].first); render()
            }.show()
    }

    private fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= screens.length()) return
        val a = screens.get(i); screens.put(i, screens.get(j)); screens.put(j, a)
        render()
    }
}
