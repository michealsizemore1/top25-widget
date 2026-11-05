package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.michealsizemore1.top25widget.RunMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The phone's Running home: runs from the watch, and where they've been sent. */
class RunsActivity : Activity() {

    /** True when this file was installed on the wrong kind of device (a message is shown instead). */
    private var wrongDevice = false

    private val ui get() = RunUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (io.github.michealsizemore1.top25widget.WrongDevice.check(this, isWatchApp = false)) { wrongDevice = true; return }
        // One-time cleanup: cancel any leftover background Stryd lookups from older versions.
        getSharedPreferences("runflags", 0).let { f -> if (!f.getBoolean("strydJobsCleared", false)) {
            try { val wm = androidx.work.WorkManager.getInstance(this)
                PhoneRuns.list(this).forEach { wm.cancelUniqueWork("stryd-" + it.nameWithoutExtension) } } catch (e: Exception) { }
            f.edit().putBoolean("strydJobsCleared", true).apply()
        } }
        handleLink(intent)
        // Clear the sign-in left from the removed Strava connection.
        try { deleteSharedPreferences("strava") } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (wrongDevice) return
        WorkoutStore.pushToWatch(this)   // keeps the watch's "today" plan current
        render()
        // Last night's sleep from Samsung Health (via Health Connect), for the readiness score.
        Thread {
            if (System.currentTimeMillis() - io.github.michealsizemore1.top25widget.ReadinessSync.nightsAt(this) > 30 * 60_000L && SleepData.granted(this)) {
                SleepData.refresh(this); runOnUiThread { if (!isFinishing) render() }
            }
        }.start()
    }

    /** The Command Center's "Send plan to Running app" opens scoresrun://localhost/plan#<days>. */
    private fun handlePlanImport(data: android.net.Uri): Boolean {
        if (data.path?.startsWith("/plan") != true) return false
        val b64 = data.encodedFragment ?: return true
        Thread {
            val msg = try {
                val days = org.json.JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
                val n = WorkoutStore.importPlan(this, days)
                "Received ${days.length()} days: $n workouts imported" +
                    (if (WorkoutStore.restDays > 0) ", ${WorkoutStore.restDays} rest days" else "") +
                    (if (WorkoutStore.keptEdited > 0) ", ${WorkoutStore.keptEdited} kept as you edited them" else "") +
                    ". Sent to your watch."
            } catch (e: Exception) { "Couldn't read the plan: ${e.message}" }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, WorkoutsActivity::class.java).putExtra("tab", "plan"))
            }
        }.start()
        return true
    }

    /** Links into the app (the Command Center's "Send plan to Running app"). */
    private fun handleLink(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "scoresrun") return
        handlePlanImport(data)
    }

    private fun render() {
        PhoneStatus.push(this)
        // Opened from the watch's Command Center button notification.
        if (intent?.getBooleanExtra("sendCc", false) == true) {
            intent.removeExtra("sendCc")
            PhoneStatus.unsentCc(this).takeIf { it.isNotEmpty() }?.let { CommandCenter.send(this, it) }
        }
        val ctx = this
        val dp = { v: Int -> RunUi.dp(ctx, v) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(RunUi.text(ctx, "BeyondStride", Color.WHITE, 28f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(RunUi.button(ctx, "Settings", RunUi.CARD, Color.WHITE) {
                startActivity(Intent(ctx, RunSettingsActivity::class.java))
            })
        })

        val files = PhoneRuns.list(this)
        val runs = files.mapNotNull { PhoneRuns.read(it) }

        // This week's and month's mileage.
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis
        cal.timeInMillis = System.currentTimeMillis(); cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        val monthStart = cal.timeInMillis
        fun miles(from: Long) = runs.filter { it.optLong("start") >= from }.sumOf { it.optDouble("distance") }
        col.addView(RunUi.card(this).apply {
            addView(RunUi.statGrid(ctx, listOf(
                Triple(RunMath.miles(miles(weekStart)), "miles this week", RunUi.ACCENT),
                Triple(RunMath.miles(miles(monthStart)), "miles this month", Color.WHITE),
                Triple("${runs.count { it.optLong("start") >= weekStart }}", "runs this week", Color.WHITE),
            )))
        })

        // Readiness: morning HRV check (watch) + sleep (Samsung Health) + recent training.
        val rd = io.github.michealsizemore1.top25widget.Readiness.compute(io.github.michealsizemore1.top25widget.ReadinessSync.checks(this),
            io.github.michealsizemore1.top25widget.ReadinessSync.nights(this), runs.take(120), io.github.michealsizemore1.top25widget.Readiness.today())
        col.addView(RunUi.card(this).apply {
            addView(RunUi.text(ctx, "READINESS", RunUi.MUTED, 11f, true))
            if (rd == null) addView(RunUi.text(ctx, "Do the 2½-minute morning check on the watch ▸", RunUi.ACCENT, 15f, true))
            else {
                addView(RunUi.text(ctx, "${rd.score} · ${rd.label}" + (if (!rd.checkedToday) "  (no morning check)" else ""), rd.color, 20f, true))
                addView(RunUi.text(ctx, rd.parts.joinToString(" · ") { "${it.name} ${it.value}" }, Color.WHITE, 12f))
                addView(RunUi.text(ctx, rd.advice, RunUi.MUTED, 12f).apply { setPadding(0, dp(4), 0, 0) })
            }
            setOnClickListener { startActivity(Intent(ctx, ReadinessActivity::class.java)) }
        })

        // Command Center and LiveTrack (same buttons as the watch).
        val unsentCc = runs.map { it.optLong("start") }.filter { !PhoneRuns.sentToCommandCenter(this, it) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            fun pill(title: String, status: String, color: Int, onClick: () -> Unit) =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = RunUi.rounded(color, dp(18).toFloat())
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    addView(RunUi.text(ctx, title, Color.WHITE, 15f, true).apply { gravity = Gravity.CENTER })
                    addView(RunUi.text(ctx, status, 0xDDFFFFFF.toInt(), 12f).apply { gravity = Gravity.CENTER })
                    setOnClickListener { onClick() }
                }
            val lp = { last: Boolean -> LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (!last) marginEnd = dp(8) } }
            addView(pill("Command Center", if (unsentCc.isEmpty()) "✓ all sent" else "Send ${unsentCc.size}", RunUi.CC) {
                if (unsentCc.isEmpty()) Toast.makeText(ctx, "All runs are already in the Command Center", Toast.LENGTH_SHORT).show()
                else CommandCenter.send(ctx, unsentCc)
            }, lp(false))
            // LiveTrack on/off (same switch as the watch).
            val live = getSharedPreferences("runsettings", 0).getBoolean("liveOn", false)
            addView(pill("LiveTrack", if (live) "● ON" else "Off", if (live) 0xFF1F6FB2.toInt() else 0xFF50555E.toInt()) {
                if (Live.db(ctx).isBlank()) startActivity(Intent(ctx, LiveTrackActivity::class.java))
                else {
                    RunSettings.setBool(ctx, "liveOn", !live)
                    Toast.makeText(ctx, if (!live) "LiveTrack on — on your watch too" else "LiveTrack off", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }, lp(true))
        })

        // Workouts & plan, with today's workout.
        val todayW = WorkoutStore.plan(this).optJSONObject(WorkoutStore.dayFmt.format(Date()))
        col.addView(RunUi.card(this).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(RunUi.text(ctx, "TODAY", RunUi.MUTED, 11f, true))
                    addView(RunUi.text(ctx, todayW?.optString("name") ?: "No workout planned", Color.WHITE, 17f, true))
                    todayW?.let { w ->
                        addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.Workouts.outline(w, RunSettings.hrMax(ctx),
                            RunSettings.hrRest(ctx), RunSettings.cp(ctx)).take(4).joinToString("\n"), RunUi.MUTED, 12f))
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(RunUi.button(ctx, "Workouts & Plan", RunUi.ACCENT) {
                    startActivity(Intent(ctx, WorkoutsActivity::class.java))
                })
            })
            // Trail chosen for the next run (same on the watch).
            io.github.michealsizemore1.top25widget.TrailSync.current(ctx)?.let { (t, oab) ->
                addView(RunUi.text(ctx, "Trail: ${t.name} · " + String.format(java.util.Locale.US, "%.1f mi", t.lengthM / 1609.344 * (if (oab) 2 else 1)) +
                    (if (oab) " out & back" else ""), 0xFF46C46E.toInt(), 13f, true).apply { setPadding(0, RunUi.dp(ctx, 8), 0, 0) })
            }
            addView(RunUi.button(ctx, "Trails near me", 0xFF46C46E.toInt()) {
                startActivity(Intent(ctx, TrailsActivity::class.java))
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = RunUi.dp(ctx, 10) } })
        })

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
        col.addView(RunUi.section(this, "RUNS FROM YOUR WATCH"))
        if (runs.isEmpty()) {
            col.addView(RunUi.text(this, "No runs yet. Finish a run on the watch and it appears here automatically " +
                "(the watch sends it whenever it's connected to this phone).", RunUi.MUTED, 14f).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        }
        val fmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        runs.forEach { run ->
            val start = run.optLong("start")
            col.addView(RunUi.card(this).apply {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(RunUi.text(ctx, PhoneRuns.title(run), Color.WHITE, 17f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    val badges = buildList {
                        if (run.has("stryd")) add("Stryd ✓")
                        if (PhoneRuns.sentToCommandCenter(ctx, start)) add("CC ✓")
                    }.joinToString("  ")
                    addView(RunUi.text(ctx, badges, RunUi.MUTED, 12f))
                })
                addView(RunUi.text(ctx, fmt.format(Date(start)), RunUi.MUTED, 12f))
                addView(RunUi.statGrid(ctx, listOf(
                    Triple(RunMath.miles(run.optDouble("distance")), "miles", RunUi.ACCENT),
                    Triple(RunMath.clock(run.optInt("movingSeconds")), "time", Color.WHITE),
                    Triple(RunMath.paceFor(run.optInt("movingSeconds"), run.optDouble("distance")), "pace /mi", Color.WHITE),
                ) + listOfNotNull(
                    run.optInt("avgHr").takeIf { it > 0 }?.let { Triple("$it", "avg HR", RunUi.HR) },
                    run.optInt("avgPower").takeIf { it > 0 }?.let { Triple("$it", "avg W", RunUi.POWER) },
                    run.optInt("stress").takeIf { it > 0 }?.let { Triple("$it", "stress", Color.WHITE) },
                )))
                setOnClickListener { startActivity(Intent(ctx, RunDetailActivity::class.java).putExtra("start", start)) }
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
