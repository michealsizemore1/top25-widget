package io.github.michealsizemore1.top25widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Saved courses: trails you've starred, or routes from your own runs. Kept on both the phone and the
 * watch — save or remove on either and the other follows.
 */
object Courses {
    const val PREFIX = "/course/"

    private fun dir(ctx: Context) = File(ctx.filesDir, "courses").apply { mkdirs() }
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun file(ctx: Context, id: String) = File(dir(ctx), safe(id) + ".json")

    private fun readRec(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (e: Exception) { null }

    /** Saved courses, newest first. */
    fun list(ctx: Context): List<Trail> = (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { readRec(it) }.filter { !it.optBoolean("deleted") }
        .sortedByDescending { it.optLong("at") }
        .mapNotNull { r -> try { Trail.fromJson(r.getJSONObject("trail")) } catch (e: Exception) { null } }

    fun isSaved(ctx: Context, id: String) = readRec(file(ctx, id))?.let { !it.optBoolean("deleted") } == true

    fun save(ctx: Context, t: Trail, me: String) = write(ctx, t.id, t.toJson(), false, me)
    fun remove(ctx: Context, id: String, me: String) = write(ctx, id, null, true, me)
    fun rename(ctx: Context, t: Trail, name: String, me: String) =
        write(ctx, t.id, t.toJson().put("name", name), false, me)

    private fun write(ctx: Context, id: String, trail: JSONObject?, deleted: Boolean, me: String) {
        val at = System.currentTimeMillis()
        val rec = JSONObject().put("id", id).put("at", at).put("deleted", deleted)
        if (trail != null) rec.put("trail", trail)
        file(ctx, id).writeText(rec.toString())
        val app = ctx.applicationContext
        Thread {
            try {
                val bytes = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(rec.toString().toByteArray()) } }.toByteArray()
                val req = PutDataMapRequest.create(PREFIX + safe(id)).apply {
                    dataMap.putAsset("data", Asset.createFromBytes(bytes))
                    dataMap.putLong("at", at); dataMap.putString("from", me); dataMap.putBoolean("deleted", deleted)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** A save/remove from the other device (call off the main thread). True if something changed here. */
    fun apply(ctx: Context, me: String, path: String, m: DataMap): Boolean {
        if (m.getString("from") == me) return false
        val id = path.removePrefix(PREFIX)
        val at = m.getLong("at")
        val f = File(dir(ctx), "$id.json")
        if (at <= (readRec(f)?.optLong("at") ?: 0L)) return false
        return try {
            val asset = m.getAsset("data") ?: return false
            val input = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(asset)).inputStream
            val text = GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) }
            f.writeText(text); true
        } catch (e: Exception) { false }
    }

    /** Catch up on everything saved/removed on the other device (call off the main thread). */
    fun pullAll(ctx: Context, me: String): Boolean {
        var changed = false
        try {
            val items = Tasks.await(Wearable.getDataClient(ctx).getDataItems(Uri.parse("wear://*$PREFIX"), DataClient.FILTER_PREFIX))
            items.forEach { item ->
                val m = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap
                if (apply(ctx, me, item.uri.path ?: "", m)) changed = true
            }
            items.release()
        } catch (e: Exception) { }
        return changed
    }

    /** A course from a run's GPS track (needs GPS on for that run). */
    fun fromRun(run: JSONObject, name: String): Trail? {
        val tr = run.optJSONArray("track") ?: return null
        val pts = (0 until tr.length()).mapNotNull { i -> tr.optJSONArray(i)?.let { p -> doubleArrayOf(p.optDouble(0), p.optDouble(1)) } }
            .filter { !it[0].isNaN() && !it[1].isNaN() }
        if (pts.size < 10) return null
        val len = Geo.length(pts)
        if (len < 200) return null
        val loop = Geo.dist(pts.first(), pts.last()) < maxOf(100.0, len * 0.03)
        return Trail("run${run.optLong("start")}", name, Trails.simplify(pts, 5.0), len, loop, "", "")
    }
}

/** Turn-by-turn directions to a trail's start in Google Maps. */
object Directions {
    /** mode: "d" drive, "w" walk, "b" bike. Opens Google Maps navigation; falls back to the Maps website. */
    fun open(ctx: Context, lat: Double, lon: Double, mode: String): Boolean {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=$mode"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(nav); return true } catch (e: Exception) { }
        val travel = when (mode) { "w" -> "walking"; "b" -> "bicycling"; else -> "driving" }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=$travel&dir_action=navigate"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(web); true } catch (e: Exception) { false }
    }
}
