package io.github.michealsizemore1.top25widget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where to look for trails (near me, or a place like "Boise"), how far, and how to sort.
 * Shared settings, so the phone and the watch always search the same way.
 */
object TrailSearch {
    data class Place(val label: String, val lat: Double, val lon: Double) {
        fun encode() = "$label|$lat|$lon"
    }
    val RADII = listOf(4, 8, 15, 25)

    private fun p(ctx: Context, prefs: String) = ctx.getSharedPreferences(prefs, 0)

    fun area(ctx: Context, prefs: String): Place? = p(ctx, prefs).getString("trailArea", "")?.split("|")
        ?.takeIf { it.size == 3 }?.let { Place(it[0], it[1].toDoubleOrNull() ?: return null, it[2].toDoubleOrNull() ?: return null) }
    fun radiusMi(ctx: Context, prefs: String) = p(ctx, prefs).getInt("trailRadius", 4)
    fun sortLongest(ctx: Context, prefs: String) = sortMode(ctx, prefs) == "long"
    /** near | short | long */
    fun sortMode(ctx: Context, prefs: String) = p(ctx, prefs).getString("trailSort", "near") ?: "near"
    fun setSort(ctx: Context, prefs: String, me: String, mode: String) = set(ctx, prefs, me) { it.putString("trailSort", mode) }
    val SORTS = listOf("near" to "Nearest", "short" to "Shortest", "long" to "Longest")

    /** Trail length filter (miles): label, min, max. */
    val LENGTHS = listOf(Triple("Any length", 0.0, 1e9), Triple("Under 2 mi", 0.0, 2.0), Triple("2–5 mi", 2.0, 5.0),
        Triple("5–10 mi", 5.0, 10.0), Triple("10+ mi", 10.0, 1e9))
    fun lengthBand(ctx: Context, prefs: String) = p(ctx, prefs).getInt("trailLen", 0).coerceIn(0, LENGTHS.size - 1)
    fun setLength(ctx: Context, prefs: String, me: String, band: Int) = set(ctx, prefs, me) { it.putInt("trailLen", band) }
    /** Keep trails in the length band (out & back counts the one-way length). */
    fun byLength(list: List<Trail>, band: Int) = LENGTHS[band].let { (_, lo, hi) -> list.filter { it.lengthM / 1609.344 in lo..hi } }
    fun sort(list: List<Trail>, mode: String) = when (mode) {
        "long" -> list.sortedByDescending { it.lengthM }
        "short" -> list.sortedBy { it.lengthM }
        else -> list.sortedBy { it.distToStartM }
    }

    private fun set(ctx: Context, prefs: String, me: String, f: (android.content.SharedPreferences.Editor) -> Unit) {
        val e = p(ctx, prefs).edit(); f(e); e.putLong("settingsAt", System.currentTimeMillis()).apply()
        SettingsSync.push(ctx.applicationContext, prefs, me)
    }
    fun setArea(ctx: Context, prefs: String, me: String, pl: Place?) = set(ctx, prefs, me) { it.putString("trailArea", pl?.encode() ?: "") }
    fun setRadius(ctx: Context, prefs: String, me: String, mi: Int) = set(ctx, prefs, me) { it.putInt("trailRadius", mi) }
    fun setSortLongest(ctx: Context, prefs: String, me: String, v: Boolean) = set(ctx, prefs, me) { it.putString("trailSort", if (v) "long" else "near") }

    /** Places matching a name ("Boise", "Boise, Idaho", "McCall ID"), from Open-Meteo's free geocoder. */
    fun geocode(query: String): List<Place> {
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        var name = parts[0]; var hint = parts.getOrNull(1) ?: ""
        // "Boise ID" → name Boise, hint ID
        if (hint.isEmpty()) Regex("^(.*\\S)\\s+([A-Za-z]{2})$").find(name)?.let { m -> name = m.groupValues[1]; hint = m.groupValues[2] }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" + URLEncoder.encode(name, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000 }
        val a = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("results") ?: return emptyList()
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        fun matches(o: JSONObject): Boolean {
            if (hint.isEmpty()) return true
            val admin = o.optString("admin1"); val country = o.optString("country"); val cc = o.optString("country_code")
            return admin.startsWith(hint, true) || country.startsWith(hint, true) || cc.equals(hint, true) ||
                STATES[hint.uppercase()]?.equals(admin, true) == true
        }
        val us = all.filter { it.optString("country_code") == "US" }
        val ordered = all.filter { matches(it) }.ifEmpty { all }.sortedByDescending { (if (it.optString("country_code") == "US") 1 else 0) }
        return ordered.map { o ->
            val admin = o.optString("admin1")
            Place(o.optString("name") + (if (admin.isNotBlank()) ", $admin" else ""), o.getDouble("latitude"), o.getDouble("longitude"))
        }.distinctBy { it.label }.take(if (us.isEmpty()) 6 else 6)
    }

    private val STATES = mapOf("AL" to "Alabama","AK" to "Alaska","AZ" to "Arizona","AR" to "Arkansas","CA" to "California",
        "CO" to "Colorado","CT" to "Connecticut","DE" to "Delaware","FL" to "Florida","GA" to "Georgia","HI" to "Hawaii",
        "ID" to "Idaho","IL" to "Illinois","IN" to "Indiana","IA" to "Iowa","KS" to "Kansas","KY" to "Kentucky",
        "LA" to "Louisiana","ME" to "Maine","MD" to "Maryland","MA" to "Massachusetts","MI" to "Michigan","MN" to "Minnesota",
        "MS" to "Mississippi","MO" to "Missouri","MT" to "Montana","NE" to "Nebraska","NV" to "Nevada","NH" to "New Hampshire",
        "NJ" to "New Jersey","NM" to "New Mexico","NY" to "New York","NC" to "North Carolina","ND" to "North Dakota",
        "OH" to "Ohio","OK" to "Oklahoma","OR" to "Oregon","PA" to "Pennsylvania","RI" to "Rhode Island",
        "SC" to "South Carolina","SD" to "South Dakota","TN" to "Tennessee","TX" to "Texas","UT" to "Utah","VT" to "Vermont",
        "VA" to "Virginia","WA" to "Washington","WV" to "West Virginia","WI" to "Wisconsin","WY" to "Wyoming")

    /** Sorts trails the chosen way. */
    fun sort(list: List<Trail>, longest: Boolean) = if (longest) list.sortedByDescending { it.lengthM } else list.sortedBy { it.distToStartM }
}
