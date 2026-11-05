package io.github.michealsizemore1.top25widget

import org.json.JSONObject
import java.util.Locale

/**
 * Longer-term insights, the same on phone and watch:
 *  - Zone 2 fitness: pace and power while heart rate is in Zone 2 (faster at the same HR = fitter).
 *  - Personal bests: fastest 1 mi, 5K, 10K, half marathon inside any run.
 *  - Training load: last 7 days vs the 4-week average, with ramp warnings.
 * Samples: [moving s, meters*10, hr, speed*100, cadence, power, elapsed s], one per moving second.
 */
object Insights {
    // ---------------------------------------------------------------- Zone 2 fitness

    data class Z2(val secPerMile: Int, val power: Int, val minutes: Int, val hr: Int)

    /** Pace/power while HR is in Zone 2 (needs 10+ minutes there, running not walking). */
    fun zone2(run: JSONObject, hrMax: Double, hrRest: Double): Z2? {
        run.optJSONObject("z2")?.let { o -> if (o.has("pace")) return Z2(o.optInt("pace"), o.optInt("power"), o.optInt("minutes"), o.optInt("hr")) }
        val a = run.optJSONArray("samples") ?: return null
        val b = RunMath.hrBounds(hrMax, hrRest)
        val lo = b[1]; val hi = b[2] - 1
        var n = 0; var spd = 0.0; var pw = 0L; var pwN = 0; var hrSum = 0L
        for (i in 0 until a.length()) {
            val s = a.optJSONArray(i) ?: continue
            val hr = s.optInt(2); val v = s.optInt(3) / 100.0; val p = s.optInt(5)
            if (hr in lo..hi && v > 1.4) {
                n++; spd += v; hrSum += hr
                if (p > 0) { pw += p; pwN++ }
            }
        }
        if (n < 600) return null
        val avg = spd / n
        return Z2((RunMath.METERS_PER_MILE / avg).toInt(), if (pwN > n / 2) (pw / pwN).toInt() else 0, n / 60, (hrSum / n).toInt())
    }
    fun z2Json(z: Z2) = JSONObject().put("pace", z.secPerMile).put("power", z.power).put("minutes", z.minutes).put("hr", z.hr)

    /** Last 4 weeks vs the 4 weeks before: average Zone 2 pace (s/mi) of each, or null. */
    fun z2Trend(runs: List<JSONObject>, hrMax: Double, hrRest: Double, now: Long = System.currentTimeMillis()): Pair<Int?, Int?> {
        val day = 86_400_000L
        val recent = runs.filter { now - it.optLong("start") < 28 * day }.mapNotNull { zone2(it, hrMax, hrRest) }
        val before = runs.filter { now - it.optLong("start") in (28 * day) until (56 * day) }.mapNotNull { zone2(it, hrMax, hrRest) }
        fun avg(l: List<Z2>) = if (l.isEmpty()) null else (l.sumOf { it.secPerMile.toLong() * it.minutes } / l.sumOf { it.minutes.toLong() }).toInt()
        return avg(recent) to avg(before)
    }

    // ---------------------------------------------------------------- Personal bests

    val PB_DISTANCES = listOf("1mi" to 1609.344, "5k" to 5000.0, "10k" to 10000.0, "half" to 21097.5)
    fun pbName(key: String) = when (key) { "1mi" -> "1 mile"; "5k" -> "5K"; "10k" -> "10K"; "half" -> "Half marathon"; else -> key }

