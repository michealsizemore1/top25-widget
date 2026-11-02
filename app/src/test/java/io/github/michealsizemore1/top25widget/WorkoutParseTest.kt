package io.github.michealsizemore1.top25widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks that written plan days turn into sensible workouts. Prints each result to the build log. */
class WorkoutParseTest {
    private fun show(text: String) = Workouts.fromPlanText(text).also { w ->
        println("=== " + text.replace("\n", " | "))
        if (w == null) println("   → rest / no workout")
        else { println("   → " + w.optString("name")); Workouts.outline(w, 185.0, 43.5, 333).forEach { println("      $it") } }
    }

    @Test fun easy() { val w = show("Monday, November 2\nEasy run 5 miles (Z2)"); assertNotNull(w); assertEquals(1, Workouts.flatten(w!!).size) }
    @Test fun repeats() { val w = show("Tuesday, November 3\n8 x 400m @ 6:20 pace with 400m jog recovery. Total 6 miles")!!; assertEquals(18, Workouts.flatten(w).size) }
    @Test fun rest() { assertNull(show("Wednesday, November 4\nRest day")) }
    @Test fun tempo() { val w = show("Thursday, November 5\nTempo 7 miles w/ 4 mi @ threshold (7:45-7:55/mi)")!!; assertEquals(3, Workouts.flatten(w).size) }
    @Test fun long() { assertNotNull(show("Saturday, November 7\nLong run 16 miles easy")) }
    @Test fun recovery() { assertNotNull(show("Sunday, November 8\nRecovery run 4 miles")) }
    @Test fun timed() { assertNotNull(show("Friday, November 6\n5 x 3 min hard, 2 min easy jog, 6 miles total")) }
    @Test fun progression() { assertNotNull(show("Progression run 8 miles: last 3 faster")) }
    @Test fun miles() { assertEquals(9656, Workouts.flatten(show("Tuesday, November 10\n6 miles easy + 6 x 20 sec strides")!!)[0].optInt("value")) }
    @Test fun range() { show("Friday, November 13\nEasy 5-6 miles") }
    // The McMillan plan's own format.
    @Test fun mc1() { val w = show("**Sun, Nov 1:** 4 E")!!; assertEquals(6437, Workouts.flatten(w)[0].optInt("value")) }
    @Test fun mc2() { assertNull(show("**Mon, Nov 2:** Rest")) }
    @Test fun mc3() { assertEquals(13, Workouts.flatten(show("**Tue, Nov 3:** 5 E + strides")!!).size) }
    @Test fun mc4() { val w = show("**Wed, Nov 4:** 4 E + STRENGTH (30 min)\n- Goblet squat: 3x12-15\n- Dumbbell Romanian deadlift: 3x10-12\n- Plank with shoulder taps: 3x30-45 sec")!!
        assertEquals(1, Workouts.flatten(w).size); assertEquals(6437, Workouts.flatten(w)[0].optInt("value")) }
    @Test fun mc5() { assertEquals("Easy + Hill Sprints", show("**Thu, Nov 5:** 5 E + 6 hill sprints")!!.optString("name")) }
    @Test fun mc6() { val w = show("**Sat, Nov 7:** 8 E + STRENGTH (30 min)\n- Goblet squat: 3x12-15\n- Bulgarian split squat: 3x10 per leg")!!
        assertEquals(12875, Workouts.flatten(w)[0].optInt("value")) }
    @Test fun mc7() { show("**Sun, Nov 15:** 14 L") ; show("**Thu, Nov 19:** 6 T") ; show("**Sun, Dec 6:** 16 L w/ last 4 @ MP") }
    @Test fun cross() { assertNotNull(show("Cross-training 45 min or rest")) }
    @Test fun rest2() { assertNull(show("Monday, November 9\nRest")) }
    @Test fun off() { assertNull(show("Friday\nOff")) }
    @Test fun steady() { assertEquals("time", Workouts.flatten(show("Saturday\nSteady 60 minutes")!!)[0].optString("dur")) }
    @Test fun lr() { assertNotNull(show("Sunday, November 15\nLR 18 mi")) }
    @Test fun aerobic() { assertNotNull(show("Wednesday\nAerobic 50 min Z2")) }
    @Test fun strength() { assertNotNull(show("Thursday\nStrength + core 30 min")) }
    @Test fun hillsW() { assertNotNull(show("Tuesday\nHill workout 8 x 1 min uphill")) }
    @Test fun hills() { show("Wednesday\nHill repeats: 6 x 90 sec uphill hard, jog down. 7 miles total") }
    @Test fun mpace() { assertEquals(3, Workouts.flatten(show("Thursday\n10 miles with middle 6 @ goal 50K pace 9:30/mi")!!).size) }
    @Test fun lastFast() { assertEquals(2, Workouts.flatten(show("Sunday\nLong run 14 miles w/ last 3 at marathon pace")!!).size) }
}
