package io.github.michealsizemore1.beyondstride.run

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

    fun has(run: JSONObject) = run.optJSONObject("weather")?.let { it.has("tempF") && it.has("windMph") } == true && (run.has("elevGain") || (run.optJSONArray("track")?.length() ?: 0) <= 10)

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

    /** Compass name for a wind direction (where it blows FROM). */
    fun compass(deg: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg % 360 + 360) % 360 + 22) / 45 % 8]

    /**
     * Estimated air power: the share of your running power spent pushing through air, from your speed,
     * the wind at the run (and which way you were heading, if there's a GPS route), air density and a
     * typical runner's frontal drag area. Stryd measures this with its wind sensor; this is an estimate
     * of the same thing. Usually 1–5%; higher into a strong headwind.
     */
    fun airEstimate(run: JSONObject, w: JSONObject): Double? {
        val samples = run.optJSONArray("samples") ?: return null
        val windMs = w.optDouble("windMph").takeIf { !it.isNaN() }?.let { it * 0.44704 * 0.7 } ?: return null   // 10 m wind → about body height
        val fromDeg = w.optDouble("windDir", Double.NaN)
        val tempK = (w.optDouble("tempF", 55.0) - 32) * 5 / 9 + 273.15
        val rho = (w.optDouble("pressure", 1013.0) * 100) / (287.05 * tempK)
        val cdA = 0.24
        // Heading over time from the GPS route (moving seconds → bearing).
        val tr = run.optJSONArray("track")
        val headings = ArrayList<Pair<Int, Double>>()
        if (tr != null && tr.length() > 10 && !fromDeg.isNaN()) {
            for (i in 1 until tr.length()) {
                val a = tr.optJSONArray(i - 1) ?: continue; val b = tr.optJSONArray(i) ?: continue
                val lat1 = Math.toRadians(a.optDouble(0)); val lat2 = Math.toRadians(b.optDouble(0))
                val dLon = Math.toRadians(b.optDouble(1) - a.optDouble(1))
                if (Math.abs(lat2 - lat1) + Math.abs(dLon) < 1e-7) continue
                val brg = Math.toDegrees(Math.atan2(Math.sin(dLon) * Math.cos(lat2), Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)))
                headings += b.optInt(2) to brg
            }
        }
        var air = 0.0; var total = 0.0; var hi = 0
        for (i in 0 until samples.length()) {
            val s = samples.optJSONArray(i) ?: continue
            val v = s.optInt(3) / 100.0; val pw = s.optInt(5)
            if (v < 1.0 || pw <= 0) continue
            val head: Double = if (headings.isNotEmpty()) {
                val t = s.optInt(0)
                while (hi + 1 < headings.size && headings[hi + 1].first <= t) hi++
                windMs * Math.cos(Math.toRadians(fromDeg - headings[hi].second))   // + into the wind
            } else 0.0
            val rel = v + head
            // No route: average over all directions → v·(v² + w²/2).
            val pAir = if (headings.isNotEmpty()) 0.5 * rho * cdA * rel * Math.abs(rel) * v else 0.5 * rho * cdA * v * (v * v + windMs * windMs / 2)
            air += maxOf(0.0, pAir); total += pw
        }
        if (total <= 0) return null
        return Math.round(air / total * 1000) / 10.0
    }

    /** Looks up and saves the weather into the run file. Call off the main thread. True if it worked. */
    fun fill(ctx: Context, start: Long): Boolean {
        val run = PhoneRuns.read(ctx, start) ?: return false
        if (has(run)) return true
        val hadWeather = run.optJSONObject("weather")?.let { it.has("tempF") && it.has("windMph") } == true
        val (lat, lon) = place(ctx, run).let { it[0] to it[1] }
        val mid = start + run.optLong("elapsedSeconds", run.optLong("movingSeconds")) * 500   // middle of the run
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val d0 = day.format(Date(mid - 86_400_000L)); val d1 = day.format(Date(mid + 86_400_000L))
        val ll = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", lat, lon)
        val w = JSONObject()
        if (!hadWeather) try {
            val ageDays = (System.currentTimeMillis() - mid) / 86_400_000L
            val base = if (ageDays > 80) "https://archive-api.open-meteo.com/v1/archive" else "https://api.open-meteo.com/v1/forecast"
            val o = get("$base?$ll&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=GMT&start_date=$d0&end_date=$d1")
            at(o, "temperature_2m", mid)?.let { w.put("tempF", Math.round(it)) }
            at(o, "relative_humidity_2m", mid)?.let { w.put("humidity", Math.round(it)) }
            at(o, "wind_speed_10m", mid)?.let { w.put("windMph", Math.round(it * 10) / 10.0) }
            at(o, "wind_direction_10m", mid)?.let { w.put("windDir", Math.round(it)) }
            at(o, "wind_gusts_10m", mid)?.let { w.put("gustMph", Math.round(it)) }
            at(o, "surface_pressure", mid)?.let { w.put("pressure", Math.round(it)) }
            airEstimate(run, w)?.let { w.put("airPct", it) }
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
