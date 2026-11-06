package io.github.michealsizemore1.beyondstride

import org.json.JSONObject
import kotlin.math.roundToInt

/** Automatic mile splits from a run's per-second samples ([moving s, meters*10, hr, speed*100, cadence, power, elapsed]). */
object Splits {
    data class Split(val n: Int, val meters: Double, val sec: Int, val hr: Int, val power: Int)

    fun miles(run: JSONObject, every: Double = 1609.344): List<Split> {
        val s = run.optJSONArray("samples") ?: return emptyList()
        if (s.length() < 2) return emptyList()
        val out = ArrayList<Split>()
        var boundary = every
        var startT = s.optJSONArray(0)?.optInt(0)?.toDouble() ?: 0.0
        var startM = 0.0
        var hrSum = 0L; var hrN = 0; var pwSum = 0L; var pwN = 0
        var prevT = startT; var prevM = (s.optJSONArray(0)?.optInt(1) ?: 0) / 10.0
        for (i in 1 until s.length()) {
            val a = s.optJSONArray(i) ?: continue
            val t = a.optInt(0).toDouble(); val m = a.optInt(1) / 10.0
            a.optInt(2).takeIf { it > 0 }?.let { hrSum += it; hrN++ }
            a.optInt(5).takeIf { it > 0 }?.let { pwSum += it; pwN++ }
            while (m >= boundary && m > prevM) {
                val tb = prevT + (t - prevT) * (boundary - prevM) / (m - prevM)
                out += Split(out.size + 1, boundary - startM, (tb - startT).roundToInt(),
                    if (hrN > 0) (hrSum / hrN).toInt() else 0, if (pwN > 0) (pwSum / pwN).toInt() else 0)
                startT = tb; startM = boundary; boundary += every
                hrSum = 0; hrN = 0; pwSum = 0; pwN = 0
            }
            prevT = t; prevM = m
        }
        val restM = prevM - startM
        if (restM >= 160) out += Split(out.size + 1, restM, (prevT - startT).roundToInt(),
            if (hrN > 0) (hrSum / hrN).toInt() else 0, if (pwN > 0) (pwSum / pwN).toInt() else 0)
        return out
    }
}
