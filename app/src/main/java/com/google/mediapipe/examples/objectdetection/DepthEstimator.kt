package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * DepthEstimator — Step 2: Class-independent obstacle detection using MiDaS Small
 *
 * ── How to get the model ────────────────────────────────────────────────────
 *   1. Go to: https://www.kaggle.com/models/intel/midas/tfLite/v21-lite-small-tflite/1
 *      OR:     https://tfhub.dev/intel/lite-model/midas/v21_small/1/lite/1
 *   2. Download the .tflite file (≈ 12 MB, input 256×256 float32, output 256×256 float32).
 *   3. Rename it "midas_small.tflite".
 *   4. Copy it to:  app/src/main/assets/midas_small.tflite
 *   The app silently skips depth analysis if this file is missing.
 *
 * ── What it does ────────────────────────────────────────────────────────────
 *   Runs on a background thread every 3rd camera frame (~5-8 depth runs/sec).
 *   Divides the lower 2/3 of the depth map into a 3×2 grid:
 *
 *       [ LEFT_FAR  | CENTER_FAR  | RIGHT_FAR  ]
 *       [ LEFT_NEAR | CENTER_NEAR | RIGHT_NEAR ]
 *
 *   MiDaS output is INVERSE depth (larger = closer).
 *   Normalises each cell value by the frame's max so thresholds are scene-relative.
 *
 * ── Tuning constants ─────────────────────────────────────────────────────────
 */
class DepthEstimator(private val context: Context) {

