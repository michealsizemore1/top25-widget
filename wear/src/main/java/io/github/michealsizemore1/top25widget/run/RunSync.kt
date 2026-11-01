package io.github.michealsizemore1.top25widget.run

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
                val req = PutDataMapRequest.create("/watchinfo").apply { dataMap.putString("version", v) }.asPutDataRequest()
                com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
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

    fun today(ctx: Context) = planFor(ctx, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
}

/** Receives zone settings and workouts from the phone app. */
class RunSettingsListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/workouts") {
                try {
                    val asset = DataMapItem.fromDataItem(e.dataItem).dataMap.getAsset("data") ?: return@forEach
                    val input = com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream
                    WatchWorkouts.save(this, java.util.zip.GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) })
                } catch (ex: Exception) { }
                return@forEach
            }
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != "/settings") return@forEach
            val m = DataMapItem.fromDataItem(e.dataItem).dataMap
            val p = getSharedPreferences("run", 0).edit()
            if (m.containsKey("hrMax")) { Run.hrMax = m.getDouble("hrMax"); p.putFloat("hrMax", Run.hrMax.toFloat()) }
            if (m.containsKey("hrRest")) { Run.hrRest = m.getDouble("hrRest"); p.putFloat("hrRest", Run.hrRest.toFloat()) }
            if (m.containsKey("cp")) { Run.criticalPower = m.getInt("cp"); p.putInt("cp", Run.criticalPower) }
            if (m.containsKey("cal")) { Run.strydCalibration = m.getDouble("cal"); p.putFloat("cal", Run.strydCalibration.toFloat()) }
            p.apply()
        }
    }
}
