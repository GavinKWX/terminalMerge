package com.sc.mf919.kotlin.helper_common

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.server.WebSocketServer
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class WebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    companion object {
        var socketConnected = false
        var messageQueue = ConcurrentLinkedQueue<String>() // Message queue for incoming messages
        fun receiveResponseMessage(receiveMessage: String) {
            messageQueue.add(receiveMessage)
        }
    }

    //private val messageQueue = ConcurrentLinkedQueue<String>() // Message queue for incoming messages
    private val connectedClients = mutableSetOf<WebSocket>() // Track connected clients
    private val scope = CoroutineScope(Dispatchers.IO) // Coroutine scope for handling messages

    /**
     * Connection lifecycle into the UPLOADED log, not just logcat.
     *
     * These four callbacks were android.util.Log.d only, so the terminal log could never show
     * whether the POS connection dropped or the listener restarted -- the evidence lived in a
     * logcat ring buffer that is gone within minutes. When a POS reports dropped connections in
     * the field, this is the history you need. Own HelperLog per event because this class is not
     * an Activity and the events are asynchronous.
     */
    private fun logWs(event: String) {
        try {
            val log = HelperLog(
                HelperCommon.getSession(),
                false,
                "",
                "WebSocketServer",
                "WebSocketServer",
                "WebSocket Server Connection"
            )
            log.appendLine("WebSocketServer", event)
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            Log.w("WebSocketServer", "logWs failed: ${e.javaClass.simpleName}")
        }
    }

    /*init {
        startMessageProcessing()
    }*/

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            connectedClients.add(it) // Add new client to the set of connected clients
            //it.send("Welcome to the WebSocket server with message queue!")
            Log.d("WebSocketServer", "New connection: ${it.remoteSocketAddress}")
            logWs("POS connected :: ${it.remoteSocketAddress} (clients=${connectedClients.size})")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            connectedClients.remove(it) // Remove client from connected clients set
            Log.d("WebSocketServer", "Closed connection: ${it.remoteSocketAddress} - Reason: $reason")
            logWs("POS disconnected :: ${it.remoteSocketAddress} code=$code remote=$remote " +
                "reason=${reason ?: "-"} (clients=${connectedClients.size})")
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message?.let {
            //messageQueue.add(it) // Add incoming message to the queue
            HTTPServer.checkWebSocketIncoming(it)
            Log.d("WebSocketServer", "Message queued: $it")
        } ?: run {
            println("Msg is null")
            val jsonResponse = JSONObject()
            jsonResponse.put("ResponseCode", "SHC001")
            jsonResponse.put("ResponseDescription", "Invalid Input")
            messageQueue.add(jsonResponse.toString())
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        socketConnected = false
        Log.e("WebSocketServer", "Error: ${ex?.message}")
        logWs("WebSocket ERROR :: ${ex?.javaClass?.simpleName}: ${ex?.message} -- socketConnected=false")
    }

    override fun onStart() {
        socketConnected = true
        startMessageProcessing()
        Log.d("WebSocketServer", "WebSocket Server started successfully!")
        logWs("WebSocket listener started :: port bound, accepting POS connections")
    }

    private fun startMessageProcessing() {
        scope.launch {
            while (true) {
                processQueue() // Continuously process the message queue
                delay(500)
            }
        }
    }

    private suspend fun processQueue() {
        while (messageQueue.isNotEmpty() && connectedClients.isNotEmpty()) {
            val message = messageQueue.poll() // Retrieve and remove the head of the queue
            if (message != null) {
                broadcastMessage(message)
            }
        }
    }

    private fun broadcastMessage(message: String) {
        connectedClients.forEach { client ->
            client.send(message) // Send the message to all connected clients
        }
        Log.d("WebSocketServer", "Broadcasted message: $message")
    }

    fun stopServer() {
        stop()
        socketConnected = false
        scope.cancel() // Cancel the coroutine scope to release resources
        Log.d("WebSocketServer", "WebSocket server stopped")
    }
}