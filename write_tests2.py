import os

# ── DistanceDirectionCalculatorTest ──────────────────────────────────────────
ddc = r"""package com.google.mediapipe.examples.objectdetection

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
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_VERY_CLOSE,
            DistanceDirectionCalculator.calculateDistanceState(100f, 100f, 900f, 700f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatioExactly040ReturnsNear() {
        val side = Math.sqrt(400_000.0).toFloat()
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_NEAR,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, side, side, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatio016ReturnsNear() {
        assertEquals(
            DistanceDirectionCalculator.DISTANCE_NEAR,
            DistanceDirectionCalculator.calculateDistanceState(0f, 0f, 400f, 400f, 1000f, 1000f)
        )
    }

    @Test
    fun distanceRatioBelow015ReturnsIgnore() {
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
    fun areaRatioOverSizedBoxClampedTo1() {
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
        val r = DistanceDirectionCalculator.analyze(300f, 100f, 700f, 800f, 1000f, 1000f, "chair")
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
"""

# ── LabelMapperTest ────────────────────────────────────────────────────────────
lm = r"""package com.google.mediapipe.examples.objectdetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelMapperTest {

    @Test
    fun thresholdChairIsLow() {
        assertEquals(LabelMapper.THRESHOLD_LOW, LabelMapper.thresholdFor("chair"))
    }

    @Test
    fun thresholdPersonIsLow() {
        assertEquals(LabelMapper.THRESHOLD_LOW, LabelMapper.thresholdFor("person"))
    }

    @Test
    fun thresholdBenchIsLow() {
        assertEquals(LabelMapper.THRESHOLD_LOW, LabelMapper.thresholdFor("bench"))
    }

    @Test
    fun thresholdDiningTableIsLow() {
        assertEquals(LabelMapper.THRESHOLD_LOW, LabelMapper.thresholdFor("dining table"))
    }

    @Test
    fun thresholdCarIsDefault() {
        assertEquals(LabelMapper.THRESHOLD_DEFAULT, LabelMapper.thresholdFor("car"))
    }

    @Test
    fun thresholdUnknownIsDefault() {
        assertEquals(LabelMapper.THRESHOLD_DEFAULT, LabelMapper.thresholdFor("toaster"))
    }

    @Test
    fun thresholdCaseInsensitiveChairIsLow() {
        assertEquals(LabelMapper.THRESHOLD_LOW, LabelMapper.thresholdFor("Chair"))
    }

    @Test
    fun mapRefrigeratorToObstacle() {
        assertEquals("obstacle", LabelMapper.map("refrigerator"))
    }

    @Test
    fun mapTvToObstacle() {
        assertEquals("obstacle", LabelMapper.map("tv"))
    }

    @Test
    fun mapMicrowaveToObstacle() {
        assertEquals("obstacle", LabelMapper.map("microwave"))
    }

    @Test
    fun mapClockToObstacle() {
        assertEquals("obstacle", LabelMapper.map("clock"))
    }

    @Test
    fun mapDiningTableToTable() {
        assertEquals("table", LabelMapper.map("dining table"))
    }

    @Test
    fun mapChairToChair() {
        assertEquals("chair", LabelMapper.map("chair"))
    }

    @Test
    fun mapBenchToBench() {
        assertEquals("bench", LabelMapper.map("bench"))
    }

    @Test
    fun mapLargeBoxNonExemptToWall() {
        assertEquals("wall", LabelMapper.map("clock", boxWidthRatio = 0.65f, boxHeightRatio = 0.55f))
    }

    @Test
    fun mapLargeBoxExemptPersonStaysPerson() {
        assertEquals("person", LabelMapper.map("person", boxWidthRatio = 0.65f, boxHeightRatio = 0.55f))
    }

    @Test
    fun mapLargeBoxExemptCarStaysCar() {
        assertEquals("car", LabelMapper.map("car", boxWidthRatio = 0.70f, boxHeightRatio = 0.60f))
    }

    @Test
    fun mapExactWidthBoundaryTriggersWall() {
        assertEquals("wall", LabelMapper.map("laptop", boxWidthRatio = 0.60f, boxHeightRatio = 0.55f))
    }

    @Test
    fun mapJustBelowWidthThresholdNoWall() {
        assertEquals("obstacle", LabelMapper.map("laptop", boxWidthRatio = 0.59f, boxHeightRatio = 0.55f))
    }

    @Test
    fun mapJustBelowHeightThresholdNoWall() {
        assertEquals("obstacle", LabelMapper.map("laptop", boxWidthRatio = 0.65f, boxHeightRatio = 0.49f))
    }

    @Test
    fun mapPersonPassThrough() {
        assertEquals("person", LabelMapper.map("person"))
    }

    @Test
    fun mapCaseInsensitivePersonLowercase() {
        assertEquals("person", LabelMapper.map("PERSON"))
    }

    @Test
    fun mapDogPassThrough() {
        assertEquals("dog", LabelMapper.map("dog"))
    }

    @Test
    fun isHazardChairTrue() {
        assertTrue(LabelMapper.isHazard("chair"))
    }

    @Test
    fun isHazardPersonTrue() {
        assertTrue(LabelMapper.isHazard("person"))
    }

    @Test
    fun isHazardCarTrue() {
        assertTrue(LabelMapper.isHazard("car"))
    }

    @Test
    fun isHazardBusTrue() {
        assertTrue(LabelMapper.isHazard("bus"))
    }

    @Test
    fun isHazardDogFalse() {
        assertFalse(LabelMapper.isHazard("dog"))
    }

    @Test
    fun isHazardUnknownFalse() {
        assertFalse(LabelMapper.isHazard("toaster"))
    }

    @Test
    fun isHazardCaseInsensitive() {
        assertTrue(LabelMapper.isHazard("CHAIR"))
    }
}
"""

