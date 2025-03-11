package com.arcamerastreamer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.arcamerastreamer.ARCameraStreamerApplication
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Manager class for AR Core operations and depth sensing
 */
class ARCoreManager(private val context: Context) {

    companion object {
        private const val TAG = "ARCoreManager"
    }

    // AR Core related variables
    private var session: Session? = null
    private var isSessionInitialized = false
    private var depthEnabled = false

    // Frame timestamps to avoid processing the same frame
    private var lastCameraFrameTimestamp = 0L

    // Listeners
    private var onDepthDataAvailableListener: ((DepthData) -> Unit)? = null
    private var onARTrackingUpdatedListener: ((TrackingState) -> Unit)? = null
    private var onARErrorListener: ((Exception) -> Unit)? = null

    /**
     * Initialize the AR session
     */
    fun initializeARSession() {
        // Check if ARCore is available using the application instance
        val appInstance = context.applicationContext as? ARCameraStreamerApplication
        val isARCoreAvailable = appInstance?.let { it::class.java.getDeclaredField("isARCoreAvailable").get(it.javaClass.getField("Companion").get(null)) as? Boolean } ?: false

        if (isARCoreAvailable) {
            try {
                // Check if we already have a session
                if (session == null) {
                    // Create a new AR session
                    session = Session(context)

                    // Configure session
                    val config = Config(session)

                    // Enable depth if available
                    if (session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        depthEnabled = true
                    }

                    // Enable plane detection
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

                    // Set lighting estimation mode
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

                    // Enable camera focus mode
                    config.focusMode = Config.FocusMode.AUTO

                    // Apply configuration
                    session?.configure(config)

                    // Store in application for global access
                    try {
                        val app = context.applicationContext as ARCameraStreamerApplication
                        val companionField = app::class.java.getDeclaredField("Companion")
                        companionField.isAccessible = true
                        val companion = companionField.get(null)

                        val arSessionField = companion.javaClass.getDeclaredField("arSession")
                        arSessionField.isAccessible = true
                        arSessionField.set(companion, session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not store AR session in application: ${e.message}", e)
                    }

                    isSessionInitialized = true
                    Log.d(TAG, "AR Session initialized, depth enabled: $depthEnabled")
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                Log.e(TAG, "ARCore not installed", e)
                onARErrorListener?.invoke(e)
            } catch (e: UnavailableApkTooOldException) {
                Log.e(TAG, "ARCore APK too old", e)
                onARErrorListener?.invoke(e)
            } catch (e: UnavailableSdkTooOldException) {
                Log.e(TAG, "Device SDK too old", e)
                onARErrorListener?.invoke(e)
            } catch (e: UnavailableDeviceNotCompatibleException) {
                Log.e(TAG, "Device not compatible with ARCore", e)
                onARErrorListener?.invoke(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                onARErrorListener?.invoke(e)
            }
        } else {
            Log.e(TAG, "ARCore is not available on this device")
            onARErrorListener?.invoke(
                UnavailableDeviceNotCompatibleException("ARCore is not available on this device")
            )
        }
    }

    /**
     * Create an ImageAnalysis.Analyzer that processes frames for AR and depth
     */
    fun createARImageAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            override fun analyze(imageProxy: ImageProxy) {
                try {
                    // Skip if session is not initialized
                    if (!isSessionInitialized || session == null) {
                        imageProxy.close()
                        return
                    }

                    val currentTimestamp = imageProxy.imageInfo.timestamp

                    // Skip duplicate frames
                    if (currentTimestamp <= lastCameraFrameTimestamp) {
                        imageProxy.close()
                        return
                    }

                    lastCameraFrameTimestamp = currentTimestamp

                    // Get camera image
                    val image = imageProxy.image ?: run {
                        imageProxy.close()
                        return
                    }

                    // Create AR frame
                    val frame = processARFrame(imageProxy, image)

                    // Process depth if enabled
                    if (depthEnabled) {
                        frame?.let { processDepthData(it) }
                    }

                    // Update tracking state
                    frame?.let { updateTrackingState(it) }

                } catch (e: Exception) {
                    Log.e(TAG, "Error analyzing image for AR: ${e.message}", e)
                    onARErrorListener?.invoke(e)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    /**
     * Process an image to create an AR frame
     */
    private fun processARFrame(imageProxy: ImageProxy, image: Image): Frame? {
        try {
            // Convert ImageProxy to AR Core compatible format
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Create AR Frame
            session?.let { arSession ->
                // Create ARCore image using camera image
                return arSession.update()
            }
        } catch (e: NotYetAvailableException) {
            // This occurs when data is not yet available, it's normal
            Log.d(TAG, "AR data not yet available")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available for AR", e)
            onARErrorListener?.invoke(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AR frame: ${e.message}", e)
            onARErrorListener?.invoke(e)
        }
        return null
    }

    /**
     * Process depth data from an AR frame
     */
    private fun processDepthData(frame: Frame) {
        try {
            // Try to get depth image
            val depthImage = frame.acquireDepthImage()

            // Process depth data
            val width = depthImage.width
            val height = depthImage.height
            val planes = depthImage.planes

            if (planes.isNotEmpty()) {
                val buffer = planes[0].buffer

                // Create depth data buffer
                val depthDataArray = ByteArray(buffer.remaining())
                buffer.get(depthDataArray)

                // Get depth confidence if available
                val confidenceBuffer = if (planes.size > 1) planes[1].buffer else null
                val confidenceArray = confidenceBuffer?.let {
                    val array = ByteArray(it.remaining())
                    it.get(array)
                    array
                }

                // Create depth data object
                val depthData = DepthData(
                    width = width,
                    height = height,
                    depthData = depthDataArray,
                    confidenceData = confidenceArray,
                    timestamp = frame.timestamp
                )

                // Notify listener
                onDepthDataAvailableListener?.invoke(depthData)
            }

            // Always close the depth image when done
            depthImage.close()

        } catch (e: NotYetAvailableException) {
            // This is normal when depth is not yet ready
            Log.v(TAG, "Depth data not yet available")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing depth data: ${e.message}", e)
        }
    }

    /**
     * Update tracking state from an AR frame
     */
    private fun updateTrackingState(frame: Frame) {
        val camera = frame.camera
        val trackingState = camera.trackingState

        // Notify listener about tracking state
        onARTrackingUpdatedListener?.invoke(trackingState)
    }

    /**
     * Check if depth is available on the device and enabled
     */
    fun isDepthAvailable(): Boolean {
        return depthEnabled && session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true
    }

    /**
     * Set surface for AR session for background camera rendering
     */
    fun setDisplayGeometry(width: Int, height: Int, rotation: Int) {
        if (isSessionInitialized && session != null) {
            session?.setDisplayGeometry(rotation, width, height)
        }
    }

    /**
     * Get current tracking state
     */
    fun getTrackingState(): TrackingState? {
        return try {
            val frame = session?.update()
            frame?.camera?.trackingState
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resume the AR session
     */
    fun resume() {
        if (isSessionInitialized && session != null) {
            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available for AR session", e)
                onARErrorListener?.invoke(e)
            }
        }
    }

    /**
     * Pause the AR session
     */
    fun pause() {
        if (isSessionInitialized && session != null) {
            session?.pause()
        }
    }

    /**
     * Close the AR session and release resources
     */
    fun close() {
        if (isSessionInitialized && session != null) {
            session?.close()
            session = null
            isSessionInitialized = false

            try {
                val app = context.applicationContext as ARCameraStreamerApplication
                val companionField = app::class.java.getDeclaredField("Companion")
                companionField.isAccessible = true
                val companion = companionField.get(null)

                val arSessionField = companion.javaClass.getDeclaredField("arSession")
                arSessionField.isAccessible = true
                arSessionField.set(companion, null)
            } catch (e: Exception) {
                Log.e(TAG, "Could not clear AR session in application: ${e.message}", e)
            }
        }
    }

    /**
     * Set listener for depth data
     */
    fun setOnDepthDataAvailableListener(listener: (DepthData) -> Unit) {
        onDepthDataAvailableListener = listener
    }

    /**
     * Set listener for tracking state updates
     */
    fun setOnARTrackingUpdatedListener(listener: (TrackingState) -> Unit) {
        onARTrackingUpdatedListener = listener
    }

    /**
     * Set listener for AR errors
     */
    fun setOnARErrorListener(listener: (Exception) -> Unit) {
        onARErrorListener = listener
    }

    /**
     * Data class to hold depth data
     */
    data class DepthData(
        val width: Int,
        val height: Int,
        val depthData: ByteArray,
        val confidenceData: ByteArray?,
        val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DepthData

            if (width != other.width) return false
            if (height != other.height) return false
            if (timestamp != other.timestamp) return false
            if (!depthData.contentEquals(other.depthData)) return false
            if (confidenceData != null) {
                if (other.confidenceData == null) return false
                if (!confidenceData.contentEquals(other.confidenceData)) return false
            } else if (other.confidenceData != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + depthData.contentHashCode()
            result = 31 * result + (confidenceData?.contentHashCode() ?: 0)
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
}