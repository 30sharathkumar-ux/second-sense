package com.secondsense.alertmanager

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * AlertManager — Pair B Module
 *
 * Provides sensory alert capabilities with full rule enforcement:
 *   1. speak(text)       — TextToSpeech output.
 *   2. vibrate(strength) — VibrationEffect haptic output.
 *   3. processFrame(…)   — Applies all 4 Pair-B alerting rules then fires TTS + vibration.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Rules enforced by the embedded AlertRuleEngine:
 *   Rule #1 — Alert only after 3 consecutive frames (eliminates false alarms).
 *   Rule #2 — Same label is silenced for 3 seconds after firing.
 *   Rule #3 — Vibration pattern speeds up as object gets closer.
 *   Rule #4 — person / vehicle / stray animal alerts always come first.
 * ─────────────────────────────────────────────────────────────────────────
 */
class AlertManager(
    private val context: Context,
    private val onInitCallback: ((Boolean) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AlertManager"
        const val DEFAULT_VIBRATION_DURATION_MS = 400L
        const val MIN_STRENGTH = 1
        const val MAX_STRENGTH = 255
    }

    // ── TextToSpeech ──────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null

    // ── Rule Engine ───────────────────────────────────────────────────────
    /** All 4 Pair-B alerting rules live here. */
    val ruleEngine = AlertRuleEngine()

    // ── Listener for UI / caller ──────────────────────────────────────────
    var eventListener: AlertEventListener? = null

    interface AlertEventListener {
        fun onSpeechStarted(text: String)
        fun onSpeechCompleted(text: String)
        fun onSpeechError(text: String, errorMessage: String)
        fun onVibrated(strength: Int, amplitude: Int, durationMs: Long)
        /**
         * Called when a detection frame is processed by the rule engine.
         * @param candidate  The approved alert (non-null) or null if all rules blocked it.
         * @param blockedReason Human-readable explanation when [candidate] is null.
         */
        fun onFrameProcessed(candidate: AlertCandidate?, blockedReason: String?)
    }

    // ── Vibrator ──────────────────────────────────────────────────────────
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Vibrator: ${e.message}", e)
            null
        }
    }

    init {
        initTextToSpeech()
    }

    // ── TTS Initialisation ────────────────────────────────────────────────

    private fun initTextToSpeech() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech: ${e.message}", e)
            onInitCallback?.invoke(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language US not supported, falling back to default locale")
                tts?.setLanguage(Locale.getDefault())
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance started: $utteranceId")
                    utteranceId?.let { eventListener?.onSpeechStarted(it) }
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance completed: $utteranceId")
                    utteranceId?.let { eventListener?.onSpeechCompleted(it) }
                }

                @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS utterance error: $utteranceId")
                    utteranceId?.let { eventListener?.onSpeechError(it, "Speech error") }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    val msg = "TTS error code: $errorCode"
                    Log.e(TAG, "$msg for utteranceId: $utteranceId")
                    utteranceId?.let { eventListener?.onSpeechError(it, msg) }
                }
            })

            isTtsReady = true
            Log.i(TAG, "TextToSpeech successfully initialized")
            onInitCallback?.invoke(true)

            // Flush any message queued before TTS was ready
            pendingSpeechText?.let {
                speak(it)
                pendingSpeechText = null
            }
        } else {
            isTtsReady = false
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            onInitCallback?.invoke(false)
        }
    }

    // ── Rule-Engine Entry Point ───────────────────────────────────────────

    /**
     * PRIMARY ENTRY POINT for camera / sensor frames.
     *
     * Feed every detection frame here. The method:
     *  1. Passes detections through [AlertRuleEngine] (all 4 rules).
     *  2. If approved: fires [speak] with priority prefix + [vibratePattern] based on distance.
     *  3. Notifies [eventListener.onFrameProcessed] in both approved and blocked cases.
     *
     * @param detectedObjects All objects seen in this camera frame.
     * @param nowMs           Current time ms (injectable for testing; defaults to wall clock).
     */
    fun processFrame(
        detectedObjects: List<DetectedObject>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (detectedObjects.isEmpty()) {
            eventListener?.onFrameProcessed(null, "No detections in frame")
            return
        }

        val candidate = ruleEngine.onFrame(detectedObjects, nowMs)

        if (candidate == null) {
            // Build a human-readable reason for the UI log
            val reasons = detectedObjects.joinToString("; ") { obj ->
                val label = obj.label.lowercase().trim()
                val frames = ruleEngine.frameCount(label)
                val cooldown = ruleEngine.cooldownRemainingMs(label, nowMs)
                when {
                    frames < AlertRuleEngine.REQUIRED_CONSECUTIVE_FRAMES ->
                        "${obj.label}: Rule#1 — only $frames/${AlertRuleEngine.REQUIRED_CONSECUTIVE_FRAMES} frames"
                    cooldown > 0L ->
                        "${obj.label}: Rule#2 — cooldown ${cooldown}ms"
                    else ->
                        "${obj.label}: blocked"
                }
            }
            eventListener?.onFrameProcessed(null, reasons)
            return
        }

        val obj = candidate.obj

        // Rule #3 — distance-based vibration pattern
        val pattern = AlertRuleEngine.vibrationPattern(obj.distanceCm, MAX_STRENGTH)
        vibratePattern(pattern, obj.distanceCm)

        // TTS alert text with priority prefix
        speak(obj.toAlertText())

        eventListener?.onFrameProcessed(candidate, null)
    }

    // ── Core Functions ───────────────────────────────────────────────────

    /**
     * Speaks [text] immediately via TextToSpeech (QUEUE_FLUSH).
     *
     * @param text The message to synthesize.
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "speak() called with empty or blank text")
            return
        }

        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready. Queuing: $text")
            pendingSpeechText = text
            eventListener?.onSpeechStarted("$text (queued – TTS engine starting)")
            return
        }

        val utteranceId = text.take(30) + "_" + System.currentTimeMillis()
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak failed for text: $text")
            eventListener?.onSpeechError(text, "speak() returned TextToSpeech.ERROR")
        } else {
            Log.i(TAG, "TTS speaking: \"$text\"")
            eventListener?.onSpeechStarted(text)
        }
    }

    /**
     * Vibrates at [strength] amplitude for [DEFAULT_VIBRATION_DURATION_MS].
     *
     * Rule #3 is NOT applied here — this overload is for direct/manual tests.
     * Use [processFrame] to get the distance-adaptive pattern automatically.
     *
     * @param strength Amplitude [1..255].
     */
    fun vibrate(strength: Int) {
        vibrate(strength, DEFAULT_VIBRATION_DURATION_MS)
    }

    /**
     * Vibrates at [strength] amplitude for [durationMillis] milliseconds.
     *
     * @param strength      Amplitude [1..255].
     * @param durationMillis Duration of the vibration burst in milliseconds.
     */
    fun vibrate(strength: Int, durationMillis: Long) {
        val vib = vibrator
        if (vib == null || !vib.hasVibrator()) {
            Log.w(TAG, "No vibration hardware")
            eventListener?.onSpeechError("Vibration", "No vibration hardware detected")
            return
        }

        val amplitude = when {
            strength <= 0       -> VibrationEffect.DEFAULT_AMPLITUDE
            strength in 1..10   -> ((strength / 10f) * MAX_STRENGTH).toInt().coerceIn(MIN_STRENGTH, MAX_STRENGTH)
            else                -> strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMillis, amplitude)
                } else {
                    VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMillis)
            }
            Log.i(TAG, "Vibrated: strength=$strength amplitude=$amplitude duration=${durationMillis}ms")
            eventListener?.onVibrated(strength, amplitude, durationMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate: ${e.message}", e)
        }
    }

    /**
     * Rule #3 — fires a distance-adapted waveform [VibrationPattern].
     *
     * Uses [VibrationEffect.createWaveform] for the multi-pulse pattern.
     * Automatically falls back to a simple one-shot if amplitude control is absent.
     *
     * @param pattern    The [VibrationPattern] produced by [AlertRuleEngine.vibrationPattern].
     * @param distanceCm Distance to the object (used only for event reporting).
     */
    fun vibratePattern(pattern: VibrationPattern, distanceCm: Float) {
        val vib = vibrator
        if (vib == null || !vib.hasVibrator()) {
            Log.w(TAG, "No vibration hardware for pattern vibration")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, pattern.repeatIndex)
                } else {
                    // No amplitude control: use timing-only waveform with max amplitude
                    val defaultAmps = IntArray(pattern.timings.size) { idx ->
                        if (pattern.amplitudes[idx] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0
                    }
                    VibrationEffect.createWaveform(pattern.timings, defaultAmps, pattern.repeatIndex)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern.timings, pattern.repeatIndex)
            }

            val totalDuration = pattern.timings.sum()
            Log.i(TAG, "Pattern vibration fired: dist=${distanceCm}cm total=${totalDuration}ms")
            eventListener?.onVibrated(MAX_STRENGTH, MAX_STRENGTH, totalDuration)
        } catch (e: Exception) {
            Log.e(TAG, "Pattern vibration failed: ${e.message}", e)
        }
    }

    // ── Hardware Diagnostics ──────────────────────────────────────────────

    /** Returns true if TTS is initialized and ready to speak. */
    fun isReady(): Boolean = isTtsReady

    /** Returns true if the device has a vibration motor. */
    fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    /** Returns true if the hardware supports fine amplitude control. */
    fun hasAmplitudeControl(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.hasAmplitudeControl() == true
        } else {
            false
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Releases TTS engine and cancels any active vibration.
     * Call from [Activity.onDestroy].
     */
    fun shutdown() {
        try {
            vibrator?.cancel()
            tts?.stop()
            tts?.shutdown()
            isTtsReady = false
            ruleEngine.reset()
            Log.i(TAG, "AlertManager shut down cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down AlertManager: ${e.message}", e)
        }
    }
}
