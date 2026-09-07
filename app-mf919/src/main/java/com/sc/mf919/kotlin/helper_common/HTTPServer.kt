package com.sc.mf919.kotlin.helper_common

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
import kotlin.concurrent.thread

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
     * Allows one request at a time across all transports (HTTP, cable, websocket).
     *
     * requestMsg/responseMsg are shared by every transport, so a second concurrent request is
     * refused with SHC000 "System Busy" on its own channel instead of overwriting the first.
     */
    private val requestInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val TRANSPORT_NONE = 0
    private const val TRANSPORT_HTTP = 1
    private const val TRANSPORT_CABLE = 2
    private const val TRANSPORT_WS = 3

    /**
     * The transport the in-flight request arrived on, so setResponseMessage sends the response
     * back to that caller only.
     */
    @Volatile
    private var inFlightTransport = TRANSPORT_NONE

    /** When the current slot was claimed, used to detect a claim that was never released. */
    @Volatile
    private var inFlightSince = 0L

    /**
     * Claims the single in-flight slot, or returns false if another request holds it.
     *
     * A claim older than the response ceiling is treated as abandoned and taken over, so a
     * transaction that never answers cannot block ECR permanently.
     */
    private fun tryClaim(transport: Int): Boolean {
        if (requestInFlight.compareAndSet(false, true)) {
            inFlightTransport = transport
            inFlightSince = SystemClock.elapsedRealtime()
            return true
        }
        if (SystemClock.elapsedRealtime() - inFlightSince > RESPONSE_WAIT_TIMEOUT_MS) {
            helperLog?.appendLine(helperlogClassName,
                "In-flight claim stale (>${RESPONSE_WAIT_TIMEOUT_MS / 1000}s) :: taking over")
            inFlightTransport = transport
            inFlightSince = SystemClock.elapsedRealtime()
            return true
        }
        return false
    }

    private fun releaseInFlight() {
        inFlightTransport = TRANSPORT_NONE
        requestInFlight.set(false)
    }

    private fun busyJson(): String {
        val busy = JSONObject()
        try {
            busy.put("ResponseCode", "SHC000")
            busy.put("ResponseDescription", "System Busy")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return busy.toString()
    }

    /**
     * Longest serve() will wait for a transaction to produce a response before giving up.
     *
     * Set high because a card transaction with PIN entry and a slow host legitimately takes
     * minutes. This is a backstop against a stuck worker thread, not a transaction timeout.
     */
    private const val RESPONSE_WAIT_TIMEOUT_MS = 300_000L

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
    private var serverRequest = false
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

    /**
     * Collapse a pretty-printed JSON payload onto one line before logging it.
     *
     * POS payloads arrive indented with embedded newlines, so appendLine wrote them as ~30
     * physical lines with the [RowIdentifier] only on the last one -- the entry point of every
     * POS request was unreadable and un-greppable. Whitespace runs collapse to a single space;
     * nothing is truncated.
     */
    private fun oneLine(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

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
        //startServeCable()
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

        when (connMethod) {
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
                            // A cancel skips the in-flight slot for the same reason it does on HTTP:
                            // it is sent while a transaction is running, and is answered directly.
                            if (isCancelRequest(tempResult)) {
                                onBackToRS232(handleCancelRequest(tempResult))
                            } else if (tryClaim(TRANSPORT_CABLE)) {
                                try {
                                    handleIncomingRequest(tempResult)
                                } catch (t: Throwable) {
                                    // setResponseMessage normally releases the slot; on a throw
                                    // it must be released here.
                                    t.printStackTrace()
                                    releaseInFlight()
                                }
                            } else {
                                tmpHelperLog.appendLine(helperlogClassName,
                                    "Cable request refused :: another request already in flight")
                                tmpHelperLog.logToFile(EnumLogFileName.TerminaLog)
                                onBackToRS232(busyJson())
                            }
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

        // Sends the response only to the transport that made the request.
        when (inFlightTransport) {
            TRANSPORT_CABLE -> {
                onBackToRS232(respMsg)
                releaseInFlight()
            }
            TRANSPORT_WS -> {
                waitingForResponse = false
                WebSocketServer.receiveResponseMessage(respMsg)
                releaseInFlight()
            }
            // TRANSPORT_HTTP: serve() reads responseMsg and releases the slot itself.
            // TRANSPORT_NONE: no caller is waiting, so nothing is sent.
        }

        helperLog?.appendLine(helperlogClassName, "Set Response Msg :: $responseMsg")
        helperLog?.logToFile(EnumLogFileName.TerminaLog)
        thread {
            Thread.sleep(2000)
            //appRunningProcess = false
            ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // The body is read before the in-flight slot is claimed so a cancel can be recognised.
        val bodyRead = try {
            readRequestBody(session)
        } catch (ioEx: IOException) {
            ioEx.printStackTrace()
            BodyRead.NoContentLength
        }

        // A cancel is a control message, not a competing transaction: it is sent precisely BECAUSE
        // something is in flight, so it skips admission control and is answered directly instead of
        // through the shared responseMsg, which the in-flight request is waiting on.
        if (bodyRead is BodyRead.Ok && isCancelRequest(bodyRead.msg)) {
            return jsonResponse(handleCancelRequest(bodyRead.msg))
        }

        if (!tryClaim(TRANSPORT_HTTP)) {
            return jsonResponse(busyJson())
        }
        try {
            return serveOne(session, bodyRead)
        } finally {
            // Always released, including on an exception escaping serveOne -- otherwise the very
            // first failure would refuse every request from then on.
            requestMsg = null
            serverRequest = false
            waitingForResponse = false
            releaseInFlight()
        }
    }

    /** True for a TransactionType 0 request, which cancels whatever is currently running. */
    private fun isCancelRequest(msg: String): Boolean = try {
        JSONObject(msg).getString("TransactionType").toInt() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Cancels the running transaction and returns the response for the caller that asked.
     *
     * Deliberately does not call setResponseMessage: that writes the shared responseMsg, which the
     * in-flight request is polling, and would hand this cancel's reply to that caller instead.
     */
    private fun handleCancelRequest(msg: String): String {
        helperLog?.appendLine(helperlogClassName, "Cancel request :: terminating current session")

        isActive = true
        appRunningProcess = false

        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Frees the slot held by the transaction just cancelled so the next request is not refused.
        releaseInFlight()

        val jsonResp = JSONObject()
        try {
            jsonResp.put("ResponseCode", "SHC009")
            jsonResp.put("ResponseDescription", "Payment Session Terminated")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        helperLog?.logToFile(EnumLogFileName.TerminaLog)
        return jsonResp.toString()
    }

    private fun jsonResponse(body: String): Response {
        val response = newFixedLengthResponse(body)
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "POST")
        response.addHeader("Access-Control-Allow-Headers", "X-Requested-With")
        return response
    }

    private fun serveOne(session: IHTTPSession, bodyRead: BodyRead): Response {
        responseMsg = null
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
            Utils.getIPAddress(),
            helperlogClassName,
            helperlogClassName,
            "HTTP Sever Serve"
        )
        helperLog?.appendLine(helperlogClassName, "serve(${session.method.name}) :: ${session.remoteIpAddress}")
        try {
            val screenLock =
                (ServiceHolder.getContext().getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ScreenLock:Test"
                )
            screenLock.acquire(100)
            screenLock.release()
        }catch (e: Exception){
            e.printStackTrace()
        }

        // One summary line instead of ~8 header lines per request. content-length, host,
        // user-agent and accept never diagnosed a transaction; the caller and content type do.
        val h = session.headers
        helperLog?.appendLine(helperlogClassName, "Request from ${h["remote-addr"] ?: "?"} :: " +
            "type=${h["content-type"] ?: "-"} len=${h["content-length"] ?: "-"} ua=${h["user-agent"] ?: "-"}")

        var msg = ""

        try {
            //waitingForResponse = true
            if (bodyRead is BodyRead.Ok) {
                msg = bodyRead.msg
                helperLog?.appendLine(helperlogClassName, "Request Msg :: ${oneLine(msg)}")
                helperLog?.appendLine(helperlogClassName, "Is Active :: $isActive")
                if(isActive) {
                    requestMsg = msg
                    serverRequest = true
                    handleIncomingRequest(msg)
                } else {
                    var isError = true

                    helperLog?.appendLine(helperlogClassName, "App Running Process :: $appRunningProcess")
                    if (!appRunningProcess) {
                        try {
                            val jsonReq = JSONObject(msg)
                            val transType = jsonReq.getString("TransactionType").toInt()
                            if (transType == 0) {
                                isError = false
                                val jsonResp = JSONObject()
                                jsonResp.put("ResponseCode", "SHC009")
                                jsonResp.put("ResponseDescription", "Payment Session Terminated")
                                setResponseMessage(jsonResp.toString())
                                isActive = true
                                appRunningProcess = false

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
                        } catch (je: JSONException) {
                            je.printStackTrace()
                        }
                    }
                    if (isError) {
                        defaultError(msg, 0)
                    }
                }
            } else if (bodyRead is BodyRead.Short) {
                // Genuinely incomplete request - report it as such rather than parsing a fragment.
                helperLog?.appendLine(helperlogClassName, "Short Read :: ", "${bodyRead.read}/${bodyRead.expected}")
                defaultError("{\"SQN\":\"FF\"}", 1)
            } else {
                helperLog?.appendLine(helperlogClassName, "Missing/Invalid content-length :: ", "${h["content-length"]}")
                defaultError("{\"SQN\":\"FF\"}", 0)
            }
        }catch (ioEx: IOException) {
            ioEx.printStackTrace();
            defaultError("{\"SQN\":\"FF\"}", 1);
        }

        helperLog?.logToFile(EnumLogFileName.TerminaLog)

        // Bounded wait so a request that never gets answered cannot hang this worker thread.
        val deadline = SystemClock.elapsedRealtime() + RESPONSE_WAIT_TIMEOUT_MS
        while (responseMsg == null && SystemClock.elapsedRealtime() < deadline) {
            Utils.DelayMili(100)
        }

        val body = responseMsg ?: run {
            // Nothing answered in time; reply so the caller fails fast and the thread is freed.
            helperLog?.appendLine(helperlogClassName,
                "No response produced within ${RESPONSE_WAIT_TIMEOUT_MS / 1000}s :: replying System Busy")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            val timeout = JSONObject()
            try {
                timeout.put("ResponseCode", "SHC000")
                timeout.put("ResponseDescription", "System Busy")
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            timeout.toString()
        }

        // Shared-state cleanup and the in-flight release both happen in serve()'s finally.
        return jsonResponse(body)
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
        while (read < contentLength) {
            val len = session.inputStream.read(byteMsg, read, contentLength - read)
            if (len <= 0) {
                break   /* peer closed before sending the full body */
            }
            read += len
        }

        if (read < contentLength) {
            return BodyRead.Short(read, contentLength)
        }
        return BodyRead.Ok(Utility.Byte2ASCII(byteMsg).replace("\n", "").replace("\r", ""))
    }

    private fun defaultError(msg: String, type: Int) {
        val jsonResponse = JSONObject()
        try {
            when (type) {
                0 -> {
                    jsonResponse.put("ResponseCode", "SHC000")
                    jsonResponse.put("ResponseDescription", "System Busy")
                    /*if(!waitingForResponse){
                        isActive = true
                    }*/
                }
                1 -> {
                    jsonResponse.put("ResponseCode", "SHC001")
                    jsonResponse.put("ResponseDescription", "Invalid Input")
                }
                2 -> {
                    jsonResponse.put("ResponseCode", "SHC001")
                    jsonResponse.put("ResponseDescription", "Invalid Request")
                }
                3 -> {
                    jsonResponse.put("ResponseCode", "SHC002")
                    jsonResponse.put("ResponseDescription", "Auto Settlement is running")
                }
                else -> {
                    jsonResponse.put("ResponseCode", "SHC007")
                    jsonResponse.put("ResponseDescription", "Unexpected Error")
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        } finally {
            setResponseMessage(jsonResponse.toString())
        }
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
        // Refuses transactions until MainActivity has finished starting up (migrations, config
        // download, sign-on) with SHC000 "System Busy".
        //
        // This function is the common entry point for all three transports (HTTP, cable and
        // websocket), so the guard here covers every one of them.
        if (ServiceHolder.appFreshLoad) {
            helperLog?.appendLine(helperlogClassName, "Request refused :: app fresh start still running")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            defaultError("", 0)
            return
        }

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
                            jsonResp.put("ResponseCode", "SHC009")
                            jsonResp.put("ResponseDescription", "Payment Session Terminated")
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
    private fun checkTransactionType(requestJson: JsonObject){
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)

        try{
            ServiceHolder.appHTTP = true
            val resultObject = requestJson
            val txnType = requestJson.get("TransactionType").asInt
            ServiceHolder.txnType = txnType

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
                    resultObject.addProperty("ResponseCode", "No Session Running")
                    setResponseMessage(resultObject.toString())
                }
                // Sale
                1 -> {
                    val terminalConfig = getTerminalConfig()
                    if (!getBooleanValue(terminalConfig, "SALES_CARD")){
                        handler.post {
                            Toast.makeText(ServiceHolder.getContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show()
                        }
                        resultObject.addProperty("ResponseCode", "SHC010")
                        resultObject.addProperty("ResponseDescription", "Transaction Not Supported")
                        setResponseMessage(resultObject.toString())
                        return
                    }

                    if (getBooleanValue(terminalConfig, "FORCE_SETTLEMENT") ||
                        getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                        if (ServiceHolder.clearSettlementBatch){
                            handler.post {
                                Toast.makeText(ServiceHolder.getContext(), "Please Run Settlement for Last day Transaction before Proceed", Toast.LENGTH_SHORT).show()
                            }
                            resultObject.addProperty("ResponseCode", "SHC011")
                            resultObject.addProperty("ResponseDescription", "Please Run Settlement for Last day Transaction before Proceed")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC010")
                        resultObject.addProperty("ResponseDescription", "Transaction Not Supported")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC010")
                        resultObject.addProperty("ResponseDescription", "Transaction Not Supported")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC010")
                        resultObject.addProperty("ResponseDescription", "Transaction Not Supported")
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
                            resultObject.addProperty("ResponseCode", "SHC007")
                            resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                            resultObject.addProperty("ResponseCode", "SHC007")
                            resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC010")
                        resultObject.addProperty("ResponseDescription", "Transaction Not Supported")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                        resultObject.addProperty("ResponseCode", "SHC007")
                        resultObject.addProperty("ResponseDescription", "Terminal System Error")
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
                                    resultObject.addProperty("ResponseCode", "SHC008")
                                    resultObject.addProperty("ResponseDescription", "QR Transaction Not Found")
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
                            resultObject.addProperty("ResponseCode", "SHC008")
                            resultObject.addProperty("ResponseDescription", "Transaction Not Found")
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

    private fun concatenateAndValidateLast(jsonString: String): String {
        val jsonObjects = jsonString.split("(?<=\\})".toRegex()).filter { it.trim().isNotBlank() }
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
                            webSocketServer?.stop()
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
        if(socketConnected) {
            socketInterface = -1
            socketConnected = false
            webSocketRequest = false
            webSocketServer?.stopServer()
            serviceScope?.cancel()
        }
    }

    @JvmStatic
    fun checkWebSocketIncoming(incomingMessage: String) {
        webSocketRequest = true
        // Was helperLog!!: a non-null assertion on a nullable field. startWebSocketServer assigns
        // it before messages can arrive today, but a WS frame landing first would have been a
        // KotlinNullPointerException on the websocket thread. ?. costs nothing and cannot throw.
        helperLog?.appendLine(helperlogClassName, "Check WebSocket Incoming :: ${oneLine(incomingMessage)}")
        // A cancel skips the in-flight slot, as on HTTP and cable.
        if (isCancelRequest(incomingMessage)) {
            WebSocketServer.receiveResponseMessage(handleCancelRequest(incomingMessage))
            return
        }
        // Shares the single in-flight slot with HTTP and cable.
        if (!tryClaim(TRANSPORT_WS)) {
            helperLog?.appendLine(helperlogClassName,
                "WebSocket request refused :: another request already in flight")
            helperLog?.logToFile(EnumLogFileName.TerminaLog)
            WebSocketServer.receiveResponseMessage(busyJson())
            return
        }
        try {
            handleIncomingRequest(incomingMessage)
        } catch (t: Throwable) {
            t.printStackTrace()
            releaseInFlight()
        }
    }
    //WebSocket
}
