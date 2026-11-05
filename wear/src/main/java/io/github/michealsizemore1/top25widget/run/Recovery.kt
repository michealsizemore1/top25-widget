package io.github.michealsizemore1.top25widget.run

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.michealsizemore1.top25widget.Readiness
import io.github.michealsizemore1.top25widget.ReadinessSync

/** Today's readiness on the watch (same math and data as the phone). */
object Recovery {
    fun result(ctx: Context): Readiness.Result? {
        val runs = RunStore.list(ctx).take(120).mapNotNull { RunStore.read(it) }
        return Readiness.compute(ReadinessSync.checks(ctx), ReadinessSync.nights(ctx), runs, Readiness.today())
    }
    fun checkedToday(ctx: Context) = ReadinessSync.checks(ctx).any { it.day == Readiness.today() }
}

/**
 * Morning check: 2½ minutes lying still with the chest strap. The first 30 seconds let the heart
 * rate settle; the next 2 minutes give HRV (RMSSD) and resting heart rate. Then two quick taps.
 */
class MorningCheckActivity : Activity() {
    companion object {
        const val SETTLE = 30; const val MEASURE = 120
        const val ACCENT = 0xFFD4E635.toInt(); const val MUTED = 0xFF9AA3A0.toInt(); const val CARD = 0xFF1C2421.toInt(); const val BG = 0xFF121A17.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var strap: HrStrap? = null
    private val rr = ArrayList<Int>()
    private var firstBeatAt = 0L
    private var startedAt = 0L
    private var measuring = false
    private var hrv: Readiness.Hrv? = null
    private var legs = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        setContentView(root)
        if (intent.getBooleanExtra("start", false) && !Recovery.checkedToday(this)) intro() else summary()
    }

    override fun onDestroy() { stopStrap(); super.onDestroy() }

    // ---------------------------------------------------------------- screens

