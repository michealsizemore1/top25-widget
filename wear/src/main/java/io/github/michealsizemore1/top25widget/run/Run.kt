package io.github.michealsizemore1.top25widget.run

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The run in progress. Sensors (Stryd, GPS, heart rate) write the latest readings
 * here; RunService calls [tick] once a second to advance time, distance, laps,
 * and auto-pause. Screens read from here and listen for changes.
 */
object Run {
    const val METERS_PER_MILE = 1609.344

    enum class State { IDLE, RUNNING, PAUSED, AUTO_PAUSED }
    enum class Source { STRYD, GPS }

    data class Lap(
        val number: Int, val seconds: Int, val meters: Double,
        val avgHr: Int, val avgPower: Int, val avgCadence: Int, val auto: Boolean,
    )

    /** Zone colors, Z1–Z5, matching the Command Center badges. */
    val ZONE_COLORS = intArrayOf(0xFF4FA3E0.toInt(), 0xFF46C46E.toInt(), 0xFFF2C94C.toInt(), 0xFFFF8A3D.toInt(), 0xFFE5484D.toInt())

    /** Power zones as a share of Critical Power — the same breakpoints as the Command Center. */
    data class Zone(val number: Int, val name: String, val lo: Double, val color: Int)
    val ZONES = listOf(
        Zone(1, "Recovery", 0.0, ZONE_COLORS[0]),
        Zone(2, "Aerobic / endurance", 0.55, ZONE_COLORS[1]),
        Zone(3, "Tempo", 0.75, ZONE_COLORS[2]),
        Zone(4, "Threshold", 0.90, ZONE_COLORS[3]),
        Zone(5, "VO2 max", 1.05, ZONE_COLORS[4]),
    )

    /** Power zone index 0–4, or -1 with no Critical Power set / no power. */
    fun zoneIndex(watts: Int, cp: Int = criticalPower): Int {
        if (cp <= 0 || watts <= 0) return -1
        val f = watts.toDouble() / cp
        return ZONES.indexOfLast { f >= it.lo }.coerceAtLeast(0)
    }

