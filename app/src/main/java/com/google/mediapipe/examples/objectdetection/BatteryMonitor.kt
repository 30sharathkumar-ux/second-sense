package com.google.mediapipe.examples.objectdetection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * BatteryMonitor — Step 5: Battery Alerts
 *
 * Registers a BroadcastReceiver for [Intent.ACTION_BATTERY_CHANGED].
 * Speaks alerts (in the active language) when battery first drops to or below
 * each threshold while NOT charging. Each threshold fires only once per discharge
 * cycle; flags reset when the phone is plugged in or the level rises above a threshold.
 *
 * Thresholds:
 *   30 % — "Battery 30 percent"
 *   20 % — "Battery 20 percent"
 *   10 % — "Battery 10 percent, please charge"   + vibration
 *    5 % — "Battery 5 percent, critical"          + vibration
 *
 * Battery alerts do NOT interrupt an active DANGER alert; they are queued via
 * QUEUE_ADD so the TTS engine delivers them after the current utterance.
 *
 * Register in Activity.onStart / unregister in Activity.onStop.
 */
class BatteryMonitor(
    private val context: Context,
    private val audioHapticManager: AudioHapticManager,
    private val languageProvider: () -> Messages.Language
) {

    companion object {
        private const val TAG = "BatteryMonitor"

        /** Percentage thresholds that trigger an alert (descending order). */
        val THRESHOLDS = intArrayOf(30, 20, 10, 5)

        /** Thresholds that also trigger a vibration alert. */
        val VIBRATE_THRESHOLDS = setOf(10, 5)
    }

    // Fired flags: one per threshold. True = alert already spoken for this discharge cycle.
    private val fired = mutableMapOf(
        30 to false,
        20 to false,
        10 to false,
        5  to false
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return

            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return

            val percent  = (level * 100 / scale)
            val charging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)

            Log.d(TAG, "Battery: $percent% charging=$charging")

            if (charging) {
                // Reset all fired flags when plugged in
                fired.keys.forEach { fired[it] = false }
                return
            }

            // Check each threshold from lowest to highest (most critical first)
            for (threshold in THRESHOLDS.sortedDescending()) {
                if (percent <= threshold && fired[threshold] == false) {
                    fired[threshold] = true
                    speakBatteryAlert(threshold)
                }
                // Reset flag if level rises above this threshold
                if (percent > threshold) {
                    fired[threshold] = false
                }
            }
        }
    }

    private fun speakBatteryAlert(threshold: Int) {
        val lang = languageProvider()
        val message = when (threshold) {
            30 -> Messages.battery30(lang)
            20 -> Messages.battery20(lang)
            10 -> Messages.battery10(lang)
             5 -> Messages.battery5(lang)
            else -> return
        }
        Log.i(TAG, "Battery alert: $message")

        // Use QUEUE_ADD so battery alerts do not interrupt an active DANGER alert
        // AudioHapticManager.speak uses QUEUE_FLUSH by default for urgent; pass isUrgent=false
        audioHapticManager.speak(message, isUrgent = false)

        if (threshold in VIBRATE_THRESHOLDS) {
            vibrateWarning()
        }
    }

    private fun vibrateWarning() {
        val vib: Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) { null }

        vib ?: return
        if (!vib.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Two slow pulses to signal battery warning
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 200, 300),
                    intArrayOf(0, 180, 0, 180),
                    -1
                )
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 300, 200, 300), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery vibration failed: ${e.message}")
        }
    }

    /**
     * Register the receiver. Call from [android.app.Activity.onStart].
     */
    fun register() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        Log.i(TAG, "BatteryMonitor registered")
    }

    /**
     * Unregister the receiver. Call from [android.app.Activity.onStop].
     */
    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
            Log.i(TAG, "BatteryMonitor unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "BatteryMonitor already unregistered: ${e.message}")
        }
    }
}
