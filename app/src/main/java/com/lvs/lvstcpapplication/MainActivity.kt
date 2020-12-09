package com.lvs.lvstcpapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lvs.lvstcpapplication.coders.LVSDecoder
import com.lvs.lvstcpapplication.coders.LVSEncoder
import com.lvs.lvstcpapplication.helpers.CameraView
import com.lvs.lvstcpapplication.managers.LVSCameraManager
import com.lvs.lvstcpapplication.managers.LVSTCPManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

interface LVSCameraListener {
    fun onConnected()
    fun onDisconnected()
    fun onReceiveFrame(bitmap: Bitmap?)
}

@ExperimentalCoroutinesApi
class MainActivity : AppCompatActivity(), LVSTCPManager.LVSTCPManagerInterface,
    LVSEncoder.LVSEncoderDelegate, LVSCameraManager.LVSCameraManagerDelegate,
    LVSCameraClient.OnCameraNetworkListener {

    private var cameraView : SurfaceView? = null
    private var isPreviewSurfaceCreated = false

    private var isBeingRecorded = false
    private var isVideoConfigDataSent = false

    private var receiverButton : Button? = null
    private var transmitterButton: Button? = null
    private var recordButton: Button? = null

    private var lvsCameraClient : LVSCameraClient? = null
    private var lvsCameraListener: LVSCameraListener? = null
    private var lvsCameraView: CameraView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }

        recordButton = findViewById(R.id.record_button)
        transmitterButton = findViewById(R.id.transmitter_button)
        receiverButton = findViewById(R.id.receiver_button)
        lvsCameraView = findViewById(R.id.lvs_camera_view)

        setRecordButtonListener()
        setTransmitterButtonListener()
        setReceiverButtonListener()

        LVSTCPManager.delegate = this
        LVSEncoder.delegate = this
        LVSCameraManager.delegate = this

        lvsCameraClient = LVSCameraClient.connectToCamera(application.applicationContext)
        lvsCameraClient?.setOnCameraNetworkListener(this)

        cameraView = findViewById(R.id.camera_view)

        cameraView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder) { isPreviewSurfaceCreated = true }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

            override fun surfaceDestroyed(p0: SurfaceHolder) = Unit
        })

    }

    override fun onStop() {
        super.onStop()

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

    // LVSTCPManager Interface Methods

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
        if (!isVideoConfigDataSent) {
            val byteBufferToBeSent = ByteBuffer.allocate(8)
            byteBufferToBeSent.putInt(LVSConstants.fps)
            byteBufferToBeSent.putInt(LVSConstants.bitRate)
            LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoConfigurationData, byteBufferToBeSent)
            isVideoConfigDataSent = true
        }
        LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoData ,byteBuffer)
    }

    // LVSCameraManager Interface Methods
    override fun cameraInitialized(encodingSurface: AtomicReference<Surface>) {
        LVSEncoder.initializeAndStartEncoder(encodingSurface.get())
    }

    // LVSCameraClient Interface Methods

    override fun onConnect() {
        Log.d("LVSCamera", "onConnect")
        this.lvsCameraListener?.onConnected()
    }

    override fun onDisconnect() {
        Log.d("LVSCamera", "onDisconnect")
        this.lvsCameraListener?.onDisconnected()
    }

    override fun onGetData(data: ByteArray?, bmp: Bitmap?) {
        //Log.d("LVSCamera", "Receive Frame")

        bmp?.let {
            runOnUiThread {
                lvsCameraView?.displayFrame(it)
            }
        }

    }

    override fun onClose() {
        Log.d("LVSCamera", "onClose")
        this.lvsCameraListener?.onDisconnected()
    }

    override fun cantConnect() {
        Log.d("LVSCamera", "cantConnect")
    }

    override fun cantReceiveImage() {
        Log.d("LVSCamera", "cantReceiveImage")
    }

    override fun onCameraSelected() {
        Log.d("LVSCamera", "onCameraSelected")
    }


    private fun setRecordButtonListener() {
        recordButton?.setOnClickListener {
            if (!isBeingRecorded) {
                recordButton?.text = "Stop Recording"
                LVSCameraManager.startRecording()
            } else {
                recordButton?.text = "Start Recording"
                LVSCameraManager.stopRecording()
            }
            isBeingRecorded = !isBeingRecorded
        }
    }

    private fun setTransmitterButtonListener() {
        transmitterButton?.setOnClickListener {
            transmitterButton?.visibility = View.GONE
            receiverButton?.visibility = View.GONE
            recordButton?.visibility = View.VISIBLE

            val streamingFPSArray = arrayOf("15", "20", "30")
            val streamingBitrateArray = arrayOf("4MB", "1MB", "15KB")
            val alertBuilder = AlertDialog.Builder(this)
            alertBuilder.setTitle("Choose Streaming FPS:")
            alertBuilder.setItems(streamingFPSArray) { dialog, which ->
                LVSConstants.fps = when(which) {
                    0 -> 15
                    1 -> 20
                    else -> 30
                }

                val bitrateAlertBuilder = AlertDialog.Builder(this)
                bitrateAlertBuilder.setItems(streamingBitrateArray) { sDialog, sWhich ->
                    LVSConstants.bitRate = when(sWhich) {
                        0 -> LVSConstants.width * LVSConstants.height * 3
                        1 -> (LVSConstants.width / 2) * (LVSConstants.height / 2) * 3
                        else -> (LVSConstants.width / 4) * (LVSConstants.height / 4)
                    }
                    LVSTCPManager.startTCPManager(this, false)
                    sDialog.dismiss()
                }

                bitrateAlertBuilder.create().show()
                dialog.dismiss()
            }

            alertBuilder.create().show()
        }
    }

    private fun setReceiverButtonListener() {
        receiverButton?.setOnClickListener {
            transmitterButton?.visibility = View.GONE
            receiverButton?.visibility = View.GONE

            lvsCameraView?.visibility = View.VISIBLE
            lvsCameraClient?.startCapture()

            LVSTCPManager.startTCPManager(this, true)
        }
    }

}