package io.github.michealsizemore1.top25widget.run

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * LiveTrack: while you run, the watch posts where you are and how it's going (over LTE) to your
 * Firebase database every 30 s. People with your link watch it live on a map. If you stop moving
 * for too long (not paused), your contacts get an alert with your last location.
 */
object LiveTrack {
    @Volatile var status = "Off"
    private val io = Executors.newSingleThreadExecutor()
    private var runStart = 0L
    private var lastPost = 0L
    private var lastState: Run.State? = null
    private var ptIndex = 0
    private var lastDist = 0.0
    private var lastMoveAt = 0L
    private var warned = false
    private var alerted = false
    private var lastDistM = 0.0
    private var lastMovingS = 0

    private fun p(ctx: Context) = ctx.getSharedPreferences("run", 0)
    fun enabled(ctx: Context) = p(ctx).getBoolean("liveOn", false) && db(ctx).isNotBlank() && id(ctx).isNotBlank()
    private fun db(ctx: Context) = (p(ctx).getString("liveDb", "") ?: "").trim().removeSuffix("/").let {
        if (it.isBlank() || it.startsWith("http")) it else "https://$it"
    }
    private fun id(ctx: Context) = p(ctx).getString("liveId", "") ?: ""
    private fun stopMin(ctx: Context) = p(ctx).getInt("liveStopMin", 10)
    private fun name(ctx: Context) = p(ctx).getString("liveName", "")?.takeIf { it.isNotBlank() } ?: "Micheal"
    fun link(ctx: Context) = "https://michealsizemore1.github.io/ChatGPT-The-Master-App/livetrack.html#db=" +
        android.net.Uri.encode(db(ctx).removePrefix("https://")) + "&id=" + id(ctx)
    private fun contacts(ctx: Context) = (p(ctx).getString("liveContacts", "") ?: "").split(",", ";", "\n").map { it.trim() }
        .filter { it.count(Char::isDigit) >= 7 }

