package io.github.michealsizemore1.top25widget.run

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** LiveTrack settings and the phone's part: texting your link when a run starts, and stop alerts. */
object Live {
    const val VIEWER = "https://michealsizemore1.github.io/ChatGPT-The-Master-App/livetrack.html"
    private fun p(ctx: Context) = ctx.getSharedPreferences("runsettings", 0)

    fun db(ctx: Context) = (p(ctx).getString("liveDb", "") ?: "").trim().removeSuffix("/")
    fun id(ctx: Context): String {
        p(ctx).getString("liveId", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"; val r = SecureRandom()
        val id = (1..20).map { abc[r.nextInt(abc.length)] }.joinToString("")
        p(ctx).edit().putString("liveId", id).putLong("settingsAt", System.currentTimeMillis()).apply()
        RunSettings.pushToWatch(ctx)
        return id
    }
    fun link(ctx: Context) = "$VIEWER#db=" + Uri.encode(db(ctx).removePrefix("https://")) + "&id=" + id(ctx)
    fun name(ctx: Context) = p(ctx).getString("liveName", "")?.takeIf { it.isNotBlank() } ?: "Micheal"
    fun contacts(ctx: Context) = (p(ctx).getString("liveContacts", "") ?: "").split(",", ";", "\n").map { it.trim() }.filter { it.count(Char::isDigit) >= 7 }
    fun smsOn(ctx: Context) = p(ctx).getBoolean("liveSms", true)

    private fun text(ctx: Context, msg: String) {
        val to = contacts(ctx)
        if (to.isEmpty()) return
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            notify(ctx, "LiveTrack: couldn't text your contacts", "Allow SMS for Running in LiveTrack settings", Intent(ctx, LiveTrackActivity::class.java)); return
        }
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        to.forEach { n -> try { sms.sendMultipartTextMessage(n, null, sms.divideMessage(msg), null, null) } catch (e: Exception) { } }
    }

    /** Watch started a run with LiveTrack on. */
    fun started(ctx: Context) {
        if (smsOn(ctx)) text(ctx, "${name(ctx)} started a run. Follow live: ${link(ctx)}")
        notify(ctx, "LiveTrack is on", if (smsOn(ctx) && contacts(ctx).isNotEmpty()) "Link texted to ${contacts(ctx).size} contact(s)" else "Tap to share your link",
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Follow my run live: ${link(ctx)}"))
    }

    /** Watch says you haven't moved for a while. */
    fun alert(ctx: Context, loc: String) {
        val where = if (loc.contains(",")) " Last location: https://maps.google.com/?q=$loc" else ""
        text(ctx, "Alert from ${name(ctx)}'s run: no movement for ${p(ctx).getInt("liveStopMin", 10)} minutes.$where Live: ${link(ctx)}")
        notify(ctx, "LiveTrack alert sent", "You stopped moving during your run", Intent(Intent.ACTION_VIEW, Uri.parse(link(ctx))))
    }

    private fun notify(ctx: Context, title: String, body: String, open: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("livetrack", "LiveTrack", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, title.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, "livetrack").setSmallIcon(io.github.michealsizemore1.top25widget.R.drawable.ic_stat_run)
            .setContentTitle(title).setContentText(body).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(title.hashCode(), n) } catch (e: SecurityException) { }
    }

    /** Writes a test record so you can check the link works. Off the main thread. */
    fun test(ctx: Context): String = try {
        val url = "${db(ctx).let { if (it.startsWith("http")) it else "https://$it" }}/live/${id(ctx)}.json"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("now", JSONObject().put("state", "test").put("updated", System.currentTimeMillis())
            .put("name", name(ctx)).put("start", System.currentTimeMillis()).put("dist", 0).put("moving", 0))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        when (val code = c.responseCode) {
            in 200..299 -> "ok"
            401, 403 -> "The database refused it — check the Rules step"
            404 -> "Database not found — check the URL"
            else -> "Database said $code"
        }
    } catch (e: Exception) { "Couldn't reach the database: ${e.message}" }
}

