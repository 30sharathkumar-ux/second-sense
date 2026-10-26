/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.mediapipe.examples.objectdetection.AudioHapticManager
import com.google.mediapipe.examples.objectdetection.DistanceDirectionCalculator
import com.google.mediapipe.examples.objectdetection.MainActivity
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.SpatialLogic
import com.google.mediapipe.examples.objectdetection.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "SecondSense"

    companion object {
        /** Frame analysis interval in milliseconds (every 3 seconds) */
        private const val FRAME_INTERVAL_MS = 3000L
        /** 3-second cooldown timer between repeated voice alerts to avoid audio spam */
        private const val VOICE_ALERT_COOLDOWN_MS = 3000L
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Audio & Haptics Manager for blind user accessibility */
    private lateinit var audioHapticManager: AudioHapticManager

    /** Scanning active flag — controlled by the large UI toggle button */
    @Volatile
    private var isScanningActive: Boolean = false

    /** Timestamp of the last processed frame — used for 3-second throttle */
    @Volatile
    private var lastAnalyzedTimestamp = 0L

    /** Cooldown tracking for repeated voice alerts */
    private var lastVoiceAlertTimestamp = 0L
    private var lastSpokenAlertMessage = ""

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            )
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }

        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // save ObjectDetector settings
        if (this::objectDetectorHelper.isInitialized) {
            viewModel.setModel(objectDetectorHelper.currentModel)
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)
            // Close the object detector and release resources
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        if (activity !is MainActivity && this::audioHapticManager.isInitialized) {
            audioHapticManager.shutdown()
        }
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor.
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Audio & Haptics coordinator (reuse activity-level manager if available)
        audioHapticManager = (activity as? MainActivity)?.audioHapticManager
            ?: AudioHapticManager(requireContext())

        // Initialize background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Create ObjectDetectorHelper for live inference
        backgroundExecutor.execute {
            objectDetectorHelper =
                ObjectDetectorHelper(
                    context = requireContext(),
                    threshold = viewModel.currentThreshold,
                    currentDelegate = viewModel.currentDelegate,
                    currentModel = viewModel.currentModel,
                    maxResults = viewModel.currentMaxResults,
                    objectDetectorListener = this,
                    runningMode = RunningMode.LIVE_STREAM
                )

            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)

        // Set up the large Start / Stop toggle button and UI state
        setupScanToggleButton()
    }

    /**
     * Configures the large high-contrast toggle button to control the scanning loop.
     */
    private fun setupScanToggleButton() {
        updateScanningUIState(active = false)

        fragmentCameraBinding.btnToggleScan.setOnClickListener {
            // Toggle scanning state
            isScanningActive = !isScanningActive
            updateScanningUIState(active = isScanningActive)

            // Reset voice alert cooldown and last message on toggle
            lastVoiceAlertTimestamp = 0L
            lastSpokenAlertMessage = ""

            if (isScanningActive) {
                // Announce start via TTS and double tactile blip
                audioHapticManager.vibrateStartConfirmation()
                audioHapticManager.announceSystem(getString(R.string.cue_scanning_started))
            } else {
                // Announce stop via TTS and single tactile tap
                audioHapticManager.vibrateStopConfirmation()
                audioHapticManager.announceSystem(getString(R.string.cue_scanning_stopped))

                // Clear visual detection overlay
                fragmentCameraBinding.overlay.clear()
            }
        }
    }

    /**
     * Updates UI visuals (button text, button color, status pill, feedback banner)
     * based on scanning state.
     */
    private fun updateScanningUIState(active: Boolean) {
        val binding = _fragmentCameraBinding ?: return

        if (active) {
            // Scanning is active -> Show STOP SECOND SENSE in red
            binding.btnToggleScan.text = getString(R.string.stop_second_sense)
            binding.btnToggleScan.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_stop)
            binding.btnToggleScan.contentDescription = getString(R.string.desc_btn_stop)

            binding.tvStatus.text = getString(R.string.status_scanning)
            binding.viewStatusDot.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_stop)

            binding.tvDetectionFeedback.text = getString(R.string.status_scanning)
        } else {
            // Scanning is inactive -> Show START SECOND SENSE in green
            binding.btnToggleScan.text = getString(R.string.start_second_sense)
            binding.btnToggleScan.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_start)
            binding.btnToggleScan.contentDescription = getString(R.string.desc_btn_start)

            binding.tvStatus.text = getString(R.string.status_idle)
            binding.viewStatusDot.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_start)

            binding.tvDetectionFeedback.text = getString(R.string.cue_idle)
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { imageProxy ->
                        throttledAnalyze(imageProxy)
                    }
                }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Throttled frame analyzer:
     * 1. If scanning is paused, drops the frame immediately to conserve battery and CPU.
     * 2. When active, only processes frames every [FRAME_INTERVAL_MS] (3 seconds).
     */
    private fun throttledAnalyze(imageProxy: ImageProxy) {
        // Drop frames immediately if scanning loop is not active
        if (!isScanningActive) {
            imageProxy.close()
            return
        }

        val currentTime = SystemClock.uptimeMillis()

        if (currentTime - lastAnalyzedTimestamp < FRAME_INTERVAL_MS) {
            // Skip this frame — throttled to 3 seconds
            imageProxy.close()
            return
        }

        // Update the timestamp and forward to detector
        lastAnalyzedTimestamp = currentTime
        Log.d(TAG, "Processing scan frame at ${currentTime}ms (3-second interval)")
        objectDetectorHelper.detectLivestreamFrame(imageProxy)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Process detection results, classify spatial cues, and provide audio/haptic feedback
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null && isAdded) {
                // Drop results if scanning was stopped in the interim
                if (!isScanningActive) {
                    fragmentCameraBinding.overlay.clear()
                    return@runOnUiThread
                }

                val detectionResult = resultBundle.results[0]

                // Draw bounding boxes on OverlayView
                fragmentCameraBinding.overlay.setResults(
                    detectionResult,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    resultBundle.inputImageRotation
                )
                fragmentCameraBinding.overlay.invalidate()

                val detections = detectionResult.detections()
                if (detections.isEmpty()) {
                    fragmentCameraBinding.tvDetectionFeedback.text =
                        getString(R.string.cue_path_clear)
                    return@runOnUiThread
                }

                // Adjust frame width and height based on rotation for spatial classification
                val rotation = resultBundle.inputImageRotation
                val frameWidth = if (rotation == 90 || rotation == 270) {
                    resultBundle.inputImageHeight.toFloat()
                } else {
                    resultBundle.inputImageWidth.toFloat()
                }
                val frameHeight = if (rotation == 90 || rotation == 270) {
                    resultBundle.inputImageWidth.toFloat()
                } else {
                    resultBundle.inputImageHeight.toFloat()
                }

                // 1. Loop Processing: For every frame where objects are detected
                // Sort detections by area descending so closest / largest hazards are evaluated first
                val sortedDetections = detections.sortedByDescending { det ->
                    val b = det.boundingBox()
                    if (b != null) (b.width() * b.height()) else 0f
                }

                var alertDispatched = false

                for (detection in sortedDetections) {
                    val box = detection.boundingBox() ?: continue
                    val label = detection.categories().firstOrNull()?.categoryName() ?: "obstacle"

                    // Pass coordinates to DistanceDirectionCalculator.calculate(...)
                    val result = DistanceDirectionCalculator.calculate(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        screenWidth = frameWidth,
                        screenHeight = frameHeight,
                        label = label
                    )

                    // If shouldAlert is true, format clear text message: e.g., "$label $distanceState, $direction"
                    if (result.shouldAlert) {
                        val message = "${result.label} ${result.distanceState}, ${result.direction}"
                        Log.i(TAG, "Detection Pipeline Alert: $message")

                        // Update feedback banner on UI for accessibility & testing
                        fragmentCameraBinding.tvDetectionFeedback.text = message

                        // 3. Rate Limiting / Anti-Spam: 3-second cooldown timer between repeated voice alerts
                        val currentTime = SystemClock.uptimeMillis()
                        val isRepeatedAlert = message.equals(lastSpokenAlertMessage, ignoreCase = true)
                        val cooldownPassed = (currentTime - lastVoiceAlertTimestamp) >= VOICE_ALERT_COOLDOWN_MS

                        if (!isRepeatedAlert || cooldownPassed) {
                            lastVoiceAlertTimestamp = currentTime
                            lastSpokenAlertMessage = message

                            // 2. Audio & Haptics Dispatch:
                            // Call audioHapticManager.speak(message)
                            audioHapticManager.speak(message)

                            // If distanceState == "very close" -> heavy pulsing pattern (isClose = true)
                            // If distanceState == "near" -> light tap (isClose = false)
                            if (result.distanceState == DistanceDirectionCalculator.DISTANCE_VERY_CLOSE) {
                                audioHapticManager.vibrate(isClose = true)
                            } else if (result.distanceState == DistanceDirectionCalculator.DISTANCE_NEAR) {
                                audioHapticManager.vibrate(isClose = false)
                            }
                        } else {
                            Log.d(TAG, "Skipping repeated voice alert within 3s cooldown: $message")
                        }

                        alertDispatched = true
                        break // Dispatched the top-priority alert for this frame
                    }
                }

                if (!alertDispatched) {
                    fragmentCameraBinding.tvDetectionFeedback.text =
                        getString(R.string.cue_path_clear)
                }
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Detection error: $error (code: $errorCode)")
        }
    }
}
