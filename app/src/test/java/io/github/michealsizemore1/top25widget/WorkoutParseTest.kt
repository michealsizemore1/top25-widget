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
    @Test fun miles() { assertNotNull(show("Tuesday, November 10\n6 miles easy + 6 x 20 sec strides")) }
    @Test fun cross() { show("Cross-training 45 min or rest") }
    @Test fun hills() { show("Wednesday\nHill repeats: 6 x 90 sec uphill hard, jog down. 7 miles total") }
    @Test fun mpace() { assertEquals(3, Workouts.flatten(show("Thursday\n10 miles with middle 6 @ goal 50K pace 9:30/mi")!!).size) }
    @Test fun lastFast() { assertEquals(2, Workouts.flatten(show("Sunday\nLong run 14 miles w/ last 3 at marathon pace")!!).size) }
}
