package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.michealsizemore1.top25widget.RunMath

/** Fitness details: training load, Zone 2 trend, personal bests, aerobic decoupling (from the home screen's Fitness card). */
class FitnessActivity : Activity() {
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val ctx = this
        val dp = { v: Int -> RunUi.dp(ctx, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(32)) }
        col.addView(RunUi.text(this, "Fitness", Color.WHITE, 26f, true))
        // Training load, Zone 2 fitness, personal bests.
        run {
            val all = PhoneRuns.list(this).take(200).mapNotNull { PhoneRuns.read(it) }
            if (all.isNotEmpty()) {
                val hrMax = RunSettings.hrMax(this); val hrRest = RunSettings.hrRest(this)
                // Training load.
                val ld = io.github.michealsizemore1.top25widget.Insights.load(all)
                col.addView(RunUi.section(this, "TRAINING LOAD"))
                col.addView(RunUi.card(this).apply {
                    addView(RunUi.text(ctx, ld.status, ld.color, 18f, true))
                    addView(RunUi.statGrid(ctx, listOfNotNull(
                        Triple("${ld.acute}", "stress, last 7 days", Color.WHITE),
                        ld.ratio?.let { Triple("${ld.chronicWeek}", "usual week", Color.WHITE) },
                        ld.ratio?.let { Triple(String.format(java.util.Locale.US, "%.2f", it), "ratio (0.8–1.3 healthy)", ld.color) },
                        Triple(String.format(java.util.Locale.US, "%.1f", ld.milesWeek), "miles, last 7 days", Color.WHITE),
                        ld.ratio?.let { Triple(String.format(java.util.Locale.US, "%.1f", ld.milesAvg), "usual weekly miles", Color.WHITE) },
                    )))
                    addView(RunUi.text(ctx, ld.advice, RunUi.MUTED, 12f).apply { setPadding(0, dp(6), 0, 0) })
                    val cpE = io.github.michealsizemore1.top25widget.CriticalPower.estimate(all, currentCp = RunSettings.cp(ctx))
                    val cpNow = RunSettings.cp(ctx)
                    addView(RunUi.text(ctx, "Critical power $cpNow W" + (cpE.cp?.let { if (kotlin.math.abs(it - cpNow) >= 3) " · your runs say $it W — tap to review" else " · matches your runs" } ?: " ▸"),
                        0xFFB18CFF.toInt(), 13f, true).apply { setPadding(0, dp(8), 0, 0); setOnClickListener { startActivity(Intent(ctx, ReadinessActivity::class.java)) } })
                })
                // Zone 2 fitness: pace while HR is in Zone 2, run by run.
                val z2 = all.mapNotNull { r -> io.github.michealsizemore1.top25widget.Insights.zone2(r, hrMax, hrRest)?.let { r.optLong("start") to it } }.take(12)
                if (z2.isNotEmpty()) {
                    val (nowP, beforeP) = io.github.michealsizemore1.top25widget.Insights.z2Trend(all, hrMax, hrRest)
                    col.addView(RunUi.section(this, "ZONE 2 FITNESS"))
                    col.addView(RunUi.card(this).apply {
                        nowP?.let { n ->
                            addView(RunUi.text(ctx, "Zone 2 pace " + io.github.michealsizemore1.top25widget.RunMath.paceFor(n, io.github.michealsizemore1.top25widget.RunMath.METERS_PER_MILE) + " /mi, last 4 weeks", Color.WHITE, 16f, true))
                            addView(RunUi.text(ctx, beforeP?.let { b -> val d = b - n
                                if (d >= 0) "${d} s/mi faster than the 4 weeks before — aerobic fitness is improving" else "${-d} s/mi slower than the 4 weeks before — heat, fatigue or terrain?" }
                                ?: "Faster pace at the same heart rate = fitter. The comparison starts after 4 weeks.", RunUi.MUTED, 12f))
                        }
                        // One bar per run (taller = faster in Zone 2).
                        val paces = z2.map { it.second.secPerMile }
                        val slow = paces.max() + 15; val fast = paces.min() - 15
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.BOTTOM
                            setPadding(0, dp(10), 0, 0)
                            z2.reversed().forEach { (st, z) ->
                                addView(LinearLayout(ctx).apply {
                                    orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                                    addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.RunMath.paceFor(z.secPerMile, io.github.michealsizemore1.top25widget.RunMath.METERS_PER_MILE), RunUi.MUTED, 9f).apply { gravity = android.view.Gravity.CENTER })
                                    addView(android.view.View(ctx).apply { background = RunUi.rounded(0xFF22C55E.toInt(), dp(3).toFloat()) },
                                        LinearLayout.LayoutParams(dp(14), dp(10 + ((slow - z.secPerMile) * 60 / maxOf(1, slow - fast)))))
                                    addView(RunUi.text(ctx, java.text.SimpleDateFormat("M/d", java.util.Locale.US).format(java.util.Date(st)), RunUi.DIM, 9f).apply { gravity = android.view.Gravity.CENTER })
                                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                            }
                        })
                    })
                }
                // Personal bests.
                val rec = io.github.michealsizemore1.top25widget.Insights.records(all)
                if (rec.isNotEmpty()) {
                    col.addView(RunUi.section(this, "PERSONAL BESTS"))
                    col.addView(RunUi.card(this).apply {
                        rec.forEach { (k, pb) ->
                            addView(LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                                addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.Insights.pbName(k), Color.WHITE, 15f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                                addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.Insights.clock(pb.sec) + "   " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(pb.start)),
                                    0xFFF2C94C.toInt(), 15f, true))
                            })
                        }
                        addView(RunUi.text(ctx, "Fastest stretch inside any run recorded in BeyondStride.", RunUi.DIM, 11f).apply { setPadding(0, dp(4), 0, 0) })
                    })
                }
            }
        }

        // Aerobic decoupling trend: steady runs of 40+ minutes.
        run {
            val pts = PhoneRuns.list(this).take(120).mapNotNull { f -> PhoneRuns.read(f) }
                .mapNotNull { r -> io.github.michealsizemore1.top25widget.Decoupling.of(r)?.takeIf { it.steady && it.minutes >= 30 && it.main != null }?.let { r.optLong("start") to it.main!! } }
                .take(10)
            if (pts.size >= 2) {
                col.addView(RunUi.section(this, "AEROBIC DECOUPLING · STEADY RUNS"))
                col.addView(RunUi.card(this).apply {
                    val now = System.currentTimeMillis()
                    val recent = pts.filter { now - it.first < 28 * 86_400_000L }.map { it.second }
                    val older = pts.filter { now - it.first >= 28 * 86_400_000L }.map { it.second }
                    if (recent.isNotEmpty()) {
                        val avg = recent.average()
                        addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.Decoupling.text(avg) + " average, last 4 weeks", io.github.michealsizemore1.top25widget.Decoupling.color(avg), 18f, true))
                        if (older.isNotEmpty()) {
                            val diff = avg - older.average()
                            addView(RunUi.text(ctx, (if (diff <= 0) "Improving: " else "Up: ") + String.format(java.util.Locale.US, "%+.1f", diff) +
                                " pts vs earlier runs" + (if (diff <= 0) " — aerobic base is building" else " — check heat, fatigue or pace"), RunUi.MUTED, 12f))
                        }
                    }
                    // One bar per run, oldest → newest.
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.BOTTOM
                        setPadding(0, RunUi.dp(ctx, 10), 0, 0)
                        pts.reversed().forEach { (st, v) ->
                            addView(LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                                addView(RunUi.text(ctx, String.format(java.util.Locale.US, "%.0f", v), RunUi.MUTED, 10f).apply { gravity = android.view.Gravity.CENTER })
                                addView(android.view.View(ctx).apply { background = RunUi.rounded(io.github.michealsizemore1.top25widget.Decoupling.color(v), RunUi.dp(ctx, 3).toFloat()) },
                                    LinearLayout.LayoutParams(RunUi.dp(ctx, 14), RunUi.dp(ctx, (6 + v.coerceIn(0.0, 15.0) * 4).toInt())))
                                addView(RunUi.text(ctx, java.text.SimpleDateFormat("M/d", java.util.Locale.US).format(java.util.Date(st)), RunUi.DIM, 9f).apply { gravity = android.view.Gravity.CENTER })
                            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        }
                    })
                })
            }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
