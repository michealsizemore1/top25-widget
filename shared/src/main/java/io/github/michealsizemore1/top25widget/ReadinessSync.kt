package io.github.michealsizemore1.top25widget

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Morning checks (made on the watch) and sleep nights (read on the phone from Health Connect),
 * kept the same on both devices. Lists are merged, so neither side loses entries.
 */
object ReadinessSync {
    const val CHECKS = "/checks"
    const val NIGHTS = "/nights"
    private fun p(ctx: Context) = ctx.getSharedPreferences("readiness", 0)

    fun checks(ctx: Context) = Readiness.checksFrom(p(ctx).getString("checks", null))
    fun nights(ctx: Context) = Readiness.nightsFrom(p(ctx).getString("nights", null))

    fun addCheck(ctx: Context, c: Readiness.Check) {
        val l = Readiness.withCheck(checks(ctx), c)
        p(ctx).edit().putString("checks", Readiness.checksJson(l)).apply()
        push(ctx, CHECKS, Readiness.checksJson(l))
    }

    fun setNights(ctx: Context, l: List<Readiness.Night>) {
        val merged = (nights(ctx).filter { n -> l.none { it.day == n.day } } + l).sortedBy { it.day }
        p(ctx).edit().putString("nights", Readiness.nightsJson(merged)).putLong("nightsAt", System.currentTimeMillis()).apply()
        push(ctx, NIGHTS, Readiness.nightsJson(merged))
    }
    fun nightsAt(ctx: Context) = p(ctx).getLong("nightsAt", 0L)

    /** Data from the other device. */
    fun apply(ctx: Context, path: String, m: DataMap) {
        val s = m.getString("json") ?: return
        when (path) {
            CHECKS -> {
                val mine = checks(ctx); val theirs = Readiness.checksFrom(s)
                val merged = (mine.filter { c -> theirs.none { it.day == c.day && it.at >= c.at } } +
                    theirs.filter { t -> mine.none { it.day == t.day && it.at > t.at } }).sortedBy { it.day }
                p(ctx).edit().putString("checks", Readiness.checksJson(merged)).apply()
            }
            NIGHTS -> {
                val theirs = Readiness.nightsFrom(s)
                val merged = (nights(ctx).filter { n -> theirs.none { it.day == n.day } } + theirs).sortedBy { it.day }
                p(ctx).edit().putString("nights", Readiness.nightsJson(merged)).putLong("nightsAt", System.currentTimeMillis()).apply()
            }
        }
    }

    private fun push(ctx: Context, path: String, json: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val req = PutDataMapRequest.create(path).apply {
                    dataMap.putString("json", json); dataMap.putLong("at", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }
}
