package com.lvs.lvstcpapplication.managers

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.lvs.lvstcpapplication.helpers.LVSConstants
import com.lvs.lvstcpapplication.helpers.LVSTCPDataType
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LVSTCPManager {

    interface LVSTCPManagerInterface {
        fun connectedToReceiver()
        fun startedToHost(sps: ByteArray, pps: ByteArray)
        fun retrievedData(byteArray: ByteArray)
        fun streamStatusChanged(isTransactionOn: Boolean): Job
    }

    var delegate: LVSTCPManagerInterface? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private var inputStream: DataInputStream? = null

    private var outputStream: DataOutputStream? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private var isHost = false

    private var retrievedContext: Context? = null

    private var partialVideoDataArray: ByteArray? = null
    private var listeningForConnectionsThread: Thread? = null

    fun startTCPManagerWithWifiDirect(context: Context, inetAddress: InetAddress?) {
        isHost = inetAddress == null
        retrievedContext = context

        if (isHost) {
            beHost()
        } else {
            while (true) {
                val result = connectToHost(inetAddress)
                if (result) break
            }

        }

    }

    private fun beHost() {
        try {
            this.serverSocket = ServerSocket().apply {
                reuseAddress = true
                soTimeout = 0
                bind(InetSocketAddress(1789))
            }
            listenForConnections()
            Log.i("LVSRND", "Being Host")
        } catch (exc: BindException) {
            Log.w("LVSRND", "Bind Exception While Trying to be a host! ${exc.localizedMessage}")
            beHost()
        }

    }

    private fun connectToHost(inetAddress: InetAddress?): Boolean {
        return try {
            this.clientSocket = Socket(inetAddress, 1789).apply {
                reuseAddress = true
                soTimeout = 0
            }

            inputStream = clientSocket?.let { DataInputStream(it.getInputStream()) }
            outputStream = clientSocket?.let { DataOutputStream(it.getOutputStream()) }

            listenForConnections(false)

            Log.i("LVSRND", "Connected to Host")
            true
        } catch (exc: ConnectException) {
            Log.e("LVSRND", "Error while connecting to Host ${exc.localizedMessage}")
            false
        }
    }


    fun stopTCPManager() {
        Log.w("OSMAN", "stopTCPManagerCalled")
        listeningForConnectionsThread?.interrupt()

        inputStream?.close()
        outputStream?.close()
        serverSocket?.close()
        clientSocket?.close()

        inputStream = null
        outputStream = null
        serverSocket = null
        clientSocket = null

        isHost = false

        sps = null
        pps = null
        partialVideoDataArray = null
//        if (isHost) discoveryManager?.unregisterService(registrationListener)
//        if (isHost) discoveryManager?.unregisterService(registrationListener) else discoveryManager?.stopServiceDiscovery(discoveryListener)
    }

    fun sendEncodedData(dataType: LVSTCPDataType, byteBuffer: ByteBuffer) {
        Log.d("LVSRND", "Data Type: $dataType Data Length: ${byteBuffer.capacity()}")
        val dataLength = byteBuffer.capacity()
        val mergedByteBuffer = ByteBuffer.allocate(8 + dataLength)

        mergedByteBuffer.putInt(dataType.value)
        mergedByteBuffer.putInt(dataLength)
        mergedByteBuffer.put(byteBuffer)

        try {
            outputStream?.write(mergedByteBuffer.array())
        } catch (exc: SocketException) {
            Log.d("LVSRND", "Socket is closed while attempting to send encoded data: ${exc.localizedMessage}")
        }
    }


    @Synchronized
    private fun listenForConnections(isHost: Boolean = true) {
        Log.d("LVSRND", "LISTENING FOR CONNECTIONS")
        listeningForConnectionsThread = Thread {
            if (isHost) {
                while (serverSocket != null) {
                    if (serverSocket?.isClosed == true) continue
                    if (listeningForConnectionsThread?.isInterrupted == true) return@Thread
                    try {
                        serverSocket?.accept()?.let { sSocket ->
                            Log.i("LVSRND", "Accepted client $sSocket")

                            inputStream =  DataInputStream(sSocket.getInputStream())
                            outputStream = DataOutputStream(sSocket.getOutputStream())
                            getInputStream()

                            if (LVSP2PManager.isTransmitter == true) {
                                val mainScope = CoroutineScope(Dispatchers.Main)
                                mainScope.launch {
                                    delegate?.connectedToReceiver()
                                    mainScope.cancel()
                                }
                            }

                        }

                    } catch (e: SocketException) {
                        Log.e(
                                "LVSRND",
                                "Socket Error while listening for connections: ${e.localizedMessage} ${e}"
                        )
                        listeningForConnectionsThread?.interrupt()
                    }
                }
            } else {
                while (clientSocket != null) {
                    if (clientSocket?.isClosed == true) continue

                    getInputStream()
                    break
                }
            }
        }

        listeningForConnectionsThread?.start()
    }

    @Synchronized
    private fun getInputStream() {
        try {
            while (true) {
                val dataType = inputStream?.readInt() ?: -1
                val dataLength = inputStream?.readInt() ?: -1

                if (dataType !in (1..6) || dataLength !in (0..LVSConstants.tcpPacketSize)) {
                    Log.e("LVSRND", "ERROR IN RETRIEVED DATA $dataType $dataLength")
                    continue
                }

                when (dataType) {
                    LVSTCPDataType.VideoPartialData.value -> {
                        Log.i("LVSRND", "Received Partial Video Data. Length: $dataLength")
                        val data = ByteArray(dataLength)
                        inputStream?.readFully(data, 0, dataLength)

                        if (partialVideoDataArray == null) {
                            partialVideoDataArray = data
                        } else {
                            partialVideoDataArray?.let { pVideoData ->
                                val outputStream = ByteArrayOutputStream()
                                outputStream.write(pVideoData)
                                outputStream.write(data)

                                val mergedArray = outputStream.toByteArray()
                                partialVideoDataArray = mergedArray
                            }

                        }

                    }

                    LVSTCPDataType.VideoPartialDataTransmissionCompleted.value -> {
                        Log.i("LVSRND", "Retrieval Video Data Completed.")

                        val data = partialVideoDataArray ?: return

                        when {
                            pps != null -> {
                                delegate?.retrievedData(data)
                            }
                            sps == null -> {
                                sps = data
                            }
                            pps == null -> {
                                pps = data
                                delegate?.startedToHost(sps!!, pps!!)
                            }
                        }

                        partialVideoDataArray = null
                    }

                    LVSTCPDataType.VideoConfigurationData.value -> {
                        LVSConstants.fps = inputStream?.readInt() ?: -1
                        LVSConstants.bitRate = inputStream?.readInt() ?: -1
                        Log.d("LVSRND", "Received Video Configuration Data; FPS: ${LVSConstants.fps} Bitrate: ${LVSConstants.bitRate}.")
                    }

                    LVSTCPDataType.StreamStatus.value -> {
                        val streamStatus = inputStream?.readInt() ?: continue
                        Log.i("LVSRND", "Retrieved Stream Status $streamStatus")
                        delegate?.streamStatusChanged(streamStatus == 1)
                    }

                    else -> Log.i("LVSRND", "Received unknown data type. Type: $dataType Length: $dataLength")
                }



            }
        } catch (socketException: SocketException) {
            Log.w("LVSRND", "Socket is closed: ${socketException.localizedMessage}")
        } catch (e: EOFException) {
            Log.i("LVSRND", "End of file reached.")
        }
    }

}