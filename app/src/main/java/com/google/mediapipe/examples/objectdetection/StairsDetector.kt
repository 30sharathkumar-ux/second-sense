package com.google.mediapipe.examples.objectdetection

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

/**
 * StairsDetector — Step 1: STAIRS_UP via horizontal edge repetition
 *
 * Algorithm:
 *   1. Every 2nd frame, downscale the camera bitmap to ~160×120 grayscale.
 *   2. Handle image rotation so "rows" means horizontal scan-lines of the
 *      upright (portrait) phone view.
 *   3. Crop to centre 50 % width, lower 70 % height of the upright view.
 *   4. Compute the vertical gradient: for each row y, average |lum(x,y) − lum(x,y+1)|
 *      across the cropped columns.  This gives a 1-D "row edge strength" profile.
 *   5. Find peaks in the profile (above mean + k·std).
 *   6. If ≥ [MIN_PEAKS] peaks with roughly regular spacing (CV < [MAX_SPACING_CV])
 *      AND spacings decrease toward the top (perspective foreshortening),
 *      classify as STAIRS_UP candidate.
 *   7. Require [CONFIRM_FRAMES] consecutive candidates and [COOLDOWN_MS] between alerts.
 *
 * Performance target: < 5 ms per frame on mid-range phone (no allocation per frame).
 *
 * ── Tuning constants ────────────────────────────────────────────────────────
 */
class StairsDetector {

