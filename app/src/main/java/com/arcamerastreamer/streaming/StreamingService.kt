package com.arcamerastreamer.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arcamerastreamer.MainActivity
import com.arcamerastreamer.R
import com.arcamerastreamer.camera.ARCoreManager
import com.arcamerastreamer.camera.CameraManager
import com.arcamerastreamer.util.NetworkUtils
import java.util.concurrent.Executors

/**
 * Foreground service to handle camera streaming in the background
 */
class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ar_camera_streamer_channel"

        const val EXTRA_FROM_NOTIFICATION = "from_notification"

        // Service Actions
        const val ACTION_START_STREAMING = "com.arcamerastreamer.action.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.arcamerastreamer.action.STOP_STREAMING"

        // Streaming parameters
        const val EXTRA_STREAM_TYPE = "stream_type"
        const val EXTRA_RESOLUTION_WIDTH = "resolution_width"
        const val EXTRA_RESOLUTION_HEIGHT = "resolution_height"
        const val EXTRA_PORT = "port"
        const val EXTRA_ENABLE_AR = "enable_ar"

        // Default values
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    // Binder for local service interaction
    private val binder = LocalBinder()

    // Camera components
    private lateinit var cameraManager: CameraManager
    private var arCoreManager: ARCoreManager? = null

    // Streaming server
    private var streamingServer: StreamingServer? = null

    // Streaming settings
    private var streamType = StreamingServer.StreamType.VIDEO_ONLY
    private var streamResolution = Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
    private var port = DEFAULT_PORT
    private var enableAR = false

    // Service state
    private var isStreaming = false

    // Camera executor
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /**
     * Binder class for client communication
     */
    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Streaming service created")

        // Initialize camera manager
        cameraManager = CameraManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received start command: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_STREAMING -> {
                // Extract streaming parameters
                intent.getSerializableExtra(EXTRA_STREAM_TYPE)?.let {
                    streamType = it as StreamingServer.StreamType
                }

                val width = intent.getIntExtra(EXTRA_RESOLUTION_WIDTH, DEFAULT_WIDTH)
                val height = intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, DEFAULT_HEIGHT)
                streamResolution = Size(width, height)

                port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                enableAR = intent.getBooleanExtra(EXTRA_ENABLE_AR, false)

                // Start streaming
                startStreaming()
            }

            ACTION_STOP_STREAMING -> {
                stopStreaming()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Streaming service destroyed")

        // Stop streaming if active
        if (isStreaming) {
            stopStreaming()
        }

        // Shutdown executor
        cameraExecutor.shutdown()
    }

    /**
     * Start the streaming process
     */
    private fun startStreaming() {
        if (isStreaming) {
            Log.w(TAG, "Streaming is already active")
            return
        }

        Log.i(TAG, "Starting streaming: type=$streamType, resolution=$streamResolution, AR=$enableAR")

        try {
            // Create and start streaming server
            streamingServer = StreamingServer(port)
            val serverStarted = streamingServer?.startServer(
                type = streamType,
                width = streamResolution.width,
                height = streamResolution.height
            ) ?: false

            if (!serverStarted) {
                Log.e(TAG, "Failed to start streaming server")
                stopSelf()
                return
            }

            // Initialize AR if requested
            if (enableAR) {
                arCoreManager = ARCoreManager(applicationContext)
                arCoreManager?.initializeARSession()

                // Set up AR depth data listener
                arCoreManager?.setOnDepthDataAvailableListener { depthData ->
                    streamingServer?.updateDepthData(depthData)
                }
            }

            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification())

            // Mark as streaming
            isStreaming = true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting streaming: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * Stop streaming
     */
    private fun stopStreaming() {
        if (!isStreaming) return

        Log.i(TAG, "Stopping streaming")

        try {
            // Stop streaming server
            streamingServer?.stopServer()
            streamingServer = null

            // Close AR session
            arCoreManager?.close()
            arCoreManager = null

            // Mark as not streaming
            isStreaming = false

            // Stop foreground service
            stopForeground(STOP_FOREGROUND_REMOVE)

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping streaming: ${e.message}", e)
        }
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(): Notification {
        // Create notification channel for Android O+
        createNotificationChannel()

        // Create intent for when user taps notification
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_FROM_NOTIFICATION, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create stop action
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, StreamingService::class.java).apply {
                action = ACTION_STOP_STREAMING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_streaming))
            .setContentText(getNotificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using Android system icon instead of missing resource
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopIntent) // Using Android system icon
            .setOngoing(true)
            .build()
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = getString(R.string.notification_streaming_text)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Get notification text based on streaming status
     */
    private fun getNotificationText(): String {
        val ipAddress = NetworkUtils.getIPAddress(applicationContext)
        return if (ipAddress != null) {
            getString(R.string.stream_url, "http://$ipAddress:$port")
        } else {
            getString(R.string.notification_streaming_text)
        }
    }

    /**
     * Get server URL for client access
     */
    fun getServerUrl(): String? {
        val ipAddress = NetworkUtils.getIPAddress(applicationContext)
        return if (ipAddress != null) {
            "http://$ipAddress:$port"
        } else {
            null
        }
    }

    /**
     * Get current streaming status
     */
    fun isStreaming(): Boolean {
        return isStreaming
    }

    /**
     * Get current stream type
     */
    fun getStreamType(): StreamingServer.StreamType {
        return streamType
    }

    /**
     * Get current resolution
     */
    fun getResolution(): Size {
        return streamResolution
    }

    /**
     * Get current FPS
     */
    fun getCurrentFps(): Int {
        return streamingServer?.getCurrentFps() ?: 0
    }

    /**
     * Get active connections count
     */
    fun getActiveConnections(): Int {
        return streamingServer?.getActiveConnections() ?: 0
    }
}