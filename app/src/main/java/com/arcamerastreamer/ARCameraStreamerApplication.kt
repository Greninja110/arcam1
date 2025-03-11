package com.arcamerastreamer

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException

class ARCameraStreamerApplication : Application(), CameraXConfig.Provider {

    companion object {
        private const val TAG = "ARCameraStreamerApp"

        // Flag for ARCore availability
        var isARCoreAvailable = false
            private set

        // Flag to indicate if AR is supported on this device
        var isARSupported = false
            private set

        // Reference to Session for cross-component use
        var arSession: Session? = null

        // Public method to update ARCore availability
        fun setARCoreAvailable(available: Boolean) {
            isARCoreAvailable = available
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Check ARCore availability
        checkARCoreAvailability()

        Log.d(TAG, "Application initialized, ARCore available: $isARCoreAvailable")
    }

    /**
     * Check if ARCore is installed and available on this device
     */
    private fun checkARCoreAvailability() {
        try {
            // Check if Google Play Services for AR (ARCore) is installed and up to date
            when (ArCoreApk.getInstance().checkAvailability(this)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Log.i(TAG, "ARCore is installed and supported")
                    isARCoreAvailable = true
                    isARSupported = true
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    Log.i(TAG, "ARCore is supported but not installed or too old")
                    isARSupported = true

                    // Request ARCore installation or update if needed (handled in MainActivity)
                    // Availability will be rechecked after installation
                    isARCoreAvailable = false
                }
                else -> {
                    Log.i(TAG, "ARCore is not supported on this device")
                    isARCoreAvailable = false
                    isARSupported = false
                }
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore not available", e)
            isARCoreAvailable = false
            isARSupported = false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking AR availability", e)
            isARCoreAvailable = false
            isARSupported = false
        }
    }

    /**
     * Provider for CameraX configuration
     */
    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }
}