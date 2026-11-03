package io.github.michealsizemore1.top25widget.run

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.michealsizemore1.top25widget.R

/**
 * Keeps the watch's Command Center / Stryd buttons in step with the phone,
 * and carries out what those buttons ask for.
 */
object PhoneStatus {
    fun unsentCc(ctx: Context) = PhoneRuns.list(ctx).map { it.nameWithoutExtension.toLongOrNull() ?: 0L }
        .filter { it > 0 && !PhoneRuns.sentToCommandCenter(ctx, it) }

    /** Sends the current status to the watch. */
    fun push(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val req = PutDataMapRequest.create("/phonestatus").apply {
                    dataMap.putInt("ccUnsent", unsentCc(app).size)
                    dataMap.putBoolean("stryd", StrydCloud.connected(app))
                    dataMap.putInt("runs", PhoneRuns.list(app).size)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** A button on the watch was tapped. */
    fun command(ctx: Context, path: String, data: ByteArray = ByteArray(0)) {
        when (path) {
            // Data screens changed on the watch: keep the phone's copy the same (no need to send back).
            "/cmd/screens" -> try {
                ctx.getSharedPreferences("runsettings", 0).edit().putString("screens", org.json.JSONArray(String(data)).toString()).apply()
            } catch (e: Exception) { }
            "/cmd/cc" -> {
                // The Command Center opens in the phone's browser, which needs a tap on the phone.
                val n = unsentCc(ctx).size
                notify(ctx, 901, if (n > 0) "Send $n run${if (n == 1) "" else "s"} to Command Center" else "Command Center is up to date",
                    if (n > 0) "Tap to open the Command Center and add them" else "All runs are already there",
                    Intent(ctx, RunsActivity::class.java).putExtra("sendCc", n > 0))
            }
            // Watch asked for directions to a trail start (no Google Maps on the watch).
            "/cmd/directions" -> {
                val p = String(data).split(",")
                if (p.size >= 3) {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(android.app.NotificationChannel("runstatus", "BeyondStride", NotificationManager.IMPORTANCE_HIGH))
                    val nav = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${p[0]},${p[1]}&mode=${p[2]}"))
                        .setPackage("com.google.android.apps.maps")
                    val pi = android.app.PendingIntent.getActivity(ctx, 904, nav, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    val n = android.app.Notification.Builder(ctx, "runstatus").setSmallIcon(io.github.michealsizemore1.top25widget.R.drawable.ic_stat_run)
                        .setContentTitle("Directions to " + (p.getOrNull(3) ?: "trail start")).setContentText("Tap to open Google Maps")
                        .setContentIntent(pi).setAutoCancel(true).build()
                    try { nm.notify(904, n) } catch (e: SecurityException) { }
                }
            }
            "/cmd/livestart" -> Live.started(ctx)
            "/cmd/livealert" -> Live.alert(ctx, String(data))
            "/cmd/livedone" -> Live.done(ctx, String(data))
            "/cmd/stryd" -> {
                if (!StrydCloud.connected(ctx)) {
                    notify(ctx, 903, "Stryd isn't connected", "Tap to sign in to Stryd in Run settings",
                        Intent(ctx, RunSettingsActivity::class.java))
                } else PhoneRuns.list(ctx).mapNotNull { PhoneRuns.read(it) }.filter { !it.has("stryd") }
                    .forEach { StrydWorker.enqueue(ctx, it.optLong("start"), delayMinutes = 0) }
            }
        }
        push(ctx)
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, open: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("runs", "Runs from watch", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, "runs").setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(id, n) } catch (e: SecurityException) { }
    }
}
