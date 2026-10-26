package com.google.mediapipe.examples.objectdetection

/**
 * Messages — Step 2: Direction Priority
 *
 * Centralised message builder for all three supported languages:
 *   ENGLISH, HINDI, KANNADA
 *
 * Usage:
 *   Messages.alert(language, label, direction, distanceState)
 *   Messages.battery(language, percent)
 *   Messages.wallAhead(language)
 *   Messages.custom(language, customKey)
 *
 * Direction strings from DistanceDirectionCalculator:
 *   "straight ahead"  → "ahead"
 *   "on your left"    → "to your left"
 *   "on your right"   → "to your right"
 */
object Messages {

    enum class Language { ENGLISH, HINDI, KANNADA }

    // ── Direction helpers ────────────────────────────────────────────────────

    private fun dirEn(dir: String) = when {
        dir.contains("left", ignoreCase = true)  -> "to your left"
        dir.contains("right", ignoreCase = true) -> "to your right"
        else                                      -> "ahead"
    }

    private fun dirHi(dir: String) = when {
        dir.contains("left", ignoreCase = true)  -> "बाईं तरफ"
        dir.contains("right", ignoreCase = true) -> "दाईं तरफ"
        else                                      -> "आगे"
    }

    private fun dirKn(dir: String) = when {
        dir.contains("left", ignoreCase = true)  -> "ಎಡಕ್ಕೆ"
        dir.contains("right", ignoreCase = true) -> "ಬಲಕ್ಕೆ"
        else                                      -> "ಮುಂದೆ"
    }

    // ── Distance proximity helpers ───────────────────────────────────────────

    private fun distEn(distanceState: String) = when {
        distanceState.contains("very close", ignoreCase = true) -> "very close"
        distanceState.contains("near", ignoreCase = true)       -> "nearby"
        else                                                     -> ""
    }

    private fun distHi(distanceState: String) = when {
        distanceState.contains("very close", ignoreCase = true) -> "बहुत पास"
        distanceState.contains("near", ignoreCase = true)       -> "पास में"
        else                                                     -> ""
    }

    private fun distKn(distanceState: String) = when {
        distanceState.contains("very close", ignoreCase = true) -> "ತುಂಬಾ ಹತ್ತಿರ"
        distanceState.contains("near", ignoreCase = true)       -> "ಹತ್ತಿರದಲ್ಲಿ"
        else                                                     -> ""
    }

    // ── Priority prefix helpers ──────────────────────────────────────────────

    /** DANGER fast-path prefix */
    fun dangerAlert(lang: Language, label: String, direction: String): String = when (lang) {
        Language.ENGLISH  -> "Warning! ${label.lowercase()} approaching ${dirEn(direction)}"
        Language.HINDI    -> "चेतावनी! ${label.lowercase()} ${dirHi(direction)} आ रहा है"
        Language.KANNADA  -> "ಎಚ್ಚರಿಕೆ! ${label.lowercase()} ${dirKn(direction)} ಬರುತ್ತಿದೆ"
    }

    /**
     * Standard object alert with direction and distance.
     * @param priorityTier  "danger", "hazard", "person", or "other"
     */
    fun alert(
        lang: Language,
        label: String,
        direction: String,
        distanceState: String,
        priorityTier: String = "other"
    ): String {
        val cleanLabel = label.lowercase().trim()
        return when (lang) {
            Language.ENGLISH -> {
                val dist = distEn(distanceState)
                val dir  = dirEn(direction)
                if (dist.isNotEmpty()) "$cleanLabel $dir, $dist"
                else "$cleanLabel $dir"
            }
            Language.HINDI -> {
                val dist = distHi(distanceState)
                val dir  = dirHi(direction)
                if (dist.isNotEmpty()) "$cleanLabel $dir, $dist"
                else "$cleanLabel $dir"
            }
            Language.KANNADA -> {
                val dist = distKn(distanceState)
                val dir  = dirKn(direction)
                if (dist.isNotEmpty()) "$cleanLabel $dir, $dist"
                else "$cleanLabel $dir"
            }
        }
    }

    // ── Wall / Obstacle ahead ────────────────────────────────────────────────

    fun wallAhead(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Obstacle ahead"
        Language.HINDI   -> "आगे रुकावट है"
        Language.KANNADA -> "ಮುಂದೆ ಅಡಚಣೆ ಇದೆ"
    }

    // ── Custom hazard labels (pothole / stairs / door) ───────────────────────

    fun pothole(lang: Language, direction: String): String = when (lang) {
        Language.ENGLISH -> "Pothole ${dirEn(direction)}"
        Language.HINDI   -> "${dirHi(direction)} गड्ढा है"
        Language.KANNADA -> "${dirKn(direction)} ಗುಂಡಿ ಇದೆ"
    }

    fun stairs(lang: Language, direction: String): String = when (lang) {
        Language.ENGLISH -> "Steps ${dirEn(direction)}"
        Language.HINDI   -> "${dirHi(direction)} सीढ़ियाँ हैं"
        Language.KANNADA -> "${dirKn(direction)} ಮೆಟ್ಟಿಲುಗಳಿವೆ"
    }

    fun door(lang: Language, direction: String): String = when (lang) {
        Language.ENGLISH -> "Door ${dirEn(direction)}"
        Language.HINDI   -> "${dirHi(direction)} दरवाज़ा है"
        Language.KANNADA -> "${dirKn(direction)} ಬಾಗಿಲು ಇದೆ"
    }

    // ── Battery alerts ───────────────────────────────────────────────────────

    fun battery30(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Battery 30 percent"
        Language.HINDI   -> "बैटरी 30 प्रतिशत"
        Language.KANNADA -> "ಬ್ಯಾಟರಿ 30 ಪ್ರತಿಶತ"
    }

    fun battery20(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Battery 20 percent"
        Language.HINDI   -> "बैटरी 20 प्रतिशत"
        Language.KANNADA -> "ಬ್ಯಾಟರಿ 20 ಪ್ರತಿಶತ"
    }

    fun battery10(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Battery 10 percent, please charge"
        Language.HINDI   -> "बैटरी 10 प्रतिशत, कृपया चार्ज करें"
        Language.KANNADA -> "ಬ್ಯಾಟರಿ 10 ಪ್ರತಿಶತ, ದಯವಿಟ್ಟು ಚಾರ್ಜ್ ಮಾಡಿ"
    }

    fun battery5(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Battery 5 percent, critical"
        Language.HINDI   -> "बैटरी 5 प्रतिशत, गंभीर"
        Language.KANNADA -> "ಬ್ಯಾಟರಿ 5 ಪ್ರತಿಶತ, ತುರ್ತು"
    }

    // ── Test / Debug ─────────────────────────────────────────────────────────

    fun scanStarted(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Second Sense started. Scanning active."
        Language.HINDI   -> "सेकंड सेंस शुरू हुआ। स्कैनिंग सक्रिय।"
        Language.KANNADA -> "ಸೆಕೆಂಡ್ ಸೆನ್ಸ್ ಪ್ರಾರಂಭವಾಯಿತು. ಸ್ಕ್ಯಾನಿಂಗ್ ಸಕ್ರಿಯ."
    }

    fun scanStopped(lang: Language): String = when (lang) {
        Language.ENGLISH -> "Second Sense stopped."
        Language.HINDI   -> "सेकंड सेंस बंद हुआ।"
        Language.KANNADA -> "ಸೆಕೆಂಡ್ ಸೆನ್ಸ್ ನಿಲ್ಲಿಸಲಾಗಿದೆ."
    }
}
