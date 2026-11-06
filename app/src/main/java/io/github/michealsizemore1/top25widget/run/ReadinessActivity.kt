package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.michealsizemore1.top25widget.CriticalPower
import io.github.michealsizemore1.top25widget.R
import io.github.michealsizemore1.top25widget.Readiness
import io.github.michealsizemore1.top25widget.ReadinessSync
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Sleep from Samsung Health (via Health Connect). This reads it and sends it to the watch.
 */
object SleepData {
    private const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    val PERMS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )
    val REQUEST = PERMS + BACKGROUND

    fun status(ctx: Context) = try { HealthConnectClient.getSdkStatus(ctx) } catch (e: Exception) { HealthConnectClient.SDK_UNAVAILABLE }
    fun available(ctx: Context) = status(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun granted(ctx: Context): Boolean = available(ctx) && try {
        runBlocking { HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS) }
    } catch (e: Exception) { false }

    fun requestIntent(ctx: Context): Intent = PermissionController.createRequestPermissionResultContract().createIntent(ctx, REQUEST)

    /** Reads the last [days] nights and sends them to the watch. Returns the number of nights, or null if it couldn't read. */
    fun refresh(ctx: Context, days: Int = 30): Int? {
        if (!available(ctx)) return null
        return try {
            val client = HealthConnectClient.getOrCreate(ctx)
            val zone = ZoneId.systemDefault()
            val end = Instant.now(); val start = end.minus(days.toLong(), ChronoUnit.DAYS)
            val sessions = runBlocking {
                client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))).records
            }
            val asleep = setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM)
            // One night = every session that's within 2 hours of the next (a bathroom trip often splits the
            // night in two). Time asleep = asleep stages (or whole sessions) with overlaps counted once,
            // so two apps logging the same night don't double it.
            fun asleepSpans(s: SleepSessionRecord): List<Pair<Instant, Instant>> =
                if (s.stages.isNotEmpty()) s.stages.filter { it.stage in asleep }.map { it.startTime to it.endTime }
                else listOf(s.startTime to s.endTime)
            val groups = ArrayList<MutableList<SleepSessionRecord>>()
            sessions.sortedBy { it.startTime }.forEach { s ->
                val g = groups.lastOrNull()
                if (g != null && ChronoUnit.MINUTES.between(g.maxOf { it.endTime }, s.startTime) <= 120) g += s else groups += mutableListOf(s)
            }
            fun minutesOf(g: List<SleepSessionRecord>): Long {
                val spans = g.flatMap { asleepSpans(it) }.sortedBy { it.first }
                if (spans.isEmpty()) return 0L
                var total = 0L; var curS = spans[0].first; var curE = spans[0].second
                for ((a, b) in spans.drop(1)) {
                    if (a > curE) { total += ChronoUnit.MINUTES.between(curS, curE); curS = a; curE = b }
                    else if (b > curE) curE = b
                }
                return total + ChronoUnit.MINUTES.between(curS, curE)
            }
            val byDay = groups.groupBy { g -> g.maxOf { it.endTime }.atZone(zone).toLocalDate().toString() }
            val nights = byDay.map { (day, gs) ->
                // The main night (longest); naps on their own only if that's all there is.
                val main = gs.maxBy { minutesOf(it) }
                val minutes = minutesOf(main).toInt()
                val first = main.minOf { it.startTime }; val last = main.maxOf { it.endTime }
                // Sleeping heart rate: the average of the lowest quarter of readings (recent nights only).
                val hr = if (ChronoUnit.DAYS.between(last, end) <= 14) try {
                    val bpm = runBlocking {
                        client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(first, last))).records
                    }.flatMap { r -> r.samples.map { it.beatsPerMinute.toInt() } }.filter { it in 30..150 }.sorted()
                    if (bpm.size >= 4) bpm.take(maxOf(2, bpm.size / 4)).average().toInt() else 0
                } catch (e: Exception) { 0 } else 0
                Readiness.Night(day, minutes, hr)
            }.filter { it.minutes > 0 }
            ReadinessSync.setNights(ctx, nights)
            nights.size
        } catch (e: Exception) { android.util.Log.w("SleepData", "read failed", e); null }
    }
}

/** Critical power from your runs: suggest (or apply) a new CP when the runs show it's changed. */
object CpUpdate {
    fun mode(ctx: Context) = ctx.getSharedPreferences("runsettings", 0).getString("cpAuto", "ask") ?: "ask"

    fun estimate(ctx: Context) = CriticalPower.estimate(PhoneRuns.list(ctx).take(200).mapNotNull { PhoneRuns.read(it) }, currentCp = RunSettings.cp(ctx))

    fun apply(ctx: Context, cp: Int) {
        RunSettings.save(ctx, RunSettings.hrMax(ctx), RunSettings.hrRest(ctx), cp, RunSettings.cal(ctx), RunSettings.ccUrl(ctx))
    }

