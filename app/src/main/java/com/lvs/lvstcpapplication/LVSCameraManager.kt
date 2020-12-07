package com.lvs.lvstcpapplication

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@ExperimentalCoroutinesApi
object LVSCameraManager {
    interface LVSCameraManagerDelegate {
        fun cameraInitialized(encodingSurface: AtomicReference<Surface>)
    }
    var delegate: LVSCameraManagerDelegate? = null

    private const val RECORDER_VIDEO_BITRATE: Int = 6_000_000

    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession

    private lateinit var cameraManager: CameraManager

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private lateinit var outputFile: File

    private lateinit var previewSurface: Surface

    private var dummyFile: File? = null

    private val encodingSurface: Surface by lazy {
        val surface = MediaCodec.createPersistentInputSurface()
        createRecorder(surface).apply {
            prepare()
            release()
        }
        surface
    }

    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(previewSurface)
        }.build()
    }

    private val encodingRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(encodingSurface)
        }.build()
    }


    fun initializeCameraManager(context: Context, previewSurface: AtomicReference<Surface>) = CoroutineScope(Dispatchers.Main).launch {
        this@LVSCameraManager.previewSurface = previewSurface.get()

        outputFile =  createFile(context, "mp4")
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        camera = openCamera(cameraManager, cameraManager.cameraIdList.first(), cameraHandler)

        session = createCaptureSession(camera, listOf(this@LVSCameraManager.previewSurface, encodingSurface), cameraHandler, context)

        session.setRepeatingRequest(previewRequest, null, cameraHandler)
        session.setRepeatingRequest(encodingRequest, null, cameraHandler)

        delegate?.cameraInitialized(AtomicReference(this@LVSCameraManager.encodingSurface))
    }

    fun stopCameraManager() {
        session.stopRepeating()
        session.close()
        encodingSurface.release()
        previewSurface.release()
        cameraHandler.removeCallbacksAndMessages(null)
        cameraThread.interrupt()
        dummyFile?.delete()
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
            val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigs, context.mainExecutor, object: CameraCaptureSession.StateCallback() {

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

    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(60)
        setVideoSize(640, 480)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(surface)
    }

    private fun createFile(context: Context, extension: String): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(context.filesDir, "VID_${sdf.format(Date())}.$extension").also { dummyFile = it }
    }
}