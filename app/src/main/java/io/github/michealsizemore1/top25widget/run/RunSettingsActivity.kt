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
    /** null = the list of setting groups; otherwise the open group. */
    private var page: String? = null

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (page != null) { page = null; render() } else @Suppress("DEPRECATION") super.onBackPressed() }

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
        val titles = mapOf("run" to "Run options", "audio" to "Audio prompts", "zones" to "Zones & power", "cc" to "Command Center", "backup" to "Backup", "about" to "About")
        col.addView(RunUi.text(this, page?.let { titles[it] } ?: "Settings", Color.WHITE, 26f, true))
        val classic = getSharedPreferences("runsettings", 0).getString("menuStyle", "cards") == "classic"
        if (page == null && !classic) { renderList(col); return }
        /** In the classic style every group shows on one long page, with a heading. */
        fun on(key: String): Boolean {
            if (page == key) return true
            if (!(classic && page == null)) return false
            col.addView(RunUi.section(ctx, (titles[key] ?: key).uppercase()))
            return true
        }
        if (classic && page == null) {
            col.addView(RunUi.button(this, "Menu style: Classic  (tap for cards)", RunUi.CARD, Color.WHITE) { setMenuStyle("cards") }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) } })
            col.addView(RunUi.button(this, "Watch data screens ›", RunUi.ACCENT) { startActivity(Intent(ctx, ScreensActivity::class.java)) }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })
            col.addView(RunUi.button(this, "LiveTrack ›", 0xFF4FA3E0.toInt(), Color.WHITE) { startActivity(Intent(ctx, LiveTrackActivity::class.java)) }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })
        }
        val sync = RunUi.text(this, "Same on the watch — changes on either show on both.", RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(4), 0, 0) }
        if (page in setOf("run", "audio", "zones")) col.addView(sync)

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

        // ---- run options (same as the watch's Settings; changes on either show on both)
        fun switch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            col.addView(RunUi.card(ctx).apply {
                addView(Switch(ctx).apply {
                    text = title; setTextColor(Color.WHITE); textSize = 15f
                    isChecked = checked
                    setOnCheckedChangeListener { _, on -> onChange(on) }
                })
                if (sub.isNotBlank()) addView(RunUi.text(ctx, sub, RunUi.MUTED, 12f))
            })
        if (on("run")) {
        switch("Use GPS for pace & distance", "Off = Stryd footpod (recommended)", RunSettings.source(this) == "gps") {
            RunSettings.setSource(ctx, if (it) "gps" else "stryd")
        }
        switch("Auto-pause", "Pauses when you stop, resumes when you run", RunSettings.bool(this, "autoPause")) { RunSettings.setBool(ctx, "autoPause", it) }
        switch("Record GPS map track", "With Stryd pace; uses more battery", RunSettings.bool(this, "track")) {
            RunSettings.setBool(ctx, "track", it)
        }
        switch("Chest strap (H10) with wrist backup", "Off = watch wrist sensor only", RunSettings.bool(this, "useStrap")) { RunSettings.setBool(ctx, "useStrap", it) }
        switch("Keep watch screen on in BeyondStride", "", RunSettings.bool(this, "keepOn")) { RunSettings.setBool(ctx, "keepOn", it) }
        switch("Bottom button: start · lap · double-press finish", "", RunSettings.bool(this, "bottomBtn")) { RunSettings.setBool(ctx, "bottomBtn", it) }

        // ---- watch text size (same setting as on the watch)
        col.addView(RunUi.card(this).apply {
            val p = getSharedPreferences("runsettings", 0)
            val k = p.getFloat("textScale", 1.25f)
            val opts = listOf(1f to "Normal", 1.25f to "Large", 1.5f to "Extra large")
            addView(RunUi.text(ctx, "Watch text size: " + (opts.minByOrNull { kotlin.math.abs(it.first - k) }?.second ?: "Large") + "  ›", RunUi.ACCENT, 15f, true))
            addView(RunUi.text(ctx, "Labels and small text on the watch (big run numbers stay sized to fit)", RunUi.MUTED, 12f))
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx).setTitle("Watch text size").setItems(opts.map { it.second }.toTypedArray()) { _, i ->
                    p.edit().putFloat("textScale", opts[i].first).putLong("settingsAt", System.currentTimeMillis()).apply(); RunSettings.pushToWatch(ctx); render()
                }.show()
            }
        })

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

        }
        // ---- audio prompts
        if (on("audio")) {
        col.addView(RunUi.text(this, "Spoken on the watch: through Bluetooth headphones when connected, otherwise the watch speaker.",
            RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        switch("Audio prompts", "", RunSettings.bool(this, "voice")) { RunSettings.setBool(ctx, "voice", it) }
        switch("Each lap / mile", "Time, pace, heart rate, power", RunSettings.bool(this, "voiceLaps")) { RunSettings.setBool(ctx, "voiceLaps", it) }
        switch("Workout steps", "What's next, and step results", RunSettings.bool(this, "voiceSteps")) { RunSettings.setBool(ctx, "voiceSteps", it) }
        switch("Target alerts", "Speed up / ease off", RunSettings.bool(this, "voiceAlerts")) { RunSettings.setBool(ctx, "voiceAlerts", it) }
        switch("Mile alerts on easy (zone 2) days", "Each mile: time, pace and heart rate, during easy workouts from your plan", RunSettings.bool(this, "voiceMiles")) { RunSettings.setBool(ctx, "voiceMiles", it) }
        switch("Louder prompts", "Turns the volume up while speaking, then back", RunSettings.bool(this, "voiceBoost")) { RunSettings.setBool(ctx, "voiceBoost", it) }
        switch("Pause music while speaking", "Off = lower the music instead", getSharedPreferences("runsettings", 0).getString("voiceMusic", "duck") == "pause") {
            getSharedPreferences("runsettings", 0).edit().putString("voiceMusic", if (it) "pause" else "duck").putLong("settingsAt", System.currentTimeMillis()).apply()
            RunSettings.pushToWatch(ctx)
        }
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

        }
        // ---- zones
        if (on("zones")) {
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

        col.addView(RunUi.button(this, "Save zones", RunUi.ACCENT) {
            val mh = maxHr.text.toString().toDoubleOrNull(); val rh = restHr.text.toString().toDoubleOrNull()
            val c = cp.text.toString().toIntOrNull(); val k = cal.text.toString().toDoubleOrNull()
            if (mh == null || rh == null || c == null || k == null || mh <= rh || k !in 0.8..1.2) {
                Toast.makeText(ctx, "Check the numbers (calibration is usually 0.95–1.05)", Toast.LENGTH_LONG).show(); return@button
            }
            RunSettings.save(ctx, mh, rh, c, k, RunSettings.ccUrl(ctx))
            Toast.makeText(ctx, "Saved — sent to your watch", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })
        }
        // ---- Command Center
        if (on("cc")) {
            val cc = input("Command Center page", RunSettings.ccUrl(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            col.addView(RunUi.text(this, "Where finished runs are sent and where \"Send plan to BeyondStride\" comes from.", RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(6), 0, 0) })
            col.addView(RunUi.button(this, "Save", RunUi.ACCENT) {
                RunSettings.save(ctx, RunSettings.hrMax(ctx), RunSettings.hrRest(ctx), RunSettings.cp(ctx), RunSettings.cal(ctx), cc.text.toString().trim().ifBlank { RunSettings.ccUrl(ctx) })
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })
        }

        // (Stryd account sign-in removed: Stryd only has runs after the pod's offline sync, which isn't used.
        //  The pod itself still pairs with the watch for pace, distance, power and cadence.)
        getSharedPreferences("strydcloud", 0).edit().clear().apply()   // forget any old Stryd login

        // ---- backup
        if (on("backup")) {
        col.addView(RunUi.card(this).apply {
            val at = Backup.lastAt(ctx)
            addView(RunUi.text(ctx, if (at == 0L) "No backup yet" else "Last backup: " +
                java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(at)), Color.WHITE, 15f, true))
            Backup.lastInfo(ctx).takeIf { it.isNotBlank() }?.let { addView(RunUi.text(ctx, it, RunUi.MUTED, 12f)) }
            addView(RunUi.text(ctx, "Runs, workouts & plan, courses, settings, zones, LiveTrack, morning checks, sleep and personal bests — in one file. " +
                "Save it to Google Drive so it's safe if the phone is lost.", RunUi.DIM, 12f).apply { setPadding(0, dp(6), 0, 0) })
            addView(RunUi.button(ctx, if (Backup.uri(ctx) == null) "Back up now…" else "Back up now", RunUi.ACCENT) {
                val u = Backup.uri(ctx)
                if (u == null) newBackup() else Thread {
                    val r = try { "Backed up: " + Backup.write(ctx, u) } catch (e: Exception) { null }
                    runOnUiThread { if (r == null) newBackup() else { Toast.makeText(ctx, r, Toast.LENGTH_LONG).show(); render() } }
                }.start()
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) } })
            if (Backup.uri(ctx) != null) addView(RunUi.button(ctx, "Back up to a new file…", RunUi.CARD, Color.WHITE) { newBackup() }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
            addView(RunUi.button(ctx, "Restore from a backup…", RunUi.CARD, Color.WHITE) {
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")), 82)
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
        })
        switch("Update the backup after every run", "Keeps the saved backup file current automatically", Backup.auto(this)) { Backup.setAuto(ctx, it) }
        }

        if (on("about")) {
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
        }

        show(col)
    }

    private fun setMenuStyle(v: String) {
        getSharedPreferences("runsettings", 0).edit().putString("menuStyle", v).putLong("settingsAt", System.currentTimeMillis()).apply()
        RunSettings.pushToWatch(this); page = null; render()
    }

    private fun show(col: LinearLayout) = setContentView(ScrollView(this).apply {
        setBackgroundColor(RunUi.BG)
        addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    })

    /** The list of setting groups: icon · name · current value · ›  (like Gear Tracker II). */
    private fun renderList(col: LinearLayout) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val p = getSharedPreferences("runsettings", 0)
        fun onOff(b: Boolean) = if (b) "on" else "off"
        fun row(icon: String, title: String, value: String, onClick: () -> Unit) = col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = RunUi.rounded(RunUi.CARD, dp(18).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(RunUi.text(ctx, icon, RunUi.ACCENT, 26f, true).apply { gravity = android.view.Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), WRAP_CONTENT))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
                addView(RunUi.text(ctx, title, Color.WHITE, 18f))
                if (value.isNotBlank()) addView(RunUi.text(ctx, value, 0xFF5FD38D.toInt(), 13f))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(RunUi.text(ctx, "›", RunUi.MUTED, 28f))
            setOnClickListener { onClick() }
        })
        fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
        val screens = try { org.json.JSONArray(p.getString("screens", "[]")).length() } catch (e: Exception) { 0 }
        row("▦", "Watch data screens", if (screens > 0) "$screens screens" else "Choose what the watch shows") { startActivity(Intent(ctx, ScreensActivity::class.java)) }
        row("➤", "Run options", (if (RunSettings.source(ctx) == "gps") "GPS" else "Stryd") + " · auto-pause ${onOff(RunSettings.bool(ctx, "autoPause"))}" +
            " · HR cap " + when (p.getString("hrCap", "easy")) { "off" -> "off"; "all" -> "all runs"; else -> "easy days" }) { page = "run"; render() }
        row("♪", "Audio prompts", if (!RunSettings.bool(ctx, "voice")) "Off" else "On · mile alerts ${onOff(RunSettings.bool(ctx, "voiceMiles"))}" +
            (if (p.getString("voiceMusic", "duck") == "pause") " · pause music" else " · lower music")) { page = "audio"; render() }
        row("∿", "Zones & power", "Max ${fmt(RunSettings.hrMax(ctx))} · Rest ${fmt(RunSettings.hrRest(ctx))} · CP ${RunSettings.cp(ctx)} W") { page = "zones"; render() }
        row("◉", "LiveTrack", if (Live.db(ctx).isBlank()) "Not set up" else if (p.getBoolean("liveOn", false)) "On · ${Live.contacts(ctx).size} contact(s)" else "Off") {
            startActivity(Intent(ctx, LiveTrackActivity::class.java)) }
        row("⌂", "Command Center", try { Uri.parse(RunSettings.ccUrl(ctx)).host ?: "" } catch (e: Exception) { "" }) { page = "cc"; render() }
        row("⇪", "Backup", Backup.lastAt(ctx).takeIf { it > 0 }?.let { "Last: " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it)) +
            (if (Backup.auto(ctx) && Backup.uri(ctx) != null) " · auto" else "") } ?: "Not set up") { page = "backup"; render() }
        row("ⓘ", "About", "Phone app " + packageManager.getPackageInfo(packageName, 0).versionName) { page = "about"; render() }
        row("☰", "Menu style", "Cards · tap to switch back to classic (phone and watch)") { setMenuStyle("classic") }
        show(col)
    }

    private fun newBackup() {
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, Backup.fileName()), 81)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            81 -> {
                Backup.remember(this, uri)
                Thread {
                    val r = try { "Backed up: " + Backup.write(this, uri) } catch (e: Exception) { "Backup failed: ${e.message}" }
                    runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show(); render() }
                }.start()
            }
            82 -> Thread {
                val meta = Backup.peek(this, uri)
                runOnUiThread {
                    if (meta == null) { Toast.makeText(this, "That isn't a BeyondStride backup", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    val made = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date(meta.optLong("created")))
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Restore this backup?")
                        .setMessage("Made $made · ${meta.optInt("runs")} runs · ${meta.optInt("courses")} courses.\n\n" +
                            "Runs and courses in the backup are added back. Settings, zones, workouts and plan are replaced with the backup's. " +
                            "The watch gets them too.")
                        .setPositiveButton("Restore") { _, _ ->
                            Thread {
                                val r = try { Backup.restore(this, uri) } catch (e: Exception) { "Restore failed: ${e.message}" }
                                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show(); render() }
                            }.start()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.start()
        }
    }
}
