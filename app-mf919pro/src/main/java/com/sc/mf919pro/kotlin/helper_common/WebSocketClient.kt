package com.sc.mf919pro.kotlin.helper_common

import android.util.Log
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import env.EnvironmentManager
import env.EnvironmentVariables
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

//TODO BRYAN
/*class WebSocketClient(private val url: String, private val enableWebSocket: Boolean, private val context: Context) : BaseActivity() {

    private var webSocketClient: WebSocketClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _messagesFlow = MutableSharedFlow<String>()
    val messagesFlow: SharedFlow<String> get() = _messagesFlow

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMillis = 2000L

    fun startWebSocket() {
        if (!enableWebSocket) {
            Log.d("WebSocketClient", "Disabled by flag.")
            return
        }

        val uri = URI(url)
        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("WebSocketClient", "WebSocket Connected")
            }

            override fun onMessage(message: String?) {
                message?.let {
                    Log.d("WebSocketClient", "Message Received: $it")
                    coroutineScope.launch { _messagesFlow.emit(it) }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("WebSocketClient", "WebSocket Closed: $reason")
                handleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.d("WebSocketClient", "WebSocket Error: ${ex?.message}")
                handleReconnect()
            }
        }

        webSocketClient?.connect()
        Log.d("WebSocketClient", "WebSocket Connection started.")
    }

    private fun handleReconnect() {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            Log.d("WebSocketClient", "Reconnecting attempt $reconnectAttempts/$maxReconnectAttempts...")

            // Cancel and clean up the old WebSocketClient instance
            webSocketClient?.close() // Ensure any lingering connection is closed
            webSocketClient = null

            coroutineScope.launch {
                try {
                    delay(reconnectDelayMillis)

                    // Create a new WebSocketClient instance and connect
                    val uri = URI(url)
                    webSocketClient = object : WebSocketClient(uri) {
                        override fun onOpen(handshakedata: ServerHandshake?) {
                            Log.d("WebSocketClient", "WebSocket Reconnected")
                            reconnectAttempts = 0 // Reset reconnect attempts on successful connection
                        }

                        override fun onMessage(message: String?) {
                            message?.let {
                                coroutineScope.launch { _messagesFlow.emit(it) }
                            }
                        }

                        override fun onClose(code: Int, reason: String?, remote: Boolean) {
                            Log.d("WebSocketClient", "WebSocket Closed: $reason")
                            handleReconnect()
                        }

                        override fun onError(ex: Exception?) {
                            Log.e("WebSocketClient", "WebSocket Error: ${ex?.message}")
                            handleReconnect()
                        }
                    }

                    webSocketClient?.connect()
                } catch (e: Exception) {
                    Log.e("WebSocketClient", "Error during reconnection: ${e.message}", e)
                }
            }
        } else {
            Log.d("WebSocketClient", "Max reconnect attempts reached. Connection failed.")
        }
    }

    fun stopWebSocket() {
        webSocketClient?.close()
        coroutineScope.cancel()
        Log.d("WebSocketClient", "Connection stopped.")
    }

    fun sendMessage(message: String) : Boolean {
        if (enableWebSocket && webSocketClient?.isOpen == true) {
            webSocketClient?.send(message)
            Log.d("WebSocketClient", "Message Sent: $message")
            return true
        } else {
            Log.d("WebSocketClient", "Not connected or disabled.")
        }

        return false
    }

    fun observeMessages() {
        coroutineScope.launch {
            messagesFlow.collect { message ->
                handleIncomingMessage(message)
            }
        }
    }

    private fun handleIncomingMessage(message: String) {
        Log.d("WebSocketClient", "Processing Message: $message")
//        Toast.makeText(context, "Received: $message", Toast.LENGTH_SHORT).show()
    }
}*/
//TODO BRYAN

//TODO GAVIN
val TAG = "WebSocketClient"
object WebSocketClientSingleton {

    private var client: WebSocketClient? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private var targetUri: URI? = null
    private var threadRunning = false

    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messageFlow = _messageFlow.asSharedFlow()

    private val sendQueue = ConcurrentLinkedQueue<String>()

    fun connect(uri: String) {
        if(client == null) {
            targetUri = URI(uri)
            connectToServer()
        }
    }

    private fun connectToServer() {
        println("WebSocket :: connectToServer")
        if (targetUri == null || isConnected) return

        val newClient = object : WebSocketClient(targetUri) {
            override fun onOpen(handshakedata: ServerHandshake) {
                println("WebSocket connected")
                isConnected = true
                reconnectJob?.cancel()
                flushSendQueue()
                threadPingPong()
            }

            override fun onMessage(message: String) {
                println("Received: $message")
                if(message.uppercase() == "PONG") {
                    return
                }

                _messageFlow.tryEmit(message)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                println("WebSocket closed: $reason")
                isConnected = false
                scheduleReconnect()
            }

            override fun onError(ex: Exception) {
                println("WebSocket error: ${ex.message}")
                isConnected = false
                scheduleReconnect()
            }
        }

        client = newClient
        CoroutineScope(Dispatchers.IO).launch {
            try {
                newClient.connectBlocking()
            } catch (e: Exception) {
                println("Initial connection failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    fun send(message: String) {
        if (isConnected && client?.isOpen == true) {
            Log.d(TAG, "send :: $message")
            client?.send(message)
        } else {
            println("Not connected, caching message: $message")
            sendQueue.offer(message)

            if(client == null) {
                val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
                val wsURI = "${environmentManager.get(EnvironmentVariables::socketHandlerUrl)}?sn=${ServiceHolder.getTerminalSerialNumber()}"
                connect(wsURI)
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        client?.close()
        client = null
        isConnected = false
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if(client == null) return

        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000L)
            println("Retrying WebSocket connection...")
            connectToServer()
        }
    }

    private fun flushSendQueue() {
        println("Flushing ${sendQueue.size} cached message(s)...")
        while (sendQueue.isNotEmpty()) {
            val msg = sendQueue.poll()
            client?.send(msg)
        }
    }

    private fun threadPingPong() {
        object : Thread() {
            override fun run() {
                super.run()
                if(!threadRunning) {
                    var count = 45
                    while(client != null) {
                        threadRunning = true
                        if(isConnected && count == 0) {
                            send("Ping")
                        }

                        if(count <= 0) {
                            count = 45
                        }
                        count--
                        sleep(1000)
                    }
                    threadRunning = false
                }
            }
        }.start()
    }
}
//TODO GAVIN

//TODO GAVIN 1
class WebSocketMessageListener(
    private val scope: CoroutineScope,
    private val onMessageReceived: (String) -> Unit,
    private val filter: (String) -> Boolean = { true }
) {

    private var job: Job? = null

    fun startListening() {
        if (job == null) {
            job = scope.launch {
                WebSocketClientSingleton.messageFlow
                    .filter { filter(it) }
                    .collectLatest { message ->
                        onMessageReceived(message)
                    }
            }
        }
    }

    fun stopListening() {
        job?.cancel()
        job = null
    }
}
//TODO GAVIN 1