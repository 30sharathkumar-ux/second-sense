package com.secondsense.alertmanager

import android.util.Log

/**
 * AlertRuleEngine — Step 3: DANGER fast path integrated
 *
 * Rules:
 *  Rule #1 — Consecutive-frame confirmation (3 frames for normal, 2 for DANGER)
 *  Rule #2 — Per-object cooldown (3 s normal, 1.5 s for DANGER)
 *  Rule #3 — Distance-based vibration pattern
 *  Rule #4 — Priority ordering (DANGER > HAZARD > PERSON_VEH > OTHER)
 */
class AlertRuleEngine {

    companion object {
        private const val TAG = "AlertRuleEngine"

        // ── Rule #1 ──────────────────────────────────────────────────────────
        /** Consecutive frames needed before a normal alert fires. */
        const val REQUIRED_CONSECUTIVE_FRAMES = 3

        /** Consecutive frames needed before a DANGER alert fires (fast path). */
        const val DANGER_CONSECUTIVE_FRAMES = 2

        // ── Rule #2 ──────────────────────────────────────────────────────────
        /** Cooldown for normal alerts (ms). */
        const val ALERT_COOLDOWN_MS = 3_000L

        /** Cooldown for DANGER alerts (ms) — shorter so re-alerts fire quickly. */
        const val DANGER_COOLDOWN_MS = 1_500L

        // ── Rule #3 ──────────────────────────────────────────────────────────
        private const val VIBRATE_DURATION_MS = 150L

        private fun gapForDistance(distanceCm: Float): Long = when {
            distanceCm < 50f  ->  80L
            distanceCm < 100f -> 150L
            distanceCm < 200f -> 300L
            distanceCm < 350f -> 500L
            else              -> 800L
        }

        /**
         * Rule #3: distance-based waveform for VibrationEffect.createWaveform.
         * 3-pulse pattern: [delay, on, off, on, off, on]
         */
        fun vibrationPattern(distanceCm: Float, strength: Int): VibrationPattern {
            val gap = gapForDistance(distanceCm)
            val amp = strength.coerceIn(1, 255)
            val timings    = longArrayOf(0, VIBRATE_DURATION_MS, gap, VIBRATE_DURATION_MS, gap, VIBRATE_DURATION_MS)
            val amplitudes = intArrayOf(0, amp, 0, amp, 0, amp)
            return VibrationPattern(timings, amplitudes, repeatIndex = -1)
        }

        /** Strongest fast vibration pattern for DANGER alerts. */
        fun dangerVibrationPattern(): VibrationPattern {
            // Very rapid 5-pulse burst at max amplitude
            val timings    = longArrayOf(0, 200, 50, 200, 50, 200, 50, 200, 50, 200)
            val amplitudes = intArrayOf(0, 255,  0, 255,  0, 255,  0, 255,  0, 255)
            return VibrationPattern(timings, amplitudes, repeatIndex = -1)
        }
    }

    // ── Rule #1 state ─────────────────────────────────────────────────────────
    private val consecutiveFrames: MutableMap<String, Int> = HashMap()

    // ── Rule #2 state ─────────────────────────────────────────────────────────
    private val lastAlertTime: MutableMap<String, Long> = HashMap()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process one frame. Returns the highest-priority [AlertCandidate] that passes
     * all rules, or null if none qualify.
     *
     * @param detectedObjects Objects detected in this frame, with [isDanger] pre-set by caller.
     * @param nowMs           Current time (injectable for tests).
     */
    fun onFrame(
        detectedObjects: List<DetectedObject>,
        nowMs: Long = System.currentTimeMillis()
    ): AlertCandidate? {

        val seenLabels = detectedObjects.map { it.label.lowercase().trim() }.toSet()

        // Decay counters for objects that disappeared
        val stalledLabels = consecutiveFrames.keys.toSet() - seenLabels
        stalledLabels.forEach { label ->
            consecutiveFrames[label] = 0
            Log.v(TAG, "Rule#1: '$label' disappeared — counter reset")
        }

        // Increment counters for currently visible objects
        for (obj in detectedObjects) {
            val key   = obj.label.lowercase().trim()
            val count = (consecutiveFrames[key] ?: 0) + 1
            consecutiveFrames[key] = count
            Log.v(TAG, "Rule#1: '$key' frames=$count")
        }

        // Collect objects that pass Rules #1 and #2
        val candidates: List<AlertCandidate> = detectedObjects.mapNotNull { obj ->
            val key = obj.label.lowercase().trim()
            val count = consecutiveFrames[key] ?: 0

            // Determine required frames and cooldown based on danger tier
            val requiredFrames = if (obj.isDanger) DANGER_CONSECUTIVE_FRAMES else REQUIRED_CONSECUTIVE_FRAMES
            val cooldown       = if (obj.isDanger) DANGER_COOLDOWN_MS        else ALERT_COOLDOWN_MS

            // Rule #1 gate
            if (count < requiredFrames) {
                Log.d(TAG, "Rule#1 BLOCKED '$key': $count/$requiredFrames frames")
                return@mapNotNull null
            }

            // Rule #2 gate
            val lastTime  = lastAlertTime[key] ?: 0L
            val elapsedMs = nowMs - lastTime
            if (elapsedMs < cooldown) {
                Log.d(TAG, "Rule#2 BLOCKED '$key': cooldown ${cooldown - elapsedMs}ms")
                return@mapNotNull null
            }

            AlertCandidate(obj, nowMs)
        }

        if (candidates.isEmpty()) return null

        // Rule #4: sort by priority level, then by proximity (area ratio descending = closer)
        val best = candidates.sortedWith(
            compareBy<AlertCandidate> { it.obj.priority.level }
                .thenBy { it.obj.distanceCm }
        ).first()

        val bestKey = best.obj.label.lowercase().trim()
        lastAlertTime[bestKey] = nowMs
        Log.i(TAG, "ALERT APPROVED: '${best.obj.label}' priority=${best.obj.priority.label} danger=${best.obj.isDanger}")

        return best
    }

    /**
     * Resets all frame counters and cooldown timestamps.
     * Call when the camera session restarts or the user resets state.
     */
    fun reset() {
        consecutiveFrames.clear()
        lastAlertTime.clear()
        Log.i(TAG, "AlertRuleEngine state reset")
    }

    fun isInCooldown(label: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastTime = lastAlertTime[label.lowercase().trim()] ?: return false
        return (nowMs - lastTime) < ALERT_COOLDOWN_MS
    }

    fun frameCount(label: String): Int =
        consecutiveFrames[label.lowercase().trim()] ?: 0

    fun cooldownRemainingMs(label: String, nowMs: Long = System.currentTimeMillis()): Long {
        val lastTime = lastAlertTime[label.lowercase().trim()] ?: return 0L
        return (ALERT_COOLDOWN_MS - (nowMs - lastTime)).coerceAtLeast(0L)
    }
}

// ─── Supporting data classes ─────────────────────────────────────────────────

data class AlertCandidate(
    val obj: DetectedObject,
    val firedAtMs: Long
)

data class VibrationPattern(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeatIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VibrationPattern) return false
        return repeatIndex == other.repeatIndex &&
               timings.contentEquals(other.timings) &&
               amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int {
        var result = timings.contentHashCode()
        result = 31 * result + amplitudes.contentHashCode()
        result = 31 * result + repeatIndex
        return result
    }
}
