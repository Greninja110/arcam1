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

    // Streaming protocol to use
    var protocol: StreamProtocol = StreamProtocol.RTSP,

    // Enable AR data in stream
    var includeARData: Boolean = false,

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

    /**
     * Available streaming protocols
     */
    enum class StreamProtocol {
        RTSP,
        WEBRTC,
        HTTP
    }

    companion object {
        // Quality presets
        val LOW_QUALITY = StreamingOptions(
            videoBitrate = 500000,  // 500 Kbps
            audioBitrate = 64000    // 64 Kbps
        )

        val MEDIUM_QUALITY = StreamingOptions(
            videoBitrate = 2000000, // 2 Mbps
            audioBitrate = 128000   // 128 Kbps
        )

        val HIGH_QUALITY = StreamingOptions(
            videoBitrate = 4000000, // 4 Mbps
            audioBitrate = 192000   // 192 Kbps
        )
    }
}