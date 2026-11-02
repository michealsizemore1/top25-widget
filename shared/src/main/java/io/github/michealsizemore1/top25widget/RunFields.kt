package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Customizable run data screens (like Gear Tracker II): each screen has a layout and a metric in
 * every spot. Built on the phone, shown on the watch during a run.
 *
 * Screen JSON: { "layout": "6", "fields": ["time","hr","avgHr","pace","avgPace","distance"] }
 */
object RunFields {
    /** key → name shown in the phone's picker and as the label above the number on the watch. */
    val METRICS: List<Pair<String, String>> = listOf(
        "time" to "Time", "elapsed" to "Elapsed time", "clock" to "Time of day",
        "distance" to "Distance", "pace" to "Pace", "avgPace" to "Avg pace",
        "hr" to "Heart rate", "avgHr" to "Avg HR", "maxHr" to "Max HR", "hrZone" to "HR zone", "hrr" to "% HR reserve",
        "power" to "Power (3s)", "power10" to "Power (10s)", "avgPower" to "Avg power", "np" to "Normalized power",
        "pctCp" to "% of CP", "powerZone" to "Power zone",
        "cadence" to "Cadence", "avgCadence" to "Avg cadence", "stride" to "Stride",
        "lapTime" to "Lap time", "lapDist" to "Lap distance", "lapPace" to "Lap pace", "lapHr" to "Lap HR",
        "lapPower" to "Lap power", "lapNum" to "Lap #", "lastLap" to "Last lap time",
        "stress" to "Stress", "intensity" to "Intensity",
    )
    fun name(key: String) = METRICS.firstOrNull { it.first == key }?.second ?: key

    /** Layouts: id → (name, number of fields, rows as counts per row). */
    data class Layout(val id: String, val name: String, val rows: List<Int>)
    val LAYOUTS = listOf(
        Layout("6", "Top · 2×2 grid · bottom (6)", listOf(1, 2, 2, 1)),
        Layout("4", "Top · 2 across · bottom (4)", listOf(1, 2, 1)),
        Layout("5", "Top · 2 across · 2 across (5)", listOf(1, 2, 2)),
        Layout("3", "Three rows (3)", listOf(1, 1, 1)),
        Layout("2", "Two rows (2)", listOf(1, 1)),
        Layout("1", "One big number (1)", listOf(1)),
    )
    fun layout(id: String) = LAYOUTS.firstOrNull { it.id == id } ?: LAYOUTS[0]
    fun size(id: String) = layout(id).rows.sum()

    private fun screen(layout: String, vararg f: String) = JSONObject().put("layout", layout).put("fields", JSONArray(f.toList()))

    /** Starting screens: yours from Gear Tracker II first, then heart rate, power, lap, averages. */
    fun defaults(): JSONArray = JSONArray(listOf(
        screen("6", "time", "hr", "avgHr", "pace", "avgPace", "distance"),
        screen("4", "hr", "hrZone", "lapHr", "avgHr"),
        screen("4", "power", "power10", "lapPower", "pctCp"),
        screen("4", "lapTime", "lapDist", "lapPace", "lastLap"),
        screen("5", "stress", "np", "intensity", "cadence", "stride"),
    ))
}
