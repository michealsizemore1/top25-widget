package io.github.michealsizemore1.top25widget.run

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.michealsizemore1.top25widget.Workouts
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Workout library and training plan on the phone.
 *  library.json: [ workout… ]
 *  plan.json:    { "yyyy-MM-dd": workout (notes hold the plan's text for that day) }
 * Every change is sent to the watch (the next 6 weeks of the plan plus the whole library).
 */
object WorkoutStore {
    private fun libFile(ctx: Context) = File(ctx.filesDir, "workout_library.json")
    private fun planFile(ctx: Context) = File(ctx.filesDir, "workout_plan.json")
    val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun library(ctx: Context): MutableList<JSONObject> {
        val f = libFile(ctx)
        if (!f.exists()) {   // first time: start with a few common workouts
            val t = Workouts.templates(); saveLibrary(ctx, t, push = false); return t.toMutableList()
        }
        return try { JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } }
               catch (e: Exception) { mutableListOf() }
    }

    fun saveLibrary(ctx: Context, list: List<JSONObject>, push: Boolean = true) {
        libFile(ctx).writeText(JSONArray(list).toString())
        if (push) pushToWatch(ctx)
    }

    fun upsertLibrary(ctx: Context, w: JSONObject) {
        val list = library(ctx)
        val i = list.indexOfFirst { it.optString("id") == w.optString("id") }
        if (i >= 0) list[i] = w else list += w
        saveLibrary(ctx, list)
    }

    fun deleteLibrary(ctx: Context, id: String) = saveLibrary(ctx, library(ctx).filter { it.optString("id") != id })

    fun plan(ctx: Context): JSONObject = try { JSONObject(planFile(ctx).readText()) } catch (e: Exception) { JSONObject() }

    fun savePlan(ctx: Context, plan: JSONObject) { planFile(ctx).writeText(plan.toString()); pushToWatch(ctx) }

    fun setDay(ctx: Context, date: String, w: JSONObject?) {
        val p = plan(ctx)
        if (w == null) p.remove(date) else p.put(date, JSONObject(w.toString()).put("id", "p$date"))
        savePlan(ctx, p)
    }

    /** What the last import did, for the message shown afterwards. */
    var restDays = 0; var keptEdited = 0; var pastDays = 0

    /**
     * Imports a written plan ({date: day's text}) from the Command Center: each day becomes a
     * workout (rest days are skipped). Days already edited on the phone are kept.
     * Returns how many days were added.
     */
    fun importPlan(ctx: Context, days: JSONObject): Int {
        val p = plan(ctx)
        val today = dayFmt.format(java.util.Date())
        var n = 0
        restDays = 0; keptEdited = 0; pastDays = 0
        days.keys().forEach { date ->
            if (date < today) { pastDays++; return@forEach }
            val text = days.optString(date)
            val existing = p.optJSONObject(date)
            if (existing != null && existing.optBoolean("edited")) { keptEdited++; return@forEach }
            // If a day can't be understood, keep it as a simple run with the plan's text as notes.
            val w = try { Workouts.fromPlanText(text) } catch (e: Exception) {
                Workouts.workout("Planned run", text.trim(), Workouts.step("run", "open", 0))
            }
            if (w == null) { p.remove(date); restDays++; return@forEach }
            p.put(date, w.put("id", "p$date").put("planText", text))
            n++
        }
        savePlan(ctx, p)
        return n
    }

    /** Sends the library and the whole plan (today onward) to the watch. */
    fun pushToWatch(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val all = plan(app)
                val cal = Calendar.getInstance()
                val upcoming = JSONObject()
                repeat(400) {   // the whole plan from today on
                    val d = dayFmt.format(cal.time)
                    all.optJSONObject(d)?.let { upcoming.put(d, it) }
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                val json = JSONObject().put("library", JSONArray(library(app))).put("plan", upcoming).toString()
                val bytes = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(json.toByteArray()) } }.toByteArray()
                val req = PutDataMapRequest.create("/workouts").apply {
                    dataMap.putAsset("data", Asset.createFromBytes(bytes))
                    dataMap.putLong("at", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }
}
