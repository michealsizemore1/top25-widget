package io.github.michealsizemore1.beyondstride

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
        "cpAuto" to (T.STRING to "ask"),
        "menuStyle" to (T.STRING to "cards"),
        "bgStyle" to (T.STRING to "light"),   // screen background: light (tiles stand out) or dark // settings menus: cards (icon · value · ›) or classic      // critical power from your runs: ask | auto (raises only) | off
        "cal" to (T.FLOAT to 1f),
        "screens" to (T.STRING to ""),
        "textScale" to (T.FLOAT to 1.25f),    // watch text size: 1 normal, 1.25 large, 1.5 extra large
        "keepOn" to (T.BOOL to true),
        "bottomBtn" to (T.BOOL to true),
        "gestures" to (T.BOOL to true),
        "voice" to (T.BOOL to true),
        "voiceLaps" to (T.BOOL to true),
        "voiceEvery" to (T.INT to 0),
        "voiceSteps" to (T.BOOL to true),
        "voiceAlerts" to (T.BOOL to true),
        "voiceMiles" to (T.BOOL to true),     // mile alerts during easy (zone 2) workouts
        "voiceMusic" to (T.STRING to "duck"), // while speaking: duck (lower music) | pause
        "voiceBoost" to (T.BOOL to true),     // raise volume while speaking
        "trailArea" to (T.STRING to ""),      // "" = near me, else "label|lat|lon"
        "trailRadius" to (T.INT to 4),        // miles
        "trailSort" to (T.STRING to "near"),  // near | short | long
        "trailLen" to (T.INT to 0),           // length filter: index into TrailSearch.LENGTHS
        "trailMapTiles" to (T.BOOL to true),  // topo map under the trail on the watch
        "hrCap" to (T.STRING to "easy"),      // easy-day heart-rate cap: off | easy | all (easy days + free runs)
        "hrCapZone" to (T.INT to 2),          // stay in this zone or below
        "liveOn" to (T.BOOL to false),        // LiveTrack
        "liveDb" to (T.STRING to ""),         // Firebase Realtime Database URL
        "liveId" to (T.STRING to ""),         // private tracking id (part of the link)
        "liveName" to (T.STRING to ""),
        "liveStopMin" to (T.INT to 10),       // alert after this many minutes not moving (0 = off)
        "liveContacts" to (T.STRING to ""),   // numbers to text (the watch texts them itself if the phone can't)
        "liveSms" to (T.BOOL to true),
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

    private fun valueOf(p: SharedPreferences, k: String, t: T, def: Any): String = when (t) {
        T.BOOL -> p.getBoolean(k, def as Boolean).toString()
        T.INT -> p.getInt(k, def as Int).toString()
        T.FLOAT -> p.getFloat(k, def as Float).toString()
        T.STRING -> p.getString(k, def as String) ?: ""
    }

    /**
     * Sends this device's settings. Each setting carries its own "changed at" time, so a change to one
     * setting on one device never blocks a different setting changed on the other.
     */
    fun push(ctx: Context, prefsName: String, me: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val p = app.getSharedPreferences(prefsName, 0)
                val now = System.currentTimeMillis()
                val e = p.edit()
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
                        // Changed here since the last sync? Then it's new as of now.
                        val v = valueOf(p, k, t, def)
                        var kat = p.getLong("at_$k", 0L)
                        if (p.getString("syn_$k", null) != v || kat == 0L) { kat = now; e.putLong("at_$k", kat).putString("syn_$k", v) }
                        dataMap.putLong("at_$k", kat)
                    }
                    dataMap.putString("from", me)
                    dataMap.putLong("at", p.getLong("settingsAt", 0L))
                }.asPutDataRequest().setUrgent()
                applying = true
                try { e.commit() } finally { applying = false }
                Tasks.await(Wearable.getDataClient(app).putDataItem(req))
            } catch (e: Exception) { }
        }.start()
    }

    /** Settings from the other device: take each one that changed there more recently. Returns true if any applied. */
    fun apply(ctx: Context, prefsName: String, me: String, m: DataMap): Boolean {
        if (m.getString("from") == me) return false
        val p = ctx.getSharedPreferences(prefsName, 0)
        val legacyAt = m.getLong("at")
        var any = false
        applying = true
        try {
            val e = p.edit()
            KEYS.forEach { (k, td) ->
                if (!m.containsKey(k)) return@forEach
                val (t, def) = td
                val inAt = if (m.containsKey("at_$k")) m.getLong("at_$k") else legacyAt
                val take = when {
                    !p.contains(k) -> true                                         // never set here
                    p.getString("syn_$k", null) == null -> inAt > p.getLong("settingsAt", 0L)   // first sync with per-setting times
                    p.getString("syn_$k", null) != valueOf(p, k, t, def) -> false // changed here, not sent yet — ours is newer
                    else -> inAt > p.getLong("at_$k", 0L)
                }
                if (!take) return@forEach
                val sv: String
                when (t) {
                    T.BOOL -> { val v = m.getBoolean(k); e.putBoolean(k, v); sv = v.toString() }
                    T.INT -> { val v = m.getInt(k); e.putInt(k, v); sv = v.toString() }
                    T.FLOAT -> { val v = m.getFloat(k); e.putFloat(k, v); sv = v.toString() }
                    T.STRING -> {
                        val v = m.getString(k) ?: ""
                        if (k == "screens" && v.isBlank()) return@forEach
                        e.putString(k, v); sv = v
                    }
                }
                e.putLong("at_$k", inAt).putString("syn_$k", sv)
                any = true
            }
            if (any) e.putLong("settingsAt", maxOf(legacyAt, p.getLong("settingsAt", 0L)))
            e.commit()
        } finally { applying = false }
        return any
    }
}
