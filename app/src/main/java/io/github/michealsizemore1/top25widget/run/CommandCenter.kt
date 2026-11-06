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

/** Text summary of a run, for the Command Center notes: short sections, one fact per line. */
object Summary {
    private val dec = io.github.michealsizemore1.top25widget.Decoupling

    /** "Z2 53:41 (89%)" lines for zones you actually spent time in. */
    fun zoneLines(secs: JSONArray?): List<String> {
        if (secs == null) return emptyList()
        val total = (0 until secs.length()).sumOf { secs.optInt(it) }
        if (total <= 0) return emptyList()
        return (0 until minOf(5, secs.length())).filter { secs.optInt(it) > 0 }.map { z ->
            "Z${z + 1} · ${RunMath.clock(secs.optInt(z))} · ${Math.round(secs.optInt(z) * 100.0 / total)}%"
        }
    }

    fun notes(ctx: Context, run: JSONObject): String {
        val sections = mutableListOf<List<String>>()
        fun section(title: String, lines: List<String>) { if (lines.isNotEmpty()) sections += listOf(title) + lines }

        run.optJSONObject("trail")?.let { t ->
            section("TRAIL", listOf(t.optString("name") + (if (t.optBoolean("outAndBack")) " (out & back)" else "") +
                (if (t.optBoolean("completed")) " ✓ completed" else "")))
        }

        run.optJSONObject("readiness")?.let { r ->
            val parts = r.optJSONArray("parts")
            section("READINESS (BEFORE RUN)", listOfNotNull(
                "${r.optInt("score")} · ${r.optString("label")}",
                parts?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.joinToString(" · ") { "${it.optString("name")} ${it.optString("value")}" } }?.ifBlank { null },
            ))
        }

        if (run.optInt("avgHr") > 0) section("HEART RATE (" + (if (run.optString("hrSource") == "strap") "chest strap" else "wrist") + ")",
            listOf("Avg ${run.optInt("avgHr")} · Max ${run.optInt("maxHr")} bpm") + zoneLines(run.optJSONArray("hrZoneSeconds")))

        if (run.optInt("np") > 0) section("POWER" + (if (run.optInt("cp") > 0) " (CP ${run.optInt("cp")})" else ""), buildList {
            add("Avg ${run.optInt("avgPower")} W · NP ${run.optInt("np")} W")
            if (run.optInt("cp") > 0) add("IF ${"%.2f".format(Locale.US, run.optDouble("intensity"))} · Stress ${run.optInt("stress")}")
            addAll(zoneLines(run.optJSONArray("zoneSeconds")))
        })

        dec.of(run)?.let { d ->
            d.main?.let { m ->
                section("AEROBIC DECOUPLING", listOfNotNull(
                    "${dec.text(m)} · ${dec.rating(m)}" + (if (!d.steady) " (uneven effort)" else ""),
                    listOfNotNull(d.pwHr?.let { "Pw:HR ${dec.text(it)}" }, d.paHr?.let { "Pa:HR ${dec.text(it)}" }).joinToString(" · ").ifBlank { null },
                    "HR ${d.hr1} → ${d.hr2} over ${d.minutes} min",
                ))
            }
        }

        io.github.michealsizemore1.top25widget.Insights.zone2(run, RunSettings.hrMax(ctx), RunSettings.hrRest(ctx))?.let { z ->
            section("ZONE 2", listOfNotNull("Pace " + RunMath.paceFor(z.secPerMile, RunMath.METERS_PER_MILE) + " /mi" +
                (if (z.power > 0) " · ${z.power} W" else "") + " · ${z.minutes} min"))
        }
        run {
            val earlier = PhoneRuns.list(ctx).mapNotNull { PhoneRuns.read(it) }.filter { it.optLong("start") < run.optLong("start") }
            val nb = io.github.michealsizemore1.top25widget.Insights.newBests(run, earlier)
            if (nb.isNotEmpty()) section("NEW PERSONAL BESTS", nb.map { (k, v) -> io.github.michealsizemore1.top25widget.Insights.pbName(k) + " · " + io.github.michealsizemore1.top25widget.Insights.clock(v) })
            val ld = io.github.michealsizemore1.top25widget.Insights.load(earlier + run, run.optLong("start") + 1000)
            section("TRAINING LOAD", listOf(ld.status + " · ${ld.acute} stress last 7 days" + (ld.ratio?.let { String.format(Locale.US, " · ratio %.2f", it) } ?: "")))
        }

        val miles = io.github.michealsizemore1.top25widget.Splits.miles(run)
        if (miles.size > 1) section("MILE SPLITS", miles.map { sp ->
            "Mile ${sp.n}" + (if (sp.meters < RunMath.METERS_PER_MILE * 0.95) String.format(Locale.US, " (%.2f)", sp.meters / RunMath.METERS_PER_MILE) else "") +
                " · " + RunMath.paceFor(sp.sec, sp.meters) + (sp.hr.takeIf { it > 0 }?.let { " @ $it bpm" } ?: "") + (sp.power.takeIf { it > 0 }?.let { " · $it W" } ?: "")
        })

