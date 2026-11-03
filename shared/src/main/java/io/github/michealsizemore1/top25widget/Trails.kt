package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A trail from OpenStreetMap: a line of points with a few facts about it. */
data class Trail(
    val id: String,
    val name: String,
    val points: List<DoubleArray>,       // [lat, lon]
    val lengthM: Double,
    val loop: Boolean,
    val surface: String,
    val sac: String,                     // OSM sac_scale (hiking difficulty), if any
    var elevations: DoubleArray? = null, // meters, one per sampled point (see Trails.profilePoints)
    var distToStartM: Double = 0.0,
) {
    /** Total climb in meters (from the elevation profile, smoothed a little). */
    val climbM: Double get() {
        val e = elevations ?: return 0.0
        var up = 0.0
        for (i in 1 until e.size) { val d = e[i] - e[i - 1]; if (d > 1.0) up += d }
        return up
    }

    /** easy / moderate / hard — from OSM's difficulty tag when present, else from length and climb. */
    val difficulty: String get() {
        if (sac.startsWith("demanding") || sac.startsWith("alpine") || sac.startsWith("difficult")) return "Hard"
        if (sac == "mountain_hiking") return "Moderate"
        val miles = lengthM / 1609.344
        val ftPerMile = if (miles > 0) climbM * 3.28084 / miles else 0.0
        return when {
            ftPerMile > 250 || miles > 12 -> "Hard"
            ftPerMile > 100 || miles > 6 -> "Moderate"
            else -> "Easy"
        }
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("length", lengthM); put("loop", loop)
        put("surface", surface); put("sac", sac)
        put("points", JSONArray(points.map { JSONArray(listOf(it[0], it[1])) }))
        elevations?.let { put("elev", JSONArray(it.toList())) }
    }

    companion object {
        fun fromJson(o: JSONObject): Trail {
            val pts = o.getJSONArray("points").let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); doubleArrayOf(p.getDouble(0), p.getDouble(1)) } }
            return Trail(o.optString("id"), o.optString("name"), pts, o.optDouble("length"), o.optBoolean("loop"),
                o.optString("surface"), o.optString("sac"),
                o.optJSONArray("elev")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } })
        }
    }
}

object Geo {
    private const val R = 6371000.0
    fun dist(a: DoubleArray, b: DoubleArray) = dist(a[0], a[1], b[0], b[1])
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }
    /** Compass bearing in degrees from a to b. */
    fun bearing(a: DoubleArray, b: DoubleArray): Double {
        val p1 = Math.toRadians(a[0]); val p2 = Math.toRadians(b[0]); val dl = Math.toRadians(b[1] - a[1])
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    fun length(pts: List<DoubleArray>) = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]) }
}

/** Finding trails near you (OpenStreetMap) and their elevation (Open-Meteo). Works over the watch's own LTE. */
object Trails {
    private val SERVERS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    /** Named trails, footpaths, and hiking/running routes within [radiusM] of a point. */
    fun query(lat: Double, lon: Double, radiusM: Int) = """
        [out:json][timeout:60];
        (
          relation["route"~"hiking|foot|running|walking"](around:$radiusM,$lat,$lon);
          way["highway"~"path|footway|track|bridleway|cycleway"]["name"](around:$radiusM,$lat,$lon);
        );
        out tags geom qt;
    """.trimIndent()