    /** Fastest time (s) for each distance inside this run. */
    fun bestEfforts(run: JSONObject): Map<String, Int> {
        run.optJSONObject("bests")?.let { o -> return o.keys().asSequence().associateWith { o.optInt(it) } }
        val a = run.optJSONArray("samples") ?: return emptyMap()
        val n = a.length()
        if (n < 60) return emptyMap()
        val t = IntArray(n); val m = DoubleArray(n)
        for (i in 0 until n) { val s = a.optJSONArray(i); t[i] = s?.optInt(0) ?: 0; m[i] = (s?.optInt(1) ?: 0) / 10.0 }
        val out = LinkedHashMap<String, Int>()
        for ((key, dist) in PB_DISTANCES) {
            if (m[n - 1] < dist) continue
            var best = Int.MAX_VALUE; var j = 0
            for (i in 0 until n) {
                while (j < n && m[j] - m[i] < dist) j++
                if (j >= n) break
                // Interpolate the exact moment the distance is reached.
                val over = (m[j] - m[i]) - dist
                val segM = m[j] - m[j - 1]
                val segT = t[j] - t[j - 1]
                val sec = (t[j] - t[i]) - (if (segM > 0) over / segM * segT else 0.0)
                if (sec > 0 && sec < best) best = sec.toInt()
            }
            if (best < Int.MAX_VALUE) out[key] = best
        }
        return out
    }
    fun bestsJson(b: Map<String, Int>) = JSONObject().apply { b.forEach { (k, v) -> put(k, v) } }

    data class PB(val sec: Int, val start: Long)

    /** Best of each distance across all runs. */
    fun records(runs: List<JSONObject>): Map<String, PB> {
        val out = LinkedHashMap<String, PB>()
        for (r in runs) for ((k, v) in bestEfforts(r)) { val cur = out[k]; if (cur == null || v < cur.sec) out[k] = PB(v, r.optLong("start")) }
        return PB_DISTANCES.mapNotNull { (k, _) -> out[k]?.let { k to it } }.toMap()
    }

    /** Distances where this run set a new best (compared with the runs before it). */
    fun newBests(run: JSONObject, earlier: List<JSONObject>): Map<String, Int> {
        val mine = bestEfforts(run)
        val rec = records(earlier.filter { it.optLong("start") < run.optLong("start") })
        return mine.filter { (k, v) -> rec[k]?.let { v < it.sec } ?: true }
    }

    fun clock(sec: Int) = if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
        else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    // ---------------------------------------------------------------- Training load

    data class Load(
        val acute: Int,          // stress, last 7 days
        val chronicWeek: Int,    // average weekly stress, last 28 days
        val ratio: Double?,      // acute / chronic (null until there's enough history)
        val milesWeek: Double, val milesAvg: Double,
        val status: String, val advice: String, val color: Int, val daysOfHistory: Int,
    )

    fun load(runs: List<JSONObject>, now: Long = System.currentTimeMillis()): Load {
        val day = 86_400_000L
        fun stress(r: JSONObject) = r.optInt("stress").takeIf { it > 0 }
            ?: (r.optInt("movingSeconds") / 60.0 * 0.9).toInt()   // no power: about 0.9 per easy minute
        val last7 = runs.filter { now - it.optLong("start") in 0 until 7 * day }
        val last28 = runs.filter { now - it.optLong("start") in 0 until 28 * day }
        val acute = last7.sumOf { stress(it) }
        val chronic = last28.sumOf { stress(it) } / 4
        val miles7 = last7.sumOf { it.optDouble("distance", 0.0) } / RunMath.METERS_PER_MILE
        val milesAvg = last28.sumOf { it.optDouble("distance", 0.0) } / RunMath.METERS_PER_MILE / 4
        val oldest = runs.minOfOrNull { it.optLong("start") } ?: now
        val days = ((now - oldest) / day).toInt()
        val ratio = if (days >= 21 && chronic > 0) acute.toDouble() / chronic else null
        val (status, advice, color) = when {
            ratio == null -> Triple("Building baseline", "Needs about 3 weeks of runs in BeyondStride to compare (${days} days so far).", 0xFF9AA3A0.toInt())
            ratio > 1.5 -> Triple("Ramping too fast", "This week is much harder than your usual. Injury risk is higher — add an easy or rest day.", 0xFFEF4444.toInt())
            ratio > 1.3 -> Triple("Building fast", "A bit more than your usual load. Fine for a week; don't stack another big jump.", 0xFFF97316.toInt())
            ratio >= 0.8 -> Triple("Steady", "Your load is in the healthy range for building fitness.", 0xFF22C55E.toInt())
            else -> Triple("Light week", "Less than usual — good for recovery or a taper.", 0xFF4FA3E0.toInt())
        }
        return Load(acute, chronic, ratio, miles7, milesAvg, status, advice, color, days)
    }
}
