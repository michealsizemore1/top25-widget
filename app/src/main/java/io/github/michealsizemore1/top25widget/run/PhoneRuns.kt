package io.github.michealsizemore1.top25widget.run

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.michealsizemore1.top25widget.R
import io.github.michealsizemore1.top25widget.RunMath
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/** Runs received from the watch, stored as JSON in the phone app's private storage. */
object PhoneRuns {
    private fun dir(ctx: Context) = File(ctx.filesDir, "runs").apply { mkdirs() }
    private fun flags(ctx: Context) = ctx.getSharedPreferences("runflags", 0)

    fun save(ctx: Context, json: JSONObject): File =
        File(dir(ctx), "${json.optLong("start")}.json").apply { writeText(json.toString()) }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }.orEmpty()

    fun file(ctx: Context, start: Long) = File(dir(ctx), "$start.json")
    fun read(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (e: Exception) { null }
    fun read(ctx: Context, start: Long): JSONObject? = file(ctx, start).takeIf { it.exists() }?.let { read(it) }

    fun delete(ctx: Context, start: Long) {
        file(ctx, start).delete()
        flags(ctx).edit().remove("strava_$start").remove("cc_$start").apply()
    }

    // Where each run has been sent.
    fun stravaId(ctx: Context, start: Long): Long = flags(ctx).getLong("strava_$start", 0L)
    fun setStravaId(ctx: Context, start: Long, id: Long) = flags(ctx).edit().putLong("strava_$start", id).apply()
    fun sentToCommandCenter(ctx: Context, start: Long) = flags(ctx).getBoolean("cc_$start", false)
    fun setSentToCommandCenter(ctx: Context, start: Long) = flags(ctx).edit().putBoolean("cc_$start", true).apply()

    fun title(json: JSONObject): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = json.optLong("start") }.get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 4..11 -> "Morning Run"; in 12..16 -> "Afternoon Run"; in 17..20 -> "Evening Run"; else -> "Night Run" }
    }

    fun shortLine(json: JSONObject) =
        "${RunMath.miles(json.optDouble("distance"))} mi · ${RunMath.clock(json.optInt("movingSeconds"))} · " +
            "${RunMath.paceFor(json.optInt("movingSeconds"), json.optDouble("distance"))} /mi"
}

/** Zone settings, kept on the phone and sent to the watch. */
object RunSettings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("runsettings", 0)
    fun hrMax(ctx: Context) = p(ctx).getFloat("hrMax", 185f).toDouble()
    fun hrRest(ctx: Context) = p(ctx).getFloat("hrRest", 43.5f).toDouble()
    fun cp(ctx: Context) = p(ctx).getInt("cp", 333)
    fun cal(ctx: Context) = p(ctx).getFloat("cal", 1f).toDouble()
    fun ccUrl(ctx: Context) = p(ctx).getString("ccUrl", null)
        ?: "https://michealsizemore1.github.io/ChatGPT-The-Master-App/daily-planner.html"

    fun save(ctx: Context, hrMax: Double, hrRest: Double, cp: Int, cal: Double, ccUrl: String) {
        p(ctx).edit().putFloat("hrMax", hrMax.toFloat()).putFloat("hrRest", hrRest.toFloat())
            .putInt("cp", cp).putFloat("cal", cal.toFloat()).putString("ccUrl", ccUrl).apply()
        pushToWatch(ctx)
    }

    /** Send the zones to the watch (it picks them up whenever it's connected). */
    fun pushToWatch(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val req = PutDataMapRequest.create("/settings").apply {
                    dataMap.putDouble("hrMax", hrMax(app)); dataMap.putDouble("hrRest", hrRest(app))
                    dataMap.putInt("cp", cp(app)); dataMap.putDouble("cal", cal(app))
                    dataMap.putLong("at", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }
}

/** Receives finished runs from the watch. */
class RunReceiverService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith("/run/") == true }
            .map { it.dataItem.freeze() }
        items.forEach { item ->
            try {
                val map = DataMapItem.fromDataItem(item).dataMap
                val asset = map.getAsset("run") ?: return@forEach
                val input = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream
                val text = GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) }
                val json = JSONObject(text)
                val isNew = !PhoneRuns.file(this, json.optLong("start")).exists()
                PhoneRuns.save(this, json)
                Tasks.await(Wearable.getDataClient(this).deleteDataItems(item.uri))
                if (isNew) {
                    notifyRun(json)
                    if (Strava.autoUpload(this) && Strava.connected(this)) StravaWorker.enqueue(this, json.optLong("start"))
                }
            } catch (e: Exception) { android.util.Log.w("RunReceiver", "run sync failed", e) }
        }
    }

    private fun notifyRun(json: JSONObject) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("runs", "Runs from watch", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, json.optLong("start").toInt(),
            Intent(this, RunDetailActivity::class.java).putExtra("start", json.optLong("start")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(this, "runs")
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("${PhoneRuns.title(json)} saved")
            .setContentText(PhoneRuns.shortLine(json))
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(json.optLong("start").toInt(), n) } catch (e: SecurityException) { }
    }
}
