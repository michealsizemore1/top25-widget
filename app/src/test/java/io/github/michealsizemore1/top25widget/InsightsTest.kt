package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private fun run(start: Long, minutes: Int, speed: Double, hr: Int, pw: Int = 260, fastMile: Boolean = false): JSONObject {
        val a = JSONArray(); var m = 0.0
        for (t in 0 until minutes * 60) {
            val v = if (fastMile && t in 600 until 1000) 4.5 else speed
            m += v
            a.put(JSONArray(listOf(t, (m * 10).toInt(), hr, (v * 100).toInt(), 170, pw, t)))
        }
        return JSONObject().put("start", start).put("samples", a).put("distance", m).put("movingSeconds", minutes * 60).put("stress", minutes)
    }

    @Test fun zone2Pace() {
        val z = Insights.zone2(run(0, 30, 3.0, 135), 185.0, 43.5)
        assertNotNull(z); assertEquals(536, z!!.secPerMile)   // 1609/3.0
        assertEquals(null, Insights.zone2(run(0, 30, 3.0, 160), 185.0, 43.5))  // not in Z2
    }

    @Test fun bestEfforts() {
        val b = Insights.bestEfforts(run(0, 40, 3.0, 140, fastMile = true))
        assertTrue(b.containsKey("1mi") && b.containsKey("5k"))
        assertTrue(b["1mi"]!! < 400)          // the 4.5 m/s stretch: ~358 s
        assertTrue(b["5k"]!! < 1666)          // includes the fast stretch
    }

    @Test fun records() {
        val r1 = run(1000, 40, 3.0, 140); val r2 = run(2000, 40, 3.0, 140, fastMile = true)
        val nb = Insights.newBests(r2, listOf(r1))
        assertTrue(nb.containsKey("1mi"))
    }

    @Test fun loadBaseline() {
        val day = 86_400_000L; val now = 100 * day
        val runs = (0 until 30).map { run(now - it * day - 1000, 30, 3.0, 140) }
        val l = Insights.load(runs, now)
        assertEquals("Steady", l.status)
        assertEquals(null, Insights.load(runs.take(5), now).ratio)
    }

    private fun assertEquals(expected: Int, actual: Int, tol: Int) = assertTrue("$actual not within $tol of $expected", kotlin.math.abs(expected - actual) <= tol)
}
