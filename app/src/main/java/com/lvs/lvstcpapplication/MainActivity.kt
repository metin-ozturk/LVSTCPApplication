package com.lvs.lvstcpapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaFormat.MIMETYPE_VIDEO_AVC
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.lang.Exception
import java.lang.Runnable
import java.net.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


@ExperimentalCoroutinesApi
class MainActivity : AppCompatActivity(), LVSTCPManager.LVSTCPManagerInterface, LVSEncoder.LVSEncoderDelegate, LVSCameraManager.LVSCameraManagerDelegate {

    private var cameraView : SurfaceView? = null
    private var isPreviewSurfaceCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }

        LVSTCPManager.delegate = this
        LVSEncoder.delegate = this
        LVSTCPManager.startTCPManager(this)
        LVSCameraManager.delegate = this

        cameraView = findViewById(R.id.camera_view)

        cameraView!!.holder!!.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder) { isPreviewSurfaceCreated = true }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

            override fun surfaceDestroyed(p0: SurfaceHolder) = Unit
        })
    }

    override fun onDestroy() {
        super.onDestroy()

        LVSEncoder.delegate = null
        LVSTCPManager.delegate = null
        LVSCameraManager.delegate = null

        LVSTCPManager.stopTCPManager()
        LVSEncoder.stopEncoder()
        LVSDecoder.endDecoding()
        LVSCameraManager.stopCameraManager()

        cameraView!!.holder.surface.release()
    }

    private fun initializeCameraView() {
        if (isPreviewSurfaceCreated) cameraView?.post { LVSCameraManager.initializeCameraManager(this, AtomicReference(cameraView!!.holder.surface)) }
    }

    // LVSTCPMANAGER Interface Methods

    override fun connectedToHost() {
        initializeCameraView()
    }

    override fun startedToHost(sps: ByteArray, pps: ByteArray) {
        cameraView = findViewById(R.id.camera_view)
        LVSDecoder.initializeAndStartDecoder(AtomicReference(cameraView!!.holder.surface), sps, pps)
    }

    override fun retrievedData(byteArray: ByteArray) {
        LVSDecoder.decode(byteArray)
    }

    // LVSEncoderDelegate Interface Methods
    override fun onDataAvailable(byteBuffer: ByteBuffer) {
        LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoData ,byteBuffer)
    }

    // LVSCameraManager Interface Methods
    override fun cameraInitialized(encodingSurface: AtomicReference<Surface>) {
        LVSEncoder.initializeAndStartEncoder(encodingSurface.get())
    }


}