package com.sc.mf919pro.kotlin.helper_common.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

class NetworkChangeObserver(
    context: Context,
    private val debounceMs: Long = 500L,
    private val onNetworkChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    private val debouncedRunnable = Runnable {
        onNetworkChanged()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyDebounced()
        }

        override fun onLost(network: Network) {
            notifyDebounced()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            notifyDebounced()
        }
    }

    private fun notifyDebounced() {
        mainHandler.removeCallbacks(debouncedRunnable)
        mainHandler.postDelayed(debouncedRunnable, debounceMs)
    }

    fun start() {
        if (isRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
        isRegistered = true

        // Optional initial refresh
        notifyDebounced()
    }

    fun stop() {
        if (!isRegistered) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }

        mainHandler.removeCallbacks(debouncedRunnable)
        isRegistered = false
    }
}