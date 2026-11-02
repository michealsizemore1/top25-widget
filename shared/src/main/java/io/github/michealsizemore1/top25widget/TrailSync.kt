package io.github.michealsizemore1.top25widget

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** The trail chosen for the next run — the same on phone and watch (choose on either). */
object TrailSync {
    const val PATH = "/trail"
    private fun p(ctx: Context) = ctx.getSharedPreferences("trailsel", 0)

    /** The chosen trail and whether it's run out and back, or null. */
    fun current(ctx: Context): Pair<Trail, Boolean>? {
        val s = p(ctx).getString("json", null)?.takeIf { it.isNotBlank() } ?: return null
        return try { Trail.fromJson(JSONObject(s)) to p(ctx).getBoolean("outAndBack", false) } catch (e: Exception) { null }
    }

    /** Choose (or clear with null) here and send to the other device. */
    fun choose(ctx: Context, t: Trail?, outAndBack: Boolean, me: String) {
        val at = System.currentTimeMillis()
        p(ctx).edit().putString("json", t?.toJson()?.toString() ?: "").putBoolean("outAndBack", outAndBack).putLong("at", at).apply()
        val app = ctx.applicationContext
        Thread {
            try {
                val req = PutDataMapRequest.create(PATH).apply {
                    dataMap.putString("json", t?.toJson()?.toString() ?: "")
                    dataMap.putBoolean("outAndBack", outAndBack)
                    dataMap.putLong("at", at); dataMap.putString("from", me)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** A choice made on the other device. Returns true if it's newer and was taken. */
    fun apply(ctx: Context, me: String, m: DataMap): Boolean {
        if (m.getString("from") == me || m.getLong("at") <= p(ctx).getLong("at", 0L)) return false
        p(ctx).edit().putString("json", m.getString("json") ?: "").putBoolean("outAndBack", m.getBoolean("outAndBack"))
            .putLong("at", m.getLong("at")).apply()
        return true
    }
}