    companion object {
        private const val TAG = "STAIRS"

        // ── Downscale target ────────────────────────────────────────────────
        /** Downscale target for the analysis sample. */
        const val SAMPLE_W = 160
        const val SAMPLE_H = 120

        // ── Region of interest (fractions of upright view) ──────────────────
        /** Fraction of upright width to keep (centre crop). */
        const val ROI_WIDTH_FRAC = 0.50f
        /** Fraction of upright height to analyse (bottom portion). */
        const val ROI_HEIGHT_FRAC = 0.70f

        // ── Peak detection ─────────────────────────────────────────────────
        /** Multiplier for threshold: mean + K * std. */
        const val PEAK_K = 1.2f
        /** Minimum number of peaks to consider as stair pattern. */
        const val MIN_PEAKS = 3
        /** Maximum coefficient of variation (std/mean) of peak spacings. */
        const val MAX_SPACING_CV = 0.35f

        // ── Perspective check ──────────────────────────────────────────────
        /**
         * Minimum fraction of adjacent spacing pairs where the upper spacing
         * is smaller than the lower spacing (perspective convergence).
         * 0.5 = at least half must converge.
         */
        const val MIN_PERSPECTIVE_RATIO = 0.50f

        // ── Temporal gating (STAIRS_UP) ─────────────────────────────────────
        /** Run detector every Nth analysis frame. */
        const val RUN_EVERY_N_FRAMES = 2
        /** Consecutive candidate frames before alert fires. */
        const val CONFIRM_FRAMES = 3
        /** Cooldown between STAIRS_UP alerts (ms). */
        const val COOLDOWN_MS = 4_000L

        // ══════════════════════════════════════════════════════════════════════
        // Step 2: STAIRS_DOWN / DROP-OFF constants (depth discontinuity)
        // ══════════════════════════════════════════════════════════════════════

        /** Width of centre column strip as fraction of depth map width. */
        const val DEPTH_CENTER_FRAC = 0.34f

        /**
         * Minimum normalised closeness drop within [DEPTH_JUMP_WINDOW_ROWS]
         * rows to classify as a sharp depth discontinuity.
         * Range 0..1.  Higher = less sensitive.
         */
        const val DEPTH_JUMP_THRESHOLD = 0.20f

        /** Number of consecutive depth-map rows over which to measure the jump. */
        const val DEPTH_JUMP_WINDOW_ROWS = 8

        /**
         * Minimum depth difference between two adjacent plateau averages to
         * count them as separate plateaus.
         */
        const val DEPTH_PLATEAU_DELTA = 0.12f

        /** Minimum number of consecutive rows at similar depth to form a plateau. */
        const val DEPTH_PLATEAU_MIN_LEN = 6

        /** Minimum number of distinct plateaus to classify as STAIRS_DOWN. */
        const val DEPTH_MIN_PLATEAUS = 2

        /** Consecutive depth frames with STAIRS_DOWN before alert fires. */
        const val DEPTH_CONFIRM_FRAMES = 3
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var frameCounter = 0
    private var confirmCount = 0
    private var lastAlertTimeMs = 0L

    // Reusable buffers — allocated once, never reallocated
    private var grayBuf: IntArray? = null          // luminance buffer (SAMPLE_W * SAMPLE_H)
    private var profileBuf: FloatArray? = null      // 1-D edge-strength profile
    private var sampleBitmap: Bitmap? = null

    // ── Step 2: depth state ──────────────────────────────────────────────────
    private var depthConfirmCount = 0
    private var depthProfileBuf: FloatArray? = null  // reusable vertical closeness profile

    // ── Debug fields (read from CameraFragment for overlay) ─────────────────

    // Step 1 debug
    /** Number of peaks found in the last analysed frame. */
    @Volatile var lastPeakCount = 0; private set
    /** Spacing CV (coefficient of variation) of the last analysed frame. */
    @Volatile var lastSpacingCV = 0f; private set
    /** Perspective ratio of the last analysed frame. */
    @Volatile var lastPerspectiveRatio = 0f; private set
    /** Wall-clock ms taken by the last edge analysis. */
    @Volatile var lastAnalysisMs = 0L; private set
    /** True if the last frame was classified as STAIRS_UP candidate. */
    @Volatile var lastIsCandidate = false; private set

    // Step 2 debug
    /** Largest depth jump found in the last depth frame. */
    @Volatile var lastDepthJump = 0f; private set
    /** Number of depth plateaus found in the last depth frame. */
    @Volatile var lastPlateauCount = 0; private set
    /** Wall-clock ms taken by the last depth analysis. */
    @Volatile var lastDepthAnalysisMs = 0L; private set
    /** True if the last depth frame was classified as STAIRS_DOWN candidate. */
    @Volatile var lastDepthIsCandidate = false; private set

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Analyse one frame. Returns `true` if a STAIRS_UP alert should fire
     * (3 consecutive candidates + cooldown elapsed).
     *
     * MUST be called on a background thread.
     *
     * @param frameBitmap RGBA_8888 bitmap from ImageProxy (raw sensor orientation).
     * @param rotationDeg Image rotation in degrees (0, 90, 180, 270) — tells us how
     *                    many degrees CW the raw buffer is rotated relative to upright.
     */
    fun analyzeFrame(frameBitmap: Bitmap, rotationDeg: Int): Boolean {
        frameCounter++
        if (frameCounter % RUN_EVERY_N_FRAMES != 0) return false

        val t0 = SystemClock.uptimeMillis()

        // 1. Downscale into a reusable bitmap
        val sample = getOrCreateSample()
        val scaled = Bitmap.createScaledBitmap(frameBitmap, SAMPLE_W, SAMPLE_H, false)
        val canvas = android.graphics.Canvas(sample)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== sample) scaled.recycle()

        // 2. Convert to grayscale in a flat buffer, applying rotation so
        //    grayBuf is indexed as [uprightRow * uprightW + uprightCol].
        val gray = getOrCreateGray()
        val uprightDims = fillGrayUpright(sample, gray, rotationDeg)
        val uprightW = uprightDims[0]
        val uprightH = uprightDims[1]

        // 3. Compute ROI boundaries (centre 50 % width, lower 70 % height)
        val roiColStart = ((1f - ROI_WIDTH_FRAC) / 2f * uprightW).toInt()
        val roiColEnd   = ((1f + ROI_WIDTH_FRAC) / 2f * uprightW).toInt()
        val roiRowStart = ((1f - ROI_HEIGHT_FRAC) * uprightH).toInt()
        val roiRowEnd   = uprightH - 1  // -1 because gradient needs y+1

        val roiCols = roiColEnd - roiColStart
        val profileLen = roiRowEnd - roiRowStart
        if (profileLen < 10 || roiCols < 4) {
            lastIsCandidate = false
            lastAnalysisMs = SystemClock.uptimeMillis() - t0
            return false
        }

        // 4. Compute 1-D vertical gradient profile
        val profile = getOrCreateProfile(profileLen)
        var profileSum = 0f
        var profileSumSq = 0f
        for (r in 0 until profileLen) {
            val y0 = roiRowStart + r
            val y1 = y0 + 1
            var rowSum = 0f
            for (c in roiColStart until roiColEnd) {
                val diff = gray[y1 * uprightW + c] - gray[y0 * uprightW + c]
                rowSum += if (diff >= 0) diff.toFloat() else (-diff).toFloat()
            }
            val avg = rowSum / roiCols
            profile[r] = avg
            profileSum += avg
            profileSumSq += avg * avg
        }

        // 5. Find peaks above mean + k * std
        val mean = profileSum / profileLen
        val variance = (profileSumSq / profileLen) - (mean * mean)
        val std = if (variance > 0) Math.sqrt(variance.toDouble()).toFloat() else 0f
        val threshold = mean + PEAK_K * std

        // Collect peak indices (simple: value > threshold AND local max)
        val peakIndices = mutableListOf<Int>()
        for (i in 1 until profileLen - 1) {
            if (profile[i] > threshold && profile[i] >= profile[i - 1] && profile[i] >= profile[i + 1]) {
                peakIndices.add(i)
            }
        }
        lastPeakCount = peakIndices.size

        if (peakIndices.size < MIN_PEAKS) {
            lastIsCandidate = false
            lastSpacingCV = 0f
            lastPerspectiveRatio = 0f
            lastAnalysisMs = SystemClock.uptimeMillis() - t0
            Log.d(TAG, "peaks=${peakIndices.size} (<$MIN_PEAKS) → NOT candidate " +
                    "[${lastAnalysisMs}ms]")
            confirmCount = 0
            return false
        }

        // 6. Compute spacings and check regularity
        val spacings = IntArray(peakIndices.size - 1)
        for (i in spacings.indices) spacings[i] = peakIndices[i + 1] - peakIndices[i]

        val spacingMean = spacings.average().toFloat()
        val spacingStd = if (spacings.size > 1) {
            val sumSq2 = spacings.sumOf { ((it - spacingMean) * (it - spacingMean)).toDouble() }
            Math.sqrt(sumSq2 / spacings.size).toFloat()
        } else 0f
        lastSpacingCV = if (spacingMean > 0f) spacingStd / spacingMean else 999f

        if (lastSpacingCV > MAX_SPACING_CV) {
            lastIsCandidate = false
            lastPerspectiveRatio = 0f
            lastAnalysisMs = SystemClock.uptimeMillis() - t0
            Log.d(TAG, "peaks=${peakIndices.size} spacingCV=%.2f (>$MAX_SPACING_CV) → NOT candidate [${lastAnalysisMs}ms]".format(lastSpacingCV))
            confirmCount = 0
            return false
        }

        // 7. Perspective check: spacings should generally decrease toward top of image
        //    (lower index = higher in the image = farther away → smaller spacing)
        var convergentPairs = 0
        for (i in 0 until spacings.size - 1) {
            if (spacings[i] < spacings[i + 1]) convergentPairs++
        }
        lastPerspectiveRatio = if (spacings.size > 1)
            convergentPairs.toFloat() / (spacings.size - 1)
        else 0f

        val perspectiveOk = lastPerspectiveRatio >= MIN_PERSPECTIVE_RATIO

        lastIsCandidate = perspectiveOk
        lastAnalysisMs = SystemClock.uptimeMillis() - t0

        Log.d(TAG, "peaks=${peakIndices.size} spacingCV=%.2f perspRatio=%.2f candidate=$lastIsCandidate [${lastAnalysisMs}ms]"
            .format(lastSpacingCV, lastPerspectiveRatio))

        if (!lastIsCandidate) {
            confirmCount = 0
            return false
        }

        // 8. Temporal gating: consecutive frames + cooldown
        confirmCount++
        Log.d(TAG, "STAIRS_UP candidate confirmed=$confirmCount/$CONFIRM_FRAMES")

        if (confirmCount < CONFIRM_FRAMES) return false

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAlertTimeMs < COOLDOWN_MS) {
            Log.d(TAG, "STAIRS_UP detected but in cooldown (${COOLDOWN_MS - (nowMs - lastAlertTimeMs)}ms left)")
            return false
        }

