package io.github.michealsizemore1.top25widget.run

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds a Garmin TCX run file (heart rate, cadence, power, laps, GPS) for sharing. */
object Tcx {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun t(ms: Long) = iso.format(Date(ms))

    fun build(run: JSONObject): String {
        val start = run.optLong("start")
        val samples = run.optJSONArray("samples")
        val track = run.optJSONArray("track")
        val laps = run.optJSONArray("laps")
        // Wall-clock time of a sample: elapsed seconds if recorded (newer runs), else moving seconds.
        fun sampleTime(s: org.json.JSONArray) = start + 1000L * (if (s.length() > 6) s.optInt(6) else s.optInt(0))

        // GPS points by moving second, for matching to samples.
        val gps = HashMap<Int, Pair<Double, Double>>()
        if (track != null) for (i in 0 until track.length()) {
            val p = track.getJSONArray(i); gps[p.optDouble(2).toInt()] = p.optDouble(0) to p.optDouble(1)
        }
        var lastGps: Pair<Double, Double>? = null

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
<Activities><Activity Sport="Running"><Id>${t(start)}</Id>
""")
        // Split the per-second samples into the recorded laps.
        var idx = 0
        val lapCount = laps?.length() ?: 0
        val total = samples?.length() ?: 0
        var lapEndT = 0
        for (li in 0 until maxOf(1, lapCount)) {
            val lap = laps?.optJSONObject(li)
            lapEndT += lap?.optInt("sec") ?: run.optInt("movingSeconds")
            val first = idx
            while (idx < total && (li == lapCount - 1 || samples!!.getJSONArray(idx).optInt(0) <= lapEndT)) idx++
            if (lapCount == 0) idx = total
            val lapStartMs = if (first < total) sampleTime(samples!!.getJSONArray(first)) - 1000 else start
            sb.append("<Lap StartTime=\"${t(lapStartMs)}\">")
            sb.append("<TotalTimeSeconds>${lap?.optInt("sec") ?: run.optInt("movingSeconds")}</TotalTimeSeconds>")
            sb.append("<DistanceMeters>${"%.1f".format(Locale.US, lap?.optDouble("m") ?: run.optDouble("distance"))}</DistanceMeters>")
            sb.append("<Calories>0</Calories>")
            (lap?.optInt("hr") ?: run.optInt("avgHr")).takeIf { it > 0 }?.let { sb.append("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
            sb.append("<Intensity>Active</Intensity><TriggerMethod>${if (lap?.optBoolean("auto") == true) "Distance" else "Manual"}</TriggerMethod><Track>\n")
            for (i in first until idx) {
                val s = samples!!.getJSONArray(i)
                val moving = s.optInt(0)
                gps[moving]?.let { lastGps = it }
                sb.append("<Trackpoint><Time>${t(sampleTime(s))}</Time>")
                lastGps?.let { (la, lo) -> sb.append("<Position><LatitudeDegrees>$la</LatitudeDegrees><LongitudeDegrees>$lo</LongitudeDegrees></Position>") }
                sb.append("<DistanceMeters>${"%.1f".format(Locale.US, s.optInt(1) / 10.0)}</DistanceMeters>")
                s.optInt(2).takeIf { it > 0 }?.let { sb.append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                sb.append("<Extensions><ns3:TPX><ns3:Speed>${"%.2f".format(Locale.US, s.optInt(3) / 100.0)}</ns3:Speed>")
                s.optInt(4).takeIf { it > 0 }?.let { sb.append("<ns3:RunCadence>${it / 2}</ns3:RunCadence>") }   // TCX counts one foot
                s.optInt(5).takeIf { it > 0 }?.let { sb.append("<ns3:Watts>$it</ns3:Watts>") }
                sb.append("</ns3:TPX></Extensions></Trackpoint>\n")
            }
            sb.append("</Track></Lap>\n")
        }
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>Galaxy Watch Run</Name></Creator>")
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
