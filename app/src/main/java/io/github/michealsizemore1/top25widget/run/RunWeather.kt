package io.github.michealsizemore1.top25widget.run

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Temperature, humidity and AQI for a run, at its place and time (Open-Meteo weather and air quality,
 * free, no key). Saved into the run as "weather": {tempF, humidity, aqi, lat, lon}.
 */
object RunWeather {
    private const val HOME_LAT = 43.6121    // Meridian, ID — used if the run has no location at all
    private const val HOME_LON = -116.3915

    fun has(run: JSONObject) = run.optJSONObject("weather")?.has("tempF") == true && (run.has("elevGain") || (run.optJSONArray("track")?.length() ?: 0) <= 10)

    /** Where the run was: the watch's start reading, else first GPS point, else the phone's last location, else home. */
    @SuppressLint("MissingPermission")
    private fun place(ctx: Context, run: JSONObject): DoubleArray {
        run.optJSONArray("startLoc")?.takeIf { it.length() == 2 }?.let { return doubleArrayOf(it.optDouble(0), it.optDouble(1)) }
        run.optJSONArray("track")?.optJSONArray(0)?.let { p -> return doubleArrayOf(p.optDouble(0), p.optDouble(1)) }
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(LocationManager::class.java)
                lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                    .maxByOrNull { it.time }?.let { return doubleArrayOf(it.latitude, it.longitude) }
            } catch (e: Exception) { }
        }
        return doubleArrayOf(HOME_LAT, HOME_LON)
    }

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000 }
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    /** Value of an hourly series at the hour nearest [atMs]. */
    private fun at(o: JSONObject, key: String, atMs: Long): Double? {
        val h = o.optJSONObject("hourly") ?: return null
        val times = h.optJSONArray("time") ?: return null
        val vals = h.optJSONArray(key) ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        var best = -1; var bestD = Long.MAX_VALUE
        for (i in 0 until times.length()) {
            if (vals.isNull(i)) continue
            val t = try { fmt.parse(times.getString(i))!!.time } catch (e: Exception) { continue }
            val d = Math.abs(t - atMs)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD < 3 * 3600_000L) vals.optDouble(best) else null
    }

    /** Looks up and saves the weather into the run file. Call off the main thread. True if it worked. */
    fun fill(ctx: Context, start: Long): Boolean {
        val run = PhoneRuns.read(ctx, start) ?: return false
        if (has(run)) return true
        val hadWeather = run.optJSONObject("weather")?.has("tempF") == true
        val (lat, lon) = place(ctx, run).let { it[0] to it[1] }
        val mid = start + run.optLong("elapsedSeconds", run.optLong("movingSeconds")) * 500   // middle of the run
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val d0 = day.format(Date(mid - 86_400_000L)); val d1 = day.format(Date(mid + 86_400_000L))
        val ll = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", lat, lon)
        val w = JSONObject()
        if (!hadWeather) try {
            val ageDays = (System.currentTimeMillis() - mid) / 86_400_000L
            val base = if (ageDays > 80) "https://archive-api.open-meteo.com/v1/archive" else "https://api.open-meteo.com/v1/forecast"
            val o = get("$base?$ll&hourly=temperature_2m,relative_humidity_2m&temperature_unit=fahrenheit&timezone=GMT&start_date=$d0&end_date=$d1")
            at(o, "temperature_2m", mid)?.let { w.put("tempF", Math.round(it)) }
            at(o, "relative_humidity_2m", mid)?.let { w.put("humidity", Math.round(it)) }
        } catch (e: Exception) { android.util.Log.w("RunWeather", "weather failed", e) }
        if (!hadWeather) try {
            val o = get("https://air-quality-api.open-meteo.com/v1/air-quality?$ll&hourly=us_aqi&timezone=GMT&start_date=$d0&end_date=$d1")
            at(o, "us_aqi", mid)?.let { w.put("aqi", Math.round(it)) }
        } catch (e: Exception) { android.util.Log.w("RunWeather", "aqi failed", e) }
        // No barometer reading on the watch: work out the climb from the GPS route's elevations.
        if (!run.has("elevGain")) try {
            val tr = run.optJSONArray("track")
            if (tr != null && tr.length() > 10) {
                val n = minOf(100, tr.length()); val step = (tr.length() - 1).toDouble() / (n - 1)
                val idx = (0 until n).map { (it * step).toInt() }
                val la = idx.joinToString(",") { String.format(Locale.US, "%.5f", tr.getJSONArray(it).optDouble(0)) }
                val lo = idx.joinToString(",") { String.format(Locale.US, "%.5f", tr.getJSONArray(it).optDouble(1)) }
                val e = get("https://api.open-meteo.com/v1/elevation?latitude=$la&longitude=$lo").getJSONArray("elevation")
                var up = 0.0; var down = 0.0
                for (i in 1 until e.length()) { val d = e.getDouble(i) - e.getDouble(i - 1); if (d > 1) up += d else if (d < -1) down -= d }
                run.put("elevGain", Math.round(up * 10) / 10.0).put("elevLoss", Math.round(down * 10) / 10.0).put("elevFrom", "map")
            }
        } catch (e: Exception) { }
        if (hadWeather) { PhoneRuns.save(ctx, run); return true }
        if (!w.has("tempF") && !w.has("aqi")) { if (run.has("elevGain")) PhoneRuns.save(ctx, run); return false }
        w.put("lat", lat).put("lon", lon)
        PhoneRuns.save(ctx, run.put("weather", w))
        return true
    }
}
