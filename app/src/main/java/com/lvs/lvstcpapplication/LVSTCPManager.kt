package com.lvs.lvstcpapplication

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.lang.Exception
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.ByteBuffer
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

    private var hostAfterJob: Job? = null

    private var discoveryManager: NsdManager? = null

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
            Log.i("LVSRND", "Discovery Started")
        }

        override fun onServiceFound(service: NsdServiceInfo?) {
            hostAfterJob?.cancel()
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

    fun startTCPManager(context: Context) {
        discoveryManager = getSystemService(context, NsdManager::class.java)
        discoverHosts()

        hostAfterJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000)
            checkIfDiscoveredAndConnectedToASocket()
            hostAfterJob?.cancel()
        }

    }

    fun stopTCPManager() {
        inputStream?.close()
        outputStream?.close()
        serverSocket?.close()
        clientSocket?.close()
        discoveryManager?.stopServiceDiscovery(discoveryListener)
        discoveryManager?.unregisterService(registrationListener)
    }

    fun sendEncodedData(dataType: LVSTCPDataType, byteBuffer: ByteBuffer) {
        outputStream?.writeInt(dataType.value)
        outputStream?.writeInt(byteBuffer.limit())
        outputStream?.write(byteBuffer.array())
    }

    private fun discoverHosts() {
        discoveryManager?.discoverServices("_LVSTcpApp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }


    private fun checkIfDiscoveredAndConnectedToASocket() {
        discoveryManager?.stopServiceDiscovery(discoveryListener)

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
                                val data = ByteArray(dataLength)
                                inputStream?.readFully(data)

                                when (dataType) {
                                    LVSTCPDataType.VideoData.value -> {
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
                                    }

                                    LVSTCPDataType.RecordingData.value -> Log.i("LVSRND", "Received Recording Data.")
                                    LVSTCPDataType.DrawingData.value -> Log.i("LVSRND", "Received Drawing Data.")
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