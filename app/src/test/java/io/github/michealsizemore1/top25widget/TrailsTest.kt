package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trail building and navigation on made-up OpenStreetMap data. */
class TrailsTest {
    // A path going north ~1.1 km in two pieces (second piece stored backwards), then turning east ~0.6 km.
    private fun pt(lat: Double, lon: Double) = JSONObject().put("lat", lat).put("lon", lon)
    private fun way(name: String, vararg p: Pair<Double, Double>) = JSONObject().put("type", "way")
        .put("tags", JSONObject().put("name", name).put("highway", "path"))
        .put("geometry", JSONArray(p.map { pt(it.first, it.second) }))

    private val osm = JSONObject().put("elements", JSONArray(listOf(
        way("Ridge Trail", 40.000 to -105.000, 40.005 to -105.000),
        way("Ridge Trail", 40.010 to -105.006, 40.010 to -105.000, 40.005 to -105.000),   // reversed piece with a right turn
        way("Tiny Path", 40.0 to -105.01, 40.001 to -105.01),                                 // too short (<500 m)
    )))

    @Test fun parseAndStitch() {
        val t = Trails.parse(osm, 40.0, -105.0)
        println(t.map { "${it.name} ${it.lengthM.toInt()} m loop=${it.loop} points=${it.points.size} away=${it.distToStartM.toInt()}" })
        assertEquals(1, t.size)
        assertEquals("Ridge Trail", t[0].name)
        assertTrue(t[0].lengthM in 1500.0..1700.0)
    }

    @Test fun navigate() {
        val t = Trails.parse(osm, 40.0, -105.0)[0]
        val nav = TrailNav(t, outAndBack = true)
        println("turns=${nav.turns} total=${nav.total.toInt()}")
        val ev = mutableListOf<String>()
        // Walk the path out and back in 10 m steps; then step 100 m off to the side.
        val path = nav.path
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val n = (Geo.dist(a, b) / 10).toInt().coerceAtLeast(1)
            for (k in 1..n) ev += nav.update(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
        }
        println("events=$ev")
        assertTrue("turnaround" in ev)
        assertTrue("trail_done" in ev)
        assertTrue(ev.any { it.startsWith("turn_") })
        assertEquals(listOf("turn_left", "turnaround", "turn_right", "trail_done"), ev)
        val nav2 = TrailNav(t, false)
        val off = (1..6).flatMap { nav2.update(40.002, -104.998) }   // ~170 m east of the trail
        println("off=$off offBy=${nav2.offBy.toInt()}")
        assertTrue("offroute" in off)
    }
}
