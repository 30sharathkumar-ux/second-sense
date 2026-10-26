/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mediapipe.examples.objectdetection

import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mediapipe.examples.objectdetection.databinding.ActivityMainBinding

/**
 * Main entry point into Second Sense — an offline AI vision assistant for blind users.
 * Runs in full-screen immersive mode with the camera filling the entire display.
 * Manages the global AudioHapticManager and provides the core detection pipeline.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "SecondSense"
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** Shared audio and haptics coordinator */
    lateinit var audioHapticManager: AudioHapticManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on — essential for an assistive vision device
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Audio & Haptics Manager (offline TTS & vibration)
        audioHapticManager = AudioHapticManager(this)

        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        // Enable full-screen immersive mode
        setupImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode when returning from another app
        setupImmersiveMode()
    }

    private fun setupImmersiveMode() {
        // Draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowInsetsControllerCompat(window, activityMainBinding.root)
        // Hide both status bar and navigation bar
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        // Use swipe to temporarily reveal system bars
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Core detection pipeline helper:
     * 1. Bounding box coordinates from detection are passed to DistanceDirectionCalculator.calculate(...)
     * 2. If shouldAlert is true, format message: "$label $distanceState, $direction"
     * 3. Call audioHapticManager.speak(message)
     * 4. If distanceState == "very close", call audioHapticManager.vibrate(isClose = true)
     *    If distanceState == "near", call audioHapticManager.vibrate(isClose = false)
     */
    fun processDetection(
        label: String,
        boundingBox: RectF,
        frameWidth: Float,
        frameHeight: Float
    ): String {
        val result = DistanceDirectionCalculator.calculate(
            box = boundingBox,
            screenWidth = frameWidth,
            screenHeight = frameHeight,
            label = label
        )

        val message = "${result.label} ${result.distanceState}, ${result.direction}"
        Log.d(TAG, "Processed detection: $message (shouldAlert=${result.shouldAlert})")

        if (result.shouldAlert) {
            audioHapticManager.speak(message)

            if (result.distanceState == DistanceDirectionCalculator.DISTANCE_VERY_CLOSE) {
                audioHapticManager.vibrate(isClose = true)
            } else if (result.distanceState == DistanceDirectionCalculator.DISTANCE_NEAR) {
                audioHapticManager.vibrate(isClose = false)
            }
        }

        return message
    }

    override fun onDestroy() {
        if (this::audioHapticManager.isInitialized) {
            audioHapticManager.shutdown()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }
}
