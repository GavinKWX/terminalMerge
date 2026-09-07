package com.sc.mf919.kotlin.helper_common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sc.mf919.java.activity.Utils
import env.EnvironmentManager
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class NetworkListener(val context: Context, private val environmentManager: EnvironmentManager) {
    companion object{
        val TAG = "NETWORK_LISTENER_TAG"
        var isConnectedWifi = false
        var startConnectSignalR = false
        var currentAction = ""
    }

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * One flushed block per connectivity transition.
     *
     * Built per event rather than held in a field: the callbacks fire on a framework thread
     * for the whole life of the process, and a transition is exactly one line - there is no
     * multi-phase flow here to accumulate. Construction is guarded because it touches the
     * session/wifi/IP helpers, and losing a connectivity line must never take down the
     * terminal from a system callback.
     */
    private fun logNet(event: String) {
        try {
            val log = HelperLog(
                HelperCommon.getSession(),
                isConnectedWifi,
                Utils.getIPAddress(),
                TAG,
                TAG,
                "Network Connectivity"
            )
            log.appendLine(TAG, event)
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (ex: Exception) {
            Log.w(TAG, "logNet failed: ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    private val networkCallback: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                //val isCellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
                val isWifi = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
                val isCellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
                isConnectedWifi = isWifi
                startConnectSignalR = false

                logNet("Network AVAILABLE :: wifi=$isWifi cellular=$isCellular " +
                        "validated=${networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")

                if (isWifi) {
                    // Connected network is Wi-Fi
                    //TODO Commented due to over stress server
                    //startConnectSignalR = true
                    //InitSignalR(context, environmentManager).start()
                }
               /* else if (isCellular) {
                    // Connected network is cellular (including metered connections)
                    println("NetworkChangeManager -> Connected network is cellular")
                }
                else {
                    // Connected network is other types (e.g., VPN)
                    println("NetworkChangeManager -> Connected network is other type")
                }
               * */
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // Network is lost
                startConnectSignalR = false
                logNet("Network LOST :: lastKnownWifi=$isConnectedWifi")
            }
        }

    fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        logNet("Network callback registered")
    }

    fun unregisterNetworkCallback() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        logNet("Network callback unregistered")
    }
}