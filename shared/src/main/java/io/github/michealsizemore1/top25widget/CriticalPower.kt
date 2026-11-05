package io.github.michealsizemore1.top25widget

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Critical power from your own runs (the same idea as Stryd's auto-CP): take your best average
 * power for 3–20 minutes over the last 90 days and fit the critical-power model
 * (work = CP × time + W′). The fit is only trusted when there are real hard efforts — easy runs
 * alone would give a flat curve and a far-too-low CP, so then nothing is suggested.
 */
object CriticalPower {
    val DURATIONS = listOf(180, 240, 300, 420, 540, 600, 720, 900, 1200)

    data class Best(val sec: Int, val watts: Int, val start: Long)
    data class Estimate(
        val cp: Int?, val wPrimeKj: Double?, val bests: List<Best>, val reason: String,
    ) {
        val valid get() = cp != null
    }

    /** Best average power for each duration in one run (samples: [t, m*10, hr, speed*100, cad, power, elapsed]). */
    fun bests(run: JSONObject): Map<Int, Int> {
        val s = run.optJSONArray("samples") ?: return emptyMap()
        val n = s.length(); if (n < 180) return emptyMap()
        val pre = LongArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + (s.optJSONArray(i)?.optInt(5) ?: 0).coerceIn(0, 1500)
        if (pre[n] == 0L) return emptyMap()
        val out = HashMap<Int, Int>()
        for (d in DURATIONS) {
            if (d > n) break
            var best = 0L
            for (i in d..n) { val w = pre[i] - pre[i - d]; if (w > best) best = w }
            out[d] = (best / d).toInt()
        }
        return out
    }

    fun estimate(runs: List<JSONObject>, now: Long = System.currentTimeMillis(), days: Int = 90): Estimate {
        val recent = runs.filter { now - it.optLong("start") in 0 until days * 86_400_000L }
        val best = HashMap<Int, Best>()
        recent.forEach { r ->
            // Cached per run (the computation is the slow part).
            val b = r.optJSONObject("mmp")?.let { o -> DURATIONS.filter { o.has("$it") }.associateWith { o.getInt("$it") } } ?: bests(r)
            b.forEach { (d, w) -> if (w > (best[d]?.watts ?: 0)) best[d] = Best(d, w, r.optLong("start")) }
        }
        val pts = DURATIONS.mapNotNull { best[it] }
        if (pts.size < 4 || pts.none { it.sec <= 300 } || pts.none { it.sec >= 720 })
            return Estimate(null, null, pts, "Not enough power data in the last $days days yet.")
        // Least-squares line through (t, work).
        val xs = pts.map { it.sec.toDouble() }; val ys = pts.map { it.sec * it.watts.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.indices.sumOf { (xs[it] - mx) * (xs[it] - mx) }
        val wPrime = (my - slope * mx) / 1000.0
        val short = pts.first { it.sec <= 300 }; val long = pts.last()
        val hardEnough = short.watts >= long.watts * 1.08
        return when {
            !hardEnough || wPrime < 5 -> Estimate(null, wPrime, pts,
                "No all-out efforts recently (your best 3–5 min power is close to your 20 min power), so CP can't be measured from easy runs.")
            wPrime > 40 || slope < 120 || slope > 700 -> Estimate(null, wPrime, pts, "Your efforts don't fit the model well — try a CP test.")
            else -> Estimate(slope.roundToInt(), wPrime, pts, "From your best ${short.sec / 60}-min (${short.watts} W) and ${long.sec / 60}-min (${long.watts} W) efforts in the last $days days.")
        }
    }

    /** Stryd's test: after a warm-up, 3 minutes all-out, 30 minutes easy, then 9 minutes all-out. */
    const val TEST = "CP test: warm up 15 min, run 3 min all-out, jog 30 min easy, then 9 min all-out, cool down. The next estimate uses those efforts."
}
