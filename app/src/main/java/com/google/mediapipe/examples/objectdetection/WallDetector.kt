package com.google.mediapipe.examples.objectdetection

import android.graphics.Bitmap
import android.util.Log

/**
 * WallDetector — Step 6: Wall/Obstacle Ahead Heuristic
 *
 * Analyses the centre strip of the camera frame to detect a flat,
 * featureless surface that fills the path (likely a wall/door).
 *
 * Algorithm:
 *   1. Downscale image to [SAMPLE_WIDTH] x [SAMPLE_HEIGHT] (very fast).
 *   2. Extract a horizontal strip from the middle [CENTER_STRIP_ROWS] rows.
 *   3. Compute per-pixel luminance and calculate variance across the strip.
 *   4. If variance < [WALL_VARIANCE_THRESHOLD], the strip is "uniform" → wall-like.
 *   5. Confirm over [WALL_CONFIRM_FRAMES] consecutive frames before alerting.
 *   6. Apply [WALL_COOLDOWN_MS] between successive alerts.
 *
 * ── Tuning constants ──────────────────────────────────────────────────────────
 * Increase WALL_VARIANCE_THRESHOLD if real walls are missed.
 * Decrease it if the sky or a plain floor causes false positives.
 */
class WallDetector {

    companion object {
        private const val TAG = "WallDetector"

        // ── TUNING CONSTANTS ─────────────────────────────────────────────────

        /** Downscale resolution for the analysis sample (width × height). */
        const val SAMPLE_WIDTH  = 160
        const val SAMPLE_HEIGHT = 120

        /** Number of centre rows to analyse (out of SAMPLE_HEIGHT). */
        const val CENTER_STRIP_ROWS = 40

        /**
         * Maximum luminance variance in the centre strip to classify as wall-like.
         * Lower = stricter (only very flat textures fire). Typical values: 150–600.
         * Default 400 works well for painted indoor walls at < 2 m distance.
         */
        const val WALL_VARIANCE_THRESHOLD = 400.0

        /**
         * Minimum fraction of analysed pixels that must be in the "uniform" luminance
         * range [mean ± uniform_band] for the wall check to pass.
         * Not currently used — variance alone is sufficient — kept for future tightening.
         */
        const val WALL_MIN_COVERAGE = 0.65f

        /** Consecutive frames that must score below WALL_VARIANCE_THRESHOLD before alerting. */
        const val WALL_CONFIRM_FRAMES = 3

        /** Minimum ms between successive wall alerts. */
        const val WALL_COOLDOWN_MS = 5_000L
    }

    private var confirmCount  = 0
    private var lastAlertTime = 0L

    /** Reusable sample bitmap — allocated once and reused every frame. */
    private var sampleBitmap: Bitmap? = null

    // Last computed variance (exposed for debug overlay in CameraFragment)
    var lastVariance: Double = Double.MAX_VALUE
        private set

    /**
     * Evaluate a single camera frame. Returns true if a wall is detected and
     * the cooldown has elapsed.
     *
     * This function must be called on a background thread (never the main thread).
     *
     * @param frameBitmap RGBA_8888 bitmap from ImageProxy (full resolution).
     */
    fun analyze(frameBitmap: Bitmap): Boolean {
        val sample = getOrCreateSample(frameBitmap.width, frameBitmap.height)

        // Downscale frame into the reusable sample bitmap
        val scaled = Bitmap.createScaledBitmap(frameBitmap, SAMPLE_WIDTH, SAMPLE_HEIGHT, false)
        val canvas = android.graphics.Canvas(sample)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle()

        // Compute luminance variance in the centre strip
        val variance = computeCentreVariance(sample)
        lastVariance = variance

        val isUniform = variance < WALL_VARIANCE_THRESHOLD
        Log.v(TAG, "Wall variance=$variance isUniform=$isUniform confirm=$confirmCount")

        if (isUniform) {
            confirmCount++
        } else {
            confirmCount = 0
            return false
        }

        if (confirmCount < WALL_CONFIRM_FRAMES) return false

        // Check cooldown
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAlertTime < WALL_COOLDOWN_MS) {
            Log.v(TAG, "Wall detected but in cooldown (${WALL_COOLDOWN_MS - (nowMs - lastAlertTime)}ms left)")
            return false
        }

        lastAlertTime = nowMs
        confirmCount  = 0
        Log.i(TAG, "WALL AHEAD detected (variance=$variance)")
        return true
    }

    /** Reset confirm counter (call when scanning stops). */
    fun reset() {
        confirmCount  = 0
        lastAlertTime = 0L
        lastVariance  = Double.MAX_VALUE
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Compute luminance variance across the horizontal centre strip of [sample].
     * Only reads pixel colours \u2014 no allocation of new arrays (uses primitive loops).
     */
    private fun computeCentreVariance(sample: Bitmap): Double {
        val startRow = (SAMPLE_HEIGHT - CENTER_STRIP_ROWS) / 2
        val endRow   = startRow + CENTER_STRIP_ROWS
        val w        = sample.width

        var sum      = 0.0
        var sumSq    = 0.0
        var count    = 0

        for (row in startRow until endRow) {
            for (col in 0 until w) {
                val pixel = sample.getPixel(col, row)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b =  pixel         and 0xFF
                // BT.601 luminance approximation (integer arithmetic)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b)
                sum   += lum
                sumSq += lum * lum
                count++
            }
        }

        if (count == 0) return Double.MAX_VALUE
        val mean     = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance
    }

    private fun getOrCreateSample(frameW: Int, frameH: Int): Bitmap {
        return sampleBitmap?.takeIf {
            it.width == SAMPLE_WIDTH && it.height == SAMPLE_HEIGHT
        } ?: Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
            .also { sampleBitmap = it }
    }
}
