package com.arcamerastreamer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Utility class for AR-related functions
 */
object ARUtils {

    private const val TAG = "ARUtils"

    /**
     * Check if ARCore is supported on the device
     */
    fun isARCoreSupported(context: Context): Boolean {
        return com.google.ar.core.ArCoreApk.getInstance()
            .checkAvailability(context)
            .isSupported
    }

    /**
     * Request ARCore installation if needed
     */
    fun requestARCoreInstall(
        context: Context,
        userRequestedInstall: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val availability = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)

        if (availability.isTransient) {
            // Re-query availability at a later time
            Log.i(TAG, "ARCore availability is transient, checking again later")
            callback(false)
            return
        }

        if (availability.isSupported) {
            val installStatus = com.google.ar.core.ArCoreApk.getInstance()
                .requestInstall(context as android.app.Activity, userRequestedInstall)

            when (installStatus) {
                com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> {
                    // ARCore is already installed
                    Log.i(TAG, "ARCore is already installed")
                    callback(true)
                }
                com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    // ARCore installation was requested
                    Log.i(TAG, "ARCore installation requested")
                    callback(false) // Will need to check again after install completes
                }
                else -> {
                    Log.e(TAG, "Unknown ARCore install status: $installStatus")
                    callback(false)
                }
            }
        } else {
            // ARCore is not supported on this device
            Log.w(TAG, "ARCore is not supported on this device")
            callback(false)
        }
    }

    /**
     * Get depth data from the current AR frame
     * Returns a byte array of depth data or null if not available
     */
    fun getDepthData(frame: Frame): ByteArray? {
        return try {
            val depthImage = frame.acquireDepthImage() ?: return null

            val width = depthImage.width
            val height = depthImage.height
            val pixels = width * height

            val planes = depthImage.planes
            val buffer = planes[0].buffer

            // Convert depth data to byte array
            val depthData = ByteArray(buffer.remaining())
            buffer.get(depthData)

            depthImage.close()
            depthData

        } catch (e: NotYetAvailableException) {
            // This is normal when depth is not yet available
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting depth data: ${e.message}", e)
            null
        }
    }

    /**
     * Find all horizontal planes in the current AR frame
     */
    fun findHorizontalPlanes(session: Session?): List<Plane> {
        if (session == null) return emptyList()

        return try {
            session.getAllTrackables(Plane::class.java)
                .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding horizontal planes: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Create an anchor at the specified position on a plane
     */
    fun createAnchor(plane: Plane, x: Float, y: Float, z: Float): Anchor? {
        return try {
            plane.createAnchor(plane.centerPose.compose(com.google.ar.core.Pose.makeTranslation(x, y, z)))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating anchor: ${e.message}", e)
            null
        }
    }

    /**
     * Compress and encode a bitmap for streaming
     */
    fun compressBitmapForStreaming(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Convert rotation matrix to Euler angles
     */
    fun rotationMatrixToEulerAngles(rotationMatrix: FloatArray): FloatArray {
        val result = FloatArray(3)

        // Roll (x-axis rotation)
        val sinr_cosp = 2.0 * (rotationMatrix[0] * rotationMatrix[1] + rotationMatrix[2] * rotationMatrix[3])
        val cosr_cosp = 1.0 - 2.0 * (rotationMatrix[1] * rotationMatrix[1] + rotationMatrix[2] * rotationMatrix[2])
        result[0] = Math.atan2(sinr_cosp, cosr_cosp).toFloat()

        // Pitch (y-axis rotation)
        val sinp = 2.0 * (rotationMatrix[0] * rotationMatrix[2] - rotationMatrix[3] * rotationMatrix[1])
        result[1] = if (Math.abs(sinp) >= 1.0)
            Math.copySign(Math.PI / 2.0, sinp).toFloat() // Use 90 degrees if out of range
        else
            Math.asin(sinp).toFloat()

        // Yaw (z-axis rotation)
        val siny_cosp = 2.0 * (rotationMatrix[0] * rotationMatrix[3] + rotationMatrix[1] * rotationMatrix[2])
        val cosy_cosp = 1.0 - 2.0 * (rotationMatrix[2] * rotationMatrix[2] + rotationMatrix[3] * rotationMatrix[3])
        result[2] = Math.atan2(siny_cosp, cosy_cosp).toFloat()

        return result
    }

    /**
     * Convert YUV image to RGB bitmap
     */
    fun convertYuvToRgb(yuvImage: android.media.Image): Bitmap? {
        try {
            val yBuffer = yuvImage.planes[0].buffer
            val uBuffer = yuvImage.planes[1].buffer
            val vBuffer = yuvImage.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                yuvImage.width,
                yuvImage.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
                100,
                out
            )

            val imageBytes = out.toByteArray()
            return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV to RGB: ${e.message}", e)
            return null
        }
    }
}