class LiveTrackActivity : Activity() {
    private fun p() = getSharedPreferences("runsettings", 0)
    private fun setPref(f: (android.content.SharedPreferences.Editor) -> Unit) {
        val e = p().edit(); f(e); e.putLong("settingsAt", System.currentTimeMillis()).apply(); RunSettings.pushToWatch(this)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        col.addView(RunUi.text(this, "LiveTrack", Color.WHITE, 28f, true))
        col.addView(RunUi.text(this, "While you run, your watch shares your location and stats over LTE. People with your link follow " +
            "along on a live map. If you stop moving too long, your contacts get an alert.", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        fun input(label: String, value: String, type: Int, hint: String = ""): EditText {
            col.addView(RunUi.text(ctx, label, RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(12), 0, dp(2)) })
            return EditText(ctx).apply {
                setText(value); this.hint = hint; inputType = type
                setTextColor(Color.WHITE); setHintTextColor(RunUi.DIM)
                background = RunUi.rounded(RunUi.CARD, dp(12).toFloat()); setPadding(dp(14), dp(10), dp(14), dp(10))
                col.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        fun switch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) = col.addView(RunUi.card(ctx).apply {
            addView(Switch(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 15f; isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) } })
            if (sub.isNotBlank()) addView(RunUi.text(ctx, sub, RunUi.MUTED, 12f))
        })

        val ready = Live.db(this).isNotBlank()
        if (ready) switch("LiveTrack on every run", "Turns GPS on during runs (pace still from Stryd). Same switch on the watch.",
            p().getBoolean("liveOn", false)) { setPref { e -> e.putBoolean("liveOn", it) } }

        col.addView(RunUi.section(this, "YOUR DATABASE"))
        if (!ready) col.addView(RunUi.card(this).apply {
            addView(RunUi.text(ctx, "One-time setup (about 5 minutes, free):", Color.WHITE, 14f, true))
            addView(RunUi.text(ctx, "1. On a computer, go to console.firebase.google.com and sign in with Google.\n" +
                "2. Add project → name it \"livetrack\" → you can turn Google Analytics off → Create.\n" +
                "3. Left menu: Build → Realtime Database → Create database → United States → Start in locked mode → Enable.\n" +
                "4. Open the Rules tab, replace everything with the rules below, and tap Publish.\n" +
                "5. Copy the database address shown at the top of the Data tab (ends in firebaseio.com or firebasedatabase.app) and paste it here.",
                RunUi.MUTED, 13f).apply { setPadding(0, dp(6), 0, 0) })
        })
        val rules = "{\n  \"rules\": {\n    \"live\": {\n      \"\$id\": {\n        \".read\": true,\n        \".write\": \"\$id.length >= 16\"\n      }\n    }\n  }\n}"
        col.addView(RunUi.text(this, if (ready) "Rules (already set up? skip this)  ›  tap to copy" else "Rules — tap to copy", RunUi.ACCENT, 13f, true).apply {
            setPadding(dp(4), dp(10), 0, 0)
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("rules", rules))
                Toast.makeText(ctx, "Rules copied", Toast.LENGTH_SHORT).show()
            }
        })
        val dbIn = input("Database address", Live.db(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "https://livetrack-xxxx-default-rtdb.firebaseio.com")
        val nameIn = input("Your name (shown to viewers)", Live.name(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)

        col.addView(RunUi.section(this, "WHO GETS YOUR LINK"))
        val contactsIn = input("Phone numbers to text (comma-separated)", p().getString("liveContacts", "") ?: "", InputType.TYPE_CLASS_PHONE, "208-555-1234, 208-555-9876")
        switch("Text them the link when a run starts", "Sent from this phone, like Garmin LiveTrack emails", Live.smsOn(this)) {
            p().edit().putBoolean("liveSms", it).apply()
            if (it && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
        }
        val stopOpts = listOf(0, 5, 10, 15, 20, 30)
        col.addView(RunUi.card(this).apply {
            val m = p().getInt("liveStopMin", 10)
            addView(RunUi.text(ctx, "Alert if not moving for: " + (if (m == 0) "Off" else "$m minutes") + "  ›", RunUi.ACCENT, 15f, true))
            addView(RunUi.text(ctx, "Doesn't count while you've paused the run. The watch warns you a minute before.", RunUi.MUTED, 12f))
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx).setTitle("Alert if not moving for")
                    .setItems(stopOpts.map { if (it == 0) "Off" else "$it minutes" }.toTypedArray()) { _, i -> setPref { e -> e.putInt("liveStopMin", stopOpts[i]) }; render() }.show()
            }
        })

        col.addView(RunUi.button(this, "Save", RunUi.ACCENT) {
            var db = dbIn.text.toString().trim().removeSuffix("/")
            if (db.isNotBlank() && !db.startsWith("http")) db = "https://$db"
            setPref { e -> e.putString("liveDb", db).putString("liveName", nameIn.text.toString().trim()) }
            p().edit().putString("liveContacts", contactsIn.text.toString().trim()).apply()
            if (db.isNotBlank()) Live.id(ctx)
            if (Live.smsOn(ctx) && Live.contacts(ctx).isNotEmpty() && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
            Toast.makeText(ctx, "Saved — sent to your watch", Toast.LENGTH_SHORT).show(); render()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })

        if (ready) {
            col.addView(RunUi.section(this, "YOUR LINK"))
            col.addView(RunUi.text(this, Live.link(this), RunUi.MUTED, 12f).apply { setPadding(dp(4), dp(4), dp(4), 0); setTextIsSelectable(true) })
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
                addView(RunUi.button(ctx, "Test it", RunUi.CARD, Color.WHITE) {
                    Toast.makeText(ctx, "Testing…", Toast.LENGTH_SHORT).show()
                    Thread { val r = Live.test(ctx); runOnUiThread {
                        if (r == "ok") startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Live.link(ctx))))
                        else Toast.makeText(ctx, r, Toast.LENGTH_LONG).show()
                    } }.start()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(RunUi.button(ctx, "Share link", RunUi.ACCENT) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Follow my runs live: ${Live.link(ctx)}"), "Share LiveTrack link"))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            })
            col.addView(RunUi.text(this, "The link stays the same for every run, so people can bookmark it. It shows your current run, " +
                "or your last one when you're not running.", RunUi.DIM, 12f).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(RunUi.BG); addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
    }
}
