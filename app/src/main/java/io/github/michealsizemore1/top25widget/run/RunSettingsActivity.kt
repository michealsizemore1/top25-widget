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

        // ---- zones
        col.addView(RunUi.section(this, "ZONES · SENT TO YOUR WATCH"))
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
