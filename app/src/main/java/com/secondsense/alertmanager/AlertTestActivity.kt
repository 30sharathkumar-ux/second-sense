package com.secondsense.alertmanager

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.ActivityAlertTestBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertTestActivity : AppCompatActivity(), AlertManager.AlertEventListener {

    private lateinit var binding: ActivityAlertTestBinding
    private lateinit var alertManager: AlertManager

    private var currentStrength: Int = 255
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Rule test state ──────────────────────────────────────────────────
    /** Current object label in the Rules Test panel. */
    private var ruleTestLabel: String = "person"
    /** Current simulated distance (cm) in the Rules Test panel. */
    private var ruleTestDistanceCm: Float = 80f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize AlertManager with TTS-ready callback
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
        setupRulesTestPanel()
        setupLogControls()
    }

    // ── Hardware Diagnostics ─────────────────────────────────────────────

    private fun setupHardwareDiagnostics() {
        binding.tvHardwareSupport.text = when {
            !alertManager.hasVibrator()       -> "Vibrator: None"
            alertManager.hasAmplitudeControl() -> "Vibrator: Full Amplitude Support"
            else                               -> "Vibrator: Basic Support"
        }
    }

    // ── Speech Controls ──────────────────────────────────────────────────

    private fun setupSpeechControls() {
        binding.chipObstacle.setOnClickListener {
            binding.etSpeakText.setText("Warning! Obstacle detected 50 centimetres ahead.")
        }
        binding.chipTurn.setOnClickListener {
            binding.etSpeakText.setText("Clear path detected on the left. Turn left.")
        }
        binding.chipBattery.setOnClickListener {
            binding.etSpeakText.setText("Alert! Device battery level is below 15 percent.")
        }

        binding.btnSpeak.setOnClickListener {
            val text = binding.etSpeakText.text?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter speech text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            alertManager.speak(text)
            triggerPulseAnimation(getColor(R.color.primary))
        }
    }

    // ── Vibration Controls ───────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun setupVibrationControls() {
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            currentStrength = value.toInt()
            val pct = ((currentStrength / 255f) * 100).toInt()
            binding.tvStrengthLabel.text = "Vibration Strength: $currentStrength / 255 ($pct%)"
        }

        binding.btnPresetLow.setOnClickListener { binding.sliderStrength.value = 64f }
        binding.btnPresetMed.setOnClickListener { binding.sliderStrength.value = 160f }
        binding.btnPresetMax.setOnClickListener { binding.sliderStrength.value = 255f }

        binding.btnVibrate.setOnClickListener {
            alertManager.vibrate(currentStrength)
            triggerPulseAnimation(getColor(R.color.vibrate_accent))
        }
    }

    // ── Rules Test Panel ─────────────────────────────────────────────────

    private fun setupRulesTestPanel() {
        // Quick object presets (Rule #4 — priority examples)
        binding.chipPerson.setOnClickListener {
            binding.etObjectLabel.setText("person")
            binding.etDistance.setText("80")
        }
        binding.chipCar.setOnClickListener {
            binding.etObjectLabel.setText("car")
            binding.etDistance.setText("200")
        }
        binding.chipDog.setOnClickListener {
            binding.etObjectLabel.setText("dog")
            binding.etDistance.setText("120")
        }
        binding.chipChair.setOnClickListener {
            binding.etObjectLabel.setText("chair")
            binding.etDistance.setText("60")
        }

        // Simulate one detection frame through the rule engine
        binding.btnSimulateFrame.setOnClickListener {
            ruleTestLabel    = binding.etObjectLabel.text?.toString()?.trim() ?: "object"
            ruleTestDistanceCm = binding.etDistance.text?.toString()?.toFloatOrNull() ?: 80f

            if (ruleTestLabel.isEmpty()) {
                Toast.makeText(this, "Enter an object label to simulate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val detected = DetectedObject(
                label       = ruleTestLabel,
                distanceCm  = ruleTestDistanceCm,
                confidence  = 0.95f
            )

            // Feed single detection frame through all 4 rules
            alertManager.processFrame(listOf(detected))

            // Refresh live status badges after processing
            refreshRuleStatusBadges(ruleTestLabel)
        }

        // Reset the rule engine state (counters + cooldowns)
        binding.btnResetRules.setOnClickListener {
            alertManager.ruleEngine.reset()
            refreshRuleStatusBadges(ruleTestLabel)
            appendLog("Rule engine reset. Frame counters and cooldowns cleared.")
            Toast.makeText(this, "Rule engine reset", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Refreshes the live frame-count, cooldown, and priority badges for [label].
     */
    @SuppressLint("SetTextI18n")
    private fun refreshRuleStatusBadges(label: String) {
        val engine   = alertManager.ruleEngine
        val frames   = engine.frameCount(label)
        val cooldown = engine.cooldownRemainingMs(label)
        val priority = AlertPriority.forObject(label)

        // Rule #1 badge
        binding.tvFrameCounter.text = "Frames: $frames / ${AlertRuleEngine.REQUIRED_CONSECUTIVE_FRAMES}"
        binding.tvFrameCounter.setTextColor(
            if (frames >= AlertRuleEngine.REQUIRED_CONSECUTIVE_FRAMES)
                getColor(R.color.accent)
            else
                getColor(R.color.text_primary)
        )

        // Rule #2 badge
        binding.tvCooldownStatus.text = if (cooldown > 0L) {
            "Cooldown: ${cooldown / 1000.0f}s"
        } else {
            "Cooldown: —"
        }

        // Rule #4 priority badge
        binding.tvPriorityBadge.text = priority.label
        binding.tvPriorityBadge.setBackgroundColor(
            when (priority) {
                AlertPriority.DANGER     -> Color.parseColor("#B71C1C")
                AlertPriority.HAZARD     -> Color.parseColor("#C62828")
                AlertPriority.PERSON_VEH -> Color.parseColor("#E65100")
                AlertPriority.OTHER      -> getColor(R.color.status_badge_bg)
            }
        )
        binding.tvPriorityBadge.setTextColor(
            if (priority == AlertPriority.OTHER) getColor(R.color.status_badge_text)
            else Color.WHITE
        )
    }

    // ── Log Controls ─────────────────────────────────────────────────────

    private fun setupLogControls() {
        binding.btnClearLog.setOnClickListener {
            binding.tvConsoleLog.text = "[Cleared] Ready."
        }
    }

    private fun appendLog(message: String) {
        val ts  = timeFormat.format(Date())
        binding.tvConsoleLog.append("[$ts] $message\n")
    }

    // ── Pulse animation ───────────────────────────────────────────────────

    private fun triggerPulseAnimation(colorTint: Int) {
        binding.pulseIndicator.setBackgroundColor(colorTint)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.pulseIndicator, View.SCALE_X, 1f, 1.8f, 1f),
                ObjectAnimator.ofFloat(binding.pulseIndicator, View.SCALE_Y, 1f, 1.8f, 1f),
                ObjectAnimator.ofFloat(binding.pulseIndicator, View.ALPHA,   1f, 0.4f, 1f)
            )
            duration = 350
            start()
        }
    }

    // ── AlertEventListener ────────────────────────────────────────────────

    override fun onSpeechStarted(text: String) = runOnUiThread {
        appendLog("SPEAK: \"${text.take(60)}\"")
    }

    override fun onSpeechCompleted(text: String) = runOnUiThread {
        appendLog("SPEAK DONE: utterance finished")
    }

    override fun onSpeechError(text: String, errorMessage: String) = runOnUiThread {
        appendLog("ERROR: $errorMessage")
    }

    override fun onVibrated(strength: Int, amplitude: Int, durationMs: Long) = runOnUiThread {
        appendLog("VIBRATE: amp=$amplitude duration=${durationMs}ms")
        triggerPulseAnimation(getColor(R.color.vibrate_accent))
    }

    /**
     * Called by AlertManager after each [processFrame] call.
     * Displays whether the alert fired or which rule blocked it.
     */
    override fun onFrameProcessed(candidate: AlertCandidate?, blockedReason: String?) {
        runOnUiThread {
            if (candidate != null) {
                val obj = candidate.obj
                appendLog(
                    "✅ ALERT FIRED — ${obj.label} | priority=${obj.priority.label} " +
                    "| dist=${obj.distanceCm}cm | text=\"${obj.toAlertText()}\""
                )
                triggerPulseAnimation(
                    when (obj.priority) {
                        AlertPriority.DANGER     -> Color.parseColor("#B71C1C")
                        AlertPriority.HAZARD     -> Color.parseColor("#C62828")
                        AlertPriority.PERSON_VEH -> Color.parseColor("#E65100")
                        AlertPriority.OTHER      -> getColor(R.color.primary)
                    }
                )
            } else {
                appendLog("⛔ BLOCKED — $blockedReason")
            }
            refreshRuleStatusBadges(ruleTestLabel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        alertManager.shutdown()
    }
}
