package com.secondsense.alertmanager

/**
 * DetectedObject — Step 3: Added centerX, areaRatio, isDanger fields
 *
 * @param label       Class label from the object detection model (e.g. "person", "car").
 * @param distanceCm  Estimated distance to the object in centimetres (0 means unknown).
 * @param confidence  Detection confidence score from the model [0.0 – 1.0].
 * @param centerX     Normalised horizontal centre of the bounding box [0..1]. Used by ApproachTracker.
 * @param areaRatio   Bounding box area / screen area [0..1]. Used by ApproachTracker.
 * @param isDanger    True when ApproachTracker classifies this as DANGER (fast path).
 * @param direction   Direction string from DistanceDirectionCalculator (e.g. "straight ahead").
 */
data class DetectedObject(
    val label: String,
    val distanceCm: Float,
    val confidence: Float = 1.0f,
    val centerX: Float = 0.5f,
    val areaRatio: Float = 0f,
    val isDanger: Boolean = false,
    val direction: String = "straight ahead"
) {
    /**
     * Derived priority using the new 4-tier classification.
     * DANGER is set from the isDanger flag; otherwise falls through to forObject().
     */
    val priority: AlertPriority
        get() = if (isDanger) AlertPriority.DANGER else AlertPriority.forObject(label)

    /**
     * Returns a natural-language distance description suitable for TTS.
     */
    fun distanceDescription(): String = when {
        distanceCm <= 0f  -> "at unknown distance"
        distanceCm < 50f  -> "very close, ${distanceCm.toInt()} centimetres"
        distanceCm < 150f -> "${distanceCm.toInt()} centimetres ahead"
        distanceCm < 500f -> "${(distanceCm / 100).toInt()} metres ahead"
        else              -> "far ahead"
    }

    /**
     * Builds the full spoken alert sentence.
     * Example: "Warning! person approaching ahead."
     */
    fun toAlertText(): String =
        "${priority.speechPrefix} ${label.lowercase()} ${distanceDescription()}."

    companion object {
        const val CRITICAL_DISTANCE_CM = 100f
        const val MEDIUM_DISTANCE_CM   = 250f
        const val FAR_DISTANCE_CM      = 500f
    }
}
