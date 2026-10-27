package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
 * StairsClassifier — stairs-v5 Phase 3
 *
 * On-device TFLite classifier for stairs_up / stairs_down / none.
 *
 * ── Model setup ──────────────────────────────────────────────────────────────
 *   1. Train with training/train_stairs.py (Colab).
 *   2. Copy stairs_model.tflite + stairs_labels.txt → app/src/main/assets/
 *   3. The app silently skips classification if the model file is missing.
 *
 * ── What it does ──────────────────────────────────────────────────────────────
 *   - Runs on the background thread every [RUN_EVERY_N_FRAMES] frames.
 *   - Takes the SAME lower-centre 224×224 crop as the training pipeline:
 *       rotate to upright → crop bottom-centre square → resize to 224×224.
 *   - Normalises to [0, 1] float32 (matching training preprocessing).
 *   - GPU delegate with CPU fallback; 2 CPU threads when GPU not available.
 *   - All reusable buffers pre-allocated; zero per-frame heap allocation.
 *
 * ── Tuning constants ──────────────────────────────────────────────────────────
 */
class StairsClassifier(private val context: Context) {

    companion object {
        private const val TAG = "STAIRS"

        const val MODEL_FILENAME  = "stairs_model.tflite"
        const val LABELS_FILENAME = "stairs_labels.txt"

        /** Must match training IMG_SIZE. */
        const val INPUT_SIZE = 224

        /** Run inference every Nth analysis frame (~5 fps at 15 fps analysis). */
        const val RUN_EVERY_N_FRAMES = 3

        /** Label strings in the same order as train_stairs.py LABELS (alphabetical). */
        val EXPECTED_LABELS = listOf("none", "stairs_down", "stairs_up")
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    var isAvailable = false
        private set

    private var frameCounter = 0

    /** Labels loaded from stairs_labels.txt (must match model output order). */
    var labels: List<String> = emptyList()
        private set

    // Reusable buffers — allocated once
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)   // 3 channels × float32
            .order(ByteOrder.nativeOrder())
    }
    private val outputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(EXPECTED_LABELS.size * 4)           // N classes × float32
            .order(ByteOrder.nativeOrder())
    }
    private val scaledBitmap: Bitmap by lazy {
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    // ── Debug fields (read by CameraFragment for debug overlay) ───────────────

    /** Probabilities from the last inference, indexed by [labels]. */
    @Volatile var lastProbs: FloatArray = FloatArray(EXPECTED_LABELS.size)
        private set
    /** Wall-clock ms for the last inference. */
    @Volatile var lastInferenceMs: Long = 0L
        private set
    /** Index of the predicted class (argmax of lastProbs), or -1 if not run. */
    @Volatile var lastPredIndex: Int = -1
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load model and labels. Call once on a background thread
     * (same thread as [classify] — GPU delegate must stay on same thread).
     */
    fun init() {
        if (!assetExists(MODEL_FILENAME)) {
            Log.w(TAG, "Classifier: $MODEL_FILENAME not found in assets — skipping")
            isAvailable = false
            return
        }
        try {
            val opts = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                opts.addDelegate(gpuDelegate!!)
                Log.i(TAG, "Classifier: GPU delegate active")
            } else {
                opts.setNumThreads(2)
                Log.i(TAG, "Classifier: CPU fallback (2 threads)")
            }
            interpreter = Interpreter(loadModelBuffer(MODEL_FILENAME), opts)
            labels = loadLabels()
            isAvailable = true
            Log.i(TAG, "StairsClassifier ready — labels: $labels")
        } catch (e: Exception) {
            Log.e(TAG, "StairsClassifier init failed: ${e.message} — skipping", e)
            isAvailable = false
        }
    }

    /** Release resources. Call from onDestroyView. */
    fun close() {
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
        isAvailable = false
        Log.i(TAG, "StairsClassifier closed")
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Classify [frame] (raw sensor-orientation RGBA_8888 bitmap).
     * Returns a [Result] or null if this frame is skipped.
     * MUST be called on the same background thread as [init].
     *
     * @param frame       Raw RGBA_8888 Bitmap from ImageProxy.
     * @param rotationDeg Degrees to rotate CW to reach upright portrait view.
     */
    fun classify(frame: Bitmap, rotationDeg: Int): Result? {
        frameCounter++
        if (!isAvailable || interpreter == null) return null
        if (frameCounter % RUN_EVERY_N_FRAMES != 0) return null

        val t0 = SystemClock.uptimeMillis()

        // 1. Rotate to upright
        val upright = rotateToUpright(frame, rotationDeg)

        // 2. Crop lower-centre square and scale to INPUT_SIZE
        val cropped = cropAndScale(upright, INPUT_SIZE)
        if (upright !== frame) upright.recycle()

        // 3. Copy cropped bitmap into the pre-allocated scaledBitmap
        val canvas = android.graphics.Canvas(scaledBitmap)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        if (cropped !== scaledBitmap) cropped.recycle()

        // 4. Fill input buffer: RGB float32, normalised [0, 1]
        inputBuffer.rewind()
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaledBitmap.getPixel(x, y)
                inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)  // R
                inputBuffer.putFloat(((px shr 8)  and 0xFF) / 255f)  // G
                inputBuffer.putFloat(( px          and 0xFF) / 255f)  // B
            }
        }
        inputBuffer.rewind()

        // 5. Run inference
        outputBuffer.rewind()
        interpreter!!.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        lastInferenceMs = SystemClock.uptimeMillis() - t0

        // 6. Read probabilities
        val probs = FloatArray(labels.size)
        for (i in probs.indices) probs[i] = outputBuffer.float
        lastProbs = probs
        lastPredIndex = probs.indices.maxByOrNull { probs[it] } ?: -1

        val predLabel = if (lastPredIndex >= 0) labels[lastPredIndex] else "?"
        Log.d(TAG, "Classifier: pred=$predLabel probs=${probs.map { "%.3f".format(it) }} [${lastInferenceMs}ms]")

        return Result(
            predIndex  = lastPredIndex,
            predLabel  = predLabel,
            probs      = probs,
            inferenceMs = lastInferenceMs
        )
    }

    // ── Result ────────────────────────────────────────────────────────────────

    data class Result(
        val predIndex:   Int,
        val predLabel:   String,
        val probs:       FloatArray,
        val inferenceMs: Long
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun rotateToUpright(bmp: Bitmap, rotDeg: Int): Bitmap {
        if (rotDeg == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotDeg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, false)
    }

    /**
     * Crop the lower-centre square (side = min(w,h)/2) then scale to [targetSize].
     * Matches the crop logic in DataCollectionActivity and train_stairs.py.
     */
    private fun cropAndScale(upright: Bitmap, targetSize: Int): Bitmap {
        val w = upright.width
        val h = upright.height
        val side = minOf(w, h) / 2
        val left = (w - side) / 2
        val top  = h - side
        val cropped = Bitmap.createBitmap(upright, left, top, side, side)
        return if (side == targetSize) cropped
        else Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            .also { if (it !== cropped) cropped.recycle() }
    }

    private fun assetExists(filename: String) = try {
        context.assets.open(filename).use { true }
    } catch (e: Exception) { false }

    private fun loadModelBuffer(filename: String): MappedByteBuffer {
        val afd = context.assets.openFd(filename)
        return FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    private fun loadLabels(): List<String> {
        if (!assetExists(LABELS_FILENAME)) {
            Log.w(TAG, "stairs_labels.txt not found — using default order")
            return EXPECTED_LABELS
        }
        return context.assets.open(LABELS_FILENAME)
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .also { Log.i(TAG, "Loaded labels: $it") }
    }
}
