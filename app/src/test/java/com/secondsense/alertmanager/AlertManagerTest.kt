package com.secondsense.alertmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AlertManager logic.
 */
class AlertManagerTest {

    @Test
    fun testStrengthBoundaries() {
        assertEquals(1, AlertManager.MIN_STRENGTH)
        assertEquals(255, AlertManager.MAX_STRENGTH)
        assertTrue(AlertManager.DEFAULT_VIBRATION_DURATION_MS > 0)
    }

    @Test
    fun testStrengthMappingCalculation() {
        // Test 1-10 mapping scale to 1-255
        val scaleStrength = 5
        val mappedAmplitude = ((scaleStrength / 10f) * AlertManager.MAX_STRENGTH).toInt().coerceIn(AlertManager.MIN_STRENGTH, AlertManager.MAX_STRENGTH)
        assertEquals(127, mappedAmplitude)

        val fullScale = 10
        val maxMapped = ((fullScale / 10f) * AlertManager.MAX_STRENGTH).toInt().coerceIn(AlertManager.MIN_STRENGTH, AlertManager.MAX_STRENGTH)
        assertEquals(255, maxMapped)
    }
}
