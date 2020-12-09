package com.lvs.lvstcpapplication.coders

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.lvs.lvstcpapplication.LVSConstants
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference


private const val timeout : Long = 12000

object LVSDecoder {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()

    fun initializeAndStartDecoder(outputSurface: AtomicReference<Surface>, sps: ByteArray, pps: ByteArray) {
        val format = LVSConstants.decodingVideoFormat
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        codec.configure(format, outputSurface.get(), null, 0)
        codec.start()
    }

    fun decode(data: ByteArray) {
//        if (isEndOfStream) codec.signalEndOfInputStream()

        var inputBuffer: ByteBuffer?
        var inputBufferIndex: Int


        while(true) {
            inputBufferIndex = codec.dequeueInputBuffer(timeout)

            if (inputBufferIndex >= 0) {
                inputBuffer = codec.getInputBuffer(inputBufferIndex)

                inputBuffer?.clear()
                inputBuffer?.put(data, 0, data.size)
                codec.queueInputBuffer(inputBufferIndex, 0, data.size, 0, 0)
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                Log.i("LVSRND", "newFormat: $newFormat")
            } else if (outputBufferIndex < 0) {
                Log.i("LVSRND", "Unexpected Result From LVSDecoder: $outputBufferIndex")
            } else {
                codec.releaseOutputBuffer(outputBufferIndex, true)
                break
            }
        }
    }


    fun endDecoding() {
        codec.stop()
        codec.reset()
        codec.release()
    }


//    fun feedFrame(data: ByteArray) {
//        val info = BufferInfo()
//        val inputIndex = decoder.dequeueInputBuffer(1000)
//        if (inputIndex == -1) return
//        val inputBuffer: ByteBuffer = decoder.inputBuffers[inputIndex]
//        if (inputIndex >= 0) {
//            inputBuffer.clear()
//            inputBuffer.put(data, 0, data.size)
//            inputBuffer.clear()
//            decoder.queueInputBuffer(inputIndex, 0, data.size, 0, 0)
//        }
//
//        when (val outIndex = decoder.dequeueOutputBuffer(info, 1000)) {
//            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
//            }
//            MediaCodec.INFO_TRY_AGAIN_LATER -> {
//            }
//            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
//            }
//            else -> decoder.releaseOutputBuffer(outIndex, true)
//        }
//    }
//
}