package com.google.mediapipe.examples.objectdetection

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DataCollectionActivity — stairs-v5 Phase 1
 *
 * Hidden screen for collecting labelled training images:
 *   - 3 labels: stairs_up, stairs_down, none
 *   - Captures the upright lower-centre 224×224 square crop
 *   - Saves JPEG to: <externalFilesDir>/dataset/<label>/<session>_<timestamp>.jpg
 *   - Auto-capture mode: 2 fps while button held
 *   - New random session ID each time the screen opens
 *
 * Access: 7 rapid taps on the status badge in CameraFragment (or via adb).
 *
 * adb pull command:
 *   adb pull /sdcard/Android/data/com.google.mediapipe.examples.objectdetection/files/dataset ./dataset
 */
class DataCollectionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "STAIRS_DATA"

        /** Target crop size matching MobileNetV3-Small input. */
        const val CROP_SIZE = 224

        /** Auto-capture interval (ms) = 500ms = 2 fps. */
        const val AUTO_CAPTURE_INTERVAL_MS = 500L
    }

    // Session
    private lateinit var sessionId: String

    // Counts per label
    private val counts = mutableMapOf(
        "stairs_up"   to 0,
        "stairs_down" to 0,
        "none"        to 0
    )

    // Camera
    private lateinit var backgroundExecutor: ExecutorService
    private var latestBitmap: Bitmap? = null
    private var latestRotationDeg: Int = 0
    @Volatile private var bitmapLock = Any()

    // Auto-capture
    private val handler = Handler(Looper.getMainLooper())
    private var autoCapturing = false
    private var autoCaptureLabel: String? = null
    private var autoEnabled = false

    // Views
    private lateinit var tvSessionId: TextView
    private lateinit var tvCounts: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_collection)

        sessionId = UUID.randomUUID().toString().take(8)

        tvSessionId = findViewById(R.id.tvSessionId)
        tvCounts    = findViewById(R.id.tvCounts)
        tvStatus    = findViewById(R.id.tvStatus)

        tvSessionId.text = "Session: $sessionId"
        updateCounts()

        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Auto-capture toggle
        val switchAuto = findViewById<SwitchMaterial>(R.id.switchAuto)
        switchAuto.setOnCheckedChangeListener { _, checked -> autoEnabled = checked }

        // Capture buttons with touch-and-hold for auto-capture
        setupCaptureButton(R.id.btnStairsUp,   "stairs_up")
        setupCaptureButton(R.id.btnStairsDown,  "stairs_down")
        setupCaptureButton(R.id.btnNone,         "none")

        // Close
        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }

        // Draw crop guide overlay
        drawCropGuide()

        // Start camera
        startCamera()

        Log.i(TAG, "DataCollectionActivity opened, session=$sessionId")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCaptureButton(buttonId: Int, label: String) {
        val btn = findViewById<MaterialButton>(buttonId)

        btn.setOnClickListener {
            // Single tap capture
            captureAndSave(label)
        }

        btn.setOnTouchListener { _, event ->
            if (!autoEnabled) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    autoCapturing = true
                    autoCaptureLabel = label
                    scheduleAutoCapture()
                    false // let click also fire
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    autoCapturing = false
                    autoCaptureLabel = null
                    handler.removeCallbacksAndMessages(null)
                    false
                }
                else -> false
            }
        }
    }

    private fun scheduleAutoCapture() {
        handler.postDelayed({
            if (autoCapturing && autoCaptureLabel != null) {
                captureAndSave(autoCaptureLabel!!)
                scheduleAutoCapture()
            }
        }, AUTO_CAPTURE_INTERVAL_MS)
    }

    private fun captureAndSave(label: String) {
        val bmp: Bitmap
        val rotDeg: Int
        synchronized(bitmapLock) {
            bmp = latestBitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                tvStatus.text = "No frame available yet"
                return
            }
            rotDeg = latestRotationDeg
        }

        backgroundExecutor.execute {
            try {
                // 1. Rotate to upright
                val upright = rotateToUpright(bmp, rotDeg)
                bmp.recycle()

                // 2. Crop lower-centre square
                val cropped = cropLowerCentreSquare(upright, CROP_SIZE)
                if (upright !== cropped) upright.recycle()

                // 3. Save JPEG
                val dir = File(getExternalFilesDir(null), "dataset/$label")
                dir.mkdirs()
                val filename = "${sessionId}_${System.currentTimeMillis()}.jpg"
                val file = File(dir, filename)
                FileOutputStream(file).use { fos ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                }
                cropped.recycle()

                counts[label] = (counts[label] ?: 0) + 1
                Log.d(TAG, "Saved $label/$filename (total=${counts[label]})")

                runOnUiThread {
                    updateCounts()
                    tvStatus.text = "Saved: $label/$filename"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed: ${e.message}", e)
                runOnUiThread { tvStatus.text = "Error: ${e.message}" }
            }
        }
    }

    private fun rotateToUpright(bmp: Bitmap, rotDeg: Int): Bitmap {
        if (rotDeg == 0) return bmp
        val matrix = Matrix()
        matrix.postRotate(rotDeg.toFloat())
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    /**
     * Crop the lower-centre square of the upright image.
     * The square side = min(width, height) / 2 to get a focused region,
     * then resize to [targetSize] × [targetSize].
     */
    private fun cropLowerCentreSquare(upright: Bitmap, targetSize: Int): Bitmap {
        val w = upright.width
        val h = upright.height

        // Square side = half of the smaller dimension (focused lower-centre)
        val side = minOf(w, h) / 2
        val left = (w - side) / 2
        val top  = h - side  // bottom-aligned

        val cropped = Bitmap.createBitmap(upright, left, top, side, side)
        return if (side == targetSize) cropped
        else Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            .also { if (it !== cropped) cropped.recycle() }
    }

    private fun updateCounts() {
        tvCounts.text = "stairs_up: ${counts["stairs_up"]} | stairs_down: ${counts["stairs_down"]} | none: ${counts["none"]}"
    }

    private fun drawCropGuide() {
        val guide = findViewById<View>(R.id.cropGuide)
        val border = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(6, Color.argb(200, 76, 175, 80))  // green border
        }
        guide.background = border
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(
                        findViewById<PreviewView>(R.id.viewFinder).surfaceProvider
                    )
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                        copyFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "Camera bound for data collection")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun copyFrame(imageProxy: ImageProxy) {
        try {
            val bmp = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)

            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = bmp
                latestRotationDeg = imageProxy.imageInfo.rotationDegrees
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame copy failed: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdown()
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        super.onDestroy()
    }
}
