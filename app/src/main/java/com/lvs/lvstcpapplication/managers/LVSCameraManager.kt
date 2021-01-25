package com.lvs.lvstcpapplication.managers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.lvs.lvstcpapplication.coders.LVSEncoder
import com.lvs.lvstcpapplication.helpers.LVSConstants
import com.lvs.lvstcpapplication.helpers.LVSTCPDataType
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


@ExperimentalCoroutinesApi
object LVSCameraManager {
    interface LVSCameraManagerDelegate {
        fun cameraInitialized(encodingSurface: Surface)
    }
    var delegate: LVSCameraManagerDelegate? = null

    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession

    private lateinit var cameraManager: CameraManager

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private lateinit var previewSurface: Surface

    private var dummyFile: File? = null
    private var recordingFile: File? = null

    private var mediaRecorder: MediaRecorder? = null

    private lateinit var recordingSurface: Surface
    private lateinit var encodingSurface: Surface

    private lateinit var previewRequest: CaptureRequest
    private lateinit var encodingRequest: CaptureRequest

    private var isRecording = false
    var recordedVideoFileLength: Int? = null

    fun initializeCameraManager(context: Context, previewSurface: Surface, retrievedEncodingSurface: Surface? = null) = CoroutineScope(Dispatchers.Main).launch {
        if (retrievedEncodingSurface == null) {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            getMaximumCameraFPS()?.let { LVSConstants.recordingFps = it }
            camera = openCamera(cameraManager, cameraManager.cameraIdList.first(), cameraHandler)
        }

        createFile(context)

        Log.d("OSMAN", "Initialize Camera Manager Called: $retrievedEncodingSurface")
//        if (LVSConstants.bitRate == 0) LVSConstants.bitRate = 1280 * 720 * 3
//        LVSConstants.fps = 30
        encodingSurface = MediaCodec.createPersistentInputSurface()
        createDummyRecorder(encodingSurface).apply {
            prepare()
            release()
        }

        recordingSurface = MediaCodec.createPersistentInputSurface()
        mediaRecorder = createRecorder(recordingSurface).apply {
            prepare()
        }

        LVSCameraManager.previewSurface = previewSurface

        session = createCaptureSession(camera, listOf(LVSCameraManager.previewSurface, encodingSurface, recordingSurface), cameraHandler, context)

        previewRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(LVSCameraManager.previewSurface)
        }.build()

        encodingRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(LVSCameraManager.previewSurface)
            addTarget(encodingSurface)
            addTarget(recordingSurface)
        }.build()

        session.setRepeatingRequest(previewRequest, null, cameraHandler)
        session.setRepeatingRequest(encodingRequest, null, cameraHandler)

        delegate?.cameraInitialized(encodingSurface)

        if (retrievedEncodingSurface != null) LVSEncoder.startEncoder(encodingSurface)
