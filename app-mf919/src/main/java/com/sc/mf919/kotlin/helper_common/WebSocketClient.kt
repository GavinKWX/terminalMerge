package com.sc.mf919.kotlin.helper_common

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import enums.EnumWebsocket
import com.sc.mf919.kotlin.database.repo.DenominationListRepo
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

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

    /*
     * Duplicate suppression state. @Volatile because it is written on the socket's own thread and
     * cleared from an IO coroutine.
     */
    private const val DEDUP_WINDOW_MS = 3000L

    @Volatile
    private var lastMessage = ""

    /** Bounded form of a message for a log line -- these are commands, not card data. */
    private fun brief(message: String): String =
        if (message.length <= 200) message else message.take(200) + "...(${message.length} chars)"

    /**
     * Connection-lifecycle log: one flushed block per connect / disconnect / error.
     *
     * Built per event with false/"" for wifi/IP - the java-websocket callbacks run on the
     * socket's own thread with no Context to hand, same shape as WebSocketServer.logWs. The
     * message traffic itself is deliberately NOT logged here (it stays on logcat via the
     * existing printlns): the channel carries a keep-alive every 45s and the payloads are
     * already recorded by whoever consumes messageFlow.
     */
    private fun logWs(event: String, isError: Boolean = false) {
        try {
            val log = HelperLog(
                HelperCommon.getSession(),
                false,
                "",
                TAG,
                TAG,
                "WebSocket Client Connection"
            )
            log.appendLine(TAG, event)
            log.logToFile(if (isError) EnumLogFileName.TerminaLogException else EnumLogFileName.TerminaLog)
        } catch (ex: Exception) {
            Log.w(TAG, "logWs failed: ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    /*
     * A server that is down is retried every 3s for as long as the app runs, and every retry
     * produces an onError/onClose. Writing a block per failure is a ~20-lines-a-minute disk
     * loop, so only the first failure of a run and then every 20th (~1 minute) is written;
     * onOpen resets the run. Nothing is hidden - the count travels with the line.
     */
    private const val FAILURE_LOG_INTERVAL = 20

    @Volatile
    private var consecutiveFailures = 0

    /*
     * A connection is only treated as healthy -- and the failure run only reset -- once it has
     * held this long. A server that accepts and instantly drops used to reset the run on every
     * onOpen, so every disconnect looked like the first failure of a new run and the throttle
     * never engaged. Measured on a terminal: accept-then-1006 every ~3.2s, ~19 log blocks a
     * minute.
     */
    private const val HEALTHY_CONNECTION_MS = 10_000L

    @Volatile
    private var openedAtMs = 0L

    private fun logConnectFailure(event: String) {
        val failures = ++consecutiveFailures
        if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
            logWs("$event (consecutive failure #$failures)", isError = true)
        }
    }

    /**
     * Connect succeeded. Throttled on the same run counter: while a server is flapping, the
     * success line would otherwise loop exactly as fast as the failure line.
     */
    private fun logConnectSuccess(event: String) {
        if (consecutiveFailures == 0 || consecutiveFailures % FAILURE_LOG_INTERVAL == 0) {
            logWs(event)
        }
    }

    /**
     * How long the connection that just dropped had been open, and reset of the failure run when
     * it held long enough to count as healthy. -1 means it never opened.
     */
    private fun heldMs(): Long {
        if (openedAtMs == 0L) return -1
        val held = SystemClock.elapsedRealtime() - openedAtMs
        openedAtMs = 0L
        if (held >= HEALTHY_CONNECTION_MS) {
            consecutiveFailures = 0
        }
        return held
    }

    fun connect(uri: String) {
        if(client == null) {
            targetUri = URI(uri)
            logWs("Connect requested :: $uri")
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
                openedAtMs = SystemClock.elapsedRealtime()
                // NOT consecutiveFailures = 0 -- see HEALTHY_CONNECTION_MS. The run is reset on
                // disconnect, and only if the connection actually held.
                logConnectSuccess("Connected to server :: $targetUri (queued=${sendQueue.size})")
                reconnectJob?.cancel()
                flushSendQueue()
                threadPingPong()
            }

            override fun onMessage(message: String) {
                // Keep-alive first: it must never enter the dedup state (a repeated PONG is not
                // a duplicate worth reporting) and never reaches messageFlow.
                if (message.uppercase() == "PONG") {
                    return
                }

                // The server can repeat a push, and the commands below are not idempotent from
                // the UI's point of view -- an UpdatePrice truncate re-running mid-render shows
                // an empty list. So a repeat inside the window is dropped, but it is LOGGED:
                // a silent drop is indistinguishable from a message that never arrived when
                // reading a terminal log after the fact.
                if (message == lastMessage) {
                    logWs("Duplicate ignored (within ${DEDUP_WINDOW_MS}ms) :: ${brief(message)}")
                    return
                }
                println("Received :: $message")
                lastMessage = message
                CoroutineScope(Dispatchers.IO).launch {
                    delay(DEDUP_WINDOW_MS)
                    lastMessage = ""
                }

                var returnMessage = message
                var jsonObject: JsonObject? = null
                try {
                    try {
                        val jsonElement = JsonParser.parseString(message)
                        jsonObject = when {
                            jsonElement.isJsonObject -> {
                                jsonElement.asJsonObject
                            }
                            jsonElement.isJsonPrimitive && jsonElement.asJsonPrimitive.isString -> {
                                // unwrap double-encoded JSON
                                val inner = jsonElement.asString
                                JsonParser.parseString(inner).asJsonObject
                            }
                            else -> {
                                println("Ignore non-JSON message: $message")
                                return
                            }
                        }
                    } catch (illegalEx: IllegalStateException) {
                        println("-------------------IllegalStateException-----------------")
                        illegalEx.printStackTrace()
                        val innerJson = JsonParser.parseString(message).asString
                        jsonObject = JsonParser.parseString(innerJson).asJsonObject
                    }
                } catch (ex: Exception) {
                    println("-------------------Exception-----------------")
                    ex.printStackTrace()
                }

                try {
                    if (jsonObject != null) {
                        returnMessage = Gson().toJson(jsonObject)
                        val jsonCommand = jsonObject.get("Command").asString
                        println("jsonCommand :: $jsonCommand")

                        when (jsonCommand) {
                            EnumWebsocket.UpdatePrice.socketCommand -> {
                                // The denomination list is only re-fetched from TMS when the local
                                // copy is empty, so without clearing it a price change on TMS never
                                // reaches a terminal that already has a list.
                                DenominationListRepo.truncateTable(ServiceHolder.mContext)
                            }
                            else -> {
                                // Not "invalid" -- the server is entitled to send it, we have no
                                // handler. Through logWs, not println, so an unhandled live
                                // command is visible in the uploaded log instead of only on a
                                // logcat nobody is watching.
                                logWs("Unhandled command :: $jsonCommand")
                            }
                        }
                    }
                } catch (ex: Exception) {
                    println("-------------------Exception-----------------")
                    ex.printStackTrace()
                }

                println("returnMessage :: $returnMessage")
                _messageFlow.tryEmit(returnMessage)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                println("WebSocket closed: $reason")
                isConnected = false
                logConnectFailure(
                    "Disconnected :: code=$code remote=$remote " +
                    "reason=${reason.ifBlank { "-" }} heldMs=${heldMs()}"
                )
                scheduleReconnect()
            }

            override fun onError(ex: Exception) {
                println("WebSocket error: ${ex.message}")
                isConnected = false
                logConnectFailure("Socket ERROR :: ${ex.javaClass.simpleName}: ${ex.message}")
                scheduleReconnect()
            }
        }

        client = newClient
        CoroutineScope(Dispatchers.IO).launch {
            try {
                newClient.connectBlocking()
            } catch (e: Exception) {
                println("Initial connection failed: ${e.message}")
                logConnectFailure("Connect attempt failed :: ${e.javaClass.simpleName}: ${e.message}")
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
        // Only worth a line when there was something to tear down - this is called
        // unconditionally from the terminal-config path on every sign-on.
        if (client != null) {
            logWs("Disconnect requested by terminal (wasConnected=$isConnected)")
        }
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
