package com.sc.mf919.kotlin.helper_common
import enums.EnumResponseCode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.library.terminal.Utility
import com.morefun.yapi.ServiceResult
import com.morefun.yapi.device.serialport.SerialPort
import com.morefun.yapi.device.serialport.SerialPortDriver
import com.sc.mf919.BuildConfig
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import utils.Util
import com.sc.mf919.kotlin.activity.*
import data_enum.CardErrorDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum.Companion.getProductCatForHttp
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appRunningProcess
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
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
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SuppressLint("StaticFieldLeak")
object HTTPServer: NanoHTTPD(8888) {
    val TAG = "HTTPSERVER"
    var httpServer: HTTPServer? = null

    // Written by the thread that finishes the transaction and polled by the nanohttpd worker in
    // serve(), so both need @Volatile to see each other's writes.
    @Volatile
    var requestMsg: String? = null
    @Volatile
    var responseMsg: String? = null

    /**
     * One request at a time across all transports (HTTP, cable, websocket).
     *
     * requestMsg/responseMsg are shared by every transport, so a second concurrent request must
     * not overwrite the first. The slot carries a future rather than a flag, so the transports
     * that cannot block -- cable and websocket -- are answered by a callback instead of polling.
     */
    private val requestLock = Any()
    private var inFlight: InFlight? = null

    // The last completed request, for the retry cache in submitRequest.
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

    private val bgScope = CoroutineScope(Dispatchers.IO)

    /**
     * Longest a caller waits for a transaction to produce a response before giving up.
     *
     * Set high because a card transaction with PIN entry and a slow host legitimately takes
     * minutes. This is a backstop against a stuck request, not a transaction timeout.
     */
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

    // Written from the main thread (MF919.onActivityResumed) and read from nanohttpd worker
    // threads, so it needs @Volatile to be seen promptly.
    @Volatile
    var isActive = false
    @Volatile
    var waitingForResponse = false

    var attendActivityContext: Activity? = null
        //get() = field
        //set(value) { field = value}

    // #1-USB/RS232, #2-WEBSOCKET, #3-MDB
    var socketInterface = -1

    //TODO USB Configuration
    private var serialPortDriver: SerialPortDriver? = null
    private var cableRequest = false
    private var comport = 4
    private var portOpen = false

    //TODO RS232 Configuration
    private var usbSerialPort: SerialPort? = null
    private var usbPath = "dev/ttyUSB0"
    private val baudRate = 115200
    private val dataBits = 8
    private val stopBits = 1
    private val parity = 0

    //TODO HelperLog
    var helperLog: HelperLog? = null
    val helperlogClassName:String  = this::class.java.simpleName

    //TODO WEBSOCKET
    private var webSocketRequest = false
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

