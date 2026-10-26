package com.secondsense.alertmanager

/**
 * AlertPriority — Step 2: Direction Priority (revised priority tiers)
 *
 * Global alert priority order:
 *   1. DANGER      — fast-approaching / sudden large object
 *   2. HAZARD      — wall, pothole, steps, door (environment hazard)
 *   3. PERSON_VEH  — person / vehicle / animal
 *   4. OTHER       — everything else
 *
 * Lower ordinal = higher priority (used for sorting).
 */
enum class AlertPriority(
    val level: Int,
    val label: String,
    val speechPrefix: String
) {
    /**
     * DANGER (1) — Fast-approaching or suddenly appearing large object.
     * Bypasses normal cooldown; confirmed in only 2 consecutive frames.
     */
    DANGER(
        level = 0,
        label = "Danger",
        speechPrefix = "Warning!"
    ),

    /**
     * HAZARD (2) — Environmental hazards: wall, pothole, stairs, door.
     */
    HAZARD(
        level = 1,
        label = "Hazard",
        speechPrefix = "Caution!"
    ),

    /**
     * PERSON_VEH (3) — Person, vehicle, stray animal.
     */
    PERSON_VEH(
        level = 2,
        label = "Person/Vehicle",
        speechPrefix = "Watch out!"
    ),

    /**
     * OTHER (4) — General obstacles (furniture, bags, etc.)
     */
    OTHER(
        level = 3,
        label = "Other",
        speechPrefix = "Notice:"
    );

    // Keep CRITICAL / HIGH / MEDIUM / LOW as aliases for legacy code compatibility
    companion object {
        @JvmStatic val CRITICAL = PERSON_VEH
        @JvmStatic val HIGH     = HAZARD
        @JvmStatic val MEDIUM   = OTHER
        @JvmStatic val LOW      = OTHER

        /**
         * Returns the AlertPriority for a given object label.
         *
         * Note: DANGER is NOT assigned here — it is assigned dynamically by
         * ApproachTracker when growth rate exceeds the threshold (Step 3).
         *
         * @param label The object detection class label (case-insensitive).
         */
        fun forObject(label: String): AlertPriority {
            val normalized = label.trim().lowercase()
            return when {
                normalized in PERSON_VEH_OBJECTS -> PERSON_VEH
                normalized in HAZARD_OBJECTS     -> HAZARD
                else                             -> OTHER
            }
        }

        /** Person, vehicle and stray animal class names (PERSON_VEH tier). */
        val PERSON_VEH_OBJECTS: Set<String> = setOf(
            // People
            "person", "human", "pedestrian", "child",
            // Vehicles
            "car", "truck", "vehicle", "bus", "motorcycle", "motorbike",
            "bicycle", "bike", "van", "auto", "rickshaw",
            // Stray / live animals
            "dog", "cat", "cow", "horse", "sheep", "goat", "pig",
            "elephant", "deer", "bear", "animal", "stray"
        )

        /**
         * Environmental hazards (HAZARD tier).
         * Includes custom-model labels: "pothole", "stairs", "door".
         */
        val HAZARD_OBJECTS: Set<String> = setOf(
            "stairs", "staircase", "step", "steps", "pit", "hole", "fire",
            "construction", "barrier", "road", "traffic",
            // Custom model labels (Step 7)
            "pothole", "door"
        )

        /** Common obstacle class names (OTHER tier). */
        val OTHER_OBJECTS: Set<String> = setOf(
            "chair", "table", "wall", "bench", "box", "pole", "pillar",
            "bin", "trash", "gate", "fence", "lamp", "tree",
            "bag", "luggage", "suitcase"
        )
    }
}
