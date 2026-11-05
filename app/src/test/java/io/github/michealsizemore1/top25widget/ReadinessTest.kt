package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test fun rmssdFromIntervals() {
        // Alternating 1000/1040 ms: successive differences are all 40 ms → RMSSD 40, HR ~59.
        val rr = (0 until 200).map { if (it % 2 == 0) 1000 else 1040 }
        val h = Readiness.hrv(rr, skipSec = 0)
        assertNotNull(h); assertEquals(40.0, h!!.rmssd, 0.01); assertEquals(59, h.hr)
    }

    @Test fun artifactsDropped() {
        val rr = (0 until 200).map { if (it % 2 == 0) 1000 else 1040 }.toMutableList()
        rr[50] = 2000; rr[120] = 500   // missed beat, extra beat
        val h = Readiness.hrv(rr, skipSec = 0)!!
        assertTrue(h.rmssd < 45)
    }

    private fun days(n: Int) = (1..n).map { java.time.LocalDate.parse("2026-11-05").minusDays(it.toLong()).toString() }

    @Test fun normalMorningIsReady() {
        val checks = days(10).mapIndexed { i, d -> Readiness.Check(d, 60.0 + (i % 3) * 3, 46) } +
            Readiness.Check("2026-11-05", 62.0, 46, legs = 2)
        val nights = listOf(Readiness.Night("2026-11-05", 470))
        val r = Readiness.compute(checks, nights, emptyList(), "2026-11-05")!!
        assertEquals("Ready", r.label); assertTrue(r.score >= 75)
    }

    @Test fun lowHrvAndShortSleepIsLow() {
        val checks = days(10).mapIndexed { i, d -> Readiness.Check(d, 60.0 + (i % 3) * 3, 46) } +
            Readiness.Check("2026-11-05", 35.0, 53, legs = 0)
        val r = Readiness.compute(checks, listOf(Readiness.Night("2026-11-05", 290)), emptyList(), "2026-11-05")!!
        assertEquals("Low", r.label)
    }

    @Test fun learningBaseline() {
        val r = Readiness.compute(listOf(Readiness.Check("2026-11-05", 55.0, 47, legs = 1)), emptyList(), emptyList(), "2026-11-05")!!
        assertTrue(r.parts.any { it.name == "HRV" && it.pts == -1 })
    }

    @Test fun jsonRoundTrip() {
        val c = listOf(Readiness.Check("2026-11-04", 55.5, 47, 1, 2, 5L))
        assertEquals(c, Readiness.checksFrom(Readiness.checksJson(c)))
    }

    // ---------------------------------------------------------------- critical power

    private fun powerRun(start: Long, segments: List<Pair<Int, Int>>): JSONObject {
        val a = JSONArray(); var t = 0
        segments.forEach { (sec, w) -> repeat(sec) { a.put(JSONArray(listOf(t, t * 30, 140, 300, 170, w, t))); t++ } }
        return JSONObject().put("start", start).put("samples", a)
    }

    @Test fun cpFromHardEfforts() {
        // True CP 340 W, W' 15 kJ: P(t) = 340 + 15000/t.
        val now = 100L * 86_400_000L
        fun p(t: Int) = (340 + 15000.0 / t).toInt()
        val runs = listOf(
            powerRun(now - 86_400_000L, listOf(900 to 250, 180 to p(180), 1800 to 240, 540 to p(540), 600 to 230)),
            powerRun(now - 5 * 86_400_000L, listOf(600 to 250, 1200 to p(1200), 300 to 240)),
            powerRun(now - 9 * 86_400_000L, listOf(600 to 250, 300 to p(300), 600 to 240, 720 to p(720), 300 to 200)),
        )
        val e = CriticalPower.estimate(runs, now)
        assertNotNull(e.cp); assertTrue("cp ${e.cp}", e.cp!! in 330..350)
    }

    @Test fun easyRunsGiveNoCp() {
        val now = 100L * 86_400_000L
        val runs = (1..5).map { powerRun(now - it * 86_400_000L, listOf(3000 to 255)) }
        assertNull(CriticalPower.estimate(runs, now).cp)
    }
}
