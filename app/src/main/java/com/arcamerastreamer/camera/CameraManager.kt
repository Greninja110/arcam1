package com.arcamerastreamer.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera manager to handle CameraX operations
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    // CameraX components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    // Store reference to current PreviewView
    private var currentPreviewView: PreviewView? = null

    // Camera settings
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Resolution settings
    private var targetResolution: Size? = null

    // Executor for camera operations
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Flags
    private var isInitialized = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // Listeners
    private var onCameraInitializedListener: (() -> Unit)? = null
    private var onCaptureFinishedListener: ((File?) -> Unit)? = null
    private var onErrorListener: ((Exception) -> Unit)? = null

    /**
     * Initialize the camera with the given lifecycle owner and preview view
     */
    fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetResolution: Size? = null
    ) {
        try {
            // Store target resolution and preview view
            this.targetResolution = targetResolution
            this.currentPreviewView = previewView

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()

                    // Create camera selector based on current lens facing
                    cameraSelector = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }

                    // Build preview use case
                    buildPreviewUseCase(previewView)

                    // Build image capture use case
                    buildImageCaptureUseCase()

                    // Bind use cases to camera
                    bindCameraUseCases(lifecycleOwner)

                    isInitialized = true
                    Log.d(TAG, "Camera initialized successfully")
                    onCameraInitializedListener?.invoke()

                } catch (e: Exception) {
                    Log.e(TAG, "Camera initialization failed: ${e.message}", e)
                    onErrorListener?.invoke(e)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Build the Preview use case
     */
    private fun buildPreviewUseCase(previewView: PreviewView) {
        try {
            // Unbind previous preview
            preview?.let {
                cameraProvider?.unbind(it)
            }

            // Create new preview use case
            preview = Preview.Builder()
                .apply {
                    // Fix for deprecated method warning
                    // Instead of setTargetResolution(Size), we now use setTargetResolution() for resolution
                    targetResolution?.let {
                        setTargetResolution(it)
                    }
                    setTargetRotation(Surface.ROTATION_0)
                }
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error building preview use case: ${e.message}", e)
            throw e
        }
    }

    /**
     * Build the ImageCapture use case
     */
    private fun buildImageCaptureUseCase() {
        try {
            // Unbind previous image capture
            imageCapture?.let {
                cameraProvider?.unbind(it)
            }

            // Create new image capture use case
            imageCapture = ImageCapture.Builder()
                .apply {
                    // Fix for deprecated method warning
                    // Instead of setTargetResolution(Size), we now use setTargetResolution() for resolution
                    targetResolution?.let {
                        setTargetResolution(it)
                    }
                    setTargetRotation(Surface.ROTATION_0)
                    setFlashMode(flashMode)
                    setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error building image capture use case: ${e.message}", e)
            throw e
        }
    }

    /**
     * Bind all camera use cases
     */
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        try {
            // Unbind all use cases before rebinding
            cameraProvider?.unbindAll()

            // Create a use case group with all active use cases
            val useCaseGroup = ArrayList<UseCase>()
            preview?.let { useCaseGroup.add(it) }
            imageCapture?.let { useCaseGroup.add(it) }

            // Bind all use cases to camera lifecycle
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCaseGroup.toTypedArray()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error binding use cases: ${e.message}", e)
            onErrorListener?.invoke(e)
            throw e
        }
    }

    /**
     * Take a picture with the current camera
     */
    fun takePicture() {
        try {
            val imageCapture = imageCapture ?: return

            // Create output file
            val outputFile = createOutputFile()

            // Create output options
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            // Take the picture
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "Image saved: ${outputFile.absolutePath}")
                        onCaptureFinishedListener?.invoke(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                        onCaptureFinishedListener?.invoke(null)
                        onErrorListener?.invoke(exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Create an output file for image capture
     */
    private fun createOutputFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "IMG_${timeStamp}.jpg"
        val storageDir = context.getExternalFilesDir("Pictures")
        return File(storageDir, imageFileName)
    }

    /**
     * Toggle the camera between front and back
     */
    fun toggleCamera(lifecycleOwner: LifecycleOwner) {
        try {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            // Create new camera selector
            cameraSelector = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            // Rebind all use cases with new camera selector
            bindCameraUseCases(lifecycleOwner)

            Log.d(TAG, "Camera toggled to: ${if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling camera: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Toggle flash mode
     */
    fun toggleFlash(): Int {
        try {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

            imageCapture?.flashMode = flashMode
            Log.d(TAG, "Flash mode changed to: $flashMode")
            return flashMode
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flash: ${e.message}", e)
            onErrorListener?.invoke(e)
            return flashMode
        }
    }

    /**
     * Set zoom ratio
     */
    fun setZoomRatio(zoomRatio: Float) {
        try {
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

            // Clamp zoom value between min and max
            val clampedZoom = zoomRatio.coerceIn(minZoom, maxZoom)

            camera?.cameraControl?.setZoomRatio(clampedZoom)
            Log.d(TAG, "Zoom ratio set to: $clampedZoom")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting zoom ratio: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Set focus at a specific point
     */
    fun setFocusPoint(x: Float, y: Float) {
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point).build()

            camera?.cameraControl?.startFocusAndMetering(action)
            Log.d(TAG, "Focus point set to: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting focus point: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Release all camera resources
     */
    fun shutdown() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            isInitialized = false
            Log.d(TAG, "Camera resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down camera: ${e.message}", e)
        }
    }

    /**
     * Set callback for when camera is fully initialized
     */
    fun setOnCameraInitializedListener(listener: () -> Unit) {
        onCameraInitializedListener = listener
    }

    /**
     * Set callback for when image capture is finished
     */
    fun setOnCaptureFinishedListener(listener: (File?) -> Unit) {
        onCaptureFinishedListener = listener
    }

    /**
     * Set callback for errors
     */
    fun setOnErrorListener(listener: (Exception) -> Unit) {
        onErrorListener = listener
    }

    /**
     * Check if flash is available on the current camera
     */
    fun isFlashAvailable(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() ?: false
    }

    /**
     * Get current camera facing
     */
    fun getCurrentCameraFacing(): Int {
        return lensFacing
    }
}