package io.github.michealsizemore1.beyondstride.run

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Watch ↔ phone over the Wear OS data layer.
 *  - Finished runs go to the phone as /run/<start> (gzipped JSON). The data layer
 *    holds them until the phone is in reach, so runs recorded away from the phone
 *    arrive later by themselves.
 *  - The phone's zone settings arrive as /settings.
 */
object RunSync {
    private const val PREFS = "runsync"

    /** Tells the phone which watch build is installed (shown in the phone's Run settings). */
    fun reportVersion(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
                // Also confirms what the watch has received, shown on the phone's Workouts screen.
                val req = PutDataMapRequest.create("/watchinfo").apply {
                    dataMap.putString("version", v)
                    dataMap.putInt("planDays", WatchWorkouts.planCount(app))
                    dataMap.putString("planLast", WatchWorkouts.lastDate(app))
                    dataMap.putInt("library", WatchWorkouts.library(app).size)
                    dataMap.putLong("receivedAt", WatchWorkouts.receivedAt(app))
                }.asPutDataRequest().setUrgent()
                com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** Asks the phone to do something (a Command Center / Stryd button on the watch). */
    fun tellPhone(ctx: Context, path: String, data: ByteArray = ByteArray(0), done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val ok = try {
                val nodes = com.google.android.gms.tasks.Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                nodes.forEach { n -> com.google.android.gms.tasks.Tasks.await(Wearable.getMessageClient(app).sendMessage(n.id, path, data)) }
                nodes.isNotEmpty()
            } catch (e: Exception) { false }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    /**
     * Reads the phone's latest workouts and status straight from the data layer. Covers updates
     * sent while the watch app wasn't listening (e.g. right after it was reinstalled).
     */
    fun pullLatest(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val dc = Wearable.getDataClient(app)
                val items = com.google.android.gms.tasks.Tasks.await(dc.getDataItems(Uri.parse("wear://*/workouts")))
                // Newest copy wins.
                val best = items.map { DataMapItem.fromDataItem(it).dataMap }.maxByOrNull { it.getLong("at") }
                items.release()
                if (best != null && best.getLong("at") > app.getSharedPreferences(PREFS, 0).getLong("workoutsAt", 0)) {
                    best.getAsset("data")?.let { asset ->
                        val input = com.google.android.gms.tasks.Tasks.await(dc.getFdForAsset(asset)).inputStream
                        WatchWorkouts.save(app, java.util.zip.GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) })
                        app.getSharedPreferences(PREFS, 0).edit().putLong("workoutsAt", best.getLong("at")).apply()
                    }
                }
                // Settings changed on the phone while this app wasn't listening.
                val rs = com.google.android.gms.tasks.Tasks.await(dc.getDataItems(Uri.parse("wear://*" + io.github.michealsizemore1.beyondstride.SettingsSync.PATH)))
                rs.map { DataMapItem.fromDataItem(it).dataMap }.forEach {
                    if (io.github.michealsizemore1.beyondstride.SettingsSync.apply(app, "run", "watch", it)) loadSettings(app)
                }
                rs.release()
                val tr = com.google.android.gms.tasks.Tasks.await(dc.getDataItems(Uri.parse("wear://*" + io.github.michealsizemore1.beyondstride.TrailSync.PATH)))
                tr.map { DataMapItem.fromDataItem(it).dataMap }.forEach { io.github.michealsizemore1.beyondstride.TrailSync.apply(app, "watch", it) }
                tr.release()
                io.github.michealsizemore1.beyondstride.Courses.pullAll(app, "watch")
                for (path in listOf(io.github.michealsizemore1.beyondstride.ReadinessSync.NIGHTS, io.github.michealsizemore1.beyondstride.ReadinessSync.CHECKS)) {
                    val r = com.google.android.gms.tasks.Tasks.await(dc.getDataItems(Uri.parse("wear://*$path")))
                    r.map { DataMapItem.fromDataItem(it).dataMap }.forEach { io.github.michealsizemore1.beyondstride.ReadinessSync.apply(app, path, it) }
                    r.release()
                }
                val st = com.google.android.gms.tasks.Tasks.await(dc.getDataItems(Uri.parse("wear://*/phonestatus")))
                st.map { DataMapItem.fromDataItem(it).dataMap }.firstOrNull()?.let { m ->
                    app.getSharedPreferences("phonestatus", 0).edit()
                        .putInt("ccUnsent", m.getInt("ccUnsent")).putBoolean("stryd", m.getBoolean("stryd"))
                        .putLong("at", System.currentTimeMillis()).apply()
                }
                st.release()
                reportVersion(app)
            } catch (e: Exception) { }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done() }
        }.start()
    }

    /** A run was deleted here: leave a note so the phone deletes its copy too (whenever it connects). */
    fun deleteEverywhere(ctx: Context, start: Long) {
        if (start <= 0) return
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, 0).edit().remove(start.toString()).apply()
        Thread {
            try {
                val req = PutDataMapRequest.create("/deleted/$start").apply { dataMap.putLong("at", System.currentTimeMillis()) }
                    .asPutDataRequest().setUrgent()
                com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** Sets the run's trail to the chosen one (shared with the phone). */
    fun loadTrail(ctx: Context) {
        val cur = io.github.michealsizemore1.beyondstride.TrailSync.current(ctx)
        if (cur == null) { if (Run.nav != null) Run.chooseTrail(null, false); return }
        val (t, oab) = cur
        if (Run.nav?.trail?.id != t.id || Run.nav?.outAndBack != oab) Run.chooseTrail(t, oab)
    }

    /** Puts the saved settings into the live run state. */
    fun loadSettings(ctx: Context) {
        val p = ctx.getSharedPreferences("run", 0)
        Run.source = if (p.getString("source", "stryd") == "gps") Run.Source.GPS else Run.Source.STRYD
        Run.autoPause = p.getBoolean("autoPause", true)
        Run.recordGpsTrack = p.getBoolean("track", false)
        Run.useStrap = p.getBoolean("useStrap", true)
        Run.criticalPower = p.getInt("cp", 333)
        Run.hrMax = p.getFloat("hrMax", 185f).toDouble()
        Run.hrRest = p.getFloat("hrRest", 43.5f).toDouble()
        Run.strydCalibration = p.getFloat("cal", 1f).toDouble()
        Run.hrCapMode = p.getString("hrCap", "easy") ?: "easy"
        Run.hrCapZone = p.getInt("hrCapZone", 2)
    }

    fun pushAll(ctx: Context) {
        val app = ctx.applicationContext
        val sent = app.getSharedPreferences(PREFS, 0)
        Thread {
            RunStore.list(app).forEach { f ->
                val start = f.nameWithoutExtension
                if (sent.getBoolean(start, false)) return@forEach
                try {
                    val bytes = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(f.readBytes()) } }.toByteArray()
                    val req = PutDataMapRequest.create("/run/$start").apply {
                        dataMap.putAsset("run", Asset.createFromBytes(bytes))
                        dataMap.putLong("start", start.toLongOrNull() ?: 0L)
                    }.asPutDataRequest().setUrgent()
                    com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(app).putDataItem(req))
                    sent.edit().putBoolean(start, true).apply()
                } catch (e: Exception) { /* no Play services link right now; try again next time */ }
            }
        }.start()
    }
}

