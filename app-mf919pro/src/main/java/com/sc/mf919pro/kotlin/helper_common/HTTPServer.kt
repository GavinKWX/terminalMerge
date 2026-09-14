package com.sc.mf919pro.kotlin.helper_common
import enums.EnumResponseCode

import helpers.IntegrationMode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.library.terminal.Utility
import com.morefun.yapi.ServiceResult
import com.morefun.yapi.device.serialport.SerialPort
import com.morefun.yapi.device.serialport.SerialPortDriver
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import utils.Util
import data_enum.CardErrorDataEnum
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum.CARD_SETTINGS
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum.Companion.getProductCatForHttp
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appRunningProcess
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import enums.EnumLogFileName
import fi.iki.elonen.NanoHTTPD
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import tms.models.EppDetail
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.text.replace

@SuppressLint("StaticFieldLeak")
object HTTPServer: NanoHTTPD(8888) {
    val TAG = "HTTPSERVER"
    var httpServer: HTTPServer? = null

    @Volatile
    var requestMsg: String? = null
    @Volatile
    var responseMsg: String? = null

    // Already @Volatile before this pass; the author was careful here. The gap was
    // ServiceHolder.appRunningProcess, which is read on this same worker thread and was not.
    @Volatile
    var isActive = false
    @Volatile
    var waitingForResponse = false

    private val gson = Gson()
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val bgScope = CoroutineScope(Dispatchers.IO)

    // Response timeout: serve() no longer waits forever for the UI flow to answer
    private const val RESPONSE_TIMEOUT_MS = 300_000L
    // How long a cancelled transaction is given to publish its own result before its caller is
    // answered SHC009 instead.
    private const val CANCEL_RESULT_WAIT_MS = 10_000L
    // DISABLED. Window in which an identical request body would be treated as a POS retry and
    // answered from cache instead of re-triggering the transaction.
    //
    // At 0L the guard in submitRequest can never be true, so the retry branch there is
    // unreachable and lastCompletedBody/Response/At are maintained but never read. Left in place
    // rather than deleted because the feature is finished, not abandoned -- but enabling it
    // changes vendor-visible behaviour: a genuine second sale with a byte-identical body inside
    // the window would be answered from cache instead of being run. That needs a deliberate
    // decision, not a constant flip. Ruled 2026-09-09: stays disabled. Set to 5_000L to enable.
    private const val RETRY_CACHE_WINDOW_MS = 0L
    private val requestLock = Any()
    private var inFlight: InFlight? = null
    private var lastCompletedBody: String? = null
    private var lastCompletedResponse: String? = null
    private var lastCompletedAt = 0L

    private enum class Origin { HTTP, CABLE, WEBSOCKET }

    private class InFlight(val origin: Origin, val body: String) {
        val future = CompletableFuture<String>()
        /** When the slot was claimed, for the stale-claim takeover in submitRequest. */
        val claimedAt = SystemClock.elapsedRealtime()
    }

    private sealed class SubmitResult {
        class Wait(val entry: InFlight, val owner: Boolean) : SubmitResult()
        class Immediate(val response: String) : SubmitResult()
    }

    var attendActivityContext: Activity? = null
        //get() = field
        //set(value) { field = value}

    // #1-USB, #2-WEBSOCKET
    // Transport state: written on the main thread or a bgScope coroutine, read on NanoHTTPD
    // worker threads and the cable read loop, whose `while (portOpen)` condition depends on it.
    @Volatile
    var socketInterface = -1

    //TODO RS232 Configuration
    private var serialPortDriver: SerialPortDriver? = null
    @Volatile
    private var cableRequest = false
    private var comport = 4

    // RS232 cable, ported from MF919. Selected by CABLE_CONNECTION = "RS232"; the USB mode above
    // uses SerialPortDriver instead. Both are cable transports feeding the same request path.
    private var usbSerialPort: SerialPort? = null
    private var usbPath = "dev/ttyUSB0"
    private val baudRate = 115200
    private val dataBits = 8
    private val stopBits = 1
    private val parity = 0
    @Volatile
    private var portOpen = false

    //TODO HelperLog
    var helperLog: HelperLog? = null
    val helperlogClassName:String  = this::class.java.simpleName

    //TODO WEBSOCKET
    @Volatile
    private var webSocketRequest = false
    @Volatile
    private var socketConnected = false
    private var webSocketServer: WebSocketServer? = null
    private var serviceScope: CoroutineScope? = null

    //TODO WEBSOCKET CLIENT
    //private var webSocketClient: WebSocketClient? = null

    @JvmStatic
    fun getInstance(): HTTPServer {
        if(httpServer == null){
            httpServer = HTTPServer
        }
        return httpServer!!
    }

    fun startHttpServer(){
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
            Utils.getIPAddress(),
            helperlogClassName,
            helperlogClassName,
            "HTTP Server"
        )
        helperLog?.appendLine(helperlogClassName, "Start HTTP Server Initialization")

