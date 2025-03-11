package com.arcamerastreamer.models

import android.os.Parcelable
import android.util.Size
import kotlinx.parcelize.Parcelize

/**
 * Data class to hold camera configuration settings
 */
@Parcelize
data class CameraSettings(
    // Camera resolution
    var resolution: Size = Size(1280, 720),

    // Frame rate
    var fps: Int = 30,

    // Camera ID (front or back)
    var lensFacing: Int = CAMERA_FACING_BACK,

    // Flash mode
    var flashMode: Int = FLASH_MODE_OFF,

    // Auto-focus enabled
    var autoFocus: Boolean = true,

    // Exposure compensation value (-10 to 10)
    var exposureCompensation: Int = 0,

    // Quality setting for JPEG compression (0-100)
    var jpegQuality: Int = 85,

    // Enable image stabilization
    var stabilization: Boolean = true
) : Parcelable {
    companion object {
        // Standard resolution presets
        val RESOLUTION_LOW = Size(640, 480)
        val RESOLUTION_MEDIUM = Size(1280, 720)
        val RESOLUTION_HIGH = Size(1920, 1080)

        // Camera facing constants
        const val CAMERA_FACING_BACK = 0
        const val CAMERA_FACING_FRONT = 1

        // Flash modes
        const val FLASH_MODE_OFF = 0
        const val FLASH_MODE_ON = 1
        const val FLASH_MODE_AUTO = 2
    }
}