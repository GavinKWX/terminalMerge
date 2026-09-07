package com.sc.mf919.kotlin.helper_common

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.EnumWebsocket
import com.sc.mf919.kotlin.data_enum.variables.TransData
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
    var temp = ""

    private val sendQueue = ConcurrentLinkedQueue<String>()

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

    private fun logConnectFailure(event: String) {
        val failures = ++consecutiveFailures
        if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
            logWs("$event (consecutive failure #$failures)", isError = true)
        }
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
                consecutiveFailures = 0
                logWs("Connected to server :: $targetUri (queued=${sendQueue.size})")
                reconnectJob?.cancel()
                flushSendQueue()
                threadPingPong()
            }

            override fun onMessage(message: String) {
                var returnMessage = message
                if(temp == message) {
                    println("Received :: duplicate ${Utils.DateTimeFormat(TransData.transDateAsci)}")
                    return
                }
                println("Received :: $message")
                temp = message
                CoroutineScope(Dispatchers.IO).launch {
                    delay(3000)
                    temp = ""
                }
                if(message.uppercase() == "PONG") {
                    return
                }

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
                                println( "Ignore non-JSON message: $message")
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
                    if(jsonObject != null) {
                        returnMessage = Gson().toJson(jsonObject)
                        val jsonCommand = jsonObject.get("Command").asString
                        println("jsonCommand :: $jsonCommand")

                        when(jsonCommand) {
                            EnumWebsocket.UpdatePrice.socketCommand -> {
                                DenominationListRepo.truncateTable(ServiceHolder.mContext)
                            }
//                            EnumWebsocket.TerminalDMDispense.socketCommand -> {
//                                println("Doing")
//                                CoroutineScope(Dispatchers.IO).launch {
//                                    HelperCommon.manualDispenseToken(ServiceHolder.mContext, returnMessage)
//                                }
//                            }
                            else -> {
                                println("Invalid Command")
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
                logConnectFailure("Disconnected :: code=$code remote=$remote reason=${reason.ifBlank { "-" }}")
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