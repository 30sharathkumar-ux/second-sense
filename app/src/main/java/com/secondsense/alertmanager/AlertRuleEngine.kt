package com.secondsense.alertmanager

import android.util.Log

/**
 * AlertRuleEngine — Pair B Rule Filter
 *
 * All 4 Pair-B alerting rules are enforced here before any TTS or vibration
 * command is passed down to AlertManager.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Rule #1 — Consecutive-frame confirmation (stops false alarms)
 *   An object must appear in [REQUIRED_CONSECUTIVE_FRAMES] consecutive frames
 *   before an alert is issued.
 *
 * Rule #2 — Per-object cooldown (no alert spam)
 *   After an alert fires for a given object label, that label is silenced for
 *   [ALERT_COOLDOWN_MS] milliseconds.
 *
 * Rule #3 — Distance-based vibration pattern (faster = closer)
 *   vibrationPattern(distanceCm) produces a repeating on/off waveform whose
 *   period shortens as the object gets closer.
 *
 * Rule #4 — Priority ordering (person / vehicle / stray animal first)
 *   When multiple objects are pending, CRITICAL priority alerts are dispatched
 *   before HIGH, MEDIUM, or LOW ones. Handled by AlertPriority.
 * ─────────────────────────────────────────────────────────────────────────
 */
class AlertRuleEngine {

    companion object {
        private const val TAG = "AlertRuleEngine"

        // ── Rule #1 ──────────────────────────────────────────────────────
        /** Number of consecutive frames an object must be seen before alerting. */
        const val REQUIRED_CONSECUTIVE_FRAMES = 3

        // ── Rule #2 ──────────────────────────────────────────────────────
        /** Minimum gap between two alerts for the same object label (ms). */
        const val ALERT_COOLDOWN_MS = 3_000L

        // ── Rule #3 ──────────────────────────────────────────────────────
        /** Vibration waveform timings at different distance bands (ms). */
        private const val VIBRATE_DURATION_MS   = 150L   // on-time per pulse

        /**
         * Off-pause (gap between pulses) by distance band:
         *  < 50 cm  → 80 ms  (rapid urgent buzzing)
         *  < 100 cm → 150 ms (fast)
         *  < 200 cm → 300 ms (medium)
         *  < 350 cm → 500 ms (slow)
         *  ≥ 350 cm → 800 ms (very slow awareness tap)
         */
        private fun gapForDistance(distanceCm: Float): Long = when {
            distanceCm < 50f  ->  80L
            distanceCm < 100f -> 150L
            distanceCm < 200f -> 300L
            distanceCm < 350f -> 500L
            else              -> 800L
        }

        /**
         * Rule #3: Returns a [VibrationEffect]-compatible timing waveform for
         * [VibrationEffect.createWaveform(timings, amplitudes, repeat)].
         *
         * Format: [delay, on, off, on, off, on]  (3 pulses; repeat = -1 for one-shot).
         * The amplitude list matches the timing list.
         *
         * @param distanceCm Distance to object in centimetres.
         * @param strength   Base amplitude [1..255].
         * @return [VibrationPattern] holding timings, amplitudes, and repeat index.
         */
        fun vibrationPattern(distanceCm: Float, strength: Int): VibrationPattern {
            val gap     = gapForDistance(distanceCm)
            val amp     = strength.coerceIn(1, 255)

            // 3-pulse waveform: 0ms delay, on, gap, on, gap, on
            val timings    = longArrayOf(0, VIBRATE_DURATION_MS, gap, VIBRATE_DURATION_MS, gap, VIBRATE_DURATION_MS)
            val amplitudes = intArrayOf(0, amp, 0, amp, 0, amp)

            return VibrationPattern(timings, amplitudes, repeatIndex = -1)
        }
    }

    // ── Rule #1 state: consecutive-frame counters ─────────────────────────
    /** Tracks how many consecutive frames each label has been detected. */
    private val consecutiveFrames: MutableMap<String, Int> = HashMap()

