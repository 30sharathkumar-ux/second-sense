package com.secondsense.alertmanager

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for all 4 Pair-B AlertRuleEngine rules.
 *
 * These run on the JVM (no Android context needed) so they execute fast.
 */
class AlertRuleEngineTest {

    private lateinit var engine: AlertRuleEngine

    // Fixed "now" timestamp used to control time in tests
    private var fakeNowMs: Long = 1_000_000L

    @Before
    fun setUp() {
        engine = AlertRuleEngine()
        fakeNowMs = 1_000_000L
    }

    // ─── Rule #1: 3 consecutive frames ────────────────────────────────────

    @Test
    fun `rule1 - no alert on first frame`() {
        val objs = listOf(DetectedObject("person", 100f))
        val result = engine.onFrame(objs, fakeNowMs)
        assertNull("Should block on frame 1", result)
    }

    @Test
    fun `rule1 - no alert on second frame`() {
        val objs = listOf(DetectedObject("person", 100f))
        engine.onFrame(objs, fakeNowMs)
        fakeNowMs += 100
        val result = engine.onFrame(objs, fakeNowMs)
        assertNull("Should block on frame 2", result)
    }

    @Test
    fun `rule1 - alert fires on third consecutive frame`() {
        val objs = listOf(DetectedObject("person", 100f))
        engine.onFrame(objs, fakeNowMs)
        fakeNowMs += 100
        engine.onFrame(objs, fakeNowMs)
        fakeNowMs += 100
        val result = engine.onFrame(objs, fakeNowMs)
        assertNotNull("Should fire on frame 3", result)
        assertEquals("person", result!!.obj.label)
    }

    @Test
    fun `rule1 - counter resets if object disappears for one frame`() {
        val objs = listOf(DetectedObject("person", 100f))
        engine.onFrame(objs, fakeNowMs)                   // frame 1
        fakeNowMs += 100
        engine.onFrame(objs, fakeNowMs)                   // frame 2
        fakeNowMs += 100
        engine.onFrame(emptyList(), fakeNowMs)             // disappears → counter resets
        fakeNowMs += 100
        engine.onFrame(objs, fakeNowMs)                   // frame 1 again
        fakeNowMs += 100
        engine.onFrame(objs, fakeNowMs)                   // frame 2 again
        fakeNowMs += 100
        val result = engine.onFrame(objs, fakeNowMs)      // must reach frame 3
        assertNotNull("Counter should have reset and re-accumulated", result)
    }

    @Test
    fun `rule1 - frameCount returns current count`() {
        val objs = listOf(DetectedObject("car", 200f))
        engine.onFrame(objs, fakeNowMs)
        assertEquals(1, engine.frameCount("car"))
        fakeNowMs += 100
        engine.onFrame(objs, fakeNowMs)
        assertEquals(2, engine.frameCount("car"))
    }

    // ─── Rule #2: 3-second cooldown ───────────────────────────────────────

    @Test
    fun `rule2 - no repeat within 3 seconds`() {
        val objs = listOf(DetectedObject("person", 100f))
        // Warm up to 3 frames to satisfy Rule #1
        repeat(3) { engine.onFrame(objs, fakeNowMs); fakeNowMs += 100 }

        // Alert just fired; reset frame counter by cycling off-on
        engine.onFrame(emptyList(), fakeNowMs)
        fakeNowMs += 100
        repeat(3) { engine.onFrame(objs, fakeNowMs); fakeNowMs += 100 }

        // Still within 3-second window
        val secondAttempt = engine.onFrame(objs, fakeNowMs + 500L)
        assertNull("Should be blocked by Rule#2 cooldown", secondAttempt)
    }

    @Test
    fun `rule2 - alert allowed after cooldown expires`() {
        val objs = listOf(DetectedObject("person", 100f))
        var firstAlert: AlertCandidate? = null
        repeat(3) {
            firstAlert = engine.onFrame(objs, fakeNowMs)
            fakeNowMs += 100
        }
        assertNotNull(firstAlert)

        // Advance time past 3-second cooldown, re-accumulate frames
        fakeNowMs += AlertRuleEngine.ALERT_COOLDOWN_MS + 1_000L
        engine.reset()
        var afterCooldown: AlertCandidate? = null
        repeat(3) {
            afterCooldown = engine.onFrame(objs, fakeNowMs)
            fakeNowMs += 100
        }
        assertNotNull("Alert should fire after cooldown", afterCooldown)
    }

    @Test
    fun `rule2 - cooldownRemainingMs is positive right after alert`() {
        val objs = listOf(DetectedObject("dog", 50f))
        repeat(3) { engine.onFrame(objs, fakeNowMs); fakeNowMs += 100 }
        val remaining = engine.cooldownRemainingMs("dog", fakeNowMs)
        assertTrue("Cooldown should be positive right after alert", remaining > 0L)
    }

    @Test
    fun `rule2 - different labels have independent cooldowns`() {
        val personObj = listOf(DetectedObject("person", 100f))
        val carObj    = listOf(DetectedObject("car",    200f))

        repeat(3) { engine.onFrame(personObj, fakeNowMs); fakeNowMs += 100 }
        // person fires on frame 3

        // car starts fresh and should be able to fire independently on frame 3
        var carAlert: AlertCandidate? = null
        repeat(3) {
            carAlert = engine.onFrame(carObj, fakeNowMs)
            fakeNowMs += 50
        }
        assertNotNull("Car alert should fire independently of person cooldown", carAlert)
    }

    // ─── Rule #3: distance-based vibration pattern ─────────────────────────

