package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * AudioHapticManager coordinates offline speech feedback and tactile haptics
 * specifically tailored for blind and visually impaired users.
 *
 * Audio: Built-in Android TextToSpeech configured for offline English speech.
 * Haptics:
 *   - Heavy pulse for immediate navigational hazards.
 *   - Short tap for detected obstacles.
 *   - Distinct tactile confirmations for starting and stopping scanning.
 */
class AudioHapticManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioHapticManager"
        private const val UTTERANCE_ID_HAZARD = "second_sense_hazard"
        private const val UTTERANCE_ID_OBSTACLE = "second_sense_obstacle"
        private const val UTTERANCE_ID_SYSTEM = "second_sense_system"

        // Minimum time between repeating the exact same cue to avoid audio fatigue
        private const val SAME_CUE_DEBOUNCE_MS = 2500L
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var isTtsInitialized = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var lastSpokenCue: String = ""
    private var lastSpokenTimestamp: Long = 0L

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Locale.US not supported, falling back to Locale.ENGLISH")
                    engine.setLanguage(Locale.ENGLISH)
                }

                // Optimal speech rate for quick spatial awareness without sounding unnatural
                engine.setSpeechRate(1.05f)
                engine.setPitch(1.0f)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started utterance: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS completed utterance: $utteranceId")
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error on utterance: $utteranceId")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error code $errorCode on utterance: $utteranceId")
                    }
                })

                isTtsInitialized = true
                Log.i(TAG, "TextToSpeech successfully initialized in offline English mode")
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            isTtsInitialized = false
        }
    }

    // =========================================================================
    // Haptic Feedback Implementation
    // =========================================================================

    /**
     * Vibrates with pattern scaled to proximity:
     * @param isClose If true, triggers heavy pulse alert for close/hazard objects;
     *                if false, triggers short tap tactile confirmation for general obstacles.
     */
    fun vibrate(isClose: Boolean = true) {
        if (isClose) {
            vibrateHazard()
        } else {
            vibrateObstacle()
        }
    }

    /**
     * Heavy pulse haptic for immediate hazards (e.g., vehicles, stairs, close barriers).
     * Two aggressive vibration bursts designed to immediately capture user attention.
     */
    fun vibrateHazard() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Waveform: wait 0ms, 250ms heavy pulse, 80ms rest, 300ms heavy pulse
                val timings = longArrayOf(0, 250, 80, 300)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 250, 80, 300), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing hazard vibration", e)
        }
    }

    /**
     * Short tap haptic for general detected obstacles (e.g., chairs, tables, doors).
     * Crisp, subtle tactile click confirming an object in path without alarming user.
     */
    fun vibrateObstacle() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vib.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(70)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing obstacle vibration", e)
        }
    }

    /**
     * Double tactile blip confirming scanning has started.
     */
    fun vibrateStartConfirmation() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 60, 80, 60)
                val amplitudes = intArrayOf(0, 180, 0, 220)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 60, 80, 60), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing start vibration", e)
        }
    }

    /**
     * Single subtle tap confirming scanning has stopped.
     */
    fun vibrateStopConfirmation() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(120, 150))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(120)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing stop vibration", e)
        }
    }

    // =========================================================================
    // High-Level Audio & Haptic Alert Actions
    // =========================================================================

    /**
     * Alerts user to a hazardous object:
     * Triggers the heavy pulse haptic immediately and interrupts any pending speech
     * to announce the urgent hazard cue.
     */
    fun alertHazard(voiceCue: String) {
        vibrateHazard()
        speak(voiceCue, isUrgent = true, utteranceId = UTTERANCE_ID_HAZARD)
    }

    /**
     * Alerts user to an ordinary obstacle:
     * Triggers short tap haptic and speaks directional voice cue.
     */
    fun alertObstacle(voiceCue: String) {
        vibrateObstacle()
        speak(voiceCue, isUrgent = false, utteranceId = UTTERANCE_ID_OBSTACLE)
    }

    /**
     * System announcement (e.g. "Second Sense started. Scanning active.")
     */
    fun announceSystem(message: String) {
        speak(message, isUrgent = true, utteranceId = UTTERANCE_ID_SYSTEM, bypassDebounce = true)
    }

    /**
     * Speaks the provided text using Android TextToSpeech.
     *
     * @param text Text to speak
     * @param isUrgent If true, uses QUEUE_FLUSH to immediately interrupt prior speech
     * @param utteranceId Identifier for speech tracking
     * @param bypassDebounce If true, ignores repeating check (for user-triggered system states)
     */
    fun speak(
        text: String,
        isUrgent: Boolean = false,
        utteranceId: String = UTTERANCE_ID_OBSTACLE,
        bypassDebounce: Boolean = false
    ) {
        if (!isTtsInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, unable to speak: $text")
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        if (!bypassDebounce && text.equals(lastSpokenCue, ignoreCase = true)) {
            if (currentTime - lastSpokenTimestamp < SAME_CUE_DEBOUNCE_MS) {
                // Skip repeating identical cue within debounce window
                return
            }
        }

        lastSpokenCue = text
        lastSpokenTimestamp = currentTime

        val queueMode = if (isUrgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_FLUSH

        tts?.speak(text, queueMode, null, utteranceId)
    }

    /**
     * Release all TTS and Vibrator resources when Fragment/Activity is destroyed.
     */
    fun shutdown() {
        try {
            vibrator?.cancel()
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsInitialized = false
            Log.i(TAG, "AudioHapticManager cleanly shut down")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down AudioHapticManager", e)
        }
    }
}
