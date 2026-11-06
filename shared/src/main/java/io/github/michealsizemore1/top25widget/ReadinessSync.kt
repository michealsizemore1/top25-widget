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

    /** Nights read from Health Connect. A time you entered yourself is kept (the watch's number is saved beside it). */
    fun setNights(ctx: Context, l: List<Readiness.Night>) {
        val old = nights(ctx).associateBy { it.day }
        val fresh = l.map { n -> old[n.day]?.takeIf { it.manual }?.copy(auto = n.minutes, sleepHr = n.sleepHr, hrv = n.hrv) ?: n.copy(auto = n.minutes, editAt = old[n.day]?.editAt ?: 0L) }
        val merged = (nights(ctx).filter { n -> fresh.none { it.day == n.day } } + fresh).sortedBy { it.day }
        p(ctx).edit().putString("nights", Readiness.nightsJson(merged)).putLong("nightsAt", System.currentTimeMillis()).apply()
        push(ctx, NIGHTS, Readiness.nightsJson(merged))
    }
    /** Enter time asleep yourself (minutes), or null to go back to what the watch measured. */
    fun setManual(ctx: Context, day: String, minutes: Int?) {
        val l = nights(ctx); val cur = l.firstOrNull { it.day == day }
        val now = System.currentTimeMillis()
        val n = if (minutes == null) (cur ?: return).let { it.copy(minutes = it.auto, manual = false, editAt = now) }
            else (cur ?: Readiness.Night(day, 0)).let { it.copy(minutes = minutes, manual = true, editAt = now) }
        val merged = (l.filter { it.day != day } + n).filter { it.minutes > 0 || it.manual }.sortedBy { it.day }
        p(ctx).edit().putString("nights", Readiness.nightsJson(merged)).apply()
        push(ctx, NIGHTS, Readiness.nightsJson(merged))
    }

    /** Send both lists (after a restore). */
    fun pushAll(ctx: Context) {
        push(ctx, CHECKS, Readiness.checksJson(checks(ctx)))
        push(ctx, NIGHTS, Readiness.nightsJson(nights(ctx)))
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
                val theirs = Readiness.nightsFrom(s).associateBy { it.day }
                val mine = nights(ctx).associateBy { it.day }
                // Newest edit wins; otherwise the other device's (newer) reading.
                val merged = (mine.keys + theirs.keys).map { d ->
                    val m = mine[d]; val t = theirs[d]
                    when { t == null -> m!!; m == null -> t; m.editAt > t.editAt -> m.copy(auto = t.auto, sleepHr = maxOf(t.sleepHr, m.sleepHr), hrv = maxOf(t.hrv, m.hrv)); else -> t }
                }.sortedBy { it.day }
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
