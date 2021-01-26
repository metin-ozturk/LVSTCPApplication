package com.lvs.lvstcpapplication

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.opengl.Visibility
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lvs.lvstcpapplication.broadcastReceivers.LVSWifiDirectBroadcastReceiver
import com.lvs.lvstcpapplication.coders.LVSDecoder
import com.lvs.lvstcpapplication.coders.LVSEncoder
import com.lvs.lvstcpapplication.helpers.LVSConstants
import com.lvs.lvstcpapplication.helpers.LVSTCPDataType
import com.lvs.lvstcpapplication.helpers.LVSyncSoundTriggeringStatus
import com.lvs.lvstcpapplication.helpers.RecordingState
import com.lvs.lvstcpapplication.managers.LVSCameraManager
import com.lvs.lvstcpapplication.managers.LVSP2PManager
import com.lvs.lvstcpapplication.managers.LVSTCPManager
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

@ExperimentalCoroutinesApi
class MainActivity : AppCompatActivity(), LVSTCPManager.LVSTCPManagerInterface, LVSCameraManager.LVSCameraManagerDelegate,
    LVSP2PManager.LVSP2PManagerDelegate{

    private var cameraView : SurfaceView? = null
    private var videoView: VideoView? = null
    private var isPreviewSurfaceCreated = false


    private var discoverPeersButton: Button? = null
    private var socketConnectionButton: Button? = null
    private var recordingButton: Button? = null

    var encodingSurface: Surface? = null

    private var multicastLock: WifiManager.MulticastLock? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
        ){
            ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
            ), 42)
        }


        discoverPeersButton = findViewById(R.id.discover_peers_button)
        socketConnectionButton = findViewById(R.id.socket_connection_button)
        recordingButton = findViewById(R.id.record_button)
        videoView = findViewById(R.id.video_view)

        setDiscoverPeersButton()
        setSocketConnectionButton()
        setRecordingButton()

        LVSTCPManager.delegate = this
        LVSCameraManager.delegate = this

        val wifiManager = this.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        cameraView = findViewById(R.id.camera_view)

        cameraView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder) {
                isPreviewSurfaceCreated = true
            }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

            override fun surfaceDestroyed(p0: SurfaceHolder) = Unit
        })


        LVSP2PManager.initializeP2PConnection(this)

    }



    // LVSTCPManager Interface Methods

    override fun connectedToReceiver() {
        if (LVSP2PManager.isTransmitter == false) return
        encodingSurface?.let {
            LVSEncoder.initializeAndStartEncoder(it)
        }
    }

    override fun startedToHost(sps: ByteArray, pps: ByteArray) {
        if (LVSP2PManager.isTransmitter == true) return
        LVSDecoder.initializeAndStartDecoder(cameraView!!.holder.surface, sps, pps)
    }

    override fun retrievedData(byteArray: ByteArray) {
        LVSDecoder.decode(byteArray)
    }

    override fun recordedVideoTransmissionFinalized(file: File) = CoroutineScope(Dispatchers.Main).launch {
        LVSDecoder.endDecoding()

        cameraView?.visibility = View.GONE
        videoView?.visibility = View.VISIBLE

        val video = Uri.parse(file.absolutePath)
        videoView?.setVideoURI(video)
        videoView?.setOnPreparedListener {
            it.isLooping = false
            videoView?.start()
        }

        videoView?.setOnCompletionListener {
            it.stop()
            it.reset()
            videoView?.stopPlayback()

            cameraView?.visibility = View.VISIBLE
            videoView?.visibility = View.GONE

            LVSDecoder.initializeAndStartDecoder(cameraView!!.holder.surface)
        }
    }

    override fun recordingStateChanged(state: Boolean) {
        recordingButton?.text = if (state) "Stop Recording" else "Start Recording"

        LVSP2PManager.toggleRecordingStatus()
    }

    override fun streamStatusChanged(isTransactionOn: Boolean) = CoroutineScope(Dispatchers.Main).launch {
        socketConnectionButton?.text =  if (!LVSP2PManager.isTransactionOn) "Stop Stream" else "Start Stream"

        changeStreamStatus()

        LVSP2PManager.setTransactionStatus(isTransactionOn)
    }

    override fun soundTriggeringInfoRetrieved(state: LVSyncSoundTriggeringStatus, beforeDuration: Int?, afterDuration: Int?) {

    }

    // LVSCameraManager Interface Methods
    override fun cameraInitialized(encodingSurface: Surface) {
        this.encodingSurface = encodingSurface
        LVSEncoder.initializeAndStartEncoder(encodingSurface)
    }

    // LVSP2PManager Interface Methods

    override fun onConnectionStatusChanged(isConnected: Boolean) {
        discoverPeersButton?.text =  if (isConnected) "Disconnect From Peer" else "Discover Peers"

        socketConnectionButton?.visibility = if (isConnected) View.VISIBLE else View.GONE
        socketConnectionButton?.text = "Stop Stream"

        recordingButton?.text = "Start Recording"
        recordingButton?.visibility = if (isConnected) View.VISIBLE else View.GONE

        if (!isConnected) {
            encodingSurface = null
        } else if (LVSP2PManager.isTransmitter == true && isPreviewSurfaceCreated) {
            cameraView?.post { LVSCameraManager.initializeCameraManager(this, cameraView!!.holder.surface, encodingSurface) }
        }

    }

    override fun transactionStatusChanged(status: Boolean) {
        recordingButton?.visibility = if (status) View.VISIBLE else View.GONE
    }


    // Listeners and Synchronization Methods

    private fun setDiscoverPeersButton() {
        discoverPeersButton?.setOnClickListener {
            if (LVSP2PManager.isConnectedToPeer) LVSP2PManager.disconnectFromPeerDevice() else LVSP2PManager.discoverPeers()
        }
    }

    private fun setSocketConnectionButton() {
        socketConnectionButton?.setOnClickListener {
            socketConnectionButton?.text =  if (!LVSP2PManager.isTransactionOn) "Stop Stream" else "Start Stream"
            changeStreamStatus()
            LVSP2PManager.setTransactionStatus()
            sendStreamStatus()
        }
    }

    private fun changeStreamStatus() {
        if (LVSP2PManager.isTransactionOn && LVSP2PManager.isTransmitter == true) {
            LVSCameraManager.stopCameraManager()
            LVSP2PManager.changeVideoConfigDataTransmissionStatus(false)
        } else if (LVSP2PManager.isTransmitter == true && isPreviewSurfaceCreated) {
            cameraView?.post { LVSCameraManager.initializeCameraManager(this@MainActivity, cameraView!!.holder.surface, encodingSurface) }
        }

    }



    private fun setRecordingButton() {
        recordingButton?.setOnClickListener {
            if (!LVSP2PManager.isTransactionOn) return@setOnClickListener

            recordingButton?.text = if (!LVSP2PManager.isRecordingOn) "Stop Recording" else "Start Recording"
            sendRecordRequest(if (!LVSP2PManager.isRecordingOn) RecordingState.Start else RecordingState.Stop)
            LVSP2PManager.toggleRecordingStatus()
        }
    }


    private fun sendRecordRequest(state: RecordingState) {
        val recordState = state.value
        val byteBufferToBeSent = ByteBuffer.allocate(4).putInt(recordState)
        val bgScope = CoroutineScope(Dispatchers.IO)
        bgScope.launch {
            LVSTCPManager.sendEncodedData(LVSTCPDataType.RecordingData, ByteBuffer.wrap(byteBufferToBeSent.array()))
            cancel()
        }
    }

    private fun sendStreamStatus() = CoroutineScope(Dispatchers.IO).launch {
        val byteBufferToBeSent = ByteBuffer.allocate(4)
        byteBufferToBeSent.putInt(if (LVSP2PManager.isTransactionOn) 1 else 0)
        LVSTCPManager.sendEncodedData(LVSTCPDataType.StreamStatus, ByteBuffer.wrap(byteBufferToBeSent.array()))
    }

    fun sendSoundTriggeringInfoToTransmitter(status: LVSyncSoundTriggeringStatus, beforeDuration: Int? = null, afterDuration: Int? = null) {
        val statusValue = status.value

        val byteBufferToBeSent : ByteBuffer = if (status == LVSyncSoundTriggeringStatus.Ended) {
            val beforeDur = beforeDuration ?: 0
            val afterDur = afterDuration ?: 0

            ByteBuffer.allocate(12)
                    .putInt(statusValue)
                    .putInt(beforeDur)
                    .putInt(afterDur)
        } else {
            ByteBuffer.allocate(4)
                    .putInt(statusValue)
        }

        val bgScope = CoroutineScope(Dispatchers.IO)
        bgScope.launch {
            LVSTCPManager.sendEncodedData(LVSTCPDataType.SoundTriggerData, ByteBuffer.wrap(byteBufferToBeSent.array()))
            cancel()
        }

    }

}