# ── MessagesTest ───────────────────────────────────────────────────────────────
msg = r"""package com.google.mediapipe.examples.objectdetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun alertEnglishVeryCloseChairOnLeft() {
        val m = Messages.alert(Messages.Language.ENGLISH, "chair", "on your left", "very close")
        assertEquals("chair to your left, very close", m)
    }

    @Test
    fun alertHindiVeryCloseChairOnRight() {
        val m = Messages.alert(Messages.Language.HINDI, "chair", "on your right", "very close")
        assertTrue(m.contains("\u092c\u0939\u0941\u0924 \u092a\u093e\u0938"))
        assertTrue(m.contains("\u0926\u093e\u0908\u0902 \u0924\u0930\u092b"))
    }

    @Test
    fun alertKannadaNearAhead() {
        val m = Messages.alert(Messages.Language.KANNADA, "car", "straight ahead", "near")
        assertTrue(m.contains("\u0cb9\u0ca4\u0ccd\u0ca4\u0cbf\u0cb0\u0ca6\u0cb2\u0ccd\u0cb2\u0cbf"))
        assertTrue(m.contains("\u0cae\u0cc1\u0c82\u0ca6\u0cc6"))
    }

    @Test
    fun alertIgnoreDistanceNoSuffix() {
        val m = Messages.alert(Messages.Language.ENGLISH, "person", "straight ahead", "ignore")
        assertEquals("person ahead", m)
    }

    @Test
    fun alertLabelLowercased() {
        val m = Messages.alert(Messages.Language.ENGLISH, "DOG", "straight ahead", "ignore")
        assertTrue(m.startsWith("dog"))
    }

    @Test
    fun dangerAlertEnglishLeft() {
        val m = Messages.dangerAlert(Messages.Language.ENGLISH, "person", "on your left")
        assertEquals("Warning! person approaching to your left", m)
    }

    @Test
    fun dangerAlertHindiRight() {
        val m = Messages.dangerAlert(Messages.Language.HINDI, "car", "on your right")
        assertTrue(m.contains("\u091a\u0947\u0924\u093e\u0935\u0928\u0940"))
        assertTrue(m.contains("\u0926\u093e\u0908\u0902 \u0924\u0930\u092b"))
    }

    @Test
    fun dangerAlertKannadaAhead() {
        val m = Messages.dangerAlert(Messages.Language.KANNADA, "truck", "straight ahead")
        assertTrue(m.contains("\u0c8e\u0c9a\u0ccd\u0c9a\u0cb0\u0cbf\u0c95\u0cc6"))
        assertTrue(m.contains("\u0cae\u0cc1\u0c82\u0ca6\u0cc6"))
    }

    @Test
    fun wallAheadEnglish() {
        assertEquals("Wall ahead", Messages.wallAhead(Messages.Language.ENGLISH))
    }

    @Test
    fun wallAheadHindiNonEmpty() {
        assertTrue(Messages.wallAhead(Messages.Language.HINDI).isNotEmpty())
    }

    @Test
    fun wallAheadKannadaNonEmpty() {
        assertTrue(Messages.wallAhead(Messages.Language.KANNADA).isNotEmpty())
    }

    @Test
    fun wallWithGuidanceEnglish() {
        assertEquals("Wall ahead, move left", Messages.wallWithGuidance(Messages.Language.ENGLISH, "move left"))
    }

    @Test
    fun obstacleAheadEnglishRight() {
        assertEquals("Obstacle to your right", Messages.obstacleAhead(Messages.Language.ENGLISH, "on your right"))
    }

    @Test
    fun obstacleAheadKannadaAhead() {
        assertTrue(Messages.obstacleAhead(Messages.Language.KANNADA, "straight ahead").contains("\u0cae\u0cc1\u0c82\u0ca6\u0cc6"))
    }

    @Test
    fun potholeEnglishLeft() {
        assertEquals("Pothole to your left", Messages.pothole(Messages.Language.ENGLISH, "on your left"))
    }

    @Test
    fun potholeHindiRight() {
        val m = Messages.pothole(Messages.Language.HINDI, "on your right")
        assertTrue(m.contains("\u0917\u0921\u093c\u094d\u0922\u093e"))
        assertTrue(m.contains("\u0926\u093e\u0908\u0902 \u0924\u0930\u092b"))
    }

    @Test
    fun potholeKannadaAhead() {
        assertTrue(Messages.pothole(Messages.Language.KANNADA, "straight ahead").contains("\u0c97\u0cc1\u0c82\u0ca1\u0cbf"))
    }

    @Test
    fun stairsEnglishAhead() {
        assertEquals("Steps ahead", Messages.stairs(Messages.Language.ENGLISH, "straight ahead"))
    }

    @Test
    fun stairsHindiLeft() {
        assertTrue(Messages.stairs(Messages.Language.HINDI, "on your left").contains("\u0938\u0940\u0922\u093c\u093f\u092f\u093e\u0901"))
    }

    @Test
    fun stairsKannadaRight() {
        assertTrue(Messages.stairs(Messages.Language.KANNADA, "on your right").contains("\u0cae\u0cc6\u0c9f\u0ccd\u0c9f\u0cbf\u0cb2\u0cc1\u0c97\u0cb3\u0cbf\u0cb5\u0cc6"))
    }

    @Test
    fun doorEnglishRight() {
        assertEquals("Door to your right", Messages.door(Messages.Language.ENGLISH, "on your right"))
    }

    @Test
    fun doorHindiAhead() {
        assertTrue(Messages.door(Messages.Language.HINDI, "straight ahead").contains("\u0926\u0930\u0935\u093e\u091c\u093c\u093e"))
    }

    @Test
    fun doorKannadaLeft() {
        assertTrue(Messages.door(Messages.Language.KANNADA, "on your left").contains("\u0cac\u0cbe\u0c97\u0cbf\u0cb2\u0cc1"))
    }

    @Test
    fun battery30English() {
        assertEquals("Battery 30 percent", Messages.battery30(Messages.Language.ENGLISH))
    }

    @Test
    fun battery20KannadaNonEmpty() {
        assertTrue(Messages.battery20(Messages.Language.KANNADA).isNotEmpty())
    }

    @Test
    fun battery10EnglishContainsCharge() {
        assertTrue(Messages.battery10(Messages.Language.ENGLISH).contains("charge", ignoreCase = true))
    }

    @Test
    fun battery5EnglishContainsCritical() {
        assertTrue(Messages.battery5(Messages.Language.ENGLISH).contains("critical", ignoreCase = true))
    }

    @Test
    fun scanStartedEnglish() {
        assertEquals("Second Sense started. Scanning active.", Messages.scanStarted(Messages.Language.ENGLISH))
    }

    @Test
    fun scanStoppedEnglish() {
        assertEquals("Second Sense stopped.", Messages.scanStopped(Messages.Language.ENGLISH))
    }

    @Test
    fun scanStartedHindiNonEmpty() {
        assertTrue(Messages.scanStarted(Messages.Language.HINDI).isNotEmpty())
    }

    @Test
    fun scanStoppedKannadaNonEmpty() {
        assertTrue(Messages.scanStopped(Messages.Language.KANNADA).isNotEmpty())
    }

    @Test
    fun moveLeftEnglish() {
        assertEquals("move left", Messages.moveLeft(Messages.Language.ENGLISH))
    }

    @Test
    fun moveRightKannadaNonEmpty() {
        assertTrue(Messages.moveRight(Messages.Language.KANNADA).isNotEmpty())
    }

    @Test
    fun stopHindiNonEmpty() {
        assertTrue(Messages.stop(Messages.Language.HINDI).isNotEmpty())
    }
}
"""

base = r"app\src\test\java\com\google\mediapipe\examples\objectdetection"
for fname, content in [
    ("DistanceDirectionCalculatorTest.kt", ddc),
    ("LabelMapperTest.kt", lm),
    ("MessagesTest.kt", msg),
]:
    with open(base + "\\" + fname, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"Written {fname}")

