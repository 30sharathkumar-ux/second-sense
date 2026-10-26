package com.secondsense.alertmanager

/**
 * AlertPriority — Pair B Rule #4
 *
 * Defines alert priority tiers so that life-critical detections (person, vehicle,
 * stray animal) are always processed before lower-priority obstacles.
 *
 * Lower ordinal = higher priority (used for sorting).
 */
enum class AlertPriority(
    val level: Int,
    val label: String,
    val speechPrefix: String
) {
    /**
     * CRITICAL — Immediate safety risk.
     * Persons, vehicles, stray animals must be announced first.
     */
    CRITICAL(
        level = 0,
        label = "Critical",
        speechPrefix = "Caution!"
    ),

    /**
     * HIGH — Notable hazard but not immediately life-threatening.
     * E.g., open pits, stairs, falling objects.
     */
    HIGH(
        level = 1,
        label = "High",
        speechPrefix = "Warning!"
    ),

    /**
     * MEDIUM — General obstacle needing attention.
     * E.g., furniture, parked bikes, walls.
     */
    MEDIUM(
        level = 2,
        label = "Medium",
        speechPrefix = "Heads up!"
    ),

    /**
     * LOW — Informational proximity hint, non-blocking.
     */
    LOW(
        level = 3,
        label = "Low",
        speechPrefix = "Notice:"
    );

    companion object {
        /**
         * Returns the AlertPriority for a given object label.
         *
         * Rule #4: person, vehicle (car/truck/bike/motorcycle/bus), and stray
         * animals (dog/cat/cow/horse/sheep) map to CRITICAL priority.
         *
         * @param label The object detection class label (case-insensitive).
         */
        fun forObject(label: String): AlertPriority {
            val normalized = label.trim().lowercase()
            return when {
                normalized in CRITICAL_OBJECTS -> CRITICAL
                normalized in HIGH_HAZARD_OBJECTS -> HIGH
                normalized in MEDIUM_OBSTACLES -> MEDIUM
                else -> LOW
            }
        }

        /** Rule #4 — Person, vehicle and stray animal class names (CRITICAL). */
        val CRITICAL_OBJECTS: Set<String> = setOf(
            // People
            "person", "human", "pedestrian", "child",
            // Vehicles
            "car", "truck", "vehicle", "bus", "motorcycle", "motorbike",
            "bicycle", "bike", "van", "auto", "rickshaw",
            // Stray / live animals
            "dog", "cat", "cow", "horse", "sheep", "goat", "pig",
            "elephant", "deer", "bear", "animal", "stray"
        )

        /** Dangerous environment hazards. */
        val HIGH_HAZARD_OBJECTS: Set<String> = setOf(
            "stairs", "staircase", "step", "pit", "hole", "fire",
            "construction", "barrier", "road", "traffic"
        )

        /** Common obstacle class names. */
        val MEDIUM_OBSTACLES: Set<String> = setOf(
            "chair", "table", "wall", "bench", "box", "pole", "pillar",
            "bin", "trash", "door", "gate", "fence", "lamp", "tree",
            "bag", "luggage", "suitcase"
        )
    }
}
