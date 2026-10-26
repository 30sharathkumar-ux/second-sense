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
 * Provides sensory alert capabilities:
 * 1. speak(text) using Android TextToSpeech
 * 2. vibrate(strength) using Android VibrationEffect
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

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null

    // Listener for UI or caller to observe speak and vibration events
    var eventListener: AlertEventListener? = null

    interface AlertEventListener {
        fun onSpeechStarted(text: String)
        fun onSpeechCompleted(text: String)
        fun onSpeechError(text: String, errorMessage: String)
        fun onVibrated(strength: Int, amplitude: Int, durationMs: Long)
    }

    // Vibrator service acquisition supporting Android 12 (S)+ and older
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
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

            // Speak any queued text requested prior to initialization
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

    /**
     * Requirement: speak(text) using TextToSpeech.
     *
     * Queues/flushes the utterance and speaks the given text out loud.
     *
     * @param text The string message to synthesize.
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "speak() called with empty or blank text")
            return
        }

        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready yet. Caching message for playback once ready: $text")
            pendingSpeechText = text
            eventListener?.onSpeechStarted("$text (queued, waiting for TTS engine)")
            return
        }

        val utteranceId = text.take(30) + "_" + System.currentTimeMillis()
        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak failed for text: $text")
            eventListener?.onSpeechError(text, "speak() returned TextToSpeech.ERROR")
        } else {
            Log.i(TAG, "TTS speaking: \"$text\"")
            eventListener?.onSpeechStarted(text)
        }
    }

    /**
     * Requirement: vibrate(strength) using VibrationEffect.
     *
     * Vibrates the device at the given intensity strength.
     *
     * @param strength Desired strength. Supports standard amplitude scale (1 to 255)
     *                 or mapped 1-10 scale.
     */
    fun vibrate(strength: Int) {
        vibrate(strength, DEFAULT_VIBRATION_DURATION_MS)
    }

    /**
     * Overload supporting custom duration while adhering to VibrationEffect requirement.
     *
     * @param strength Amplitude value (1 to 255).
     * @param durationMillis Duration of the vibration burst in milliseconds.
     */
    fun vibrate(strength: Int, durationMillis: Long) {
        val vib = vibrator
        if (vib == null || !vib.hasVibrator()) {
            Log.w(TAG, "Device does not have vibration hardware")
            eventListener?.onSpeechError("Vibration", "No vibration hardware detected")
            return
        }

        // Normalize strength to valid amplitude (1..255)
        // If caller passed 1..10, scale up to 1..255
        val amplitude = when {
            strength <= 0 -> VibrationEffect.DEFAULT_AMPLITUDE
            strength in 1..10 -> ((strength / 10f) * MAX_STRENGTH).toInt().coerceIn(MIN_STRENGTH, MAX_STRENGTH)
            else -> strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Using VibrationEffect as specified in requirements
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMillis, amplitude)
                } else {
                    // Fall back to default amplitude if amplitude control isn't supported by hardware
                    VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMillis)
            }

            Log.i(TAG, "Vibrated with strength=$strength (amplitude=$amplitude, duration=${durationMillis}ms)")
            eventListener?.onVibrated(strength, amplitude, durationMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate: ${e.message}", e)
        }
    }

    /**
     * Checks if TTS is ready to speak immediately.
     */
    fun isReady(): Boolean = isTtsReady

    /**
     * Checks if hardware vibrator is present.
     */
    fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    /**
     * Checks if hardware supports fine-grained amplitude modulation.
     */
    fun hasAmplitudeControl(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.hasAmplitudeControl() == true
        } else {
            false
        }
    }

    /**
     * Releases TTS and vibrator resources cleanly when activity is destroyed.
     */
    fun shutdown() {
        try {
            vibrator?.cancel()
            tts?.stop()
            tts?.shutdown()
            isTtsReady = false
            Log.i(TAG, "AlertManager resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down AlertManager: ${e.message}", e)
        }
    }
}
