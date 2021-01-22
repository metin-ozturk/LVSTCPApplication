package com.lvs.lvstcpapplication.managers

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lvs.lvstcpapplication.MainActivity
import com.lvs.lvstcpapplication.broadcastReceivers.LVSWifiDirectBroadcastReceiver
import java.net.InetAddress


object LVSP2PManager {

    interface LVSP2PManagerDelegate {
        fun onConnectionStatusChanged(isConnected: Boolean)
    }

    var delegate: LVSP2PManagerDelegate? = null

    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver : LVSWifiDirectBroadcastReceiver? = null
    private var wifiDirectReceiverIntentFilter: IntentFilter? = null

    private var deviceNames = arrayListOf<String>()
    private var devices = arrayListOf<WifiP2pDevice>()
    private var selectedDeviceIdx = -1

    var isTransmitter: Boolean? = null
    var isHost: Boolean? = null

    var isConnectedToPeer = false
        private set(value) {
            if (field != value && !value) {
                delegate?.onConnectionStatusChanged(false)
            }
            alertDialog?.dismiss()
            field = value
        }

    var inetAddress : InetAddress? = null

    private var isPeerDeviceAlertActive = false

    private var retrievedActivity: Activity? = null

    private var alertDialog : AlertDialog? = null

    val peerListListener : WifiP2pManager.PeerListListener by lazy {
        WifiP2pManager.PeerListListener { retrievedPeers ->

            if (retrievedPeers?.deviceList != devices && retrievedPeers?.deviceList?.isNullOrEmpty() == false && !isPeerDeviceAlertActive && !isConnectedToPeer) {
                isPeerDeviceAlertActive = true
                devices.clear()
                deviceNames.clear()

                retrievedPeers.deviceList.forEach { retrievedDevice ->
                    Log.i("LVSRND", "Found Peer: ${retrievedDevice.deviceName}")
                    if (deviceNames.contains(retrievedDevice.deviceName)) return@forEach
                    deviceNames.add(retrievedDevice.deviceName)
                    devices.add(retrievedDevice)
                }

                retrievedActivity?.let {
                    alertDialog = AlertDialog.Builder(it)
                            .setTitle("Select A Device To Connect and Stream to it?")
                            .setItems(deviceNames.toTypedArray()) { dialog, which ->
                                isTransmitter = true
                                selectedDeviceIdx = which
                                connectToPeerDevice()
                                dialog.dismiss()

                            }
                            .setNegativeButton("Cancel") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setOnDismissListener {
                                isPeerDeviceAlertActive = false
                            }.show()

                }
            }
        }
    }

    val connectionInfoListener : WifiP2pManager.ConnectionInfoListener by lazy {
        WifiP2pManager.ConnectionInfoListener {
            val isGroupOwner = it.groupFormed && it.isGroupOwner
            inetAddress = if (!isGroupOwner) it.groupOwnerAddress else null
            if (isTransmitter == null) isTransmitter = false
            isHost = isGroupOwner
            isConnectedToPeer = true
            delegate?.onConnectionStatusChanged(true)
        }
    }


    fun initializeP2PConnection(activity: Activity) {
        delegate = activity as MainActivity
        retrievedActivity = activity

        p2pManager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = p2pManager?.initialize(activity, activity.mainLooper, null)

        wifiDirectReceiver = LVSWifiDirectBroadcastReceiver(p2pManager!!, channel!!)

        wifiDirectReceiverIntentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        activity.registerReceiver(wifiDirectReceiver, wifiDirectReceiverIntentFilter)
    }

    fun deinitializeP2pConnection() {
        retrievedActivity?.let {
            disconnectFromPeerDevice()
            it.unregisterReceiver(wifiDirectReceiver)
        }
    }

    fun discoverPeers() {
        channel?.let {
            p2pManager?.discoverPeers(it, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("LVSRND", "P2P Discovery Started")
                }

                override fun onFailure(reason: Int) {
                    Log.e("LVSRND", "P2P Discovery Failed with reason code: $reason")
                }
            })
        }
    }

    private fun connectToPeerDevice() {

        if (devices.isEmpty() && selectedDeviceIdx != -1) return
        val config = WifiP2pConfig()
        config.deviceAddress = devices[selectedDeviceIdx].deviceAddress

        p2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2pManager?.stopPeerDiscovery(channel, null)
                isConnectedToPeer = true
                Log.i("LVSRND", "Connected to Peer Successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e("LVSRND", "Couldn't Connect to Peer")
            }
        })

    }

    fun disconnectFromPeerDevice(atStart: Boolean = false) {
        if (isConnectedToPeer || atStart) {
            isConnectedToPeer = false
            inetAddress = null
            isTransmitter = false

            p2pManager?.requestGroupInfo(channel) { group ->
                if (group != null) {
                    p2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d("LVSRND", "removeGroup onSuccess")
                        }

                        override fun onFailure(reason: Int) {
                            Log.d("LVSRND", "removeGroup onFailure - $reason")
                        }
                    })
                }
            }
        }

    }

}