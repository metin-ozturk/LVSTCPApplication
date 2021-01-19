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
    }

    var delegate: LVSTCPManagerInterface? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private var inputStream: DataInputStream? = null

    private var outputStream: DataOutputStream? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private var connectedClients: MutableList<Socket> = CopyOnWriteArrayList()

    private var isHost = false

    private var retrievedContext: Context? = null

    private var partialVideoDataArray: ByteArray? = null
    private var listeningForConnectionsThread: Thread? = null

    fun startTCPManagerWithWifiDirect(context: Context, inetAddress: InetAddress?) {
        isHost = inetAddress == null
        retrievedContext = context

        if (isHost) {
            this.serverSocket = ServerSocket(1789)
            listenForConnections()
            outputStream = connectedClients.firstOrNull()?.let { DataOutputStream(it.getOutputStream()) }

        } else {
            while (true) {
                val result = connectToHost(inetAddress)
                Log.i("OSMAN", "RESULT: $result")
                if (result) break
            }

        }

    }

    private fun connectToHost(inetAddress: InetAddress?): Boolean {
        return try {
            this.clientSocket = Socket(inetAddress, 1789)
            listenForConnections(false)
            outputStream = clientSocket?.let { DataOutputStream(it.getOutputStream()) }
            Log.i("LVSRND", "Connected to Host")
            true
        } catch (exc: ConnectException) {
            Log.e("LVSRND", "Error while connecting to Host ${exc.localizedMessage}")
            false
        }
    }


    fun stopTCPManager() {
        listeningForConnectionsThread?.interrupt()

        inputStream?.close()
        outputStream?.close()
        serverSocket?.close()
        clientSocket?.close()

        inputStream = null
        outputStream = null
        serverSocket = null
        clientSocket = null

//        if (isHost) discoveryManager?.unregisterService(registrationListener)
//        if (isHost) discoveryManager?.unregisterService(registrationListener) else discoveryManager?.stopServiceDiscovery(discoveryListener)
    }

    fun sendEncodedData(dataType: LVSTCPDataType, byteBuffer: ByteBuffer) = runBlocking(Dispatchers.IO) {
        Log.d("LVSRND", "Data Type: $dataType Data Length: ${byteBuffer.capacity()}")
        val dataLength = byteBuffer.capacity()

        (0..2).forEach { _ ->
            outputStream?.writeInt(dataType.value)
            outputStream?.writeInt(dataLength)
        }

        outputStream?.write(byteBuffer.array())
    }


    @Synchronized
    private fun listenForConnections(isHost: Boolean = true) {
        listeningForConnectionsThread = Thread {
            if (isHost) {
                while (serverSocket != null) {
                    if (serverSocket?.isClosed == true) continue
                    try {
                        serverSocket?.accept()?.let { sSocket ->
                            Log.i("LVSRND", "Accepted client $sSocket")
                            connectedClients.add(sSocket)

                            inputStream = connectedClients.firstOrNull()?.let { DataInputStream(it.getInputStream()) }
                            getInputStream()

                            if (LVSP2PManager.isTransmitter) {
                                val mainScope = CoroutineScope(Dispatchers.Main)
                                mainScope.launch {
                                    Log.i("OSMAN", "CONNECTED TO HOST")
                                    delegate?.connectedToReceiver()
                                    mainScope.cancel()
                                }
                            }
                        }
                    } catch (e: SocketException) {
                        Log.e(
                                "LVSRND",
                                "Socket Error while listening for connections: ${e.localizedMessage}"
                        )
                    } catch (e: Exception) {
                        Log.e("LVSRND", "Error while listening for connections: ${e.localizedMessage}")
                    }
                }
            } else {
                while (clientSocket != null) {
                    if (clientSocket?.isClosed == true) continue

                    inputStream = clientSocket?.let { DataInputStream(it.getInputStream()) }
                    Thread {
                        getInputStream()
                    }.start()
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
                val dataTypeArrayList = arrayListOf<Int>()
                val dataLengthArrayList = arrayListOf<Int>()

                var dataType : Int = -1
                var dataLength: Int = -1

                (0..2).forEach { _ ->
                    val retrievedDataType = inputStream?.readInt() ?: -2
                    val retrievedDataLength = inputStream?.readInt() ?: -2

                    if ((dataTypeArrayList.count() == 1 && retrievedDataType == dataTypeArrayList.first())
                        || (dataTypeArrayList.count() == 2  && (retrievedDataType == dataTypeArrayList.first() || retrievedDataType == dataTypeArrayList[1]))) {
                        dataType = retrievedDataType
                    }

                    if ((dataLengthArrayList.count() == 1 && retrievedDataLength == dataLengthArrayList.first())
                        || (dataLengthArrayList.count() == 2  && (retrievedDataLength == dataLengthArrayList.first() || retrievedDataLength == dataLengthArrayList[1]))){
                        dataLength = retrievedDataLength
                    }

                    dataTypeArrayList.add(retrievedDataType)
                    dataLengthArrayList.add(retrievedDataLength)
                }


                if ( dataType !in (1..5) || dataLength !in (0..4096)) {
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
                        Log.i("LVSRND", "Video Data Transmission Completed. Length: $dataLength")

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
                        LVSConstants.fps = inputStream?.readInt() ?: continue
                        LVSConstants.bitRate = inputStream?.readInt() ?: continue
                        Log.d("LVSRND", "Received Video Configuration Data; FPS: ${LVSConstants.fps} Bitrate: ${LVSConstants.bitRate}.")
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