    fun fetchNearby(lat: Double, lon: Double, radiusM: Int = 6500): List<Trail> {
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                val conn = (URL(server).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 90_000
                    setRequestProperty("User-Agent", "BeyondStride/1.0 (personal Wear OS running app)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query(lat, lon, radiusM), "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) throw RuntimeException("Trail server busy (${conn.responseCode})")
                val text = conn.inputStream.bufferedReader().readText()
                return parse(JSONObject(text), lat, lon)
            } catch (e: Exception) { last = e }
        }
        throw last ?: RuntimeException("Couldn't reach the trail server")
    }

    /** Turns the OpenStreetMap answer into trails: whole routes, plus named paths joined end to end. */
    fun parse(osm: JSONObject, lat: Double, lon: Double): List<Trail> {
        val els = osm.optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Trail>()
        val byName = LinkedHashMap<String, MutableList<Pair<List<DoubleArray>, JSONObject>>>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            when (e.optString("type")) {
                "relation" -> {
                    val members = e.optJSONArray("members") ?: continue
                    val segs = (0 until members.length()).mapNotNull { j ->
                        members.getJSONObject(j).takeIf { it.optString("type") == "way" }?.optJSONArray("geometry")?.let { geom(it) }
                    }.filter { it.size > 1 }
                    val line = stitch(segs) ?: continue
                    val name = tags.optString("name").ifBlank { tags.optString("ref") }
                    if (name.isBlank()) continue
                    out += make("r${e.optLong("id")}", name, line, tags)
                }
                "way" -> {
                    val name = tags.optString("name")
                    if (name.isBlank()) continue
                    val g = e.optJSONArray("geometry")?.let { geom(it) }?.takeIf { it.size > 1 } ?: continue
                    byName.getOrPut(name) { mutableListOf() } += g to tags
                }
            }
        }
        // Named paths: join the pieces of each name; keep the longest connected stretch.
        val routeNames = out.map { it.name.lowercase() }.toSet()
        byName.forEach { (name, pieces) ->
            if (name.lowercase() in routeNames) return@forEach
            val line = stitch(pieces.map { it.first }) ?: return@forEach
            out += make("w${name.hashCode()}", name, line, pieces.first().second)
        }
        val me = doubleArrayOf(lat, lon)
        return out.filter { it.lengthM >= 500 }
            .onEach { t -> t.distToStartM = t.points.minOf { Geo.dist(it, me) } }
            .distinctBy { it.name.lowercase() + (it.lengthM / 200).roundToInt() }
            .sortedBy { it.distToStartM }
            .take(150)
    }

    private fun geom(a: JSONArray) = (0 until a.length()).map { val p = a.getJSONObject(it); doubleArrayOf(p.getDouble("lat"), p.getDouble("lon")) }

    private fun make(id: String, name: String, line: List<DoubleArray>, tags: JSONObject): Trail {
        val len = Geo.length(line)
        val loop = Geo.dist(line.first(), line.last()) < maxOf(80.0, len * 0.03)
        return Trail(id, name, simplify(line, 4.0), len, loop, tags.optString("surface"), tags.optString("sac_scale"))
    }

    /**
     * Joins line pieces into the longest single line, flipping pieces as needed so their ends meet
     * (pieces whose ends are within 50 m count as connected — bridges and crossings are often unnamed gaps).
     */
    fun stitch(segs: List<List<DoubleArray>>): List<DoubleArray>? {
        if (segs.isEmpty()) return null
        val left = segs.toMutableList()
        var best: List<DoubleArray>? = null
        while (left.isNotEmpty()) {
            val line = ArrayList(left.removeAt(0))
            var grew = true
            while (grew) {
                grew = false
                val it = left.listIterator()
                while (it.hasNext()) {
                    val s = it.next()
                    when {
                        Geo.dist(line.last(), s.first()) < 50 -> { line.addAll(s.drop(1)); it.remove(); grew = true }
                        Geo.dist(line.last(), s.last()) < 50 -> { line.addAll(s.reversed().drop(1)); it.remove(); grew = true }
                        Geo.dist(line.first(), s.last()) < 50 -> { line.addAll(0, s.dropLast(1)); it.remove(); grew = true }
                        Geo.dist(line.first(), s.first()) < 50 -> { line.addAll(0, s.reversed().dropLast(1)); it.remove(); grew = true }
                    }
                }
            }
            if (best == null || Geo.length(line) > Geo.length(best)) best = line
        }
        return best
    }

    /** Drops points that add almost nothing (keeps the shape within [tolM] meters). */
    fun simplify(pts: List<DoubleArray>, tolM: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        fun rec(a: Int, b: Int) {
            var maxD = 0.0; var idx = -1
            for (i in a + 1 until b) { val d = crossTrack(pts[i], pts[a], pts[b]); if (d > maxD) { maxD = d; idx = i } }
            if (idx >= 0 && maxD > tolM) { keep[idx] = true; rec(a, idx); rec(idx, b) }
        }
        rec(0, pts.size - 1)
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance (m) from p to the segment a–b, on a local flat projection. */
    fun crossTrack(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double = segInfo(p, a, b).first

    /** (distance from p to segment a–b, fraction 0..1 along it). */
    fun segInfo(p: DoubleArray, a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val k = cos(Math.toRadians(a[0])) * 111320.0
        val ax = 0.0; val ay = 0.0
        val bx = (b[1] - a[1]) * k; val by = (b[0] - a[0]) * 110540.0
        val px = (p[1] - a[1]) * k; val py = (p[0] - a[0]) * 110540.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px - ax) * bx + (py - ay) * by) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val dx = px - tc * bx; val dy = py - tc * by
        return sqrt(dx * dx + dy * dy) to tc
    }

    /** Up to [n] evenly spaced points along the line, for the elevation profile. */
    fun profilePoints(pts: List<DoubleArray>, n: Int = 80): List<DoubleArray> {
        val total = Geo.length(pts)
        if (total <= 0 || pts.size < 2) return pts
        val out = mutableListOf<DoubleArray>()
        var seg = 1; var acc = 0.0
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (seg < pts.size - 1 && acc + Geo.dist(pts[seg - 1], pts[seg]) < target) { acc += Geo.dist(pts[seg - 1], pts[seg]); seg++ }
            val d = Geo.dist(pts[seg - 1], pts[seg]).coerceAtLeast(1e-6)
            val f = ((target - acc) / d).coerceIn(0.0, 1.0)
            val a = pts[seg - 1]; val b = pts[seg]
            out += doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
        }
        return out
    }

    /** Elevations (m) for the trail's profile points, from Open-Meteo (free, no key). */
    fun fetchElevation(t: Trail) {
        val pts = profilePoints(t.points)
        val lats = pts.joinToString(",") { String.format(java.util.Locale.US, "%.5f", it[0]) }
        val lons = pts.joinToString(",") { String.format(java.util.Locale.US, "%.5f", it[1]) }
        val conn = (URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 15_000; readTimeout = 20_000 }
        val a = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("elevation")
        t.elevations = DoubleArray(a.length()) { a.getDouble(it) }
    }
}

