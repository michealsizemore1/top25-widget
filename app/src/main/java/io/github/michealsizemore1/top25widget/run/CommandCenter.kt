package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import io.github.michealsizemore1.top25widget.RunMath
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text summaries of a run, for the Command Center notes and the Strava description. */
object Summary {
    fun zoneLine(secs: JSONArray?, prefix: String): String? {
        if (secs == null) return null
        val total = (0 until secs.length()).sumOf { secs.optInt(it) }
        if (total <= 0) return null
        return prefix + (0 until minOf(5, secs.length())).joinToString(" · ") { "Z${it + 1} ${RunMath.clock(secs.optInt(it))}" }
    }

    fun notes(ctx: Context, run: JSONObject, forStrava: Boolean = false): String {
        val lines = mutableListOf<String>()
        if (run.optInt("np") > 0) {
            var l = "Power: avg ${run.optInt("avgPower")} W · NP ${run.optInt("np")} W"
            if (run.optInt("cp") > 0) l += " · IF ${"%.2f".format(Locale.US, run.optDouble("intensity"))} · Stress ${run.optInt("stress")} (CP ${run.optInt("cp")})"
            lines += l
        }
        if (run.optInt("avgHr") > 0) lines += "Heart rate: avg ${run.optInt("avgHr")} · max ${run.optInt("maxHr")} bpm (" +
            (if (run.optString("hrSource") == "strap") "chest strap" else "wrist") + ")"
        zoneLine(run.optJSONArray("hrZoneSeconds"), "HR zones: ")?.let { lines += it }
        zoneLine(run.optJSONArray("zoneSeconds"), "Power zones: ")?.let { lines += it }
        run.optDouble("avgStride").takeIf { !it.isNaN() && it > 0 }?.let { lines += "Avg stride ${"%.2f".format(Locale.US, it)} m" }
        val laps = run.optJSONArray("laps")
        if (laps != null && laps.length() > 1) {
            lines += "Splits: " + (0 until laps.length()).joinToString(", ") { i ->
                val l = laps.getJSONObject(i)
                RunMath.paceFor(l.optInt("sec"), l.optDouble("m")) + (l.optInt("hr").takeIf { it > 0 }?.let { "@$it" } ?: "")
            }
        }
        run.optJSONObject("stryd")?.let { s ->
            val parts = listOfNotNull(
                s.optDouble("groundTime").takeIf { !it.isNaN() }?.let { "GCT ${it.toInt()} ms" },
                s.optDouble("oscillation").takeIf { !it.isNaN() }?.let { "VO %.1f cm".format(Locale.US, it) },
                s.optDouble("legSpring").takeIf { !it.isNaN() }?.let { "LSS %.1f kN/m".format(Locale.US, it) },
                s.optDouble("formPower").takeIf { !it.isNaN() }?.let { "Form power ${it.toInt()} W" },
                s.optDouble("airPowerPct").takeIf { !it.isNaN() }?.let { "Air power %.1f%%".format(Locale.US, it) },
                s.optDouble("rss").takeIf { !it.isNaN() }?.let { "RSS ${it.toInt()}" },
            )
            if (parts.isNotEmpty()) lines += "Stryd: " + parts.joinToString(" · ")
        }
        if (!forStrava) lines += "Recorded on Galaxy Watch (" + (if (run.optString("source") == "gps") "GPS" else "Stryd") + " pace/distance)"
        return lines.joinToString("\n")
    }
}

/**
 * Sends runs to the My Life Command Center Activities list. The Command Center keeps its data in
 * the browser (with its own cloud sync), so runs travel in the page link and the page adds them.
 */
object CommandCenter {
    fun activity(ctx: Context, run: JSONObject): JSONObject {
        val start = run.optLong("start")
        val sec = run.optInt("movingSeconds")
        return JSONObject().apply {
            put("id", start)   // same run sent twice is recognized and skipped
            put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start)))
            put("type", "Run")
            put("title", PhoneRuns.title(run))
            put("distance", Math.round(run.optDouble("distance") / RunMath.METERS_PER_MILE * 100) / 100.0)
            put("duration", RunMath.clock(sec))
            put("pace", RunMath.paceFor(sec, run.optDouble("distance")))
            put("power", run.optInt("avgPower"))
            put("heartRate", run.optInt("avgHr"))
            put("cadence", run.optInt("avgCadence"))
            put("notes", Summary.notes(ctx, run))
            put("source", "watch")
            run.optJSONObject("stryd")?.let { s ->
                s.optDouble("elevGain").takeIf { !it.isNaN() }?.let { put("elevGain", Math.round(it * 3.28084)) }
                s.optDouble("temp").takeIf { !it.isNaN() }?.let { put("temp", Math.round(it * 9 / 5 + 32).toString()) }
                s.optDouble("humidity").takeIf { !it.isNaN() }?.let { put("humidity", Math.round(it)) }
                s.optDouble("airPowerPct").takeIf { !it.isNaN() }?.let { put("airPower", it) }
                s.optDouble("rss").takeIf { !it.isNaN() }?.let { put("rss", it.toInt()) }
            }
            PhoneRuns.stravaId(ctx, start).takeIf { it > 0 }?.let { put("routeUrl", "https://www.strava.com/activities/$it") }
        }
    }

    /** Opens the Command Center with these runs; it adds them to Activities and syncs. */
    fun send(ctx: Context, starts: List<Long>) {
        val arr = JSONArray()
        starts.forEach { s -> PhoneRuns.read(ctx, s)?.let { arr.put(activity(ctx, it)) } }
        if (arr.length() == 0) return
        val b64 = Base64.encodeToString(arr.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val url = RunSettings.ccUrl(ctx).substringBefore('#') + "#watchruns=" + b64
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        starts.forEach { PhoneRuns.setSentToCommandCenter(ctx, it) }
    }
}
