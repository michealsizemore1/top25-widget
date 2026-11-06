package io.github.michealsizemore1.beyondstride.run

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

    /**
     * Who sends the text: the phone when it's with you (Bluetooth), otherwise the watch itself over its
     * own cell connection — right away, instead of a message to a phone at home that may arrive late.
     */
    @Volatile var lastAck = ""
    val log = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun note(what: String) { if (log.size < 50) log += java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date()) + " " + what }

    private fun textVia(ctx: Context, path: String, data: String, msg: String) {
        val app = ctx.applicationContext
        val kind = path.substringAfterLast("/").removePrefix("live")
        Thread {
            val phoneNearby = try {
                com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getNodeClient(app).connectedNodes,
                    5, java.util.concurrent.TimeUnit.SECONDS).any { it.isNearby }
            } catch (e: Exception) { false }
            if (phoneNearby) {
                lastAck = ""
                RunSync.tellPhone(app, path, data.toByteArray()) { ok ->
                    note("$kind: asked phone (${if (ok) "delivered" else "failed"})")
                    Thread {
                        // Wait for the phone to confirm it texted; otherwise the watch sends it.
                        val until = System.currentTimeMillis() + 20_000
                        while (System.currentTimeMillis() < until && lastAck != path) Thread.sleep(500)
                        if (lastAck == path) note("$kind: phone texted")
                        else if (p(app).getBoolean("liveSms", true)) { watchText(app, msg); note("$kind: no reply from phone — $smsStatus") }
                    }.start()
                }
            } else if (p(app).getBoolean("liveSms", true)) { watchText(app, msg); note("$kind: $smsStatus (phone not with you)") }
        }.start()
    }

    // ---- Network: ask Wear OS for a real internet connection (LTE or Wi-Fi). Without this, a watch away
    // from its phone may keep apps on the (gone) Bluetooth link, and posts pile up until you're home.
    @Volatile private var net: android.net.Network? = null
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    private fun requestNet(ctx: Context) {
        if (netCb != null) return
        val cm = ctx.applicationContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) { net = n }
            override fun onLost(n: android.net.Network) { if (net == n) net = null }
        }
        try {
            cm.requestNetwork(android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    private fun releaseNet(ctx: Context) {
        val cb = netCb ?: return
        netCb = null; net = null
        try { ctx.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    // ---- Sending: always the latest snapshot plus every map point not yet delivered (no backlog of old posts).
    private val pendingPts = java.util.TreeMap<Int, JSONArray>()
    @Volatile private var pendingNow: JSONObject? = null
    @Volatile private var pendingReset = false
    @Volatile private var dirty = false
    private var dirtySince = 0L
    private var lastFlushTry = 0L
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun http(ctx: Context, method: String, body: JSONObject): Boolean {
        val url = URL("${db(ctx)}/live/${id(ctx)}.json" + if (method == "PATCH") "?x-http-method-override=PATCH" else "")
        val c = ((net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            status = if (code in 200..299) "Live" else "Server said $code"
            code in 200..299
        } finally { c.disconnect() }
    }

    private fun flush(ctx: Context) {
        lastFlushTry = System.currentTimeMillis()
        if (!flushing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        io.execute {
            try {
                val snap = pendingNow ?: return@execute
                if (pendingReset) {
                    if (!http(app, "PUT", JSONObject().put("now", snap))) return@execute
                    pendingReset = false
                }
                val body = JSONObject().put("now", snap)
                val keys = ArrayList<Int>()
                synchronized(pendingPts) { pendingPts.entries.take(400).forEach { (k, v) -> body.put("pts/$k", v); keys += k } }
                if (http(app, "PATCH", body)) {
                    synchronized(pendingPts) { keys.forEach { pendingPts.remove(it) } }
                    if (pendingNow === snap && synchronized(pendingPts) { pendingPts.isEmpty() }) dirty = false
                }
            } catch (e: Exception) { status = "No connection" } finally { flushing.set(false) }
        }
    }

    private fun queue(ctx: Context, snapshot: JSONObject, reset: Boolean = false) {
        pendingNow = snapshot
        if (reset) { pendingReset = true; synchronized(pendingPts) { pendingPts.clear() } }
        if (!dirty) dirtySince = System.currentTimeMillis()
        dirty = true
        flush(ctx)
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
        val t = System.currentTimeMillis()
        // Undelivered updates (e.g. no signal for a bit): keep retrying every 5 s, for up to 30 minutes.
        if (dirty && t - lastFlushTry > 5_000) {
            if (t - dirtySince < 30 * 60_000L) flush(ctx) else dirty = false
        }
        if (!dirty && runStart == 0L && netCb != null) releaseNet(ctx)
        if (!enabled(ctx)) { if (runStart == 0L) status = "Off"; return }
        if (Run.isActive) {
            if (runStart != Run.startMillis) {
                // New run: reset the page and tell the phone to text the link.
                runStart = Run.startMillis; ptIndex = 0; lastPost = t; lastState = Run.state
                lastDist = Run.distance; lastMoveAt = t; warned = false; alerted = false
                requestNet(ctx)
                queue(ctx, now(ctx, "running"), reset = true)
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
            queue(ctx, now(ctx, "finished").put("dist", Math.round(lastDistM)).put("moving", lastMovingS))
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
        if (Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 60_000)
            synchronized(pendingPts) { pendingPts[ptIndex++] = JSONArray(listOf(Run.gpsLat, Run.gpsLon, Run.movingSeconds)) }
        queue(ctx, now(ctx, st))
    }
}
