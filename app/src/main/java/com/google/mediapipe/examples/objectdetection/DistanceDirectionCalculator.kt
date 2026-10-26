package com.google.mediapipe.examples.objectdetection

/**
 * DistanceDirectionCalculator — Precise spatial analysis utility for Second Sense.
 *
 * Calculates object direction and distance proxy from MediaPipe bounding box
 * coordinates using screen-relative geometry. Designed for real-time assistive
 * feedback to blind and visually impaired users.
 *
 * Direction: Screen divided into 3 equal vertical columns.
 * Distance:  Bounding box area ratio against total screen area.
 */
object DistanceDirectionCalculator {

    // =========================================================================
    // Direction thresholds — screen divided into 3 equal vertical columns
    // =========================================================================
    private const val LEFT_BOUNDARY_FRACTION = 1f / 3f    // 0.333...
    private const val RIGHT_BOUNDARY_FRACTION = 2f / 3f   // 0.666...

    // =========================================================================
    // Distance proxy thresholds — bounding box area ratio vs screen area
    // =========================================================================
    private const val VERY_CLOSE_THRESHOLD = 0.40f   // ratio > 0.40 → "very close"
    private const val NEAR_THRESHOLD = 0.15f          // ratio >= 0.15 → "near"
    // ratio < 0.15 → "ignore" (too far away, skip alert)

    // =========================================================================
    // Direction constants
    // =========================================================================
    const val DIRECTION_LEFT = "on your left"
    const val DIRECTION_RIGHT = "on your right"
    const val DIRECTION_CENTER = "straight ahead"

    // =========================================================================
    // Distance state constants
    // =========================================================================
    const val DISTANCE_VERY_CLOSE = "very close"
    const val DISTANCE_NEAR = "near"
    const val DISTANCE_IGNORE = "ignore"

    /**
     * Structured result returned by [analyze].
     *
     * @property label        The detected object label (e.g. "chair", "person").
     * @property direction    Spatial direction cue: "on your left", "straight ahead", or "on your right".
     * @property distanceState Proximity classification: "very close", "near", or "ignore".
     * @property shouldAlert  True only when [distanceState] is "very close" or "near".
     */
    data class AnalysisResult(
        val label: String,
        val direction: String,
        val distanceState: String,
        val shouldAlert: Boolean
    ) {
        /**
         * Formats a natural-language voice cue for TTS output.
         * Example: "chair on your left, very close" or "person straight ahead, near"
         */
        fun toVoiceCue(): String {
            return "$label $direction, $distanceState"
        }
    }

    /**
     * Performs full spatial analysis on a single detected object.
     *
     * @param left         Bounding box left edge (pixels) from MediaPipe detection.
     * @param top          Bounding box top edge (pixels) from MediaPipe detection.
     * @param right        Bounding box right edge (pixels) from MediaPipe detection.
     * @param bottom       Bounding box bottom edge (pixels) from MediaPipe detection.
     * @param screenWidth  Camera preview width (pixels).
     * @param screenHeight Camera preview height (pixels).
     * @param label        Detected object label (e.g. "chair", "person").
     * @return [AnalysisResult] containing direction, distance state, and alert flag.
     */
    fun analyze(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float,
        label: String
    ): AnalysisResult {
        val direction = calculateDirection(left, right, screenWidth)
        val distanceState = calculateDistanceState(left, top, right, bottom, screenWidth, screenHeight)
        val shouldAlert = distanceState == DISTANCE_VERY_CLOSE || distanceState == DISTANCE_NEAR

        return AnalysisResult(
            label = label,
            direction = direction,
            distanceState = distanceState,
            shouldAlert = shouldAlert
        )
    }

    /**
     * Alias for [analyze] matching calculate(left, top, right, bottom, screenWidth, screenHeight, label).
     */
    fun calculate(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float,
        label: String
    ): AnalysisResult = analyze(left, top, right, bottom, screenWidth, screenHeight, label)

    /**
     * Overload for [calculate] taking an Android [android.graphics.RectF] bounding box.
     */
    fun calculate(
        box: android.graphics.RectF,
        screenWidth: Float,
        screenHeight: Float,
        label: String
    ): AnalysisResult = analyze(box.left, box.top, box.right, box.bottom, screenWidth, screenHeight, label)

    /**
     * Classifies the horizontal direction of the bounding box center.
     *
     * Screen is divided into 3 equal vertical columns:
     * ```
     *  |  LEFT  | CENTER |  RIGHT |
     *  | 0..1/3 | 1/3..2/3 | 2/3..1 |
     * ```
     *
     * @param left        Bounding box left edge (pixels).
     * @param right       Bounding box right edge (pixels).
     * @param screenWidth Camera preview width (pixels).
     * @return Direction string: "on your left", "straight ahead", or "on your right".
     */
    fun calculateDirection(left: Float, right: Float, screenWidth: Float): String {
        if (screenWidth <= 0f) return DIRECTION_CENTER

        val centerX = (left + right) / 2f

        return when {
            centerX < screenWidth * LEFT_BOUNDARY_FRACTION -> DIRECTION_LEFT
            centerX > screenWidth * RIGHT_BOUNDARY_FRACTION -> DIRECTION_RIGHT
            else -> DIRECTION_CENTER
        }
    }

    /**
     * Classifies the distance proxy of the detected object using bounding box
     * area ratio against the total screen area.
     *
     * Thresholds:
     * - ratio > 0.40  → "very close" (Heavy hazard — immediate alert)
     * - ratio >= 0.15  → "near"       (Moderate obstacle — standard alert)
     * - ratio < 0.15  → "ignore"     (Too far away — skip alert entirely)
     *
     * @param left         Bounding box left edge (pixels).
     * @param top          Bounding box top edge (pixels).
     * @param right        Bounding box right edge (pixels).
     * @param bottom       Bounding box bottom edge (pixels).
     * @param screenWidth  Camera preview width (pixels).
     * @param screenHeight Camera preview height (pixels).
     * @return Distance state string: "very close", "near", or "ignore".
     */
    fun calculateDistanceState(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float
    ): String {
        if (screenWidth <= 0f || screenHeight <= 0f) return DISTANCE_IGNORE

        val boxWidth = (right - left).coerceAtLeast(0f)
        val boxHeight = (bottom - top).coerceAtLeast(0f)
        val boxArea = boxWidth * boxHeight
        val screenArea = screenWidth * screenHeight
        val ratio = boxArea / screenArea

        return when {
            ratio > VERY_CLOSE_THRESHOLD -> DISTANCE_VERY_CLOSE
            ratio >= NEAR_THRESHOLD -> DISTANCE_NEAR
            else -> DISTANCE_IGNORE
        }
    }

    /**
     * Computes the raw bounding box area ratio (0.0 to 1.0) for external use.
     */
    fun calculateAreaRatio(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float
    ): Float {
        if (screenWidth <= 0f || screenHeight <= 0f) return 0f

        val boxWidth = (right - left).coerceAtLeast(0f)
        val boxHeight = (bottom - top).coerceAtLeast(0f)
        val boxArea = boxWidth * boxHeight
        val screenArea = screenWidth * screenHeight

        return (boxArea / screenArea).coerceIn(0f, 1f)
    }
}