    // ── Rule #2 state: per-label cooldown timestamps ──────────────────────
    /** Records the [System.currentTimeMillis] when each label last fired an alert. */
    private val lastAlertTime: MutableMap<String, Long> = HashMap()

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Notify the engine that [detectedObjects] were seen in the current frame.
     *
     * The engine:
     *  1. Updates consecutive-frame counters for all seen objects (Rule #1).
     *  2. Resets counters for objects that were present last frame but are now gone.
     *  3. Applies priority ordering (Rule #4) to the candidates that pass Rules 1 & 2.
     *  4. Returns the highest-priority [AlertCandidate] that is ready to fire,
     *     or null if none qualify.
     *
     * Call this once per camera/sensor frame.
     *
     * @param detectedObjects All objects detected in the current frame.
     * @param nowMs           Current time in ms (injectable for unit-testing).
     * @return The single best [AlertCandidate] to act on, or null.
     */
    fun onFrame(
        detectedObjects: List<DetectedObject>,
        nowMs: Long = System.currentTimeMillis()
    ): AlertCandidate? {

        val seenLabels = detectedObjects.map { it.label.lowercase().trim() }.toSet()

        // ── Step 1: Decay counters for objects that disappeared ────────────
        val stalledLabels = consecutiveFrames.keys.toSet() - seenLabels
        stalledLabels.forEach { label ->
            consecutiveFrames[label] = 0
            Log.v(TAG, "Rule#1: '$label' disappeared — counter reset to 0")
        }

        // ── Step 2: Increment counters for currently visible objects ───────
        for (obj in detectedObjects) {
            val key   = obj.label.lowercase().trim()
            val count = (consecutiveFrames[key] ?: 0) + 1
            consecutiveFrames[key] = count
            Log.v(TAG, "Rule#1: '$key' consecutive frames = $count / $REQUIRED_CONSECUTIVE_FRAMES")
        }

        // ── Step 3: Collect objects that pass Rules #1 and #2 ─────────────
        val candidates: List<AlertCandidate> = detectedObjects.mapNotNull { obj ->
            val key   = obj.label.lowercase().trim()
            val count = consecutiveFrames[key] ?: 0

            // Rule #1 gate
            if (count < REQUIRED_CONSECUTIVE_FRAMES) {
                Log.d(TAG, "Rule#1 BLOCKED '$key': only $count/$REQUIRED_CONSECUTIVE_FRAMES frames")
                return@mapNotNull null
            }

            // Rule #2 gate
            val lastTime  = lastAlertTime[key] ?: 0L
            val elapsedMs = nowMs - lastTime
            if (elapsedMs < ALERT_COOLDOWN_MS) {
                val remainingMs = ALERT_COOLDOWN_MS - elapsedMs
                Log.d(TAG, "Rule#2 BLOCKED '$key': cooldown ${remainingMs}ms remaining")
                return@mapNotNull null
            }

            AlertCandidate(obj, nowMs)
        }

        if (candidates.isEmpty()) return null

        // ── Step 4: Sort by priority, then by distance (closer = first) ───
        // Rule #4: CRITICAL first; within same priority, nearest object first.
        val best = candidates.sortedWith(
            compareBy<AlertCandidate> { it.obj.priority.level }
                .thenBy { it.obj.distanceCm }
        ).first()

        // Mark the winner as alerted now (Rule #2 timestamp)
        val bestKey = best.obj.label.lowercase().trim()
        lastAlertTime[bestKey] = nowMs
        Log.i(TAG, "ALERT APPROVED: '${best.obj.label}' priority=${best.obj.priority.label} dist=${best.obj.distanceCm}cm")

        return best
    }

    /**
     * Resets all frame counters and cooldown timestamps.
     * Call when the camera session restarts or the user resets the state.
     */
    fun reset() {
        consecutiveFrames.clear()
        lastAlertTime.clear()
        Log.i(TAG, "AlertRuleEngine state reset")
    }

    /**
     * Checks whether an object label is currently within its cooldown window.
     * Useful for UI feedback without actually polling [onFrame].
     */
    fun isInCooldown(label: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastTime = lastAlertTime[label.lowercase().trim()] ?: return false
        return (nowMs - lastTime) < ALERT_COOLDOWN_MS
    }

    /**
     * Returns the current consecutive-frame count for [label].
     */
    fun frameCount(label: String): Int =
        consecutiveFrames[label.lowercase().trim()] ?: 0

    /**
     * Returns milliseconds remaining in the cooldown for [label], or 0 if not cooling down.
     */
    fun cooldownRemainingMs(label: String, nowMs: Long = System.currentTimeMillis()): Long {
        val lastTime = lastAlertTime[label.lowercase().trim()] ?: return 0L
        return (ALERT_COOLDOWN_MS - (nowMs - lastTime)).coerceAtLeast(0L)
    }
}

// ─── Supporting data classes ─────────────────────────────────────────────────

/**
 * An alert that has passed all 4 rules and is ready to be dispatched.
 *
 * @param obj      The [DetectedObject] that triggered this alert.
 * @param firedAtMs Epoch-ms when this candidate was approved.
 */
data class AlertCandidate(
    val obj: DetectedObject,
    val firedAtMs: Long
)

/**
 * Rule #3 output — encapsulates a VibrationEffect waveform.
 *
 * @param timings     On/off timing array for [VibrationEffect.createWaveform].
 * @param amplitudes  Amplitude array matching [timings].
 * @param repeatIndex Index in [timings] to loop from, or -1 for one-shot.
 */
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
