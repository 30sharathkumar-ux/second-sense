package com.secondsense.alertmanager

/**
 * DetectedObject — Pair B Data Model
 *
 * Represents a single detection event from the camera/sensor pipeline.
 *
 * @param label       Class label from the object detection model (e.g. "person", "car").
 * @param distanceCm  Estimated distance to the object in centimetres (0 means unknown).
 * @param confidence  Detection confidence score from the model [0.0 – 1.0].
 */
data class DetectedObject(
    val label: String,
    val distanceCm: Float,
    val confidence: Float = 1.0f
) {
    /** Derived priority using Rule #4 classification. */
    val priority: AlertPriority get() = AlertPriority.forObject(label)

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
     * Example: "Caution! person detected, very close, 30 centimetres."
     */
    fun toAlertText(): String =
        "${priority.speechPrefix} ${label.lowercase()} detected, ${distanceDescription()}."

    companion object {
        /** Distance (cm) below which we consider an object dangerously close. */
        const val CRITICAL_DISTANCE_CM = 100f

        /** Distance (cm) for medium-range warnings. */
        const val MEDIUM_DISTANCE_CM = 250f

        /** Distance (cm) for long-range awareness. */
        const val FAR_DISTANCE_CM = 500f
    }
}
