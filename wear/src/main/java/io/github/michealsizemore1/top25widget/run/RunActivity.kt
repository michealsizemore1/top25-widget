package io.github.michealsizemore1.top25widget.run

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * The run screens: a start screen (sensor status, Stryd or GPS), swipeable live pages
 * during the run, and a summary when you finish.
 */
class RunActivity : Activity() {

    private object C {
        const val ACCENT = 0xFFD4E635.toInt()
        const val MUTED = 0xFF9AA3A0.toInt()
        const val DIM = 0xFF6B7370.toInt()
        const val CARD = 0xFF1C2421.toInt()
        const val PAUSE = 0xFFFFB547.toInt()
        const val STOP = 0xFFE5484D.toInt()
        const val OK = 0xFF46C46E.toInt()
    }

    private enum class Screen { START, LIVE, SUMMARY }

    private lateinit var root: FrameLayout
    private var screen = Screen.START
    private var page = 1                 // 0 controls, 1 main, 2 heart, 3 power, 4 lap, 5 averages, 6 stress
    private var confirmingFinish = false
    private var summary: JSONObject? = null
    private val pageCount = 7
    /** While a number editor is open, the bezel adjusts it. */
    private var editorRotary: ((Int) -> Unit)? = null
    private var lastRotary = 0L
    private val prefs by lazy { getSharedPreferences("run", 0) }

    private val onRunChanged: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        Run.source = if (prefs.getString("source", "stryd") == "gps") Run.Source.GPS else Run.Source.STRYD
        Run.autoPause = prefs.getBoolean("autoPause", true)
        Run.recordGpsTrack = prefs.getBoolean("track", false)
        Run.criticalPower = prefs.getInt("cp", 333)
        Run.hrMax = prefs.getFloat("hrMax", 185f).toDouble()
        Run.hrRest = prefs.getFloat("hrRest", 43.5f).toDouble()
        Run.strydCalibration = prefs.getFloat("cal", 1f).toDouble()

