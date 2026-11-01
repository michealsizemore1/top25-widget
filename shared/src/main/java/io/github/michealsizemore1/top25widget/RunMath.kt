package io.github.michealsizemore1.top25widget

import java.util.Locale
import kotlin.math.roundToInt

/** Formatting and zone math for runs, shared by the phone and watch apps. */
object RunMath {
    const val METERS_PER_MILE = 1609.344

    /** Zone colors Z1–Z5, matching the Command Center badges. */
    val ZONE_COLORS = intArrayOf(0xFF4FA3E0.toInt(), 0xFF46C46E.toInt(), 0xFFF2C94C.toInt(), 0xFFFF8A3D.toInt(), 0xFFE5484D.toInt())
    val HR_ZONE_NAMES = listOf("Recovery", "Aerobic base", "Aerobic threshold", "Anaerobic", "Max effort")
    val POWER_ZONE_NAMES = listOf("Recovery", "Aerobic / endurance", "Tempo", "Threshold", "VO2 max")
    val POWER_ZONE_LO = doubleArrayOf(0.0, 0.55, 0.75, 0.90, 1.05)

    /** Karvonen zone floors (50/60/70/80/90% of heart-rate reserve). */
    fun hrBounds(max: Double, rest: Double): IntArray {
        val reserve = (max - rest).coerceAtLeast(1.0)
        return doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9).map { Math.round(rest + reserve * it).toInt() }.toIntArray()
    }

    fun powerBounds(cp: Int): IntArray = POWER_ZONE_LO.map { (it * cp).roundToInt() }.toIntArray()

    fun miles(m: Double) = String.format(Locale.US, "%.2f", m / METERS_PER_MILE)

    fun clock(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Pace per mile from meters/second. */
    fun pace(speed: Double): String {
        if (speed < 0.5) return "--:--"
        val sec = (METERS_PER_MILE / speed).roundToInt()
        if (sec >= 60 * 60) return "--:--"
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    fun paceFor(seconds: Int, meters: Double) = if (meters < 10 || seconds <= 0) "--:--" else pace(meters / seconds)
}
