package com.lvs.lvstcpapplication.coders

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.lvs.lvstcpapplication.helpers.LVSConstants
import java.nio.ByteBuffer


object LVSEncoder: MediaCodec.Callback() {

    interface LVSEncoderDelegate {
        fun onDataAvailable(byteBuffer: ByteBuffer)
    }

    var delegate: LVSEncoderDelegate? = null

    private var encoder: MediaCodec? = null
    private var encodingConfigSent = false
    private var encodingThread : Thread? = null

    private var isEncoderRunning = false

    fun initializeAndStartEncoder(encodingSurface: Surface) {
        isEncoderRunning = true

        while (true) {
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder?.configure(LVSConstants.encodingVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i("LVSRND", "Encoding FPS: ${LVSConstants.fps}")
                break
            } catch(e: MediaCodec.CodecException) {
                if (e.errorCode == -1010) {
                    LVSConstants.fps /= 2
                    Log.i("LVSRND", "FPS Adjusted to ${LVSConstants.fps}")
                } else {
                    Log.e("LVSRND", "Unexpected Codec Error, Code: ${e.errorCode}")
                }
                encoder?.release()
                encoder = null
            }
        }

        encoder?.setCallback(this)
        encoder?.setInputSurface(encodingSurface)
        encoder?.start()
    }

    fun startEncoder(encodingSurface: Surface) {
        if (!isEncoderRunning) {
            isEncoderRunning = true

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder?.setCallback(this)
            encoder?.configure(LVSConstants.encodingVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.setInputSurface(encodingSurface)
            encoder?.start()
        }
    }

    fun stopEncoder() {
        if (isEncoderRunning) {
            encodingThread?.interrupt()
            encodingConfigSent = false
            isEncoderRunning = false
            encoder?.signalEndOfInputStream()
            encoder?.flush()
            encoder?.stop()
            encoder?.setCallback(null)
            encoder?.release()
        }
    }

    override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) = Unit

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (!encodingConfigSent) return

        encodingThread = Thread {
            if (Thread.currentThread().isInterrupted) return@Thread

            try {
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

                if (encodingConfigSent && isEncoderRunning)codec.releaseOutputBuffer(index, false)
            } catch (exc: InterruptedException) {
                Log.i("LVSRND", "Encoding thread is interrupted")
            }

        }

        encodingThread?.start()
    }

    override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
        Log.e("LVSRND", "Error during encoding: ${p1.localizedMessage} Error Code: ${p1.errorCode}")
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