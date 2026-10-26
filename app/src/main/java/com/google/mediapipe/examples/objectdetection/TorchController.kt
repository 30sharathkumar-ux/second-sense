package com.google.mediapipe.examples.objectdetection

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera

/**
 * TorchController — Step 4: Torch auto-off after 5 seconds
 *
 * Uses CameraX's [Camera.cameraControl] to toggle the torch so it works
 * while CameraX has exclusive camera ownership (CameraManager.setTorchMode
 * would conflict and crash on many devices).
 *
 * Behaviour:
 *  - [triggerDangerTorch] turns the torch ON and schedules it OFF after
 *    [TORCH_AUTO_OFF_MS] milliseconds.
 *  - If another DANGER fires while the torch is still on, the 5-second
 *    timer is restarted (not stacked).
 *  - [forceOff] immediately extinguishes the torch and cancels any pending
 *    timer. Call from onPause / onDestroy.
 *  - Handles phones without a flash unit gracefully (no crash).
 *
 * ── Tuning constant ──────────────────────────────────────────────────────────
 */
class TorchController {

    companion object {
        private const val TAG = "TorchController"

        /** How long (ms) the torch stays on after a DANGER alert before auto-off. */
        const val TORCH_AUTO_OFF_MS = 5_000L
    }

    // CameraX Camera reference — injected when camera is bound.
    private var camera: Camera? = null

    private val handler = Handler(Looper.getMainLooper())

    // Runnable that turns the torch off — stored so we can cancel & restart it.
    private val torchOffRunnable = Runnable {
        Log.d(TAG, "Auto-off: turning torch OFF after ${TORCH_AUTO_OFF_MS}ms")
        setTorch(false)
    }

    /** Inject the CameraX [Camera] instance after bindToLifecycle. */
    fun attachCamera(cam: Camera?) {
        camera = cam
    }

    /**
     * Turn the torch ON and (re)start the 5-second auto-off timer.
     * Safe to call repeatedly while the torch is already on.
     */
    fun triggerDangerTorch() {
        val hasTorch = camera?.cameraInfo?.hasFlashUnit() ?: false
        if (!hasTorch) {
            Log.d(TAG, "Device has no flash unit — torch skipped")
            return
        }

        // Cancel any previously scheduled off-runnable before restarting the timer
        handler.removeCallbacks(torchOffRunnable)
        setTorch(true)
        handler.postDelayed(torchOffRunnable, TORCH_AUTO_OFF_MS)
        Log.i(TAG, "Torch ON — will auto-off in ${TORCH_AUTO_OFF_MS}ms")
    }

    /**
     * Immediately turn the torch off and cancel the auto-off timer.
     * Call from onPause / onDestroy to avoid leaving the torch on.
     */
    fun forceOff() {
        handler.removeCallbacks(torchOffRunnable)
        setTorch(false)
        Log.d(TAG, "TorchController.forceOff() called")
    }

    private fun setTorch(on: Boolean) {
        val cam = camera ?: return
        try {
            cam.cameraControl.enableTorch(on)
        } catch (e: Exception) {
            // Some devices throw if no flash unit exists — safe to ignore here
            Log.w(TAG, "enableTorch($on) failed: ${e.message}")
        }
    }
}
