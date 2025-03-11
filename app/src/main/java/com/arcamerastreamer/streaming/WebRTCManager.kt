package com.arcamerastreamer.streaming

import android.content.Context
import android.util.Log
import android.util.Size
import com.arcamerastreamer.models.StreamingOptions
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager for WebRTC streaming
 */
class WebRTCManager(
    private val context: Context,
    private val options: StreamingOptions
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val LOCAL_TRACK_ID = "ARCameraStreamTrack"
        private const val LOCAL_STREAM_ID = "ARCameraStream"

        // Default values
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_FPS = 30
    }

    // WebRTC objects
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localMediaStream: MediaStream? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    // Peer connections
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    // State
    private val isInitialized = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    /**
     * Initialize WebRTC components
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.w(TAG, "WebRTC already initialized")
            return
        }

        try {
            // Initialize PeerConnectionFactory
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(initializationOptions)

            // Create PeerConnectionFactory
            val options = PeerConnectionFactory.Options()

            val encoderFactory = DefaultVideoEncoderFactory(
                EglBase.create().eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            // Create MediaStream
            localMediaStream = peerConnectionFactory?.createLocalMediaStream(LOCAL_STREAM_ID)

            isInitialized.set(true)
            Log.i(TAG, "WebRTC initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebRTC: ${e.message}", e)
        }
    }

    /**
     * Start streaming with the given camera capturer
     */
    fun startStreaming(videoCapturer: VideoCapturer?) {
        if (!isInitialized.get() || isStreaming.get() || peerConnectionFactory == null) {
            Log.e(TAG, "Cannot start streaming: WebRTC not initialized or already streaming")
            return
        }

        try {
            // Create video source
            if (videoCapturer != null) {
                videoSource = peerConnectionFactory?.createVideoSource(videoCapturer.isScreencast)

                // Create SurfaceTextureHelper for capturing
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    EglBase.create().eglBaseContext
                )

                // Initialize video capturer
                videoCapturer.initialize(
                    surfaceTextureHelper,
                    context,
                    videoSource?.capturerObserver
                )

                // Get resolution and fps from options or use defaults
                val width = getVideoWidth()
                val height = getVideoHeight()
                val fps = getVideoFps()

                // Start video capture
                videoCapturer.startCapture(width, height, fps)

                // Create video track
                localVideoTrack = peerConnectionFactory?.createVideoTrack(
                    "${LOCAL_TRACK_ID}_video",
                    videoSource
                )

                // Add video track to stream
                localVideoTrack?.let { localMediaStream?.addTrack(it) }
            }

            // Create audio source if needed
            if (options.streamType == StreamingOptions.StreamType.AUDIO_ONLY ||
                options.streamType == StreamingOptions.StreamType.VIDEO_AUDIO) {

                val audioConstraints = MediaConstraints()
                audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)

                // Create audio track
                localAudioTrack = peerConnectionFactory?.createAudioTrack(
                    "${LOCAL_TRACK_ID}_audio",
                    audioSource
                )

                // Add audio track to stream
                localAudioTrack?.let { localMediaStream?.addTrack(it) }
            }

            isStreaming.set(true)
            Log.i(TAG, "WebRTC streaming started")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebRTC streaming: ${e.message}", e)
            stopStreaming()
        }
    }

    /**
     * Get video width from options
     */
    private fun getVideoWidth(): Int {
        return try {
            // Try multiple ways to get the resolution width
            when {
                // If there's a method to get resolution
                options::class.java.getMethod("getResolution") != null -> {
                    val resolution = options.javaClass.getMethod("getResolution").invoke(options) as? Size
                    resolution?.width ?: DEFAULT_WIDTH
                }
                // If there's a direct resolution property
                options::class.java.getDeclaredField("resolution") != null -> {
                    val field = options::class.java.getDeclaredField("resolution")
                    field.isAccessible = true
                    val resolution = field.get(options) as? Size
                    resolution?.width ?: DEFAULT_WIDTH
                }
                // If there's a width property
                options::class.java.getDeclaredField("width") != null -> {
                    val field = options::class.java.getDeclaredField("width")
                    field.isAccessible = true
                    field.getInt(options)
                }
                // Fallback
                else -> DEFAULT_WIDTH
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get width from options, using default: ${e.message}")
            DEFAULT_WIDTH
        }
    }

    /**
     * Get video height from options
     */
    private fun getVideoHeight(): Int {
        return try {
            // Try multiple ways to get the resolution height
            when {
                // If there's a method to get resolution
                options::class.java.getMethod("getResolution") != null -> {
                    val resolution = options.javaClass.getMethod("getResolution").invoke(options) as? Size
                    resolution?.height ?: DEFAULT_HEIGHT
                }
                // If there's a direct resolution property
                options::class.java.getDeclaredField("resolution") != null -> {
                    val field = options::class.java.getDeclaredField("resolution")
                    field.isAccessible = true
                    val resolution = field.get(options) as? Size
                    resolution?.height ?: DEFAULT_HEIGHT
                }
                // If there's a height property
                options::class.java.getDeclaredField("height") != null -> {
                    val field = options::class.java.getDeclaredField("height")
                    field.isAccessible = true
                    field.getInt(options)
                }
                // Fallback
                else -> DEFAULT_HEIGHT
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get height from options, using default: ${e.message}")
            DEFAULT_HEIGHT
        }
    }

    /**
     * Get video fps from options
     */
    private fun getVideoFps(): Int {
        return try {
            // Try multiple ways to get the fps
            when {
                // If there's a method to get fps
                options::class.java.getMethod("getFps") != null -> {
                    val fpsValue = options.javaClass.getMethod("getFps").invoke(options) as? Int
                    fpsValue ?: DEFAULT_FPS
                }
                // If there's a direct fps property
                options::class.java.getDeclaredField("fps") != null -> {
                    val field = options::class.java.getDeclaredField("fps")
                    field.isAccessible = true
                    field.getInt(options)
                }
                // Fallback
                else -> DEFAULT_FPS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get fps from options, using default: ${e.message}")
            DEFAULT_FPS
        }
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        if (!isStreaming.get()) {
            return
        }

        try {
            // Stop and dispose audio/video sources
            videoSource?.dispose()
            audioSource?.dispose()

            // Clear media stream
            localMediaStream?.removeTrack(localVideoTrack)
            localMediaStream?.removeTrack(localAudioTrack)

            // Dispose tracks
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()

            // Reset references
            videoSource = null
            audioSource = null
            localVideoTrack = null
            localAudioTrack = null

            isStreaming.set(false)
            Log.i(TAG, "WebRTC streaming stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebRTC streaming: ${e.message}", e)
        }
    }

    /**
     * Release all resources
     */
    fun release() {
        if (isStreaming.get()) {
            stopStreaming()
        }

        try {
            // Close all peer connections
            peerConnections.values.forEach { it.dispose() }
            peerConnections.clear()

            // Dispose factory
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            isInitialized.set(false)
            Log.i(TAG, "WebRTC resources released")

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC resources: ${e.message}", e)
        }
    }
}