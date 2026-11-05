package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recovery & readiness (same on the phone and the watch).
 *
 * Inputs:
 *  - Morning check: 2 minutes lying still with the chest strap → HRV (RMSSD) and resting heart rate,
 *    plus how the legs feel (and sleep, if there's no sleep data from Samsung Health).
 *  - Last night's sleep (Samsung Health → Health Connect → phone → watch).
 *  - Recent training (stress, hard runs) from BeyondStride runs.
 * Each is compared with YOUR normal (rolling 60 days), the way HRV4Training / Garmin do it.
 */
object Readiness {

    // ---------------------------------------------------------------- HRV from beat-to-beat intervals

    data class Hrv(val rmssd: Double, val hr: Int, val beats: Int, val cleanPct: Int)

    /**
     * RMSSD from R-R intervals (ms). Skips the first [skipSec] seconds (settling) and drops
     * beats that differ by more than 20% from the median of their neighbours (missed/extra beats).
     */
    fun hrv(rr: List<Int>, skipSec: Int = 30): Hrv? {
        var t = 0; val use = ArrayList<Int>()
        for (x in rr) { t += x; if (t >= skipSec * 1000 && x in 300..2000) use += x }
        if (use.size < 60) return null
        val clean = ArrayList<Int>()
        for (i in use.indices) {
            val win = use.subList(maxOf(0, i - 5), minOf(use.size, i + 6)).sorted()
            val med = win[win.size / 2]
            if (kotlin.math.abs(use[i] - med) <= med * 0.2) clean += use[i]
        }
        if (clean.size < 50) return null
        var sum = 0.0; var n = 0
        for (i in 1 until clean.size) { val d = (clean[i] - clean[i - 1]).toDouble(); sum += d * d; n++ }
        val rmssd = sqrt(sum / n)
        val hr = (60000.0 / clean.average()).roundToInt()
        return Hrv(rmssd, hr, clean.size, clean.size * 100 / use.size)
    }

    // ---------------------------------------------------------------- stored data

    /** One morning check. legs/sleepFeel: -1 not answered, 0 bad, 1 OK, 2 good. */
    data class Check(val day: String, val rmssd: Double, val hr: Int, val legs: Int = -1, val sleepFeel: Int = -1, val at: Long = 0L)
    /** One night of sleep, keyed by the day you woke up. sleepHr = average of the lowest heart rates overnight. */
    data class Night(val day: String, val minutes: Int, val sleepHr: Int = 0)

    fun checksFrom(s: String?): List<Check> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Check(it.getString("day"), it.optDouble("rmssd"), it.optInt("hr"), it.optInt("legs", -1), it.optInt("sleep", -1), it.optLong("at"))
        }
    } catch (e: Exception) { emptyList() }

    fun checksJson(l: List<Check>) = JSONArray().apply {
        l.sortedBy { it.day }.takeLast(120).forEach {
            put(JSONObject().put("day", it.day).put("rmssd", Math.round(it.rmssd * 10) / 10.0).put("hr", it.hr)
                .put("legs", it.legs).put("sleep", it.sleepFeel).put("at", it.at))
        }
    }.toString()

    /** Adds or replaces the check for its day. */
    fun withCheck(l: List<Check>, c: Check) = (l.filter { it.day != c.day } + c).sortedBy { it.day }

    fun nightsFrom(s: String?): List<Night> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Night(it.getString("day"), it.optInt("min"), it.optInt("hr")) }
    } catch (e: Exception) { emptyList() }

    fun nightsJson(l: List<Night>) = JSONArray().apply {
        l.sortedBy { it.day }.takeLast(90).forEach { put(JSONObject().put("day", it.day).put("min", it.minutes).put("hr", it.sleepHr)) }
    }.toString()

    // ---------------------------------------------------------------- the score

    data class Part(val name: String, val value: String, val pts: Int, val note: String, val weight: Int)
    data class Result(
        val score: Int, val label: String, val color: Int, val advice: String,
        val parts: List<Part>, val checkedToday: Boolean, val baselineDays: Int,
    ) {
        fun json(): JSONObject = JSONObject().put("score", score).put("label", label)
            .put("parts", JSONArray().apply { parts.forEach { put(JSONObject().put("name", it.name).put("value", it.value).put("pts", it.pts)) } })
    }

    const val GREEN = 0xFF22C55E.toInt(); const val YELLOW = 0xFFF2C94C.toInt(); const val RED = 0xFFEF4444.toInt()
    private const val DAY = 86_400_000L

    private fun clamp(v: Double) = v.coerceIn(0.0, 100.0).roundToInt()
    fun hmm(min: Int) = "${min / 60}h ${String.format(Locale.US, "%02d", min % 60)}m"

    /**
     * @param today local date "yyyy-MM-dd"
     * @param runs BeyondStride runs (any order)
     */
    fun compute(checks: List<Check>, nights: List<Night>, runs: List<JSONObject>, today: String, now: Long = System.currentTimeMillis()): Result? {
        val parts = ArrayList<Part>()
        val todayCheck = checks.lastOrNull { it.day == today }
        val prior = checks.filter { it.day < today && daysBetween(it.day, today) in 1..60 }
        var hrvZ: Double? = null

        // HRV vs your normal (log scale, as HRV is skewed).
        if (todayCheck != null && todayCheck.rmssd > 0) {
            val v = todayCheck.rmssd
            if (prior.size >= 5) {
                val lns = prior.map { ln(it.rmssd) }
                val mean = lns.average()
                val sd = maxOf(0.05, sqrt(lns.sumOf { (it - mean) * (it - mean) } / (lns.size - 1)))
                val z = (ln(v) - mean) / sd; hrvZ = z
                val normal = kotlin.math.exp(mean).roundToInt()
                val note = when {
                    z < -1.5 -> "well below your normal ($normal ms)"
                    z < -0.5 -> "a little below your normal ($normal ms)"
                    z > 1.5 -> "well above your normal ($normal ms)"
                    else -> "in your normal range (~$normal ms)"
                }
                parts += Part("HRV", "${v.roundToInt()} ms", clamp(80 + 20 * z), note, 35)
            } else parts += Part("HRV", "${v.roundToInt()} ms", -1, "learning your normal · ${prior.size}/5 mornings", 0)
        }
        // Resting heart rate from the morning check (or overnight from Samsung Health).
        if (todayCheck != null && todayCheck.hr > 0 && prior.size >= 5) {
            val mean = prior.map { it.hr }.average()
            val d = todayCheck.hr - mean
            parts += Part("Resting HR", "${todayCheck.hr} bpm", clamp(80 - d * 8), when {
                d >= 5 -> "${d.roundToInt()} bpm above normal — stress, illness or fatigue?"
                d >= 2.5 -> "a bit above your normal (${mean.roundToInt()})"
                else -> "normal (${mean.roundToInt()})"
            }, 15)
        } else {
            val nightToday = nights.lastOrNull { it.day == today && it.sleepHr > 0 }
            val nPrior = nights.filter { it.day < today && it.sleepHr > 0 && daysBetween(it.day, today) in 1..60 }
            if (nightToday != null && nPrior.size >= 5) {
                val mean = nPrior.map { it.sleepHr }.average(); val d = nightToday.sleepHr - mean
                parts += Part("Sleeping HR", "${nightToday.sleepHr} bpm", clamp(80 - d * 8),
                    if (d >= 4) "${d.roundToInt()} bpm above normal" else "normal (${mean.roundToInt()})", 15)
            }
        }
        // Sleep.
        val night = nights.lastOrNull { it.day == today && it.minutes > 0 }
        if (night != null) {
            val m = night.minutes
            parts += Part("Sleep", hmm(m), clamp(15 + (m - 240) * 85.0 / 210), when {
                m >= 450 -> "great"; m >= 400 -> "good"; m >= 330 -> "a bit short"; else -> "short — recovery suffers"
            }, 25)
        } else if (todayCheck != null && todayCheck.sleepFeel >= 0) {
            parts += Part("Sleep", listOf("Poor", "OK", "Good")[todayCheck.sleepFeel], listOf(35, 70, 95)[todayCheck.sleepFeel], "how you said you slept", 25)
        }
        // Training: recent hard runs and load ramp.
        if (runs.isNotEmpty()) {
            val ld = Insights.load(runs.filter { now - it.optLong("start") < 56 * DAY }, now)
            var pts = when { ld.ratio == null -> 85; ld.ratio > 1.5 -> 30; ld.ratio > 1.3 -> 55; else -> 90 }
            var note = if (ld.ratio == null) "load looks fine" else ld.status.lowercase()
            val lastHard = runs.filter { isHard(it) }.maxOfOrNull { it.optLong("end", it.optLong("start")) }
            if (lastHard != null) {
                val h = (now - lastHard) / 3_600_000.0
                if (h < 24) { pts = minOf(pts, 45); note = "hard run ${h.roundToInt()} h ago — still recovering" }
                else if (h < 48) { pts = minOf(pts, 70); note = "hard run yesterday" }
            }
            parts += Part("Training", ld.ratio?.let { String.format(Locale.US, "%.2f ratio", it) } ?: "${ld.acute} stress/7d", pts, note, 15)
        }
        // Legs.
        if (todayCheck != null && todayCheck.legs >= 0)
            parts += Part("Legs", listOf("Sore", "OK", "Fresh")[todayCheck.legs], listOf(30, 70, 100)[todayCheck.legs], "how they feel", 10)

        val scored = parts.filter { it.pts >= 0 && it.weight > 0 }
        if (scored.isEmpty()) return null
        var score = (scored.sumOf { it.pts * it.weight }.toDouble() / scored.sumOf { it.weight }).roundToInt()
        if ((hrvZ ?: 0.0) < -1.5) score = minOf(score, 49)
        if (night != null && night.minutes < 300) score = minOf(score, 64)
        if (todayCheck == null) score = minOf(score, 80)   // no HRV: don't promise "fully ready"

        val (label, color) = when { score >= 70 -> "Ready" to GREEN; score >= 50 -> "Moderate" to YELLOW; else -> "Low" to RED }
        val drag = scored.filter { it.pts < 60 }.minByOrNull { it.pts }
        val advice = when (label) {
            "Ready" -> "Good to go with today's plan."
            "Moderate" -> "Run today's plan but keep the hard parts controlled. If it feels off, make it an easy Zone 2 run."
            else -> "Make today easy Zone 2 (or rest). Swap any intervals or tempo for another day."
        } + (drag?.let { " Biggest factor: ${it.name.lowercase()} — ${it.note}." } ?: "") +
            (if (todayCheck == null) " Do the 2-minute morning check for the full picture." else "")
        return Result(score, label, color, advice, parts, todayCheck != null, prior.size)
    }

    /** A run that needs ~2 days to recover from: high intensity, high stress, or 15+ minutes in Zone 4–5. */
    fun isHard(r: JSONObject): Boolean {
        if (r.optDouble("intensity", 0.0) >= 0.88 && r.optInt("movingSeconds") >= 900) return true
        if (r.optInt("stress") >= 100) return true
        val z = r.optJSONArray("hrZoneSeconds") ?: return false
        return z.length() >= 5 && z.optInt(3) + z.optInt(4) >= 15 * 60
    }

    /** Average morning-check heart rate over the last 14 days (rounded to 0.5), with the number of checks; null until 5 checks. */
    fun morningRest(checks: List<Check>, today: String = today()): Pair<Double, Int>? {
        val l = checks.filter { it.hr > 0 && daysBetween(it.day, today) in 0..13 }
        if (l.size < 5) return null
        return Math.round(l.map { it.hr }.average() * 2) / 2.0 to l.size
    }

    fun daysBetween(a: String, b: String): Long = try {
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b))
    } catch (e: Exception) { 999 }

    fun today(): String = java.time.LocalDate.now().toString()
}
