package com.google.mediapipe.examples.objectdetection

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
        assertTrue(m.isNotEmpty())
    }

    @Test
    fun alertKannadaNearAhead() {
        val m = Messages.alert(Messages.Language.KANNADA, "car", "straight ahead", "near")
        assertTrue(m.isNotEmpty())
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
    fun alertEnglishNearDistanceSuffix() {
        val m = Messages.alert(Messages.Language.ENGLISH, "car", "straight ahead", "near")
        assertEquals("car ahead, nearby", m)
    }

    @Test
    fun dangerAlertEnglishLeft() {
        val m = Messages.dangerAlert(Messages.Language.ENGLISH, "person", "on your left")
        assertEquals("Warning! person approaching to your left", m)
    }

    @Test
    fun dangerAlertHindiNonEmpty() {
        val m = Messages.dangerAlert(Messages.Language.HINDI, "car", "on your right")
        assertTrue(m.isNotEmpty())
    }

    @Test
    fun dangerAlertKannadaNonEmpty() {
        val m = Messages.dangerAlert(Messages.Language.KANNADA, "truck", "straight ahead")
        assertTrue(m.isNotEmpty())
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
    fun obstacleAheadKannadaNonEmpty() {
        assertTrue(Messages.obstacleAhead(Messages.Language.KANNADA, "straight ahead").isNotEmpty())
    }

    @Test
    fun potholeEnglishLeft() {
        assertEquals("Pothole to your left", Messages.pothole(Messages.Language.ENGLISH, "on your left"))
    }

    @Test
    fun potholeHindiNonEmpty() {
        assertTrue(Messages.pothole(Messages.Language.HINDI, "on your right").isNotEmpty())
    }

    @Test
    fun potholeKannadaNonEmpty() {
        assertTrue(Messages.pothole(Messages.Language.KANNADA, "straight ahead").isNotEmpty())
    }

    @Test
    fun stairsEnglishAhead() {
        assertEquals("Steps ahead", Messages.stairs(Messages.Language.ENGLISH, "straight ahead"))
    }

    @Test
    fun stairsHindiNonEmpty() {
        assertTrue(Messages.stairs(Messages.Language.HINDI, "on your left").isNotEmpty())
    }

    @Test
    fun stairsKannadaNonEmpty() {
        assertTrue(Messages.stairs(Messages.Language.KANNADA, "on your right").isNotEmpty())
    }

    @Test
    fun doorEnglishRight() {
        assertEquals("Door to your right", Messages.door(Messages.Language.ENGLISH, "on your right"))
    }

    @Test
    fun doorHindiNonEmpty() {
        assertTrue(Messages.door(Messages.Language.HINDI, "straight ahead").isNotEmpty())
    }

    @Test
    fun doorKannadaNonEmpty() {
        assertTrue(Messages.door(Messages.Language.KANNADA, "on your left").isNotEmpty())
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
