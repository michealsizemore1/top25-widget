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

        // Destinations: Command Center, Stryd.
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
            val lp = { last: Boolean -> LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (!last) marginEnd = dp(8) } }
            addView(pill("Command Center", if (unsentCc.isEmpty()) "✓ all sent" else "Send ${unsentCc.size}", RunUi.CC) {
                if (unsentCc.isEmpty()) Toast.makeText(ctx, "All runs are already in the Command Center", Toast.LENGTH_SHORT).show()
                else CommandCenter.send(ctx, unsentCc)
            }, lp(false))
            addView(pill("Stryd", if (StrydCloud.connected(ctx)) "✓ connected" else "Connect", RunUi.STRYD) {
                if (StrydCloud.connected(ctx)) {
                    // Check for Stryd data on any run that doesn't have it yet.
                    val missing = runs.filter { !it.has("stryd") }.map { it.optLong("start") }
                    missing.forEach { StrydWorker.enqueue(ctx, it, delayMinutes = 0) }
                    Toast.makeText(ctx, if (missing.isEmpty()) "All runs have their Stryd data"
                        else "Checking Stryd for ${missing.size} run${if (missing.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
                } else startActivity(Intent(ctx, RunSettingsActivity::class.java))
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
