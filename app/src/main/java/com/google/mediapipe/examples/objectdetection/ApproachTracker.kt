package com.google.mediapipe.examples.objectdetection

import android.util.Log

/**
 * ApproachTracker — Step 3: Sudden / Approaching Object Danger Alert
 *
 * Tracks each detected object across frames by matching label + nearest centerX.
 * Computes areaRatio growth rate per second and classifies objects as DANGER when:
 *
 *  (a) Growth rate > [GROWTH_RATE_THRESHOLD] per second AND areaRatio > [MIN_AREA_FOR_GROWTH_DANGER]
 *  OR
 *  (b) Object is a person/vehicle/animal that appeared suddenly with areaRatio > [SUDDEN_APPEAR_THRESHOLD]
 *
 * ── Tuning constants (at the top of this file) ──────────────────────────────
 */
class ApproachTracker {

    companion object {
        private const val TAG = "ApproachTracker"

        // ── TUNING CONSTANTS ─────────────────────────────────────────────────

        /**
         * Growth rate threshold (areaRatio per second).
         * If an object's bounding-box area ratio grows faster than this, it is approaching.
         * Start value: +0.25 / second.  Increase if you get too many false positives outdoors.
         */
        const val GROWTH_RATE_THRESHOLD = 0.25f

        /**
         * Minimum areaRatio for the growth-rate path to fire.
         * Prevents tiny far-away objects with noisy coordinates from triggering DANGER.
         */
        const val MIN_AREA_FOR_GROWTH_DANGER = 0.10f

        /**
         * If a person / vehicle / animal appears for the first time with areaRatio above this,
         * it is classified as DANGER (sudden appearance path).
         */
        const val SUDDEN_APPEAR_THRESHOLD = 0.25f

        /**
         * Max age of a tracked entry before it is evicted (milliseconds).
         * If an object disappears for longer than this, its history is cleared.
         */
        const val TRACK_MAX_AGE_MS = 1500L

        /**
         * When matching a detection to an existing track, the centerX must be within this
         * fraction of the frame width to count as the same object.
         */
        const val MATCH_CENTER_X_TOLERANCE = 0.25f

        /** Labels considered "person/vehicle/animal" for the sudden-appearance path. */
        private val SUDDEN_DANGER_LABELS: Set<String> = setOf(
            "person", "human", "pedestrian", "child",
            "car", "truck", "vehicle", "bus", "motorcycle", "motorbike",
            "bicycle", "bike", "van", "auto", "rickshaw",
            "dog", "cat", "cow", "horse", "sheep", "goat", "animal"
        )
    }

    /** Per-label tracking entry */
    data class TrackEntry(
        val label: String,
        val areaRatio: Float,
        val centerX: Float, // normalised 0..1
        val timestampMs: Long
    )

    // Map of label → last known track entry
    private val tracks: MutableMap<String, TrackEntry> = HashMap()

    /**
     * Evaluates a single detection and returns true if it qualifies as DANGER.
     *
     * @param label         Detection label (e.g. "person").
     * @param centerXNorm   Normalised horizontal centre of bounding box (0..1).
     * @param areaRatio     Bounding box area / screen area (0..1).
     * @param nowMs         Current timestamp in ms (injectable for tests).
     * @return true if the object should be classified as DANGER.
     */
    fun evaluate(
        label: String,
        centerXNorm: Float,
        areaRatio: Float,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        evictStaleTracks(nowMs)

        val key = label.lowercase().trim()
        val prev = findBestMatch(key, centerXNorm)

        return if (prev == null) {
            // First time we see this object — check sudden-appearance path
            tracks[key] = TrackEntry(key, areaRatio, centerXNorm, nowMs)
            val isSuddenDanger = key in SUDDEN_DANGER_LABELS && areaRatio >= SUDDEN_APPEAR_THRESHOLD
            if (isSuddenDanger) {
                Log.i(TAG, "DANGER (sudden): $key areaRatio=$areaRatio >= $SUDDEN_APPEAR_THRESHOLD")
            }
            isSuddenDanger
        } else {
            // We have history — compute growth rate
            val dtSeconds = (nowMs - prev.timestampMs) / 1000f
            val growthRate = if (dtSeconds > 0f) (areaRatio - prev.areaRatio) / dtSeconds else 0f

            // Update track
            tracks[key] = TrackEntry(key, areaRatio, centerXNorm, nowMs)

            val isDanger = growthRate > GROWTH_RATE_THRESHOLD && areaRatio >= MIN_AREA_FOR_GROWTH_DANGER
            if (isDanger) {
                Log.i(TAG, "DANGER (growth): $key growthRate=$growthRate/s areaRatio=$areaRatio")
            } else {
                Log.v(TAG, "Track ok: $key growthRate=$growthRate/s areaRatio=$areaRatio")
            }
            isDanger
        }
    }

    /** Find the existing track for [label] whose centerX is closest to [centerXNorm]. */
    private fun findBestMatch(label: String, centerXNorm: Float): TrackEntry? {
        val candidate = tracks[label] ?: return null
        return if (Math.abs(candidate.centerX - centerXNorm) <= MATCH_CENTER_X_TOLERANCE) {
            candidate
        } else {
            null
        }
    }

    /** Remove tracks older than [TRACK_MAX_AGE_MS]. */
    private fun evictStaleTracks(nowMs: Long) {
        val iter = tracks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (nowMs - entry.value.timestampMs > TRACK_MAX_AGE_MS) {
                Log.v(TAG, "Evicting stale track: ${entry.key}")
                iter.remove()
            }
        }
    }

    /** Reset all tracking state (call when camera session restarts). */
    fun reset() {
        tracks.clear()
        Log.i(TAG, "ApproachTracker reset")
    }
}