    private fun page(build: (LinearLayout) -> Unit) {
        val w = resources.displayMetrics.widthPixels
        val col = column().apply { setPadding(w / 8, w / 7, w / 8, w / 4) }
        build(col)
        root.removeAllViews()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); requestFocus() }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Today's readiness, or an invitation to do the check. */
    private fun summary() = page { col ->
        col.addView(label("READINESS", MUTED, 10f, true))
        val r = try { Recovery.result(this) } catch (e: Exception) { null }
        if (r == null) {
            col.addView(label("No data yet", Color.WHITE, 16f, true))
        } else {
            col.addView(label("${r.score}", r.color, 40f, true))
            col.addView(label(r.label + (if (!r.checkedToday) " · no check today" else ""), r.color, 14f, true))
            r.parts.forEach { p ->
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; background = rounded(CARD, 12)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
                    addView(label("${p.name}  ${p.value}", if (p.pts < 0) Color.WHITE else dotColor(p.pts), 12f, true))
                    addView(label(p.note, MUTED, 10f))
                })
            }
            col.addView(label(r.advice, Color.WHITE, 11f).apply { setPadding(0, dp(6), 0, 0) })
        }
        col.addView(button(if (Recovery.checkedToday(this)) "Check again" else "Morning check · 2½ min", ACCENT, Color.BLACK) { intro() })
        val nights = ReadinessSync.nights(this)
        col.addView(label(if (nights.isEmpty()) "Sleep comes from Garmin Connect or Samsung Health via the phone app (Readiness → Connect sleep)." else
            "Sleep · last night " + (nights.lastOrNull { it.day == Readiness.today() }?.let { Readiness.hmm(it.minutes) } ?: "not in yet"),
            MUTED, 9f).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun intro() = page { col ->
        col.addView(label("MORNING CHECK", MUTED, 10f, true))
        col.addView(label("Do it right after waking, before coffee.", Color.WHITE, 13f, true))
        col.addView(label("1. Wet & put on the chest strap\n2. Lie on your back, stay still\n3. Breathe normally\n\nTakes 2½ minutes. The watch buzzes when done.", MUTED, 11f).apply {
            setPadding(0, dp(6), 0, dp(6)); gravity = Gravity.START })
        col.addView(button("Start", ACCENT, Color.BLACK) { begin() })
    }

    private lateinit var big: TextView
    private lateinit var info: TextView

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 11); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rr.clear(); firstBeatAt = 0L; startedAt = System.currentTimeMillis(); measuring = true
        Run.rrListener = { ms -> handler.post { onBeat(ms) } }
        // Use the strap connection the run screen already has, or open one just for this.
        if (!(RunService.running && Run.useStrap)) strap = HrStrap(this).also { it.start() }
        page { col ->
            col.addView(label("LIE STILL", MUTED, 10f, true))
            big = label("--", ACCENT, 40f, true); col.addView(big)
            info = label("Connecting to strap…", MUTED, 11f); col.addView(info)
            col.addView(button("Cancel", CARD, Color.WHITE) { cancel() })
        }
        handler.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) begin()
    }

    private fun onBeat(ms: Int) {
        if (!measuring) return
        if (firstBeatAt == 0L) firstBeatAt = System.currentTimeMillis()
        rr += ms
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!measuring) return
            val now = System.currentTimeMillis()
            if (firstBeatAt == 0L) {
                info.text = "Connecting to strap… " + Run.strapStatus
                if (now - startedAt > 45_000) { fail("No beat data from the strap. Wet the strap, make sure it's snug, and try again."); return }
            } else {
                val el = ((now - firstBeatAt) / 1000).toInt()
                val left = SETTLE + MEASURE - el
                big.text = String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60)
                info.text = (if (el < SETTLE) "Settling… " else "Measuring… ") + (if (Run.heartRate > 0) "${Run.heartRate} bpm" else "")
                if (left <= 0) { finishMeasure(); return }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun finishMeasure() {
        measuring = false; Run.rrListener = null; stopStrap()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buzz()
        hrv = Readiness.hrv(rr, SETTLE)
        if (hrv == null) { fail("Too many skipped beats to measure. Lie completely still and try again (wet the strap)."); return }
        askLegs()
    }

    private fun askLegs() = page { col ->
        col.addView(label("HRV ${hrv!!.rmssd.toInt()} ms · ${hrv!!.hr} bpm", ACCENT, 14f, true))
        col.addView(label("How do your legs feel?", Color.WHITE, 14f, true).apply { setPadding(0, dp(8), 0, dp(4)) })
        listOf("Sore" to 0, "OK" to 1, "Fresh" to 2).forEach { (t, v) -> col.addView(button(t, CARD, Color.WHITE) { legs = v; afterLegs() }) }
    }

    private fun afterLegs() {
        if (ReadinessSync.nights(this).any { it.day == Readiness.today() }) save(-1)
        else page { col ->
            col.addView(label("How did you sleep?", Color.WHITE, 14f, true).apply { setPadding(0, dp(8), 0, dp(4)) })
            listOf("Poor" to 0, "OK" to 1, "Good" to 2).forEach { (t, v) -> col.addView(button(t, CARD, Color.WHITE) { save(v) }) }
        }
    }

    private fun save(sleepFeel: Int) {
        val h = hrv ?: return
        ReadinessSync.addCheck(this, Readiness.Check(Readiness.today(), h.rmssd, h.hr, legs, sleepFeel, System.currentTimeMillis()))
        // Ask the phone for last night's sleep (Samsung Health); the score updates when it arrives.
        RunSync.tellPhone(this, "/cmd/sleep") { }
        summary()
        handler.postDelayed({ if (!isFinishing && !measuring) summary() }, 6000)
    }

    private fun fail(msg: String) {
        measuring = false; Run.rrListener = null; stopStrap()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        page { col ->
            col.addView(label(msg, Color.WHITE, 12f, true))
            col.addView(button("Try again", ACCENT, Color.BLACK) { begin() })
            col.addView(button("Close", CARD, Color.WHITE) { finish() })
        }
    }

    private fun cancel() { measuring = false; Run.rrListener = null; stopStrap(); summary() }

    private fun stopStrap() { handler.removeCallbacks(tick); strap?.stop(); strap = null; if (measuring) Run.rrListener = null }

    private fun buzz() = try {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    } catch (e: Exception) { }

    // ---------------------------------------------------------------- views

    private fun dotColor(pts: Int) = when { pts >= 70 -> Readiness.GREEN; pts >= 50 -> Readiness.YELLOW; else -> Readiness.RED }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
    private fun label(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, RunViews.scaled(this@MorningCheckActivity, size))
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
    private fun button(text: String, color: Int, textColor: Int, onClick: () -> Unit) = label(text, textColor, 14f, true).apply {
        background = rounded(color, 22); setPadding(0, dp(9), 0, dp(9))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }
}
