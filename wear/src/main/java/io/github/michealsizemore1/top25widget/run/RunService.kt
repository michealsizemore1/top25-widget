package io.github.michealsizemore1.top25widget.run

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.michealsizemore1.top25widget.R

/**
 * Keeps sensors running and the run ticking with the screen off. Started when the run
 * screen opens (so you can see Stryd/GPS/HR status before starting) and stopped when
 * you leave without running or finish.
 */
class RunService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 77
        @Volatile var running = false; private set
        /** The live service, so the start screen can ask it to re-pair the Stryd. */
        @Volatile var instance: RunService? = null; private set

        fun start(ctx: Context) {
            val i = Intent(ctx, RunService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RunService::class.java)) }

        fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stryd: Stryd? = null
    private var strap: HrStrap? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLocation: Location? = null
    private var gpsOn = false
    private var saveCounter = 0

    private val ticker = object : Runnable {
        override fun run() {
            Run.tick()
            Speaker.onTick()
            updateGps()
            grabStartLocation()
            try { LiveTrack.onTick(this@RunService) } catch (e: Exception) { }
            if (Run.isActive && ++saveCounter % 30 == 0) {
                try { RunStore.saveCurrent(this@RunService, Run.toJson()) } catch (e: Exception) { }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        try { goForeground() } catch (e: Exception) {
            // Android refuses a foreground service with no granted sensor permissions.
            running = false; instance = null; stopSelf(); return
        }

        // Heart rate from the watch's sensor.
        if (has(this, Manifest.permission.BODY_SENSORS)) {
            val sm = getSystemService(SensorManager::class.java)
            sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        // Barometer, for elevation gain (no permission needed).
        getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            getSystemService(SensorManager::class.java).registerListener(this, it, 1_000_000)
        }

        // Stryd footpod.
        if (Build.VERSION.SDK_INT < 31 || has(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            stryd = Stryd(this).also { it.start() }
            if (Run.useStrap) strap = HrStrap(this).also { it.start() }
        } else { Run.strydStatus = "Allow Nearby devices"; Run.strapStatus = "Allow Nearby devices" }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scores:run").apply { acquire(4 * 60 * 60 * 1000L) }

        Speaker.init(this)
        Run.onEvent = { e -> buzz(e); Speaker.onEvent(e) }
        updateGps()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        stryd?.stop(); stryd = null
        strap?.stop(); strap = null
        getSystemService(SensorManager::class.java).unregisterListener(this)
        stopGps()
        Run.onEvent = null
        try { wakeLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    /** Re-pair the Stryd (called from the start screen). */
    fun pairStryd() { stryd?.pairNew() }

    /** Re-pair the chest strap (called from the start screen). */
    fun pairStrap() { strap?.pairNew() ?: applyHrMode() }

    /** Turn the strap connection on or off to match the heart-rate setting. */
    fun applyHrMode() {
        if (Run.useStrap && strap == null &&
            (Build.VERSION.SDK_INT < 31 || has(this, Manifest.permission.BLUETOOTH_CONNECT))) {
            strap = HrStrap(this).also { it.start() }
        } else if (!Run.useStrap) {
            strap?.stop(); strap = null
            Run.strapStatus = "Off (wrist only)"
        }
    }

    // ---------------------------------------------------------------- foreground + ongoing activity

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Run in progress", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("Run")
            .setContentText("Recording")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        // Shows a run icon on the watch face to jump back into the run.
        OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_run)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Run").build())
            .build()
            .apply(applicationContext)

        var types = 0
        if (Build.VERSION.SDK_INT >= 29 && has(this, Manifest.permission.ACCESS_FINE_LOCATION))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 29 && (Build.VERSION.SDK_INT < 31 || has(this, Manifest.permission.BLUETOOTH_CONNECT)))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 34 && has(this, Manifest.permission.BODY_SENSORS))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        ServiceCompat.startForeground(this, NOTIF_ID, builder.build(), types)
    }

    // ---------------------------------------------------------------- heart rate

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            event.values.firstOrNull()?.takeIf { it in 300f..1100f }?.let { Run.onPressure(it) }
            return
        }
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values.firstOrNull()?.toInt() ?: 0
            if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW && bpm in 30..230) {
                Run.wristLastUpdate = System.currentTimeMillis()
                if (!Run.strapActive) Run.heartRate = bpm   // the chest strap wins when it's sending
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- GPS

    private var startLocTriedFor = 0L

    /** One quick location reading at the start of each run (even with GPS off), for weather/AQI. */
    @SuppressLint("MissingPermission")
    private fun grabStartLocation() {
        if (Run.state != Run.State.RUNNING || Run.startLoc != null || startLocTriedFor == Run.startMillis) return
        if (!has(this, Manifest.permission.ACCESS_FINE_LOCATION) && !has(this, Manifest.permission.ACCESS_COARSE_LOCATION)) return
        startLocTriedFor = Run.startMillis
        val forRun = Run.startMillis
        try {
            val lm = getSystemService(LocationManager::class.java)
            val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }.maxByOrNull { it.time }
                ?.let { Run.startLoc = doubleArrayOf(it.latitude, it.longitude); return }
            val p = providers.firstOrNull { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } } ?: return
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, mainExecutor) { loc ->
                if (loc != null && Run.startMillis == forRun && Run.startLoc == null) Run.startLoc = doubleArrayOf(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) { }
    }

    /** GPS runs only when it's the pace source or a GPS track is wanted. */
    private fun updateGps() {
        val want = (Run.source == Run.Source.GPS || Run.recordGpsTrack || Run.nav != null || LiveTrack.enabled(this) || Run.usingGpsFallback) &&
            has(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (want && !gpsOn) startGps() else if (!want && gpsOn) stopGps()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { Run.gpsStatus = "Location is off"; return }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        gpsOn = true
        Run.gpsStatus = "Searching…"
        Run.gpsFix = false
    }

    private fun stopGps() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        gpsOn = false; lastLocation = null
        Run.gpsFix = false
        Run.gpsStatus = "Off"
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 25f) { Run.gpsStatus = "Weak signal"; return }
        Run.gpsStatus = "Ready"
        val prev = lastLocation
        val moved = if (prev == null) 0.0 else prev.distanceTo(loc).toDouble().let { if (it > 60) 0.0 else it }
        lastLocation = loc
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) Run.gpsBearing = loc.bearing
        Run.onGps(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else 0.0, moved)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { Run.gpsStatus = "Location is off" }

    // ---------------------------------------------------------------- haptics

    private fun buzz(event: String) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val pattern = when (event) {
            "lap", "lap_auto" -> longArrayOf(0, 250, 120, 250)
            "autopause" -> longArrayOf(0, 120)
            "autoresume" -> longArrayOf(0, 120, 80, 120)
            "step" -> longArrayOf(0, 300, 120, 300, 120, 300)          // new workout step
            "offroute" -> longArrayOf(0, 600, 150, 600, 150, 600)      // off the trail
            "onroute" -> longArrayOf(0, 150, 100, 150)
            "turn_left", "turn_right" -> longArrayOf(0, 200, 100, 200)
            "turnaround", "trail_done" -> longArrayOf(0, 500, 150, 500)
            "workout_done" -> longArrayOf(0, 700, 200, 700)
            "push" -> longArrayOf(0, 90, 90, 90, 90, 90)                // speed up / work harder
            "ease" -> longArrayOf(0, 600)                               // ease off
            "hrcap" -> longArrayOf(0, 600, 200, 600)                    // over the easy-day heart-rate cap
            "hrcap_ok" -> longArrayOf(0, 120)
            "stryd_lost" -> longArrayOf(0, 300, 150, 300, 150, 300)
            else -> longArrayOf(0, 200)
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
