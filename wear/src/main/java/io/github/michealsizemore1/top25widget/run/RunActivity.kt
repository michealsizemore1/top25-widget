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
    private fun pageOrder() = listOf(0) + (if (Run.hasWorkout) listOf(7) else emptyList()) +
        (if (Run.nav != null) listOf(8) else emptyList()) + (0 until screens.length()).map { 100 + it }   // 8 = trail map
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
        Speaker.init(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                logKey("bottom button (back) · screen=$screen")
                handleBack()
            }
        }

        RunSync.loadSettings(this)
        // Changes made here go to the phone; changes made on the phone come back (RunSettingsListener).
        io.github.michealsizemore1.top25widget.SettingsSync.watch(this, "run", "watch")
        loadScreens()
        // Keep the screen awake so the app keeps focus — otherwise, once the watch dims, the bottom
        // button goes to the system instead of the app (and closes it).
        if (prefs.getBoolean("keepOn", true)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                if (!Run.isActive) { RunSync.loadSettings(this); loadScreens(); RunSync.loadTrail(this) }
                startBuilt = false; render()
            }
        }
        screen = if (Run.isActive) Screen.LIVE else Screen.START
        requestNeeded()
        tileStart(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tileStart(intent)
    }

    /** "Start" on the Running tile: open and start the run right away. */
    private fun tileStart(i: android.content.Intent?) {
        if (i?.getBooleanExtra(RunTileService.EXTRA_START, false) != true) return
        i.removeExtra(RunTileService.EXTRA_START)
        if (Run.isActive || wrongDevice) return
        handler.postDelayed({
            if (!Run.isActive && screen == Screen.START) { overlayOpen = false; showSettings = false; startRun(); flash("Run started") }
        }, 700)
    }

    override fun onResume() {
        super.onResume()
        if (wrongDevice) return
        if (!Run.isActive) { RunSync.loadSettings(this); RunSync.loadTrail(this) }
        loadScreens()
        Run.listen(onRunChanged)
        // Coming back from History (which stopped the idle sensors): turn them on again.
        if (!RunService.running && screen != Screen.SUMMARY) RunService.start(this)
        render()
    }
    override fun onPause() {
        RunTileService.refresh(this)
        if (isOnScreen || hasWindowFocus()) lastShownAt = System.currentTimeMillis()
        isOnScreen = false
        super.onPause()
        if (!wrongDevice) Run.unlisten(onRunChanged)
    }

    /** Last few button events, shown in Settings to diagnose the orange button. */
    private fun logButton(line: String) {
        val t = java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = prefs.getString("buttonLog", "").orEmpty().lines().filter { it.isNotBlank() }.take(4)
        prefs.edit().putString("buttonLog", (listOf("$t $line") + old).joinToString("\n")).apply()
    }
    private var stoppedAt = 0L
    /** When the Running screen was last on the display, for telling a button press from opening the app. */
    private var lastShownAt = 0L
    private var isOnScreen = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) isOnScreen = true else { isOnScreen = false; lastShownAt = System.currentTimeMillis() }
    }
    /** True while the Running screen is on the display (not behind another app or the watch face). */
    private var wasVisible = false
    override fun onStart() { super.onStart(); handler.postDelayed({ wasVisible = true }, 600) }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
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
        if (requestCode == 7) {   // texting permission for LiveTrack
            if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED)
                flash("Watch can't text — your phone will still send texts")
            return
        }
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
        set("voiceRow", (if (prefs.getBoolean("voice", true)) "On" else "Off") + " ▸", C.ACCENT)
        set("quickBtn", if (prefs.getBoolean("gestures", true)) "On" else "Off")
        set("bottomBtn", if (prefs.getBoolean("bottomBtn", true)) "On" else "Off")
        set("keepOn", if (prefs.getBoolean("keepOn", true)) "On" else "Off")
        set("keyLog", "Button log:\n" + (prefs.getString("buttonLog", null)?.lines()?.take(3)?.joinToString("\n") ?: "none yet"), C.DIM)
        set("source", if (Run.source == Run.Source.STRYD) "Stryd ▸" else "GPS ▸", C.ACCENT)
        startValues["trailLine"]?.apply {
            val n = Run.nav
            text = if (n == null) "" else "Trail: ${n.trail.name} · " + String.format(java.util.Locale.US, "%.1f mi", n.total / 1609.344) +
                (if (n.outAndBack) " out & back" else "")
            visibility = if (n == null) View.GONE else View.VISIBLE
        }
        set("workout", (Run.workout?.optString("name") ?: "Free run") + " ›", if (Run.workout != null) Color.WHITE else C.ACCENT)
        startValues["workoutOutline"]?.apply {
            val w = Run.workout
            text = if (w == null) "" else io.github.michealsizemore1.top25widget.Workouts.outline(w, Run.hrMax, Run.hrRest, Run.criticalPower)
                .take(6).joinToString("\n")
            visibility = if (w == null) View.GONE else View.VISIBLE
        }
        // Green = connected (same as the heart-rate strap); orange = low battery or connected but quiet; gray = not connected.
        val strydLive = Run.strydConnected && System.currentTimeMillis() - Run.strydLastUpdate < 5000
        set("stryd", (if (Run.strydConnected) "✓ " else "") + Run.strydStatus +
                (if (Run.strydConnected && Run.strydBattery >= 0) " · ${Run.strydBattery}%" else "") +
                (if (strydLive && Run.strydPower > 0) " · ${Run.strydPower} W" else ""),
            when {
                !Run.strydConnected -> C.MUTED
                Run.strydBattery in 0..15 -> C.PAUSE
                else -> C.OK
            })
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
        set("textScale", when { prefs.getFloat("textScale", 1.25f) >= 1.45f -> "Extra large"; prefs.getFloat("textScale", 1.25f) >= 1.2f -> "Large"; else -> "Normal" } + " ▸")
        set("hrcap", when (Run.hrCapMode) { "off" -> "Off"; "all" -> "Easy days + free runs · under ${Run.hrCap}"; else -> "Easy days · under ${Run.hrCap}" })
        set("live", if (!prefs.getBoolean("liveOn", false)) "Off" else if (Run.isActive) "On · ${LiveTrack.status}" else "On")
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
        col.addView(label("BeyondStride", Color.WHITE, 20f, true))

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
        // LiveTrack on/off for the next run (same switch as Settings and the phone).
        col.addView(label("", Color.WHITE, 12f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(7))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) }
            fun show() {
                val on = prefs.getBoolean("liveOn", false)
                text = if (on) "● LiveTrack ON · tap to turn off" else "LiveTrack off · tap to turn on"
                background = rounded(if (on) 0xFF1F6FB2.toInt() else C.CARD, 14)
                setTextColor(if (on) Color.WHITE else C.MUTED)
            }
            show()
            setOnClickListener {
                if (prefs.getString("liveDb", "").isNullOrBlank()) { flash("Set up LiveTrack in the phone app first"); return@setOnClickListener }
                val on = !prefs.getBoolean("liveOn", false)
                prefs.edit().putBoolean("liveOn", on).apply()
                show(); flash(if (on) "LiveTrack on — contacts get your link at Start" else "LiveTrack off")
                if (on && checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 7)
            }
        })

        // Today's workout (tap to change).
        col.addView(card().apply {
            addView(label("TODAY", C.MUTED, 10f, true))
            addView(label("", Color.WHITE, 15f, true).also { startValues["workout"] = it })
            addView(label("", C.MUTED, 10f).apply { startValues["workoutOutline"] = this; visibility = View.GONE })
            addView(label("", 0xFF46C46E.toInt(), 11f, true).apply { startValues["trailLine"] = this; visibility = View.GONE })
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
        Run.nav?.trail?.let { t -> col.addView(navButton("Directions to trail start", C.CARD, Color.WHITE) { showDirections(t) }) }
        col.addView(navButton("Trails near me", 0xFF46C46E.toInt(), Color.BLACK) { showTrails() })
        col.addView(navButton("Workouts & Plan", C.ACCENT, Color.BLACK) { showWorkoutPicker() })
        col.addView(navButton("History", C.CARD, Color.WHITE) { startActivity(Intent(this, RunHistoryActivity::class.java)) })
        col.addView(navButton("Settings", C.CARD, Color.WHITE) { showSettings = true; startBuilt = false; renderStart() })

        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    /** After a run: back to the Running home screen (sensors on again, ready for the next run). */
    private fun backToHome() {
        screen = Screen.START
        summary = null
        showSettings = false; overlayOpen = false; editorRotary = null
        liveBuilt = false; startBuilt = false
        if (!RunService.running) RunService.start(this)
        render()
    }

    private fun startRun() {
        if (!RunService.running) RunService.start(this)
        Run.start()
        // Open on your first data screen (the workout step and trail map are a swipe to the left).
        screen = Screen.LIVE; page = pageOrder().indexOf(100).takeIf { it >= 0 } ?: 1; liveBuilt = false
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

        col.addView(row("live", "LiveTrack (GPS on, shares your run)") {
            if (prefs.getString("liveDb", "").isNullOrBlank()) flash("Set up LiveTrack in the phone app first")
            else prefs.edit().putBoolean("liveOn", !prefs.getBoolean("liveOn", false)).apply()
        })
        col.addView(row("textScale", "Text size") {
            val opts = listOf(1f, 1.25f, 1.5f)
            val cur = prefs.getFloat("textScale", 1.25f)
            val next = opts[(opts.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f } + 1) % opts.size]
            prefs.edit().putFloat("textScale", next).apply()
            startBuilt = false; liveBuilt = false; builtScreen = -1; render()
        })
        col.addView(row("hrcap", "Easy-day heart-rate cap") {
            val next = when (Run.hrCapMode) { "off" -> "easy"; "easy" -> "all"; else -> "off" }
            Run.hrCapMode = next; prefs.edit().putString("hrCap", next).apply()
        })
        col.addView(row("screensRow", "Data screens (during a run)") { showScreensEditor() })
        col.addView(row("voiceRow", "Audio prompts") { showVoiceSettings() })
        col.addView(row("bottomBtn", "Bottom button: start · lap · double-press finish") {
            prefs.edit().putBoolean("bottomBtn", !prefs.getBoolean("bottomBtn", true)).apply()
        }.apply { addView(label("", C.DIM, 9f).also { startValues["keyLog"] = it }) })
        col.addView(row("keepOn", "Keep screen on in BeyondStride") {
            val on = !prefs.getBoolean("keepOn", true)
            prefs.edit().putBoolean("keepOn", on).apply()
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        })
        col.addView(row("quickBtn", "Run gestures: double-tap = lap · hold = finish") {
            prefs.edit().putBoolean("gestures", !prefs.getBoolean("gestures", true)).apply()
        }.apply {
            addView(label("Anywhere on a run screen: double-tap for a lap (next step in a workout); press and hold " +
                "about a second to pause and open Finish (big SAVE / RESUME). Tip: set the orange Quick button to open BeyondStride.", C.MUTED, 9f))
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

    // ---------------------------------------------------------------- trails (no phone needed: GPS + LTE)

    private var trailCache: Pair<Long, List<io.github.michealsizemore1.top25widget.Trail>>? = null
    private var trailStatus = ""

    private var trailKey = ""
    private var trailShowAll = false

    private fun showTrails(): Unit = overlay { col ->
        val place = io.github.michealsizemore1.top25widget.TrailSearch.area(this, "run")
        val radius = io.github.michealsizemore1.top25widget.TrailSearch.radiusMi(this, "run")
        val longest = io.github.michealsizemore1.top25widget.TrailSearch.sortLongest(this, "run")
        // Search settings changed (here or on the phone) → search again.
        val key = "${place?.encode()}|$radius"
        if (key != trailKey) { trailKey = key; trailCache = null; trailStatus = ""; trailShowAll = false }
        val from = place?.label?.substringBefore(",")
        col.addView(label(if (from == null) "Trails near me" else "Trails · $from", Color.WHITE, 18f, true))
        val saved = io.github.michealsizemore1.top25widget.Courses.list(this)
        if (saved.isNotEmpty()) col.addView(card("★ Saved courses (${saved.size})", saved.take(2).joinToString(", ") { it.name }, 0xFFD4E635.toInt()) { showCourses() })
        col.addView(card("Where: " + (from ?: "Near me"), "Tap to change", C.ACCENT) { showTrailWhere() })
        col.addView(card("Within $radius mi · " + (if (longest) "longest first" else "nearest first"), "Tap to change") { showTrailRange() })
        if (Run.nav != null) col.addView(card("No trail", "Clear ${Run.nav?.trail?.name}", C.STOP) {
            Run.chooseTrail(null, false); io.github.michealsizemore1.top25widget.TrailSync.choose(this, null, false, "watch")
            overlayOpen = false; startBuilt = false; renderStart()
        })
        val cached = trailCache?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second
        if (cached == null) {
            col.addView(label(trailStatus.ifBlank { "Finding your location…" }, C.MUTED, 12f).apply { setPadding(0, dp(10), 0, 0) })
            if (trailStatus.isBlank() || trailStatus.startsWith("Finding")) loadTrails()
            else col.addView(card("Try again", "", C.ACCENT) { trailStatus = ""; showTrails() })
            return@overlay
        }
        if (cached.isEmpty()) col.addView(label("No mapped trails within $radius miles.", C.MUTED, 12f))
        val sorted = io.github.michealsizemore1.top25widget.TrailSearch.sort(cached, longest)
        (if (trailShowAll) sorted else sorted.take(25)).forEach { t ->
            val mi = t.lengthM / 1609.344
            col.addView(card(t.name, String.format(java.util.Locale.US, "%.1f mi %s · %s · %s", mi, if (t.loop) "loop" else "one way",
                t.difficulty.takeIf { t.elevations != null || t.sac.isNotBlank() } ?: (if (t.surface.isNotBlank()) t.surface else "trail"),
                distText(t.distToStartM) + if (from == null) " away" else " from $from")) { showTrail(t) })
        }
        if (!trailShowAll && sorted.size > 25) col.addView(card("Show all ${sorted.size}", "", C.ACCENT) { trailShowAll = true; showTrails() })
        col.addView(card("Refresh", "Search again from here", C.ACCENT) { trailCache = null; trailStatus = ""; showTrails() })
    }

    /** Saved courses (same list as the phone). */
    private fun showCourses(): Unit = overlay { col ->
        col.addView(label("Saved courses", Color.WHITE, 18f, true))
        val here = if (Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 10 * 60_000) doubleArrayOf(Run.gpsLat, Run.gpsLon) else lastKnown()
        val list = io.github.michealsizemore1.top25widget.Courses.list(this)
        if (list.isEmpty()) col.addView(label("None yet. Open a trail and tap Save course, or save a run's route on the phone.", C.MUTED, 11f))
        list.forEach { c ->
            here?.let { me -> c.distToStartM = c.points.minOf { io.github.michealsizemore1.top25widget.Geo.dist(it, me) } }
            col.addView(card(c.name, String.format(java.util.Locale.US, "%.1f mi %s", c.lengthM / 1609.344, if (c.loop) "loop" else "one way") +
                (if (here != null) " · " + distText(c.distToStartM) + " away" else "")) { showTrail(c) })
        }
        col.addView(card("Back", "", C.ACCENT) { showTrails() })
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnown(): DoubleArray? = try {
        val lm = getSystemService(android.location.LocationManager::class.java)
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.FUSED_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }?.let { doubleArrayOf(it.latitude, it.longitude) }
    } catch (e: Exception) { null }

    /** Google Maps directions to the trail start — on the watch, or on the phone if the watch has no Maps. */
    private fun showDirections(t: io.github.michealsizemore1.top25widget.Trail): Unit = overlay { col ->
        val s0 = t.points.first()
        col.addView(label("Directions to start", Color.WHITE, 18f, true))
        col.addView(label(t.name, C.MUTED, 11f))
        fun go(mode: String) {
            if (!io.github.michealsizemore1.top25widget.Directions.open(this, s0[0], s0[1], mode)) {
                RunSync.tellPhone(this, "/cmd/directions", "${s0[0]},${s0[1]},$mode,${t.name}".toByteArray()) { ok ->
                    runOnUiThread { flash(if (ok) "Sent to your phone" else "No Google Maps here or phone") }
                }
            }
        }
        col.addView(card("Walk / run there", "Google Maps on the watch", C.ACCENT) { go("w") })
        col.addView(card("Drive there", "Google Maps on the watch", C.ACCENT) { go("d") })
        col.addView(card("Send to phone", "Directions notification on the phone") {
            RunSync.tellPhone(this, "/cmd/directions", "${s0[0]},${s0[1]},d,${t.name}".toByteArray()) { ok ->
                runOnUiThread { flash(if (ok) "Sent to your phone" else "Phone not reachable") }
            }
        })
        col.addView(card("Back", "", C.ACCENT) { showTrail(t) })
    }

    /** Where to search: near me, a recent place, or type/say a new one. */
    private fun showTrailWhere(): Unit = overlay { col ->
        val TSr = io.github.michealsizemore1.top25widget.TrailSearch
        col.addView(label("Search where?", Color.WHITE, 18f, true))
        col.addView(card("Near me", "Uses GPS", C.ACCENT) { TSr.setArea(this, "run", "watch", null); showTrails() })
        recentPlaces().forEach { pl ->
            col.addView(card(pl.label.substringBefore(","), pl.label.substringAfter(", ", "")) { TSr.setArea(this, "run", "watch", pl); showTrails() })
        }
        col.addView(label("Or type a town:", C.MUTED, 11f).apply { setPadding(0, dp(10), 0, 0) })
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Boise"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(C.MUTED); textSize = 14f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = rounded(C.CARD, 14); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val msg = label("", C.MUTED, 11f)
        fun go() {
            val q = input.text.toString().trim(); if (q.isEmpty()) return
            msg.text = "Looking up $q…"
            Thread {
                val found = try { TSr.geocode(q) } catch (e: Exception) { null }
                runOnUiThread {
                    when {
                        found == null -> msg.text = "No connection"
                        found.isEmpty() -> msg.text = "No place called \"$q\""
                        found.size == 1 -> { TSr.setArea(this, "run", "watch", found[0]); rememberPlace(found[0]); showTrails() }
                        else -> overlay { c2 ->
                            c2.addView(label("Which one?", Color.WHITE, 18f, true))
                            found.forEach { pl -> c2.addView(card(pl.label.substringBefore(","), pl.label.substringAfter(", ", "")) {
                                TSr.setArea(this, "run", "watch", pl); rememberPlace(pl); showTrails() }) }
                            c2.addView(card("Back", "", C.ACCENT) { showTrailWhere() })
                        }
                    }
                }
            }.start()
        }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        col.addView(input)
        col.addView(card("Search", "", C.ACCENT) { go() })
        col.addView(msg)
        col.addView(card("Back", "", C.ACCENT) { showTrails() })
    }

    /** How far, and sort order. */
    private fun showTrailRange(): Unit = overlay { col ->
        val TSr = io.github.michealsizemore1.top25widget.TrailSearch
        val r = TSr.radiusMi(this, "run"); val longest = TSr.sortLongest(this, "run")
        col.addView(label("How far?", Color.WHITE, 18f, true))
        TSr.RADII.forEach { mi -> col.addView(card((if (mi == r) "✓ " else "") + "Within $mi mi", "", if (mi == r) C.ACCENT else Color.WHITE) {
            TSr.setRadius(this, "run", "watch", mi); showTrailRange() }) }
        col.addView(label("Sort", C.MUTED, 11f).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(card((if (!longest) "✓ " else "") + "Nearest first", "", if (!longest) C.ACCENT else Color.WHITE) { TSr.setSortLongest(this, "run", "watch", false); showTrailRange() })
        col.addView(card((if (longest) "✓ " else "") + "Longest first", "", if (longest) C.ACCENT else Color.WHITE) { TSr.setSortLongest(this, "run", "watch", true); showTrailRange() })
        col.addView(card("Done", "", C.ACCENT) { showTrails() })
    }

    private fun recentPlaces(): List<io.github.michealsizemore1.top25widget.TrailSearch.Place> {
        val list = (prefs.getString("trailRecent", "") ?: "").split("\n").mapNotNull { line ->
            line.split("|").takeIf { it.size == 3 }?.let { io.github.michealsizemore1.top25widget.TrailSearch.Place(it[0], it[1].toDoubleOrNull() ?: return@mapNotNull null, it[2].toDoubleOrNull() ?: return@mapNotNull null) }
        }.toMutableList()
        io.github.michealsizemore1.top25widget.TrailSearch.area(this, "run")?.let { cur -> if (list.none { it.label == cur.label }) list.add(0, cur) }
        return list.take(5)
    }
    private fun rememberPlace(pl: io.github.michealsizemore1.top25widget.TrailSearch.Place) {
        val l = listOf(pl) + recentPlaces().filter { it.label != pl.label }
        prefs.edit().putString("trailRecent", l.take(5).joinToString("\n") { it.encode() }).apply()
    }

    private fun distText(m: Double) = if (m < 160) "${(m * 3.28084).toInt()} ft" else String.format(java.util.Locale.US, "%.1f mi", m / 1609.344)

    /** Gets a location (GPS, or the last known one if recent), then trails around it from OpenStreetMap. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun loadTrails() {
        io.github.michealsizemore1.top25widget.TrailSearch.area(this, "run")?.let { pl ->
            trailStatus = "Looking for trails…"
            Thread {
                trailStatus = try {
                    trailCache = System.currentTimeMillis() to io.github.michealsizemore1.top25widget.Trails.fetchNearby(pl.lat, pl.lon,
                        (io.github.michealsizemore1.top25widget.TrailSearch.radiusMi(this, "run") * 1609.344).toInt()); ""
                } catch (e: Exception) { "Couldn't get trails: ${e.message}" }
                runOnUiThread { if (overlayOpen) showTrails() }
            }.start()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            trailStatus = "Allow location for BeyondStride to find trails"; return
        }
        trailStatus = "Finding your location…"
        val lm = getSystemService(android.location.LocationManager::class.java)
        fun search(lat: Double, lon: Double) {
            trailStatus = "Looking for trails…"
            runOnUiThread { if (overlayOpen) showTrails() }
            Thread {
                trailStatus = try {
                    val list = io.github.michealsizemore1.top25widget.Trails.fetchNearby(lat, lon,
                        (io.github.michealsizemore1.top25widget.TrailSearch.radiusMi(this, "run") * 1609.344).toInt())
                    trailCache = System.currentTimeMillis() to list
                    ""
                } catch (e: Exception) { "Couldn't get trails: ${e.message}" }
                runOnUiThread { if (overlayOpen) showTrails() }
            }.start()
        }
        val recent = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.FUSED_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }.maxByOrNull { it.time }
        if (recent != null) { search(recent.latitude, recent.longitude); return }
        if (Run.gpsFix && System.currentTimeMillis() - Run.gpsLastUpdate < 60_000) { search(Run.gpsLat, Run.gpsLon); return }
        try {
            lm.getCurrentLocation(android.location.LocationManager.GPS_PROVIDER, null, mainExecutor) { loc ->
                if (loc != null) search(loc.latitude, loc.longitude)
                else { trailStatus = "No GPS fix — step outside and try again"; if (overlayOpen) showTrails() }
            }
        } catch (e: Exception) { trailStatus = "Location is off"; }
    }

    /** One trail: facts, elevation profile, and how to run it. */
    private fun showTrail(t: io.github.michealsizemore1.top25widget.Trail): Unit = overlay { col ->
        val mi = t.lengthM / 1609.344
        col.addView(label(t.name, Color.WHITE, 16f, true))
        val facts = mutableListOf(String.format(java.util.Locale.US, "%.2f mi %s", mi, if (t.loop) "loop" else "one way"))
        if (t.elevations != null) facts += "${(t.climbM * 3.28084).toInt()} ft climb"
        facts += t.difficulty
        if (t.surface.isNotBlank()) facts += t.surface
        if (t.distToStartM > 0) facts += distText(t.distToStartM) + " away"
        col.addView(label(facts.joinToString(" · "), C.MUTED, 11f))
        val w = resources.displayMetrics.widthPixels * 3 / 4
        val profile = android.widget.ImageView(this)
        col.addView(profile, LinearLayout.LayoutParams(w, dp(50)).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        fun showProfile() { TrailProfile.bitmap(t, w, dp(50), resources.displayMetrics.density)?.let { profile.setImageBitmap(it) } }
        if (t.elevations == null) {
            col.addView(label("Loading elevation…", C.DIM, 10f).also { status ->
                Thread {
                    val ok = try { io.github.michealsizemore1.top25widget.Trails.fetchElevation(t); true } catch (e: Exception) { false }
                    runOnUiThread { if (ok) { status.text = ""; showTrail(t) } else status.text = "Elevation unavailable" }
                }.start()
            })
        } else showProfile()
        fun choose(outAndBack: Boolean) {
            Run.chooseTrail(t, outAndBack)
            io.github.michealsizemore1.top25widget.TrailSync.choose(this, t, outAndBack, "watch")   // shows on the phone too
            overlayOpen = false; startBuilt = false; renderStart()
            flash("Trail set — press Start")
        }
        if (t.loop) col.addView(card("Run this loop", String.format(java.util.Locale.US, "%.2f mi", mi), C.ACCENT) { choose(false) })
        else {
            col.addView(card("Out & back", String.format(java.util.Locale.US, "%.2f mi · turn around at the end", mi * 2), C.ACCENT) { choose(true) })
            col.addView(card("One way", String.format(java.util.Locale.US, "%.2f mi", mi), C.ACCENT) { choose(false) })
        }
        col.addView(card("Directions to start", "Google Maps", Color.WHITE) { showDirections(t) })
        val isSaved = io.github.michealsizemore1.top25widget.Courses.isSaved(this, t.id)
        col.addView(card(if (isSaved) "★ Saved course" else "☆ Save course", if (isSaved) "Tap to remove" else "Also saves on the phone", 0xFFD4E635.toInt()) {
            if (isSaved) io.github.michealsizemore1.top25widget.Courses.remove(this, t.id, "watch") else io.github.michealsizemore1.top25widget.Courses.save(this, t, "watch")
            flash(if (isSaved) "Removed" else "Saved"); showTrail(t)
        })
        col.addView(card("Back", "", C.ACCENT) { if (isSaved || io.github.michealsizemore1.top25widget.Courses.isSaved(this, t.id)) showCourses() else showTrails() })
    }

    // ---------------------------------------------------------------- audio prompts settings

    private fun showVoiceSettings(): Unit = overlay { col ->
        col.addView(label("Audio prompts", Color.WHITE, 18f, true))
        col.addView(label("Voice: " + Speaker.status + ". Plays through Bluetooth headphones when connected, " +
            "otherwise the watch speaker. Volume = watch media volume.", C.MUTED, 10f))
        fun toggle(key: String, title: String, def: Boolean = true) =
            card(title, if (prefs.getBoolean(key, def)) "On" else "Off", if (prefs.getBoolean(key, def)) C.ACCENT else Color.WHITE) {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); showVoiceSettings()
            }
        col.addView(toggle("voice", "Audio prompts"))
        col.addView(toggle("voiceLaps", "Each lap / mile: time, pace, HR, power"))
        val every = prefs.getInt("voiceEvery", 0)
        col.addView(card("Time update every", if (every == 0) "Off" else "$every minutes", C.ACCENT) {
            val next = listOf(0, 5, 10, 15, 20, 30).let { it[(it.indexOf(every) + 1) % it.size] }
            prefs.edit().putInt("voiceEvery", next).apply(); showVoiceSettings()
        })
        col.addView(toggle("voiceSteps", "Workout steps (what's next)"))
        col.addView(toggle("voiceAlerts", "Target alerts (speed up / ease off)"))
        val music = prefs.getString("voiceMusic", "duck")
        col.addView(card("Music while speaking", if (music == "pause") "Pause it" else "Lower it", C.ACCENT) {
            prefs.edit().putString("voiceMusic", if (music == "pause") "duck" else "pause").apply(); showVoiceSettings()
        })
        col.addView(toggle("voiceBoost", "Louder prompts (turns volume up while speaking)"))
        col.addView(card("Test the voice", "", C.ACCENT) {
            Speaker.say("Lap 1. 8 minutes 32 seconds. Pace 8 32. Heart rate 142", force = true)
        })
        col.addView(card("Done", "", C.ACCENT) { overlayOpen = false; startBuilt = false; renderStart() })
    }

    // ---------------------------------------------------------------- data screens editor (watch)

    private fun saveScreens() {
        prefs.edit().putString("screens", screens.toString()).apply()
        builtScreen = -1
        // (sent to the phone by SettingsSync, like every other setting)
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
            col.addView(label("Build workouts in BeyondStride on the phone — they show up here.", C.DIM, 11f).apply { setPadding(0, dp(10), 0, 0) })
        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() })
    }

    @Deprecated("Deprecated in Java")
    /**
     * The bottom (back) button. With swipe-to-close turned off for Running, this is only ever the button:
     * home screen = start, during a run = lap / double-press finish, elsewhere = go back.
     */
    private fun handleBack() {
        if (wrongDevice) { finish(); return }
        when {
            screen == Screen.LIVE -> if (prefs.getBoolean("bottomBtn", true)) bottomInRun()
            editorRotary != null || overlayOpen -> { editorRotary = null; overlayOpen = false; startBuilt = false; renderStart() }
            screen == Screen.START && showSettings -> { showSettings = false; startBuilt = false; renderStart() }
            screen == Screen.SUMMARY -> backToHome()
            screen == Screen.START && !Run.isActive && prefs.getBoolean("bottomBtn", true) -> { pulse(longArrayOf(0, 400)); startRun() }
            else -> finish()
        }
    }

    /** Back gesture (swipe): navigation only — never starts or ends a run, never leaves mid-run. */
    override fun onBackPressed() {
        if (screen == Screen.LIVE && !wrongDevice) { moveTaskToBack(true); return }   // run keeps recording
        if (editorRotary != null || overlayOpen) { editorRotary = null; overlayOpen = false; startBuilt = false; renderStart(); return }
        if (screen == Screen.START && showSettings) { showSettings = false; startBuilt = false; renderStart(); return }
        if (screen == Screen.SUMMARY) { backToHome(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- bottom button

    /** Last few button events, shown in Settings. */
    private fun logKey(line: String) {
        val t = java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = prefs.getString("buttonLog", "").orEmpty().lines().filter { it.isNotBlank() }.take(4)
        prefs.edit().putString("buttonLog", (listOf("$t $line") + old).joinToString("\n")).apply()
    }

    /**
     * Bottom button: on the Running home screen, start; during a run, one press = lap (next step in a
     * workout), a quick double press = finish and save. Elsewhere (settings, lists) it's a normal back.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (wrongDevice) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            logKey("${android.view.KeyEvent.keyCodeToString(event.keyCode)} · focus=${hasWindowFocus()} · screen=$screen")
        if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK || !prefs.getBoolean("bottomBtn", true)) return super.dispatchKeyEvent(event)
        val home = screen == Screen.START && !Run.isActive && !showSettings && !overlayOpen && editorRotary == null
        if (screen != Screen.LIVE && !home) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
            if (home) { pulse(longArrayOf(0, 400)); startRun() } else bottomInRun()
        }
        return true   // consume down and up so the system doesn't also go back
    }

    private var pendingLap: Runnable? = null

    private fun bottomInRun() {
        val waiting = pendingLap
        if (waiting != null) {   // second press within the window: finish
            handler.removeCallbacks(waiting); pendingLap = null
            if (Run.state != Run.State.RUNNING) Run.resume()
            pulse(longArrayOf(0, 200, 100, 200, 100, 700))
            finishRun()
            return
        }
        if (confirmingFinish) { pulse(longArrayOf(0, 200, 100, 200, 100, 700)); finishRun(); return }
        val lap = Runnable { pendingLap = null; lapNow() }
        pendingLap = lap
        handler.postDelayed(lap, 450)
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
        trailView = TrailMapView(this).apply { visibility = View.GONE; layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        root.addView(trailView)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 400) { turnPage(if (vx < 0) 1 else -1); return true }
                return false
            }
            // Anywhere on a data screen: double-tap = lap, press and hold = finish.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!onDataScreen() || !prefs.getBoolean("gestures", true)) return false
                lapNow(); return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!onDataScreen() || !prefs.getBoolean("gestures", true)) return
                openFinish()
            }
        })
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    // ---------------------------------------------------------------- watch buttons

    /** On a run data screen (not Controls or the Finish screen). */
    private fun onDataScreen(): Boolean {
        if (screen != Screen.LIVE || confirmingFinish) return false
        return pageOrder().getOrNull(page) != 0
    }

    /** Lap now (next step in a workout), with a buzz and a short note on screen. */
    private fun lapNow() {
        if (!Run.isActive) return
        pulse(longArrayOf(0, 150))
        if (Run.hasWorkout && !Run.workoutDone) { Run.nextWorkoutStep(auto = false); flash("NEXT STEP") }
        else { Run.lap(); flash("LAP ${Run.laps.size}") }
    }

    /** Pause and show the Finish screen with a big SAVE button. */
    private fun openFinish() {
        Run.pause()
        confirmingFinish = true; page = 0
        pulse(longArrayOf(0, 500)); renderLive()
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
        if (screen != Screen.LIVE) return   // e.g. right after SAVE: the summary is showing
        if (!liveBuilt) buildLive()
        val paused = Run.state == Run.State.PAUSED || Run.state == Run.State.AUTO_PAUSED
        root.setBackgroundColor(if (paused) 0xFF2A1F05.toInt() else Color.BLACK)
        val order = pageOrder()
        if (this.page >= order.size) this.page = order.size - 1
        dots.text = order.indices.joinToString(" ") { if (it == this.page) "●" else "○" }
        val page = order[this.page]   // which screen this position shows
        fieldD.first.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)

        val stateNow = when (Run.state) { Run.State.PAUSED -> "PAUSED"; Run.State.AUTO_PAUSED -> "AUTO-PAUSED"; else -> null }
        if (page == 8) {
            pageView.visibility = View.GONE; customBox.visibility = View.GONE
            trailView.visibility = View.VISIBLE; trailView.invalidate(); return
        }
        trailView.visibility = View.GONE
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
        // Easy step under the heart-rate cap: below the zone is fine — no "speed up".
        val easyOk = Run.capApplies() && target == "hr" && Run.stepStatus == -1
        val color = if (easyOk) C.OK else when (Run.stepStatus) { 0 -> C.OK; null -> Color.WHITE; else -> if (
            (target == "pace" && Run.stepStatus == 1) || (target != "pace" && Run.stepStatus == -1)) 0xFF4FA3E0.toInt() else C.STOP }
        val hint = if (easyOk) "easy ✓" else when (Run.stepStatus) {
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
    private lateinit var trailView: TrailMapView
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
            // Big targets you can hit mid-stride.
            controlsBox.addView(button("SAVE", C.OK) { pulse(longArrayOf(0, 200, 100, 200, 100, 700)); finishRun() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setPadding(0, dp(26), 0, dp(26))
            })
            controlsBox.addView(button("RESUME", C.ACCENT) { confirmingFinish = false; Run.resume() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(0, dp(16), 0, dp(16))
            })
            controlsBox.addView(label("Discard run", C.STOP, 11f, true).apply {
                setPadding(0, dp(10), 0, 0)
                setOnClickListener { Run.discard(this@RunActivity); RunService.stop(this@RunActivity); finish() }
            })
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
        val usedTrail = Run.nav != null
        val json = Run.finish(this)
        if (usedTrail) io.github.michealsizemore1.top25widget.TrailSync.choose(this, null, false, "watch")
        Speaker.finished(json)
        RunSync.pushAll(this)
        RunService.stop(this)
        summary = json
        screen = Screen.SUMMARY
        liveBuilt = false
        root.removeAllViews()
        root.setBackgroundColor(C.BG)
        root.addView(RunViews.summary(this, json, onDone = { backToHome() }))
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, RunViews.scaled(this@RunActivity, size))
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}