/** Workouts and the training plan, as sent by the phone. */
object WatchWorkouts {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "workouts.json")
    fun save(ctx: Context, text: String) = file(ctx).writeText(text)
    private fun load(ctx: Context): org.json.JSONObject =
        try { org.json.JSONObject(file(ctx).readText()) } catch (e: Exception) { org.json.JSONObject() }

    fun library(ctx: Context): List<org.json.JSONObject> =
        load(ctx).optJSONArray("library")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /** Planned workout for a date (yyyy-MM-dd), with the plan's text in its notes. */
    fun planFor(ctx: Context, date: String): org.json.JSONObject? = load(ctx).optJSONObject("plan")?.optJSONObject(date)

    fun lastDate(ctx: Context): String = load(ctx).optJSONObject("plan")?.keys()?.asSequence()?.maxOrNull().orEmpty()
    fun planCount(ctx: Context) = load(ctx).optJSONObject("plan")?.length() ?: 0
    fun receivedAt(ctx: Context) = file(ctx).takeIf { it.exists() }?.lastModified() ?: 0L

    /** The next [days] planned days from today: (date, workout). */
    fun upcoming(ctx: Context, days: Int): List<Pair<String, org.json.JSONObject>> {
        val plan = load(ctx).optJSONObject("plan") ?: return emptyList()
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        return (0 until days).mapNotNull {
            val d = f.format(cal.time); cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            plan.optJSONObject(d)?.let { w -> d to w }
        }
    }

    fun today(ctx: Context) = planFor(ctx, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
}

