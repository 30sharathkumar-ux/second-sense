package com.google.mediapipe.examples.objectdetection

import android.graphics.RectF
import com.google.mediapipe.tasks.components.containers.Detection
import java.util.Locale

/**
 * SpatialLogic classifies bounding box coordinates of detected objects
 * into intuitive directional voice cues and priority levels for blind users.
 *
 * Directional Cues:
 * - Left zone (0.00 <= X < 0.35)  -> "on your left"
 * - Center zone (0.35 <= X <= 0.65) -> "straight ahead"
 * - Right zone (0.65 < X <= 1.00) -> "on your right"
 */
object SpatialLogic {

    // Horizontal partition boundaries (normalized 0.0 - 1.0)
    const val LEFT_ZONE_THRESHOLD = 0.35f
    const val RIGHT_ZONE_THRESHOLD = 0.65f

    // Proximity estimation thresholds based on bounding box height relative to frame
    const val PROXIMITY_IMMEDIATE_HEIGHT = 0.50f // Object occupies > 50% vertical frame
    const val PROXIMITY_CLOSE_HEIGHT = 0.25f     // Object occupies 25% - 50% vertical frame

    /**
     * Set of object categories considered navigation hazards for visually impaired individuals.
     */
    private val HAZARD_CATEGORIES = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle", "vehicle",
        "stairs", "traffic light", "stop sign", "fire hydrant", "train"
    )

    enum class SpatialDirection(val cueText: String) {
        LEFT("on your left"),
        CENTER("straight ahead"),
        RIGHT("on your right")
    }

    enum class ProximityLevel {
        IMMEDIATE, // Right in front / urgent
        CLOSE,     // Approaching within walking distance
        DISTANT    // In background
    }

    data class SpatialCue(
        val label: String,
        val direction: SpatialDirection,
        val voiceCue: String,
        val isHazard: Boolean,
        val proximity: ProximityLevel,
        val confidence: Float,
        val priorityScore: Float
    )

    /**
     * Classifies a normalized horizontal coordinate (0.0 to 1.0) into a SpatialDirection.
     */
    fun classifyDirection(normalizedCenterX: Float): SpatialDirection {
        return when {
            normalizedCenterX < LEFT_ZONE_THRESHOLD -> SpatialDirection.LEFT
            normalizedCenterX > RIGHT_ZONE_THRESHOLD -> SpatialDirection.RIGHT
            else -> SpatialDirection.CENTER
        }
    }

    /**
     * Classifies bounding box coordinates into a SpatialDirection given frame dimensions.
     */
    fun classifyDirection(box: RectF, frameWidth: Float): SpatialDirection {
        if (frameWidth <= 0f) return SpatialDirection.CENTER
        val centerX = (box.left + box.right) / 2f
        val normalizedX = (centerX / frameWidth).coerceIn(0f, 1f)
        return classifyDirection(normalizedX)
    }

    /**
     * Classifies bounding box coordinates into directional voice cues:
     * "on your left", "straight ahead", or "on your right".
     */
    fun getDirection(box: RectF, frameWidth: Float): String {
        return classifyDirection(box, frameWidth).cueText
    }

    /**
     * Classifies normalized center X coordinate (0.0 to 1.0) into directional voice cue.
     */
    fun getDirection(normalizedCenterX: Float): String {
        return classifyDirection(normalizedCenterX).cueText
    }

    /**
     * Classifies horizontal center pixel and frame width into directional voice cue.
     */
    fun getDirection(centerX: Float, frameWidth: Float): String {
        if (frameWidth <= 0f) return SpatialDirection.CENTER.cueText
        return classifyDirection((centerX / frameWidth).coerceIn(0f, 1f)).cueText
    }

    /**
     * Produces the full natural voice description combining object label and direction
     * (e.g. "chair on your left", "person straight ahead").
     */
    fun getDirection(label: String, box: RectF, frameWidth: Float): String {
        val cleanLabel = label.replace("_", " ").trim()
        val direction = getDirection(box, frameWidth)
        return "$cleanLabel $direction"
    }

    /**
     * Checks if a bounding box is in the foreground / close to the user based on relative height.
     */
    fun isClose(box: RectF, frameHeight: Float): Boolean {
        if (frameHeight <= 0f) return false
        val relativeHeight = (box.height() / frameHeight).coerceIn(0f, 1f)
        return relativeHeight >= PROXIMITY_CLOSE_HEIGHT
    }

    /**
     * Determines whether an object category and its proximity constitutes an immediate hazard.
     */
    fun isHazard(categoryName: String, relativeHeight: Float): Boolean {
        val normalizedCategory = categoryName.lowercase(Locale.ROOT).trim()
        if (HAZARD_CATEGORIES.contains(normalizedCategory)) {
            return true
        }
        // Any large object directly blocking path in immediate proximity is treated as a hazard
        if (relativeHeight >= PROXIMITY_IMMEDIATE_HEIGHT) {
            return true
        }
        return false
    }

    /**
     * Estimates proximity based on the bounding box height relative to frame height.
     */
    fun estimateProximity(box: RectF, frameHeight: Float): ProximityLevel {
        if (frameHeight <= 0f) return ProximityLevel.CLOSE
        val relativeHeight = (box.height() / frameHeight).coerceIn(0f, 1f)
        return when {
            relativeHeight >= PROXIMITY_IMMEDIATE_HEIGHT -> ProximityLevel.IMMEDIATE
            relativeHeight >= PROXIMITY_CLOSE_HEIGHT -> ProximityLevel.CLOSE
            else -> ProximityLevel.DISTANT
        }
    }

    /**
     * Formats directional voice cue string (e.g., "chair on your left", "car straight ahead").
     */
    fun formatVoiceCue(label: String, direction: SpatialDirection, isHazard: Boolean, proximity: ProximityLevel): String {
        val cleanLabel = label.replace("_", " ").trim()
        val baseCue = "$cleanLabel ${direction.cueText}"

        return if (isHazard && proximity == ProximityLevel.IMMEDIATE) {
            "Warning: $baseCue"
        } else {
            baseCue
        }
    }

    /**
     * Evaluates a single detection and produces a structured [SpatialCue].
     */
    fun analyzeDetection(
        detection: Detection,
        frameWidth: Float,
        frameHeight: Float
    ): SpatialCue? {
        val categories = detection.categories()
        if (categories.isNullOrEmpty()) return null

        val topCategory = categories[0]
        val label = topCategory.categoryName() ?: return null
        val score = topCategory.score()

        val box = detection.boundingBox() ?: return null
        val direction = classifyDirection(box, frameWidth)
        val proximity = estimateProximity(box, frameHeight)
        val relativeHeight = if (frameHeight > 0f) (box.height() / frameHeight) else 0f
        val hazard = isHazard(label, relativeHeight)

        // Calculate priority score:
        // - Hazards get +100
        // - Straight ahead gets +50
        // - Closer proximity gets higher weight
        // - Confidence score multiplier
        var priority = score * 10f
        if (hazard) priority += 100f
        if (direction == SpatialDirection.CENTER) priority += 50f
        when (proximity) {
            ProximityLevel.IMMEDIATE -> priority += 40f
            ProximityLevel.CLOSE -> priority += 20f
            ProximityLevel.DISTANT -> priority += 0f
        }

        val voiceCue = formatVoiceCue(label, direction, hazard, proximity)

        return SpatialCue(
            label = label,
            direction = direction,
            voiceCue = voiceCue,
            isHazard = hazard,
            proximity = proximity,
            confidence = score,
            priorityScore = priority
        )
    }

    /**
     * Processes all detections from a frame and selects the most critical cue to voice.
     * Prevents sensory overload by prioritizing hazards and forward-path obstacles.
     */
    fun selectTopPriorityCue(
        detections: List<Detection>,
        frameWidth: Float,
        frameHeight: Float
    ): SpatialCue? {
        if (detections.isEmpty()) return null

        return detections
            .mapNotNull { analyzeDetection(it, frameWidth, frameHeight) }
            .maxByOrNull { it.priorityScore }
    }
}