        if (!RunService.running) RunStore.recoverInterrupted(this)
        screen = if (Run.isActive) Screen.LIVE else Screen.START
        requestNeeded()
    }

    override fun onResume() {
        super.onResume()
        Run.listen(onRunChanged)
        // Coming back from History (which stopped the idle sensors): turn them on again.
        if (!RunService.running && screen != Screen.SUMMARY) RunService.start(this)
        render()
    }
    override fun onPause() { super.onPause(); Run.unlisten(onRunChanged) }

    override fun onStop() {
        super.onStop()
        // Leaving the start screen without running: turn the sensors back off.
        if (!Run.isActive && screen != Screen.LIVE && !isChangingConfigurations) RunService.stop(this)
    }

    // ---------------------------------------------------------------- permissions

    private fun neededPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.filter { !RunService.has(this, it) }
    }

    private fun requestNeeded() {
        val missing = neededPermissions()
        if (missing.isEmpty()) RunService.start(this) else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        RunService.start(this)
        render()
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        when (screen) {
            Screen.START -> if (Run.isActive) { screen = Screen.LIVE; renderLive() } else if (editorRotary == null) renderStart()
            Screen.LIVE -> renderLive()
            Screen.SUMMARY -> {}   // static; drawn once in finish()
        }
    }

    // ---------------------------------------------------------------- start screen

    private var startBuilt = false
    private val startValues = HashMap<String, TextView>()
    private var trackRow: View? = null
    private var calRow: View? = null

    private fun renderStart() {
        if (!startBuilt) buildStart()
        fun set(key: String, text: String, color: Int = Color.WHITE) {
            startValues[key]?.apply { this.text = text; setTextColor(color) }
        }
        set("source", if (Run.source == Run.Source.STRYD) "Stryd ▸" else "GPS ▸", C.ACCENT)
        set("stryd", Run.strydStatus +
                (if (Run.strydConnected && Run.strydBattery >= 0) " · ${Run.strydBattery}%" else "") +
                (if (Run.strydConnected && Run.strydPower > 0) " · ${Run.strydPower} W" else ""),
            if (Run.strydConnected) (if (Run.strydBattery in 0..15) C.PAUSE else C.OK) else C.MUTED)
        set("cp", "${Run.criticalPower} W ▸")
        set("hrmax", "${fmtHr(Run.hrMax)} bpm ▸")
        set("hrrest", "${fmtHr(Run.hrRest)} bpm ▸")
        set("cal", String.format(java.util.Locale.US, "%.3f ▸", Run.strydCalibration))
        val gpsNeeded = Run.source == Run.Source.GPS || Run.recordGpsTrack
        set("gps", if (gpsNeeded) Run.gpsStatus else "Off (not needed)", if (Run.gpsFix && gpsNeeded) C.OK else C.MUTED)
        val hrText = when {
            Run.strapActive -> "${Run.strapStatus} · ${Run.heartRate} bpm" +
                (if (Run.strapBattery >= 0) " · ${Run.strapBattery}%" else "")
            Run.strapConnected && !Run.strapContact -> "Strap: wet the pads / no skin contact"
            Run.heartRate > 0 -> "Wrist · ${Run.heartRate} bpm" +
                (if (Run.strapStatus.startsWith("Searching") || Run.strapStatus.startsWith("Connecting") ||
                     Run.strapStatus.startsWith("Reconnecting")) " · strap: ${Run.strapStatus}" else "")
            else -> "Waiting… · strap: ${Run.strapStatus}"
        }
        set("hr", hrText, if (Run.strapActive) C.OK else if (Run.heartRate > 0) C.PAUSE else C.MUTED)
        set("autopause", if (Run.autoPause) "On" else "Off")
        set("track", if (Run.recordGpsTrack) "On" else "Off")
        trackRow?.visibility = if (Run.source == Run.Source.STRYD) View.VISIBLE else View.GONE
        calRow?.visibility = if (Run.source == Run.Source.STRYD) View.VISIBLE else View.GONE

        val ready = if (Run.source == Run.Source.STRYD) Run.strydConnected else Run.gpsFix
        startValues["startBtn"]?.apply {
            text = if (ready) "START" else "START ANYWAY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (ready) 20f else 14f)
        }
    }

    private fun buildStart() {
        startBuilt = true
        startValues.clear()
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 8, w / 7, w / 8, w / 5) }
        col.addView(label("RUN", C.ACCENT, 15f, true).apply { letterSpacing = 0.15f })

        fun row(key: String, title: String, onTap: (() -> Unit)?) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(C.CARD, 14)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) }
            addView(label(title, C.MUTED, 11f))
            addView(label("", Color.WHITE, 14f, true).also { startValues[key] = it })
            if (onTap != null) setOnClickListener { onTap(); if (editorRotary == null) renderStart() }
        }

        col.addView(row("source", "Pace & distance") {
            Run.source = if (Run.source == Run.Source.STRYD) Run.Source.GPS else Run.Source.STRYD
            prefs.edit().putString("source", Run.source.name.lowercase()).apply()
        })
        col.addView(row("stryd", "Stryd footpod (tap to re-pair)") {
            if (!Run.strydConnected) RunService.instance?.pairStryd()
        })
        col.addView(row("hrmax", "Max heart rate (HR zones)") {
            editNumber("MAX HEART RATE", Run.hrMax, 1.0, 140.0, 230.0, { "${it.toInt()} bpm" }) {
                Run.hrMax = it; prefs.edit().putFloat("hrMax", it.toFloat()).apply()
            }
        })
        col.addView(row("hrrest", "Resting heart rate (HR zones)") {
            editNumber("RESTING HEART RATE", Run.hrRest, 0.5, 30.0, 90.0, { "${fmtHr(it)} bpm" }) {
                Run.hrRest = it; prefs.edit().putFloat("hrRest", it.toFloat()).apply()
            }
        })
        col.addView(row("cp", "Critical Power (power zones)") {
            editNumber("CRITICAL POWER", Run.criticalPower.toDouble(), 1.0, 100.0, 600.0, { "${it.toInt()} W" }) {
                Run.criticalPower = it.toInt(); prefs.edit().putInt("cp", Run.criticalPower).apply()
            }
        })
        col.addView(row("cal", "Stryd calibration") {
            editNumber("STRYD CALIBRATION", Run.strydCalibration, 0.005, 0.900, 1.100,
                { String.format(java.util.Locale.US, "%.3f", it) }) {
                Run.strydCalibration = it; prefs.edit().putFloat("cal", it.toFloat()).apply()
            }
        }.also { calRow = it })
        col.addView(row("gps", "GPS", null))
        col.addView(row("hr", "Heart rate (tap to pair chest strap)") {
            if (!Run.strapActive) RunService.instance?.pairStrap()
        })
        col.addView(row("autopause", "Auto-pause") {
            Run.autoPause = !Run.autoPause; prefs.edit().putBoolean("autoPause", Run.autoPause).apply()
        })
        col.addView(row("track", "GPS map track (uses battery)") {
            Run.recordGpsTrack = !Run.recordGpsTrack; prefs.edit().putBoolean("track", Run.recordGpsTrack).apply()
        }.also { trackRow = it })

        col.addView(label("START", Color.BLACK, 20f, true).apply {
            startValues["startBtn"] = this
            background = rounded(C.ACCENT, 30)
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
            setOnClickListener {
                if (!RunService.running) RunService.start(this@RunActivity)
                Run.start()
                screen = Screen.LIVE; page = 1; liveBuilt = false
                render()
            }
        })
        col.addView(label("History ›", C.MUTED, 13f, true).apply {
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { startActivity(Intent(this@RunActivity, RunHistoryActivity::class.java)) }
        })

        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    /** Full-screen number picker: bezel or − / + to change, Save to keep. */
    private fun editNumber(
        title: String, initial: Double, step: Double, min: Double, max: Double,
        format: (Double) -> String, onSave: (Double) -> Unit,
    ) {
        var value = initial.coerceIn(min, max)
        val w = resources.displayMetrics.widthPixels
        val col = column().apply {
            gravity = Gravity.CENTER
            setPadding(w / 8, w / 8, w / 8, w / 8)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        col.addView(label(title, C.ACCENT, 12f, true).apply { letterSpacing = 0.1f })
        val valueView = label(format(value), Color.WHITE, 34f, true)
        fun change(steps: Int) {
            value = (Math.round((value + steps * step) / step) * step).coerceIn(min, max)
            valueView.text = format(value)
        }
        fun small(text: String, steps: Int) = label(text, Color.WHITE, 24f, true).apply {
            background = rounded(C.CARD, 22)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { change(steps) }
            setOnLongClickListener { change(steps * 10); true }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(small("−", -1), LinearLayout.LayoutParams(dp(44), WRAP_CONTENT))
            addView(valueView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(small("+", 1), LinearLayout.LayoutParams(dp(44), WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        col.addView(label("Turn bezel · hold ± for big steps", C.DIM, 10f).apply { setPadding(0, dp(4), 0, 0) })
        fun close() { editorRotary = null; startBuilt = false; renderStart() }
        col.addView(label("SAVE", Color.BLACK, 16f, true).apply {
            background = rounded(C.ACCENT, 24)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { onSave(value); close() }
        })
        col.addView(label("Cancel", C.MUTED, 13f, true).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { close() }
        })
        editorRotary = { change(it) }
        startBuilt = false
        root.removeAllViews()
        root.addView(col)
        root.isFocusable = true; root.isFocusableInTouchMode = true; root.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back (or swipe) from a number editor returns to the start screen without saving.
        if (editorRotary != null) { editorRotary = null; startBuilt = false; renderStart(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- live pages

    private var liveBuilt = false
    private lateinit var liveTitle: TextView
    private lateinit var fieldA: Pair<TextView, TextView>
    private lateinit var fieldB: Pair<TextView, TextView>
    private lateinit var fieldC: Pair<TextView, TextView>
    private lateinit var fieldD: Pair<TextView, TextView>
    private lateinit var dataBox: LinearLayout
    private lateinit var zoneBar: LinearLayout
    private lateinit var controlsBox: LinearLayout
    private lateinit var dots: TextView
    private lateinit var gestures: GestureDetector

    private fun field(valueSize: Float): Pair<LinearLayout, Pair<TextView, TextView>> {
        val v = label("--", Color.WHITE, valueSize, true)
        val l = label("", C.MUTED, 11f)
        val box = column().apply { addView(v); addView(l) }
        return box to (v to l)
    }

    private fun buildLive() {
        liveBuilt = true
        startBuilt = false
        val w = resources.displayMetrics.widthPixels
        root.removeAllViews()

        val page = column().apply {
            gravity = Gravity.CENTER
            setPadding(w / 10, w / 9, w / 10, w / 10)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        liveTitle = label("", C.ACCENT, 12f, true).apply { letterSpacing = 0.1f }
        page.addView(liveTitle)

        dataBox = column()
        val (a, fa) = field(40f); fieldA = fa
        val (b, fb) = field(26f); fieldB = fb
        val (c, fc) = field(26f); fieldC = fc
        val (d, fd) = field(24f); fieldD = fd
        dataBox.addView(a)
        dataBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
        })
        dataBox.addView(d.apply { setPadding(0, dp(4), 0, 0) })
        zoneBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            Run.ZONE_COLORS.forEach { color ->
                addView(View(context).apply { background = rounded(color, 3) },
                    LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        dataBox.addView(zoneBar)
        page.addView(dataBox)

        controlsBox = column().apply { visibility = View.GONE }
        page.addView(controlsBox)

        dots = label("", C.DIM, 12f).apply { setPadding(0, dp(6), 0, 0) }
        page.addView(dots)
        root.addView(page)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 400) { turnPage(if (vx < 0) 1 else -1); return true }
                return false
            }
        })
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (screen == Screen.LIVE && liveBuilt && gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Turning the bezel also flips pages. */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        val ed = editorRotary
        if (ed != null && ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            ed(if (-ev.getAxisValue(MotionEvent.AXIS_SCROLL) > 0) 1 else -1)
            return true
        }
        if (screen == Screen.LIVE && ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val now = System.currentTimeMillis()
            if (now - lastRotary > 350) {
                lastRotary = now
                turnPage(if (-ev.getAxisValue(MotionEvent.AXIS_SCROLL) > 0) 1 else -1)
            }
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun turnPage(delta: Int) {
        page = (page + delta).coerceIn(0, pageCount - 1)
        confirmingFinish = false
        renderLive()
    }

    private fun show(f: Pair<TextView, TextView>, value: String, label: String, color: Int = Color.WHITE) {
        f.first.text = value; f.first.setTextColor(color); f.second.text = label
    }

    private fun renderLive() {
        if (!liveBuilt) buildLive()
        val paused = Run.state == Run.State.PAUSED || Run.state == Run.State.AUTO_PAUSED
        root.setBackgroundColor(if (paused) 0xFF2A1F05.toInt() else Color.BLACK)
        dots.text = (0 until pageCount).joinToString(" ") { if (it == page) "●" else "○" }

        if (page == 0) { renderControls(paused); return }
        dataBox.visibility = View.VISIBLE
        controlsBox.visibility = View.GONE

        val stateText = when (Run.state) {
            Run.State.PAUSED -> "PAUSED"
            Run.State.AUTO_PAUSED -> "AUTO-PAUSED"
            else -> null
        }
        val zi = Run.zoneIndex(Run.power3s)
        val hz = Run.hrZoneIndex(Run.heartRate)
        val hrColor = if (hz >= 0) Run.ZONE_COLORS[hz] else 0xFFFF6B6B.toInt()
        val hrZoneText = if (hz >= 0) "Z${hz + 1} · ${Run.HR_ZONE_NAMES[hz]}" else if (Run.heartRate > 0) "below Z1" else "heart rate"
        val pageName = when (page) {
            1 -> "RUN"
            2 -> if (hz >= 0) "HR Z${hz + 1} · ${Run.HR_ZONE_NAMES[hz].uppercase()}" else "HEART RATE"
            3 -> if (zi >= 0) "Z${zi + 1} · ${Run.ZONES[zi].name.uppercase()}" else "POWER"
            4 -> "LAP ${Run.lapNumber}"
            5 -> "AVERAGES"
            else -> "STRESS"
        }
        liveTitle.text = stateText ?: pageName
        liveTitle.setTextColor(when {
            stateText != null -> C.PAUSE
            page == 2 && hz >= 0 -> hrColor
            page == 3 && zi >= 0 -> Run.ZONES[zi].color
            else -> C.ACCENT
        })
        // Zone bar: heart-rate zone on the main and heart pages, power zone on the power page.
        val barZone = when (page) { 1, 2 -> hz; 3 -> zi; else -> -2 }
        zoneBar.visibility = if (barZone == -2) View.GONE else View.VISIBLE
        for (i in 0 until zoneBar.childCount) zoneBar.getChildAt(i).alpha = if (i == barZone) 1f else 0.22f

        val hr = if (Run.heartRate > 0) "${Run.heartRate}" else "--"
        when (page) {
            1 -> {
                show(fieldA, Run.clock(Run.movingSeconds), "time")
                show(fieldB, Run.miles(Run.distance), "miles")
                show(fieldC, Run.pace(Run.currentSpeed), "pace /mi", C.ACCENT)
                show(fieldD, hr, hrZoneText, hrColor)
            }
            2 -> {
                show(fieldA, hr, "bpm", hrColor)
                show(fieldB, if (Run.lapHr > 0) "${Run.lapHr}" else "--", "lap HR")
                show(fieldC, if (Run.avgHr > 0) "${Run.avgHr}" else "--", "avg HR")
                val b = Run.hrBounds()
                show(fieldD, if (Run.heartRate > 0) "${Run.hrrPercent(Run.heartRate)}%" else "--",
                    if (hz in 0..3) "HRR · Z${hz + 1} ${b[hz]}–${b[hz + 1] - 1}" else if (hz == 4) "HRR · Z5 ${b[4]}+" else "of HR reserve",
                    hrColor)
            }
            3 -> {
                show(fieldA, if (Run.strydConnected) "${Run.power3s}" else "--", "watts (3s)",
                    if (zi >= 0) Run.ZONES[zi].color else C.ACCENT)
                show(fieldB, if (Run.strydConnected) "${Run.power10s}" else "--", "10s W")
                show(fieldC, if (Run.lapPower > 0) "${Run.lapPower}" else "--", "lap W")
                show(fieldD,
                    if (Run.power3s > 0) "${(Run.power3s * 100.0 / Run.criticalPower).toInt()}%" else "--",
                    "of CP (${Run.criticalPower} W)", if (zi >= 0) Run.ZONES[zi].color else Color.WHITE)
            }
            4 -> {
                show(fieldA, Run.clock(Run.lapSeconds), "lap time")
                show(fieldB, Run.miles(Run.lapMeters), "lap mi")
                show(fieldC, Run.paceFor(Run.lapSeconds, Run.lapMeters), "lap pace", C.ACCENT)
                show(fieldD, if (Run.lapHr > 0) "${Run.lapHr}" else "--", "lap HR",
                    Run.hrZoneIndex(Run.lapHr).let { if (it >= 0) Run.ZONE_COLORS[it] else Color.WHITE })
            }
            6 -> {
                show(fieldA, "${Run.stress}", "stress (est.)", C.ACCENT)
                show(fieldB, if (Run.normalizedPower > 0) "${Run.normalizedPower}" else "--", "norm W")
                show(fieldC, if (Run.intensity > 0) String.format(java.util.Locale.US, "%.2f", Run.intensity) else "--", "intensity")
                show(fieldD, (if (Run.strydCadence > 0) "${Run.strydCadence}" else "--") +
                    (if (Run.strydStride > 0.2) String.format(java.util.Locale.US, " · %.2fm", Run.strydStride * Run.strydCalibration) else ""),
                    if (Run.strydStride > 0.2) "cadence · stride" else "cadence")
            }
            else -> {
                show(fieldA, Run.pace(Run.avgSpeed), "avg pace /mi", C.ACCENT)
                show(fieldB, if (Run.avgHr > 0) "${Run.avgHr}" else "--", "avg HR")
                show(fieldC, if (Run.avgPower > 0) "${Run.avgPower}" else "--", "avg W")
                show(fieldD, if (Run.avgCadence > 0) "${Run.avgCadence}" else "--", "avg cadence")
            }
        }
    }

    private fun renderControls(paused: Boolean) {
        dataBox.visibility = View.GONE
        controlsBox.visibility = View.VISIBLE
        liveTitle.text = if (confirmingFinish) "FINISH RUN?" else Run.clock(Run.movingSeconds) + " · " + Run.miles(Run.distance) + " mi"
        liveTitle.setTextColor(if (paused) C.PAUSE else C.ACCENT)
        if (controlsBox.childCount > 0 && controlsBox.tag == "$confirmingFinish$paused") return
        controlsBox.tag = "$confirmingFinish$paused"
        controlsBox.removeAllViews()

        fun button(text: String, color: Int, textColor: Int = Color.BLACK, action: () -> Unit) =
            label(text, textColor, 17f, true).apply {
                background = rounded(color, 26)
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) }
                setOnClickListener { action(); renderLive() }
            }

        if (confirmingFinish) {
            controlsBox.addView(button("SAVE RUN", C.OK) { finishRun() })
            controlsBox.addView(button("Discard", C.STOP, Color.WHITE) {
                Run.discard(this); RunService.stop(this); finish()
            })
            controlsBox.addView(button("Back", C.CARD, Color.WHITE) { confirmingFinish = false })
        } else {
            controlsBox.addView(if (paused) button("RESUME", C.ACCENT) { Run.resume() }
                                else button("PAUSE", C.PAUSE) { Run.pause() })
            controlsBox.addView(button("LAP", C.CARD, Color.WHITE) { Run.lap() })
            controlsBox.addView(button("FINISH", C.STOP, Color.WHITE) { Run.pause(); confirmingFinish = true })
        }
    }

    // ---------------------------------------------------------------- summary

    private fun finishRun() {
        val json = Run.finish(this)
        RunService.stop(this)
        summary = json
        screen = Screen.SUMMARY
        liveBuilt = false
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)
        root.addView(RunViews.summary(this, json, onDone = { finish() }))
    }

    // ---------------------------------------------------------------- helpers

    private fun fmtHr(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else String.format(java.util.Locale.US, "%.1f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
    private fun label(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}