        if(!isAlive){
            helperLog?.appendLine(helperlogClassName, "Start Api Server")
            try {
                start(SOCKET_READ_TIMEOUT, false)
            }catch (e: IOException){
                helperLog?.appendLine(helperlogClassName, "HTTP Server IO Exception :: ", "${e.message}")
                helperLog?.logToFile(EnumLogFileName.TerminaLogException)
                e.printStackTrace()
            }
        }
    }

    fun stopHTTPServer() {
        stop()
    }

    fun setRequestMessage() {
        requestMsg = null
    }

    fun getRequestMessage(): String {
        return requestMsg ?: ""
    }

    @JvmStatic
    fun startServeCable() {
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
        val tmpHelperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
            Utils.getIPAddress(),
            helperlogClassName,
            helperlogClassName,
            "HTTP Server"
        )
        tmpHelperLog.appendLine(helperlogClassName, "startServeCable")
        tmpHelperLog.appendLine(helperlogClassName, "Connection Method :: ", connMethod)
        tmpHelperLog.appendLine(helperlogClassName, "portOpen :: $portOpen")

        when (connMethod.uppercase()) {
            "USB" -> {
                socketInterface = 1
                if(!portOpen) {
                    try {
                        serialPortDriver = DeviceHelper.getSerialPortDriver(comport)
                        serialPortDriver?.let {
                            val connect: Int = serialPortDriver!!.connect("115200,N,8,1")
                            if (connect == ServiceResult.Success) {
                                portOpen = true
                                cableRequest = true
                                cableConnectionReceiving(connMethod)
                            } else {
                                tmpHelperLog.appendLine(helperlogClassName, "Open Serial Port Fail")
                            }
                        }
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }
                }
            }
            "RS232" -> {
                socketInterface = 1
                if(!portOpen) {
                    try {
                        usbSerialPort = DeviceHelper.getUsbSerialPort(usbPath)
                        usbSerialPort?.let {
                            val connect: Int = usbSerialPort!!.openAndInit(baudRate, dataBits, stopBits, parity)
                            if (connect == ServiceResult.Success) {
                                portOpen = true
                                cableRequest = true
                                cableConnectionReceiving(connMethod)
                            } else {
                                tmpHelperLog.appendLine(helperlogClassName, "Open USB Serial Fail")
                            }
                        }
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }
                }
            }
            else -> {
                tmpHelperLog.appendLine(helperlogClassName, "reset port from serve cable")
                if(socketInterface == 1) {
                    socketInterface = -1
                    serialPortDriver?.let {
                        it.clrBuffer()
                        it.disconnect()
                    }
                    usbSerialPort?.let {
                        it.clearInputBuffer()
                        it.close()
                    }
                    // Outside the ?.let, as MF919 has it: with a null driver the old code left
                    // portOpen true, so the receive loop kept spinning on a closed port.
                    portOpen = false
                    cableRequest = false
                }
            }
        }
        tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    fun resetCommunicationPort() {
        //TODO
        if(socketInterface == 1) {
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
            // Reconnect only if a cable mode is still configured -- unchanged from before.
            val reconnect = connMethod.isNotEmpty() && connMethod != "None"
            when (connMethod.uppercase()) {
                "RS232" -> {
                    usbSerialPort = DeviceHelper.getUsbSerialPort(usbPath)
                    usbSerialPort?.let {
                        serviceScope?.cancel()
                        it.clearInputBuffer()
                        it.close()

                        Util.DelayMili(500)
                        if (reconnect) {
                            it.openAndInit(baudRate, dataBits, stopBits, parity)
                        }
                    }
                }
                else -> {
                    serialPortDriver = DeviceHelper.getSerialPortDriver(comport)
                    serialPortDriver?.let {
                        serviceScope?.cancel()
                        it.clrBuffer()
                        it.disconnect()

                        Util.DelayMili(500)
                        if (reconnect) {
                            it.connect("115200,N,8,1")
                        }
                    }
                }
            }
        } else if (socketInterface == 2) {
            startWebSocketServer()
        }
    }

    private fun cableConnectionReceiving(connMethod: String) {
        bgScope.launch {
            val buffer = StringBuilder()
            val recvBytes = ByteArray(1024)
            while (portOpen) {
                try {
                    val read = when (connMethod.uppercase()) {
                        "USB" -> serialPortDriver!!.recv(recvBytes, recvBytes.size, 1000)
                        "RS232" -> usbSerialPort!!.read(recvBytes, recvBytes.size, 1000)
                        else -> 0
                    }
                    if (read > 0) {
                        buffer.append(Utils.byteArrayToAsciiString(recvBytes.copyOf(read)))
                    } else if (buffer.isNotEmpty()) {
                        val message = buffer.toString()
                        buffer.setLength(0)
                        val tmpHelperLog = HelperLog(
                            HelperCommon.getSession(),
                            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
                            Utils.getIPAddress(),
                            helperlogClassName,
                            helperlogClassName,
                            "HTTP Server"
                        )
                        tmpHelperLog.appendLine(helperlogClassName, "Final Message :: $message")
                        tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                        helperLog = tmpHelperLog
                        dispatchTransportRequest(Origin.CABLE, message)
                    }
                } catch (re: RemoteException) {
                    re.printStackTrace()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
                delay(500)
            }
        }
    }

    fun setResponseMessage(respMsg: String) {
        requestMsg = null
        responseMsg = respMsg
        val current = synchronized(requestLock) { inFlight }
        if (current != null) {
            // Delivery is handled per-origin by the waiter attached to the future
            // (serve() for HTTP, dispatchTransportRequest() for cable/WebSocket)
            current.future.complete(respMsg)
        } else {
            // No slot means no caller is waiting: the request was already answered, by a cancel
            // or by a timeout. Routing this by which port is open would deliver it to a POS that
            // never asked for it, so drop it and record that it happened.
            helperLog?.appendLine(
                helperlogClassName,
                "Unrouted response discarded :: ${HelperCommon.oneLine(respMsg)}"
            )
            waitingForResponse = false
        }

        helperLog?.appendLine(helperlogClassName, "Set Response Msg :: ${HelperCommon.oneLine(responseMsg)}")
        helperLog?.logToFile(EnumLogFileName.TerminaLog)
        bgScope.launch {
            delay(2000)
            ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond
        }
    }

    override fun serve(session: IHTTPSession): Response {
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
            Utils.getIPAddress(),
            helperlogClassName,
            helperlogClassName,
            "HTTP Sever Serve"
        )
        // One summary line instead of enumerating every inbound header: the interesting facts are
        // who called, what they sent and how big it was. Content type and user-agent identify the
        // POS integration when a request is malformed.
        helperLog?.appendLine(
            helperlogClassName,
            "Request from ${session.remoteIpAddress} :: method=${session.method.name}" +
                " type=${session.headers["content-type"] ?: "-"}" +
                " len=${session.headers["content-length"] ?: "-"}" +
                " ua=${session.headers["user-agent"] ?: "-"}"
        )

        // Both failure modes answer errorJson(1), exactly as before. Only the log tells them
        // apart: a short read is the POS's transport failing mid-request, a bad content-length is
        // a malformed request. MF919 answers errorJson(0) for the latter; that is deliberately NOT
        // adopted here -- "System Busy" for a malformed header is questionable, and it would be an
        // ECR contract change made for symmetry rather than for a reason.
        val bodyRead = readRequestBody(session)
        if (bodyRead is BodyRead.Short) {
            helperLog?.appendLine(helperlogClassName, "Short Read :: ",
                "${bodyRead.read}/${bodyRead.expected}")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(errorJson(1))
        }
        if (bodyRead !is BodyRead.Ok) {
            helperLog?.appendLine(helperlogClassName, "Missing/Invalid content-length :: ",
                "${session.headers["content-length"]}")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(errorJson(1))
        }
        val msg = bodyRead.msg
        if (msg.isEmpty()) {
            helperLog?.appendLine(helperlogClassName, "Empty request body")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(errorJson(1))
        }
        helperLog?.appendLine(helperlogClassName, "Request Msg :: ${HelperCommon.oneLine(msg)}")
        helperLog?.appendLine(helperlogClassName, "Is Active :: $isActive")

        val result = submitRequest(Origin.HTTP, msg)
        if (result is SubmitResult.Immediate) {
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(result.response)
        }

        val wait = result as SubmitResult.Wait
        var resp = errorJson(-1)
        try {
            if (wait.owner) {
                responseMsg = null
                requestMsg = msg
                handleIncomingRequest(msg)
            }
            helperLog?.logToFile(EnumLogFileName.TerminaLog)

            resp = try {
                wait.entry.future.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                timeoutJson()
            }
            return corsResponse(resp)
        } catch (ex: Exception) {
            // Answer with JSON the caller can parse rather than letting nanohttpd return a 500.
            ex.printStackTrace()
            helperLog?.appendLine(helperlogClassName, "Request handling threw :: ${ex.message}")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(resp)
        } finally {
            // In a finally: a throw must not leave the slot claimed, or every later request is
            // refused for the life of the process.
            if (wait.owner) {
                finishInFlight(wait.entry, resp)
                requestMsg = null
                waitingForResponse = false
            }
        }
    }

    // Single admission point shared by all transports (HTTP, RS232 cable, WebSocket).
    // Guarantees: one transaction in flight at a time, identical retries attach to the
    // in-flight response, TransactionType-0 can terminate a waiting session, and an
    // identical request just after completion is answered from cache (idempotent retry).
    private fun submitRequest(origin: Origin, body: String): SubmitResult {
        synchronized(requestLock) {
            // Admission point for HTTP, cable and WebSocket alike: nothing is accepted while a
            // load is in progress. isActive cannot carry this on its own, because it records the
            // last screen the nav listener saw and survives a MainActivity recreate.
            if (ServiceHolder.ecrStartupBlocking()) {
                helperLog?.appendLine(
                    helperlogClassName,
                    "Request refused :: app startup still running (${origin.name})"
                )
                return SubmitResult.Immediate(errorJson(0))
            }
            var current = inFlight
            if (current != null &&
                SystemClock.elapsedRealtime() - current.claimedAt > RESPONSE_TIMEOUT_MS) {
                // The claim outlived the response ceiling, so its owner is never going to answer.
                // Take it over instead of refusing every later request.
                val age = SystemClock.elapsedRealtime() - current.claimedAt
                helperLog?.appendLine(
                    helperlogClassName,
                    "Stale in-flight claim taken over (${origin.name}), age ${age} ms"
                )
                current.future.complete(timeoutJson())
                inFlight = null
                current = null
            }
            if (current != null) {
                if (body == current.body) {
                    helperLog?.appendLine(helperlogClassName, "Duplicate in-flight request (${origin.name}), attaching to pending response")
                    return SubmitResult.Wait(current, owner = false)
                }
                // TransactionType 0 is a control request: allow it to terminate a
                // waiting session instead of rejecting it as busy
                val cancelResp = handleCancelWhileBusy(body, current.future)
                if (cancelResp != null) {
                    helperLog?.appendLine(helperlogClassName, "Cancel request while busy (${origin.name}), terminating session")
                    // The slot stays claimed: the cancelled transaction still owes its own caller a
                    // result, and its owner releases the slot once that result arrives.
                    return SubmitResult.Immediate(cancelResp)
                }
                helperLog?.appendLine(helperlogClassName, "Different request while busy (${origin.name}), reject SHC000")
                return SubmitResult.Immediate(errorJson(0))
            }
            if (body == lastCompletedBody && lastCompletedResponse != null &&
                SystemClock.elapsedRealtime() - lastCompletedAt < RETRY_CACHE_WINDOW_MS) {
                // Retry right after the response was lost on the network:
                // replay the cached response instead of re-running the transaction
                helperLog?.appendLine(helperlogClassName, "Retry of completed request (${origin.name}), replaying cached response")
                return SubmitResult.Immediate(lastCompletedResponse!!)
            }
            val fresh = InFlight(origin, body)
            inFlight = fresh
            return SubmitResult.Wait(fresh, owner = true)
        }
    }

    private fun finishInFlight(entry: InFlight, resp: String) {
        synchronized(requestLock) {
            if (inFlight === entry) {
                inFlight = null
                lastCompletedBody = entry.body
                lastCompletedResponse = resp
                lastCompletedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun timeoutJson(): String {
        val json = JSONObject()
        json.put("ResponseCode", EnumResponseCode.TERMINAL_RESPONSE_TIMEOUT.code)
        json.put("ResponseDescription", EnumResponseCode.TERMINAL_RESPONSE_TIMEOUT.description)
        return json.toString()
    }

    // Entry point for the fire-and-forget transports (cable / WebSocket): they cannot
    // block like an HTTP connection, so the response is delivered via a future callback,
    // with the same overall timeout as HTTP.
    private fun dispatchTransportRequest(origin: Origin, body: String) {
        when (val result = submitRequest(origin, body)) {
            is SubmitResult.Immediate -> deliverToTransport(origin, result.response)
            is SubmitResult.Wait -> {
                val entry = result.entry
                if (result.owner) {
                    entry.future.whenComplete { resp, _ ->
                        val out = resp ?: errorJson(-1)
                        finishInFlight(entry, out)
                        waitingForResponse = false
                        deliverToTransport(origin, out)
                    }
                    bgScope.launch {
                        delay(RESPONSE_TIMEOUT_MS)
                        if (!entry.future.isDone) {
                            entry.future.complete(timeoutJson())
                        }
                    }
                    responseMsg = null
                    requestMsg = body
                    try {
                        handleIncomingRequest(body)
                    } catch (ex: Exception) {
                        // A throw before a response is published would hold the slot and leave this
                        // transport's caller with nothing.
                        ex.printStackTrace()
                        helperLog?.appendLine(helperlogClassName, "Request handling threw :: ${ex.message}")
                        entry.future.complete(errorJson(-1))
                    }
                } else if (origin != entry.origin) {
                    // Duplicate arriving over a DIFFERENT transport than the owner:
                    // the owner's delivery won't reach this transport, so deliver here.
                    // Same-origin duplicates need nothing extra (cable writes once,
                    // WebSocket broadcast already reaches the retrying client).
                    entry.future.whenComplete { resp, _ ->
                        deliverToTransport(origin, resp ?: errorJson(-1))
                    }
                }
            }
        }
    }

    private fun deliverToTransport(origin: Origin, respMsg: String) {
        when (origin) {
            Origin.CABLE -> onBackToRS232(respMsg)
            Origin.WEBSOCKET -> WebSocketServer.receiveResponseMessage(respMsg)
            Origin.HTTP -> { /* delivered by the blocked serve() thread */ }
        }
    }

    /**
     * Reads exactly content-length bytes, or returns null - callers must treat null as a rejected
     * request (SHC001), never as an empty one.
     *
     * Reading session.inputStream directly is safe with nanohttpd 2.3.1: HTTPSession.execute() does
     * mark(BUFSIZE) -> read headers -> reset() -> skip(splitbyte), so the BufferedInputStream is
     * repositioned to the first body byte and nothing the header parse consumed is lost. Re-check
     * this if the nanohttpd jar is ever upgraded.
     */
    /**
     * Outcome of reading the request body. Typed rather than String?: a truncated body and a
     * malformed content-length are different faults and a reader of the uploaded log needs to
     * tell them apart. Both still answer the POS identically -- see the caller.
     */
    private sealed class BodyRead {
        data class Ok(val msg: String) : BodyRead()
        data class Short(val read: Int, val expected: Int) : BodyRead()
        object NoContentLength : BodyRead()
    }

    private fun readRequestBody(session: IHTTPSession): BodyRead {
        val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (len <= 0) return BodyRead.NoContentLength
        val buf = ByteArray(len)
        var off = 0
        try {
            // read may return partial data; loop until the full declared body arrives
            while (off < len) {
                val r = session.inputStream.read(buf, off, len - off)
                if (r <= 0) break   /* peer closed before sending the full body */
                off += r
            }
        } catch (ioEx: IOException) {
            // A timeout or reset mid-body is a short read, not a header problem: the POS declared
            // len and we received off. Reporting it as "missing content-length" sends whoever
            // reads the log after the header, which was fine.
            ioEx.printStackTrace()
            return BodyRead.Short(off, len)
        }
        if (off < len) {
            // A partial body must NOT be parsed as if complete - that is exactly how a short
            // read turns into a bogus "Invalid Input" against a request the POS sent correctly.
            return BodyRead.Short(off, len)
        }
        return BodyRead.Ok(Utility.Byte2ASCII(buf).replace("\n", "").replace("\r", ""))
    }

    // Mirrors the pre-refactor behavior: a TransactionType-0 request received while a
    // session is waiting terminates it (when no transaction is actively processing).
    // Completes the in-flight future too, so the original POS connection also gets SHC009.
    private fun handleCancelWhileBusy(msg: String, pending: CompletableFuture<String>): String? {
        return try {
            val body = concatenateAndValidateLast(msg)
            if (body.isEmpty()) return null
            val obj = JsonParser.parseString(body).asJsonObject
            if (!obj.has("TransactionType") || obj.get("TransactionType").asInt != 0) return null
            if (appRunningProcess) return null

            val jsonResp = JSONObject()
            jsonResp.put("ResponseCode", EnumResponseCode.PAYMENT_SESSION_TERMINATED.code)
            jsonResp.put("ResponseDescription", EnumResponseCode.PAYMENT_SESSION_TERMINATED.description)
            val resp = jsonResp.toString()
            mainScope.launch {
                AppBus.emitWhenSubscribed(UiEvent.EndPaymentSession(true))
            }
            // The in-flight caller asked for a transaction, so it receives that transaction's own
            // result, not this cancel's SHC009. Completing `pending` here would hand it the wrong
            // body and orphan the real one.
            //
            // Bounded, because only CardPaymentFragment, GenerateQrFragment and ScanQrFragment
            // handle EndPaymentSession. A session parked on any other screen publishes nothing, so
            // its caller would block until RESPONSE_TIMEOUT_MS.
            bgScope.launch {
                delay(CANCEL_RESULT_WAIT_MS)
                if (!pending.isDone) {
                    helperLog?.appendLine(
                        helperlogClassName,
                        "No result published ${CANCEL_RESULT_WAIT_MS} ms after cancel :: answering SHC009"
                    )
                    pending.complete(resp)
                }
            }
            resp
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun corsResponse(body: String?): Response {
        val response = newFixedLengthResponse(body ?: "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "POST")
        response.addHeader("Access-Control-Allow-Headers", "X-Requested-With")
        return response
    }

    private fun errorJson(type: Int): String {
        val jsonResponse = JSONObject()
        try {
            when (type) {
                0 -> {
                    jsonResponse.put("ResponseCode", EnumResponseCode.SYSTEM_BUSY.code)
                    jsonResponse.put("ResponseDescription", EnumResponseCode.SYSTEM_BUSY.description)
                }
                1 -> {
                    jsonResponse.put("ResponseCode", EnumResponseCode.INVALID_INPUT.code)
                    jsonResponse.put("ResponseDescription", EnumResponseCode.INVALID_INPUT.description)
                }
                2 -> {
                    jsonResponse.put("ResponseCode", EnumResponseCode.INVALID_REQUEST.code)
                    jsonResponse.put("ResponseDescription", EnumResponseCode.INVALID_REQUEST.description)
                }
                3 -> {
                    jsonResponse.put("ResponseCode", EnumResponseCode.AUTO_SETTLEMENT_RUNNING.code)
                    jsonResponse.put("ResponseDescription", EnumResponseCode.AUTO_SETTLEMENT_RUNNING.description)
                }
                else -> {
                    jsonResponse.put("ResponseCode", EnumResponseCode.UNEXPECTED_ERROR.code)
                    jsonResponse.put("ResponseDescription", EnumResponseCode.UNEXPECTED_ERROR.description)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return jsonResponse.toString()
    }

    private fun defaultError(msg: String, type: Int) {
        setResponseMessage(errorJson(type))
    }

    private fun wakeScreen() {
        try {
            val screenLock =
                (ServiceHolder.getContext().getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ScreenLock:HttpTransaction"
                )
            // Hold briefly so the display is fully awake by the time the activity
            // restarts and collects the navigation event; auto-released by timeout
            screenLock.acquire(3000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleIncomingRequest(requestData: String) {
        val concatenateRequest = concatenateAndValidateLast(requestData)
        if(concatenateRequest.isEmpty()) {
            defaultError("", 1)
            return
        }
        val obj = JsonParser.parseString(concatenateRequest)
        val jsonObject = obj.asJsonObject

        if(ServiceHolder.autoSettlementIsRunning) {
            defaultError("", 3)
        //} else if(!waitingForResponse) {
        } else if(isActive) {
            waitingForResponse = true
            requestMsg = concatenateRequest
            checkTransactionType(jsonObject)
        } else {
            var isError = true
            if(!appRunningProcess) {
                try {
                    if(jsonObject.has("TransactionType")) {
                        val transType = jsonObject.get("TransactionType").asInt
                        if(transType == 0) {
                            isError = false
                            val jsonResp = JSONObject()
                            jsonResp.put("ResponseCode", EnumResponseCode.PAYMENT_SESSION_TERMINATED.code)
                            jsonResp.put("ResponseDescription", EnumResponseCode.PAYMENT_SESSION_TERMINATED.description)
                            setResponseMessage(jsonResp.toString())
                            //TODO Payment Session Terminated
                            mainScope.launch {
                                AppBus.emitWhenSubscribed(UiEvent.EndPaymentSession(true))
                            }
                        }
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    defaultError("", 1)
                }
            }

            if (isError) {
                defaultError("", 0)
            }
        }
    }

    private fun checkTransactionType(requestJson: JsonObject) {
        try {
            // Old vs new integration. This rule is canonical -- see helpers.IntegrationMode;
            // the intent path in TransactionParser defers to the same decision.
            //
            // The flag is read by VALUE: only boolean true or the string "true" selects old
            // integration. It used to be a bare has() check, so a caller explicitly sending
            // "IsOldIntegration": false was routed to old anyway. asString renders a JSON
            // boolean as "true"/"false", so one check covers both shapes.
            val rawFlag = requestJson.get("IsOldIntegration")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            val flagIsTrue = IntegrationMode.flagIsTrue(rawFlag)
            var isTransString = false
            if(requestJson.has("TransactionAmount")) {
                val transStringPrimitive = requestJson.getAsJsonPrimitive("TransactionAmount")
                if(transStringPrimitive.isString) {
                    isTransString = true
                }
            }

            if(IntegrationMode.isOld(flagIsTrue, isTransString)) {
                oldIntegrationType(requestJson)
            } else {
                newIntegrationType(requestJson)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            println("Check Transaction Type Exception")
            defaultError("", 2)
        }
    }

    private fun oldIntegrationType(requestJson: JsonObject) {
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        try{
            ServiceHolder.appHTTP = true
            val resultObject = requestJson.deepCopy().asJsonObject
            val txnType = requestJson.get("TransactionType").asInt
            ServiceHolder.txnType = txnType

            // Wake the screen only when a transaction flow is about to start (not for enquiry/status)
            if (txnType != 0 && txnType != 13) {
                wakeScreen()
            }

            var txnAmount: String? = null
            if(requestJson.has("TransactionAmount")) {
                try {
                    txnAmount = requestJson.get("TransactionAmount").asString
                    txnAmount.toDouble()
                    val ss = txnAmount.toBigDecimal().setScale(2)
                    val ss1 = BigDecimal("999999.99")
                    txnAmount = ss.toString()
                    if (ss > ss1) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    } else if (txnAmount == "0.00") {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }
                } catch (_: Exception) {
                    handler.post {
                        Toast.makeText(ServiceHolder.getContext(), "Invalid Amount", Toast.LENGTH_SHORT).show()
                    }
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                    setResponseMessage(resultObject.toString())
                    throw Exception()
                }
            }
            var posReference: String? = null
            if (requestJson.has("PosReference")) posReference = requestJson.get("PosReference").asString

            if(requestJson.has("AcknowledgeCountdown")){
                try {
                    ServiceHolder.ackCountDownSecond = requestJson.get("AcknowledgeCountdown").asInt
                } catch (_: Exception) {
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (AcknowledgeCountdown)")
                    setResponseMessage(resultObject.toString())
                    throw Exception()
                }
            }

            when (txnType) {
                0 -> {
                    resultObject.addProperty("ResponseCode", "00")
                    resultObject.addProperty("ResponseDescription", "No Session Running")
                    setResponseMessage(resultObject.toString())
                }
                // Sale
                1 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SALES_CARD")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (getBooleanValue(terminalConfig, "FORCE_SETTLEMENT") ||
                        getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                        if (ServiceHolder.clearSettlementBatch) {
                            handler.post {
                                Toast.makeText(ServiceHolder.getContext(), "Please Run Settlement for Last day Transaction before Proceed", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description)
                            setResponseMessage(resultObject.toString())
                            return
                        }
                    }

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    try {
                        val productCat = getProductCatForHttp(txnType)
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                        val jsonProductList = gson.toJson(dbProductModel)
                        val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        saleModelNew.TransAmount = amountLong
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }
                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.cardPaymentFragment, bundleOf("posReference" to posReference)))
                    }
                }
                // Void
                2 -> {
                    if (!requestJson.has("TransactionInvoice")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val txnInvoice: String = requestJson.get("TransactionInvoice").asString

                    var forceVoid = 0
                    if(requestJson.has("ForceVoid")){
                        forceVoid = requestJson.get("ForceVoid").asInt
                    }

                    try {
                        val productCat = getProductCatForHttp(txnType)
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val jsonProductList = gson.toJson(dbProductModel)
                        val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidSaleFragment,
                            bundleOf(
                                "Invoice" to txnInvoice,
                                "forceVoid" to forceVoid,
                                "posReference" to posReference
                            )
                        ))
                    }
                }
                // Settlement
                3 -> {
                    try {
                        val productCat = getProductCatForHttp(txnType)
                        ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.settleOptionFragment, bundleOf("settlementType" to "CARD")))
                    }
                }
                // Pre Auth
                4 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "PreAuth") || !getBooleanValue(terminalConfig, "SALES_CARD")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    try {
                        val productCat = getProductCatForHttp(txnType)
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                        val jsonProductList = gson.toJson(dbProductModel)
                        val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = 8
                        saleModelNew.TransAmount = amountLong
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.cardPaymentFragment, bundleOf("posReference" to posReference)))
                    }
                }
                // Sale Complete
                5 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SaleComOnline") || !getBooleanValue(terminalConfig, "SALES_CARD")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    if (!requestJson.has("TransactionApprovalCode")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionApprovalCode)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionApprovalCode)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    if (!requestJson.has("TransactionRRN")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionRRN)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionRRN)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    if (!requestJson.has("TransactionInvoice")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    try {
                        val productCat = getProductCatForHttp(txnType)
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                        val jsonProductList = gson.toJson(dbProductModel)
                        val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = 4
                        saleModelNew.TransAmount = amountLong
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.keypadSaleCompletionFragment,
                            bundleOf(
                                "apprCode" to requestJson.get("TransactionApprovalCode").asString,
                                "rrn" to requestJson.get("TransactionRRN").asString,
                                "invNo" to requestJson.get("TransactionInvoice").asString,
                                "posReference" to posReference
                            )
                        ))
                    }
                }
                //Pre Auth Cancel
                6 -> {
                    if (!requestJson.has("TransactionInvoice")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val productCat = getProductCatForHttp(txnType)
                    val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                    val jsonProductList = gson.toJson(dbProductModel)
                    val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidPreAuthFragment,
                            bundleOf(
                                "Invoice" to requestJson.get("TransactionInvoice").asString,
                                "posReference" to posReference
                            )
                        ))
                    }
                }
                //E-Wallet Sale
                7 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SALES_EWALLET")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val productCat = getProductCatForHttp(txnType)
                    if (requestJson.has("ProductCode")) {
                        try{
                            val productCode: String = requestJson.get("ProductCode").asString
                            val generateQrModel = ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, productCode, "true"))
                            generateQrModel?.let {
                                val jsonProductList = gson.toJson(generateQrModel)
                                val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                                val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                                saleModelNew.TransAmount = amountLong
                                ServiceHolder.saleModelCache = saleModelNew
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.generateQrFragment, bundleOf("posReference" to posReference)))
                                }
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                                return
                            }
                        } catch (_: Exception) {
                            handler.post {
                                Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                            setResponseMessage(resultObject.toString())
                            return
                        }
                    } else {
                        var cameraFacing: Int = 0
                        if(requestJson.has("CameraFacing")) {
                            val tempCamera = requestJson.get("CameraFacing").asInt
                            if(tempCamera == 1 || tempCamera == 0) {
                                cameraFacing = tempCamera
                            }
                        }

                        try {
                            val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                            val jsonProductList = gson.toJson(dbProductModel)
                            val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                            val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType
                            saleModelNew.TransAmount = amountLong
                            ServiceHolder.saleModelCache = saleModelNew
                            mainScope.launch {
                                AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.scanQrFragment,
                                    bundleOf("posReference" to posReference, "cameraFacing" to cameraFacing)))
                            }
                        } catch (_: Exception) {
                            handler.post {
                                Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                            setResponseMessage(resultObject.toString())
                            return
                        }
                    }
                }
                //E-Wallet Void
                8 -> {
                    if(!requestJson.has("TransactionRefId")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionRefId)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionRefId)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val txnInvoice: String = requestJson.get("TransactionRefId").asString

                    var forceVoid = 0
                    if(requestJson.has("ForceVoid")) {
                        forceVoid = requestJson.get("ForceVoid").asInt
                    }

                    try {
                        var dbProduct = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name))
                        if(dbProduct == null) {
                            dbProduct = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.GENERATE_QR.name)) ?: throw Exception()
                        }
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidQrFragment,
                            bundleOf(
                                "Invoice" to txnInvoice,
                                "forceVoid" to forceVoid,
                                "posReference" to posReference
                            )
                        ))
                    }
                }
                //E-Wallet Settlement
                9 -> {
                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.settleOptionFragment, bundleOf("settlementType" to "QR")))
                    }
                }
                //Void Sale Complete
                10 -> {
                    if(!requestJson.has("TransactionInvoice")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val productCat = getProductCatForHttp(txnType)
                    val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                    val jsonProductList = gson.toJson(dbProductModel)
                    val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew

                    val txnInvoice: String = requestJson.get("TransactionInvoice").asString
                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidSaleCompletionFragment,
                            bundleOf("Invoice" to txnInvoice, "posReference" to posReference)))
                    }
                }
                // MOTO
                11 -> {
                    //TODO
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "MOTO") || !getBooleanValue(terminalConfig, "SALES_CARD")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("CardNumber")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (CardNumber)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (CardNumber)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val cardNumber: String = requestJson.get("CardNumber").asString

                    if (!requestJson.has("ExpiryDate")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (ExpiryDate)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (ExpiryDate)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val expDate: String = requestJson.get("ExpiryDate").asString

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    try {
                        var productCat = getProductCatForHttp(txnType)
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat))
                        if (dbProductModel == null) {
                            //Handle for MOTO merged with CARD
                            Utils.debugLogPrint(TAG, "MOTO merged with CARD")
                            productCat = getProductCatForHttp(1)
                            ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        }
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.keypadMotoFragment,
                            bundleOf(
                                "txnAmt" to amountLong,
                                "cardNumber" to cardNumber,
                                "expDate" to expDate,
                                "posReference" to posReference
                            )
                        ))
                    }
                }
                // EPP
                12 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SALES_CARD")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    try {
                        val productCat = getProductCatForHttp(txnType)
                        ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val amountLong = txnAmount?.replace(".", "")?.toLongOrNull() ?: 0L
                        mainScope.launch {
                            AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.eppAcquirerFragment,
                                bundleOf("txnAmt" to amountLong, "posReference" to posReference)
                            ))
                        }
                    } catch (_: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }
                }
                // Pos References Enquiry
                13 -> {
                    if (!requestJson.has("PosReference")) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (PosReference)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PosReference)")
                    } else {
                        val dbmodelReceiptUpload = ReceiptUploadRepo.getSingleDesc(ServiceHolder.getContext(), listOf("POS_REF_NO"), arrayOf(posReference!!))
                        if(dbmodelReceiptUpload != null) {
                            if(dbmodelReceiptUpload.QrRefId?.trim()?.isNotEmpty() == true){
                                val transactionQrData = TransactionQrRepo.getSingleTransactionQr(ServiceHolder.getContext(), listOf("refId"), listOf(dbmodelReceiptUpload.QrRefId!!))
                                transactionQrData?.let {
                                    val transactionDateTime = if(transactionQrData.txnType?.trim().equals("Void", true)){
                                        transactionQrData.voidDateTime
                                    } else {
                                        transactionQrData.txnDateTime
                                    }

                                    resultObject.addProperty("ResponseCode", transactionQrData.respCode)
                                    resultObject.addProperty("ResponseDescription", transactionQrData.respDesc)
                                    resultObject.addProperty("TransactionLabel", transactionQrData.txnType)
                                    resultObject.addProperty("TransactionAmount", Utils.getActualAmount(transactionQrData.txnAmount))
                                    resultObject.addProperty("TransactionId", transactionQrData.hostRefNo)
                                    resultObject.addProperty("TransactionRefId", transactionQrData.refId)
                                    resultObject.addProperty("TransactionEWallet", transactionQrData.productCode)
                                    resultObject.addProperty("TransactionEWalletDescription", transactionQrData.productName)
                                    resultObject.addProperty("TransactionDateTime", transactionDateTime)
                                } ?: run {
                                    resultObject.addProperty("ResponseCode", EnumResponseCode.QR_TRANSACTION_NOT_FOUND.code)
                                    resultObject.addProperty("ResponseDescription", EnumResponseCode.QR_TRANSACTION_NOT_FOUND.description)
                                }
                            } else {
                                var desc = "Failed"
                                try {
                                    val formedEnumTag = "TAG_${dbmodelReceiptUpload.RESP_CODE}"
                                    desc = "(" + dbmodelReceiptUpload.RESP_CODE + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                //TODO add txnAmount
                                resultObject.addProperty("ResponseCode", dbmodelReceiptUpload.RESP_CODE)
                                resultObject.addProperty("ResponseDescription", desc)
                                resultObject.addProperty("TransactionLabel", dbmodelReceiptUpload.TXN_TYPE)
                                resultObject.addProperty("TransactionAmount", Utils.getActualAmount(dbmodelReceiptUpload.TXN_AMT))
                                resultObject.addProperty("TransactionMID", dbmodelReceiptUpload.MID)
                                resultObject.addProperty("TransactionTID", dbmodelReceiptUpload.TID)
                                resultObject.addProperty("TransactionSTN", dbmodelReceiptUpload.STAN)
                                resultObject.addProperty("TransactionRRN", dbmodelReceiptUpload.RRN)
                                resultObject.addProperty("TransactionBatchNo", dbmodelReceiptUpload.BATCH_NO)
                                resultObject.addProperty("TransactionApplicationLabel", dbmodelReceiptUpload.CARD_LABEL)
                                resultObject.addProperty("TransactionCardNo", dbmodelReceiptUpload.CARD_MASKED)
                                resultObject.addProperty("TransactionEntryType", dbmodelReceiptUpload.ENTRY_TYPE)
                                resultObject.addProperty("TransactionARQC", dbmodelReceiptUpload.ARQC)
                                resultObject.addProperty("TransactionTVR", dbmodelReceiptUpload.TVR)
                                resultObject.addProperty("TransactionAID", dbmodelReceiptUpload.AID)
                                resultObject.addProperty("TransactionCVM", dbmodelReceiptUpload.CVM)
                                resultObject.addProperty("TransactionTSI", "-")
                                resultObject.addProperty("TransactionApprovalCode", dbmodelReceiptUpload.APPR_CODE)
                                resultObject.addProperty("OriTransactionRRN", dbmodelReceiptUpload.RRN_ORI)
                                resultObject.addProperty("OriTransactionApprovalCode", dbmodelReceiptUpload.APPR_CODE_ORI)
                                resultObject.addProperty("TransactionInvoice", dbmodelReceiptUpload.INV_NO)
                                resultObject.addProperty("TransactionSchemeID", dbmodelReceiptUpload.SCHEME_ID)
                                resultObject.addProperty("TransactionDateTime", dbmodelReceiptUpload.TXN_DT)

                                val eppDetail = gson.fromJson(dbmodelReceiptUpload.EPP_DETAIL, EppDetail::class.java)
                                if(eppDetail?.Tenure != null && eppDetail.Tenure != "00"){
                                    resultObject.addProperty("TransactionEPP", dbmodelReceiptUpload.EPP_DETAIL)
                                } else {
                                    resultObject.addProperty("TransactionEPP", "-")
                                }
                            }
                        } else {
                            resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_FOUND.description)
                        }
                    }
                    setResponseMessage(resultObject.toString())
                    throw Exception()
                }
                else -> {
                    Toast.makeText(ServiceHolder.getContext(), "Invalid Transaction Type", Toast.LENGTH_SHORT).show()
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionType)")
                    setResponseMessage(resultObject.toString())
                }
            }
        } catch (ex: Exception) {
            ServiceHolder.appHTTP = false
            ex.printStackTrace()
            if(responseMsg == null) {
                defaultError("", 1)
            }
        }
    }

    private fun newIntegrationType(requestJson: JsonObject) {
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)
        try {
            ServiceHolder.appHTTP = true
            var transType = 99
            if (requestJson.has("TransactionType")) {
                transType = requestJson.get("TransactionType").asInt
            }
            val resultObject = requestJson.deepCopy().asJsonObject
            ServiceHolder.txnType = transType

            // Wake the screen only when a transaction flow is about to start (not for enquiry/status)
            if (transType in 2..5) {
                wakeScreen()
            }

            var posReference: String? = null
            if (requestJson.has("PosReference")) {
                posReference = requestJson.get("PosReference").asString
            }

            var orderingItemImage: String? = null
            if (requestJson.has("OrderingItemImage")) {
                orderingItemImage = requestJson.get("OrderingItemImage").asString
            }
            var orderingItem: String? = null
            if (requestJson.has("OrderingItem") && orderingItemImage.isNullOrEmpty()) {
                orderingItem = requestJson.get("OrderingItem").asString
            }

            if(requestJson.has("AcknowledgeCountdown")){
                ServiceHolder.ackCountDownSecond = requestJson.get("AcknowledgeCountdown").asInt
            }

            when (transType) {
                0 -> {
                    resultObject.addProperty("ResponseCode", "00")
                    resultObject.addProperty("ResponseDescription", "No Session Running")
                    setResponseMessage(resultObject.toString())
                }
                1 -> {
                    //TODO Ewallet Enquiry
                    posReference?.let {
                        //TODO Enquiry
                        val dbModelReceiptUpload = ReceiptUploadRepo.getSingleDesc(ServiceHolder.mContext, listOf("POS_REF_NO"), arrayOf(posReference))
                        if(dbModelReceiptUpload != null){
                            var desc = "Failed"
                            try {
                                val formedEnumTag = "TAG_${dbModelReceiptUpload.RESP_CODE}"
                                desc = "(" + dbModelReceiptUpload.RESP_CODE + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            if(dbModelReceiptUpload.QrRefId?.trim()?.isNotEmpty() == true){
                                val transactionQrData = TransactionQrRepo.getSingleTransactionQr(ServiceHolder.mContext, listOf("refId"), listOf(dbModelReceiptUpload.QrRefId!!))
                                transactionQrData?.let {
                                    val transactionDateTime = if(transactionQrData.txnType?.trim().equals("Void", true)){
                                        transactionQrData.voidDateTime
                                    } else {
                                        transactionQrData.txnDateTime
                                    }

                                    resultObject.addProperty("ResponseCode", transactionQrData.respCode)
                                    resultObject.addProperty("ResponseDescription", transactionQrData.respDesc)
                                    resultObject.addProperty("TransactionLabel", transactionQrData.txnType)
                                    resultObject.addProperty("TransactionAmount", Utils.getActualAmount(transactionQrData.txnAmount))
                                    resultObject.addProperty("TransactionId", transactionQrData.hostRefNo)
                                    resultObject.addProperty("TransactionRefId", transactionQrData.refId)
                                    resultObject.addProperty("TransactionEWallet", transactionQrData.productCode)
                                    resultObject.addProperty("TransactionEWalletDescription", transactionQrData.productName)
                                    resultObject.addProperty("TransactionDateTime", transactionDateTime)
                                } ?: run {
                                    resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                                    resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_FOUND.description)
                                }
                            } else {
                                resultObject.addProperty("ResponseCode", dbModelReceiptUpload.RESP_CODE)
                                resultObject.addProperty("ResponseDescription", desc)
                                resultObject.addProperty("TransactionLabel", dbModelReceiptUpload.TXN_TYPE)
                                resultObject.addProperty("TransactionAmount", Utils.getActualAmount(dbModelReceiptUpload.TXN_AMT))
                                resultObject.addProperty("TransactionMID", dbModelReceiptUpload.MID)
                                resultObject.addProperty("TransactionTID", dbModelReceiptUpload.TID)
                                resultObject.addProperty("TransactionSTN", dbModelReceiptUpload.STAN)
                                resultObject.addProperty("TransactionRRN", dbModelReceiptUpload.RRN)
                                resultObject.addProperty("TransactionBatchNo", dbModelReceiptUpload.BATCH_NO)
                                resultObject.addProperty("TransactionApplicationLabel", dbModelReceiptUpload.CARD_LABEL)
                                resultObject.addProperty("TransactionCardNo", dbModelReceiptUpload.CARD_MASKED)
                                resultObject.addProperty("TransactionEntryType", dbModelReceiptUpload.ENTRY_TYPE)
                                resultObject.addProperty("TransactionARQC", dbModelReceiptUpload.ARQC)
                                resultObject.addProperty("TransactionTVR", dbModelReceiptUpload.TVR)
                                resultObject.addProperty("TransactionAID", dbModelReceiptUpload.AID)
                                resultObject.addProperty("TransactionCVM", dbModelReceiptUpload.CVM)
                                resultObject.addProperty("TransactionApprovalCode", dbModelReceiptUpload.APPR_CODE)
                                resultObject.addProperty("TransactionInvoice", dbModelReceiptUpload.INV_NO)
                                resultObject.addProperty("TransactionSchemeID", dbModelReceiptUpload.SCHEME_ID)
                                resultObject.addProperty("TransactionDateTime", dbModelReceiptUpload.TXN_DT)
                                /*
                                val eppDetail = gson.fromJson(dbmodelReceiptUpload.EPP_DETAIL, EppDetail::class.java)
                                if(eppDetail?.Tenure != null && eppDetail.Tenure != "00"){
                                    resultObject.put("TransactionEPP", dbmodelReceiptUpload.EPP_DETAIL)
                                } else {
                                    resultObject.put("TransactionEPP", "-")
                                }*/
                                resultObject.addProperty("TransactionEPP", "-")
                            }
                        } else {
                            resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_FOUND.description)
                        }
                        setResponseMessage(resultObject.toString())
                    } ?: run {
                        handler.post {
                            Toast.makeText(ServiceHolder.mContext, "Invalid Parameter - (PosReference)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PosReference)")
                        setResponseMessage(resultObject.toString())
                    }
                }
                2 -> {
                    //TODO SALES
                    var txnAmount: Long = 0
                    if(requestJson.has("TransactionAmount")) {
                        var toastMessage = "Invalid Amount"
                        try{
                            txnAmount = (requestJson.get("TransactionAmount").asNumber).toLong()
                            val amountLimit = 999999999
                            if (txnAmount > amountLimit) {
                                toastMessage = "Trade amount should be less than 999999.99"
                                throw Exception()
                            } else if (txnAmount <= 0) {
                                toastMessage = "Trade amount should be greater than 0"
                                throw Exception()
                            }
                        } catch (_: Exception) {
                            handler.post {
                                Toast.makeText(ServiceHolder.mContext, toastMessage, Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", "SHC001")
                            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                            setResponseMessage(resultObject.toString())
                            throw Exception()
                        }
                    } else {
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    val paymentChannel: String
                    if(requestJson.has("PaymentChannel")){
                        paymentChannel = requestJson.get("PaymentChannel").asString
                    } else {
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    val terminalConfig = getTerminalConfig()
                    val qrList = listOf("SCAN", "QR")
                    var isCard = false
                    val isEnableSales = if (paymentChannel in qrList) {
                        getBooleanValue(terminalConfig, "SALES_EWALLET")
                    } else {
                        isCard = true
                        getBooleanValue(terminalConfig, "SALES_CARD")
                    }
                    if(!isEnableSales) {
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    if(isCard) {
                        if (getBooleanValue(terminalConfig, "FORCE_SETTLEMENT") ||
                            getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                            if (ServiceHolder.clearSettlementBatch) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "Please Run Settlement for Last day Transaction before Proceed", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description)
                                setResponseMessage(resultObject.toString())
                                return
                            }
                        }
                    }

                    when(paymentChannel.trim().uppercase()){
                        "CARD" -> {
                            val cardProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                            cardProductModel?.let {
                                val jsonProductList = gson.toJson(cardProductModel)
                                val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                                saleModelNew.TransAmount = txnAmount
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                                ServiceHolder.saleModelCache = saleModelNew
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.cardPaymentFragment,
                                        bundleOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage)
                                    ))
                                }
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        "SCAN" -> {
                            var cameraFacing: Int = 1
                            if(requestJson.has("CameraFacing")){
                                val tempCamera = requestJson.get("CameraFacing").asInt
                                if(tempCamera == 1 || tempCamera == 0) {
                                    cameraFacing = tempCamera
                                }
                            }

                            val qrScanModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, "true"))
                            qrScanModel?.let {
                                val jsonProductList = gson.toJson(qrScanModel)
                                val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType
                                saleModelNew.TransAmount = txnAmount
                                ServiceHolder.saleModelCache = saleModelNew
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.scanQrFragment,
                                        bundleOf("posReference" to posReference, "cameraFacing" to cameraFacing, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage)))
                                }
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        "QR" -> {
                            val paymentCode: String
                            if(requestJson.has("PaymentCode")){
                                paymentCode = requestJson.get("PaymentCode").asString.trim().uppercase()
                            } else {
                                resultObject.addProperty("ResponseCode", "SHC001")
                                resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentCode)")
                                setResponseMessage(resultObject.toString())
                                throw Exception()
                            }

                            val generateQrModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, paymentCode, "true"))
                            generateQrModel?.let {
                                val jsonProductList = gson.toJson(generateQrModel)
                                val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                                saleModelNew.TransAmount = txnAmount
                                ServiceHolder.saleModelCache = saleModelNew
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.generateQrFragment,
                                        bundleOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage)))
                                }
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        "EPP" -> {
                            val eppAcquirerList = ProductListRepo.getDistinctEppAcquirer(ServiceHolder.mContext)
                            if(eppAcquirerList.isNotEmpty()) {
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.eppAcquirerFragment,
                                        bundleOf("txnAmt" to txnAmount, "posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage)
                                    ))
                                }
                            } else {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        "MOTO" -> {
                            if (!requestJson.has("CardNumber")) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (CardNumber)", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", "SHC001")
                                resultObject.addProperty("ResponseDescription", "Invalid Parameter - (CardNumber)")
                                setResponseMessage(resultObject.toString())
                                throw Exception()
                            }
                            val cardNumber: String = requestJson.get("CardNumber").asString

                            if (!requestJson.has("ExpiryDate")) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (ExpiryDate)", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", "SHC001")
                                resultObject.addProperty("ResponseDescription", "Invalid Parameter - (ExpiryDate)")
                                setResponseMessage(resultObject.toString())
                                throw Exception()
                            }
                            val expDate: String = requestJson.get("ExpiryDate").asString

                            if (!requestJson.has("TransactionAmount")) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", "SHC001")
                                resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                                setResponseMessage(resultObject.toString())
                                throw Exception()
                            }

                            try {
                                var dbProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.MOTO.name, "true"))
                                if (dbProductModel == null) {
                                    //Handle for MOTO merged with CARD
                                    Utils.debugLogPrint(TAG, "MOTO merged with CARD")
                                    dbProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                                }

                                dbProductModel?.let {
                                    mainScope.launch {
                                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.keypadMotoFragment,
                                            bundleOf(
                                                "txnAmt" to txnAmount,
                                                "cardNumber" to cardNumber,
                                                "expDate" to expDate,
                                                "posReference" to posReference,
                                                "orderingItem" to orderingItem,
                                                "orderingItemImage" to orderingItemImage
                                            )
                                        ))
                                    }
                                } ?: run {
                                    handler.post {
                                        Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                    }
                                    resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                    resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                    setResponseMessage(resultObject.toString())
                                }
                            } catch (_: Exception) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                                setResponseMessage(resultObject.toString())
                                return
                            }
                        }
                        else -> {
                            handler.post {
                                Toast.makeText(ServiceHolder.mContext, "Invalid Payment Channel", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.INVALID_PAYMENT_CHANNEL.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.INVALID_PAYMENT_CHANNEL.description)
                            setResponseMessage(resultObject.toString())
                        }
                    }
                }
                3 -> {
                    //TODO VOID
                    val txnInvoice: String
                    if(requestJson.has("TransactionInvoice")){
                        txnInvoice = requestJson.get("TransactionInvoice").asString
                    } else {
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    var forceVoid = 0
                    if(requestJson.has("ForceVoid")){
                        forceVoid = requestJson.get("ForceVoid").asInt
                    }

                    val paymentChannel: String
                    if(requestJson.has("PaymentChannel")){
                        paymentChannel = requestJson.get("PaymentChannel").asString
                    } else {
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    when(paymentChannel.trim().uppercase()){
                        "CARD", "EPP" -> {
                            val cardProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                            cardProductModel?.let {
                                val jsonProductList = gson.toJson(cardProductModel)
                                val saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                                ServiceHolder.saleModelCache = saleModelNew

                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidSaleFragment,
                                        bundleOf(
                                            "Invoice" to txnInvoice,
                                            "forceVoid" to forceVoid,
                                            "posReference" to posReference
                                        )
                                    ))
                                }
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        "QR" -> {
                            val scanModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, "true"))
                            val generateQrModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, "true"))

                            if(scanModel != null || generateQrModel != null){
                                mainScope.launch {
                                    AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidQrFragment,
                                        bundleOf(
                                            "Invoice" to txnInvoice,
                                            "forceVoid" to forceVoid,
                                            "posReference" to posReference
                                        )
                                    ))
                                }
                            } else {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                                setResponseMessage(resultObject.toString())
                            }
                        }
                        else -> {
                            handler.post {
                                Toast.makeText(ServiceHolder.mContext, "Invalid Payment Channel", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.INVALID_PAYMENT_CHANNEL.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.INVALID_PAYMENT_CHANNEL.description)
                            setResponseMessage(resultObject.toString())
                        }
                    }
                }
                4 -> {
                    //TODO SETTLEMENT
                    val settlementType: String
                    if(requestJson.has("PaymentChannel")) {
                        val tempType = if (requestJson.get("PaymentChannel").asString == "EPP") "CARD" else requestJson.get("PaymentChannel").asString
                        val listSettlementType = listOf<String>("ALL", "CARD", "QR")
                        if(listSettlementType.contains(tempType.trim().uppercase())){
                            settlementType = tempType
                        } else {
                            resultObject.addProperty("ResponseCode", "SHC001")
                            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
                            setResponseMessage(resultObject.toString())
                            throw Exception()
                        }
                    } else if (requestJson.has("SettlementType")) {
                        val tempType = if (requestJson.get("SettlementType").asString == "EPP") "CARD" else requestJson.get("SettlementType").asString
                        val listSettlementType = listOf<String>("ALL", "CARD", "QR")
                        if(listSettlementType.contains(tempType.trim().uppercase())){
                            settlementType = tempType
                        } else {
                            resultObject.addProperty("ResponseCode", "SHC001")
                            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
                            setResponseMessage(resultObject.toString())
                            throw Exception()
                        }
                    } else {
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    mainScope.launch {
                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.settleOptionFragment, bundleOf("settlementType" to settlementType)))
                    }
                }
                5 -> {
                    // PreAuth
                    if(!requestJson.has("PreAuthType")) {
                        sendInvalidParameterResponse("PreAuthType")
                        throw Exception()
                    }

                    var saleModelNew: SaleModelNew? = null
                    val cardProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    cardProductModel?.let {
                        val jsonProductList = gson.toJson(cardProductModel)
                        saleModelNew = gson.fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew?.SalesType = 8
                    } ?: run {
                        handler.post {
                            Toast.makeText(ServiceHolder.mContext, "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    val preAuthType: String = requestJson.get("PreAuthType").asString
                    when(preAuthType.trim().uppercase()) {
                        "PREAUTH", "PREAUTHCOMPLETE" -> {
                            val terminalConfig = getTerminalConfig()
                            val isEnableSales = getBooleanValue(terminalConfig, "SALES_CARD")
                            if(!isEnableSales) {
                                resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                                resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                                setResponseMessage(resultObject.toString())
                                throw Exception()
                            }

                            val txnAmount = validateTransactionAmount(
                                requestJson = requestJson,
                                allowZero = preAuthType.trim().uppercase() == "PREAUTHCOMPLETE",
                                handler = handler
                            )
                            saleModelNew?.TransAmount = txnAmount
                            ServiceHolder.saleModelCache = saleModelNew

                            //TODO Ordering Item
                            when(preAuthType.trim().uppercase()) {
                                "PREAUTH" -> {
                                    mainScope.launch {
                                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.cardPaymentFragment,
                                            bundleOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage)))
                                    }
                                }
                                "PREAUTHCOMPLETE" -> {
                                    if (!requestJson.has("TransactionApprovalCode")){
                                        handler.post {
                                            Toast.makeText(ServiceHolder.mContext, "Invalid Parameter - (TransactionApprovalCode)", Toast.LENGTH_SHORT).show()
                                        }
                                        sendInvalidParameterResponse("TransactionApprovalCode")
                                        throw Exception()
                                    }
                                    if (!requestJson.has("TransactionRRN")){
                                        handler.post {
                                            Toast.makeText(ServiceHolder.mContext, "Invalid Parameter - (TransactionRRN)", Toast.LENGTH_SHORT).show()
                                        }
                                        sendInvalidParameterResponse("TransactionRRN")
                                        throw Exception()
                                    }
                                    if (!requestJson.has("TransactionInvoice")){
                                        handler.post {
                                            Toast.makeText(ServiceHolder.mContext, "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                                        }
                                        sendInvalidParameterResponse("TransactionInvoice")
                                        throw Exception()
                                    }

                                    mainScope.launch {
                                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.keypadSaleCompletionFragment,
                                            bundleOf(
                                                "apprCode" to requestJson.get("TransactionApprovalCode").asString,
                                                "rrn" to requestJson.get("TransactionRRN").asString,
                                                "invNo" to requestJson.get("TransactionInvoice").asString,
                                                "posReference" to posReference,
                                                "orderingItem" to orderingItem,
                                                "orderingItemImage" to orderingItemImage
                                            )
                                        ))
                                    }
                                }
                            }
                        }
                        "VOIDPREAUTH", "VOIDPREAUTHCOMPLETE" -> {
                            if (!requestJson.has("TransactionInvoice")) {
                                handler.post {
                                    Toast.makeText(ServiceHolder.mContext, "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show()
                                }
                                sendInvalidParameterResponse("TransactionInvoice")
                                throw Exception()
                            }
                            val txnInvoice: String = requestJson.get("TransactionInvoice").asString
                            ServiceHolder.saleModelCache = saleModelNew

                            when(preAuthType.trim().uppercase()) {
                                "VOIDPREAUTH" -> {
                                    mainScope.launch {
                                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidPreAuthFragment,
                                            bundleOf("Invoice" to txnInvoice, "posReference" to posReference)))
                                    }
                                }
                                "VOIDPREAUTHCOMPLETE" -> {
                                    mainScope.launch {
                                        AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(R.id.voidSaleCompletionFragment,
                                            bundleOf("Invoice" to txnInvoice, "posReference" to posReference)))
                                    }
                                }
                            }
                        }
                        else -> {
                            sendInvalidParameterResponse("PreAuthType")
                        }
                    }
                }
                else -> {
                    handler.post {
                        Toast.makeText(ServiceHolder.mContext, "Invalid Transaction Type", Toast.LENGTH_SHORT).show()
                    }
                    resultObject.addProperty("ResponseCode", EnumResponseCode.INVALID_TRANSACTION_TYPE.code)
                    resultObject.addProperty("ResponseDescription", EnumResponseCode.INVALID_TRANSACTION_TYPE.description)
                    setResponseMessage(resultObject.toString())
                }
            }
        } catch (ex:Exception) {
            ServiceHolder.appHTTP = false
            ex.printStackTrace()
            if(responseMsg == null) {
                defaultError("", 1)
            }
        }
    }

    private fun isJSONValid(jsonString: String): Boolean {
        return try {
            val jsonElement: JsonElement = JsonParser.parseString(jsonString)
            jsonElement.isJsonObject || jsonElement.isJsonArray
        } catch (_: JsonSyntaxException) {
            false // Parsing failed, not valid JSON
        }
    }

    private val jsonSplitRegex = "(?<=\\})".toRegex()

    private fun concatenateAndValidateLast(jsonString: String): String {
        val jsonObjects = jsonString.split(jsonSplitRegex).filter { it.trim().isNotBlank() }
        val lastJsonObject = jsonObjects.lastOrNull() ?: return ""
        if(isJSONValid(lastJsonObject)) {
            return lastJsonObject
        }
        return ""
    }

    private fun validateTransactionAmount(
        requestJson: JsonObject,
        allowZero: Boolean = false,
        handler: Handler
    ): Long {
        if (!requestJson.has("TransactionAmount")) {
            sendInvalidParameterResponse("TransactionAmount")
        }

        var result: Long = -1
        try {
            val amount = requestJson.get("TransactionAmount").asNumber.toLong()
            val amountLimit = 999999999

            when {
                amount > amountLimit -> {
                    handler.post {
                        Toast.makeText(ServiceHolder.mContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
                    }
                    sendInvalidParameterResponse("TransactionAmount")
                }

                amount < 0 || (!allowZero && amount == 0L) -> {
                    val message = if (allowZero) "Trade amount should be greater than or equal to 0"
                    else "Trade amount should be greater than 0"
                    handler.post {
                        Toast.makeText(ServiceHolder.mContext, message, Toast.LENGTH_SHORT).show()
                    }
                    sendInvalidParameterResponse("TransactionAmount")
                }

                else -> result = amount
            }
        } catch (_: Exception) {
            handler.post {
                Toast.makeText(ServiceHolder.mContext, "Invalid Amount", Toast.LENGTH_SHORT).show()
            }
            sendInvalidParameterResponse("TransactionAmount")
        }
        return result
    }

    private fun sendInvalidParameterResponse(paramName: String) {
        val resultObject = JsonObject()
        resultObject.addProperty("ResponseCode", "SHC001")
        resultObject.addProperty("ResponseDescription", "Invalid Parameter - ($paramName)")
        setResponseMessage(resultObject.toString())
        throw Exception()
    }

    private fun onBackToRS232(msg: String){
        waitingForResponse = false
        val msgByte = msg.toByteArray()
        serialPortDriver?.let {
            val sendStatus = it.send(msgByte, msgByte.size)
            helperLog?.appendLine(helperlogClassName, "onBackToRS232 Send Status :: $sendStatus")
        }
        usbSerialPort?.let {
            val sendStatus = it.write(msgByte, msgByte.size, 0)
            helperLog?.appendLine(helperlogClassName, "onBackToRS232 Send Status :: $sendStatus")
        }
    }

    //WebSocket
    @JvmStatic
    fun startWebSocketServer() {
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
        val tmpHelperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
            Utils.getIPAddress(),
            helperlogClassName,
            helperlogClassName,
            "HTTP Server"
        )
        tmpHelperLog.appendLine(helperlogClassName, "startWebSocketServer")
        tmpHelperLog.appendLine(helperlogClassName, "Connection Method :: ", connMethod)
        if(connMethod == "WEBSOCKET_SERVER") {
            socketInterface = 2
            // Explicit outcome per branch. This used to log only on entry, above the guard, so a
            // healthy no-op and an actual restart produced the same line -- which reads as a
            // per-transaction restart when it is nothing of the kind.
            if(!socketConnected) {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server :: NOT running -> starting (retry loop until bound)")
                serviceScope?.cancel()
                serviceScope = CoroutineScope(Dispatchers.IO)
                serviceScope?.launch {
                    var attempt = 0
                    do {
                        attempt++
                        try {
                            // stopServer (not stop) so the old instance's processing
                            // coroutine is cancelled and does not leak per retry
                            webSocketServer?.stopServer()
                            webSocketServer = WebSocketServer(8080)
                            webSocketServer!!.start()
                            delay(2000)
                        } catch (ex: Exception) {
                            delay(2000)
                            tmpHelperLog.appendLine(helperlogClassName,
                                "WebSocket server :: start attempt $attempt failed :: ${ex.javaClass.simpleName}: ${ex.message}")
                        }
                    } while (!WebSocketServer.socketConnected)
                    socketConnected = WebSocketServer.socketConnected
                    tmpHelperLog.appendLine(helperlogClassName, "WebSocket server :: bound and listening on 8080 after $attempt attempt(s)")
                    tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                }
            } else {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server :: already running, no action taken")
            }
        } else {
            tmpHelperLog.appendLine(helperlogClassName, "reset port from web socket")
            if(socketConnected) {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server :: stopping (connection method is now $connMethod)")
                stopWebSocketServer()
            } else {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server :: not in use (connection method is $connMethod)")
            }
        }
        tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    fun stopWebSocketServer() {
        // Unconditional: a server whose bind is still in progress (socketConnected not
        // yet true) must also be stopped, otherwise it leaks and holds port 8080
        socketInterface = -1
        socketConnected = false
        webSocketRequest = false
        webSocketServer?.stopServer()
        webSocketServer = null
        serviceScope?.cancel()
    }

    @JvmStatic
    fun checkWebSocketIncoming(incomingMessage: String) {
        webSocketRequest = true
        helperLog?.appendLine(helperlogClassName, "Check WebSocket Incoming :: ${HelperCommon.oneLine(incomingMessage)}")
        dispatchTransportRequest(Origin.WEBSOCKET, incomingMessage)
    }
    //WebSocket

    //WebSocket Client
    /*fun startWebSocketClient(url: String) {
        webSocketClient = WebSocketClient(url, true, ServiceHolder.getContext())
        webSocketClient?.startWebSocket()
    }
    @JvmStatic
    fun stopWebSocketClient() {
        webSocketClient?.stopWebSocket()
        webSocketClient = null
    }

    fun getWebSocketClient(): WebSocketClient? = webSocketClient*/
    //WebSocket Client
}