    /** After a new run arrives from the watch. */
    fun afterRun(ctx: Context) {
        val m = mode(ctx); if (m == "off") return
        val e = estimate(ctx); val est = e.cp ?: return
        val cur = RunSettings.cp(ctx)
        val p = ctx.getSharedPreferences("runflags", 0)
        if (m == "auto" && est >= cur + 3) {
            apply(ctx, est)
            notify(ctx, "Critical power updated: $cur → $est W", e.reason)
        } else if (kotlin.math.abs(est - cur) >= 3 && p.getInt("cpAsked", 0) != est) {
            p.edit().putInt("cpAsked", est).apply()
            notify(ctx, "Your runs show a critical power of $est W", "Now $cur W. Tap to review and update your power zones.")
        }
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("runs", "Runs from watch", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 905, Intent(ctx, ReadinessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, "runs").setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(905, n) } catch (e: SecurityException) { }
    }
}

/** Readiness details, sleep connection, and critical power. Also Health Connect's privacy screen. */
class ReadinessActivity : Activity() {
    override fun onResume() {
        super.onResume()
        render()
        if (SleepData.available(this)) Thread {
            if (SleepData.granted(this) && System.currentTimeMillis() - ReadinessSync.nightsAt(this) > 10 * 60_000L) {
                SleepData.refresh(this); runOnUiThread { if (!isFinishing) render() }
            }
        }.start()
    }

