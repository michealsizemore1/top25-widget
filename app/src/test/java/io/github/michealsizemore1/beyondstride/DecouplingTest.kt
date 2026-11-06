package io.github.michealsizemore1.beyondstride

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecouplingTest {
    private fun run(minutes: Int, hr: (Int) -> Int, pw: (Int) -> Int = { 250 }, speed: Double = 3.0): JSONObject {
        val a = JSONArray(); var m = 0.0
        for (t in 0 until minutes * 60) { m += speed; a.put(JSONArray(listOf(t, (m * 10).toInt(), hr(t), (speed * 100).toInt(), 170, pw(t), t))) }
        return JSONObject().put("samples", a)
    }

    @Test fun steadyNoDrift() {
        val r = Decoupling.compute(run(60, { 140 }))!!
        assertEquals(0.0, r.pwHr!!, 0.01); assertEquals(0.0, r.paHr!!, 0.01); assertTrue(r.steady); assertEquals(49, r.minutes)
    }

    @Test fun heartRateDrift() {
        // HR climbs from 140 to ~154 over the run at steady power/pace -> roughly 5% drift
        val r = Decoupling.compute(run(60, { t -> 140 + t * 14 / 3600 }))!!
        assertTrue(r.main!! in 3.0..7.0)
        assertTrue(r.hr2 > r.hr1)
        assertTrue(Decoupling.line(r).startsWith("Aerobic decoupling"))
    }

    @Test fun tooShortOrNoHr() {
        assertNull(Decoupling.compute(run(15, { 140 })))
        assertNull(Decoupling.compute(run(60, { 0 })))
    }

    @Test fun intervalsAreUneven() {
        val r = Decoupling.compute(run(60, { 150 }, { t -> if ((t / 120) % 2 == 0) 330 else 180 }))
        assertNotNull(r); assertTrue(!r!!.steady)
    }
}
