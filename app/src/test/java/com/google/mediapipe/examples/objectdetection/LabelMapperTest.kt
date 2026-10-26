package com.google.mediapipe.examples.objectdetection

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