        lastAlertTimeMs = nowMs
        confirmCount = 0
        Log.i(TAG, "*** STAIRS_UP ALERT *** peaks=${peakIndices.size} " +
                "spacingCV=%.2f perspRatio=%.2f [${lastAnalysisMs}ms]"
                    .format(lastSpacingCV, lastPerspectiveRatio))
        return true
    }

    /** Reset state (call when scanning stops). */
    fun reset() {
        // Step 1 state
        confirmCount = 0
        lastAlertTimeMs = 0L
        lastPeakCount = 0
        lastSpacingCV = 0f
        lastPerspectiveRatio = 0f
        lastIsCandidate = false
        // Step 2 state
        depthConfirmCount = 0
        lastDepthJump = 0f
        lastPlateauCount = 0
        lastDepthIsCandidate = false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 2: STAIRS_DOWN / DROP-OFF via depth discontinuity
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Analyse the MiDaS depth map for stairs-down / drop-off patterns.
     * Returns `true` if a DANGER-level STAIRS_DOWN alert should fire.
     *
     * The depth map is inverse depth (larger = closer). This method:
     *   1. Normalises values to 0..1 by dividing by [maxVal].
     *   2. Builds a vertical closeness profile from the centre columns,
     *      bottom to top (index 0 = bottom row = nearest to user).
     *   3. Detects sharp jumps: closeness drops by > [DEPTH_JUMP_THRESHOLD]
     *      within [DEPTH_JUMP_WINDOW_ROWS] consecutive rows.
     *   4. Detects stepped plateaus: 2+ plateaus separated by [DEPTH_PLATEAU_DELTA].
     *   5. Requires [DEPTH_CONFIRM_FRAMES] consecutive depth frames.
     *   6. No cooldown — DANGER level bypasses cooldown.
     *
     * MUST be called on a background thread.
     *
     * @param depthMap   Raw MiDaS output (MODEL_SIZE × MODEL_SIZE floats, inverse depth).
     * @param modelSize  Side length of the square depth map (typically 256).
     * @param maxVal     Maximum value in depthMap for normalisation.
     */
    fun analyzeDepthMap(depthMap: FloatArray, modelSize: Int, maxVal: Float): Boolean {
        val t0 = SystemClock.uptimeMillis()

        // 1. Define centre column range
        val colStart = ((1f - DEPTH_CENTER_FRAC) / 2f * modelSize).toInt()
        val colEnd   = ((1f + DEPTH_CENTER_FRAC) / 2f * modelSize).toInt()
        val colCount = colEnd - colStart
        if (colCount < 2) {
            lastDepthIsCandidate = false
            lastDepthAnalysisMs = SystemClock.uptimeMillis() - t0
            return false
        }

        // 2. Build vertical closeness profile: bottom-to-top (index 0 = bottom row)
        val profileLen = modelSize
        val profile = getOrCreateDepthProfile(profileLen)
        val invMax = if (maxVal > 0f) 1f / maxVal else 1f

        for (i in 0 until profileLen) {
            val row = modelSize - 1 - i  // bottom row first
            var rowSum = 0f
            for (c in colStart until colEnd) {
                rowSum += depthMap[row * modelSize + c] * invMax
            }
            profile[i] = rowSum / colCount  // normalised 0..1 closeness
        }

        // 3. Detect sharp jump: closeness drops by > threshold within window
        var maxJump = 0f
        for (i in 0 until profileLen - DEPTH_JUMP_WINDOW_ROWS) {
            val drop = profile[i] - profile[i + DEPTH_JUMP_WINDOW_ROWS]
            if (drop > maxJump) maxJump = drop
        }
        lastDepthJump = maxJump
        val hasSharpJump = maxJump > DEPTH_JUMP_THRESHOLD

        // 4. Detect stepped plateaus
        val plateauCount = countPlateaus(profile, profileLen)
        lastPlateauCount = plateauCount
        val hasPlateaus = plateauCount >= DEPTH_MIN_PLATEAUS

        val isCandidate = hasSharpJump || hasPlateaus
        lastDepthIsCandidate = isCandidate
        lastDepthAnalysisMs = SystemClock.uptimeMillis() - t0

        Log.d(TAG, "DEPTH: jump=%.3f (thr=%.2f) plateaus=$plateauCount candidate=$isCandidate [${lastDepthAnalysisMs}ms]"
            .format(lastDepthJump, DEPTH_JUMP_THRESHOLD))

        if (!isCandidate) {
            depthConfirmCount = 0
            return false
        }

        // 5. Temporal confirmation (no cooldown — DANGER bypasses)
        depthConfirmCount++
        Log.d(TAG, "STAIRS_DOWN candidate confirmed=$depthConfirmCount/$DEPTH_CONFIRM_FRAMES")

        if (depthConfirmCount < DEPTH_CONFIRM_FRAMES) return false

        depthConfirmCount = 0
        Log.i(TAG, "*** STAIRS_DOWN ALERT *** jump=%.3f plateaus=$plateauCount [${lastDepthAnalysisMs}ms]"
            .format(lastDepthJump))
        return true
    }

    /**
     * Count the number of distinct depth plateaus in [profile].
     * A plateau is [DEPTH_PLATEAU_MIN_LEN] or more consecutive rows where
     * values stay within [DEPTH_PLATEAU_DELTA] of the plateau's starting value.
     * Two adjacent plateaus must differ by at least [DEPTH_PLATEAU_DELTA].
     */
    private fun countPlateaus(profile: FloatArray, len: Int): Int {
        var plateaus = 0
        var lastPlateauAvg = -1f
        var i = 0

        while (i < len) {
            val start = i
            val anchor = profile[i]
            // Extend plateau while values stay near anchor
            while (i < len && Math.abs(profile[i] - anchor) < DEPTH_PLATEAU_DELTA) {
                i++
            }
            val plateauLen = i - start
            if (plateauLen >= DEPTH_PLATEAU_MIN_LEN) {
                val avg = profile.sliceArray(start until i).average().toFloat()
                // Only count if distinct from previous plateau
                if (lastPlateauAvg < 0f || Math.abs(avg - lastPlateauAvg) >= DEPTH_PLATEAU_DELTA) {
                    plateaus++
                    lastPlateauAvg = avg
                }
            }
            // Skip non-plateau rows
            if (i == start) i++
        }
        return plateaus
    }

    private fun getOrCreateDepthProfile(len: Int): FloatArray {
        return depthProfileBuf?.takeIf { it.size >= len }
            ?: FloatArray(len).also { depthProfileBuf = it }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fill [gray] with luminance values in upright orientation.
     * Returns [uprightW, uprightH] as an IntArray(2).
     *
     * Rotation semantics: [rotationDeg] = how many degrees CW the raw buffer
     * must be rotated to reach upright.
     *   - 0:   raw is already upright.  uprightW = SAMPLE_W,  uprightH = SAMPLE_H
     *   - 90:  phone portrait, sensor landscape.  uprightW = SAMPLE_H,  uprightH = SAMPLE_W
     *   - 180: upside down.  uprightW = SAMPLE_W,  uprightH = SAMPLE_H
     *   - 270: reverse landscape.  uprightW = SAMPLE_H,  uprightH = SAMPLE_W
     */
    private fun fillGrayUpright(bmp: Bitmap, gray: IntArray, rotDeg: Int): IntArray {
        val bw = bmp.width   // always SAMPLE_W
        val bh = bmp.height  // always SAMPLE_H

        return when (rotDeg) {
            90 -> {
                // Upright pixel (ux, uy) ← raw pixel (bh-1-uy, ux)
                val uw = bh; val uh = bw
                for (by in 0 until bh) {
                    for (bx in 0 until bw) {
                        val ux = by           // upright x = raw y
                        val uy = bw - 1 - bx  // upright y = bw-1-raw x
                        val px = bmp.getPixel(bx, by)
                        gray[uy * uw + ux] = lum(px)
                    }
                }
                intArrayOf(uw, uh)
            }
            180 -> {
                val uw = bw; val uh = bh
                for (by in 0 until bh) {
                    for (bx in 0 until bw) {
                        val ux = bw - 1 - bx
                        val uy = bh - 1 - by
                        val px = bmp.getPixel(bx, by)
                        gray[uy * uw + ux] = lum(px)
                    }
                }
                intArrayOf(uw, uh)
            }
            270 -> {
                val uw = bh; val uh = bw
                for (by in 0 until bh) {
                    for (bx in 0 until bw) {
                        val ux = bh - 1 - by
                        val uy = bx
                        val px = bmp.getPixel(bx, by)
                        gray[uy * uw + ux] = lum(px)
                    }
                }
                intArrayOf(uw, uh)
            }
            else -> { // 0 or any unexpected value
                val uw = bw; val uh = bh
                for (by in 0 until bh) {
                    for (bx in 0 until bw) {
                        val px = bmp.getPixel(bx, by)
                        gray[by * uw + bx] = lum(px)
                    }
                }
                intArrayOf(uw, uh)
            }
        }
    }

    /** BT.601 luminance (integer approximation, no allocation). */
    private fun lum(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8   // 0..255
    }

    private fun getOrCreateSample(): Bitmap {
        return sampleBitmap?.takeIf { it.width == SAMPLE_W && it.height == SAMPLE_H }
            ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
                .also { sampleBitmap = it }
    }

    private fun getOrCreateGray(): IntArray {
        // Max possible upright pixels = SAMPLE_W * SAMPLE_H regardless of rotation
        val needed = SAMPLE_W * SAMPLE_H
        return grayBuf?.takeIf { it.size >= needed }
            ?: IntArray(needed).also { grayBuf = it }
    }

    private fun getOrCreateProfile(len: Int): FloatArray {
        return profileBuf?.takeIf { it.size >= len }
            ?: FloatArray(len).also { profileBuf = it }
    }
}
