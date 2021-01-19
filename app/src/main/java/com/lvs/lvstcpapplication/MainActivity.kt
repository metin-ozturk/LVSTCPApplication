package com.lvs.lvstcpapplication

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
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
import com.lvs.lvstcpapplication.helpers.CameraView
import com.lvs.lvstcpapplication.helpers.LVSConstants
import com.lvs.lvstcpapplication.helpers.LVSTCPDataType
import com.lvs.lvstcpapplication.managers.LVSCameraManager
import com.lvs.lvstcpapplication.managers.LVSP2PManager
import com.lvs.lvstcpapplication.managers.LVSTCPManager
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference


@ExperimentalCoroutinesApi
class MainActivity : AppCompatActivity(), LVSTCPManager.LVSTCPManagerInterface,
    LVSEncoder.LVSEncoderDelegate, LVSCameraManager.LVSCameraManagerDelegate,
    LVSP2PManager.LVSP2PManagerDelegate{

    private var cameraView : SurfaceView? = null
    private var isPreviewSurfaceCreated = false

    private var isVideoConfigDataSent = false

    private var discoverPeersButton: Button? = null
    private var socketConnectionButton: Button? = null

    var encodingSurface: Surface? = null
    private var lvsCameraView: CameraView? = null

    private var isConnectedThroughTCP = false
    private var isTransactionOn = false

    private var isHost: Boolean? = null
        set(value) {
            Log.i("LVSRND", "IS HOST: $value")
            field = value
        }
    private var isTransmitter : Boolean? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE), 43)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 44)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_WIFI_STATE), 45)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CHANGE_WIFI_STATE), 46)
        }


        lvsCameraView = findViewById(R.id.lvs_camera_view)
        discoverPeersButton = findViewById(R.id.discover_peers_button)
        socketConnectionButton = findViewById(R.id.socket_connection_button)

        setDiscoverPeersButton()
        setSocketConnectionButton()

        LVSTCPManager.delegate = this
        LVSEncoder.delegate = this
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


        LVSP2PManager.disconnectFromPeerDevice()

        LVSP2PManager.initializeP2PConnection(this)

    }

    override fun onDestroy() {
        super.onDestroy()
//        LVSP2PManager.deinitializeP2pConnection()
//
//        multicastLock?.release()
//
//        LVSEncoder.delegate = null
//        LVSTCPManager.delegate = null
//        LVSCameraManager.delegate = null
//
//        if (isHost == true) {
//            LVSTCPManager.stopTCPManager()
//            LVSDecoder.endDecoding()
//        } else if (isHost == false) {
//            LVSCameraManager.stopCameraManager()
//            LVSEncoder.stopEncoder()
//        }
//
//        cameraView!!.holder.surface.release()
    }


    private fun initializeCameraView() {
        if (isPreviewSurfaceCreated) cameraView?.post { LVSCameraManager.initializeCameraManager(this, cameraView!!.holder.surface, encodingSurface) }
    }

    // LVSTCPManager Interface Methods

    override fun connectedToReceiver() {
        encodingSurface?.let {
            LVSEncoder.initializeAndStartEncoder(it)
        }
    }

    override fun startedToHost(sps: ByteArray, pps: ByteArray) {
        cameraView = findViewById(R.id.camera_view)
        LVSDecoder.initializeAndStartDecoder(cameraView!!.holder.surface, sps, pps)
    }

    override fun retrievedData(byteArray: ByteArray) {
        LVSDecoder.decode(byteArray)
    }

    // LVSEncoderDelegate Interface Methods
    override fun onDataAvailable(byteBuffer: ByteBuffer) {
        if (!isConnectedThroughTCP) return
        if (!isVideoConfigDataSent) {
            val byteBufferToBeSent = ByteBuffer.allocate(8)
            byteBufferToBeSent.putInt(LVSConstants.fps)
            byteBufferToBeSent.putInt(LVSConstants.bitRate)
            LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoConfigurationData, byteBufferToBeSent)
            isVideoConfigDataSent = true
        }

        val byteArray = byteBuffer.array()
        val dataLength = byteArray.count()

        val maxLoopCount = dataLength / LVSConstants.tcpPacketSize
        var loopCounter = 0

        while (loopCounter < maxLoopCount) {
            val videoDataArray = byteArray.sliceArray((loopCounter * LVSConstants.tcpPacketSize) until (loopCounter * LVSConstants.tcpPacketSize + LVSConstants.tcpPacketSize))
            LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoPartialData, ByteBuffer.wrap(videoDataArray))
            loopCounter++
        }

        val lastLoopByteSize = dataLength % LVSConstants.tcpPacketSize
        if (lastLoopByteSize > 0) {
            val lastVideoArray = byteArray.sliceArray((loopCounter * LVSConstants.tcpPacketSize) until (loopCounter * LVSConstants.tcpPacketSize + lastLoopByteSize))
            LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoPartialData, ByteBuffer.wrap(lastVideoArray))
        }

        LVSTCPManager.sendEncodedData(LVSTCPDataType.VideoPartialDataTransmissionCompleted, ByteBuffer.wrap(ByteArray(0)))

    }

    // LVSCameraManager Interface Methods
    override fun cameraInitialized(encodingSurface: Surface) {
        this.encodingSurface = encodingSurface
        LVSEncoder.initializeAndStartEncoder(encodingSurface)
    }


    private fun setDiscoverPeersButton() {
        discoverPeersButton?.setOnClickListener {
            if (LVSP2PManager.isConnectedToPeer) {
                LVSTCPManager.stopTCPManager()
                changeTransactionStatus()
                LVSP2PManager.disconnectFromPeerDevice()
            } else {
                LVSP2PManager.discoverPeers()
            }
        }
    }

    private fun setSocketConnectionButton() {
        socketConnectionButton?.setOnClickListener {
            changeTransactionStatus()
        }
    }

    private fun changeTransactionStatus() {
        if (isTransactionOn) {
            if (isTransmitter == true) {
                LVSCameraManager.stopCameraManager()
                isVideoConfigDataSent = false
            } else if (isTransmitter == false) LVSDecoder.endDecoding()
        } else if (isTransmitter == true) initializeCameraView()

        isTransactionOn = !isTransactionOn
    }


    override fun onConnectionStatusChanged(isConnected: Boolean) {
        discoverPeersButton?.text =  if (isConnected) "Disconnect From Peer" else "Discover Peers"

        isConnectedThroughTCP = isConnected
        isTransactionOn = isConnected

    }

    override fun isHost(isHost: Boolean, isTransmitter: Boolean) {
        this.isHost = isHost
        this.isTransmitter = isTransmitter

        if (isTransmitter) initializeCameraView()

        val bgScope = CoroutineScope(Dispatchers.IO)
        bgScope.launch {
            LVSTCPManager.startTCPManagerWithWifiDirect(this@MainActivity, LVSP2PManager.inetAddress)
        }
    }

}