    @Test
    fun `rule3 - closer objects produce shorter gaps (faster vibration)`() {
        val nearPattern = AlertRuleEngine.vibrationPattern(40f,  255)   // < 50 cm
        val farPattern  = AlertRuleEngine.vibrationPattern(400f, 255)   // ≥ 350 cm

        // timing[2] is the first gap (off period between pulses)
        val nearGap = nearPattern.timings[2]
        val farGap  = farPattern.timings[2]

        assertTrue(
            "Near gap ($nearGap ms) should be shorter than far gap ($farGap ms)",
            nearGap < farGap
        )
    }

    @Test
    fun `rule3 - pattern has correct 3-pulse structure`() {
        val pattern = AlertRuleEngine.vibrationPattern(100f, 200)
        // Expected: [delay, on, gap, on, gap, on] = 6 entries
        assertEquals("Pattern should have 6 timing entries", 6, pattern.timings.size)
        assertEquals("Pattern should have 6 amplitude entries", 6, pattern.amplitudes.size)
        assertEquals("One-shot pattern must have repeatIndex = -1", -1, pattern.repeatIndex)
    }

    @Test
    fun `rule3 - amplitude in pattern respects strength input`() {
        val pattern = AlertRuleEngine.vibrationPattern(80f, 128)
        // Amplitudes at indices 1, 3, 5 should equal 128
        assertEquals(128, pattern.amplitudes[1])
        assertEquals(128, pattern.amplitudes[3])
        assertEquals(128, pattern.amplitudes[5])
        // Off slots (0, 2, 4) should be 0
        assertEquals(0, pattern.amplitudes[0])
        assertEquals(0, pattern.amplitudes[2])
        assertEquals(0, pattern.amplitudes[4])
    }

    // ─── Rule #4: priority classification ─────────────────────────────────

    @Test
    fun `rule4 - person is CRITICAL priority`() {
        assertEquals(AlertPriority.CRITICAL, AlertPriority.forObject("person"))
    }

    @Test
    fun `rule4 - car is CRITICAL priority`() {
        assertEquals(AlertPriority.CRITICAL, AlertPriority.forObject("car"))
    }

    @Test
    fun `rule4 - stray dog is CRITICAL priority`() {
        assertEquals(AlertPriority.CRITICAL, AlertPriority.forObject("dog"))
    }

    @Test
    fun `rule4 - motorcycle is CRITICAL priority`() {
        assertEquals(AlertPriority.CRITICAL, AlertPriority.forObject("motorcycle"))
    }

    @Test
    fun `rule4 - stairs is HIGH priority`() {
        assertEquals(AlertPriority.HIGH, AlertPriority.forObject("stairs"))
    }

    @Test
    fun `rule4 - chair is MEDIUM priority`() {
        assertEquals(AlertPriority.MEDIUM, AlertPriority.forObject("chair"))
    }

    @Test
    fun `rule4 - unknown object is LOW priority`() {
        assertEquals(AlertPriority.LOW, AlertPriority.forObject("unknown_thing_xyz"))
    }

    @Test
    fun `rule4 - priority matching is case-insensitive`() {
        assertEquals(AlertPriority.forObject("Person"), AlertPriority.forObject("person"))
        assertEquals(AlertPriority.forObject("CAR"), AlertPriority.forObject("car"))
    }

    @Test
    fun `rule4 - CRITICAL object fires before MEDIUM when both are in same frame`() {
        val personObj = DetectedObject("person", 300f)
        val chairObj  = DetectedObject("chair",  100f)  // chair is closer but lower priority

        var alert: AlertCandidate? = null
        repeat(3) {
            alert = engine.onFrame(listOf(personObj, chairObj), fakeNowMs)
            fakeNowMs += 100
        }
        assertNotNull(alert)
        assertEquals(
            "CRITICAL 'person' should win over MEDIUM 'chair' despite being farther",
            "person",
            alert!!.obj.label
        )
    }

    @Test
    fun `rule4 - among same priority, nearer object fires first`() {
        // Use different labels to avoid frame-count collision
        val car1 = DetectedObject("car",   50f)
        val car2 = DetectedObject("truck", 400f)

        var alert: AlertCandidate? = null
        repeat(3) {
            alert = engine.onFrame(listOf(car1, car2), fakeNowMs)
            fakeNowMs += 100
        }
        assertNotNull(alert)
        assertTrue(
            "Nearest CRITICAL object should fire first",
            alert!!.obj.distanceCm <= 100f
        )
    }

    // ─── AlertManager constants ────────────────────────────────────────────

    @Test
    fun `alertManager constants are correct`() {
        assertEquals(1,   AlertManager.MIN_STRENGTH)
        assertEquals(255, AlertManager.MAX_STRENGTH)
        assertTrue(AlertManager.DEFAULT_VIBRATION_DURATION_MS > 0)
    }

    @Test
    fun `ruleEngine constants are correct`() {
        assertEquals(3,      AlertRuleEngine.REQUIRED_CONSECUTIVE_FRAMES)
        assertEquals(3_000L, AlertRuleEngine.ALERT_COOLDOWN_MS)
    }

    // ─── Reset behaviour ──────────────────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        val objs = listOf(DetectedObject("person", 100f))
        repeat(3) { engine.onFrame(objs, fakeNowMs); fakeNowMs += 100 }
        engine.onFrame(objs, fakeNowMs) // triggers alert, sets cooldown

        engine.reset()

        assertEquals("Frame counter must be 0 after reset", 0, engine.frameCount("person"))
        assertEquals("Cooldown must be 0 after reset",       0L, engine.cooldownRemainingMs("person"))
    }
}