    /** Heart-rate zones (Karvonen, 50/60/70/80/90% of heart-rate reserve) — same as the Command Center. */
    val HR_ZONE_NAMES = listOf("Recovery", "Aerobic base", "Aerobic threshold", "Anaerobic", "Max effort")
    fun hrBounds(): IntArray {
        val reserve = (hrMax - hrRest).coerceAtLeast(1.0)
        return doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9).map { Math.round(hrRest + reserve * it).toInt() }.toIntArray()
    }
    /** Heart-rate zone index 0–4, or -1 below Zone 1 / no reading. */
    fun hrZoneIndex(bpm: Int): Int {
        if (bpm <= 0) return -1
        return hrBounds().indexOfLast { bpm >= it }
    }
    /** Percent of heart-rate reserve. */
    fun hrrPercent(bpm: Int) = if (bpm <= 0) 0 else ((bpm - hrRest) * 100 / (hrMax - hrRest).coerceAtLeast(1.0)).roundToInt()

    // ---------------------------------------------------------------- settings
    @Volatile var criticalPower = 333        // watts
    @Volatile var hrMax = 185.0
    @Volatile var hrRest = 43.5
    @Volatile var strydCalibration = 1.0     // multiplies Stryd distance and speed
    @Volatile var source = Source.STRYD
    @Volatile var autoPause = true
    @Volatile var recordGpsTrack = false   // with Stryd as source, GPS is optional (map only)

    // ---------------------------------------------------------------- live sensor readings
    @Volatile var heartRate = 0
    @Volatile var strydConnected = false
    @Volatile var strydStatus = "Not paired"
    @Volatile var strydSpeed = 0.0          // m/s
    @Volatile var strydCadence = 0          // steps per minute
    @Volatile var strydPower = 0            // watts
    @Volatile var strydTotalDistance: Double? = null   // meters, as the pod reports it
    @Volatile var strydLastUpdate = 0L
    @Volatile var strydBattery = -1         // percent, -1 = unknown
    @Volatile var strydStride = 0.0         // meters, if the pod sends it
    @Volatile var gpsFix = false
    @Volatile var gpsStatus = "Off"
    @Volatile var gpsSpeed = 0.0            // m/s
    @Volatile var gpsLat = 0.0
    @Volatile var gpsLon = 0.0
    @Volatile var gpsLastUpdate = 0L
    private var gpsPendingMeters = 0.0      // distance from GPS since the last tick

    // ---------------------------------------------------------------- run totals
    @Volatile var state = State.IDLE; private set
    var startMillis = 0L; private set
    var movingSeconds = 0; private set
    var distance = 0.0; private set          // meters
    private var lastStrydTotal: Double? = null
    private var slowSeconds = 0

    private val samples = ArrayList<IntArray>()  // per second: t, meters*10, hr, speed*100, cadence, power
    private val track = ArrayList<DoubleArray>() // lat, lon, t
    private val speedWindow = ArrayDeque<Double>()
    private val powerWindow = ArrayDeque<Int>()      // last 3 s
    private val power10Window = ArrayDeque<Int>()    // last 10 s
    private val power30Window = ArrayDeque<Int>()    // last 30 s, for normalized power
    private var np4Sum = 0.0; private var np4Count = 0
    val zoneSeconds = IntArray(5)
    val hrZoneSeconds = IntArray(5)
    private var lapHrSum = 0L; private var lapHrCount = 0
    private var strideSum = 0.0; private var strideCount = 0
    private var lapPowerSum = 0L; private var lapPowerCount = 0
    var maxPower = 0; private set
    val laps = ArrayList<Lap>()
    private var lapStartSec = 0
    private var lapStartMeters = 0.0
    private var lapSampleStart = 0

    // Summed values for averages (moving time only)
    private var hrSum = 0L; private var hrCount = 0; var maxHr = 0; private set
    private var powerSum = 0L; private var powerCount = 0
    private var cadSum = 0L; private var cadCount = 0

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Called by the service so the screens can buzz on laps and auto-pause. */
    var onEvent: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- controls

    fun start() {
        reset()
        startMillis = System.currentTimeMillis()
        lastStrydTotal = strydTotalDistance
        state = State.RUNNING
        changed()
    }

    fun pause() { if (state == State.RUNNING || state == State.AUTO_PAUSED) { state = State.PAUSED; changed() } }

    fun resume() {
        if (state == State.PAUSED || state == State.AUTO_PAUSED) {
            lastStrydTotal = strydTotalDistance   // don't count distance covered while paused
            slowSeconds = 0
            state = State.RUNNING; changed()
        }
    }

    fun lap(auto: Boolean = false) {
        if (state == State.IDLE) return
        val sec = movingSeconds - lapStartSec
        if (sec <= 0) return
        val slice = samples.subList(lapSampleStart, samples.size)
        fun avg(i: Int) = slice.map { it[i] }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().roundToInt() }
        laps += Lap(laps.size + 1, sec, distance - lapStartMeters, avg(2), avg(5), avg(4), auto)
        lapStartSec = movingSeconds; lapStartMeters = distance; lapSampleStart = samples.size
        lapPowerSum = 0; lapPowerCount = 0; lapHrSum = 0; lapHrCount = 0
        onEvent?.invoke(if (auto) "lap_auto" else "lap")
        changed()
    }

    /** Stop and return the finished run as JSON (also saved to the watch). */
    fun finish(ctx: Context): JSONObject {
        if (movingSeconds - lapStartSec > 5) lap(auto = true)
        val json = toJson(System.currentTimeMillis())
        RunStore.save(ctx, json)
        RunStore.clearCurrent(ctx)
        state = State.IDLE
        changed()
        return json
    }

    fun discard(ctx: Context) { RunStore.clearCurrent(ctx); reset(); changed() }

    private fun reset() {
        state = State.IDLE; startMillis = 0; movingSeconds = 0; distance = 0.0
        samples.clear(); track.clear(); speedWindow.clear(); powerWindow.clear(); laps.clear()
        power10Window.clear(); power30Window.clear(); np4Sum = 0.0; np4Count = 0
        zoneSeconds.fill(0); lapPowerSum = 0; lapPowerCount = 0; maxPower = 0
        hrZoneSeconds.fill(0); lapHrSum = 0; lapHrCount = 0; strideSum = 0.0; strideCount = 0
        lapStartSec = 0; lapStartMeters = 0.0; lapSampleStart = 0; slowSeconds = 0
        hrSum = 0; hrCount = 0; maxHr = 0; powerSum = 0; powerCount = 0; cadSum = 0; cadCount = 0
        gpsPendingMeters = 0.0
    }

    // ---------------------------------------------------------------- sensor input

    /** GPS reports each new fix with the distance moved since the previous good fix. */
    fun onGps(lat: Double, lon: Double, speed: Double, metersMoved: Double) {
        gpsLat = lat; gpsLon = lon; gpsSpeed = speed; gpsFix = true; gpsLastUpdate = System.currentTimeMillis()
        if (state == State.RUNNING || state == State.AUTO_PAUSED) {
            if (state == State.RUNNING) gpsPendingMeters += metersMoved
            if (recordGpsTrack || source == Source.GPS) track += doubleArrayOf(lat, lon, movingSeconds.toDouble())
        }
    }

    // ---------------------------------------------------------------- once a second

    fun tick() {
        val now = System.currentTimeMillis()
        if (strydConnected && now - strydLastUpdate > 5000) strydSpeed = 0.0
        if (now - gpsLastUpdate > 5000) gpsSpeed = 0.0

        val speedNow = if (source == Source.STRYD) strydSpeed * strydCalibration else gpsSpeed

        if (state == State.RUNNING || state == State.AUTO_PAUSED) {
            // Auto-pause: stopped for 4 seconds pauses; moving again resumes.
            if (autoPause) {
                if (state == State.RUNNING) {
                    slowSeconds = if (speedNow < 0.45) slowSeconds + 1 else 0
                    if (slowSeconds >= 4) { state = State.AUTO_PAUSED; onEvent?.invoke("autopause") }
                } else if (speedNow > 0.9) {
                    lastStrydTotal = strydTotalDistance
                    slowSeconds = 0; state = State.RUNNING; onEvent?.invoke("autoresume")
                }
            }
        }

        if (state == State.RUNNING) {
            movingSeconds++
            // Distance from the chosen source.
            val step = if (source == Source.STRYD) {
                val total = strydTotalDistance
                val last = lastStrydTotal
                val d = if (total != null && last != null && total >= last && total - last < 50) total - last
                        else strydSpeed          // no pod total (or it reset): speed × 1 second
                lastStrydTotal = total
                d * strydCalibration
            } else gpsPendingMeters
            gpsPendingMeters = 0.0
            distance += step

            speedWindow.addLast(speedNow); while (speedWindow.size > 5) speedWindow.removeFirst()
            val pw = strydPower
            powerWindow.addLast(pw); while (powerWindow.size > 3) powerWindow.removeFirst()
            power10Window.addLast(pw); while (power10Window.size > 10) power10Window.removeFirst()
            power30Window.addLast(pw); while (power30Window.size > 30) power30Window.removeFirst()
            if (power30Window.size == 30) {
                val a = power30Window.average(); np4Sum += a * a * a * a; np4Count++
            }
            if (pw > 0) {
                lapPowerSum += pw; lapPowerCount++
                if (pw > maxPower) maxPower = pw
                zoneIndex(pw).takeIf { it >= 0 }?.let { zoneSeconds[it]++ }
            }

            val hr = heartRate
            if (hr > 0) {
                hrSum += hr; hrCount++; if (hr > maxHr) maxHr = hr
                lapHrSum += hr; lapHrCount++
                hrZoneIndex(hr).takeIf { it >= 0 }?.let { hrZoneSeconds[it]++ }
            }
            if (strydStride > 0.2 && speedNow > 0.9) { strideSum += strydStride * strydCalibration; strideCount++ }
            if (strydPower > 0) { powerSum += strydPower; powerCount++ }
            val cad = if (source == Source.STRYD || strydConnected) strydCadence else 0
            if (cad > 0) { cadSum += cad; cadCount++ }
            samples += intArrayOf(movingSeconds, (distance * 10).toInt(), hr, (speedNow * 100).toInt(), cad, strydPower)

            // Auto-lap every mile.
            if (distance - lapStartMeters >= METERS_PER_MILE) lap(auto = true)
        } else if (state == State.PAUSED || state == State.AUTO_PAUSED) {
            lastStrydTotal = strydTotalDistance
            gpsPendingMeters = 0.0
            speedWindow.clear()
        }
        changed()
    }

    // ---------------------------------------------------------------- readouts

    val isActive get() = state != State.IDLE
    val currentSpeed get() = if (speedWindow.isEmpty()) 0.0 else speedWindow.average()
    val power3s get() = if (powerWindow.isEmpty()) strydPower else powerWindow.average().roundToInt()
    val avgHr get() = if (hrCount == 0) 0 else (hrSum / hrCount).toInt()
    val avgPower get() = if (powerCount == 0) 0 else (powerSum / powerCount).toInt()
    val avgCadence get() = if (cadCount == 0) 0 else (cadSum / cadCount).toInt()
    val avgSpeed get() = if (movingSeconds == 0) 0.0 else distance / movingSeconds
    val lapHr get() = if (lapHrCount == 0) 0 else (lapHrSum / lapHrCount).toInt()
    val avgStride get() = if (strideCount == 0) 0.0 else strideSum / strideCount
    val power10s get() = if (power10Window.isEmpty()) strydPower else power10Window.average().roundToInt()
    val lapPower get() = if (lapPowerCount == 0) 0 else (lapPowerSum / lapPowerCount).toInt()
    /** Normalized power: 30 s rolling average, raised to the 4th power, averaged, 4th root. */
    val normalizedPower get() = if (np4Count == 0) avgPower else Math.pow(np4Sum / np4Count, 0.25).roundToInt()
    /** Intensity = normalized power ÷ Critical Power. */
    val intensity get() = if (criticalPower <= 0 || normalizedPower <= 0) 0.0 else normalizedPower.toDouble() / criticalPower
    /** Estimated stress: hours × intensity² × 100 (an hour at CP ≈ 100). */
    val stress get() = (movingSeconds / 3600.0 * intensity * intensity * 100).roundToInt()
    val lapSeconds get() = movingSeconds - lapStartSec
    val lapMeters get() = distance - lapStartMeters
    val lapNumber get() = laps.size + 1
    val elapsedSeconds get() = if (startMillis == 0L) 0 else ((System.currentTimeMillis() - startMillis) / 1000).toInt()

    // ---------------------------------------------------------------- saving

    fun toJson(endMillis: Long = System.currentTimeMillis()) = JSONObject().apply {
        put("start", startMillis); put("end", endMillis)
        put("source", source.name.lowercase())
        put("movingSeconds", movingSeconds); put("elapsedSeconds", ((endMillis - startMillis) / 1000).toInt())
        put("distance", distance)
        put("avgHr", avgHr); put("maxHr", maxHr); put("avgPower", avgPower); put("avgCadence", avgCadence)
        put("maxPower", maxPower); put("np", normalizedPower)
        if (criticalPower > 0) {
            put("cp", criticalPower); put("intensity", intensity); put("stress", stress)
            put("zoneSeconds", JSONArray(zoneSeconds.toList()))
        }
        if (source == Source.STRYD) put("calibration", strydCalibration)
        put("hrMax", hrMax); put("hrRest", hrRest)
        put("hrZoneSeconds", JSONArray(hrZoneSeconds.toList()))
        if (avgStride > 0) put("avgStride", avgStride)
        put("laps", JSONArray().apply {
            laps.forEach { l ->
                put(JSONObject().apply {
                    put("n", l.number); put("sec", l.seconds); put("m", l.meters)
                    put("hr", l.avgHr); put("pw", l.avgPower); put("cad", l.avgCadence); put("auto", l.auto)
                })
            }
        })
        // Compact per-second samples: [t, meters*10, hr, speed*100, cadence, power]
        put("samples", JSONArray().apply { samples.forEach { s -> put(JSONArray(s.toList())) } })
        if (track.isNotEmpty()) put("track", JSONArray().apply { track.forEach { p -> put(JSONArray(p.toList())) } })
    }

    // ---------------------------------------------------------------- formatting

    fun miles(m: Double) = String.format(Locale.US, "%.2f", m / METERS_PER_MILE)

    fun clock(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Pace per mile from meters/second, e.g. "8:32". */
    fun pace(speed: Double): String {
        if (speed < 0.5) return "--:--"
        val sec = (METERS_PER_MILE / speed).roundToInt()
        if (sec >= 60 * 60) return "--:--"
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    fun paceFor(seconds: Int, meters: Double) = if (meters < 10) "--:--" else pace(meters / seconds)
}

/** Finished runs live as JSON files in the watch's private storage. */
object RunStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "runs").apply { mkdirs() }
    private fun currentFile(ctx: Context) = File(ctx.filesDir, "run_current.json")

    fun save(ctx: Context, json: JSONObject) {
        File(dir(ctx), "${json.optLong("start")}.json").writeText(json.toString())
    }

    /** Autosave of the run in progress, so a crash or restart doesn't lose it. */
    fun saveCurrent(ctx: Context, json: JSONObject) = currentFile(ctx).writeText(json.toString())
    fun clearCurrent(ctx: Context) { currentFile(ctx).delete() }

    /** If the watch restarted mid-run, keep what was recorded as a finished run. */
    fun recoverInterrupted(ctx: Context): Boolean {
        val f = currentFile(ctx)
        if (!f.exists()) return false
        return try {
            val json = JSONObject(f.readText())
            if (json.optInt("movingSeconds") > 30) { json.put("recovered", true); save(ctx, json) }
            f.delete(); true
        } catch (e: Exception) { f.delete(); false }
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }.orEmpty()

    fun read(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (e: Exception) { null }

    fun delete(f: File) { f.delete() }
}
