package io.github.michealsizemore1.top25widget.run

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import io.github.michealsizemore1.top25widget.RunMath

/** Zones (sent to the watch), Command Center link, and Stryd connection. */
class RunSettingsActivity : Activity() {

    override fun onResume() {
        super.onResume()
        io.github.michealsizemore1.top25widget.SettingsSync.watch(this, "runsettings", "phone")
        render()
    }

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }
        col.addView(RunUi.text(this, "Run settings", Color.WHITE, 26f, true))

        fun input(label: String, value: String, type: Int, hint: String = ""): EditText {
            col.addView(RunUi.text(ctx, label, RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(12), 0, dp(2)) })
            return EditText(ctx).apply {
                setText(value); this.hint = hint; inputType = type
                setTextColor(Color.WHITE); setHintTextColor(RunUi.DIM)
                background = RunUi.rounded(RunUi.CARD, dp(12).toFloat())
                setPadding(dp(14), dp(10), dp(14), dp(10))
                col.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        val num = InputType.TYPE_CLASS_NUMBER
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

        // ---- watch screens
        col.addView(RunUi.button(this, "Watch data screens ›", RunUi.ACCENT) {
            startActivity(Intent(ctx, ScreensActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) } })

        col.addView(RunUi.button(this, "LiveTrack ›", 0xFF4FA3E0.toInt(), Color.WHITE) {
            startActivity(Intent(ctx, LiveTrackActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })

        // ---- run options (same as the watch's Settings; changes on either show on both)
        col.addView(RunUi.section(this, "RUN OPTIONS · SAME ON WATCH"))
        fun switch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            col.addView(RunUi.card(ctx).apply {
                addView(Switch(ctx).apply {
                    text = title; setTextColor(Color.WHITE); textSize = 15f
                    isChecked = checked
                    setOnCheckedChangeListener { _, on -> onChange(on) }
                })
                if (sub.isNotBlank()) addView(RunUi.text(ctx, sub, RunUi.MUTED, 12f))
            })
        switch("Use GPS for pace & distance", "Off = Stryd footpod (recommended)", RunSettings.source(this) == "gps") {
            RunSettings.setSource(ctx, if (it) "gps" else "stryd")
        }
        switch("Auto-pause", "Pauses when you stop, resumes when you run", RunSettings.bool(this, "autoPause")) { RunSettings.setBool(ctx, "autoPause", it) }
        switch("Record GPS map track", "With Stryd pace; uses more battery", RunSettings.bool(this, "track")) {
            RunSettings.setBool(ctx, "track", it)
        }
        switch("Chest strap (H10) with wrist backup", "Off = watch wrist sensor only", RunSettings.bool(this, "useStrap")) { RunSettings.setBool(ctx, "useStrap", it) }
        switch("Keep watch screen on in Running", "", RunSettings.bool(this, "keepOn")) { RunSettings.setBool(ctx, "keepOn", it) }
        switch("Bottom button: start · lap · double-press finish", "", RunSettings.bool(this, "bottomBtn")) { RunSettings.setBool(ctx, "bottomBtn", it) }
        switch("Gestures: double-tap = lap · hold = finish", "", RunSettings.bool(this, "gestures")) { RunSettings.setBool(ctx, "gestures", it) }

        // ---- easy-day heart-rate cap
        col.addView(RunUi.card(this).apply {
            val p = getSharedPreferences("runsettings", 0)
            val mode = p.getString("hrCap", "easy") ?: "easy"; val zone = p.getInt("hrCapZone", 2)
            val b = RunMath.hrBounds(RunSettings.hrMax(ctx), RunSettings.hrRest(ctx))
            val cap = if (zone in 1..4) b[zone] - 1 else RunSettings.hrMax(ctx).toInt()
            addView(RunUi.text(ctx, "Easy-day heart-rate cap: " + when (mode) { "off" -> "Off"; "all" -> "Easy days + free runs"; else -> "Easy days" } + "  ›", RunUi.ACCENT, 15f, true))
            addView(RunUi.text(ctx, "Buzz + \"ease up\" when you go over Zone $zone ($cap bpm) on easy runs, recovery runs, long runs " +
                "and the easy parts of workouts. No \"speed up\" nags on easy steps.", RunUi.MUTED, 12f))
            setOnClickListener {
                val opts = listOf("off" to "Off", "easy" to "Easy days (from your plan)", "all" to "Easy days + free runs")
                android.app.AlertDialog.Builder(ctx).setTitle("Easy-day heart-rate cap").setItems(opts.map { it.second }.toTypedArray()) { _, i ->
                    p.edit().putString("hrCap", opts[i].first).putLong("settingsAt", System.currentTimeMillis()).apply(); RunSettings.pushToWatch(ctx)
                    if (opts[i].first != "off") android.app.AlertDialog.Builder(ctx).setTitle("Stay at or below")
                        .setItems((1..3).map { z -> "Zone $z (under ${b[z] - 1} bpm)" }.toTypedArray()) { _, j ->
                            p.edit().putInt("hrCapZone", j + 1).putLong("settingsAt", System.currentTimeMillis()).apply(); RunSettings.pushToWatch(ctx); render()
                        }.setOnCancelListener { render() }.show()
                    else render()
                }.show()
            }
        })

        // ---- audio prompts
        col.addView(RunUi.section(this, "AUDIO PROMPTS · SAME ON WATCH"))
        col.addView(RunUi.text(this, "Spoken on the watch: through Bluetooth headphones when connected, otherwise the watch speaker.",
            RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        switch("Audio prompts", "", RunSettings.bool(this, "voice")) { RunSettings.setBool(ctx, "voice", it) }
        switch("Each lap / mile", "Time, pace, heart rate, power", RunSettings.bool(this, "voiceLaps")) { RunSettings.setBool(ctx, "voiceLaps", it) }
        switch("Workout steps", "What's next, and step results", RunSettings.bool(this, "voiceSteps")) { RunSettings.setBool(ctx, "voiceSteps", it) }
        switch("Target alerts", "Speed up / ease off", RunSettings.bool(this, "voiceAlerts")) { RunSettings.setBool(ctx, "voiceAlerts", it) }
        val everyOpts = listOf(0, 5, 10, 15, 20, 30)
        col.addView(RunUi.card(this).apply {
            val every = RunSettings.voiceEvery(ctx)
            addView(RunUi.text(ctx, "Time update every: " + (if (every == 0) "Off" else "$every minutes") + "  ›", RunUi.ACCENT, 15f, true))
            addView(RunUi.text(ctx, "Distance, time, average pace, heart rate", RunUi.MUTED, 12f))
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx).setTitle("Time update every")
                    .setItems(everyOpts.map { if (it == 0) "Off" else "$it minutes" }.toTypedArray()) { _, i ->
                        RunSettings.setVoiceEvery(ctx, everyOpts[i]); render()
                    }.show()
            }
        })

        // ---- zones
        col.addView(RunUi.section(this, "ZONES · SAME ON WATCH"))
        val maxHr = input("Max heart rate (bpm)", fmt(RunSettings.hrMax(this)), num)
        val restHr = input("Resting heart rate (bpm)", fmt(RunSettings.hrRest(this)), dec)
        val cp = input("Critical Power (W)", RunSettings.cp(this).toString(), num)
        val cal = input("Stryd calibration factor", String.format(java.util.Locale.US, "%.3f", RunSettings.cal(this)), dec)
        val preview = RunUi.text(this, "", RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(8), 0, 0) }
        col.addView(preview)
        fun updatePreview() {
            val b = RunMath.hrBounds(maxHr.text.toString().toDoubleOrNull() ?: 185.0, restHr.text.toString().toDoubleOrNull() ?: 43.5)
            val p = RunMath.powerBounds(cp.text.toString().toIntOrNull() ?: 333)
            preview.text = "HR zones: " + (0..4).joinToString(" · ") { "Z${it + 1} ${b[it]}" + if (it == 4) "+" else "" } +
                "\nPower zones: " + (0..4).joinToString(" · ") { "Z${it + 1} ${p[it]}" + if (it == 4) "+ W" else "" }
        }
        updatePreview()
        listOf(maxHr, restHr, cp).forEach { it.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }) }

        // ---- Command Center
        col.addView(RunUi.section(this, "MY LIFE COMMAND CENTER"))
        val cc = input("Command Center page", RunSettings.ccUrl(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        col.addView(RunUi.button(this, "Save settings", RunUi.ACCENT) {
            val mh = maxHr.text.toString().toDoubleOrNull(); val rh = restHr.text.toString().toDoubleOrNull()
            val c = cp.text.toString().toIntOrNull(); val k = cal.text.toString().toDoubleOrNull()
            if (mh == null || rh == null || c == null || k == null || mh <= rh || k !in 0.8..1.2) {
                Toast.makeText(ctx, "Check the numbers (calibration is usually 0.95–1.05)", Toast.LENGTH_LONG).show(); return@button
            }
            RunSettings.save(ctx, mh, rh, c, k, cc.text.toString().trim().ifBlank { RunSettings.ccUrl(ctx) })
            Toast.makeText(ctx, "Saved — sent to your watch", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })

        // ---- Stryd
        col.addView(RunUi.section(this, "STRYD"))
        if (StrydCloud.connected(this)) {
            col.addView(RunUi.card(this).apply {
                addView(RunUi.text(ctx, "Connected as ${StrydCloud.email(ctx)}", Color.WHITE, 16f, true))
                addView(Switch(ctx).apply {
                    text = "Add Stryd data to new runs automatically"; setTextColor(Color.WHITE)
                    isChecked = StrydCloud.autoFetch(ctx)
                    setOnCheckedChangeListener { _, on -> StrydCloud.setAutoFetch(ctx, on) }
                    setPadding(0, dp(10), 0, 0)
                })
                addView(RunUi.text(ctx, "Sign out", RunUi.MUTED, 14f, true).apply {
                    setPadding(0, dp(12), 0, 0)
                    setOnClickListener { StrydCloud.signOut(ctx); render() }
                })
            })
        } else {
            col.addView(RunUi.text(this,
                "Sign in with your Stryd app login. After each run the phone finds the same run in your Stryd account " +
                "and adds ground time, oscillation, leg spring stiffness, elevation, RSS, weather, and Air Power when Stryd has it. " +
                "Your login stays on this phone. Stryd has no official public connection, so this uses the same sign-in " +
                "as the Stryd website and could stop working if Stryd changes it.", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            val email = input("Stryd email", StrydCloud.email(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val pw = input("Stryd password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            col.addView(RunUi.button(this, "Connect Stryd", RunUi.STRYD, Color.WHITE) {
                if (email.text.isBlank() || pw.text.isEmpty()) {
                    Toast.makeText(ctx, "Enter your Stryd email and password", Toast.LENGTH_SHORT).show(); return@button
                }
                val e = email.text.toString(); val p = pw.text.toString()
                Thread {
                    val msg = try {
                        StrydCloud.signIn(ctx, e, p)
                        // Fill in Stryd data for runs already on the phone.
                        PhoneRuns.list(ctx).mapNotNull { PhoneRuns.read(it) }.filter { !it.has("stryd") }
                            .forEach { StrydWorker.enqueue(ctx, it.optLong("start"), delayMinutes = 0) }
                        "Stryd connected"
                    } catch (ex: Exception) { "Stryd: ${ex.message}" }
                    runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); render() }
                }.start()
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) } })
        }

        // ---- Versions
        col.addView(RunUi.section(this, "APP VERSIONS"))
        col.addView(RunUi.text(this, "Phone app " + packageManager.getPackageInfo(packageName, 0).versionName, RunUi.MUTED, 13f)
            .apply { setPadding(dp(4), dp(6), 0, 0) })
        val watchVer = RunUi.text(this, "Watch app: checking…", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(4), 0, 0) }
        col.addView(watchVer)
        Thread {
            val v = try {
                val items = com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getDataClient(ctx)
                    .getDataItems(android.net.Uri.parse("wear://*/watchinfo")))
                val found = items.map { com.google.android.gms.wearable.DataMapItem.fromDataItem(it).dataMap.getString("version") }.firstOrNull()
                items.release()
                found?.let { "Watch app $it" } ?: "Watch app: open Run on the watch once to show its build"
            } catch (e: Exception) { "Watch app: not connected" }
            runOnUiThread { watchVer.text = v }
        }.start()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(RunUi.BG)
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
