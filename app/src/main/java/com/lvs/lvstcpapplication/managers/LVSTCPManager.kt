package com.lvs.lvstcpapplication.managers

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.lvs.lvstcpapplication.helpers.LVSConstants
import com.lvs.lvstcpapplication.helpers.LVSTCPDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LVSTCPManager {

    interface LVSTCPManagerInterface {
        fun connectedToHost()
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

    private var discoveryManager: NsdManager? = null
    private var isHost = false

    private var fileOutputStream : BufferedOutputStream? = null
    private var retrievedContext: Context? = null

    private var partialVideoDataArray: ByteArray? = null

    private val discoveryListener = object: NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
            Log.i("LVSRND", "Start Discovery Failed: $p1")
            discoveryManager?.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
            Log.i("LVSRND", "Stop Discovery Failed: $p1")
            discoveryManager?.stopServiceDiscovery(this)

        }

        override fun onDiscoveryStarted(p0: String?) {
            Log.i("LVSRND", "Discovery Started")
        }

        override fun onDiscoveryStopped(p0: String?) {
            Log.i("LVSRND", "Discovery Stopped")
        }

        override fun onServiceFound(service: NsdServiceInfo?) {
            Log.i("LVSRND", "Service Found")

            discoveryManager?.resolveService(service, resolveListener)
        }

        override fun onServiceLost(p0: NsdServiceInfo?) {
            Log.i("LVSRND", "Service Lost! :o")
        }
    }

    private val resolveListener = object: NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("LVSRND", "Resolve failed: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.e("LVSRND", "Resolve Succeeded. $serviceInfo")

            clientSocket?.let {
                Log.i("LVSRND", "Socket already connected $it")
                return
            }

            try {
                // Connect to the host
                clientSocket = Socket(serviceInfo.host, serviceInfo.port)
                outputStream = DataOutputStream(clientSocket?.getOutputStream())

                val mainScope = CoroutineScope(Dispatchers.Main)
                mainScope.launch {
                    delegate?.connectedToHost()
                    mainScope.cancel()
                }

            } catch (e: UnknownHostException) {
                Log.e("LVSRND", "Unknown host. ${e.localizedMessage}")
            } catch (e: Exception) {
                Log.e("LVSRND", "Service Resolve - exception thrown: ${e.localizedMessage}")
            }


        }
    }

    private val registrationListener = object:  NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {

            Log.d("LVSRND", "Discovery service registered")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.d("LVSRND", "Discovery service registration failed")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Log.d("LVSRND", "Discovery service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.d("LVSRND", "Discovery service unregistration failed")
        }

    }

    fun startTCPManager(context: Context, asHost: Boolean) {
        isHost = asHost
        discoveryManager = getSystemService(context, NsdManager::class.java)
        retrievedContext = context

        if (asHost) checkIfDiscoveredAndConnectedToASocket() else discoverHosts()

    }

    fun stopTCPManager() {
        inputStream?.close()
        outputStream?.close()
        serverSocket?.close()
        clientSocket?.close()

        if (isHost) discoveryManager?.unregisterService(registrationListener) else discoveryManager?.stopServiceDiscovery(discoveryListener)
    }

    fun sendEncodedData(dataType: LVSTCPDataType, byteBuffer: ByteBuffer) {
        Log.d("LVSRND", "Data Type: $dataType Data Length: ${byteBuffer.capacity()}")
        val dataLength = byteBuffer.capacity()
        outputStream?.writeInt(dataType.value)
        outputStream?.writeInt(dataLength)
        outputStream?.write(byteBuffer.array())
    }

    private fun discoverHosts() {
        discoveryManager?.discoverServices("_LVSTcpApp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }


    private fun checkIfDiscoveredAndConnectedToASocket() {
        val port: Int
        serverSocket = ServerSocket(0).also { socket ->
            // Store the chosen port.
            port = socket.localPort
        }

        listenForConnections()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LVSTcpApp"
            serviceType = "_LVSTcpApp._tcp"
            setPort(port)
        }

        discoveryManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

    }

    private fun listenForConnections() {
        Thread{
            while (serverSocket != null) {
                try {
                    serverSocket?.accept()?.let {
                        Log.d("LVSRND", "Accepted client")
                        connectedClients.add(it)

                        inputStream = DataInputStream(it.getInputStream())

                        try {
                            while (true) {
                                val dataType = inputStream?.readInt() ?: continue
                                val dataLength = inputStream?.readInt() ?: continue

                                when (dataType) {

                                    LVSTCPDataType.VideoPartialData.value -> {
                                        Log.i("LVSRND", "Received Partial Video Data. Length: $dataLength")
                                        val data = ByteArray(dataLength)
                                        inputStream?.readFully(data)

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
                                        val data = partialVideoDataArray!!

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

                                    LVSTCPDataType.RecordingData.value -> Log.i("LVSRND", "Received Recording Data.")
                                    LVSTCPDataType.DrawingData.value -> Log.i("LVSRND", "Received Drawing Data.")
                                    LVSTCPDataType.VideoConfigurationData.value -> {
                                        LVSConstants.fps = inputStream?.readInt() ?: continue
                                        LVSConstants.bitRate = inputStream?.readInt() ?: continue
                                        Log.d("LVSRND", "Received Video Configuration Data; FPS: ${LVSConstants.fps} Bitrate: ${LVSConstants.bitRate}.")
                                    }

                                    LVSTCPDataType.RecordedVideoInProgress.value -> {
                                        Log.d("LVSRND", "Received Recorded Video Packet")

                                        if (fileOutputStream == null) {
                                            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                                            val retrievedFile = File(retrievedContext?.filesDir, "RETRIEVED_${sdf.format(Date())}.mp4")
                                            fileOutputStream = BufferedOutputStream(FileOutputStream(retrievedFile))
                                        }

                                        val retrievedData = ByteArray(dataLength)
                                        inputStream?.readFully(retrievedData)
                                        fileOutputStream?.write(retrievedData)
                                    }

                                    LVSTCPDataType.RecordedVideoEnded.value -> {
                                        Log.d("LVSRND", "Recorded Video Transmission Ended")
                                        fileOutputStream?.close()
                                    }
                                    else -> Log.i("LVSRND", "Received unknown data type.")
                                }



                            }
                        } catch (e: EOFException) {
                            Log.i("LVSRND", "End of file reached.")
                        }

                    }
                } catch (e: SocketException) {
                    Log.i("LVSRND", "Error while listening for connections: ${e.localizedMessage}")
                    break
                } catch (e: EOFException) {
                    Log.i("LVSRND", "Reached EOF")
                }
            }
        }.start()
    }
}