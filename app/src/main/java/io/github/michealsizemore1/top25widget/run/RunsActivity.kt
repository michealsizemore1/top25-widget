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
open class RunsActivity : Activity() {

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
        io.github.michealsizemore1.top25widget.Theme.load(this, "runsettings")
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
            addView(RunUi.text(ctx, if (this@RunsActivity is AllRunsActivity) "Runs" else "BeyondStride", Color.WHITE, 28f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(RunUi.button(ctx, "Settings", RunUi.CARD, Color.WHITE) {
                startActivity(Intent(ctx, RunSettingsActivity::class.java))
            })
        })

        val files = PhoneRuns.list(this)
        val runs = files.mapNotNull { PhoneRuns.read(it) }
        val showAll = this is AllRunsActivity
        if (!showAll) {
        val I = io.github.michealsizemore1.top25widget.Insights
        val R = io.github.michealsizemore1.top25widget.Readiness
        val hrMax = RunSettings.hrMax(this); val hrRest = RunSettings.hrRest(this)
        fun divider() = android.view.View(ctx).apply { setBackgroundColor(0x22FFFFFF) }
            .also { it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) } }

        // 1. TODAY: readiness + today's workout (+ trail).
        val rd = R.compute(io.github.michealsizemore1.top25widget.ReadinessSync.checks(this),
            io.github.michealsizemore1.top25widget.ReadinessSync.nights(this), runs.take(120), R.today())
        val todayW = WorkoutStore.plan(this).optJSONObject(WorkoutStore.dayFmt.format(Date()))
        col.addView(RunUi.section(this, "TODAY"))
        col.addView(RunUi.card(this).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                if (rd == null) addView(RunUi.text(ctx, "Readiness: do the 2½-min morning check on the watch ▸", RunUi.ACCENT, 15f, true))
                else {
                    addView(RunUi.text(ctx, "${rd.score}  ${rd.label}", rd.color, 22f, true))
                    if (!rd.checkedToday) addView(RunUi.text(ctx, "No morning check yet — based on sleep and training", RunUi.MUTED, 12f))
                    addView(RunUi.text(ctx, rd.parts.filter { it.pts >= 0 }.joinToString(" · ") { "${it.name} ${it.value}" }, RunUi.MUTED, 12f))
                    addView(RunUi.text(ctx, rd.advice.substringBefore(". ") + ".", Color.WHITE, 13f).apply { setPadding(0, dp(4), 0, 0) })
                }
                setOnClickListener { startActivity(Intent(ctx, ReadinessActivity::class.java)) }
            })
            addView(divider())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(RunUi.text(ctx, todayW?.optString("name") ?: "No workout planned", Color.WHITE, 17f, true))
                todayW?.let { w ->
                    addView(RunUi.text(ctx, io.github.michealsizemore1.top25widget.Workouts.outline(w, hrMax, hrRest, RunSettings.cp(ctx)).take(4).joinToString("\n"), RunUi.MUTED, 12f))
                }
                io.github.michealsizemore1.top25widget.TrailSync.current(ctx)?.let { (t, oab) ->
                    addView(RunUi.text(ctx, "Trail: ${t.name} · " + String.format(Locale.US, "%.1f mi", t.lengthM / 1609.344 * (if (oab) 2 else 1)) +
                        (if (oab) " out & back" else ""), 0xFF46C46E.toInt(), 13f, true).apply { setPadding(0, dp(6), 0, 0) })
                }
                setOnClickListener { startActivity(Intent(ctx, WorkoutsActivity::class.java)) }
            })
        })

        // 2. Command Center + LiveTrack.
        val unsentCc = runs.map { it.optLong("start") }.filter { !PhoneRuns.sentToCommandCenter(this, it) }
        val live = getSharedPreferences("runsettings", 0).getBoolean("liveOn", false)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            fun tile(title: String, status: String, color: Int, onClick: () -> Unit) = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = RunUi.rounded(color, dp(16).toFloat())
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(RunUi.text(ctx, title, Color.WHITE, 13f, true).apply { gravity = Gravity.CENTER })
                addView(RunUi.text(ctx, status, 0xCCFFFFFF.toInt(), 11f).apply { gravity = Gravity.CENTER })
                setOnClickListener { onClick() }
            }
            val lp = { last: Boolean -> LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (!last) marginEnd = dp(6) } }
            addView(tile("Command Center", if (unsentCc.isEmpty()) "✓ sent" else "Send ${unsentCc.size}", RunUi.CC) {
                if (unsentCc.isEmpty()) Toast.makeText(ctx, "All runs are already in the Command Center", Toast.LENGTH_SHORT).show()
                else CommandCenter.send(ctx, unsentCc)
            }, lp(false))
            addView(tile("LiveTrack", if (live) "● ON" else "Off", if (live) 0xFF1F6FB2.toInt() else RunUi.CARD) {
                if (Live.db(ctx).isBlank()) startActivity(Intent(ctx, LiveTrackActivity::class.java))
                else {
                    RunSettings.setBool(ctx, "liveOn", !live)
                    Toast.makeText(ctx, if (!live) "LiveTrack on — on your watch too" else "LiveTrack off", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }, lp(true))
        })

        // 3. THIS WEEK: miles, runs, training load.
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis
        cal.timeInMillis = System.currentTimeMillis(); cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        val monthStart = cal.timeInMillis
        fun miles(from: Long) = runs.filter { it.optLong("start") >= from }.sumOf { it.optDouble("distance") }
        val ld = I.load(runs.take(200))
        col.addView(RunUi.section(this, "THIS WEEK"))
        col.addView(RunUi.card(this).apply {
            addView(RunUi.statGrid(ctx, listOf(
                Triple(RunMath.miles(miles(weekStart)), "miles", Color.WHITE),
                Triple("${runs.count { it.optLong("start") >= weekStart }}", "runs", Color.WHITE),
                Triple(RunMath.miles(miles(monthStart)), "this month", Color.WHITE),
            )))
            if (runs.isNotEmpty()) {
                addView(RunUi.text(ctx, "Load: ${ld.status}" + (ld.ratio?.let { String.format(Locale.US, " · ratio %.2f", it) } ?: "") +
                    "  ·  ${ld.acute} stress, last 7 days", ld.color, 13f, true).apply { setPadding(0, dp(8), 0, 0) })
                if (ld.ratio != null && ld.ratio > 1.3) addView(RunUi.text(ctx, ld.advice, RunUi.MUTED, 12f))
            }
            setOnClickListener { startActivity(Intent(ctx, FitnessActivity::class.java)) }
        })

        }  // !showAll
        col.addView(RunUi.section(this, if (showAll) "ALL RUNS" else "LAST RUN"))
        if (runs.isEmpty()) {
            col.addView(RunUi.text(this, "No runs yet. Finish a run on the watch and it appears here automatically " +
                "(the watch sends it whenever it's connected to this phone).", RunUi.MUTED, 14f).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        }
        val fmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        (if (showAll) runs else runs.take(1)).forEach { run ->
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
                    Triple(RunMath.miles(run.optDouble("distance")), "miles", Color.WHITE),
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
        setContentView(RunUi.tabbed(this, ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, if (showAll) RunUi.TAB_RUNS else RunUi.TAB_TODAY))
    }
}

/** Every run (the Runs tab). */
class AllRunsActivity : RunsActivity()