        val laps = run.optJSONArray("laps")
        if (laps != null && laps.length() > 1) section("LAPS", (0 until laps.length()).map { i ->
            val l = laps.getJSONObject(i)
            "${i + 1}. " + String.format(Locale.US, "%.2f mi", l.optDouble("m") / RunMath.METERS_PER_MILE) + " · " +
                RunMath.paceFor(l.optInt("sec"), l.optDouble("m")) + (l.optInt("hr").takeIf { it > 0 }?.let { " @ $it bpm" } ?: "")
        })

        val w = run.optJSONObject("weather")
        section("CONDITIONS", listOfNotNull(
            w?.takeIf { it.has("windMph") }?.let { "Wind ${Math.round(it.optDouble("windMph"))} mph" +
                (if (it.has("windDir")) " from " + RunWeather.compass(it.optInt("windDir")) else "") +
                (if (it.has("gustMph")) ", gusts ${it.optInt("gustMph")}" else "") },
            w?.takeIf { it.has("airPct") && run.optJSONObject("stryd")?.has("airPowerPct") != true }?.let { "Air power ${it.optDouble("airPct")}% (est.)" },
            run.optDouble("elevGain").takeIf { !it.isNaN() }?.let { "Climb ${Math.round(it * 3.28084)} ft" },
        ))

        val s = run.optJSONObject("stryd")
        section("FORM", listOfNotNull(
            run.optDouble("avgStride").takeIf { !it.isNaN() && it > 0 }?.let { "Avg stride ${"%.2f".format(Locale.US, it)} m" },
            s?.optDouble("groundTime")?.takeIf { !it.isNaN() }?.let { "Ground contact ${it.toInt()} ms" },
            s?.optDouble("oscillation")?.takeIf { !it.isNaN() }?.let { "Vertical oscillation %.1f cm".format(Locale.US, it) },
            s?.optDouble("legSpring")?.takeIf { !it.isNaN() }?.let { "Leg spring %.1f kN/m".format(Locale.US, it) },
            s?.optDouble("formPower")?.takeIf { !it.isNaN() }?.let { "Form power ${it.toInt()} W" },
            s?.optDouble("airPowerPct")?.takeIf { !it.isNaN() }?.let { "Air power %.1f%% (Stryd)".format(Locale.US, it) },
            s?.optDouble("rss")?.takeIf { !it.isNaN() }?.let { "Stryd RSS ${it.toInt()}" },
        ))

        // (This last line also tells the Command Center the notes were written by the app, so they can be refreshed.)
        val footer = "Recorded on Galaxy Watch (" + (if (run.optString("source") == "gps") "GPS" else "Stryd") + " pace/distance)"
        return (sections.map { it.joinToString("\n") } + footer).joinToString("\n\n")
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
            // Weather at the run's place and time; Stryd's reading is used if the lookup failed.
            run.optJSONObject("weather")?.let { w ->
                if (w.has("tempF")) put("temp", w.optLong("tempF").toString())
                if (w.has("humidity")) put("humidity", w.optLong("humidity"))
                if (w.has("aqi")) put("aqi", w.optLong("aqi"))
                if (w.has("airPct")) put("airPower", w.optDouble("airPct"))   // estimate; Stryd's measured value replaces it below
            }
            // Elevation gain: the watch's barometer (or the route), else Stryd's.
            run.optDouble("elevGain").takeIf { !it.isNaN() }?.let { put("elevGain", Math.round(it * 3.28084)) }
            run.optJSONObject("stryd")?.let { s ->
                if (!has("elevGain")) s.optDouble("elevGain").takeIf { !it.isNaN() }?.let { put("elevGain", Math.round(it * 3.28084)) }
                if (!has("temp")) s.optDouble("temp").takeIf { !it.isNaN() }?.let { put("temp", Math.round(it * 9 / 5 + 32).toString()) }
                if (!has("humidity")) s.optDouble("humidity").takeIf { !it.isNaN() }?.let { put("humidity", Math.round(it)) }
                s.optDouble("airPowerPct").takeIf { !it.isNaN() }?.let { put("airPower", it) }   // measured beats estimated
                s.optDouble("rss").takeIf { !it.isNaN() }?.let { put("rss", it.toInt()) }
            }
        }
    }

    /** Opens the Command Center with these runs; it adds them to Activities and syncs. */
    fun send(ctx: Context, starts: List<Long>) {
        // Fill in weather/AQI first for any run that doesn't have it yet (quick lookup), then open.
        Thread {
            starts.forEach { s -> PhoneRuns.read(ctx, s)?.let { if (!RunWeather.has(it)) try { RunWeather.fill(ctx, s) } catch (e: Exception) { } } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { open(ctx, starts) }
        }.start()
    }

    private fun open(ctx: Context, starts: List<Long>) {
        val arr = JSONArray()
        starts.forEach { s -> PhoneRuns.read(ctx, s)?.let { arr.put(activity(ctx, it)) } }
        if (arr.length() == 0) return
        val b64 = Base64.encodeToString(arr.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val url = RunSettings.ccUrl(ctx).substringBefore('#') + "#watchruns=" + b64
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        starts.forEach { PhoneRuns.setSentToCommandCenter(ctx, it) }
        PhoneStatus.push(ctx)
    }
}
