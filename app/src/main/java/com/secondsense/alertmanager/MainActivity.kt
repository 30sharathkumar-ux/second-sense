package com.secondsense.alertmanager

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.secondsense.alertmanager.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), AlertManager.AlertEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var alertManager: AlertManager

    private var currentStrength: Int = 255
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize AlertManager with a callback for TTS engine readiness
        alertManager = AlertManager(this) { isSuccess ->
            runOnUiThread {
                if (isSuccess) {
                    binding.tvEngineStatus.text = "TTS: Ready"
                    binding.tvEngineStatus.setTextColor(getColor(R.color.status_badge_text))
                    binding.tvEngineStatus.setBackgroundColor(getColor(R.color.status_badge_bg))
                    appendLog("TTS Engine initialized and ready.")
                } else {
                    binding.tvEngineStatus.text = "TTS: Failed"
                    binding.tvEngineStatus.setTextColor(Color.RED)
                    appendLog("TTS Engine failed to initialize.")
                }
            }
        }
        alertManager.eventListener = this

        setupHardwareDiagnostics()
        setupSpeechControls()
        setupVibrationControls()
        setupLogControls()
    }

    private fun setupHardwareDiagnostics() {
        val hasVibrator = alertManager.hasVibrator()
        val hasAmpControl = alertManager.hasAmplitudeControl()

        binding.tvHardwareSupport.text = when {
            !hasVibrator -> "Vibrator: None"
            hasAmpControl -> "Vibrator: Full Amplitude Support"
            else -> "Vibrator: Basic Support"
        }
    }

    private fun setupSpeechControls() {
        // Quick phrase chips
        binding.chipObstacle.setOnClickListener {
            binding.etSpeakText.setText("Warning! Obstacle detected 50 centimeters ahead.")
        }

        binding.chipTurn.setOnClickListener {
            binding.etSpeakText.setText("Clear path detected on the left. Turn left.")
        }

        binding.chipBattery.setOnClickListener {
            binding.etSpeakText.setText("Alert! Device battery level is below 15 percent.")
        }

        // Requirement: "Speak" button
        binding.btnSpeak.setOnClickListener {
            val textToSpeak = binding.etSpeakText.text?.toString()?.trim()
            if (textToSpeak.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter speech text to test speak()", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Call AlertManager function
            alertManager.speak(textToSpeak)
            triggerPulseAnimation(getColor(R.color.primary))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupVibrationControls() {
        // Strength slider listener
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            currentStrength = value.toInt()
            val percentage = ((currentStrength / 255f) * 100).toInt()
            binding.tvStrengthLabel.text = "Vibration Strength: $currentStrength / 255 ($percentage%)"
        }

        // Preset strength buttons
        binding.btnPresetLow.setOnClickListener {
            binding.sliderStrength.value = 64f
        }

        binding.btnPresetMed.setOnClickListener {
            binding.sliderStrength.value = 160f
        }

        binding.btnPresetMax.setOnClickListener {
            binding.sliderStrength.value = 255f
        }

        // Requirement: "Vibrate" button
        binding.btnVibrate.setOnClickListener {
            // Call AlertManager function
            alertManager.vibrate(currentStrength)
            triggerPulseAnimation(getColor(R.color.vibrate_accent))
        }
    }

    private fun setupLogControls() {
        binding.btnClearLog.setOnClickListener {
            binding.tvConsoleLog.text = "[Cleared] Ready."
        }
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val newEntry = "[$timestamp] $message\n"
        binding.tvConsoleLog.append(newEntry)
    }

    private fun triggerPulseAnimation(colorTint: Int) {
        binding.pulseIndicator.setBackgroundColor(colorTint)

        val scaleX = ObjectAnimator.ofFloat(binding.pulseIndicator, View.SCALE_X, 1f, 1.8f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.pulseIndicator, View.SCALE_Y, 1f, 1.8f, 1f)
        val alpha = ObjectAnimator.ofFloat(binding.pulseIndicator, View.ALPHA, 1f, 0.4f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 350
            start()
        }
    }

    // AlertEventListener callbacks
    override fun onSpeechStarted(text: String) {
        runOnUiThread {
            appendLog("SPEAK: \"$text\"")
        }
    }

    override fun onSpeechCompleted(text: String) {
        runOnUiThread {
            appendLog("SPEAK COMPLETED: Utterance finished")
        }
    }

    override fun onSpeechError(text: String, errorMessage: String) {
        runOnUiThread {
            appendLog("ERROR: $errorMessage (Text: $text)")
        }
    }

    override fun onVibrated(strength: Int, amplitude: Int, durationMs: Long) {
        runOnUiThread {
            appendLog("VIBRATE: strength=$strength (amp=$amplitude, ${durationMs}ms)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up TextToSpeech and Vibrator resources
        alertManager.shutdown()
    }
}
