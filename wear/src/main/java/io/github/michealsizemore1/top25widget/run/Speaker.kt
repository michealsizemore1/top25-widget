package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import io.github.michealsizemore1.top25widget.Workouts
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Spoken run updates. Uses the watch's text-to-speech; plays through Bluetooth headphones when
 * connected (otherwise the watch speaker) and lowers music while speaking.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set
    @Volatile var status = "Starting…"; private set
    private lateinit var app: Context
    private fun p() = app.getSharedPreferences("run", 0)

    // Settings (Running → Settings → Audio prompts).
    fun on() = p().getBoolean("voice", true)
    fun laps() = p().getBoolean("voiceLaps", true)
    fun everyMinutes() = p().getInt("voiceEvery", 0)
    fun alerts() = p().getBoolean("voiceAlerts", true)
    fun steps() = p().getBoolean("voiceSteps", true)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (tts != null) return
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)   // ducks music
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(progress)
                ready = true; status = "Ready"
            } else { status = "No voice on this watch (install a text-to-speech engine)" }
        }
    }

    // ---- Making prompts heard over music: lower (or pause) the music and turn the voice up while speaking.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pending = 0
    private const val PAUSE_MS = 450L      // pause between metrics
    private var focus: android.media.AudioFocusRequest? = null
    private var savedVolume = -1

    private fun audio() = app.getSystemService(android.media.AudioManager::class.java)

    private fun beginSpeaking() {
        val am = audio() ?: return
        if (focus == null) {
            val pause = p().getString("voiceMusic", "duck") == "pause"
            focus = android.media.AudioFocusRequest.Builder(
                if (pause) android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(false).build()
            try { am.requestAudioFocus(focus!!) } catch (e: Exception) { }
            if (p().getBoolean("voiceBoost", true)) try {
                val s = android.media.AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(s); val cur = am.getStreamVolume(s)
                val loud = (max * 0.85).toInt().coerceAtLeast(1)
                if (cur < loud) { savedVolume = cur; am.setStreamVolume(s, loud, 0) }
            } catch (e: Exception) { }
        }
    }

    private fun doneSpeaking() {
        // A short pause first, so back-to-back prompts don't make the music bounce.
        main.postDelayed({
            if (pending > 0) return@postDelayed
            val am = audio() ?: return@postDelayed
            if (savedVolume >= 0) { try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVolume, 0) } catch (e: Exception) { }; savedVolume = -1 }
            focus?.let { try { am.abandonAudioFocusRequest(it) } catch (e: Exception) { } }
            focus = null
        }, 400)
    }

    private val progress = object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { pending = (pending - 1).coerceAtLeast(0); doneSpeaking() }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { pending = (pending - 1).coerceAtLeast(0); doneSpeaking() }
        override fun onError(id: String?, code: Int) { pending = (pending - 1).coerceAtLeast(0); doneSpeaking() }
    }

    fun say(text: String, force: Boolean = false) {
        if (!force && !on()) return
        if (!ready) return
        // Each metric is its own sentence ("Mile 3. Pace 9:12. Heart rate 140."): speak them one by one
        // with a short pause between, instead of one run-on sentence.
        val parts = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        main.post {
            pending += parts.size * 2 - 1
            beginSpeaking()
            val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
            // Start a moment after the music dips, so the first word isn't lost.
            main.postDelayed({
                val t = tts
                parts.forEachIndexed { i, part ->
                    if (i > 0 && t?.playSilentUtterance(PAUSE_MS, TextToSpeech.QUEUE_ADD, "gap${System.nanoTime()}") != TextToSpeech.SUCCESS) {
                        pending = (pending - 1).coerceAtLeast(0)
                    }
                    if (t?.speak(part, TextToSpeech.QUEUE_ADD, params, "run${System.nanoTime()}") != TextToSpeech.SUCCESS) {
                        pending = (pending - 1).coerceAtLeast(0)
                    }
                }
                if (pending == 0) doneSpeaking()
            }, 250)
        }
    }

    // ---------------------------------------------------------------- phrasing

    fun duration(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour" + if (h > 1) "s" else ""
        if (m > 0) parts += "$m minute" + if (m != 1) "s" else ""
        if (h == 0 && s > 0) parts += "$s second" + if (s != 1) "s" else ""
        return parts.joinToString(" ").ifBlank { "0 seconds" }
    }

    /** "8 32" reads naturally as a pace. */
    fun pace(secPerMile: Int?): String {
        if (secPerMile == null || secPerMile <= 0 || secPerMile >= 3600) return "no pace"
        val m = secPerMile / 60; val s = secPerMile % 60
        return if (s == 0) "$m flat" else "$m ${if (s < 10) "oh $s" else "$s"}"
    }

    fun miles(meters: Double) = String.format(Locale.US, "%.2f", meters / Run.METERS_PER_MILE) + " miles"

    private fun secPerMile(sec: Int, meters: Double) = if (meters < 10 || sec <= 0) null else (sec * Run.METERS_PER_MILE / meters).roundToInt()

    // ---------------------------------------------------------------- what gets spoken

    fun onEvent(event: String) {
        if (!on()) return
        when (event) {
            "start" -> say(if (Run.hasWorkout) "Workout started. " + stepText() else "Run started")
            "pause" -> say("Paused")
            "resume" -> say("Resumed")
            "autopause" -> say("Auto paused")
            "autoresume" -> say("Resumed")
            "lap", "lap_auto" -> if (laps()) lapText()?.let { say(it) }
            "mile" -> if (p().getBoolean("voiceMiles", true)) Run.lastMile?.let { (n, sec, hr) ->
                say("Mile $n. " + duration(sec) + ". Pace " + pace(sec) + (if (hr > 0) ". Heart rate $hr" else "") + ".")
            }
            "step" -> if (steps()) {
                // A step just ended: its numbers, then what's next.
                val done = lapText(short = true)
                say((done?.let { "$it. " } ?: "") + "Next, " + stepText())
            }
            "workout_done" -> if (steps()) say("Workout complete. Keep running or finish when ready.")
            "offroute" -> say("Off route. " + (Run.nav?.let { "Trail is ${(it.offBy * 3.28084 / 10).toInt() * 10} feet away" } ?: ""))
            "onroute" -> say("Back on route")
            "turn_left" -> say("Sharp left ahead")
            "turn_right" -> say("Sharp right ahead")
            "turnaround" -> say("Turnaround point. Head back the way you came.")
            "trail_done" -> say("Trail complete. " + miles(Run.distance) + " in " + duration(Run.movingSeconds))
            "push" -> if (alerts()) say(targetText(push = true))
            "ease" -> if (alerts()) say(targetText(push = false))
            "stryd_lost" -> say("Stryd signal lost. Using GPS for distance.", force = true)
            "stryd_back" -> say("Stryd connected.")
            "hrcap" -> if (alerts()) say("Heart rate ${Run.heartRate}. Ease up. Stay under ${Run.hrCap}.")
            "hrcap_ok" -> if (alerts()) say("Back under ${Run.hrCap}. Nice.")
        }
    }

    /** Called once a second by the service; speaks the time-based update. */
    fun onTick() {
        val every = everyMinutes()
        if (!on() || every <= 0 || Run.state != Run.State.RUNNING) return
        val t = Run.movingSeconds
        if (t > 0 && t % (every * 60) == 0) {
            val parts = mutableListOf(miles(Run.distance), duration(t), "average pace " + pace(secPerMile(t, Run.distance)))
            if (Run.avgHr > 0) parts += "heart rate ${Run.heartRate.takeIf { it > 0 } ?: Run.avgHr}"
            say(parts.joinToString(". "))
        }
    }

    private fun lapText(short: Boolean = false): String? {
        val l = Run.laps.lastOrNull() ?: return null
        val parts = mutableListOf<String>()
        parts += (if (l.name.isNotBlank()) l.name else "Lap ${l.number}")
        parts += duration(l.seconds)
        parts += "pace " + pace(secPerMile(l.seconds, l.meters))
        if (!short) {
            if (l.avgHr > 0) parts += "heart rate ${l.avgHr}"
            if (l.avgPower > 0) parts += "${l.avgPower} watts"
            parts += "total " + miles(Run.distance)
        }
        return parts.joinToString(". ")
    }

    private fun stepText(): String {
        val s = Run.currentStep ?: return ""
        val kind = Workouts.kindName(s.optString("kind"))
        val len = when (s.optString("dur")) {
            "time" -> duration(s.optInt("value"))
            "dist" -> s.optInt("value").let { m -> if (m < 1600) "$m meters" else miles(m.toDouble()) }
            else -> "until you press lap"
        }
        val zone = s.optInt("zone").takeIf { it in 1..5 }
        val target = when (s.optString("target")) {
            "hr" -> if (zone != null) "heart rate zone $zone" else "heart rate ${s.optInt("lo")} to ${s.optInt("hi")}"
            "power" -> if (zone != null) "power zone $zone" else "${s.optInt("lo")} to ${s.optInt("hi")} watts"
            "pace" -> "pace ${pace(s.optInt("lo"))} to ${pace(s.optInt("hi"))}"
            else -> ""
        }
        return listOf("$kind $len", target).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun targetText(push: Boolean): String {
        val s = Run.currentStep ?: return if (push) "Speed up" else "Ease off"
        val v = Run.stepValue()
        return when (s.optString("target")) {
            "hr" -> (if (push) "Heart rate low" else "Heart rate high") + (v?.let { ", $it" } ?: "") + if (push) ". Pick it up" else ". Ease off"
            "power" -> (if (push) "Power low" else "Power high") + (v?.let { ", $it watts" } ?: "") + if (push) ". Push" else ". Ease off"
            "pace" -> (if (push) "Too slow" else "Too fast") + (v?.let { ", ${pace(it)}" } ?: "")
            else -> if (push) "Speed up" else "Ease off"
        }
    }

    /** Spoken when a run is saved. */
    fun finished(json: org.json.JSONObject) {
        if (!on()) return
        val sec = json.optInt("movingSeconds"); val m = json.optDouble("distance")
        val parts = mutableListOf("Run saved", miles(m), duration(sec), "average pace " + pace(secPerMile(sec, m)))
        json.optInt("avgHr").takeIf { it > 0 }?.let { parts += "average heart rate $it" }
        say(parts.joinToString(". "))
    }
}
