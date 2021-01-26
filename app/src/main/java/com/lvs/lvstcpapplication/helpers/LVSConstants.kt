package com.lvs.lvstcpapplication.helpers

import android.media.MediaCodecInfo
import android.media.MediaFormat

object LVSConstants {
    var bitRate : Int = 1280 * 720 * 3
    var recordingFps = 60
    var fps : Int = 30
    const val recordingBitRate = 1280 * 720 * 3
    const val width = 1280
    const val height = 720
    const val tcpPacketSize = 4096

    val encodingVideoFormat: MediaFormat
        get() {
            return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / fps)
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0x00)
            }
        }

    val decodingVideoFormat: MediaFormat
        get() {
            return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0x00)
                setInteger(MediaFormat.KEY_ROTATION, 90)
        }
    }
}

enum class LVSTCPDataType(val value: Int) {
    VideoPartialData(1),
    VideoPartialDataTransmissionCompleted(2),
    RecordingData(3),
    DrawingData(4),
    VideoConfigurationData(5),
    RecordedVideoInProgress(6),
    RecordedVideoEnded(7),
    SoundTriggerData(8),
    StreamStatus(9)
}

enum class RecordingState(val value: Int) {
    Stop(0),
    Start(1)
}

enum class LVSyncSoundTriggeringStatus(val value: Int) {
    Started(1),
    Ended(2),
    Cancelled(3)
}