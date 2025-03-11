package com.arcamerastreamer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera manager to handle CameraX operations
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    // CameraX components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
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
    private var imageAnalysisListener: ImageAnalysis.Analyzer? = null
    private var onCameraInitializedListener: (() -> Unit)? = null
    private var onCaptureFinishedListener: ((File?) -> Unit)? = null
    private var onErrorListener: ((Exception) -> Unit)? = null

    /**
     * Initialize the camera with the given lifecycle owner and preview view
     */
    fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetResolution: Size? = null,
        enableImageAnalysis: Boolean = false
    ) {
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

                // Build image analysis use case if requested
                if (enableImageAnalysis) {
                    buildImageAnalysisUseCase()
                }

                // Bind use cases to camera
                bindCameraUseCases(lifecycleOwner)

                isInitialized = true
                onCameraInitializedListener?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed: ${e.message}", e)
                onErrorListener?.invoke(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Build the Preview use case
     */
    private fun buildPreviewUseCase(previewView: PreviewView) {
        // Unbind previous preview
        preview?.let {
            cameraProvider?.unbind(it)
        }

        // Create new preview use case
        preview = Preview.Builder()
            .apply {
                targetResolution?.let { setTargetResolution(it) }
                setTargetRotation(Surface.ROTATION_0)
            }
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
    }

    /**
     * Build the ImageCapture use case
     */
    private fun buildImageCaptureUseCase() {
        // Unbind previous image capture
        imageCapture?.let {
            cameraProvider?.unbind(it)
        }

        // Create new image capture use case
        imageCapture = ImageCapture.Builder()
            .apply {
                targetResolution?.let { setTargetResolution(it) }
                setTargetRotation(Surface.ROTATION_0)
                setFlashMode(flashMode)
                setCaptureMode(CAPTURE_MODE_MINIMIZE_LATENCY)
            }
            .build()
    }

    /**
     * Build the ImageAnalysis use case
     */
    private fun buildImageAnalysisUseCase() {
        // Unbind previous image analysis
        imageAnalysis?.let {
            cameraProvider?.unbind(it)
        }

        // Create new image analysis use case
        imageAnalysis = ImageAnalysis.Builder()
            .apply {
                targetResolution?.let { setTargetResolution(it) }
                setTargetRotation(Surface.ROTATION_0)
                setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            }
            .build()
            .also {
                imageAnalysisListener?.let { analyzer ->
                    it.setAnalyzer(cameraExecutor, analyzer)
                }
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
            imageAnalysis?.let { useCaseGroup.add(it) }

            // Bind all use cases to camera lifecycle
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCaseGroup.toTypedArray()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error binding use cases: ${e.message}", e)
            onErrorListener?.invoke(e)
        }
    }

    /**
     * Set up image analysis
     */
    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        imageAnalysisListener = analyzer

        // Apply analyzer to current image analysis if it exists
        imageAnalysis?.setAnalyzer(cameraExecutor, analyzer)
    }

    /**
     * Take a picture with the current camera
     */
    fun takePicture(executor: Executor, saveLocation: File? = null) {
        val imageCapture = imageCapture ?: return

        // Create output file if not provided
        val outputFile = saveLocation ?: createOutputFile()

        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        // Take the picture
        imageCapture.takePicture(
            outputOptions,
            executor,
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
    }

    /**
     * Toggle flash mode
     */
    fun toggleFlash(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        imageCapture?.flashMode = flashMode
        return flashMode
    }

    /**
     * Set zoom ratio
     */
    fun setZoomRatio(zoomRatio: Float) {
        camera?.cameraControl?.setZoomRatio(
            max(min(zoomRatio, camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f), 1f)
        )
    }

    /**
     * Set focus at a specific point
     */
    fun setFocusPoint(x: Float, y: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()

        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Set exposure compensation
     */
    fun setExposureCompensation(value: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(value)
    }

    /**
     * Get camera properties
     */
    fun getCameraProperties(): CameraProperties {
        val cameraInfo = camera?.cameraInfo

        return CameraProperties(
            minZoomRatio = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f,
            maxZoomRatio = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f,
            hasFlash = cameraInfo?.hasFlashUnit() ?: false,
            minExposure = cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: 0,
            maxExposure = cameraInfo?.exposureState?.exposureCompensationRange?.upper ?: 0,
            supportedResolutions = getSupportedResolutions()
        )
    }

    /**
     * Get supported resolutions for the current camera
     */
    private fun getSupportedResolutions(): List<Size> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
        } else {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            }
        }

        if (cameraId == null) return emptyList()

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()

        return configs.getOutputSizes(android.graphics.ImageFormat.JPEG).toList()
    }

    /**
     * Get current resolution
     */
    fun getCurrentResolution(): Size? {
        return targetResolution
    }

    /**
     * Set target resolution
     */
    fun setTargetResolution(resolution: Size, lifecycleOwner: LifecycleOwner) {
        targetResolution = resolution

        // Rebuild and rebind all use cases with new resolution
        currentPreviewView?.let {
            if (preview != null) buildPreviewUseCase(it)
            if (imageCapture != null) buildImageCaptureUseCase()
            if (imageAnalysis != null) buildImageAnalysisUseCase()

            bindCameraUseCases(lifecycleOwner)
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
     * Class to hold camera properties
     */
    data class CameraProperties(
        val minZoomRatio: Float,
        val maxZoomRatio: Float,
        val hasFlash: Boolean,
        val minExposure: Int,
        val maxExposure: Int,
        val supportedResolutions: List<Size>
    )
}