/**
 * Following a trail: where you are along it, how far off it you are, and what's coming.
 * Out-and-back trails are followed out to the end and back to the start.
 */
class TrailNav(val trail: Trail, val outAndBack: Boolean) {
    val path: List<DoubleArray> = if (outAndBack) trail.points + trail.points.reversed().drop(1) else trail.points
    val cum = DoubleArray(path.size).also { c -> for (i in 1 until path.size) c[i] = c[i - 1] + Geo.dist(path[i - 1], path[i]) }
    val total get() = cum.last()
    val turnaroundM = if (outAndBack) trail.lengthM else -1.0

    /** Sharp bends worth a heads-up: (distance along the path, "left"/"right"). */
    val turns: List<Pair<Double, String>> = buildList {
        var lastAt = -1000.0
        for (i in 1 until path.size - 1) {
            // Compare the direction ~25 m before and after this point.
            val before = (i - 1 downTo 0).firstOrNull { cum[i] - cum[it] >= 25 } ?: continue
            val after = (i + 1 until path.size).firstOrNull { cum[it] - cum[i] >= 25 } ?: continue
            val b1 = Geo.bearing(path[before], path[i]); val b2 = Geo.bearing(path[i], path[after])
            val d = ((b2 - b1 + 540) % 360) - 180
            if (abs(d) >= 60 && cum[i] - lastAt > 60 && abs(cum[i] - turnaroundM) > 40) {
                add(cum[i] to if (d > 0) "right" else "left"); lastAt = cum[i]
            }
        }
    }

    var progress = 0.0; private set          // meters along the path
    var offBy = 0.0; private set             // meters from the path
    var offRoute = false; private set
    var finished = false; private set
    private var lastIdx = 0
    private var offCount = 0
    private var onCount = 0
    private val announcedTurns = HashSet<Int>()
    private var turnedAround = false

