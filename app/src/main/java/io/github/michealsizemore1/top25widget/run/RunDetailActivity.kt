package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.michealsizemore1.top25widget.RunMath
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One run: totals, charts, zones, splits, route, and sending. */
class RunDetailActivity : Activity() {

    private var start = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start = intent.getLongExtra("start", 0)
    }

    override fun onResume() {
        super.onResume()
        val run = PhoneRuns.read(this, start) ?: run { finish(); return }
        render(run)
    }

    private fun render(run: JSONObject) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }
        val sec = run.optInt("movingSeconds")
        val meters = run.optDouble("distance")

        col.addView(RunUi.text(this, PhoneRuns.title(run), Color.WHITE, 26f, true))
        col.addView(RunUi.text(this, SimpleDateFormat("EEEE, MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(run.optLong("start"))) +
            " · " + (if (run.optString("source") == "gps") "GPS" else "Stryd") +
            (if (run.has("hrSource")) " · HR " + (if (run.optString("hrSource") == "strap") "chest strap" else "wrist") else "") +
            (if (run.optBoolean("recovered")) " · recovered" else ""), RunUi.MUTED, 13f))

        col.addView(RunUi.card(this).apply {
            addView(RunUi.statGrid(ctx, listOfNotNull(
                Triple(RunMath.miles(meters), "miles", RunUi.ACCENT),
                Triple(RunMath.clock(sec), "moving time", Color.WHITE),
                Triple(RunMath.paceFor(sec, meters), "avg pace /mi", Color.WHITE),
                run.optInt("avgHr").takeIf { it > 0 }?.let { Triple("$it", "avg HR", RunUi.HR) },
                run.optInt("maxHr").takeIf { it > 0 }?.let { Triple("$it", "max HR", RunUi.HR) },
                run.optInt("avgCadence").takeIf { it > 0 }?.let { Triple("$it", "cadence spm", Color.WHITE) },
                run.optInt("avgPower").takeIf { it > 0 }?.let { Triple("$it", "avg W", RunUi.POWER) },
                run.optInt("np").takeIf { it > 0 }?.let { Triple("$it", "normalized W", RunUi.POWER) },
                run.optInt("maxPower").takeIf { it > 0 }?.let { Triple("$it", "max W", RunUi.POWER) },
                run.optInt("stress").takeIf { it > 0 }?.let { Triple("$it", "stress (est.)", Color.WHITE) },
                run.optDouble("intensity").takeIf { !it.isNaN() && it > 0 }?.let { Triple("%.2f".format(Locale.US, it), "intensity", Color.WHITE) },
                run.optDouble("avgStride").takeIf { !it.isNaN() && it > 0 }?.let { Triple("%.2f m".format(Locale.US, it), "avg stride", Color.WHITE) },
                Triple(RunMath.clock(run.optInt("elapsedSeconds")), "elapsed", RunUi.MUTED),
            )))
        })

        // Send buttons.
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            val sid = PhoneRuns.stravaId(ctx, start)
            addView(RunUi.button(ctx, if (sid > 0) "View on Strava" else "Upload to Strava", RunUi.STRAVA, Color.WHITE) {
                when {
                    sid > 0 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$sid")))
                    !Strava.connected(ctx) -> startActivity(Intent(ctx, RunSettingsActivity::class.java))
                    else -> uploadNow(run)
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(RunUi.button(ctx, if (PhoneRuns.sentToCommandCenter(ctx, start)) "Resend to CC" else "Send to Command Center",
                RunUi.CC, Color.WHITE) { CommandCenter.send(ctx, listOf(start)) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        getSharedPreferences("runflags", 0).getString("strava_err_$start", null)?.takeIf { PhoneRuns.stravaId(ctx, start) == 0L }?.let {
            col.addView(RunUi.text(this, "Last Strava try: $it", RunUi.STRAVA, 12f).apply { setPadding(dp(4), dp(6), 0, 0) })
        }

        // Stryd metrics (from your Stryd account).
        col.addView(RunUi.section(this, "STRYD"))
        val s = run.optJSONObject("stryd")
        if (s != null) {
            fun d(k: String) = s.optDouble(k).takeIf { !it.isNaN() }
            col.addView(RunUi.card(this).apply {
                addView(RunUi.statGrid(ctx, listOfNotNull(
                    d("groundTime")?.let { Triple("${it.toInt()}", "ground time ms", Color.WHITE) },
                    d("oscillation")?.let { Triple("%.1f".format(Locale.US, it), "oscillation cm", Color.WHITE) },
                    d("legSpring")?.let { Triple("%.1f".format(Locale.US, it), "leg spring kN/m", Color.WHITE) },
                    d("stride")?.let { Triple("%.2f".format(Locale.US, it), "stride m", Color.WHITE) },
                    d("verticalRatio")?.let { Triple("%.1f".format(Locale.US, it), "vertical ratio %", Color.WHITE) },
                    d("formPower")?.let { Triple("${it.toInt()}", "form power W", RunUi.POWER) },
                    d("airPowerPct")?.let { Triple("%.1f%%".format(Locale.US, it), "air power", RunUi.POWER) },
                    d("rss")?.let { Triple("${it.toInt()}", "RSS", RunUi.ACCENT) },
                    d("cp")?.let { Triple("${it.toInt()}", "Stryd CP W", RunUi.POWER) },
                    d("elevGain")?.let { Triple("${Math.round(it * 3.28084)}", "elev gain ft", Color.WHITE) },
                    d("temp")?.let { Triple("${Math.round(it * 9 / 5 + 32)}°", "temp °F", Color.WHITE) },
                    d("humidity")?.let { Triple("${it.toInt()}%", "humidity", Color.WHITE) },
                )))
                addView(RunUi.text(ctx, "Refresh from Stryd", RunUi.MUTED, 13f, true).apply {
                    setPadding(0, dp(8), 0, 0); setOnClickListener { fetchStryd() }
                })
            })
        } else if (StrydCloud.connected(this)) {
            col.addView(RunUi.card(this).apply {
                addView(RunUi.text(ctx, "Not found in Stryd yet. Open the Stryd app so the pod syncs, then tap below " +
                    "(the phone also keeps checking on its own for a few hours).", RunUi.MUTED, 13f))
                addView(RunUi.button(ctx, "Get Stryd data", RunUi.STRYD, Color.WHITE) { fetchStryd() }.apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) }
                })
            })
        } else {
            col.addView(RunUi.button(this, "Connect Stryd for ground time, oscillation, leg spring, RSS…", RunUi.STRYD, Color.WHITE) {
                startActivity(Intent(ctx, RunSettingsActivity::class.java))
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })
        }

        // Charts.
        val samples = run.optJSONArray("samples")
        if (samples != null && samples.length() > 10) {
            val n = samples.length()
            val xs = FloatArray(n) { samples.getJSONArray(it).optInt(0) / 60f }
            fun ys(i: Int, scale: Float = 1f) = FloatArray(n) { samples.getJSONArray(it).optInt(i) * scale }
            val minutes = { v: Float -> "${v.toInt()}" }
            col.addView(RunUi.section(this, "PACE (min/mi)"))
            col.addView(RunUi.card(this).apply {
                // Pace in seconds per mile, plotted with faster at the top.
                val speed = ys(3, 0.01f)
                val pace = FloatArray(n) { if (speed[it] > 1.0f) (RunMath.METERS_PER_MILE / speed[it]).toFloat() else 0f }
                addView(RunUi.chart(ctx, xs, pace, RunUi.ACCENT, invert = true) { v -> RunMath.clock(v.toInt()) })
            })
            if ((0 until n).any { samples.getJSONArray(it).optInt(2) > 0 }) {
                col.addView(RunUi.section(this, "HEART RATE (bpm) · shaded by zone"))
                col.addView(RunUi.card(this).apply {
                    addView(RunUi.chart(ctx, xs, ys(2), RunUi.HR,
                        bands = RunMath.hrBounds(run.optDouble("hrMax", RunSettings.hrMax(ctx)), run.optDouble("hrRest", RunSettings.hrRest(ctx))), label = minutes))
                })
            }
            if ((0 until n).any { samples.getJSONArray(it).optInt(5) > 0 }) {
                col.addView(RunUi.section(this, "POWER (W) · shaded by zone"))
                col.addView(RunUi.card(this).apply {
                    addView(RunUi.chart(ctx, xs, ys(5), RunUi.POWER,
                        bands = RunMath.powerBounds(run.optInt("cp", RunSettings.cp(ctx))), label = minutes))
                })
            }
            if ((0 until n).any { samples.getJSONArray(it).optInt(4) > 0 }) {
                col.addView(RunUi.section(this, "CADENCE (spm)"))
                col.addView(RunUi.card(this).apply { addView(RunUi.chart(ctx, xs, ys(4), 0xFF4FA3E0.toInt(), label = minutes)) })
            }
            col.addView(RunUi.text(this, "Charts run left to right over moving time.", RunUi.DIM, 11f).apply { setPadding(dp(4), dp(4), 0, 0) })
        }

        // Zones.
        run.optJSONArray("hrZoneSeconds")?.takeIf { a -> (0 until a.length()).any { a.optInt(it) > 0 } }?.let { zs ->
            val b = RunMath.hrBounds(run.optDouble("hrMax", RunSettings.hrMax(ctx)), run.optDouble("hrRest", RunSettings.hrRest(ctx)))
            col.addView(RunUi.section(this, "TIME IN HEART-RATE ZONES"))
            col.addView(RunUi.card(this).apply {
                addView(RunUi.zoneBars(ctx, zs, RunMath.HR_ZONE_NAMES, (0..4).map { if (it < 4) "${b[it]}–${b[it + 1] - 1}" else "${b[4]}+" }))
            })
        }
        run.optJSONArray("zoneSeconds")?.takeIf { a -> (0 until a.length()).any { a.optInt(it) > 0 } }?.let { zs ->
            val b = RunMath.powerBounds(run.optInt("cp", RunSettings.cp(ctx)))
            col.addView(RunUi.section(this, "TIME IN POWER ZONES"))
            col.addView(RunUi.card(this).apply {
                addView(RunUi.zoneBars(ctx, zs, RunMath.POWER_ZONE_NAMES, (0..4).map { if (it < 4) "${b[it]}–${b[it + 1]} W" else "${b[4]}+ W" }))
            })
        }

        // Splits.
        val laps = run.optJSONArray("laps")
        if (laps != null && laps.length() > 0) {
            col.addView(RunUi.section(this, "SPLITS"))
            col.addView(RunUi.card(this).apply {
                fun row(cells: List<String>, color: Int, bold: Boolean) = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                    cells.forEachIndexed { i, c ->
                        addView(RunUi.text(ctx, c, color, 13f, bold).apply { gravity = if (i == 0) Gravity.START else Gravity.END },
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, if (i == 0) 0.7f else 1f))
                    }
                }
                addView(row(listOf("#", "mi", "time", "pace", "HR", "W"), RunUi.MUTED, true))
                for (i in 0 until laps.length()) {
                    val l = laps.getJSONObject(i)
                    addView(row(listOf(l.optString("name").takeIf { it.isNotBlank() } ?: "${l.optInt("n")}", RunMath.miles(l.optDouble("m")), RunMath.clock(l.optInt("sec")),
                        RunMath.paceFor(l.optInt("sec"), l.optDouble("m")),
                        l.optInt("hr").takeIf { it > 0 }?.toString() ?: "–", l.optInt("pw").takeIf { it > 0 }?.toString() ?: "–"),
                        Color.WHITE, false))
                }
            })
        }

        // Route.
        run.optJSONArray("track")?.takeIf { it.length() > 5 }?.let { track ->
            col.addView(RunUi.section(this, "ROUTE"))
            col.addView(RunUi.card(this).apply { addView(RunUi.route(ctx, track)) })
        }

        col.addView(RunUi.text(this, "Share TCX file (for manual Strava or other uploads)", RunUi.ACCENT, 14f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setOnClickListener { shareTcx(run) }
        })
        col.addView(RunUi.text(this, "Delete this run", 0xFFE5484D.toInt(), 14f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Delete this run from the phone?")
                    .setMessage("It stays on Strava and in the Command Center if it was sent there.")
                    .setPositiveButton("Delete") { _, _ -> PhoneRuns.delete(ctx, start); finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun fetchStryd() {
        Toast.makeText(this, "Checking Stryd…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val run = PhoneRuns.read(this, start) ?: return@Thread
                val m = StrydCloud.fetchFor(this, run)
                if (m == null) "Stryd doesn't have this run yet — open the Stryd app to sync the pod"
                else {
                    StrydCloud.attach(this, start, m)
                    val sid = PhoneRuns.stravaId(this, start)
                    if (sid > 0) try {
                        PhoneRuns.read(this, start)?.let { Strava.updateDescription(this, sid, Summary.notes(this, it, forStrava = true)) }
                    } catch (e: Exception) { }
                    "Stryd data added"
                }
            } catch (e: Exception) { "Stryd: ${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); PhoneRuns.read(this, start)?.let { render(it) } }
        }.start()
    }

    private fun shareTcx(run: JSONObject) {
        val dir = java.io.File(cacheDir, "tcx").apply { mkdirs() }
        val f = java.io.File(dir, "run-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(run.optLong("start")))}.tcx")
        f.writeText(Tcx.build(run))
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.garmin.tcx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, PhoneRuns.title(run))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share run file"))
    }

    private fun uploadNow(run: JSONObject) {
        Toast.makeText(this, "Uploading to Strava…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                PhoneRuns.setStravaId(this, start, Strava.upload(this, run))
                getSharedPreferences("runflags", 0).edit().remove("strava_err_$start").apply()
                "Uploaded to Strava"
            } catch (e: Exception) {
                getSharedPreferences("runflags", 0).edit().putString("strava_err_$start", e.message).apply()
                "Strava: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); PhoneRuns.read(this, start)?.let { render(it) } }
        }.start()
    }
}