//        if (retrievedEncodingSurface != null) LVSEncoder.startEncoder(LVSEncoder.encodingSurface!!)

    }

    fun startRecording() {
        if (!isRecording) {
            isRecording = true
            mediaRecorder?.start()
        }
    }

    fun stopRecording() {
        if (isRecording) {
            isRecording = false

            mediaRecorder?.pause()
            mediaRecorder?.stop()
            mediaRecorder?.release()

            stopCameraManager()

            recordingFile?.let {
                val bgScope = CoroutineScope(Dispatchers.IO)
                bgScope.launch {
                    sendRecordedVideo(it)
                    bgScope.cancel()
                }
            }
        } else {
            stopCameraManager()
        }
    }

    fun cancelRecording(context: Context, previewSurface: Surface) {
        if (isRecording) {
            isRecording = false

            mediaRecorder?.pause()
            mediaRecorder?.stop()
            mediaRecorder?.release()

            stopCameraManager()
            recordingFile?.delete()

            initializeCameraManager(context, previewSurface, encodingSurface)
        }
    }

    fun stopCameraManager() {
        LVSEncoder.stopEncoder()

        session.abortCaptures()
        session.stopRepeating()
        session.close()
        recordingSurface.release()
        encodingSurface.release()
//        previewSurface.release()
        dummyFile?.delete()
    }

    private fun sendRecordedVideo(videoFile: File) {
        val inputStream = DataInputStream(FileInputStream(videoFile))
        val videoFileBuffer = ByteArray(LVSConstants.tcpPacketSize)

        val maxLoopCount = videoFile.length().toInt() / LVSConstants.tcpPacketSize
        var loopCounter = 0
        recordedVideoFileLength = videoFile.length().toInt()
        var currentTransmittedBytes = 0

        while (loopCounter < maxLoopCount) {
            inputStream.readFully(videoFileBuffer, 0, LVSConstants.tcpPacketSize)
            currentTransmittedBytes += LVSConstants.tcpPacketSize
//            broadcastingDelegate?.transmittedDataLengthChanged(((currentTransmittedBytes.toFloat() / (recordedVideoFileLength ?: 1F).toFloat()) * 100).toInt())
            LVSTCPManager.sendEncodedData(LVSTCPDataType.RecordedVideoInProgress, ByteBuffer.wrap(videoFileBuffer))
            loopCounter++
        }

        val lastLoopByteSize = videoFile.length().toInt() % LVSConstants.tcpPacketSize
        if (lastLoopByteSize > 0) {
            val lastVideoBuffer = ByteArray(lastLoopByteSize)
            inputStream.readFully(lastVideoBuffer, 0, lastLoopByteSize)
            currentTransmittedBytes += lastLoopByteSize
//            broadcastingDelegate?.transmittedDataLengthChanged(((currentTransmittedBytes.toFloat() / (recordedVideoFileLength ?: 1F).toFloat()) * 100).toInt())
            LVSTCPManager.sendEncodedData(LVSTCPDataType.RecordedVideoInProgress, ByteBuffer.wrap(lastVideoBuffer))
        }

        LVSTCPManager.sendEncodedData(LVSTCPDataType.RecordedVideoEnded, ByteBuffer.wrap(ByteArray(0)))
    }

    private fun getMaximumCameraFPS() : Int? {
        val cameraId = cameraManager.cameraIdList[0]
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null

        return if (fpsRange.last().upper > 60) 60 else fpsRange.last().upper
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String, handler: Handler? = null): CameraDevice = suspendCancellableCoroutine { cont ->

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device) {
                Log.w("LVSRND", "Error while opening camera: $it")
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w("LVSRND", "Camera $cameraId has been disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e("LVSRND", exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler? = null, context: Context): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val outputConfigs = targets.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigs, context.mainExecutor, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e("LVSRND", exc.message, exc)
                    cont.resumeWithException(exc)
                }
            })
            device.createCaptureSession(sessionConfig)
        } else {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e("LVSRND", exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }, handler)
        }
    }

    private fun createDummyRecorder(surface: Surface) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(dummyFile!!.absolutePath)
        setVideoEncodingBitRate(LVSConstants.bitRate)
        setVideoFrameRate(LVSConstants.fps)
        setCaptureRate(LVSConstants.fps.toDouble())
        setVideoSize(LVSConstants.width, LVSConstants.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(surface)
    }

    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(recordingFile!!.absolutePath)
        setVideoFrameRate(LVSConstants.recordingFps)
        setCaptureRate(LVSConstants.recordingFps.toDouble())
        setVideoEncodingBitRate(LVSConstants.recordingBitRate)
        setVideoSize(LVSConstants.width, LVSConstants.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT)
        setOrientationHint(90)
        setInputSurface(surface)
    }


    private fun createFile(context: Context) {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        dummyFile = File(context.filesDir, "DUMMY_${sdf.format(Date())}.mp4")
        recordingFile = File(context.filesDir, "ACTUAL_${sdf.format(Date())}.mp4")
    }
}