    val remaining get() = (total - progress).coerceAtLeast(0.0)
    val nextTurn: Pair<Double, String>? get() = turns.firstOrNull { it.first > progress + 5 }?.let { (it.first - progress) to it.second }

    /** Climb left (m) from the elevation profile, for the part of the trail still ahead. */
    fun climbLeft(): Double {
        val e = trail.elevations ?: return 0.0
        if (e.size < 2) return 0.0
        // Profile covers the trail one way; map path progress to it (reversed on the way back).
        val oneWay = trail.lengthM
        fun elevAt(m: Double): Double {
            val x = (if (outAndBack && m > oneWay) 2 * oneWay - m else m).coerceIn(0.0, oneWay) / oneWay * (e.size - 1)
            val i = x.toInt().coerceAtMost(e.size - 2); val f = x - i
            return e[i] + (e[i + 1] - e[i]) * f
        }
        var up = 0.0; var prev = elevAt(progress)
        var m = progress
        while (m < total) { m = minOf(total, m + oneWay / (e.size - 1)); val v = elevAt(m); if (v > prev) up += v - prev; prev = v }
        return up
    }

    /** New GPS position → things to announce: offroute, onroute, turn_left/right, turnaround, trail_done. */
    fun update(lat: Double, lon: Double): List<String> {
        val p = doubleArrayOf(lat, lon)
        val events = mutableListOf<String>()
        // Look near where we were (so a trail that doubles back doesn't confuse it); widen if we're lost.
        // Nearest stretch of the path that isn't behind us — on an out-and-back the way out and the way
        // back lie on the same ground, so prefer the one further along when they're about equally close.
        fun best(from: Int, to: Int, wide: Boolean = false): Triple<Int, Double, Double> {
            var bi = -1; var bd = Double.MAX_VALUE; var bf = 0.0
            for (i in from until to) {
                val (d, f) = Trails.segInfo(p, path[i], path[i + 1])
                val pos = cum[i] + (cum[i + 1] - cum[i]) * f
                if (pos < progress - 30) continue
                if (!wide && pos > progress + 150) continue   // can't have gone that far since the last fix
                if (d < bd - 2.0 || (d <= bd + 2.0 && bi >= 0 && pos > cum[bi])) { bd = d; bi = i; bf = f }
            }
            if (bi < 0) { bi = lastIdx; bd = Trails.segInfo(p, path[bi], path[bi + 1]).first; bf = 0.0 }
            return Triple(bi, bd, bf)
        }
        val window = lastIdx.coerceAtLeast(0) to (lastIdx + 40).coerceAtMost(path.size - 1)
        var (i, d, f) = best(window.first, window.second)
        if (d > 50) { val all = best(0, path.size - 1, wide = true); if (all.second < d) { i = all.first; d = all.second; f = all.third } }
        lastIdx = i
        offBy = d
        // Allow a little backward drift (GPS wobble), but don't jump far back.
        val here = cum[i] + (cum[i + 1] - cum[i]) * f
        if (here >= progress - 30) progress = here

        // Off route after 5 readings over 40 m; back on after 3 readings under 25 m.
        if (!offRoute) { offCount = if (d > 40) offCount + 1 else 0; if (offCount >= 5) { offRoute = true; onCount = 0; events += "offroute" } }
        else { onCount = if (d < 25) onCount + 1 else 0; if (onCount >= 3) { offRoute = false; offCount = 0; events += "onroute" } }

        if (!offRoute) {
            turns.forEachIndexed { k, (at, dir) ->
                if (k !in announcedTurns && at - progress in 0.0..40.0) { announcedTurns += k; events += "turn_$dir" }
            }
            if (outAndBack && !turnedAround && progress >= turnaroundM - 15) { turnedAround = true; events += "turnaround" }
            if (!finished && progress >= total - 25) { finished = true; events += "trail_done" }
        }
        return events
    }

    /** Bearing (degrees) and distance (m) from a point to the trail start — for getting to the trailhead. */
    fun toStart(lat: Double, lon: Double): Pair<Double, Double> =
        Geo.bearing(doubleArrayOf(lat, lon), path.first()) to Geo.dist(doubleArrayOf(lat, lon), path.first())
}