    /** Text from the watch itself (its own LTE number) — used when the phone can't be reached. */
    @Volatile var smsStatus = ""
    private fun watchText(ctx: Context, msg: String) {
        val to = contacts(ctx)
        if (to.isEmpty()) return
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            smsStatus = "Watch texting not allowed"; return
        }
        smsStatus = try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
                else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            to.forEach { n -> sms.sendMultipartTextMessage(n, null, sms.divideMessage(msg), null, null) }
            "Texted from watch"
        } catch (e: Exception) { "Watch couldn't text: ${e.message}" }
    }

    /** Ask the phone to text; if the phone isn't reachable, the watch texts. */
    private fun textVia(ctx: Context, path: String, data: String, msg: String) {
        val app = ctx.applicationContext
        RunSync.tellPhone(app, path, data.toByteArray()) { ok -> if (!ok && p(app).getBoolean("liveSms", true)) Thread { watchText(app, msg) }.start() }
    }

    private fun send(ctx: Context, method: String, path: String, body: JSONObject) {
        val url = "${db(ctx)}/live/${id(ctx)}$path.json" + if (method == "PATCH") "?x-http-method-override=PATCH" else ""
        io.execute {
            status = try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    // PATCH isn't allowed by HttpURLConnection; Firebase accepts the override header.
                    requestMethod = if (method == "PATCH") "POST" else method
                    if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..299) "Live" else "Server said $code"
            } catch (e: Exception) { "No connection" }
        }
    }

    private fun now(ctx: Context, state: String) = JSONObject().apply {
        put("state", state); put("updated", System.currentTimeMillis()); put("start", Run.startMillis)
        put("name", p(ctx).getString("liveName", "") ?: "")
        put("dist", Math.round(Run.distance)); put("moving", Run.movingSeconds); put("elapsed", Run.elapsedSeconds)
        if (Run.heartRate > 0) put("hr", Run.heartRate)
        if (Run.strydPower > 0) put("power", Run.strydPower)
        if (Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 60_000) { put("lat", Run.gpsLat); put("lon", Run.gpsLon) }
        Run.nav?.let { n -> put("trail", n.trail.name); put("trailLeft", Math.round(n.remaining)) }
        if (alerted) put("alert", true)
    }

    /** Called every second by the run service. */
    fun onTick(ctx: Context) {
        if (!enabled(ctx)) { if (runStart == 0L) status = "Off"; return }
        val t = System.currentTimeMillis()
        if (Run.isActive) {
            if (runStart != Run.startMillis) {
                // New run: reset the page and tell the phone to text the link.
                runStart = Run.startMillis; ptIndex = 0; lastPost = t; lastState = Run.state
                lastDist = Run.distance; lastMoveAt = t; warned = false; alerted = false
                send(ctx, "PUT", "", JSONObject().put("now", now(ctx, "running")))
                textVia(ctx, "/cmd/livestart", "", "${name(ctx)} started a run. Follow live: ${link(ctx)}")
                status = "Starting…"
                return
            }
            // Stopped-moving check (manual pause doesn't count).
            if (Run.distance > lastDist + 5) { lastDist = Run.distance; lastMoveAt = t
                if (warned || alerted) { warned = false; if (alerted) { alerted = false; post(ctx, true) } }
            }
            if (Run.state == Run.State.PAUSED) lastMoveAt = t
            val limit = stopMin(ctx) * 60_000L
            if (limit > 0 && !alerted) {
                if (!warned && t - lastMoveAt > limit - 60_000) {
                    warned = true
                    Speaker.say("Live track. You've stopped moving. Your contacts get an alert in one minute unless you start moving or pause the run.", force = true)
                    Run.onEvent?.invoke("offroute")
                }
                if (warned && t - lastMoveAt > limit) {
                    alerted = true
                    post(ctx, true)
                    val loc = if (Run.gpsFix) "${Run.gpsLat},${Run.gpsLon}" else ""
                    textVia(ctx, "/cmd/livealert", loc, "Alert from ${name(ctx)}'s run: no movement for ${stopMin(ctx)} minutes." +
                        (if (loc.isNotEmpty()) " Last location: https://maps.google.com/?q=$loc" else "") + " Live: ${link(ctx)}")
                    Speaker.say("Alert sent.", force = true)
                }
            }
            lastDistM = Run.distance; lastMovingS = Run.movingSeconds
            if (t - lastPost >= 30_000 || Run.state != lastState) post(ctx, false)
        } else if (runStart != 0L) {
            // Run finished (or discarded).
            send(ctx, "PATCH", "", JSONObject().put("now", now(ctx, "finished")
                .put("dist", Math.round(lastDistM)).put("moving", lastMovingS)))
            // Tell your contacts the run is over (with the totals).
            val mi = lastDistM / 1609.344
            val pace = if (mi > 0.05) (lastMovingS / mi).toInt().let { String.format(java.util.Locale.US, " (%d:%02d /mi)", it / 60, it % 60) } else ""
            val summary = String.format(java.util.Locale.US, "%.2f mi in %s", mi, Run.clock(lastMovingS)) + pace
            textVia(ctx, "/cmd/livedone", summary, "${name(ctx)} finished the run: $summary. ${link(ctx)}")
            runStart = 0L; status = "Finished"
        }
    }

    private fun post(ctx: Context, force: Boolean) {
        lastPost = System.currentTimeMillis(); lastState = Run.state
        val st = when (Run.state) { Run.State.PAUSED -> "paused"; Run.State.AUTO_PAUSED -> "stopped"; else -> "running" }
        val body = JSONObject().put("now", now(ctx, st))
        if (Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 60_000)
            body.put("pts/${ptIndex++}", JSONArray(listOf(Run.gpsLat, Run.gpsLon, Run.movingSeconds)))
        send(ctx, "PATCH", "", body)
    }
}
