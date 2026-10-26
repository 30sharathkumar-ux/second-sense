package com.google.mediapipe.examples.objectdetection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApproachTracker].
 * android.util.Log is stubbed by returnDefaultValues=true in build.gradle testOptions.
 */
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
        // "chair" is NOT in SUDDEN_DANGER_LABELS
        assertFalse(tracker.evaluate("chair", 0.5f, 0.90f, fakeNow))
    }

    @Test
    fun fastGrowthIsDanger() {
        // growthRate = (0.40 - 0.05) / 0.5s = 0.70 > GROWTH_RATE_THRESHOLD(0.25) -> DANGER
        tracker.evaluate("obstacle", 0.5f, 0.05f, fakeNow)
        assertTrue(tracker.evaluate("obstacle", 0.5f, 0.40f, fakeNow + 500L))
    }

    @Test
    fun slowGrowthIsNotDanger() {
        // growthRate = (0.20 - 0.10) / 2s = 0.05 < 0.25 -> NOT DANGER
        tracker.evaluate("obstacle", 0.5f, 0.10f, fakeNow)
        assertFalse(tracker.evaluate("obstacle", 0.5f, 0.20f, fakeNow + 2000L))
    }

    @Test
    fun fastGrowthBelowMinAreaIsNotDanger() {
        // Fast rate but areaRatio stays below MIN_AREA_FOR_GROWTH_DANGER (0.10)
        tracker.evaluate("obstacle", 0.5f, 0.01f, fakeNow)
        assertFalse(tracker.evaluate("obstacle", 0.5f, 0.09f, fakeNow + 100L))
    }

    @Test
    fun staleTrackEvictionTreatsObjectAsNew() {
        tracker.evaluate("person", 0.5f, 0.10f, fakeNow)
        val future = fakeNow + ApproachTracker.TRACK_MAX_AGE_MS + 100L
        // Track evicted -> treated as new -> sudden-appearance fires
        assertTrue(tracker.evaluate("person", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, future))
    }

    @Test
    fun centerXMismatchTreatsObjectAsNew() {
        // Register on far left, then see same label on far right -> no match -> new object
        tracker.evaluate("person", 0.1f, 0.10f, fakeNow)
        assertTrue(tracker.evaluate("person", 0.9f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow + 100L))
    }

    @Test
    fun resetClearsAllTracks() {
        tracker.evaluate("car", 0.5f, 0.10f, fakeNow)
        tracker.evaluate("car", 0.5f, 0.30f, fakeNow + 200L)
        tracker.reset()
        // History cleared -> sudden-appearance fires again
        assertTrue(tracker.evaluate("car", 0.5f, ApproachTracker.SUDDEN_APPEAR_THRESHOLD, fakeNow + 300L))
    }
}
