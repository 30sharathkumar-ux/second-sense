package com.google.mediapipe.examples.objectdetection

/**
 * LabelMapper — Step 1: Relabel and Filter COCO Results
 *
 * Problems this solves:
 *  1. COCO labels misapplied to flat surfaces (e.g. "refrigerator" for a wall).
 *  2. Important but visually-similar classes (chair, bench, table) were at wrong priority.
 *  3. Score threshold was uniform; chair/person need a LOWER threshold to catch them early.
 *
 * ── Tuning constants ──────────────────────────────────────────────────────────
 */
object LabelMapper {

    // ── TUNING CONSTANTS ──────────────────────────────────────────────────────

    /** Default confidence threshold used for most COCO classes. */
    const val THRESHOLD_DEFAULT = 0.45f

    /**
     * Lower threshold for classes that are hard to detect but critically important.
     * Applied to: chair, bench, dining table, person.
     */
    const val THRESHOLD_LOW = 0.30f

    /**
     * If a detection's bounding box covers more than this fraction of the frame WIDTH,
     * AND more than HEIGHT_FILL_FRACTION of the frame HEIGHT, and its label is not in
     * LARGE_BOX_EXEMPT, it is relabelled as "wall".
     */
    const val LARGE_BOX_WIDTH_FRACTION  = 0.60f
    const val LARGE_BOX_HEIGHT_FRACTION = 0.50f

    // ── LABEL MAPS ────────────────────────────────────────────────────────────

    /**
     * COCO labels that are almost always mislabelled flat surfaces or irrelevant objects.
     * These are silently remapped to "obstacle" so the user hears "Obstacle ahead"
     * instead of "refrigerator ahead".
     */
    private val OBSTACLE_REMAP: Set<String> = setOf(
        "refrigerator", "oven", "microwave", "tv", "television",
        "laptop", "couch", "sofa", "bed", "toilet", "sink",
        "suitcase", "potted plant", "vase", "clock", "monitor",
        "display"
    )

    /**
     * COCO labels remapped to a friendlier generic label.
     * Key = COCO label (lowercase), Value = remapped label.
     */
    private val GENERIC_REMAP: Map<String, String> = mapOf(
        "dining table" to "table",
        "bench"        to "bench",
        "chair"        to "chair"
    )

    /**
     * Labels that are exempt from the large-box → "wall" reclassification.
     * These objects CAN legitimately fill the frame without being a wall.
     */
    private val LARGE_BOX_EXEMPT: Set<String> = setOf(
        "person", "car", "truck", "bus", "van",
        "dog", "cat", "cow", "horse", "bicycle", "motorcycle"
    )

    /**
     * Labels that should use [THRESHOLD_LOW] rather than [THRESHOLD_DEFAULT].
     * These are objects a visually impaired person trips over most often.
     */
    val LOW_THRESHOLD_LABELS: Set<String> = setOf(
        "chair", "bench", "dining table", "person"
    )

    /**
     * Labels that are treated as HAZARD priority (priority tier 2).
     * These are trip/collision hazards that deserve earlier warning than generic objects.
     */
    val HAZARD_LABELS: Set<String> = setOf(
        "chair", "bench", "table", "person",
        "car", "truck", "bus", "motorcycle", "bicycle"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the score threshold to use for [rawLabel].
     * Call BEFORE filtering by confidence so low-threshold classes aren't dropped early.
     */
    fun thresholdFor(rawLabel: String): Float =
        if (rawLabel.lowercase().trim() in LOW_THRESHOLD_LABELS) THRESHOLD_LOW
        else THRESHOLD_DEFAULT

    /**
     * Maps a raw COCO label to the cleaned, user-facing label.
     * Also applies large-box → "wall" reclassification when box geometry is provided.
     *
     * @param rawLabel      Original COCO label from the model.
     * @param boxWidthRatio  Box width / frame width  [0..1]. Pass -1f to skip geometry check.
     * @param boxHeightRatio Box height / frame height [0..1]. Pass -1f to skip geometry check.
     * @return Cleaned label string. Returns null if the detection should be SUPPRESSED entirely.
     */
    fun map(
        rawLabel: String,
        boxWidthRatio: Float  = -1f,
        boxHeightRatio: Float = -1f
    ): String {
        val key = rawLabel.lowercase().trim()

        // 1. Large-box → "wall" reclassification (geometry-based)
        if (boxWidthRatio  >= LARGE_BOX_WIDTH_FRACTION &&
            boxHeightRatio >= LARGE_BOX_HEIGHT_FRACTION &&
            key !in LARGE_BOX_EXEMPT) {
            return "wall"
        }

        // 2. Obstacle remap: noisy / irrelevant COCO classes
        if (key in OBSTACLE_REMAP) return "obstacle"

        // 3. Generic friendly remap (chair, bench, dining table)
        GENERIC_REMAP[key]?.let { return it }

        // 4. Return as-is (already clean: person, car, dog, etc.)
        return rawLabel.lowercase().trim()
    }

    /**
     * Returns true if [mappedLabel] should be treated as a HAZARD (priority tier 2).
     */
    fun isHazard(mappedLabel: String): Boolean =
        mappedLabel.lowercase().trim() in HAZARD_LABELS
}
