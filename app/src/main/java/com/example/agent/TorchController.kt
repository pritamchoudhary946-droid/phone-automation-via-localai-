package com.example.agent

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Result of a single "turn the flashlight on/off" request. */
data class TorchResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The torch/flashlight boundary for [AgentAction.SetTorch]. This is the "turn on the
 * flashlight" / "switch the torch off" primitive: the torch is a camera-hardware
 * setting, not an accessibility-tree control, so no screen read or UI tap can toggle
 * it reliably on an arbitrary screen (and it may be off-screen in quick settings).
 * Instead this drives the platform `CameraManager.setTorchMode` on the device's
 * back-facing camera with a flash unit.
 *
 * The state is read back after the call (`CameraManager.TorchCallback`), so a toggle
 * that the hardware ignored is reported as an honest failure
 * ([TorchResult.method] `TORCH_UNCHANGED`), never a fake success - the same
 * read-back honesty used by [AudioController]/[MediaController]/[RingerModeController].
 * This is what makes [AgentAction.SetTorch] self-evidencing despite carrying a default
 * `NONE` verification.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller (or a device with no flash) returns an honest "unavailable"
 * result rather than a silent no-op.
 */
interface TorchController {
    /**
     * Turns the torch on when [on] is true, off otherwise. Implementations must report
     * honestly: a device with no flash, a camera the app may not use, or a toggle the
     * hardware ignored is a failure, never a claimed success. A successful result means
     * the torch state was confirmed to have changed to [on].
     */
    fun setTorch(on: Boolean): TorchResult
}

/**
 * Real implementation backed by [CameraManager]. It resolves the back-facing camera
 * with a flash unit once and drives [CameraManager.setTorchMode]. Because the torch is
 * a hardware setting no accessibility screen reflects, the result is honest only if the
 * state is read back; this implementation tracks the last known state via
 * [CameraManager.registerTorchCallback] and reports [TorchResult.method] `TORCH_ON` /
 * `TORCH_OFF` on a confirmed change, `TORCH_UNCHANGED` when the request did not move the
 * hardware, and `TORCH_UNAVAILABLE` / `TORCH_PERMISSION` / `TORCH_ERROR` otherwise.
 */
class AndroidTorchController(context: Context) : TorchController {

    private val appContext = context.applicationContext
    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val lastKnownOn = AtomicBoolean(false)

    private var torchCallback: CameraManager.TorchCallback? = null

    private fun ensureCallbackRegistered() {
        val manager = cameraManager ?: return
        if (torchCallback != null) return
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == resolveCameraId()) lastKnownOn.set(enabled)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.registerTorchCallback(appContext.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerTorchCallback(callback, null)
            }
            torchCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Torch callback registration failed: ${e.message}")
        }
    }

    override fun setTorch(on: Boolean): TorchResult {
        val manager = cameraManager
            ?: return TorchResult(false, "TORCH_UNAVAILABLE", "This device has no camera service")
        val cameraId = resolveCameraId()
            ?: return TorchResult(false, "TORCH_UNAVAILABLE", "This device has no camera with a flash")
        ensureCallbackRegistered()
        return try {
            manager.setTorchMode(cameraId, on)
            // The hardware may ignore the request; the callback updates lastKnownOn. Give
            // it a moment to land, then confirm the state actually moved.
            val deadline = System.currentTimeMillis() + CONFIRM_BUDGET_MS
            while (System.currentTimeMillis() < deadline && lastKnownOn.get() != on) {
                Thread.sleep(CONFIRM_POLL_MS)
            }
            if (lastKnownOn.get() == on) {
                TorchResult(true, if (on) "TORCH_ON" else "TORCH_OFF", "Flashlight is ${if (on) "on" else "off"}")
            } else {
                TorchResult(
                    false,
                    "TORCH_UNCHANGED",
                    "The flashlight did not turn ${if (on) "on" else "off"}"
                )
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Torch toggle failed: ${e.message}")
            TorchResult(false, "TORCH_ERROR", "The flashlight could not be changed: ${e.localizedMessage}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Torch toggle denied: ${e.message}")
            TorchResult(false, "TORCH_PERMISSION", "Changing the flashlight is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Torch toggle failed: ${e.message}")
            TorchResult(false, "TORCH_ERROR", "The flashlight could not be changed: ${e.localizedMessage}")
        }
    }

    /**
     * Returns the id of the back-facing camera that has a flash unit, or null when the
     * device has none. Prefers a rear camera with `FLASH_INFO_AVAILABLE`; falls back to
     * any camera that reports a flash.
     */
    private fun resolveCameraId(): String? {
        val manager = cameraManager ?: return null
        return try {
            val withFlash = manager.cameraIdList.filter { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (withFlash.isEmpty()) return null
            withFlash.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.first()
        } catch (e: Exception) {
            Log.w(TAG, "Camera enumeration failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "AndroidTorchController"

        /** How long to wait for the torch callback to confirm the requested state. */
        private const val CONFIRM_BUDGET_MS = 400L

        /** Poll interval while waiting for the torch state to confirm. */
        private const val CONFIRM_POLL_MS = 40L
    }
}
