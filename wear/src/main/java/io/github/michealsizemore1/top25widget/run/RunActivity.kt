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

    /** True when this file was installed on the wrong kind of device (a message is shown instead). */
    private var wrongDevice = false

    private object C {
        const val ACCENT = 0xFFD4E635.toInt()
        const val MUTED = 0xFF9AA3A0.toInt()
        const val DIM = 0xFF6B7370.toInt()
        const val CARD = 0xFF1C2421.toInt()
        const val PAUSE = 0xFFFFB547.toInt()
        const val STOP = 0xFFE5484D.toInt()
        const val OK = 0xFF46C46E.toInt()
        const val BG = 0xFF121A17.toInt()      // same background as the phone app
    }

    /** Start area: phone-style home, or the settings list. */
    private var showSettings = false

    private enum class Screen { START, LIVE, SUMMARY }

    private lateinit var root: FrameLayout
    private var screen = Screen.START
    private var page = 1                 // 0 controls, 1 main, 2 heart, 3 power, 4 lap, 5 averages, 6 stress
    private var confirmingFinish = false
    private var summary: JSONObject? = null
    private val pageCount = 7
    /** While a number editor is open, the bezel adjusts it. */
    private var editorRotary: ((Int) -> Unit)? = null
    /** True while the workout picker covers the start screen. */
    private var overlayOpen = false
    /** Live pages in order: 0 controls, 7 workout step (only with a workout), 1 main, 2 heart, 3 power, 4 lap, 5 averages, 6 stress. */
    /** Live pages: 0 controls, 7 workout step (with a workout), then your data screens (100 + index). */
    private fun pageOrder() = listOf(0) + (if (Run.hasWorkout) listOf(7) else emptyList()) + (0 until screens.length()).map { 100 + it }
    private var builtScreen = -1
    /** Data screens set up on the phone (Run settings → Watch data screens). */
    private var screens: org.json.JSONArray = io.github.michealsizemore1.top25widget.RunFields.defaults()
    private fun loadScreens() {
        screens = try { org.json.JSONArray(prefs.getString("screens", null) ?: "") } catch (e: Exception) { null }
            ?.takeIf { it.length() > 0 } ?: io.github.michealsizemore1.top25widget.RunFields.defaults()
        builtScreen = -1
    }
    private var lastRotary = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("run", 0) }

    private val onRunChanged: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (io.github.michealsizemore1.top25widget.WrongDevice.check(this, isWatchApp = true)) { wrongDevice = true; return }
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(root)

        Run.source = if (prefs.getString("source", "stryd") == "gps") Run.Source.GPS else Run.Source.STRYD
        Run.autoPause = prefs.getBoolean("autoPause", true)
        Run.recordGpsTrack = prefs.getBoolean("track", false)
        Run.useStrap = prefs.getBoolean("useStrap", true)
        loadScreens()
        Run.criticalPower = prefs.getInt("cp", 333)
        Run.hrMax = prefs.getFloat("hrMax", 185f).toDouble()
        Run.hrRest = prefs.getFloat("hrRest", 43.5f).toDouble()
        Run.strydCalibration = prefs.getFloat("cal", 1f).toDouble()

        if (!RunService.running) RunStore.recoverInterrupted(this)
        // Load today's planned workout once a day (you can switch to a free run on the start screen).
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (!Run.isActive && Run.workout == null && prefs.getString("autoPicked", "") != today) {
            WatchWorkouts.today(this)?.let { Run.chooseWorkout(it) }
            prefs.edit().putString("autoPicked", today).apply()
        }
        RunSync.pushAll(this)   // send any runs the phone doesn't have yet
        RunSync.reportVersion(this)
        // Catch up on anything the phone sent while this app wasn't listening, then redraw the home screen.
        RunSync.pullLatest(this) {
            if (!wrongDevice && screen == Screen.START && !overlayOpen && editorRotary == null) {
                if (!Run.isActive && Run.workout == null) WatchWorkouts.today(this)?.let { Run.chooseWorkout(it) }
                startBuilt = false; render()
            }
        }
        screen = if (Run.isActive) Screen.LIVE else Screen.START
        requestNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (wrongDevice) return
        loadScreens()
        Run.listen(onRunChanged)
        // Coming back from History (which stopped the idle sensors): turn them on again.
        if (!RunService.running && screen != Screen.SUMMARY) RunService.start(this)
        render()
    }
    override fun onPause() { super.onPause(); if (!wrongDevice) Run.unlisten(onRunChanged) }

    /** True while the Running screen is on the display (not behind another app or the watch face). */
    private var wasVisible = false
    override fun onStart() { super.onStart(); handler.postDelayed({ wasVisible = true }, 600) }

    override fun onStop() {
        super.onStop()
        wasVisible = false
        handler.removeCallbacksAndMessages(null)
        if (wrongDevice) return
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
            Screen.START -> if (Run.isActive) { screen = Screen.LIVE; renderLive() } else if (editorRotary == null && !overlayOpen) renderStart()
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
        set("screensRow", "${screens.length()} screens ▸", C.ACCENT)
        set("quickBtn", if (prefs.getBoolean("bottomButton", true)) "On" else "Off")
        set("lastKey", "Last button the app saw: " + (prefs.getString("lastKey", null) ?: "none yet"), C.DIM)
        set("source", if (Run.source == Run.Source.STRYD) "Stryd ▸" else "GPS ▸", C.ACCENT)
        set("workout", (Run.workout?.optString("name") ?: "Free run") + " ›", if (Run.workout != null) Color.WHITE else C.ACCENT)
        startValues["workoutOutline"]?.apply {
            val w = Run.workout
            text = if (w == null) "" else io.github.michealsizemore1.top25widget.Workouts.outline(w, Run.hrMax, Run.hrRest, Run.criticalPower)
                .take(6).joinToString("\n")
            visibility = if (w == null) View.GONE else View.VISIBLE
        }
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
        set("hrmode", if (Run.useStrap) "Chest strap + wrist backup ▸" else "Wrist only ▸", C.ACCENT)
        val hrText = when {
            !Run.useStrap -> if (Run.heartRate > 0) "Wrist · ${Run.heartRate} bpm" else "Waiting for wrist…"
            Run.strapActive -> "${Run.strapStatus} · ${Run.heartRate} bpm" +
                (if (Run.strapBattery >= 0) " · ${Run.strapBattery}%" else "")
            Run.strapConnected && !Run.strapContact -> "Strap: wet the pads / no skin contact"
            Run.heartRate > 0 -> "Wrist · ${Run.heartRate} bpm" +
                (if (Run.strapStatus.startsWith("Searching") || Run.strapStatus.startsWith("Connecting") ||
                     Run.strapStatus.startsWith("Reconnecting")) " · strap: ${Run.strapStatus}" else "")
            else -> "Waiting… · strap: ${Run.strapStatus}"
        }
        set("hr", hrText, if (Run.strapActive || (!Run.useStrap && Run.heartRate > 0)) C.OK else if (Run.heartRate > 0) C.PAUSE else C.MUTED)
        set("autopause", if (Run.autoPause) "On" else "Off")
        set("track", if (Run.recordGpsTrack) "On" else "Off")
        trackRow?.visibility = if (Run.source == Run.Source.STRYD) View.VISIBLE else View.GONE
        calRow?.visibility = if (Run.source == Run.Source.STRYD) View.VISIBLE else View.GONE

        val ready = if (Run.source == Run.Source.STRYD) Run.strydConnected else Run.gpsFix
        startValues["startBtn"]?.apply {
            text = if (!ready) "START ANYWAY" else if (Run.workout != null) "START WORKOUT" else "START"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (ready) 20f else 14f)
        }
    }

    private fun buildStart() {
        startBuilt = true
        startValues.clear()
        root.setBackgroundColor(C.BG)
        if (showSettings) buildSettings() else buildHome()
    }

    /** Miles and runs since the start of this week, from the runs saved on the watch. */
    private fun weekTotals(): Pair<Double, Int> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }
        val from = cal.timeInMillis
        var meters = 0.0; var n = 0
        RunStore.list(this).forEach { f ->
            if ((f.nameWithoutExtension.toLongOrNull() ?: 0L) < from) return@forEach
            // Only the totals near the top of the file are needed — avoid parsing every sample.
            val head = f.bufferedReader().use { r -> CharArray(400).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
            Regex("\"distance\":([0-9.]+)").find(head)?.groupValues?.get(1)?.toDoubleOrNull()?.let { meters += it; n++ }
        }
        return meters to n
    }

    /** Home, laid out like the phone's Running screen. */
    private fun buildHome() {
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 9, w / 8, w / 9, w / 5) }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(C.CARD, 16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        col.addView(label("Running", Color.WHITE, 20f, true))

        // This week.
        val (meters, runs) = weekTotals()
        col.addView(card().apply {
            addView(LinearLayout(this@RunActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(column().apply {
                    addView(label(Run.miles(meters), C.ACCENT, 20f, true)); addView(label("mi this week", C.MUTED, 10f))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(column().apply {
                    addView(label("$runs", Color.WHITE, 20f, true)); addView(label("runs", C.MUTED, 10f))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        })

        // Same buttons as the phone. They run on the phone (its browser, internet, and logins).
        val ps = getSharedPreferences("phonestatus", 0)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
            fun pill(title: String, status: String, color: Int, path: String, okMsg: String) = column().apply {
                gravity = Gravity.CENTER
                background = rounded(color, 14)
                setPadding(dp(2), dp(6), dp(2), dp(6))
                addView(label(title, Color.WHITE, 11f, true))
                addView(label(status, 0xDDFFFFFF.toInt(), 9f))
                setOnClickListener {
                    RunSync.tellPhone(this@RunActivity, path) { ok ->
                        android.widget.Toast.makeText(this@RunActivity, if (ok) okMsg else "Phone not connected", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val cc = ps.getInt("ccUnsent", 0)
            val lp = { last: Boolean -> LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (!last) marginEnd = dp(4) } }
            addView(pill("Command Center", if (ps.contains("at")) (if (cc == 0) "✓ sent" else "Send $cc") else "—", 0xFF7B61FF.toInt(), "/cmd/cc",
                "Tap the notification on your phone"), lp(false))
            addView(pill("Stryd", if (ps.getBoolean("stryd", false)) "✓" else "Connect", 0xFF50555E.toInt(), "/cmd/stryd",
                "Phone is checking Stryd"), lp(true))
        })

        // Today's workout (tap to change).
        col.addView(card().apply {
            addView(label("TODAY", C.MUTED, 10f, true))
            addView(label("", Color.WHITE, 15f, true).also { startValues["workout"] = it })
            addView(label("", C.MUTED, 10f).apply { startValues["workoutOutline"] = this; visibility = View.GONE })
            setOnClickListener { showWorkoutPicker() }
        })

        col.addView(label("START", Color.BLACK, 20f, true).apply {
            startValues["startBtn"] = this
            background = rounded(C.ACCENT, 30)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { startRun() }
        })

        // Sensors at a glance.
        col.addView(card().apply {
            addView(label("Stryd", C.MUTED, 10f))
            addView(label("", Color.WHITE, 12f, true).also { startValues["stryd"] = it })
            addView(label("Heart rate", C.MUTED, 10f).apply { setPadding(0, dp(4), 0, 0) })
            addView(label("", Color.WHITE, 12f, true).also { startValues["hr"] = it })
            addView(label("GPS", C.MUTED, 10f).apply { setPadding(0, dp(4), 0, 0) })
            addView(label("", Color.WHITE, 12f, true).also { startValues["gps"] = it })
            setOnClickListener {
                if (!Run.strydConnected) RunService.instance?.pairStryd()
                if (Run.useStrap && !Run.strapActive) RunService.instance?.pairStrap()
            }
        })

        fun navButton(text: String, color: Int, textColor: Int, onClick: () -> Unit) = label(text, textColor, 14f, true).apply {
            background = rounded(color, 22)
            setPadding(0, dp(9), 0, dp(9))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }
        col.addView(navButton("Workouts & Plan", C.ACCENT, Color.BLACK) { showWorkoutPicker() })
        col.addView(navButton("History", C.CARD, Color.WHITE) { startActivity(Intent(this, RunHistoryActivity::class.java)) })
        col.addView(navButton("Settings", C.CARD, Color.WHITE) { showSettings = true; startBuilt = false; renderStart() })

        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    private fun startRun() {
        if (!RunService.running) RunService.start(this)
        Run.start()
        screen = Screen.LIVE; page = 1; liveBuilt = false   // with a workout, page 1 is its step screen
        render()
    }

    /** All the run settings (the phone's Run settings send zones here too). */
    private fun buildSettings() {
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 8, w / 7, w / 8, w / 5) }
        col.addView(label("Settings", Color.WHITE, 18f, true))

        fun row(key: String, title: String, onTap: (() -> Unit)?) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(C.CARD, 14)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) }
            addView(label(title, C.MUTED, 11f))
            addView(label("", Color.WHITE, 14f, true).also { startValues[key] = it })
            if (onTap != null) setOnClickListener { onTap(); if (editorRotary == null && !overlayOpen) renderStart() }
        }

        col.addView(row("screensRow", "Data screens (during a run)") { showScreensEditor() })
        col.addView(row("quickBtn", "Bottom button: start · lap · double-press finish") {
            prefs.edit().putBoolean("bottomButton", !prefs.getBoolean("bottomButton", true)).apply()
        }.apply {
            addView(label("Press once on the Running screen to start. During a run: press once for a lap (next step in a workout), " +
                "press twice quickly to finish and save.", C.MUTED, 9f))
            addView(label("", C.DIM, 9f).also { startValues["lastKey"] = it })
        })
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
        col.addView(row("hrmode", "Heart-rate sensor") {
            Run.useStrap = !Run.useStrap
            prefs.edit().putBoolean("useStrap", Run.useStrap).apply()
            RunService.instance?.applyHrMode()
        })
        col.addView(row("hr", "Heart rate (tap to re-pair strap)") {
            if (Run.useStrap && !Run.strapActive) RunService.instance?.pairStrap()
        })
        col.addView(row("autopause", "Auto-pause") {
            Run.autoPause = !Run.autoPause; prefs.edit().putBoolean("autoPause", Run.autoPause).apply()
        })
        col.addView(row("track", "GPS map track (uses battery)") {
            Run.recordGpsTrack = !Run.recordGpsTrack; prefs.edit().putBoolean("track", Run.recordGpsTrack).apply()
        }.also { trackRow = it })

        col.addView(label("Done", Color.BLACK, 16f, true).apply {
            background = rounded(C.ACCENT, 24)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
            setOnClickListener { showSettings = false; startBuilt = false; renderStart() }
        })
        col.addView(label("Watch app " + packageManager.getPackageInfo(packageName, 0).versionName, C.DIM, 10f).apply {
            setPadding(0, dp(12), 0, 0)
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

    // ---------------------------------------------------------------- data screens editor (watch)

    private fun saveScreens() {
        prefs.edit().putString("screens", screens.toString()).apply()
        builtScreen = -1
        RunSync.tellPhone(this, "/cmd/screens", screens.toString().toByteArray()) { }
    }

    private fun overlay(build: (LinearLayout) -> Unit) {
        overlayOpen = true; startBuilt = false
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 8, w / 7, w / 8, w / 4) }
        build(col)
        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    private fun card(title: String, sub: String, titleColor: Int = Color.WHITE, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(C.CARD, 14)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) }
        addView(label(title, titleColor, 14f, true))
        if (sub.isNotBlank()) addView(label(sub, C.MUTED, 10f))
        setOnClickListener { onTap() }
    }

    /** List of screens: tap one to change it. */
    private fun showScreensEditor() = overlay { col ->
        val RF = io.github.michealsizemore1.top25widget.RunFields
        col.addView(label("Data screens", Color.WHITE, 18f, true))
        col.addView(label("Swipe through these during a run. Also editable on the phone.", C.MUTED, 10f))
        for (i in 0 until screens.length()) {
            val sc = screens.getJSONObject(i)
            val f = sc.optJSONArray("fields")
            val names = (0 until RF.size(sc.optString("layout", "6"))).joinToString(" · ") { RF.name(f?.optString(it).orEmpty()) }
            col.addView(card("Screen ${i + 1}", names) { showScreenEditor(i) })
        }
        col.addView(card("+ Add a screen", "", C.ACCENT) {
            screens.put(org.json.JSONObject().put("layout", "4").put("fields", org.json.JSONArray(listOf("time", "pace", "hr", "distance"))))
            saveScreens(); showScreenEditor(screens.length() - 1)
        })
        col.addView(card("Done", "", C.ACCENT) { overlayOpen = false; startBuilt = false; renderStart() })
    }

    /** One screen: its layout and each spot, top to bottom. */
    private fun showScreenEditor(i: Int): Unit = overlay { col ->
        val RF = io.github.michealsizemore1.top25widget.RunFields
        val sc = screens.getJSONObject(i)
        val layout = RF.layout(sc.optString("layout", "6"))
        val fields = sc.optJSONArray("fields") ?: org.json.JSONArray().also { sc.put("fields", it) }
        while (fields.length() < layout.rows.sum()) fields.put("time")
        col.addView(label("Screen ${i + 1}", Color.WHITE, 18f, true))
        col.addView(card("Layout", layout.name, C.ACCENT) {
            // Tap to step through the layouts.
            val next = RF.LAYOUTS[(RF.LAYOUTS.indexOf(layout) + 1) % RF.LAYOUTS.size]
            sc.put("layout", next.id); saveScreens(); showScreenEditor(i)
        })
        var k = 0
        layout.rows.forEachIndexed { r, count ->
            repeat(count) { c ->
                val idx = k++
                val where = when {
                    layout.rows.size == 1 -> "Number"
                    r == 0 -> "Top"
                    r == layout.rows.size - 1 && count == 1 -> "Bottom"
                    count == 1 -> "Row ${r + 1}"
                    else -> "Row ${r + 1} " + (if (c == 0) "left" else "right")
                }
                col.addView(card(RF.name(fields.optString(idx)), where) { pickMetric(i, idx) })
            }
        }
        if (screens.length() > 1) col.addView(card("Remove this screen", "", C.STOP) {
            screens.remove(i); saveScreens(); showScreensEditor()
        })
        col.addView(card("Back", "", C.ACCENT) { showScreensEditor() })
    }

    private fun pickMetric(i: Int, idx: Int) = overlay { col ->
        val RF = io.github.michealsizemore1.top25widget.RunFields
        col.addView(label("Show", Color.WHITE, 18f, true))
        RF.METRICS.forEach { (key, name) ->
            col.addView(card(name, "") {
                screens.getJSONObject(i).optJSONArray("fields")?.put(idx, key)
                saveScreens(); showScreenEditor(i)
            })
        }
    }

    /** Pick today's planned workout, one from the library, or a free run. */
    private fun showWorkoutPicker() {
        overlayOpen = true
        startBuilt = false
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 8, w / 7, w / 8, w / 4) }
        col.addView(label("Workouts", Color.WHITE, 18f, true))
        fun item(title: String, sub: String, workout: org.json.JSONObject?) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(C.CARD, 14)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) }
            addView(label(title, Color.WHITE, 14f, true))
            if (sub.isNotBlank()) addView(label(sub, C.MUTED, 10f))
            setOnClickListener {
                Run.chooseWorkout(workout)
                overlayOpen = false; startBuilt = false; renderStart()
            }
        }
        fun outline(x: org.json.JSONObject) =
            io.github.michealsizemore1.top25widget.Workouts.outline(x, Run.hrMax, Run.hrRest, Run.criticalPower).take(3).joinToString(" · ")
        col.addView(item("Free run", "No targets", null))
        // The whole plan from today on, by week (like the phone's Plan tab).
        val dayName = java.text.SimpleDateFormat("EEE M/d", java.util.Locale.getDefault())
        val weekName = java.text.SimpleDateFormat("'WEEK OF' MMM d", java.util.Locale.getDefault())
        val keyFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        var lastWeek = ""
        WatchWorkouts.upcoming(this, 400).forEach { (d, wk) ->
            val date = keyFmt.parse(d) ?: return@forEach
            val wkStart = java.util.Calendar.getInstance().apply { time = date; set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek) }.time
            val header = weekName.format(wkStart).uppercase()
            if (header != lastWeek) { lastWeek = header; col.addView(label(header, C.MUTED, 10f, true).apply { setPadding(0, dp(8), 0, 0) }) }
            val label = if (d == keyFmt.format(java.util.Date())) "Today" else dayName.format(date)
            col.addView(item("$label: " + wk.optString("name"), outline(wk), wk))
        }
        if (WatchWorkouts.library(this).isNotEmpty()) col.addView(label("LIBRARY", C.MUTED, 10f, true).apply { setPadding(0, dp(10), 0, 0) })
        WatchWorkouts.library(this).forEach { col.addView(item(it.optString("name"), outline(it), it)) }
        if (WatchWorkouts.library(this).isEmpty())
            col.addView(label("Build workouts in the phone's Running app — they show up here.", C.DIM, 11f).apply { setPadding(0, dp(10), 0, 0) })
        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    @Deprecated("Deprecated in Java")
    /** Back gesture (swipe): navigation only — never starts or ends a run, never leaves mid-run. */
    override fun onBackPressed() {
        if (screen == Screen.LIVE && !wrongDevice) return
        if (editorRotary != null || overlayOpen) { editorRotary = null; overlayOpen = false; startBuilt = false; renderStart(); return }
        if (screen == Screen.START && showSettings) { showSettings = false; startBuilt = false; renderStart(); return }
        if (screen == Screen.SUMMARY) { finish(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * The physical bottom button, caught as a raw key press before the system turns it into
     * "back" (newer Wear OS skips onBackPressed for buttons, which is why it used to leave the app).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (wrongDevice) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            prefs.edit().putString("lastKey", android.view.KeyEvent.keyCodeToString(event.keyCode)).apply()   // shown in Settings
        }
        val isButton = event.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
            event.keyCode == android.view.KeyEvent.KEYCODE_STEM_PRIMARY ||
            event.keyCode == android.view.KeyEvent.KEYCODE_STEM_1
        if (!isButton || !prefs.getBoolean("bottomButton", true)) return super.dispatchKeyEvent(event)
        // Only handle it where the button has a run job; elsewhere it's a normal back.
        val runControl = screen == Screen.LIVE ||
            (screen == Screen.START && !Run.isActive && !showSettings && !overlayOpen && editorRotary == null)
        if (!runControl) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
            if (screen == Screen.LIVE) backButton()
            else { pulse(longArrayOf(0, 400)); startRun() }
        }
        return true   // consume both down and up so the system doesn't also go back
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
        pageView = page
        customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        builtScreen = -1
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
        root.addView(customBox)

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

    // ---------------------------------------------------------------- watch buttons

    /**
     * Bottom button during a run: one press = lap (or next workout step), a quick double press =
     * finish and save. A single press waits ~half a second to make sure it isn't a double.
     */
    private var pendingLap: Runnable? = null

    private fun backButton() {
        val waiting = pendingLap
        if (waiting != null) {
            // Second press: it's a double press → finish.
            handler.removeCallbacks(waiting); pendingLap = null
            if (Run.state != Run.State.RUNNING) Run.resume()   // a paused run still saves normally
            pulse(longArrayOf(0, 200, 100, 200, 100, 700))
            finishRun()
            return
        }
        val lap = Runnable {
            pendingLap = null
            if (!Run.isActive) return@Runnable
            if (Run.hasWorkout && !Run.workoutDone) { Run.nextWorkoutStep(auto = false); flash("NEXT STEP") }
            else { Run.lap(); flash("LAP ${Run.laps.size}") }
        }
        pendingLap = lap
        handler.postDelayed(lap, 450)
    }

    private fun pulse(p: LongArray) {
        try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(p, -1)) } catch (e: Exception) { }
    }

    private fun flash(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (wrongDevice) return super.dispatchTouchEvent(ev)
        if (screen == Screen.LIVE && liveBuilt && gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Turning the bezel also flips pages. */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (wrongDevice) return super.onGenericMotionEvent(ev)
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
        page = (page + delta).coerceIn(0, pageOrder().size - 1)
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
        val order = pageOrder()
        if (this.page >= order.size) this.page = order.size - 1
        dots.text = order.indices.joinToString(" ") { if (it == this.page) "●" else "○" }
        val page = order[this.page]   // which screen this position shows
        fieldD.first.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)

        val stateNow = when (Run.state) { Run.State.PAUSED -> "PAUSED"; Run.State.AUTO_PAUSED -> "AUTO-PAUSED"; else -> null }
        if (page >= 100) { renderScreen(page - 100, stateNow, order); return }
        pageView.visibility = View.VISIBLE
        customBox.visibility = View.GONE
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
            7 -> stepTitle()
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
        if (page == 7) { zoneBar.visibility = View.GONE; renderStep(stateText); return }
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

    // ---------------------------------------------------------------- workout step page

    private fun stepTitle(): String {
        if (Run.workoutDone) return "WORKOUT DONE"
        val s = Run.currentStep ?: return "WORKOUT"
        val kind = io.github.michealsizemore1.top25widget.Workouts.kindName(s.optString("kind")).uppercase()
        return if (s.has("rep")) "$kind · ${s.optInt("rep")}/${s.optInt("reps")}"
               else "$kind · ${Run.stepIndex + 1}/${Run.workoutSteps.size}"
    }

    private fun renderStep(stateText: String?) {
        if (stateText != null) { liveTitle.text = stateText; liveTitle.setTextColor(C.PAUSE) }
        else { liveTitle.text = stepTitle(); liveTitle.setTextColor(C.ACCENT) }
        if (Run.workoutDone) {
            show(fieldA, Run.clock(Run.movingSeconds), "total time")
            show(fieldB, Run.miles(Run.distance), "miles")
            show(fieldC, Run.pace(Run.currentSpeed), "pace", C.ACCENT)
            show(fieldD, "✓", "keep running or finish", C.OK)
            return
        }
        val s = Run.currentStep ?: return
        // Big number: what's left in the step.
        val rem = Run.stepRemaining
        when (s.optString("dur")) {
            "time" -> show(fieldA, Run.clock(rem?.toInt() ?: 0), "left in step")
            "dist" -> show(fieldA, rem?.let { if (s.optInt("value") < 1600) "${it.toInt()} m" else Run.miles(it) } ?: "--",
                if (s.optInt("value") < 1600) "left in step" else "mi left in step")
            else -> show(fieldA, Run.clock(Run.stepSeconds), "tap NEXT STEP when done")
        }
        // Target and where you are against it.
        val target = s.optString("target")
        val (lo, hi) = Run.stepTarget
        val v = Run.stepValue()
        val color = when (Run.stepStatus) { 0 -> C.OK; null -> Color.WHITE; else -> if (
            (target == "pace" && Run.stepStatus == 1) || (target != "pace" && Run.stepStatus == -1)) 0xFF4FA3E0.toInt() else C.STOP }
        val hint = when (Run.stepStatus) {
            0 -> "on target"
            -1 -> if (target == "pace") "ease off" else "speed up"
            1 -> if (target == "pace") "speed up" else "ease off"
            else -> ""
        }
        when (target) {
            "hr" -> show(fieldB, v?.toString() ?: "--", "HR $lo–$hi", color)
            "power" -> show(fieldB, v?.toString() ?: "--", "W $lo–$hi", color)
            "pace" -> show(fieldB, v?.let { Run.clock(it) } ?: "--", "${Run.clock(lo)}–${Run.clock(hi)}", color)
            else -> show(fieldB, if (Run.heartRate > 0) "${Run.heartRate}" else "--", "HR", Color.WHITE)
        }
        show(fieldC, Run.paceFor(Run.stepSeconds, Run.stepMeters), if (hint.isNotEmpty()) hint else "step pace",
            if (hint.isNotEmpty()) color else C.ACCENT)
        val next = Run.nextStep
        show(fieldD, if (next == null) "Last step" else "Next: " +
            io.github.michealsizemore1.top25widget.Workouts.kindName(next.optString("kind")) + " " +
            io.github.michealsizemore1.top25widget.Workouts.durText(next), "", C.MUTED)
        fieldD.first.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    // ---------------------------------------------------------------- custom data screens

    private lateinit var pageView: View
    private lateinit var customBox: LinearLayout
    private val cells = mutableListOf<Pair<String, Pair<TextView, TextView>>>()   // key → (label, value)
    private lateinit var customState: TextView
    private lateinit var customDots: TextView

    /** Builds a screen: rows of cells, each a small label over a number that fills its space. */
    private fun buildScreen(i: Int) {
        builtScreen = i
        cells.clear()
        customBox.removeAllViews()
        val w = resources.displayMetrics.widthPixels
        val sc = screens.optJSONObject(i) ?: return
        val layout = io.github.michealsizemore1.top25widget.RunFields.layout(sc.optString("layout", "6"))
        val fields = sc.optJSONArray("fields")
        customBox.setPadding(0, w / 16, 0, w / 22)
        customState = label("", C.PAUSE, 10f, true)
        customBox.addView(customState, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        var k = 0
        layout.rows.forEachIndexed { r, count ->
            val edge = r == 0 || r == layout.rows.size - 1
            // Round screen: the top and bottom rows are narrower.
            val inset = if (edge && layout.rows.size > 2) w / 6 else w / 14
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(inset, dp(1), inset, dp(1))
            }
            repeat(count) {
                val key = fields?.optString(k).orEmpty().ifBlank { "time" }; k++
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    if (count > 1) { background = rounded(C.CARD, 10); setPadding(dp(2), dp(1), dp(2), dp(1)) }
                }
                val lab = label(io.github.michealsizemore1.top25widget.RunFields.name(key), C.MUTED, 9f)
                val value = TextView(this).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setAutoSizeTextTypeUniformWithConfiguration(10, 80, 1, TypedValue.COMPLEX_UNIT_SP)
                }
                cell.addView(lab, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                cell.addView(value, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                row.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (count > 1) { marginStart = dp(2); marginEnd = dp(2) } })
                cells += key to (lab to value)
            }
            customBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) })
        }
        customDots = label("", C.DIM, 10f)
        customBox.addView(customDots, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun renderScreen(i: Int, stateText: String?, order: List<Int>) {
        pageView.visibility = View.GONE
        customBox.visibility = View.VISIBLE
        if (builtScreen != i) buildScreen(i)
        customState.text = stateText ?: ""
        customState.visibility = if (stateText == null) View.INVISIBLE else View.VISIBLE
        customDots.text = order.indices.joinToString(" ") { if (it == this.page) "●" else "○" }
        cells.forEach { (key, views) ->
            val (v, color) = metric(key)
            views.second.text = v
            views.second.setTextColor(color)
        }
    }

    /** Current value and color for one metric. */
    private fun metric(key: String): Pair<String, Int> {
        val w = Color.WHITE
        fun n(v: Int) = if (v > 0) "$v" else "--"
        val hz = Run.hrZoneIndex(Run.heartRate)
        val zi = Run.zoneIndex(Run.power3s)
        fun hrColor(bpm: Int) = Run.hrZoneIndex(bpm).let { if (it >= 0) Run.ZONE_COLORS[it] else w }
        fun pwColor(watts: Int) = Run.zoneIndex(watts).let { if (it >= 0) Run.ZONE_COLORS[it] else w }
        return when (key) {
            "time" -> Run.clock(Run.movingSeconds) to w
            "elapsed" -> Run.clock(Run.elapsedSeconds) to w
            "clock" -> java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(java.util.Date()) to w
            "distance" -> Run.miles(Run.distance) to w
            "pace" -> Run.pace(Run.currentSpeed) to C.ACCENT
            "avgPace" -> Run.pace(Run.avgSpeed) to C.ACCENT
            "hr" -> n(Run.heartRate) to hrColor(Run.heartRate)
            "avgHr" -> n(Run.avgHr) to hrColor(Run.avgHr)
            "maxHr" -> n(Run.maxHr) to hrColor(Run.maxHr)
            "hrZone" -> (if (hz >= 0) "Z${hz + 1}" else "--") to (if (hz >= 0) Run.ZONE_COLORS[hz] else w)
            "hrr" -> (if (Run.heartRate > 0) "${Run.hrrPercent(Run.heartRate)}%" else "--") to hrColor(Run.heartRate)
            "power" -> (if (Run.strydConnected) n(Run.power3s) else "--") to pwColor(Run.power3s)
            "power10" -> (if (Run.strydConnected) n(Run.power10s) else "--") to pwColor(Run.power10s)
            "avgPower" -> n(Run.avgPower) to pwColor(Run.avgPower)
            "np" -> n(Run.normalizedPower) to pwColor(Run.normalizedPower)
            "pctCp" -> (if (Run.power3s > 0) "${Run.power3s * 100 / Run.criticalPower.coerceAtLeast(1)}%" else "--") to pwColor(Run.power3s)
            "powerZone" -> (if (zi >= 0) "Z${zi + 1}" else "--") to (if (zi >= 0) Run.ZONE_COLORS[zi] else w)
            "cadence" -> n(Run.strydCadence) to w
            "avgCadence" -> n(Run.avgCadence) to w
            "stride" -> (if (Run.strydStride > 0.2) String.format(java.util.Locale.US, "%.2f", Run.strydStride * Run.strydCalibration) else "--") to w
            "lapTime" -> Run.clock(Run.lapSeconds) to w
            "lapDist" -> Run.miles(Run.lapMeters) to w
            "lapPace" -> Run.paceFor(Run.lapSeconds, Run.lapMeters) to C.ACCENT
            "lapHr" -> n(Run.lapHr) to hrColor(Run.lapHr)
            "lapPower" -> n(Run.lapPower) to pwColor(Run.lapPower)
            "lapNum" -> "${Run.lapNumber}" to w
            "lastLap" -> (Run.laps.lastOrNull()?.let { Run.clock(it.seconds) } ?: "--") to w
            "stress" -> "${Run.stress}" to C.ACCENT
            "intensity" -> (if (Run.intensity > 0) String.format(java.util.Locale.US, "%.2f", Run.intensity) else "--") to w
            else -> "--" to w
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
            controlsBox.addView(label("Tip: double-press the bottom button to finish", C.MUTED, 10f))
            controlsBox.addView(button("SAVE RUN", C.OK) { finishRun() })
            controlsBox.addView(button("Discard", C.STOP, Color.WHITE) {
                Run.discard(this); RunService.stop(this); finish()
            })
            controlsBox.addView(button("Back", C.CARD, Color.WHITE) { confirmingFinish = false })
        } else {
            controlsBox.addView(if (paused) button("RESUME", C.ACCENT) { Run.resume() }
                                else button("PAUSE", C.PAUSE) { Run.pause() })
            if (Run.hasWorkout && !Run.workoutDone) controlsBox.addView(button("NEXT STEP", C.CARD, Color.WHITE) { Run.nextWorkoutStep(auto = false) })
            else controlsBox.addView(button("LAP", C.CARD, Color.WHITE) { Run.lap() })
            controlsBox.addView(button("FINISH", C.STOP, Color.WHITE) { Run.pause(); confirmingFinish = true })
        }
    }

    // ---------------------------------------------------------------- summary

    private fun finishRun() {
        val json = Run.finish(this)
        RunSync.pushAll(this)
        RunService.stop(this)
        summary = json
        screen = Screen.SUMMARY
        liveBuilt = false
        root.removeAllViews()
        root.setBackgroundColor(C.BG)
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
