package com.lvs.lvstcpapplication

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.Exception
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer


object LVSEncoder: MediaCodec.Callback() {

    interface LVSEncoderDelegate {
        fun onDataAvailable(byteBuffer: ByteBuffer)
    }

    var delegate: LVSEncoderDelegate? = null

    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var encodingConfigSent = false

    init {
        while (true) {
            try {
                Log.i("LVSRND", "Encoding FPS: ${LVSConstants.fps}")
                encoder.configure(LVSConstants.encodingVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                break
            } catch(e: MediaCodec.CodecException) {
                if (e.errorCode == -1010) {
                    LVSConstants.fps /= 2
                } else {
                    Log.i("LVSRND", "Unexpected Codec Error, Code: ${e.errorCode}")
                }
            }
        }

        encoder.setCallback(this)
    }

    fun initializeAndStartEncoder(encodingSurface: Surface) {
        encoder.setInputSurface(encodingSurface)
        encoder.start()
    }

    fun stopEncoder() {
        encoder.stop()
    }

    override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) = Unit

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (!encodingConfigSent) return

        Thread {
            val outputBuffer: ByteBuffer? = codec.getOutputBuffer(index)
            val byteBuffer: ByteBuffer

            if (outputBuffer != null) {
                byteBuffer = ByteBuffer.allocate(outputBuffer.limit())

                byteBuffer.put(outputBuffer)
                byteBuffer.flip()

                delegate?.onDataAvailable(byteBuffer)
            } else {
                return@Thread
            }

            codec.releaseOutputBuffer(index, false)
        }.start()
    }

    override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
        Log.i("LVSRND", "Error during encoding: ${p1.localizedMessage}")
    }

    override fun onOutputFormatChanged(p0: MediaCodec, mediaFormat: MediaFormat) {
        Thread {
            val sps: ByteBuffer? = mediaFormat.getByteBuffer("csd-0")
            val pps: ByteBuffer? = mediaFormat.getByteBuffer("csd-1")

            if (sps == null || pps == null) return@Thread
            delegate?.onDataAvailable(sps)
            delegate?.onDataAvailable(pps)

            encodingConfigSent = true
        }.start()
    }
}