/** Receives zone settings and workouts from the phone app. */
class RunSettingsListener : WearableListenerService() {
    /** The phone confirms it sent a LiveTrack text. */
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == "/live/ack") LiveTrack.lastAck = String(event.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            // Course saved/removed on the phone.
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path?.startsWith(io.github.michealsizemore1.beyondstride.Courses.PREFIX) == true) {
                io.github.michealsizemore1.beyondstride.Courses.apply(this, "watch", e.dataItem.uri.path ?: "", DataMapItem.fromDataItem(e.dataItem.freeze()).dataMap)
                return@forEach
            }
            // Sleep from the phone (Health Connect) / morning checks.
            if (e.type == DataEvent.TYPE_CHANGED && (e.dataItem.uri.path == io.github.michealsizemore1.beyondstride.ReadinessSync.NIGHTS ||
                    e.dataItem.uri.path == io.github.michealsizemore1.beyondstride.ReadinessSync.CHECKS)) {
                io.github.michealsizemore1.beyondstride.ReadinessSync.apply(this, e.dataItem.uri.path ?: "", DataMapItem.fromDataItem(e.dataItem).dataMap)
                return@forEach
            }
            // Trail chosen on the phone.
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == io.github.michealsizemore1.beyondstride.TrailSync.PATH) {
                if (io.github.michealsizemore1.beyondstride.TrailSync.apply(this, "watch", DataMapItem.fromDataItem(e.dataItem).dataMap) && !Run.isActive) RunSync.loadTrail(this)
                return@forEach
            }
            // Settings changed on the phone.
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == io.github.michealsizemore1.beyondstride.SettingsSync.PATH) {
                if (io.github.michealsizemore1.beyondstride.SettingsSync.apply(this, "run", "watch", DataMapItem.fromDataItem(e.dataItem).dataMap)) {
                    RunSync.loadSettings(this)
                    RunService.instance?.applyHrMode()
                }
                return@forEach
            }
            // A run deleted on the phone: delete the watch's copy too.
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path?.startsWith("/deleted/") == true) {
                e.dataItem.uri.lastPathSegment?.toLongOrNull()?.let { RunStore.deleteStart(this, it) }
                return@forEach
            }
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/phonestatus") {
                val m = DataMapItem.fromDataItem(e.dataItem).dataMap
                getSharedPreferences("phonestatus", 0).edit()
                    .putInt("ccUnsent", m.getInt("ccUnsent"))
                    .putBoolean("stryd", m.getBoolean("stryd")).putLong("at", System.currentTimeMillis()).apply()
                return@forEach
            }
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/workouts") {
                try {
                    val asset = DataMapItem.fromDataItem(e.dataItem).dataMap.getAsset("data") ?: return@forEach
                    val input = com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream
                    WatchWorkouts.save(this, java.util.zip.GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) })
                    getSharedPreferences("runsync", 0).edit().putLong("workoutsAt", DataMapItem.fromDataItem(e.dataItem).dataMap.getLong("at")).apply()
                    RunSync.reportVersion(this)   // tell the phone it arrived
                } catch (ex: Exception) { }
                return@forEach
            }
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != "/settings") return@forEach
            val m = DataMapItem.fromDataItem(e.dataItem).dataMap
            val p = getSharedPreferences("run", 0).edit()
            if (m.containsKey("hrMax")) { Run.hrMax = m.getDouble("hrMax"); p.putFloat("hrMax", Run.hrMax.toFloat()) }
            if (m.containsKey("hrRest")) { Run.hrRest = m.getDouble("hrRest"); p.putFloat("hrRest", Run.hrRest.toFloat()) }
            if (m.containsKey("cp")) { Run.criticalPower = m.getInt("cp"); p.putInt("cp", Run.criticalPower) }
            if (m.containsKey("screens")) p.putString("screens", m.getString("screens"))
            if (m.containsKey("cal")) { Run.strydCalibration = m.getDouble("cal"); p.putFloat("cal", Run.strydCalibration.toFloat()) }
            p.apply()
        }
    }
}
