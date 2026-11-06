package io.github.michealsizemore1.beyondstride

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitsTest {
    @Test fun mileSplitsFromSamples() {
        // 3 m/s for 1300 s = 3900 m → 2 full miles (536 s each) + 0.42 mi.
        val a = JSONArray()
        for (t in 0..1300) a.put(JSONArray(listOf(t, t * 30, 140, 300, 170, 280, t)))
        val sp = Splits.miles(JSONObject().put("samples", a))
        assertEquals(3, sp.size)
        assertEquals(536, sp[0].sec); assertEquals(536, sp[1].sec)
        assertEquals(140, sp[0].hr); assertEquals(280, sp[1].power)
        assertEquals(3900 - 2 * 1609.344, sp[2].meters, 1.0)
    }
}
