package ws
import enums.EnumResponseCode

import android.util.Log
import enums.EnumLogFileName
import helpers.HelperNetwork
import helpers.HelperLog
import helpers.HelperText
import helpers.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.server.WebSocketServer
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

class WebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    companion object {
        private const val TAG = "WebSocketServer"
        // Bound the pending queue so undeliverable responses cannot grow forever
        private const val MAX_QUEUED_MESSAGES = 20

        // Set from the WebSocket server's callback threads and spin-read by
        // each app's HTTPServer.startWebSocketServer.
        @Volatile
        var socketConnected = false
        // Responses are queued so a client that disconnected before its response was
        // ready still receives it after reconnecting (delivery is broadcast by design)
        val messageQueue = ConcurrentLinkedQueue<String>()

        @Volatile
        private var activeInstance: ws.WebSocketServer? = null

        fun receiveResponseMessage(receiveMessage: String) {
            messageQueue.add(receiveMessage)
            while (messageQueue.size > MAX_QUEUED_MESSAGES) {
                messageQueue.poll()
            }
            // Deliver immediately when possible; the polling loop remains as the
            // safety net for clients that reconnect later
            activeInstance?.flushQueue()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var processingJob: Job? = null

    init {
        // Allow quick restarts on the same port without address-in-use failures
        isReuseAddr = true
    }

    /*
     * These callbacks were Log.d only, so the uploaded log could never show a dropped POS
     * connection -- and logcat is gone within minutes on a terminal. Route them through HelperLog
     * so the connection lifecycle is in TerminaLog, which is what gets uploaded to TMS.
     *
     * A fresh instance per event on purpose: these fire on java-websocket's own threads, there is
     * no per-connection HelperLog to reuse, and each event is a complete one-line fact.
     */
    private fun wsLog(msg: String) {
        try {
            Log.d(TAG, msg)
            val log = HelperLog(
                newLogSession(),
                HelperNetwork.isConnectedWifi(CurrentWsHost.context()),
                TerminalInfo.ipAddress(),
                TAG,
                TAG,
                "WebSocket Server"
            )
            log.appendLine(TAG, msg)
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (ex: Exception) {
            // Never let diagnostics break the socket callback.
            Log.w(TAG, "wsLog failed: ${ex.javaClass.simpleName}")
        }
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            wsLog("Client connected :: ${it.remoteSocketAddress}")
        }
        // A client just connected: deliver anything still pending
        flushQueue()
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            wsLog("Client disconnected :: ${it.remoteSocketAddress} code=$code remote=$remote reason=${reason ?: "-"}")
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message?.let {
            wsLog("Message received :: ${HelperText.oneLine(it)}")
            CurrentWsHost.onEcrMessage(it)
        } ?: run {
            wsLog("REJECT :: null message from client")
            val jsonResponse = JSONObject()
            jsonResponse.put("ResponseCode", EnumResponseCode.INVALID_INPUT.code)
            jsonResponse.put("ResponseDescription", EnumResponseCode.INVALID_INPUT.description)
            receiveResponseMessage(jsonResponse.toString())
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        // conn == null means a server-level failure; a per-connection error must not
        // flag the whole server as down (it confuses HTTPServer's restart logic)
        if (conn == null) {
            socketConnected = false
        }
        wsLog("Error :: conn=${conn?.remoteSocketAddress ?: "server-level"} ${ex?.javaClass?.simpleName}: ${ex?.message}")
    }

    override fun onStart() {
        socketConnected = true
        activeInstance = this
        startMessageProcessing()
        wsLog("Server started successfully, listening for POS connections")
    }

    private fun startMessageProcessing() {
        processingJob?.cancel()
        processingJob = scope.launch {
            while (isActive) {
                flushQueue()
                delay(500)
            }
        }
    }

    // Drains the pending queue to all connected clients (broadcast is intentional:
    // every client mirrors the transaction responses). Safe to call from any thread;
    // ConcurrentLinkedQueue.poll guarantees each message is taken exactly once.
    fun flushQueue() {
        while (messageQueue.isNotEmpty() && connections.isNotEmpty()) {
            val message = messageQueue.poll() ?: break
            broadcastMessage(message)
        }
    }

    private fun broadcastMessage(message: String) {
        // `connections` is the library's thread-safe live collection
        connections.forEach { client ->
            try {
                client.send(message)
            } catch (ex: Exception) {
                // A dropped client must not abort delivery to the others
                // or kill the processing loop
                Log.e(TAG, "Send failed to ${client.remoteSocketAddress}: ${ex.message}")
            }
        }
        Log.d(TAG, "Broadcasted message: $message")
    }

    fun stopServer() {
        try {
            stop()
        } catch (ex: Exception) {
            Log.e(TAG, "Stop failed: ${ex.message}")
        }
        socketConnected = false
        if (activeInstance === this) {
            activeInstance = null
        }
        scope.cancel()
        wsLog("Server stopped")
    }
}