    /** Android 14+: health data is a normal permission prompt (shown by Health Connect). Older: the Health Connect app. */
    private fun askHealth() {
        if (android.os.Build.VERSION.SDK_INT >= 34) { requestPermissions(SleepData.REQUEST.toTypedArray(), 50); return }
        try { @Suppress("DEPRECATION") startActivityForResult(SleepData.requestIntent(this), 50) } catch (e: Exception) {
            try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } catch (e2: Exception) { }
            Toast.makeText(this, "In Health Connect, open Sports Scores and allow Sleep and Heart rate", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 50) return
        if (!SleepData.granted(this)) {
            Toast.makeText(this, "Not allowed yet. Health Connect → See more health apps → Sports Scores → allow Sleep and Heart rate.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } catch (e: Exception) { }
            return
        }
        Thread {
            val n = SleepData.refresh(this)
            runOnUiThread { Toast.makeText(this, if (n == null) "Couldn't read sleep yet" else "$n nights of sleep found", Toast.LENGTH_SHORT).show(); render() }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 50) Thread {
            val n = SleepData.refresh(this)
            runOnUiThread { Toast.makeText(this, if (n == null) "Couldn't read sleep yet" else "$n nights of sleep found", Toast.LENGTH_SHORT).show(); render() }
        }.start()
    }

    private fun render() {
        val ctx = this
        val dp = { v: Int -> RunUi.dp(ctx, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(32)) }
        col.addView(RunUi.text(this, "Readiness", Color.WHITE, 26f, true))
        val runs = PhoneRuns.list(this).take(120).mapNotNull { PhoneRuns.read(it) }
        val r = Readiness.compute(ReadinessSync.checks(this), ReadinessSync.nights(this), runs, Readiness.today())
        col.addView(RunUi.card(this).apply {
            if (r == null) addView(RunUi.text(ctx, "No data yet. Do the 2½-minute morning check on the watch (BeyondStride → Readiness).", RunUi.MUTED, 14f))
            else {
                addView(RunUi.text(ctx, "${r.score}  ${r.label}", r.color, 30f, true))
                if (!r.checkedToday) addView(RunUi.text(ctx, "No morning check today — score uses sleep and training only.", RunUi.MUTED, 12f))
                r.parts.forEach { p ->
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
                        addView(RunUi.text(ctx, p.name, Color.WHITE, 15f, true), LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(RunUi.text(ctx, p.value, if (p.pts < 0) Color.WHITE else dot(p.pts), 15f, true))
                            addView(RunUi.text(ctx, p.note, RunUi.MUTED, 12f))
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    })
                }
                addView(RunUi.text(ctx, r.advice, Color.WHITE, 13f).apply { setPadding(0, dp(10), 0, 0) })
            }
        })

        // HRV history.
        val checks = ReadinessSync.checks(this).takeLast(14)
        if (checks.isNotEmpty()) {
            col.addView(RunUi.section(this, "MORNING CHECKS"))
            col.addView(RunUi.card(this).apply {
                val hi = checks.maxOf { it.rmssd }; val lo = checks.minOf { it.rmssd }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.BOTTOM
                    checks.forEach { c ->
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                            addView(RunUi.text(ctx, "${c.rmssd.toInt()}", RunUi.MUTED, 10f).apply { gravity = android.view.Gravity.CENTER })
                            addView(android.view.View(ctx).apply { background = RunUi.rounded(0xFF4FA3E0.toInt(), dp(3).toFloat()) },
                                LinearLayout.LayoutParams(dp(14), dp(10 + ((c.rmssd - lo) * 60 / maxOf(1.0, hi - lo)).toInt())))
                            addView(RunUi.text(ctx, c.day.substring(5).replace("-", "/").trimStart('0'), RunUi.DIM, 9f).apply { gravity = android.view.Gravity.CENTER })
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    }
                })
                addView(RunUi.text(ctx, "HRV (ms) each morning. Your own trend matters, not the number itself. " +
                    "Resting HR today: ${checks.last().hr} bpm.", RunUi.DIM, 11f).apply { setPadding(0, dp(6), 0, 0) })
            })
        }

        // Sleep from Samsung Health.
        col.addView(RunUi.section(this, "SLEEP (SAMSUNG HEALTH)"))
        col.addView(RunUi.card(this).apply {
            val nights = ReadinessSync.nights(ctx).takeLast(7)
            when {
                !SleepData.available(ctx) -> {
                    addView(RunUi.text(ctx, "Health Connect isn't available on this phone yet.", Color.WHITE, 14f))
                    addView(RunUi.button(ctx, "Get Health Connect", RunUi.ACCENT) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))) } catch (e: Exception) { }
                    })
                }
                nights.isEmpty() || !SleepData.granted(ctx) -> {
                    addView(RunUi.text(ctx, "Let BeyondStride read the sleep and heart rate Samsung Health saves to Health Connect. " +
                        "In Samsung Health: Settings → Health Connect → allow Sleep and Heart rate.", RunUi.MUTED, 13f))
                    addView(RunUi.button(ctx, "Connect sleep", RunUi.ACCENT) {
                        askHealth()
                    }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
                    if (SleepData.granted(ctx)) addView(RunUi.text(ctx, "Connected — no nights found yet. Check that Samsung Health is sharing Sleep with Health Connect.", RunUi.POWER, 12f).apply { setPadding(0, dp(6), 0, 0) })
                }
                else -> {
                    nights.reversed().forEach { n ->
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
                            setOnClickListener { editSleep(n.day) }
                            addView(RunUi.text(ctx, java.time.LocalDate.parse(n.day).let { it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.monthValue + "/" + it.dayOfMonth },
                                RunUi.MUTED, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                            addView(RunUi.text(ctx, (if (n.manual) "✎ " else "") + Readiness.hmm(n.minutes) + (if (n.sleepHr > 0) " · ${n.sleepHr} bpm" else ""),
                                if (n.manual) RunUi.ACCENT else Color.WHITE, 14f, true))
                        })
                    }
                    if (nights.none { it.day == Readiness.today() }) addView(RunUi.button(ctx, "Enter last night's sleep", RunUi.CARD, Color.WHITE) { editSleep(Readiness.today()) }
                        .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) } })
                    addView(RunUi.text(ctx, "Time asleep · sleeping heart rate. Tap a night to fix the time (e.g. a bathroom trip counted as awake). ✎ = entered by you.", RunUi.DIM, 11f).apply { setPadding(0, dp(4), 0, 0) })
                }
            }
        })

        // Critical power.
        // Resting heart rate for the zones, from the morning checks (H10, lying still after waking).
        col.addView(RunUi.section(this, "RESTING HEART RATE (ZONES)"))
        col.addView(RunUi.card(this).apply {
            val cur = RunSettings.hrRest(ctx)
            val mr = Readiness.morningRest(ReadinessSync.checks(ctx))
            fun f(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else String.format(java.util.Locale.US, "%.1f", v)
            addView(RunUi.text(ctx, "Zones use ${f(cur)} bpm" + (mr?.let { "  ·  14-day morning avg ${f(it.first)}" } ?: ""), Color.WHITE, 18f, true))
            if (mr == null) {
                val n = ReadinessSync.checks(ctx).count { it.hr > 0 && Readiness.daysBetween(it.day, Readiness.today()) in 0..13 }
                addView(RunUi.text(ctx, "Needs 5 morning checks in the last 14 days ($n so far).", RunUi.MUTED, 12f))
            } else {
                val d = mr.first - cur
                addView(RunUi.text(ctx, "From ${mr.second} morning checks. " + when {
                    kotlin.math.abs(d) < 1 -> "Matches your setting — nothing to change."
                    kotlin.math.abs(d) < 3 -> "Within ${f(kotlin.math.abs(d))} bpm — normal day-to-day variation, changing is optional."
                    else -> "${f(kotlin.math.abs(d))} bpm ${if (d > 0) "higher" else "lower"} than your setting — worth updating your zones."
                }, RunUi.MUTED, 12f))
                if (kotlin.math.abs(d) >= 1) addView(RunUi.button(ctx, "Use ${f(mr.first)} bpm for zones", if (kotlin.math.abs(d) >= 3) RunUi.ACCENT else RunUi.CARD,
                    if (kotlin.math.abs(d) >= 3) Color.BLACK else Color.WHITE) {
                    RunSettings.save(ctx, RunSettings.hrMax(ctx), mr.first, RunSettings.cp(ctx), RunSettings.cal(ctx), RunSettings.ccUrl(ctx))
                    Toast.makeText(ctx, "Resting HR set to ${f(mr.first)} — on the watch too", Toast.LENGTH_SHORT).show(); render()
                }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
            }
        })

        col.addView(RunUi.section(this, "CRITICAL POWER"))
        col.addView(RunUi.card(this).apply {
            val cur = RunSettings.cp(ctx)
            val e = CpUpdate.estimate(ctx)
            addView(RunUi.text(ctx, "Now $cur W" + (e.cp?.let { "  ·  your runs: $it W" } ?: ""), Color.WHITE, 18f, true))
            addView(RunUi.text(ctx, e.reason + (e.wPrimeKj?.takeIf { e.cp != null }?.let { String.format(java.util.Locale.US, " W′ %.1f kJ.", it) } ?: ""), RunUi.MUTED, 12f))
            e.bests.takeIf { it.isNotEmpty() }?.let { b ->
                addView(RunUi.text(ctx, "Best power, last 90 days: " + b.filter { it.sec in setOf(180, 300, 600, 720, 1200) }
                    .joinToString(" · ") { "${it.sec / 60} min ${it.watts} W" }, RunUi.DIM, 11f).apply { setPadding(0, dp(4), 0, 0) })
            }
            e.cp?.takeIf { kotlin.math.abs(it - cur) >= 3 }?.let { est ->
                addView(RunUi.button(ctx, "Use $est W", RunUi.POWER) {
                    CpUpdate.apply(ctx, est)
                    Toast.makeText(ctx, "Critical power set to $est W — on the watch too", Toast.LENGTH_SHORT).show(); render()
                }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
            }
            if (e.cp == null) addView(RunUi.text(ctx, CriticalPower.TEST, RunUi.ACCENT, 12f).apply { setPadding(0, dp(6), 0, 0) })
            val m = CpUpdate.mode(ctx)
            addView(RunUi.button(ctx, "Update from runs: " + when (m) { "auto" -> "Automatically (raises only)"; "off" -> "Off"; else -> "Ask me" }, RunUi.CARD, Color.WHITE) {
                val next = when (m) { "ask" -> "auto"; "auto" -> "off"; else -> "ask" }
                getSharedPreferences("runsettings", 0).edit().putString("cpAuto", next).putLong("settingsAt", System.currentTimeMillis()).apply()
                RunSettings.pushToWatch(ctx); render()
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
        })

        col.addView(RunUi.text(this, "Privacy: sleep and heart-rate data stay on your phone and watch. BeyondStride only reads them " +
            "to work out your readiness score; nothing is uploaded.", RunUi.DIM, 11f).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    /** Hours + minutes pickers for one night's time asleep. */
    private fun editSleep(day: String) {
        val n = ReadinessSync.nights(this).firstOrNull { it.day == day }
        val cur = n?.minutes?.takeIf { it > 0 } ?: 420
        val dp = { v: Int -> RunUi.dp(this, v) }
        val h = android.widget.NumberPicker(this).apply { minValue = 0; maxValue = 14; value = cur / 60 }
        val mins = (0..55 step 5).map { String.format(java.util.Locale.US, "%02d", it) }.toTypedArray()
        val m = android.widget.NumberPicker(this).apply { minValue = 0; maxValue = mins.size - 1; displayedValues = mins; value = (cur % 60) / 5 }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), 0)
            addView(h); addView(android.widget.TextView(context).apply { text = " h   " }); addView(m); addView(android.widget.TextView(context).apply { text = " min" })
        }
        val date = java.time.LocalDate.parse(day)
        val b = android.app.AlertDialog.Builder(this)
            .setTitle("Time asleep · ${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.monthValue}/${date.dayOfMonth}")
            .setMessage(if (n != null && n.auto > 0) "The watch measured ${Readiness.hmm(n.auto)}." else "Enter how long you slept.")
            .setView(row)
            .setPositiveButton("Save") { _, _ -> ReadinessSync.setManual(this, day, h.value * 60 + m.value * 5); render() }
            .setNegativeButton("Cancel", null)
        if (n?.manual == true && n.auto > 0) b.setNeutralButton("Use watch's ${Readiness.hmm(n.auto)}") { _, _ -> ReadinessSync.setManual(this, day, null); render() }
        b.show()
    }

    private fun dot(pts: Int) = when { pts >= 70 -> Readiness.GREEN; pts >= 50 -> Readiness.YELLOW; else -> Readiness.RED }
}
