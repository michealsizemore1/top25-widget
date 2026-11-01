package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Structured workouts, shared by the phone (builder, plan) and the watch (guided runs).
 *
 * JSON shape:
 *  { "id", "name", "notes", "steps": [ step | repeat ] }
 *  step:   { "kind": warmup|run|recover|rest|cooldown, "dur": time|dist|open, "value": seconds or meters,
 *            "target": none|hr|power|pace, "zone": 1–5 or 0 (custom range), "lo", "hi" }
 *            (pace lo/hi are seconds per mile: lo = faster end)
 *  repeat: { "kind": "repeat", "times": n, "steps": [ step… ] }
 */
object Workouts {
    val KINDS = listOf("warmup", "run", "recover", "rest", "cooldown")
    fun kindName(k: String) = when (k) {
        "warmup" -> "Warm up"; "run" -> "Run"; "recover" -> "Recover"; "rest" -> "Rest"; "cooldown" -> "Cool down"; else -> k
    }

    fun step(kind: String, dur: String, value: Int, target: String = "none", zone: Int = 0, lo: Int = 0, hi: Int = 0) =
        JSONObject().put("kind", kind).put("dur", dur).put("value", value)
            .put("target", target).put("zone", zone).put("lo", lo).put("hi", hi)

    fun repeat(times: Int, vararg steps: JSONObject) =
        JSONObject().put("kind", "repeat").put("times", times).put("steps", JSONArray(steps.toList()))

    fun workout(name: String, notes: String, vararg steps: JSONObject) =
        JSONObject().put("id", "w" + System.nanoTime()).put("name", name).put("notes", notes).put("steps", JSONArray(steps.toList()))

    private fun mi(m: Double) = (m * RunMath.METERS_PER_MILE).toInt()

    /** Starter workouts, using heart-rate zones (how you train). */
    fun templates(): List<JSONObject> = listOf(
        workout("Easy Run", "Conversational effort.", step("run", "open", 0, "hr", 2)),
        workout("Recovery Run", "Very easy — keep it in Zone 1.", step("run", "open", 0, "hr", 1)),
        workout("Long Run", "Steady Zone 2 the whole way.", step("run", "dist", mi(10.0), "hr", 2)),
        workout("Tempo Run", "Comfortably hard in the middle.",
            step("warmup", "dist", mi(1.0), "hr", 2), step("run", "dist", mi(3.0), "hr", 4), step("cooldown", "dist", mi(1.0), "hr", 2)),
        workout("6 × 800 m", "Hard repeats, easy jog between.",
            step("warmup", "dist", mi(1.5), "hr", 2),
            repeat(6, step("run", "dist", 800, "hr", 5), step("recover", "dist", 400, "hr", 1)),
            step("cooldown", "dist", mi(1.0), "hr", 2)),
        workout("Progression Run", "Finish faster than you start.",
            step("run", "dist", mi(3.0), "hr", 2), step("run", "dist", mi(2.0), "hr", 3), step("run", "dist", mi(1.0), "hr", 4)),
    )