    companion object {
        private const val TAG = "DepthEstimator"

        /** Asset filename — see comment above for download instructions. */
        const val MODEL_FILENAME = "midas_small.tflite"

        /** MiDaS model input/output resolution (square). */
        const val MODEL_SIZE = 256

        /**
         * Run depth inference only every Nth camera frame to reduce CPU/GPU load.
         * At ~15-20 fps analysis, N=3 gives ~5-7 depth runs/sec.
         */
        const val DEPTH_EVERY_N_FRAMES = 3

        /**
         * Normalised closeness threshold for CENTER/NEAR cell to trigger an obstacle alert.
         * Range 0..1 (relative to frame max). Lower = more sensitive.
         */
        const val CENTER_NEAR_OBSTACLE_THRESHOLD = 0.75f

        /**
         * If all three column averages in the NEAR row exceed this, classify as "wall ahead"
         * rather than generic obstacle.
         */
        const val ALL_COLS_WALL_THRESHOLD = 0.70f

        /**
         * Consecutive depth frames CENTER_NEAR must exceed threshold before alert fires.
         * Prevents single noisy frames from firing alerts.
         */
        const val CONFIRM_FRAMES = 3

        /** Cooldown between successive depth-triggered alerts (ms). */
        const val ALERT_COOLDOWN_MS = 4_000L

        /**
         * Minimum difference between CENTER_NEAR and a side column for guidance
         * (e.g. "move left") to fire. Below this, both sides are considered equivalent.
         */
        const val GUIDANCE_MARGIN = 0.12f

        /**
         * Drop-ahead heuristic (best effort): if bottom-centre row is suddenly MUCH FARTHER
         * than the row above it, this is a potential drop/step-down.
         * Difference threshold (normalised closeness, higher = more sensitive).
         */
        const val DROP_DETECTION_DELTA = 0.30f

        /**
         * Steps-up heuristic (best effort): if bottom-centre row becomes suddenly
         * MUCH CLOSER, this is a potential step-up.
         */
        const val STEPS_UP_DELTA = 0.30f
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isAvailable = false

    private var frameCounter = 0
    private var confirmCount = 0
    private var lastAlertTimeMs = 0L
    var lastDepthMs: Long = 0L          // exposed for debug overlay
        private set

    // Reusable buffers — allocated once
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }
    private val outputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4)
            .order(ByteOrder.nativeOrder())
    }
    private val scaledBitmap: Bitmap by lazy {
        Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    }

    // Grid results — readable by CameraFragment for debug overlay
    data class GridResult(
        val leftNear:   Float,
        val centerNear: Float,
        val rightNear:  Float,
        val leftFar:    Float,
        val centerFar:  Float,
        val rightFar:   Float
    )
    var lastGrid: GridResult? = null
        private set

    /** Stairs-v4: expose last depth map for StairsDetector depth analysis. */
    var lastDepthMap: FloatArray? = null
        private set
    /** Stairs-v4: max value in the last depth map (for normalisation). */
    var lastDepthMaxVal: Float = 1f
        private set

    enum class AlertType { NONE, OBSTACLE, WALL, DROP, STEPS_UP }
    data class DepthAlert(val type: AlertType, val guidance: String)

    // Previous frame's center-near value for drop/steps detection
    private var prevCenterNear = 0f
    private var prevCenterFar  = 0f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Load the model. Call once (e.g., in Fragment's onViewCreated background thread). */
    fun init() {
        if (!assetExists()) {
            Log.w(TAG, "MiDaS model not found at assets/$MODEL_FILENAME — depth analysis disabled.")
            isAvailable = false
            return
        }
        try {
            val opts = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                opts.addDelegate(gpuDelegate!!)
                Log.i(TAG, "Depth: GPU delegate active")
            } else {
                opts.setNumThreads(2)
                Log.i(TAG, "Depth: CPU-only (GPU delegate not supported)")
            }
            interpreter = Interpreter(loadModelBuffer(), opts)
            isAvailable = true
            Log.i(TAG, "DepthEstimator initialised (MiDaS Small ${MODEL_SIZE}x${MODEL_SIZE})")
        } catch (e: Exception) {
            Log.e(TAG, "DepthEstimator init failed: ${e.message} — depth analysis disabled.")
            isAvailable = false
        }
    }

    /** Release resources. Call from onDestroyView. */
    fun close() {
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
        isAvailable = false
        Log.i(TAG, "DepthEstimator closed")
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Analyse [frame] if this is the Nth camera frame.
     * Returns a [DepthAlert] (type=NONE if nothing to report) or null if skipped.
     * MUST be called from a background thread.
     */
    fun analyzeFrame(frame: Bitmap): DepthAlert? {
        frameCounter++
        if (!isAvailable || interpreter == null) return null
        if (frameCounter % DEPTH_EVERY_N_FRAMES != 0) return null

        val t0 = SystemClock.uptimeMillis()

        // 1. Scale frame into reusable bitmap
        val canvas = android.graphics.Canvas(scaledBitmap)
        canvas.drawBitmap(
            Bitmap.createScaledBitmap(frame, MODEL_SIZE, MODEL_SIZE, false),
            0f, 0f, null
        )

        // 2. Fill input buffer (RGB float32, normalised 0..1)
        inputBuffer.rewind()
        for (y in 0 until MODEL_SIZE) {
            for (x in 0 until MODEL_SIZE) {
                val px = scaledBitmap.getPixel(x, y)
                inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f) // R
                inputBuffer.putFloat(((px shr 8)  and 0xFF) / 255f) // G
                inputBuffer.putFloat(( px          and 0xFF) / 255f) // B
            }
        }
        inputBuffer.rewind()

        // 3. Run inference
        outputBuffer.rewind()
        interpreter!!.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        lastDepthMs = SystemClock.uptimeMillis() - t0
        Log.v(TAG, "Depth inference: ${lastDepthMs}ms")

        // 4. Read output floats into array (inverse depth: larger = closer)
        val depthMap = FloatArray(MODEL_SIZE * MODEL_SIZE)
        for (i in depthMap.indices) depthMap[i] = outputBuffer.float

        // Stairs-v4: store for StairsDetector
        lastDepthMap = depthMap
        lastDepthMaxVal = depthMap.maxOrNull()?.takeIf { it > 0f } ?: 1f

        // 5. Compute grid averages (lower 2/3 of depth map, 3 columns × 2 rows)
        val alert = computeGridAndAlert(depthMap)
        return alert
    }

    // ── Grid computation ──────────────────────────────────────────────────────

    private fun computeGridAndAlert(depthMap: FloatArray): DepthAlert {
        val maxVal = depthMap.maxOrNull()?.takeIf { it > 0f } ?: 1f

        // Lower 2/3 of image = rows from MODEL_SIZE/3 to MODEL_SIZE
        val rowStart = MODEL_SIZE / 3
        val rowMid   = rowStart + (MODEL_SIZE - rowStart) / 2  // split into NEAR and FAR

        val colLeft  = 0 until MODEL_SIZE / 3
        val colCenter = MODEL_SIZE / 3 until 2 * MODEL_SIZE / 3
        val colRight = 2 * MODEL_SIZE / 3 until MODEL_SIZE

        fun avg(rowRange: IntRange, colRange: IntRange): Float {
            var sum = 0f; var count = 0
            for (r in rowRange) for (c in colRange) {
                sum += depthMap[r * MODEL_SIZE + c] / maxVal
                count++
            }
            return if (count > 0) sum / count else 0f
        }

        val nearRows = rowMid until MODEL_SIZE
        val farRows  = rowStart until rowMid

        val lNear = avg(nearRows, colLeft)
        val cNear = avg(nearRows, colCenter)
        val rNear = avg(nearRows, colRight)
        val lFar  = avg(farRows,  colLeft)
        val cFar  = avg(farRows,  colCenter)
        val rFar  = avg(farRows,  colRight)

        lastGrid = GridResult(lNear, cNear, rNear, lFar, cFar, rFar)

        // ── Heuristic: Drop (best effort) ─────────────────────────────────────
        // If center-near was close and now it's far → sudden drop below
        val dropDelta = prevCenterNear - cNear
        val isDropAhead = prevCenterNear > CENTER_NEAR_OBSTACLE_THRESHOLD &&
                          dropDelta > DROP_DETECTION_DELTA
        // ── Heuristic: Steps-up (best effort) ────────────────────────────────
        val stepUpDelta = cNear - prevCenterNear
        val isStepUp = stepUpDelta > STEPS_UP_DELTA && prevCenterNear < 0.4f

        prevCenterNear = cNear
        prevCenterFar  = cFar

        val nowMs = System.currentTimeMillis()
        val cooldownOk = (nowMs - lastAlertTimeMs) >= ALERT_COOLDOWN_MS

        if (isDropAhead && cooldownOk) {
            lastAlertTimeMs = nowMs; confirmCount = 0
            return DepthAlert(AlertType.DROP, "")
        }
        if (isStepUp && cooldownOk) {
            lastAlertTimeMs = nowMs; confirmCount = 0
            return DepthAlert(AlertType.STEPS_UP, "")
        }

        // ── Main obstacle gate (CENTER/NEAR cell) ─────────────────────────────
        if (cNear >= CENTER_NEAR_OBSTACLE_THRESHOLD) {
            confirmCount++
        } else {
            confirmCount = 0
            return DepthAlert(AlertType.NONE, "")
        }

        if (confirmCount < CONFIRM_FRAMES) return DepthAlert(AlertType.NONE, "")
        if (!cooldownOk) return DepthAlert(AlertType.NONE, "")

        confirmCount = 0
        lastAlertTimeMs = nowMs

        // ── Wall vs obstacle ──────────────────────────────────────────────────
        val allBlocked = lNear >= ALL_COLS_WALL_THRESHOLD &&
                         cNear >= ALL_COLS_WALL_THRESHOLD &&
                         rNear >= ALL_COLS_WALL_THRESHOLD
        if (allBlocked) return DepthAlert(AlertType.WALL, "")

        // ── Directional guidance ──────────────────────────────────────────────
        val guidance = when {
            lNear - rNear > GUIDANCE_MARGIN  -> "right"   // left blocked → go right
            rNear - lNear > GUIDANCE_MARGIN  -> "left"    // right blocked → go left
            else                              -> "stop"
        }
        return DepthAlert(AlertType.OBSTACLE, guidance)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assetExists(): Boolean = try {
        context.assets.open(MODEL_FILENAME).use { true }
    } catch (e: Exception) { false }

    private fun loadModelBuffer(): MappedByteBuffer {
        val afd = context.assets.openFd(MODEL_FILENAME)
        val fis = FileInputStream(afd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }
}
