package com.arcamerastreamer.streaming

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Streaming server implementation using NanoHTTPD
 */
class StreamingServer(private val port: Int = 8080) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "StreamingServer"
        private const val BOUNDARY = "mjpegboundary"
        private const val JPEG_QUALITY = 80
        private const val MJPEG_MIME = "multipart/x-mixed-replace;boundary=$BOUNDARY"
    }

    // Stream settings
    enum class StreamType {
        IMAGE_ONLY,
        AUDIO_ONLY,
        VIDEO_ONLY,
        VIDEO_AUDIO
    }

    // Current streaming type
    var streamType = StreamType.VIDEO_ONLY
        private set

    // Resolution
    var streamWidth = 1280
        private set
    var streamHeight = 720
        private set

    // Stream properties
    private val isStreaming = AtomicBoolean(false)
    private var currentFPS = AtomicInteger(0)
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    // Image frames
    private var latestJpegFrame = ByteArray(0)
    private val frameLock = ReentrantLock()

    // Connections tracking
    private val activeConnections = AtomicInteger(0)

    /**
     * Start the streaming server
     */
    fun startServer(
        type: StreamType,
        width: Int = 1280,
        height: Int = 720
    ): Boolean {
        streamType = type
        streamWidth = width
        streamHeight = height
        isStreaming.set(true)

        return try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Streaming server started on port $port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start streaming server: ${e.message}", e)
            isStreaming.set(false)
            false
        }
    }

    /**
     * Stop the streaming server
     */
    fun stopServer() {
        isStreaming.set(false)
        stop()
        Log.i(TAG, "Streaming server stopped")
    }

    /**
     * Create an image analyzer to process frames for streaming
     */
    fun createFrameProcessor(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            override fun analyze(imageProxy: ImageProxy) {
                if (!isStreaming.get()) {
                    imageProxy.close()
                    return
                }

                val image = imageProxy.image
                if (image == null) {
                    imageProxy.close()
                    return
                }

                when (streamType) {
                    StreamType.IMAGE_ONLY, StreamType.VIDEO_ONLY, StreamType.VIDEO_AUDIO -> {
                        processImageFrame(image, imageProxy.imageInfo.rotationDegrees)
                    }
                    StreamType.AUDIO_ONLY -> {
                        // No image processing needed for audio only
                    }
                }

                // Increment frame count for FPS calculation
                frameCount++
                calculateFPS()

                imageProxy.close()
            }
        }
    }

    /**
     * Process an image frame for streaming
     */
    private fun processImageFrame(image: Image, rotation: Int) {
        try {
            // Only process YUV images for now
            if (image.format == ImageFormat.YUV_420_888) {
                val yuvImage = convertImageToYuvImage(image)
                val jpegBytes = compressToJpeg(yuvImage)

                // Store the latest JPEG frame
                frameLock.withLock {
                    latestJpegFrame = jpegBytes
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image frame: ${e.message}", e)
        }
    }

    /**
     * Convert Image to YuvImage for JPEG compression
     */
    private fun convertImageToYuvImage(image: Image): YuvImage {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y
        yBuffer.get(nv21, 0, ySize)

        // Copy VU (NV21 format)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )
    }

    /**
     * Compress YuvImage to JPEG
     */
    private fun compressToJpeg(yuvImage: YuvImage): ByteArray {
        val out = ByteArrayOutputStream()

        // Compress to JPEG
        yuvImage.compressToJpeg(
            Rect(0, 0, yuvImage.width, yuvImage.height),
            JPEG_QUALITY,
            out
        )

        return out.toByteArray()
    }

    /**
     * Calculate FPS
     */
    private fun calculateFPS() {
        val currentTime = System.currentTimeMillis()
        val timeElapsed = currentTime - lastFpsTimestamp

        if (timeElapsed >= 1000) {
            // Calculate FPS - fix: convert to Int for AtomicInteger.set()
            val fps = ((frameCount * 1000) / timeElapsed).toInt()
            currentFPS.set(fps)

            // Reset counters
            frameCount = 0
            lastFpsTimestamp = currentTime
        }
    }

    /**
     * Get current frames per second
     */
    fun getCurrentFps(): Int {
        return currentFPS.get()
    }

    /**
     * Get active connections count
     */
    fun getActiveConnections(): Int {
        return activeConnections.get()
    }

    /**
     * Check if server is streaming
     */
    fun isStreaming(): Boolean {
        return isStreaming.get()
    }

    /**
     * Handle incoming HTTP requests
     */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            // Serve video stream
            uri == "/video" && (streamType == StreamType.VIDEO_ONLY || streamType == StreamType.VIDEO_AUDIO) -> {
                serveVideoStream(session)
            }

            // Serve static image
            uri == "/image" && streamType == StreamType.IMAGE_ONLY -> {
                serveStaticImage(session)
            }

            // Serve status information as JSON
            uri == "/status" -> {
                serveStatusInfo(session)
            }

            // Default to index page with links
            else -> {
                serveIndexPage(session)
            }
        }
    }

    /**
     * Serve MJPEG video stream
     */
    private fun serveVideoStream(session: IHTTPSession): Response {
        activeConnections.incrementAndGet()

        val videoStreamSource = MJPEGInputStream()

        return newChunkedResponse(
            Response.Status.OK,
            MJPEG_MIME,
            videoStreamSource
        ).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Connection", "close")
        }
    }

    /**
     * Serve a static image (latest frame)
     */
    private fun serveStaticImage(session: IHTTPSession): Response {
        val jpegBytes = frameLock.withLock { latestJpegFrame }

        return if (jpegBytes.isNotEmpty()) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(jpegBytes),
                jpegBytes.size.toLong()
            ).apply {
                addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            }
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "No image available"
            )
        }
    }

    /**
     * Serve server status information as JSON
     */
    private fun serveStatusInfo(session: IHTTPSession): Response {
        val statusJson = """
            {
                "streaming": ${isStreaming.get()},
                "fps": ${currentFPS.get()},
                "connections": ${activeConnections.get()},
                "streamType": "${streamType.name}",
                "resolution": {
                    "width": $streamWidth,
                    "height": $streamHeight
                }
            }
        """.trimIndent()

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            statusJson
        ).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        }
    }

    /**
     * Serve simple HTML index page with links
     */
    private fun serveIndexPage(session: IHTTPSession): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>AR Camera Streamer</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        margin: 0;
                        padding: 20px;
                        background-color: #121212;
                        color: #fff;
                    }
                    h1 {
                        color: #03DAC6;
                    }
                    .container {
                        max-width: 800px;
                        margin: 0 auto;
                    }
                    .card {
                        background-color: #1E1E1E;
                        border-radius: 8px;
                        padding: 20px;
                        margin-bottom: 20px;
                        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
                    }
                    .links {
                        display: flex;
                        flex-wrap: wrap;
                        gap: 10px;
                        margin-top: 20px;
                    }
                    .link {
                        background-color: #BB86FC;
                        color: #000;
                        padding: 10px 15px;
                        border-radius: 4px;
                        text-decoration: none;
                        font-weight: bold;
                    }
                    .stream-view {
                        width: 100%;
                        margin-top: 20px;
                        border-radius: 8px;
                        overflow: hidden;
                    }
                    .stream-view img, .stream-view video {
                        width: 100%;
                        display: block;
                    }
                    .status {
                        margin-top: 20px;
                        font-family: monospace;
                        white-space: pre-wrap;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="card">
                        <h1>AR Camera Streamer</h1>
                        <p>This web interface allows you to view the camera stream.</p>
                        
                        <div class="links">
                            <a class="link" href="/video" target="_blank">Video Stream</a>
                            <a class="link" href="/image" target="_blank">Static Image</a>
                            <a class="link" href="/status" target="_blank">Server Status</a>
                        </div>
                    </div>
                    
                    <div class="card">
                        <h2>Live Preview</h2>
                        <div class="stream-view">
                            <img src="/video" alt="Video Stream" id="videoStream">
                        </div>
                    </div>
                    
                    <div class="card">
                        <h2>Server Status</h2>
                        <div class="status" id="statusText">Loading...</div>
                    </div>
                </div>
                
                <script>
                    // Periodically update status
                    function updateStatus() {
                        fetch('/status')
                            .then(response => response.json())
                            .then(data => {
                                const statusElem = document.getElementById('statusText');
                                statusElem.textContent = JSON.stringify(data, null, 2);
                            })
                            .catch(err => console.error('Failed to update status:', err));
                    }
                    
                    // Update status every 2 seconds
                    updateStatus();
                    setInterval(updateStatus, 2000);
                    
                    // Handle stream errors
                    document.getElementById('videoStream').onerror = function() {
                        this.style.display = 'none';
                        this.parentNode.innerHTML = '<p>Stream not available or not started.</p>';
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(html)
    }

    /**
     * Inner class for MJPEG streaming
     */
    inner class MJPEGInputStream : InputStream() {
        private val BOUNDARY_BYTES = "--$BOUNDARY\r\n".toByteArray()
        private val CONTENT_TYPE_BYTES = "Content-Type: image/jpeg\r\n\r\n".toByteArray()
        private val BOUNDARY_END_BYTES = "\r\n".toByteArray()

        private var currentFrame = ByteArray(0)
        private var position = 0
        private var frameState = 0 // 0: boundary, 1: content type, 2: jpeg data, 3: boundary end

        init {
            activeConnections.incrementAndGet()
        }

        override fun read(): Int {
            try {
                when (frameState) {
                    0 -> {
                        if (position >= BOUNDARY_BYTES.size) {
                            position = 0
                            frameState = 1
                            return read()
                        }
                        return BOUNDARY_BYTES[position++].toInt() and 0xFF
                    }
                    1 -> {
                        if (position >= CONTENT_TYPE_BYTES.size) {
                            position = 0
                            frameState = 2
                            // Get the latest frame
                            frameLock.withLock {
                                currentFrame = latestJpegFrame
                            }
                            return read()
                        }
                        return CONTENT_TYPE_BYTES[position++].toInt() and 0xFF
                    }
                    2 -> {
                        if (currentFrame.isEmpty() || position >= currentFrame.size) {
                            position = 0
                            frameState = 3
                            return read()
                        }
                        return currentFrame[position++].toInt() and 0xFF
                    }
                    3 -> {
                        if (position >= BOUNDARY_END_BYTES.size) {
                            position = 0
                            frameState = 0
                            // Sleep to control frame rate
                            try {
                                Thread.sleep(33) // ~30 FPS
                            } catch (e: InterruptedException) {
                                // Ignore
                            }
                            return read()
                        }
                        return BOUNDARY_END_BYTES[position++].toInt() and 0xFF
                    }
                    else -> return -1
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading from stream", e)
                return -1
            }
        }

        override fun close() {
            super.close()
            activeConnections.decrementAndGet()
        }
    }
}