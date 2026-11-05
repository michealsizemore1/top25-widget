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
        val avgHr: Int, val avgPower: Int, val avgCadence: Int, val auto: Boolean, val name: String = "",
    )

    // ---------------------------------------------------------------- guided workout
    /** The workout chosen for the next run (null = free run). */
    @Volatile var workout: JSONObject? = null
    var workoutSteps: List<JSONObject> = emptyList(); private set
    @Volatile var stepIndex = 0; private set
    @Volatile var workoutDone = false; private set
    private var stepStartSec = 0
    private var stepStartMeters = 0.0
    private var stepLo = 0; private var stepHi = 0
    private var outSeconds = 0
    private var lastNudge = 0L
    /** -1 below target, 0 in range, 1 above, null no target / not checked yet. */
    @Volatile var stepStatus: Int? = null; private set

    val hasWorkout get() = workout != null && workoutSteps.isNotEmpty()
    val currentStep get() = workoutSteps.getOrNull(stepIndex)
    val nextStep get() = workoutSteps.getOrNull(stepIndex + 1)
    val stepSeconds get() = movingSeconds - stepStartSec
    val stepMeters get() = distance - stepStartMeters
    val stepTarget get() = stepLo to stepHi

    fun chooseWorkout(w: JSONObject?) {
        workout = w
        workoutSteps = w?.let { io.github.michealsizemore1.top25widget.Workouts.flatten(it) } ?: emptyList()
        changed()
    }

    private fun beginStep(i: Int) {
        stepIndex = i
        stepStartSec = movingSeconds; stepStartMeters = distance
        outSeconds = 0; stepStatus = null
        currentStep?.let { s ->
            val (lo, hi) = io.github.michealsizemore1.top25widget.Workouts.resolve(s, hrMax, hrRest, criticalPower)
            stepLo = lo; stepHi = hi
        }
    }

    /** Ends the current step (Lap button, or the step's time/distance is reached). */
    fun nextWorkoutStep(auto: Boolean) {
        if (!hasWorkout || workoutDone || state == State.IDLE) return
        lap(auto = auto, silent = true)
        if (stepIndex + 1 >= workoutSteps.size) {
            workoutDone = true; stepStatus = null
            onEvent?.invoke("workout_done")
        } else {
            beginStep(stepIndex + 1)
            onEvent?.invoke("step")
        }
        changed()
    }

    /** Remaining time (s) or distance (m) in the step; null for "until lap" steps. */
    val stepRemaining: Double? get() {
        val s = currentStep ?: return null
        return when (s.optString("dur")) {
            "time" -> (s.optInt("value") - stepSeconds).toDouble().coerceAtLeast(0.0)
            "dist" -> (s.optInt("value") - stepMeters).coerceAtLeast(0.0)
            else -> null
        }
    }

    /** The reading being compared to the target: bpm, watts, or pace in s/mi. */
    fun stepValue(): Int? {
        val s = currentStep ?: return null
        return when (s.optString("target")) {
            "hr" -> heartRate.takeIf { it > 0 }
            "power" -> power3s.takeIf { it > 0 }
            "pace" -> currentSpeed.takeIf { it > 0.5 }?.let { (METERS_PER_MILE / it).roundToInt() }
            else -> null
        }
    }

    private fun checkWorkout() {
        val s = currentStep ?: return
        val done = when (s.optString("dur")) {
            "time" -> stepSeconds >= s.optInt("value")
            "dist" -> stepMeters >= s.optInt("value")
            else -> false
        }
        if (done) { nextWorkoutStep(auto = true); return }
        val target = s.optString("target")
        val v = stepValue()
        // Give heart rate time to catch up after a change of effort.
        val grace = if (target == "hr") 45 else 15
        if (target == "none" || v == null || stepLo <= 0 || stepHi <= 0) { stepStatus = null; return }
        val status = if (v < stepLo) -1 else if (v > stepHi) 1 else 0
        stepStatus = status
        if (stepSeconds < grace) return
        outSeconds = if (status == 0) 0 else outSeconds + 1
        val now = System.currentTimeMillis()
        if (capApplies()) return   // easy step: the heart-rate cap does the reminding (no "speed up" nags)
        if (outSeconds >= 8 && now - lastNudge > 30_000) {
            lastNudge = now
            // For pace, a bigger number means slower — so "above" means speed up.
            val faster = if (target == "pace") status > 0 else status < 0
            onEvent?.invoke(if (faster) "push" else "ease")
        }
    }

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
    // Easy-day heart-rate cap.
    @Volatile var hrCapMode = "easy"          // off | easy | all
    @Volatile var hrCapZone = 2
    /** Top of the cap zone (bpm). */
    val hrCap get() = io.github.michealsizemore1.top25widget.RunMath.hrBounds(hrMax, hrRest).let { b -> if (hrCapZone in 1..4) b[hrCapZone] - 1 else hrMax.toInt() }
    @Volatile var capOver = false; private set
    private var capOverSec = 0
    private var capUnderSec = 0
    private var capLastAlert = 0L

    /** Is the cap on right now? Easy steps of easy workouts (and free runs, if chosen). */
    fun capApplies(): Boolean {
        if (hrCapMode == "off") return false
        if (!hasWorkout || workoutDone) return hrCapMode == "all"
        return easyStep()
    }

    /** Is the current workout step an easy (zone 1–2) one? */
    fun easyStep(): Boolean {
        if (!hasWorkout || workoutDone) return false
        val s = currentStep ?: return false
        val target = s.optString("target")
        if (target == "hr") return s.optInt("zone") in 1..hrCapZone
        if (target != "none") return false
        // No target: only on easy-looking workouts, and not short strides/sprints.
        if (s.optString("dur") == "time" && s.optInt("value") <= 120) return false
        val name = workout?.optString("name").orEmpty().lowercase()
        return listOf("easy", "recovery", "long", "planned run", "aerobic", "base").any { name.contains(it) }
    }

    private fun checkCap() {
        if (!capApplies() || heartRate <= 0 || movingSeconds < 180 || (hasWorkout && !workoutDone && stepSeconds < 45)) {
            capOverSec = 0; capOver = false; return
        }
        val cap = hrCap
        if (heartRate > cap) { capOverSec++; capUnderSec = 0 } else { capUnderSec++; capOverSec = 0 }
        val now = System.currentTimeMillis()
        if (capOverSec >= 10 && now - capLastAlert > 45_000) { capLastAlert = now; capOver = true; onEvent?.invoke("hrcap") }
        if (capOver && capUnderSec >= 8) { capOver = false; onEvent?.invoke("hrcap_ok") }
    }

    @Volatile var criticalPower = 333        // watts
    @Volatile var hrMax = 185.0
    @Volatile var hrRest = 43.5
    @Volatile var strydCalibration = 1.0     // multiplies Stryd distance and speed
    @Volatile var source = Source.STRYD
    @Volatile var autoPause = true
    @Volatile var recordGpsTrack = false   // with Stryd as source, GPS is optional (map only)

    // ---------------------------------------------------------------- live sensor readings
    /** true = chest strap when it's there (wrist as backup); false = wrist sensor only. */
    @Volatile var useStrap = true
    @Volatile var heartRate = 0
    @Volatile var wristLastUpdate = 0L
    @Volatile var strapConnected = false
    @Volatile var strapStatus = "Not paired"
    @Volatile var strapLastUpdate = 0L
    @Volatile var strapContact = true
    @Volatile var strapBattery = -1
    /** True while the chest strap is sending — its reading wins over the wrist. */
    val strapActive get() = useStrap && strapConnected && System.currentTimeMillis() - strapLastUpdate < 5000
    private val rr = ArrayList<Int>()        // beat-to-beat intervals (ms) from the strap, for HRV
    private var strapSeconds = 0

    /** R-R interval from the strap. */
    fun onRr(ms: Int) {
        if (state == State.RUNNING && ms in 250..2000) synchronized(rr) { rr += ms }
    }
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
    /** Where the run started (one quick reading even with GPS off) — for the weather lookup. */
    @Volatile var startLoc: DoubleArray? = null
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
    // Mile alerts inside workouts (steps are the laps there, so miles are tracked separately).
    private var mileNum = 0
    private var mileStartSec = 0
    private var mileHrSum = 0L; private var mileHrN = 0
    /** Last finished mile: number, seconds, average heart rate. */
    @Volatile var lastMile: Triple<Int, Int, Int>? = null; private set
    private fun checkMile() {
        if (heartRate > 0) { mileHrSum += heartRate; mileHrN++ }
        val m = (distance / METERS_PER_MILE).toInt()
        if (m > mileNum) {
            lastMile = Triple(m, movingSeconds - mileStartSec, if (mileHrN > 0) (mileHrSum / mileHrN).toInt() else 0)
            mileNum = m; mileStartSec = movingSeconds; mileHrSum = 0; mileHrN = 0
            if (easyStep()) onEvent?.invoke("mile")
        }
    }
    // Elevation from the barometer: smoothed altitude, counting climbs/descents of more than 2 m (ignores pressure noise).
    private var altSmooth = Double.NaN
    private var altRef = Double.NaN
    @Volatile var elevGain = 0.0; private set
    @Volatile var elevLoss = 0.0; private set
    fun onPressure(hPa: Float) {
        val alt = android.hardware.SensorManager.getAltitude(android.hardware.SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa).toDouble()
        altSmooth = if (altSmooth.isNaN()) alt else altSmooth + (alt - altSmooth) * 0.15
        if (state != State.RUNNING) { altRef = altSmooth; return }
        if (altRef.isNaN()) { altRef = altSmooth; return }
        val d = altSmooth - altRef
        if (d > 2.0) { elevGain += d; altRef = altSmooth } else if (d < -2.0) { elevLoss -= d; altRef = altSmooth }
    }
    /** True while Stryd isn't sending and GPS stands in. */
    @Volatile var usingGpsFallback = false; private set
    private var gpsFallbackSeconds = 0
    private val strydLog = ArrayList<String>()
    fun logStryd(what: String) {
        synchronized(strydLog) { if (strydLog.size < 200) strydLog += (if (startMillis > 0) "${elapsedSeconds}s " else "") + what }
    }
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
        workoutDone = false
        if (hasWorkout) beginStep(0)
        state = State.RUNNING
        onEvent?.invoke("start")
        changed()
    }

    fun pause() { if (state == State.RUNNING || state == State.AUTO_PAUSED) { state = State.PAUSED; onEvent?.invoke("pause"); changed() } }

    fun resume() {
        if (state == State.PAUSED || state == State.AUTO_PAUSED) {
            lastStrydTotal = strydTotalDistance   // don't count distance covered while paused
            slowSeconds = 0
            state = State.RUNNING; onEvent?.invoke("resume"); changed()
        }
    }

    fun lap(auto: Boolean = false, silent: Boolean = false) {
        if (state == State.IDLE) return
        val sec = movingSeconds - lapStartSec
        if (sec <= 0) return
        val slice = samples.subList(lapSampleStart, samples.size)
        fun avg(i: Int) = slice.map { it[i] }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().roundToInt() }
        val name = if (hasWorkout && !workoutDone) currentStep?.let { s ->
            io.github.michealsizemore1.top25widget.Workouts.kindName(s.optString("kind")) +
                (if (s.has("rep")) " ${s.optInt("rep")}/${s.optInt("reps")}" else "")
        }.orEmpty() else ""
        laps += Lap(laps.size + 1, sec, distance - lapStartMeters, avg(2), avg(5), avg(4), auto, name)
        lapStartSec = movingSeconds; lapStartMeters = distance; lapSampleStart = samples.size
        lapPowerSum = 0; lapPowerCount = 0; lapHrSum = 0; lapHrCount = 0
        if (!silent) onEvent?.invoke(if (auto) "lap_auto" else "lap")
        changed()
    }

    /** Stop and return the finished run as JSON (also saved to the watch). */
    fun finish(ctx: Context): JSONObject {
        if (movingSeconds - lapStartSec > 5) lap(auto = true)
        val json = toJson(System.currentTimeMillis())
        try { io.github.michealsizemore1.top25widget.Decoupling.compute(json)?.let { json.put("decoupling", it.toJson()) } } catch (e: Exception) { }
        try {
            io.github.michealsizemore1.top25widget.Insights.zone2(json, hrMax, hrRest)?.let { json.put("z2", io.github.michealsizemore1.top25widget.Insights.z2Json(it)) }
            val bests = io.github.michealsizemore1.top25widget.Insights.bestEfforts(json)
            if (bests.isNotEmpty()) json.put("bests", io.github.michealsizemore1.top25widget.Insights.bestsJson(bests))
            // New personal bests vs. every earlier run (records kept in prefs; built from history the first time).
            val p = ctx.getSharedPreferences("run", 0)
            val rec = try { org.json.JSONObject(p.getString("pbRecords", null) ?: "") } catch (e: Exception) {
                org.json.JSONObject().also { o -> io.github.michealsizemore1.top25widget.Insights.records(RunStore.list(ctx).mapNotNull { RunStore.read(it) })
                    .forEach { (k, v) -> o.put(k, org.json.JSONObject().put("sec", v.sec).put("start", v.start)) } }
            }
            val newB = org.json.JSONObject()
            bests.forEach { (k, v) ->
                val old = rec.optJSONObject(k)?.optInt("sec") ?: Int.MAX_VALUE
                if (v < old) { newB.put(k, v); rec.put(k, org.json.JSONObject().put("sec", v).put("start", startMillis)) }
            }
            if (newB.length() > 0) json.put("newBests", newB)
            p.edit().putString("pbRecords", rec.toString()).apply()
        } catch (e: Exception) { }
        RunStore.save(ctx, json)
        RunStore.clearCurrent(ctx)
        RunTileService.refresh(ctx)
        state = State.IDLE
        chooseWorkout(null)   // next run starts as a free run unless a workout is picked
        nav = null
        changed()
        return json
    }

    fun discard(ctx: Context) { RunStore.clearCurrent(ctx); reset(); changed() }

    private fun reset() {
        state = State.IDLE; startMillis = 0; movingSeconds = 0; distance = 0.0
        startLoc = null
        elevGain = 0.0; elevLoss = 0.0; altRef = Double.NaN
        mileNum = 0; mileStartSec = 0; mileHrSum = 0; mileHrN = 0; lastMile = null
        LiveTrack.log.clear()
        usingGpsFallback = false; gpsFallbackSeconds = 0; synchronized(strydLog) { strydLog.clear() }
        samples.clear(); track.clear(); speedWindow.clear(); powerWindow.clear(); laps.clear()
        power10Window.clear(); power30Window.clear(); np4Sum = 0.0; np4Count = 0
        zoneSeconds.fill(0); lapPowerSum = 0; lapPowerCount = 0; maxPower = 0
        synchronized(rr) { rr.clear() }; strapSeconds = 0
        hrZoneSeconds.fill(0); lapHrSum = 0; lapHrCount = 0; strideSum = 0.0; strideCount = 0
        lapStartSec = 0; lapStartMeters = 0.0; lapSampleStart = 0; slowSeconds = 0
        hrSum = 0; hrCount = 0; maxHr = 0; powerSum = 0; powerCount = 0; cadSum = 0; cadCount = 0
        gpsPendingMeters = 0.0
    }

    // ---------------------------------------------------------------- sensor input

    /** GPS reports each new fix with the distance moved since the previous good fix. */
    // ---------------------------------------------------------------- trail navigation
    /** The trail being followed (null = no trail). */
    @Volatile var nav: io.github.michealsizemore1.top25widget.TrailNav? = null
    @Volatile var gpsBearing = -1f

    fun chooseTrail(t: io.github.michealsizemore1.top25widget.Trail?, outAndBack: Boolean) {
        nav = t?.let { io.github.michealsizemore1.top25widget.TrailNav(it, outAndBack) }
        changed()
    }

    fun onGps(lat: Double, lon: Double, speed: Double, metersMoved: Double) {
        gpsLat = lat; gpsLon = lon; gpsSpeed = speed; gpsFix = true; gpsLastUpdate = System.currentTimeMillis()
        if (startLoc == null && state == State.RUNNING) startLoc = doubleArrayOf(lat, lon)
        if (state == State.RUNNING || state == State.AUTO_PAUSED) {
            if (state == State.RUNNING) gpsPendingMeters += metersMoved
            // GPS is only on when something needs it (GPS pace, map track, a trail, LiveTrack, or standing in
            // for Stryd) — whenever it is, keep the route so the run has a map.
            track += doubleArrayOf(lat, lon, movingSeconds.toDouble(), elapsedSeconds.toDouble())
            // Following a trail: off-route, turns, turnaround, finish.
            if (state == State.RUNNING) nav?.update(lat, lon)?.forEach { onEvent?.invoke(it) }
        }
    }

    // ---------------------------------------------------------------- once a second

    fun tick() {
        val now = System.currentTimeMillis()
        // Old Stryd readings don't count (connected or not).
        if (now - strydLastUpdate > 5000) { strydSpeed = 0.0; strydPower = 0; strydCadence = 0 }
        // Stryd lost during a run: use GPS for pace and distance until it's back.
        val strydLost = source == Source.STRYD && isActive && startMillis > 0 && now - strydLastUpdate > 10_000
        if (strydLost != usingGpsFallback) {
            usingGpsFallback = strydLost
            if (state == State.RUNNING || state == State.AUTO_PAUSED) onEvent?.invoke(if (strydLost) "stryd_lost" else "stryd_back")
            logStryd(if (strydLost) "lost, using GPS" else "back")
        }
        if (now - gpsLastUpdate > 5000) gpsSpeed = 0.0
        if (now - strapLastUpdate > 10_000 && now - wristLastUpdate > 10_000) heartRate = 0

        val speedNow = if (source == Source.STRYD && !usingGpsFallback) strydSpeed * strydCalibration else gpsSpeed

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
            if (usingGpsFallback) gpsFallbackSeconds++
            val step = if (source == Source.STRYD && !usingGpsFallback) {
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
            if (strapActive) strapSeconds++
            if (hr > 0) {
                hrSum += hr; hrCount++; if (hr > maxHr) maxHr = hr
                lapHrSum += hr; lapHrCount++
                hrZoneIndex(hr).takeIf { it >= 0 }?.let { hrZoneSeconds[it]++ }
            }
            if (strydStride > 0.2 && speedNow > 0.9) { strideSum += strydStride * strydCalibration; strideCount++ }
            if (strydPower > 0) { powerSum += strydPower; powerCount++ }
            val cad = if (source == Source.STRYD || strydConnected) strydCadence else 0
            if (cad > 0) { cadSum += cad; cadCount++ }
            samples += intArrayOf(movingSeconds, (distance * 10).toInt(), hr, (speedNow * 100).toInt(), cad, strydPower, elapsedSeconds)

            if (state == State.RUNNING) checkCap()
            if (hasWorkout && !workoutDone) { checkWorkout(); checkMile() }
            // Auto-lap every mile (during a workout, each step is a lap instead).
            else if (distance - lapStartMeters >= METERS_PER_MILE) lap(auto = true)
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
        startLoc?.let { put("startLoc", JSONArray(listOf(it[0], it[1]))) }
        if (gpsFallbackSeconds > 0) put("gpsFallbackSeconds", gpsFallbackSeconds)
        if (!altSmooth.isNaN()) { put("elevGain", Math.round(elevGain * 10) / 10.0); put("elevLoss", Math.round(elevLoss * 10) / 10.0) }   // meters, barometer
        synchronized(strydLog) { if (strydLog.isNotEmpty()) put("strydLog", JSONArray(strydLog)) }
        synchronized(LiveTrack.log) { if (LiveTrack.log.isNotEmpty()) put("liveLog", JSONArray(LiveTrack.log.toList())) }
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
        nav?.let { n -> put("trail", JSONObject().put("name", n.trail.name).put("outAndBack", n.outAndBack)
            .put("length", n.total).put("completed", n.finished)) }
        workout?.let { w ->
            put("workout", JSONObject().put("name", w.optString("name")).put("notes", w.optString("notes"))
                .put("steps", w.optJSONArray("steps")).put("completed", workoutDone)
                .put("stepsDone", if (workoutDone) workoutSteps.size else stepIndex))
        }
        put("hrSource", if (movingSeconds > 0 && strapSeconds * 2 >= movingSeconds) "strap" else "wrist")
        synchronized(rr) { if (rr.isNotEmpty()) put("rr", JSONArray(rr.toList())) }
        put("hrZoneSeconds", JSONArray(hrZoneSeconds.toList()))
        if (avgStride > 0) put("avgStride", avgStride)
        put("laps", JSONArray().apply {
            laps.forEach { l ->
                put(JSONObject().apply {
                    put("n", l.number); put("sec", l.seconds); put("m", l.meters)
                    put("hr", l.avgHr); put("pw", l.avgPower); put("cad", l.avgCadence); put("auto", l.auto)
                    if (l.name.isNotEmpty()) put("name", l.name)
                })
            }
        })
        // Compact per-second samples: [moving s, meters*10, hr, speed*100, cadence, power, elapsed s]
        // GPS track points: [lat, lon, moving s, elapsed s]
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
    fun deleteStart(ctx: Context, start: Long) { File(dir(ctx), "$start.json").delete() }
}
