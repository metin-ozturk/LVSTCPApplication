package com.lvs.lvstcpapplication.broadcastReceivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.lvs.lvstcpapplication.MainActivity
import com.lvs.lvstcpapplication.managers.LVSP2PManager

class LVSWifiDirectBroadcastReceiver(private val manager: WifiP2pManager, private val channel : WifiP2pManager.Channel) : BroadcastReceiver() {


    override fun onReceive(context: Context?, intent: Intent?) {

        when(intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                Log.i("LVSRND", "WIFI_P2P_STATE_CHANGED_ACTION called")
                when (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                        Log.i("LVSRND", "P2P is enabled")
                        manager.requestPeers(channel, LVSP2PManager.peerListListener)
                    }
                    else -> {
                        Log.e("LVSRND", "P2P is disabled")
                        // Wi-Fi P2P is not enabled
                    }
                }

            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.i("LVSRND", "WIFI_P2P_PEERS_CHANGED_ACTION called")
                manager.requestPeers(channel, LVSP2PManager.peerListListener)
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {

                val networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo

                Log.i("LVSRND", "WIFI_P2P_CONNECTION_CHANGED_ACTION called $networkInfo")

                if (networkInfo?.isConnected == true) {
                    manager.requestConnectionInfo(channel, LVSP2PManager.connectionInfoListener)
                } else if (networkInfo?.isConnected == false) {
                    LVSP2PManager.disconnectFromPeerDevice()
                }

            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.i("LVSRND", "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION called")

            }

        }

    }
}