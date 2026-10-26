package com.google.mediapipe.examples.objectdetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceDirectionCalculatorTest {

    @Test
    fun directionLeftThirdReturnsLeft() {
        assertEquals(
            DistanceDirectionCalculator.DIRECTION_LEFT,
            DistanceDirectionCalculator.calculateDirection(0f, 200f, 1000f)
        )
    }

    @Test
    fun directionRightThirdReturnsRight() {
        assertEquals(
            DistanceDirectionCalculator.DIRECTION_RIGHT,
            DistanceDirectionCalculator.calculateDirection(700f, 900f, 1000f)
        )
    }

    @Test
    fun directionMiddleThirdReturnsCenter() {
        assertEquals(
            DistanceDirectionCalculator.DIRECTION_CENTER,
            DistanceDirectionCalculator.calculateDirection(400f, 600f, 1000f)
        )
    }

    @Test
    fun directionZeroScreenWidthReturnsCenter() {
        assertEquals(
            DistanceDirectionCalculator.DIRECTION_CENTER,
            DistanceDirectionCalculator.calculateDirection(0f, 100f, 0f)
        )
    }

    @Test
    fun directionFullWidthObjectReturnsCenter() {
        assertEquals(
            DistanceDirectionCalculator.DIRECTION_CENTER,
            DistanceDirectionCalculator.calculateDirection(0f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatioOver040ReturnsVeryClose() {
        // 800x600 on 1000x1000 -> ratio=0.48 -> VERY_CLOSE
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_VERY_CLOSE,
            DistanceDirectionCalculator.calculateDistanceState(100f, 100f, 900f, 700f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatioExactly040ReturnsNear() {
        // area exactly 0.40 is NOT > 0.40, so -> NEAR
        val side = Math.sqrt(400_000.0).toFloat()
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_NEAR,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, side, side, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatio016ReturnsNear() {
        // 400x400 on 1000x1000 -> ratio=0.16 -> NEAR
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_NEAR,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, 400f, 400f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatioBelow015ReturnsIgnore() {
        // 100x100 on 1000x1000 -> ratio=0.01 -> IGNORE
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_IGNORE,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, 100f, 100f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceZeroScreenWidthReturnsIgnore() {
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_IGNORE,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, 500f, 500f, 0f, 1000f)
        )
    }

    @Test
    fun distanceZeroScreenHeightReturnsIgnore() {
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_IGNORE,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, 500f, 500f, 1000f, 0f)
        )
    }

    @Test
    fun distanceInvertedBoxReturnsIgnore() {
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_IGNORE,
            DistanceDirectionCalculator.calculateDistanceState(500f, 500f, 100f, 100f, 1000f, 1000f)
        )
    }

    @Test
    fun areaRatioNormalBox() {
        assertEquals(0.25f, DistanceDirectionCalculator.calculateAreaRatio(0f, 0f, 500f, 500f, 1000f, 1000f), 0.001f)
    }

    @Test
    fun areaRatioOversizedBoxClampedTo1() {
        assertEquals(1.0f, DistanceDirectionCalculator.calculateAreaRatio(0f, 0f, 2000f, 2000f, 1000f, 1000f), 0.001f)
    }

    @Test
    fun areaRatioZeroBoxReturnsZero() {
        assertEquals(0.0f, DistanceDirectionCalculator.calculateAreaRatio(200f, 200f, 200f, 200f, 1000f, 1000f), 0.001f)
    }

    @Test
    fun areaRatioZeroScreenReturnsZero() {
        assertEquals(0.0f, DistanceDirectionCalculator.calculateAreaRatio(0f, 0f, 500f, 500f, 0f, 1000f), 0.001f)
    }

    @Test
    fun analyzeCloseCenterObjectShouldAlertTrue() {
        // 100,0,900,1000 -> area=800*1000=800000/1000000=0.80 -> VERY_CLOSE; centerX=500 -> CENTER
        val r = DistanceDirectionCalculator.analyze(100f, 0f, 900f, 1000f, 1000f, 1000f, "chair")
        assertEquals("chair", r.label)
        assertEquals(DistanceDirectionCalculator.DIRECTION_CENTER, r.direction)
        assertEquals(DistanceDirectionCalculator.DISTANCE_VERY_CLOSE, r.distanceState)
        assertTrue(r.shouldAlert)
    }

    @Test
    fun analyzeFarObjectShouldAlertFalse() {
        val r = DistanceDirectionCalculator.analyze(0f, 0f, 100f, 100f, 1000f, 1000f, "dog")
        assertEquals(DistanceDirectionCalculator.DISTANCE_IGNORE, r.distanceState)
        assertFalse(r.shouldAlert)
    }

    @Test
    fun analyzeNearDistanceShouldAlertTrue() {
        val r = DistanceDirectionCalculator.analyze(0f, 0f, 400f, 400f, 1000f, 1000f, "car")
        assertEquals(DistanceDirectionCalculator.DISTANCE_NEAR, r.distanceState)
        assertTrue(r.shouldAlert)
    }

    @Test
    fun toVoiceCueFormatsCorrectly() {
        val r = DistanceDirectionCalculator.AnalysisResult(
            label = "chair",
            direction = DistanceDirectionCalculator.DIRECTION_LEFT,
            distanceState = DistanceDirectionCalculator.DISTANCE_VERY_CLOSE,
            shouldAlert = true
        )
        assertEquals("chair on your left, very close", r.toVoiceCue())
    }
}
