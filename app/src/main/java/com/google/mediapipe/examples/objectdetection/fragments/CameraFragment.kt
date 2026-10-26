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
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
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
import com.google.mediapipe.examples.objectdetection.ApproachTracker
import com.google.mediapipe.examples.objectdetection.AudioHapticManager
import com.google.mediapipe.examples.objectdetection.DistanceDirectionCalculator
import com.google.mediapipe.examples.objectdetection.MainActivity
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.Messages
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.DepthEstimator
import com.google.mediapipe.examples.objectdetection.LabelMapper
import com.google.mediapipe.examples.objectdetection.StairsDetector
import com.google.mediapipe.examples.objectdetection.WallDetector
import com.google.mediapipe.examples.objectdetection.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "SecondSense"

    companion object {
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

    /** Step 3: Approach tracker — detects fast-approaching / suddenly appearing objects */
    private val approachTracker = ApproachTracker()

    /** Step 6: Wall/obstacle ahead heuristic (variance-based) */
    private val wallDetector = WallDetector()

    /** Stairs-v4 Step 1: Stairs-up detector (horizontal edge repetition) */
    private val stairsDetector = StairsDetector()

    /** Step 2: MiDaS depth estimator — class-independent obstacle detection. */
    private lateinit var depthEstimator: DepthEstimator
    /** Last depth alert reason string for debug overlay. */
    @Volatile var lastAlertReason: String = ""

    /** Last wall-alert timestamp — used by the debug overlay to show variance. */
    @Volatile private var lastWallVariance = Double.MAX_VALUE

    /** Step 8: Test mode — count taps on the status badge to reveal the test overlay. */
    private var testTapCount = 0
    private var testTapLastMs = 0L
    private val TEST_TAP_WINDOW_MS = 2000L
    private val TEST_TAP_REQUIRED = 5

    /** Scanning active flag — controlled by the large UI toggle button */
    @Volatile
    private var isScanningActive: Boolean = false

    /** Cooldown tracking for repeated voice alerts */
    private var lastVoiceAlertTimestamp = 0L
    private var lastSpokenAlertMessage = ""
    /** Timestamp of the last DANGER alert fired (for its own cooldown tracking). */
    private var lastDangerAlertTimestamp = 0L

    /** Debug mode: show ms/frame overlay. Toggle by long-pressing the status badge. */
    @Volatile
    private var isDebugOverlayVisible = false

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

        // Step 4: Always turn off torch when fragment pauses
        (activity as? MainActivity)?.torchController?.forceOff()

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
        // Step 2: release depth estimator resources
        if (this::depthEstimator.isInitialized) depthEstimator.close()
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

        // Step 2: Initialize depth estimator on the background thread (model load can be slow)
        depthEstimator = DepthEstimator(requireContext())

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

            // Step 2: init depth estimator on background thread (GPU delegate must be on same thread)
            depthEstimator.init()

            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)

        // Set up the large Start / Stop toggle button and UI state
        setupScanToggleButton()

        // Long-press the status badge to toggle the ms/frame debug overlay
        fragmentCameraBinding.layoutStatus.setOnLongClickListener {
            isDebugOverlayVisible = !isDebugOverlayVisible
            fragmentCameraBinding.tvDebugLatency.visibility =
                if (isDebugOverlayVisible) View.VISIBLE else View.GONE
            true
        }

        // Step 8: 5 rapid taps on the status badge reveal the test mode panel
        fragmentCameraBinding.layoutStatus.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - testTapLastMs > TEST_TAP_WINDOW_MS) testTapCount = 0
            testTapLastMs = now
            testTapCount++
            if (testTapCount >= TEST_TAP_REQUIRED) {
                testTapCount = 0
                showTestMode()
            }
        }

        // Wire test mode overlay buttons
        setupTestMode()
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

    // ── Step 8: Test Mode ─────────────────────────────────────────────────────

    private fun showTestMode() {
        requireView().findViewById<android.widget.FrameLayout>(R.id.layout_test_overlay)?.visibility = View.VISIBLE
        Log.i(TAG, "Test mode opened")
    }

    private fun hideTestMode() {
        requireView().findViewById<android.widget.FrameLayout>(R.id.layout_test_overlay)?.visibility = View.GONE
    }

    /**
     * Wires the 4 simulation buttons in the test overlay:
     *   1. DANGER      — speaks a danger alert and triggers the torch+vibration.
     *   2. BATTERY 10% — speaks the 10% battery alert.
     *   3. WALL AHEAD  — speaks the wall-ahead cue.
     *   4. LANGUAGE    — cycles EN → HI → KN → EN and announces the new language.
     *   5. CLOSE       — hides the overlay.
     */
    private fun setupTestMode() {
        // Use requireView().findViewById to access the included layout's children
        val btnDanger   = requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_danger)
        val btnBattery  = requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_battery)
        val btnWall     = requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_wall)
        val btnLanguage = requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_language)
        val btnClose    = requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_test_close)

        btnDanger?.setOnClickListener {
            val lang = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
            val msg  = Messages.dangerAlert(lang, "person", "straight ahead")
            fragmentCameraBinding.tvDetectionFeedback.text = msg
            audioHapticManager.speak(msg, isUrgent = true)
            audioHapticManager.vibrateHazard()
            (activity as? MainActivity)?.torchController?.triggerDangerTorch()
            Log.i(TAG, "TEST: DANGER simulated")
            hideTestMode()
        }

        btnBattery?.setOnClickListener {
            val lang = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
            val msg  = Messages.battery10(lang)
            fragmentCameraBinding.tvDetectionFeedback.text = msg
            audioHapticManager.speak(msg, isUrgent = false)
            audioHapticManager.vibrateHazard()
            Log.i(TAG, "TEST: BATTERY 10% simulated")
            hideTestMode()
        }

        btnWall?.setOnClickListener {
            val lang = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
            val msg  = Messages.wallAhead(lang)
            fragmentCameraBinding.tvDetectionFeedback.text = msg
            audioHapticManager.speak(msg, isUrgent = false)
            audioHapticManager.vibrateHazard()
            Log.i(TAG, "TEST: WALL AHEAD simulated")
            hideTestMode()
        }

        btnLanguage?.setOnClickListener {
            val mainActivity = activity as? MainActivity
            mainActivity?.cycleLanguage()
            val lang = mainActivity?.activeLanguage ?: Messages.Language.ENGLISH
            val announcement = when (lang) {
                Messages.Language.ENGLISH -> "Language: English"
                Messages.Language.HINDI   -> "Bhaasha: Hindi"
                Messages.Language.KANNADA -> "Bhaashe: Kannada"
            }
            audioHapticManager.speak(announcement, isUrgent = true)
            fragmentCameraBinding.tvDetectionFeedback.text = announcement
            Log.i(TAG, "TEST: Language cycled to $lang")
        }

        btnClose?.setOnClickListener { hideTestMode() }
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

        // Target 640×480 for analysis: low enough latency, high enough accuracy.
        // STRATEGY_KEEP_ONLY_LATEST drops stale frames so detection always runs on the newest image.
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetResolution(Size(
                    ObjectDetectorHelper.ANALYSIS_WIDTH,
                    ObjectDetectorHelper.ANALYSIS_HEIGHT
                ))
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { imageProxy ->
                        detectFrame(imageProxy)
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

            // Step 4: Inject Camera into TorchController so it can call cameraControl.enableTorch()
            (activity as? MainActivity)?.torchController?.attachCamera(camera)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Frame analyzer: forwards every frame to the detector when scanning is active.
     * Step 6: Also runs WallDetector on a downscaled copy of the bitmap.
     * STRATEGY_KEEP_ONLY_LATEST ensures we never pile up frames on the background thread.
     */
    private fun detectFrame(imageProxy: ImageProxy) {
        // Drop frames immediately if scanning loop is not active
        if (!isScanningActive) {
            imageProxy.close()
            return
        }

        // Step 6: Run wall detector on the raw frame BEFORE closing the proxy.
        // We need a Bitmap copy since imageProxy.planes[0].buffer is consumed by detectLivestreamFrame.
        val wallBitmap: Bitmap? = try {
            val bmp = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            buffer.rewind() // rewind so detectLivestreamFrame can also read it
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Wall bitmap copy failed: ${e.message}")
            null
        }

        // Wall analysis runs on background thread (heavy variance computation)
        if (wallBitmap != null) {
            val isWall = wallDetector.analyze(wallBitmap)
            lastWallVariance = wallDetector.lastVariance

            // Step 2: Run MiDaS depth estimator on the same frame copy (every 3rd frame internally)
            val depthAlert = if (this::depthEstimator.isInitialized) {
                depthEstimator.analyzeFrame(wallBitmap)
            } else null

            wallBitmap.recycle()

            if (isWall) {
                val lang    = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
                val wallMsg = Messages.wallAhead(lang)
                lastAlertReason = "LUMINANCE_WALL"
                Log.i(TAG, "WALL AHEAD (variance=${wallDetector.lastVariance}): $wallMsg")
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null && isScanningActive) {
                        fragmentCameraBinding.tvDetectionFeedback.text = wallMsg
                    }
                }
                audioHapticManager.speak(wallMsg, isUrgent = false)
                audioHapticManager.vibrateHazard()
            }

            // Step 2: Handle depth alert (only if it provides higher info than luminance wall)
            if (depthAlert != null && depthAlert.type != DepthEstimator.AlertType.NONE && !isWall) {
                val lang = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
                val grid = depthEstimator.lastGrid
                val guidanceStr = when (depthAlert.guidance) {
                    "left"  -> Messages.moveLeft(lang)
                    "right" -> Messages.moveRight(lang)
                    "stop"  -> Messages.stop(lang)
                    else    -> ""
                }
                val msg = when (depthAlert.type) {
                    DepthEstimator.AlertType.WALL    -> Messages.wallAhead(lang)
                    DepthEstimator.AlertType.DROP    -> Messages.dropAhead(lang)
                    DepthEstimator.AlertType.STEPS_UP -> Messages.stepsUp(lang)
                    else                              -> Messages.depthObstacle(lang, guidanceStr)
                }
                lastAlertReason = "DEPTH_${depthAlert.type}"
                Log.i(TAG, "DEPTH ALERT ${depthAlert.type}: $msg (guidance=${depthAlert.guidance})")
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null && isScanningActive) {
                        fragmentCameraBinding.tvDetectionFeedback.text = msg
                        if (isDebugOverlayVisible && grid != null) {
                            fragmentCameraBinding.tvDebugLatency.text =
                                "depth ${depthEstimator.lastDepthMs}ms | L=${f(grid.leftNear)} C=${f(grid.centerNear)} R=${f(grid.rightNear)}"
                        }
                    }
                }
                audioHapticManager.speak(msg, isUrgent = false)
                audioHapticManager.vibrateHazard()
            }
        }

        // Stairs-v4 Step 1: Run stairs-up edge detector on the same frame bitmap.
        // We need a fresh copy since wallBitmap was already recycled above.
        val stairsBitmap: Bitmap? = try {
            val bmp = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            val buffer2 = imageProxy.planes[0].buffer
            buffer2.rewind()
            bmp.copyPixelsFromBuffer(buffer2)
            buffer2.rewind()
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Stairs bitmap copy failed: ${e.message}")
            null
        }
        if (stairsBitmap != null) {
            val rotDeg = imageProxy.imageInfo.rotationDegrees
            val stairsUp = stairsDetector.analyzeFrame(stairsBitmap, rotDeg)
            stairsBitmap.recycle()

            // Update debug overlay with stairs analysis time
            if (isDebugOverlayVisible) {
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) {
                        val existingText = fragmentCameraBinding.tvDebugLatency.text?.toString() ?: ""
                        fragmentCameraBinding.tvDebugLatency.text =
                            "$existingText | stairs ${stairsDetector.lastAnalysisMs}ms pk=${stairsDetector.lastPeakCount} cv=${"%0.2f".format(stairsDetector.lastSpacingCV)}"
                    }
                }
            }

            if (stairsUp) {
                val lang = (activity as? MainActivity)?.activeLanguage ?: Messages.Language.ENGLISH
                val msg = Messages.stepsUp(lang)
                lastAlertReason = "STAIRS_EDGE_UP"
                Log.i(TAG, "STAIRS UP ALERT (edge heuristic): $msg")
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null && isScanningActive) {
                        fragmentCameraBinding.tvDetectionFeedback.text = msg
                    }
                }
                audioHapticManager.speak(msg, isUrgent = false)
                audioHapticManager.vibrateHazard()
            }
        }

        Log.v(TAG, "Forwarding frame to detector at ${SystemClock.uptimeMillis()}ms")
        objectDetectorHelper.detectLivestreamFrame(imageProxy)
    }

    /** Format float to 2 decimal places for debug overlay. */
    private fun f(v: Float) = "%.2f".format(v)

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

                // Update debug latency overlay (ms/frame = inference time reported by helper)
                if (isDebugOverlayVisible) {
                    fragmentCameraBinding.tvDebugLatency.text =
                        "${resultBundle.inferenceTime} ms/frame"
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

                // Step 3: Evaluate each detection for approaching-object danger.
                // Sort by direction priority (center ahead first) then area descending.
                val sortedDetections = detections.sortedWith(
                    compareBy<com.google.mediapipe.tasks.components.containers.Detection> { det ->
                        val b = det.boundingBox() ?: return@compareBy 999
                        val centerXNorm = ((b.left + b.right) / 2f) / frameWidth
                        if (centerXNorm in (1f / 3f)..(2f / 3f)) 0 else 1
                    }.thenByDescending { det ->
                        val b = det.boundingBox()
                        if (b != null) (b.width() * b.height()) else 0f
                    }
                )

                var alertDispatched = false
                val lang = (activity as? MainActivity)?.activeLanguage
                    ?: Messages.Language.ENGLISH

                for (detection in sortedDetections) {
                    val box = detection.boundingBox() ?: continue
                    val rawLabel = detection.categories().firstOrNull()?.categoryName() ?: "obstacle"
                    val score    = detection.categories().firstOrNull()?.score() ?: 0f

                    // Step 1: Per-class threshold filtering
                    val threshold = LabelMapper.thresholdFor(rawLabel)
                    if (score < threshold) continue

                    // Step 1: Large-box geometry check and label remapping
                    val boxWidthRatio  = (box.right - box.left)   / frameWidth
                    val boxHeightRatio = (box.bottom - box.top)   / frameHeight
                    val label = LabelMapper.map(rawLabel, boxWidthRatio, boxHeightRatio)

                    // Calculate direction and distance
                    val result = DistanceDirectionCalculator.calculate(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        screenWidth = frameWidth,
                        screenHeight = frameHeight,
                        label = label
                    )

                    if (!result.shouldAlert) continue

                    // Step 3: Compute approach metrics for this detection
                    val centerXNorm = ((box.left + box.right) / 2f) / frameWidth
                    val areaRatio = DistanceDirectionCalculator.calculateAreaRatio(
                        box.left, box.top, box.right, box.bottom, frameWidth, frameHeight
                    )
                    val isDanger = approachTracker.evaluate(
                        label        = label,
                        centerXNorm  = centerXNorm,
                        areaRatio    = areaRatio,
                        nowMs        = System.currentTimeMillis()
                    )

                    val currentTime = SystemClock.uptimeMillis()

                    if (isDanger) {
                        // DANGER fast path: 1.5s cooldown, QUEUE_FLUSH, strongest vibration
                        val dangerCooldownPassed =
                            (currentTime - lastDangerAlertTimestamp) >= 1_500L
                        if (dangerCooldownPassed) {
                            lastDangerAlertTimestamp = currentTime
                            val dangerMsg = Messages.dangerAlert(lang, label, result.direction)
                            Log.i(TAG, "DANGER ALERT: $dangerMsg")
                            fragmentCameraBinding.tvDetectionFeedback.text = dangerMsg
                            // QUEUE_FLUSH interrupts any lower-priority speech
                            audioHapticManager.speak(dangerMsg, isUrgent = true)
                            audioHapticManager.vibrateHazard()
                            (activity as? MainActivity)?.torchController?.triggerDangerTorch()
                        }
                        alertDispatched = true
                        break
                    }

                    // Step 1: Choose correct message based on mapped label
                    // Step 7: Custom hazard labels (pothole / stairs / door) get dedicated messages
                    val message = when (label) {
                        "wall"                          -> Messages.wallAhead(lang)
                        "obstacle"                      -> Messages.obstacleAhead(lang, result.direction)
                        "pothole"                       -> Messages.pothole(lang, result.direction)
                        "stairs", "staircase",
                        "step", "steps"                 -> Messages.stairs(lang, result.direction)
                        "door"                          -> Messages.door(lang, result.direction)
                        else                            -> Messages.alert(
                            lang          = lang,
                            label         = label,
                            direction     = result.direction,
                            distanceState = result.distanceState
                        )
                    }
                    Log.i(TAG, "Detection Alert: $message (raw=$rawLabel mapped=$label dir=${result.direction} score=$score)")
                    fragmentCameraBinding.tvDetectionFeedback.text = message

                    val isRepeatedAlert = message.equals(lastSpokenAlertMessage, ignoreCase = true)
                    val cooldownPassed = (currentTime - lastVoiceAlertTimestamp) >= VOICE_ALERT_COOLDOWN_MS

                    if (!isRepeatedAlert || cooldownPassed) {
                        lastVoiceAlertTimestamp = currentTime
                        lastSpokenAlertMessage = message

                        audioHapticManager.speak(message)

                        if (result.distanceState == DistanceDirectionCalculator.DISTANCE_VERY_CLOSE) {
                            audioHapticManager.vibrate(isClose = true)
                        } else if (result.distanceState == DistanceDirectionCalculator.DISTANCE_NEAR) {
                            audioHapticManager.vibrate(isClose = false)
                        }
                    } else {
                        Log.d(TAG, "Skipping repeated voice alert within cooldown: $message")
                    }

                    alertDispatched = true
                    break // One alert per frame — highest priority wins
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