        // uppercase: a lowercase value from TMS config matched no branch at all
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
                    portOpen = false
                    cableRequest = false
                }
            }
        }
        tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    fun resetCommunicationPort() {
        if(socketInterface == 1) {
            val dbModelTerminalConfig = getTerminalConfig()
            val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
            when (connMethod) {
                "USB" -> {
                    serialPortDriver = DeviceHelper.getSerialPortDriver(comport)
                    serialPortDriver?.let {
                        serviceScope?.cancel()
                        it.clrBuffer()
                        it.disconnect()

                        Util.DelayMili(500)
                        it.connect("115200,N,8,1")
                    }
                }
                "RS232" -> {
                    usbSerialPort = DeviceHelper.getUsbSerialPort(usbPath)
                    usbSerialPort?.let {
                        it.clearInputBuffer()
                        it.close()

                        Util.DelayMili(500)
                        it.openAndInit(baudRate, dataBits, stopBits, parity)
                    }
                }
            }
        } else if (socketInterface == 2) {
            startWebSocketServer()
        }
    }

    private fun cableConnectionReceiving(connMethod: String) {
        CoroutineScope(Dispatchers.Default).launch {
            var tempResult = ""
            while (portOpen) {
                val tmpHelperLog = HelperLog(
                    HelperCommon.getSession(),
                    TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
                    Utils.getIPAddress(),
                    helperlogClassName,
                    helperlogClassName,
                    "HTTP Server"
                )
                //tmpHelperLog.appendLine(helperlogClassName, "Check Receive Cable Data")
                try {
                    var recvBytes = ByteArray(1024)
                    val read = when (connMethod) {
                        "USB" -> {serialPortDriver!!.recv(recvBytes, recvBytes.size, 1000)}
                        "RS232" -> {usbSerialPort!!.read(recvBytes, recvBytes.size, 1000)}
                        else -> 0
                    }
                    //tmpHelperLog.appendLine(helperlogClassName, "Cable Data Len >> $read")
                    if (read > 0) {
                        recvBytes = Arrays.copyOfRange(recvBytes, 0, read)
                        val rcvMsg2 = Utils.byteArrayToAsciiString(recvBytes)
                        tempResult += rcvMsg2
                    } else {
                        if(tempResult.isNotEmpty()){
                            tmpHelperLog.appendLine(helperlogClassName, "Final Message :: $tempResult")
                            tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                            helperLog = tmpHelperLog
                            dispatchTransportRequest(Origin.CABLE, tempResult)
                        }
                        tempResult = ""
                    }
                } catch (re: RemoteException) {
                    re.printStackTrace()
                }  catch (ex: Exception) {
                    ex.printStackTrace()
                }
                SystemClock.sleep(500)
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
            //appRunningProcess = false
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
        helperLog?.appendLine(helperlogClassName, "serve(${session.method.name}) :: ${session.remoteIpAddress}")

        // One summary line instead of ~8 header lines per request. content-length, host,
        // user-agent and accept never diagnosed a transaction; the caller and content type do.
        val h = session.headers
        helperLog?.appendLine(helperlogClassName, "Request from ${h["remote-addr"] ?: "?"} :: " +
            "type=${h["content-type"] ?: "-"} len=${h["content-length"] ?: "-"} ua=${h["user-agent"] ?: "-"}")

        val bodyRead = readRequestBody(session)
        if (bodyRead is BodyRead.Short) {
            // Genuinely incomplete request -- report it as such rather than parsing a fragment.
            helperLog?.appendLine(helperlogClassName, "Short Read :: ", "${bodyRead.read}/${bodyRead.expected}")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(errorJson(1))
        }
        if (bodyRead !is BodyRead.Ok) {
            helperLog?.appendLine(helperlogClassName, "Missing/Invalid content-length :: ", "${h["content-length"]}")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            return corsResponse(errorJson(0))
        }

        val msg = bodyRead.msg
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
                // Nothing answered in time. SHC007 "Terminal Response Timeout" means "query before
                // retry": the request was accepted and ran, so the cardholder may already have been
                // charged. SHC000 would say "safe to retry" and invite a double charge.
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

    // Single admission point shared by all transports (HTTP, cable, websocket).
    // Guarantees: one transaction in flight at a time, an identical retry attaches to the
    // in-flight response instead of starting a second one, and TransactionType 0 can terminate a
    // waiting session rather than being refused as busy.
    private fun submitRequest(origin: Origin, body: String): SubmitResult {
        synchronized(requestLock) {
            // Admission point for HTTP, cable and WebSocket alike: nothing is accepted while a
            // load is in progress. The guard, not the raw flag: a claim that outlives the ceiling
            // is treated as abandoned rather than closing ECR for the life of the process.
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

    // Entry point for the fire-and-forget transports (cable / websocket): they cannot block like
    // an HTTP connection, so the response is delivered by a future callback, with the same
    // overall timeout as HTTP.
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
     * A TransactionType-0 request arriving while a session is waiting terminates it.
     *
     * Returns null when this is not a cancel, or when appRunningProcess says the transaction has
     * gone too far to abandon -- the caller then treats it as an ordinary busy request. Completes
     * the in-flight future too, bounded, so the original POS connection is answered as well.
     */
    private fun handleCancelWhileBusy(msg: String, pending: CompletableFuture<String>): String? {
        return try {
            val body = concatenateAndValidateLast(msg)
            if (body.isEmpty()) return null
            val obj = JsonParser.parseString(body).asJsonObject
            if (!obj.has("TransactionType") || obj.get("TransactionType").asInt != 0) return null
            if (appRunningProcess) return null

            helperLog?.appendLine(helperlogClassName, "Cancel request :: terminating current session")
            isActive = true

            when (val ctx = getInstance().attendActivityContext) {
                is CardPaymentActivity -> Handler(Looper.getMainLooper()).post {
                    ctx.stopSearch()
                    ctx.endEMV()
                }
                is QrScanActivity -> ctx.abortSession()
                is GenerateQrActivity -> ctx.abortSession()
                else -> {
                    val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
                    var newIntent = Intent(ServiceHolder.getContext(), AttendActivity::class.java)
                    if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")) {
                        newIntent = Intent(ServiceHolder.getContext(), UnattendActivity::class.java)
                    }
                    newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(newIntent)
                }
            }

            val jsonResp = JSONObject()
            jsonResp.put("ResponseCode", EnumResponseCode.PAYMENT_SESSION_TERMINATED.code)
            jsonResp.put("ResponseDescription", EnumResponseCode.PAYMENT_SESSION_TERMINATED.description)
            val resp = jsonResp.toString()

            // The in-flight caller asked for a transaction, so it gets that transaction's own
            // result, not this cancel's SHC009. Completing `pending` here would hand it the wrong
            // body and orphan the real one.
            //
            // Bounded, because a session parked on a screen that publishes no result would
            // otherwise leave its caller blocked until RESPONSE_TIMEOUT_MS.
            bgScope.launch {
                delay(CANCEL_RESULT_WAIT_MS)
                if (!pending.isDone) {
                    helperLog?.appendLine(helperlogClassName,
                        "No result published $CANCEL_RESULT_WAIT_MS ms after cancel :: answering SHC009")
                    pending.complete(resp)
                }
            }
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            resp
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun wakeScreen() {
        try {
            val screenLock =
                (ServiceHolder.getContext().getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ScreenLock:HttpTransaction"
                )
            // Held 3s on a timeout rather than acquired-and-released immediately,
            // which did not keep the screen up long enough to show the transaction.
            screenLock.acquire(3000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun corsResponse(body: String?): Response {
        val response = newFixedLengthResponse(body ?: "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "POST")
        response.addHeader("Access-Control-Allow-Headers", "X-Requested-With")
        return response
    }

    private sealed class BodyRead {
        data class Ok(val msg: String) : BodyRead()
        data class Short(val read: Int, val expected: Int) : BodyRead()
        object NoContentLength : BodyRead()
    }

    /**
     * Reads exactly content-length bytes off the request stream.
     *
     * A single InputStream.read() is only obliged to return what has already arrived - one TCP
     * segment - not the whole body. This used to read once and check only len > 0, then hand
     * Byte2ASCII the entire content-length-sized array regardless of len. Bodies over one MSS
     * (~1460 bytes) came back short, so the truncated JSON was NUL-padded back to full length, had
     * no closing brace, and concatenateAndValidateLast() rejected the terminal's own truncated copy
     * as SHC001 Invalid Input - on a request the POS had sent correctly.
     *
     * Reading session.inputStream directly is safe with nanohttpd 2.3.1: HTTPSession.execute()
     * does mark(BUFSIZE) -> read headers -> reset() -> skip(splitbyte), so the BufferedInputStream
     * is repositioned to the first body byte and nothing the header parse consumed is lost. Re-check
     * this if the nanohttpd jar is ever upgraded.
     *
     * Space stripping was dropped deliberately: it corrupted any legitimate value containing a
     * space and was only ever masking the NUL padding the short read produced.
     *
     * See obsidian FIX-2026-08-10-HTTPServer-Short-Read-SHC001-Invalid-Input.
     */
    private fun readRequestBody(session: IHTTPSession): BodyRead {
        val contentLength = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (contentLength <= 0) {
            return BodyRead.NoContentLength
        }

        val byteMsg = ByteArray(contentLength)
        var read = 0
        try {
            while (read < contentLength) {
                val len = session.inputStream.read(byteMsg, read, contentLength - read)
                if (len <= 0) {
                    break   /* peer closed before sending the full body */
                }
                read += len
            }
        } catch (ioEx: IOException) {
            // A timeout or reset mid-body is a short read, not a header problem: the POS declared
            // contentLength and we received `read`. Reporting it as "missing content-length" sends
            // whoever reads the log after the header, which was fine.
            ioEx.printStackTrace()
            return BodyRead.Short(read, contentLength)
        }

        if (read < contentLength) {
            return BodyRead.Short(read, contentLength)
        }
        return BodyRead.Ok(Utility.Byte2ASCII(byteMsg).replace("\n", "").replace("\r", ""))
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

    private fun getSpecificProduct(name: String): DbModelProductList? {
        val merchantProductList = ServiceHolder.getMerchantProductList()
        if (merchantProductList != null) {
            for (item in merchantProductList) {
                if (item.Product == name) {
                    return item
                }
            }
        }
        return null
    }

    private fun handleIncomingRequest(requestData: String) {
        val concatenateRequest = concatenateAndValidateLast(requestData)
        if(concatenateRequest.isEmpty()){
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
            if(!appRunningProcess){
                try {
                    if(jsonObject.has("TransactionType")) {
                        val transType = jsonObject.get("TransactionType").asInt
                        if(transType == 0) {
                            isError = false
                            val jsonResp = JSONObject()
                            jsonResp.put("ResponseCode", EnumResponseCode.PAYMENT_SESSION_TERMINATED.code)
                            jsonResp.put("ResponseDescription", EnumResponseCode.PAYMENT_SESSION_TERMINATED.description)
                            setResponseMessage(jsonResp.toString())

                            val ctx = getInstance().attendActivityContext
                            if (ctx is CardPaymentActivity) {
                                val mainLooper = Looper.getMainLooper()
                                val handler = Handler(mainLooper)
                                handler.post {
                                    ctx.stopSearch()
                                    ctx.endEMV()
                                }
                            } else if (ctx is QrScanActivity) {
                                // Stop the enquiry loop promptly instead of letting it run its
                                // full course and clobber TransData for whatever transaction
                                // starts next. See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
                                ctx.abortSession()
                            } else if (ctx is GenerateQrActivity) {
                                ctx.abortSession()
                            } else {
                                val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
                                var newIntent = Intent(ServiceHolder.getContext(), AttendActivity::class.java)
                                if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")){
                                    newIntent = Intent(ServiceHolder.getContext(), UnattendActivity::class.java)
                                }
                                newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                ServiceHolder.getContext().startActivity(newIntent)
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

    //TODO RS232
    /**
     * ECR entry point. MF919 speaks only its own protocol, so this forwards straight to
     * [oldIntegrationType] -- the same name Pro gives the handler for this protocol, so the
     * two can be compared directly. A new-integration branch would go here.
     */
    private fun checkTransactionType(requestJson: JsonObject) {
        oldIntegrationType(requestJson)
    }

    private fun oldIntegrationType(requestJson: JsonObject){
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        try{
            ServiceHolder.appHTTP = true
            val resultObject = requestJson
            val txnType = requestJson.get("TransactionType").asInt
            ServiceHolder.txnType = txnType

            // Only for flows that actually start a transaction: not cancel (0) or status
            // (13). The wake holds the screen for 3s, so calling it per request -- as serve()
            // used to -- would light the screen on every POS poll.
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
                } catch (e: Exception) {
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
                try{
                    ServiceHolder.ackCountDownSecond = requestJson.get("AcknowledgeCountdown").asInt
                }catch (ex: Exception) {
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
                    if (!getBooleanValue(terminalConfig, "SALES_CARD")){
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
                        if (ServiceHolder.clearSettlementBatch){
                            handler.post {
                                Toast.makeText(ServiceHolder.getContext(), "Please Run Settlement for Last day Transaction before Proceed", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code)
                            resultObject.addProperty("ResponseDescription", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description)
                            setResponseMessage(resultObject.toString())
                            return
                        }
                    }

                    if (!requestJson.has("TransactionAmount")){
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
                        //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val salesModel = SalesModel(
                            ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                            dbProductModel.Product,
                            dbProductModel.AcqCode,
                            dbProductModel.AcqMid,
                            dbProductModel.AcqTid,
                            dbProductModel.QrProductCode,
                            dbProductModel.ProductName,
                            dbProductModel.EppProductCode,
                            dbProductModel.EppTenure,
                            dbProductModel.EppTenureCode
                        )
                        ServiceHolder.selectedCacheModel = salesModel
                        val jsonProductList = Gson().toJson(dbProductModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        throw Exception()
                    }

                    val intent = Intent(ServiceHolder.getContext(), CardPaymentActivity::class.java)
                    intent.putExtra("txnAmt", txnAmount)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
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
                        //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val salesModel = SalesModel(
                            ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                            dbProductModel.Product,
                            dbProductModel.AcqCode,
                            dbProductModel.AcqMid,
                            dbProductModel.AcqTid,
                            dbProductModel.QrProductCode,
                            dbProductModel.ProductName,
                            dbProductModel.EppProductCode,
                            dbProductModel.EppTenure,
                            dbProductModel.EppTenureCode
                        )
                        ServiceHolder.selectedCacheModel = salesModel
                        val jsonProductList = Gson().toJson(dbProductModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), VoidSaleActivity::class.java)
                    intent.putExtra("Invoice", txnInvoice)
                    intent.putExtra("forceVoid", forceVoid)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                // Settlement
                3 -> {
                    try {
                        val productCat = getProductCatForHttp(txnType)
                        //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val salesModel = SalesModel(
                            ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                            dbProductModel.Product,
                            dbProductModel.AcqCode,
                            dbProductModel.AcqMid,
                            dbProductModel.AcqTid,
                            dbProductModel.QrProductCode,
                            dbProductModel.ProductName,
                            dbProductModel.EppProductCode,
                            dbProductModel.EppTenure,
                            dbProductModel.EppTenureCode
                        )
                        ServiceHolder.selectedSettlementModel = salesModel
                        val jsonProductList = Gson().toJson(dbProductModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), SettlementActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                // Pre Auth
                4 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "PreAuth")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")){
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
                        //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val salesModel = SalesModel(
                            8,
                            dbProductModel.Product,
                            dbProductModel.AcqCode,
                            dbProductModel.AcqMid,
                            dbProductModel.AcqTid,
                            dbProductModel.QrProductCode,
                            dbProductModel.ProductName,
                            dbProductModel.EppProductCode,
                            dbProductModel.EppTenure,
                            dbProductModel.EppTenureCode
                        )
                        ServiceHolder.selectedSettlementModel = salesModel
                        val jsonProductList = Gson().toJson(dbProductModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = 8
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), CardPaymentActivity::class.java)
                    intent.putExtra("txnAmt", txnAmount)
                    intent.putExtra("posReference", posReference)
                    intent.putExtra("typeofSale", 8)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                // Sale Complete
                5 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SaleComOnline")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")){
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
                    if (!requestJson.has("TransactionRRN")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionRRN)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionRRN)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    if (!requestJson.has("TransactionInvoice")){
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
                        //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                        val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                        val salesModel = SalesModel(
                            4,
                            dbProductModel.Product,
                            dbProductModel.AcqCode,
                            dbProductModel.AcqMid,
                            dbProductModel.AcqTid,
                            dbProductModel.QrProductCode,
                            dbProductModel.ProductName,
                            dbProductModel.EppProductCode,
                            dbProductModel.EppTenure,
                            dbProductModel.EppTenureCode
                        )
                        ServiceHolder.selectedSettlementModel = salesModel
                        val jsonProductList = Gson().toJson(dbProductModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = 4
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), KeypadActivitySaleCom::class.java)
                    intent.putExtra("txnAmt", txnAmount)
                    intent.putExtra("posReference", posReference)
                    intent.putExtra("typeofSale", 4)
                    intent.putExtra("apprCode", requestJson.get("TransactionApprovalCode").asString)
                    intent.putExtra("rrn", requestJson.get("TransactionRRN").asString)
                    intent.putExtra("invNo", requestJson.get("TransactionInvoice").asString)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                //Pre Auth Cancel
                6 -> {
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

                    val productCat = getProductCatForHttp(txnType)
                    val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew

                    val intent = Intent(ServiceHolder.getContext(), VoidPreauthActivity::class.java)
                    intent.putExtra("Invoice", txnInvoice)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                //E-Wallet Sale
                7 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SALES_EWALLET")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("TransactionAmount")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val productCat = getProductCatForHttp(txnType)
                    if (requestJson.has("ProductCode")){
                        try{
                            val productCode: String = requestJson.get("ProductCode").asString
                            val generateQrModel = ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, productCode, "true"))
                            generateQrModel?.let {
                                val salesModel = SalesModel(
                                    ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType,
                                    it.Product,
                                    it.AcqCode,
                                    it.AcqMid,
                                    it.AcqTid,
                                    it.QrProductCode,
                                    it.ProductName,
                                    it.EppProductCode,
                                    it.EppTenure,
                                    it.EppTenureCode
                                )
                                ServiceHolder.selectedCacheModel = salesModel
                                val jsonProductList = Gson().toJson(generateQrModel)
                                val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                                saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                                ServiceHolder.saleModelCache = saleModelNew

                                val intent = Intent(ServiceHolder.getContext(), GenerateQrActivity::class.java)
                                intent.putExtra("txnAmt", txnAmount)
                                intent.putExtra("posReference", posReference)
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                ServiceHolder.getContext().startActivity(intent)
                                attendActivityContext?.finish()
                            } ?: run {
                                handler.post {
                                    Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                                }
                                resultObject.addProperty("ResponseCode", "SHC007")
                                // A3: SHC007 stays "Terminal System Error" and carries the cause
                                // in parentheses. MF919 was the only place still emitting the bare
                                // "Product Is Not Configured", so a vendor saw a different
                                // description from Pro for the identical condition and code.
                                // See enums.EnumResponseCode.PRODUCT_NOT_CONFIGURED.
                                resultObject.addProperty(
                                    "ResponseDescription",
                                    enums.EnumResponseCode.PRODUCT_NOT_CONFIGURED.description
                                )
                                setResponseMessage(resultObject.toString())
                                return
                            }
                        } catch (e: Exception) {
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
                        if(requestJson.has("CameraFacing")){
                            val tempCamera = requestJson.get("CameraFacing").asInt
                            if(tempCamera == 1 || tempCamera == 0) {
                                cameraFacing = tempCamera
                            }
                        }

                        try {
                            //val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = getSpecificProduct(productCat) ?: throw Exception()
                            val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                            val salesModel = SalesModel(
                                ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType,
                                dbProductModel.Product,
                                dbProductModel.AcqCode,
                                dbProductModel.AcqMid,
                                dbProductModel.AcqTid,
                                dbProductModel.QrProductCode,
                                dbProductModel.ProductName,
                                dbProductModel.EppProductCode,
                                dbProductModel.EppTenure,
                                dbProductModel.EppTenureCode
                            )
                            ServiceHolder.selectedCacheModel = salesModel
                            val jsonProductList = Gson().toJson(dbProductModel)
                            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType
                            ServiceHolder.saleModelCache = saleModelNew

                            val intent = Intent(ServiceHolder.getContext(), QrScanActivity::class.java)
                            intent.putExtra("txnAmt", txnAmount)
                            intent.putExtra("posReference", posReference)
                            intent.putExtra("cameraFacing", cameraFacing)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            ServiceHolder.getContext().startActivity(intent)
                            attendActivityContext?.finish()
                        } catch (e: Exception) {
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
                    if(!requestJson.has("TransactionRefId")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionRefId)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionRefId)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val txnInvoice: String = requestJson.get("TransactionRefId").asString

                    try {
                        var dbProduct = getSpecificProduct(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name)
                        if(dbProduct == null) {
                            dbProduct = getSpecificProduct(ProductCatSelectionDataEnum.GENERATE_QR.name) ?: throw Exception()
                        }
                        val jsonProductList = Gson().toJson(dbProduct)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), VoidQrActivity::class.java)
                    intent.putExtra("Invoice", txnInvoice)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                //E-Wallet Settlement
                9 -> {
                    val intent = Intent(ServiceHolder.getContext(), SettlementQrActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                //Void Sale Complete
                10 -> {
                    if(!requestJson.has("TransactionInvoice")){
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
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew

                    val txnInvoice: String = requestJson.get("TransactionInvoice").asString
                    val intent = Intent(ServiceHolder.getContext(), VoidOffSaleActivity::class.java)
                    intent.putExtra("Invoice", txnInvoice)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                // MOTO
                11 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "MOTO")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (!requestJson.has("CardNumber")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (CardNumber)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (CardNumber)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val cardNumber: String = requestJson.get("CardNumber").asString

                    if (!requestJson.has("ExpiryDate")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (ExpiryDate)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (ExpiryDate)")
                        setResponseMessage(resultObject.toString())
                        return
                    }
                    val expDate: String = requestJson.get("ExpiryDate").asString

                    if (!requestJson.has("TransactionAmount")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC001")
                        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    try {
                        val terminalConfig = getTerminalConfig()
                        if (getBooleanValue(terminalConfig, "MOTO")) {
                            var productCat = getProductCatForHttp(txnType)
                            var dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat))
                            if (dbProductModel == null) {
                                //Handle for MOTO merged with CARD
                                println("MOTO empty -> CARD SETTINGS")
                                productCat = getProductCatForHttp(1)
                                dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                            }
                            val salesModel = SalesModel(
                                ProductCatSelectionDataEnum.MOTO.data.SalesType,
                                dbProductModel.Product,
                                dbProductModel.AcqCode,
                                dbProductModel.AcqMid,
                                dbProductModel.AcqTid,
                                dbProductModel.QrProductCode,
                                dbProductModel.ProductName,
                                dbProductModel.EppProductCode,
                                dbProductModel.EppTenure,
                                dbProductModel.EppTenureCode
                            )
                            ServiceHolder.selectedCacheModel = salesModel
                            val jsonProductList = Gson().toJson(dbProductModel)
                            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                            ServiceHolder.saleModelCache = saleModelNew
                        } else {
                            throw Exception()
                        }
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), MotoSaleActivity::class.java)
                    intent.putExtra("cardNumber", cardNumber)
                    intent.putExtra("expDate", expDate)
                    intent.putExtra("txnAmt", txnAmount)
                    intent.putExtra("posReference", posReference)
                    intent.putExtra("typeofSale", ProductCatSelectionDataEnum.MOTO.data.SalesType)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
                }
                // EPP
                12 -> {
                    if (!requestJson.has("TransactionAmount")){
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
                        //getSpecificProduct(productCat) ?: throw Exception()
                        ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(productCat)) ?: throw Exception()
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "System Error", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    val intent = Intent(ServiceHolder.getContext(), EppAcquirerActivity::class.java)
                    intent.putExtra("txnAmt", txnAmount)
                    intent.putExtra("posReference", posReference)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    ServiceHolder.getContext().startActivity(intent)
                    attendActivityContext?.finish()
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
                        if(dbmodelReceiptUpload != null){
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

                                val eppDetail = Gson().fromJson(dbmodelReceiptUpload.EPP_DETAIL, EppDetail::class.java)
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
        }catch (ex: Exception) {
            ServiceHolder.appHTTP = false
            ex.printStackTrace()
        }
    }

    private fun isJSONValid(jsonString: String): Boolean {
        return try {
            val jsonElement: JsonElement = JsonParser.parseString(jsonString)
            jsonElement.isJsonObject || jsonElement.isJsonArray
        } catch (e: JsonSyntaxException) {
            false // Parsing failed, not valid JSON
        }
    }

    // Compiled once: concatenateAndValidateLast runs per cable message.
    private val jsonSplitRegex = "(?<=\\})".toRegex()

    private fun concatenateAndValidateLast(jsonString: String): String {
        val jsonObjects = jsonString.split(jsonSplitRegex).filter { it.trim().isNotBlank() }
        val lastJsonObject = jsonObjects.lastOrNull() ?: return ""
        if(isJSONValid(lastJsonObject)) {
            return lastJsonObject
        }
        return ""
    }

    @JvmStatic
    fun cableHealthCheck() {
        val runningFlavor = BuildConfig.FLAVOR
        if(runningFlavor == "oxpay") {
            Utils.debugLogPrint(TAG, "Doing Cable Health Check")
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
            if(connMethod.equals("USB", true) && socketInterface == 1) {
                serialPortDriver?.let {
                    val jsonObject = JSONObject()
                    jsonObject.put("Action", "ping")
                    val msgByte = jsonObject.toString().toByteArray()
                    //val msgByte = "ping".toByteArray()
                    val sendStatus = it.send(msgByte, msgByte.size)
                    Utils.debugLogPrint(TAG, "Health Check Send Status :: $sendStatus")
                    if(sendStatus != 0) {
                        resetCommunicationPort()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun rs232(msg: String) {
        onBackToRS232(msg)
    }

    private fun onBackToRS232(msg: String){
        waitingForResponse = false

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
        when (connMethod) {
            "USB" -> {
                serialPortDriver?.let {
                    val msgByte = msg.toByteArray()
                    val sendStatus = it.send(msgByte, msgByte.size)
                    helperLog?.appendLine(helperlogClassName, "onBackToRS232 Send Status :: $sendStatus")
                }
            }
            "RS232" -> {
                usbSerialPort?.let {
                    val msgByte = msg.toByteArray()
                    val sendStatus = it.write(msgByte, msgByte.size, 0)
                    helperLog?.appendLine(helperlogClassName, "onBackToRS232 Send Status :: $sendStatus")
                }
            }
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
            // The entry line above says only that this function ran. Without an outcome line the
            // log cannot distinguish "checked, socket healthy, did nothing" from "socket was dead,
            // restarted" -- which is exactly the ambiguity that made a routine screen reload look
            // like a per-transaction server restart.
            if(!socketConnected) {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server DOWN :: restarting listener on 8080")
                tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                serviceScope?.cancel()
                serviceScope = CoroutineScope(Dispatchers.IO)
                serviceScope?.launch {
                    do {
                        try {
                            // stopServer (not stop) so the old instance's processing
                            // coroutine is cancelled and does not leak per retry
                            webSocketServer?.stopServer()
                            webSocketServer = WebSocketServer(8080)
                            webSocketServer!!.start()
                            delay(2000)
                        } catch (ex: Exception) {
                            delay(2000)
                            println("<><><><><><><><> ERROR <><><><><><><><><><>")
                        }
                    } while (!WebSocketServer.socketConnected)
                    socketConnected = WebSocketServer.socketConnected
                    tmpHelperLog.appendLine(helperlogClassName, "WebSocket server restarted :: connected=$socketConnected")
                    tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                }
            } else {
                tmpHelperLog.appendLine(helperlogClassName, "WebSocket server already connected :: no restart")
            }
        } else {
            tmpHelperLog.appendLine(helperlogClassName, "reset port from web socket")
            if(socketConnected) {
                println("Socket connected closing server")
                stopWebSocketServer()
            }
        }
    }

    @JvmStatic
    fun stopWebSocketServer() {
        // Unconditional, and the reference is dropped: a server whose bind is still in progress
        // has socketConnected == false, so the old guard skipped it and leaked the server holding
        // port 8080. Taken from Pro, which already had this fix.
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
        // Was helperLog!!: a non-null assertion on a nullable field. startWebSocketServer assigns
        // it before messages can arrive today, but a WS frame landing first would have been a
        // KotlinNullPointerException on the websocket thread. ?. costs nothing and cannot throw.
        helperLog?.appendLine(helperlogClassName, "Check WebSocket Incoming :: ${HelperCommon.oneLine(incomingMessage)}")
        dispatchTransportRequest(Origin.WEBSOCKET, incomingMessage)
    }
    //WebSocket
}
