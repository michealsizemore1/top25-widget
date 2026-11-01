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

    private val ui get() = RunUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStravaRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Strava sends you back here after you approve: finish connecting. */
    private fun handleStravaRedirect(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "scoresrun") return
        val code = data.getQueryParameter("code")
        if (code == null) { Toast.makeText(this, "Strava wasn't connected", Toast.LENGTH_LONG).show(); return }
        Thread {
            val msg = try { Strava.exchangeCode(this, code); "Strava connected" } catch (e: Exception) { "Strava: ${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); render() }
        }.start()
    }

    private fun render() {
        val ctx = this
        val dp = { v: Int -> RunUi.dp(ctx, v) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(RunUi.text(ctx, "Running", Color.WHITE, 28f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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

        // Sending.
        val unsentCc = runs.map { it.optLong("start") }.filter { !PhoneRuns.sentToCommandCenter(this, it) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(RunUi.button(ctx, if (unsentCc.isEmpty()) "Command Center ✓" else "Send ${unsentCc.size} to Command Center",
                RunUi.CC, Color.WHITE) {
                if (unsentCc.isEmpty()) Toast.makeText(ctx, "All runs are already in the Command Center", Toast.LENGTH_SHORT).show()
                else { CommandCenter.send(ctx, unsentCc); }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(RunUi.button(ctx, if (Strava.connected(ctx)) "Strava ✓" else "Connect Strava", RunUi.STRAVA, Color.WHITE) {
                startActivity(Intent(ctx, RunSettingsActivity::class.java))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })

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
                        if (PhoneRuns.stravaId(ctx, start) > 0) add("Strava ✓")
                        else if (getSharedPreferences("runflags", 0).getString("strava_err_$start", null) != null) add("Strava ⚠")
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
