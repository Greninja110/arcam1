package com.arcamerastreamer.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data class for streaming configuration options
 */
@Parcelize
data class StreamingOptions(
    // Type of streaming
    var streamType: StreamType = StreamType.VIDEO_ONLY,

    // Network port to use
    var port: Int = 8080,

    // Video quality/bitrate
    var videoBitrate: Int = 2000000, // 2 Mbps

    // Audio quality/bitrate
    var audioBitrate: Int = 128000, // 128 kbps

    // Resolution settings
    var width: Int = 1280,
    var height: Int = 720,

    // Maximum allowed connections
    var maxConnections: Int = 1
) : Parcelable {

    /**
     * Streaming types
     */
    enum class StreamType {
        IMAGE_ONLY,
        AUDIO_ONLY,
        VIDEO_ONLY,
        VIDEO_AUDIO
    }

    // Add these quality presets to your StreamingOptions.kt file in the companion object
    companion object {
        // Quality presets with predefined resolutions
        val LOW_QUALITY = StreamingOptions(
            videoBitrate = 500000,  // 500 Kbps
            audioBitrate = 64000,   // 64 Kbps
            width = 640,
            height = 480
        )

        val MEDIUM_QUALITY = StreamingOptions(
            videoBitrate = 2000000, // 2 Mbps
            audioBitrate = 128000,  // 128 Kbps
            width = 1280,
            height = 720
        )

        val HIGH_QUALITY = StreamingOptions(
            videoBitrate = 4000000, // 4 Mbps
            audioBitrate = 192000,  // 192 Kbps
            width = 1920,
            height = 1080
        )
    }
}