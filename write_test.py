import os
path = r"app\src\test\java\com\google\mediapipe\examples\objectdetection\ApproachTrackerTest.kt"
code = """\
package com.google.mediapipe.examples.objectdetection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ApproachTrackerTest {

    private lateinit var tracker: ApproachTracker
    private var fakeNow: Long = 1_000_000L

    @Before
    fun setUp() {
        tracker = ApproachTracker()
        fakeNow = 1_000_000L
    }

    @Test
    fun suddenPersonAtThresholdIsDanger() {
        assertTrue(tracker.evaluate("person", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow))
    }

    @Test
    fun suddenCarAtThresholdIsDanger() {
        assertTrue(tracker.evaluate("car", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow))
    }

    @Test
    fun suddenPersonBelowThresholdIsNotDanger() {
        assertFalse(tracker.evaluate("person", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD - 0.01f, fakeNow))
    }

    @Test
    fun nonDangerLabelChairWithLargeAreaIsNotDanger() {
        assertFalse(tracker.evaluate("chair", 0.5f, 0.90f, fakeNow))
    }

    @Test
    fun fastGrowthIsDanger() {
        tracker.evaluate("obstacle", 0.5f, 0.05f, fakeNow)
        assertTrue(tracker.evaluate("obstacle", 0.5f, 0.40f, fakeNow + 500L))
    }

    @Test
    fun slowGrowthIsNotDanger() {
        tracker.evaluate("obstacle", 0.5f, 0.10f, fakeNow)
        assertFalse(tracker.evaluate("obstacle", 0.5f, 0.20f, fakeNow + 2000L))
    }

    @Test
    fun fastGrowthBelowMinAreaIsNotDanger() {
        tracker.evaluate("obstacle", 0.5f, 0.01f, fakeNow)
        assertFalse(tracker.evaluate("obstacle", 0.5f, 0.09f, fakeNow + 100L))
    }

    @Test
    fun staleTrackEvictionTreatsObjectAsNew() {
        tracker.evaluate("person", 0.5f, 0.10f, fakeNow)
        val future = fakeNow + ApproachTracker.TRACK_MAX_AGE_MS + 100L
        assertTrue(tracker.evaluate("person", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, future))
    }

    @Test
    fun centerXMismatchTreatsObjectAsNew() {
        tracker.evaluate("person", 0.1f, 0.10f, fakeNow)
        assertTrue(tracker.evaluate("person", 0.9f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow + 100L))
    }

    @Test
    fun resetClearsAllTracks() {
        tracker.evaluate("car", 0.5f, 0.10f, fakeNow)
        tracker.evaluate("car", 0.5f, 0.30f, fakeNow + 200L)
        tracker.reset()
        assertTrue(tracker.evaluate("car", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow + 300L))
    }
}
"""
with open(path, "w", encoding="utf-8") as f:
    f.write(code)
print("Done")