    /** Expands repeats into the plain list of steps the watch runs through. */
    fun flatten(w: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val steps = w.optJSONArray("steps") ?: return out
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("kind") == "repeat") {
                val inner = s.optJSONArray("steps") ?: continue
                val times = s.optInt("times", 1).coerceIn(1, 50)
                for (r in 1..times) for (j in 0 until inner.length()) {
                    out += JSONObject(inner.getJSONObject(j).toString()).put("rep", r).put("reps", times)
                }
            } else out += s
        }
        return out
    }

    /** Fills in lo/hi for zone targets from the runner's zones. */
    fun resolve(step: JSONObject, hrMax: Double, hrRest: Double, cp: Int): Pair<Int, Int> {
        val zone = step.optInt("zone")
        val lo = step.optInt("lo"); val hi = step.optInt("hi")
        if (zone !in 1..5) return lo to hi
        return when (step.optString("target")) {
            "hr" -> RunMath.hrBounds(hrMax, hrRest).let { b -> b[zone - 1] to (if (zone < 5) b[zone] - 1 else hrMax.toInt()) }
            "power" -> RunMath.powerBounds(cp).let { b -> b[zone - 1] to (if (zone < 5) b[zone] - 1 else (cp * 1.3).toInt()) }
            else -> lo to hi
        }
    }

    fun durText(s: JSONObject) = when (s.optString("dur")) {
        "time" -> RunMath.clock(s.optInt("value"))
        "dist" -> s.optInt("value").let { m ->
            if (m < 1600 && m % 100 == 0) "$m m" else String.format(Locale.US, "%.2f mi", m / RunMath.METERS_PER_MILE).replace(".00 ", " ")
        }
        else -> "until lap"
    }

    fun targetText(s: JSONObject, hrMax: Double, hrRest: Double, cp: Int): String {
        val (lo, hi) = resolve(s, hrMax, hrRest, cp)
        val z = s.optInt("zone").takeIf { it in 1..5 }?.let { "Z$it " }.orEmpty()
        return when (s.optString("target")) {
            "hr" -> "${z}HR $lo–$hi"
            "power" -> "${z}$lo–$hi W"
            "pace" -> "${RunMath.clock(lo)}–${RunMath.clock(hi)} /mi"
            else -> "no target"
        }
    }

    /** One line per step, e.g. "Run 3 mi · Z4 HR 157–170". */
    fun outline(w: JSONObject, hrMax: Double, hrRest: Double, cp: Int): List<String> {
        val lines = mutableListOf<String>()
        val steps = w.optJSONArray("steps") ?: return lines
        fun one(s: JSONObject) = "${kindName(s.optString("kind"))} ${durText(s)}" +
            (if (s.optString("target") != "none") " · " + targetText(s, hrMax, hrRest, cp) else "")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("kind") == "repeat") {
                lines += "${s.optInt("times")} ×"
                val inner = s.optJSONArray("steps") ?: continue
                for (j in 0 until inner.length()) lines += "   " + one(inner.getJSONObject(j))
            } else lines += one(s)
        }
        return lines
    }

    /** Total planned distance in meters (open and time steps count as 0). */
    fun plannedMeters(w: JSONObject) = flatten(w).filter { it.optString("dur") == "dist" }.sumOf { it.optInt("value") }

    // ------------------------------------------------------------------ plan text → workout

    /**
     * Turns one day of a written training plan (e.g. "Tempo 6 miles w/ 3 mi @ threshold",
     * "Long run 14 miles easy", "8 x 400m @ 5K pace with 400m jog") into a workout.
     * Returns null for rest days. The day's text is kept as the notes.
     */
    fun fromPlanText(text: String): JSONObject? {
        // Skip a date heading line ("Tuesday, November 10") so its numbers aren't read as distances.
        val lines = text.trim().lines()
        val header = Regex("""^\W*(mon|tue|wed|thu|fri|sat|sun|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|day\s+\d|\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)
        val t = (if (lines.size > 1 && header.containsMatchIn(lines[0]) && !Regex("""\b(mi|miles?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lines[0]))
            lines.drop(1).joinToString("\n") else text).lowercase(Locale.US)
        val body = text.lines().drop(1).joinToString(" ").ifBlank { text }
        val hasRun = Regex("""\b(run|race|intervals?|repeats?|tempo|threshold|recovery|progression|easy|long|strides|fartlek|\d+(\.\d+)?\s*(mi|miles?|km))\b""").containsMatchIn(t)
        if (!hasRun || Regex("""\brest\s*day\b|\boff\b(?!\s*road)|\bno\s+run\b""").containsMatchIn(t) &&
            !Regex("""\d+(\.\d+)?\s*(mi|miles?)""").containsMatchIn(t)) return null

        val notes = text.trim()
        fun miles(s: String) = Regex("""(\d+(?:\.\d+)?)(?:\s*(?:-|to|–)\s*\d+(?:\.\d+)?)?\s*(?:mi\b|miles?\b)""").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        val total = miles(t)
        val explicitZone = Regex("""\b(?:z|zone\s*)([1-5])\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val pace = Regex("""(\d{1,2}:\d{2})\s*(?:-|–|to)\s*(\d{1,2}:\d{2})""").find(t)?.let {
            secs(it.groupValues[1]) to secs(it.groupValues[2])
        } ?: (Regex("""@\s*(\d{1,2}:\d{2})""").find(t)
              ?: Regex("""(\d{1,2}:\d{2})\s*(?:/\s*mi|/\s*mile|min/mi|min per mile|per mile|pace)""").find(t))
              ?.let { secs(it.groupValues[1]).let { p -> (p - 10) to (p + 10) } }

        val label = when {
            Regex("""\brace\b""").containsMatchIn(t) -> "Race"
            Regex("""\b(intervals?|repeats?|speed\s*work|\d+\s*[x×]\s*\d)""").containsMatchIn(t) -> "Intervals"
            Regex("""\btempo|threshold""").containsMatchIn(t) -> "Tempo Run"
            Regex("""\blong\s+run""").containsMatchIn(t) -> "Long Run"
            Regex("""\brecovery""").containsMatchIn(t) -> "Recovery Run"
            Regex("""\bprogression""").containsMatchIn(t) -> "Progression Run"
            else -> "Easy Run"
        }

        fun distOrOpen(m: Double?) = if (m != null && m > 0) "dist" to mi(Math.round(m * 4) / 4.0) else "open" to 0
        fun easyStep(kind: String, m: Double?, zone: Int = 2) = distOrOpen(m).let { (d, v) -> step(kind, d, v, "hr", zone) }

        // Easy run with strides: "6 miles easy + 6 x 20 sec strides"
        if (Regex("""\bstrides?\b""").containsMatchIn(t)) {
            val n = Regex("""(\d+)\s*[x×]""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 6
            val sec = Regex("""(\d+)\s*(?:sec|s\b|seconds?)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 20
            return workout("Easy + Strides", notes, easyStep("run", total, explicitZone ?: 2),
                repeat(n, step("run", "time", sec), step("recover", "time", 60, "hr", 1)))
        }

        // A faster section inside a run: "10 miles with middle 6 @ goal pace", "12 mi w/ last 3 at tempo"
        Regex("""(?:w/|with|incl\.?|including)\s*(?:the\s*)?(middle|last|final|first)?\s*(\d+(?:\.\d+)?)\s*(?:mi\b|miles?)?\s*(?:@|at|of)\b?""").find(t)
            ?.takeIf { label != "Intervals" && total != null }?.let { m ->
                val where = m.groupValues[1]
                val hard = m.groupValues[2].toDouble()
                val easy = (total!! - hard).coerceAtLeast(0.0)
                val fast = step("run", "dist", mi(hard))
                if (pace != null) fast.put("target", "pace").put("zone", 0).put("lo", pace.first).put("hi", pace.second)
                else fast.put("target", "hr").put("zone", explicitZone ?: if (label == "Tempo Run") 4 else 3)
                val name = if (label == "Easy Run") "${fmt(total)} mi w/ ${fmt(hard)} fast" else label
                return when (where) {
                    "last", "final" -> workout(name, notes, easyStep("run", easy), fast)
                    "first" -> workout(name, notes, fast, easyStep("run", easy))
                    else -> workout(name, notes, easyStep("warmup", easy / 2), fast, easyStep("cooldown", easy / 2))
                }
            }

        // Repeats: "6 x 800m", "4 × 1 mile", "5x3 min"
        Regex("""(\d+)\s*[x×]\s*(\d+(?:\.\d+)?)\s*(m\b|meters?|k\b|km|mi\b|miles?|min\b|minutes?|sec\b|seconds?|s\b)""").find(t)?.let { m ->
            val times = m.groupValues[1].toInt()
            val n = m.groupValues[2].toDouble()
            val unit = m.groupValues[3]
            val work = when {
                unit.startsWith("min") -> step("run", "time", (n * 60).toInt())
                unit.startsWith("s") -> step("run", "time", n.toInt())
                unit.startsWith("k") -> step("run", "dist", (n * 1000).toInt())
                unit.startsWith("mi") -> step("run", "dist", mi(n))
                else -> step("run", "dist", n.toInt())
            }
            if (pace != null) work.put("target", "pace").put("zone", 0).put("lo", pace.first).put("hi", pace.second)
            else work.put("target", "hr").put("zone", explicitZone ?: 5)
            val rec = Regex("""(\d+(?:\.\d+)?)\s*(m\b|meters?|min\b|minutes?|sec\b|s\b)\s*(?:jog|recovery|rest|easy|walk)""").find(t)?.let { r ->
                val v = r.groupValues[1].toDouble(); val u = r.groupValues[2]
                when {
                    u.startsWith("min") -> step("recover", "time", (v * 60).toInt(), "hr", 1)
                    u.startsWith("s") -> step("recover", "time", v.toInt(), "hr", 1)
                    else -> step("recover", "dist", v.toInt(), "hr", 1)
                }
            } ?: step("recover", "time", 90, "hr", 1)
            val workMeters = if (work.optString("dur") == "dist") work.optInt("value") * times else 0
            val restMiles = total?.let { (it * RunMath.METERS_PER_MILE - workMeters) / RunMath.METERS_PER_MILE }
            val wu = restMiles?.takeIf { it > 0.5 }?.let { it / 2 } ?: 1.0
            return workout(label, notes, easyStep("warmup", wu), repeat(times, work, rec), easyStep("cooldown", wu))
        }

        // Tempo inside a run: "6 miles w/ 3 mi @ threshold"
        if (label == "Tempo Run" || label == "Progression Run") {
            val inner = Regex("""(?:w/|with|incl\.?|including)\s*(\d+(?:\.\d+)?)\s*(?:mi\b|miles?)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val hard = inner ?: total?.let { maxOf(1.0, it - 2) }
            val tempo = step("run", if (hard != null) "dist" else "open", hard?.let { mi(it) } ?: 0)
            if (pace != null) tempo.put("target", "pace").put("lo", pace.first).put("hi", pace.second)
            else tempo.put("target", "hr").put("zone", explicitZone ?: if (label == "Tempo Run") 4 else 3)
            val rest = if (total != null && hard != null) ((total - hard) / 2).takeIf { it > 0.2 } else 1.0
            return workout(label, notes, easyStep("warmup", rest), tempo, easyStep("cooldown", rest))
        }

        // Steady runs.
        val zone = explicitZone ?: when (label) { "Recovery Run" -> 1; "Race" -> 4; else -> 2 }
        val main = easyStep("run", total, zone)
        if (pace != null && label == "Race") main.put("target", "pace").put("zone", 0).put("lo", pace.first).put("hi", pace.second)
        return workout(label + (total?.let { " " + String.format(Locale.US, "%s mi", fmt(it)) } ?: ""), notes, main)
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
    private fun secs(mmss: String) = mmss.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
}
