package io.github.michealsizemore1.top25widget

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Keeps the Running settings identical on the phone and the watch: a change on either side is
 * sent to the other (whenever they're connected — Bluetooth or over the internet).
 * Both apps keep these keys, with the same names and types, in their own settings file.
 */
object SettingsSync {
    const val PATH = "/runsettings"

    enum class T { BOOL, INT, FLOAT, STRING }
    val KEYS: Map<String, Pair<T, Any>> = linkedMapOf(
        "source" to (T.STRING to "stryd"),
        "autoPause" to (T.BOOL to true),
        "track" to (T.BOOL to false),
        "useStrap" to (T.BOOL to true),
        "hrMax" to (T.FLOAT to 185f),
        "hrRest" to (T.FLOAT to 43.5f),
        "cp" to (T.INT to 333),
        "cal" to (T.FLOAT to 1f),
        "screens" to (T.STRING to ""),
        "keepOn" to (T.BOOL to true),
        "bottomBtn" to (T.BOOL to true),
        "gestures" to (T.BOOL to true),
        "voice" to (T.BOOL to true),
        "voiceLaps" to (T.BOOL to true),
        "voiceEvery" to (T.INT to 0),
        "voiceSteps" to (T.BOOL to true),
        "voiceAlerts" to (T.BOOL to true),
        "trailArea" to (T.STRING to ""),      // "" = near me, else "label|lat|lon"
        "trailRadius" to (T.INT to 4),        // miles
        "trailSort" to (T.STRING to "near"),  // near | long
    )

    @Volatile private var applying = false
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = HashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()

    /**
     * Watches this app's settings file and sends any change of a shared setting to the other device
     * (bunched up for a second so several quick changes go together).
     */
    fun watch(ctx: Context, prefsName: String, me: String) {
        val app = ctx.applicationContext
        if (listeners.containsKey(prefsName)) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (applying || key == null || key !in KEYS) return@OnSharedPreferenceChangeListener
            p.edit().putLong("settingsAt", System.currentTimeMillis()).apply()
            handler.removeCallbacksAndMessages(prefsName)
            handler.postAtTime({ push(app, prefsName, me) }, prefsName, android.os.SystemClock.uptimeMillis() + 1000)
        }
        listeners[prefsName] = l   // keep a strong reference (SharedPreferences holds listeners weakly)
        app.getSharedPreferences(prefsName, 0).registerOnSharedPreferenceChangeListener(l)
    }

    fun push(ctx: Context, prefsName: String, me: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val p = app.getSharedPreferences(prefsName, 0)
                val req = PutDataMapRequest.create(PATH).apply {
                    KEYS.forEach { (k, td) ->
                        if (!p.contains(k)) return@forEach   // only settings actually set on this device
                        val (t, def) = td
                        when (t) {
                            T.BOOL -> dataMap.putBoolean(k, p.getBoolean(k, def as Boolean))
                            T.INT -> dataMap.putInt(k, p.getInt(k, def as Int))
                            T.FLOAT -> dataMap.putFloat(k, p.getFloat(k, def as Float))
                            T.STRING -> dataMap.putString(k, p.getString(k, def as String) ?: "")
                        }
                    }
                    dataMap.putString("from", me)
                    dataMap.putLong("at", p.getLong("settingsAt", 0L))
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** Settings from the other device: take them if they're newer than ours. Returns true if applied. */
    fun apply(ctx: Context, prefsName: String, me: String, m: DataMap): Boolean {
        if (m.getString("from") == me) return false
        val p = ctx.getSharedPreferences(prefsName, 0)
        val at = m.getLong("at")
        if (at <= p.getLong("settingsAt", 0L)) return false
        applying = true
        try {
            val e = p.edit()
            KEYS.forEach { (k, td) ->
                if (!m.containsKey(k)) return@forEach
                when (td.first) {
                    T.BOOL -> e.putBoolean(k, m.getBoolean(k))
                    T.INT -> e.putInt(k, m.getInt(k))
                    T.FLOAT -> e.putFloat(k, m.getFloat(k))
                    T.STRING -> m.getString(k)?.let { if (k != "screens" || it.isNotBlank()) e.putString(k, it) }
                }
            }
            e.putLong("settingsAt", at)
            e.commit()
        } finally { applying = false }
        return true
    }
}
