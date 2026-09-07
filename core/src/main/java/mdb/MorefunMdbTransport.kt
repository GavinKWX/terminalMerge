package mdb

import android.os.RemoteException
import android.util.Log
import com.morefun.yapi.device.mdb.IRecvCallback
import enums.EnumLogFileName
import helpers.HelperLog

/**
 * MDB slave link via the Morefun YAPI MDB service, shared by both apps.
 * Frames arrive on a binder thread through [IRecvCallback] with framing already stripped.
 */
class MorefunMdbTransport(private val host: MdbHost) : MdbTransport {

    private val devicePath = "/dev/ttyACM0"

    /**
     * Link-layer log. Built per event and only on the failure paths: a healthy bus sends a
     * frame for every VMC poll, so anything unconditional here would be a per-poll disk write.
     * Wifi/IP are passed as false/"" - this runs on a driver thread with no Context of its own
     * and a link fault must never be masked by a logging failure (same shape as
     * WebSocketServer.logWs).
     */
    private fun newLog(): HelperLog? = try {
        HelperLog(host.logHeader().session, false, "", TAG, TAG, "MDB Link")
    } catch (ex: Exception) {
        Log.w(TAG, "log init failed: ${ex.javaClass.simpleName}: ${ex.message}")
        null
    }

    override fun connect(onFrame: (ByteArray) -> Unit): Boolean {
        val service = host.mdbService()
        if (service == null) {
            newLog()?.let {
                it.appendLine(TAG, "MDB service unavailable :: cannot connect")
                it.logToFile(EnumLogFileName.TerminaLogException)
            }
            return false
        }
        val ret: Int = service.connect(devicePath)
        if (ret != 0) {
            newLog()?.let {
                it.appendLine(TAG, "MDB connect failed :: path=$devicePath ret=$ret")
                it.logToFile(EnumLogFileName.TerminaLogException)
            }
            return false
        }
        return try {
            service.registerRecv(object : IRecvCallback.Stub() {
                @Throws(RemoteException::class)
                override fun onRecv(data: ByteArray?, len: Int) {
                    if (data == null) return
                    onFrame(data)
                }
            })
            true
        } catch (ex: Exception) {
            ex.printStackTrace()
            newLog()?.let {
                it.appendLine(TAG, "MDB registerRecv failed :: ${ex.javaClass.simpleName}: ${ex.message}")
                it.logToFile(EnumLogFileName.TerminaLogException)
            }
            false
        }
    }

    override fun send(data: ByteArray): Boolean {
        var attemptsLeft = SEND_RETRY_COUNT
        // Created on the first failed attempt only, so a successful send writes nothing.
        var failLog: HelperLog? = null
        while (attemptsLeft > 0) {
            attemptsLeft--
            try {
                val ret = host.mdbService()?.send(data, data.size) ?: -1
                if (ret == 0) {
                    // Boundary: the frame is out. Emit the retry trail as one block so a
                    // recovered send is distinguishable from one that gave up below.
                    failLog?.let {
                        it.appendLine(TAG, "MDB send recovered after retry")
                        it.logToFile(EnumLogFileName.TerminaLog)
                    }
                    return true
                }
                if (failLog == null) failLog = newLog()
                failLog?.appendLine(TAG, "MDB send failed (ret=$ret), retries left: $attemptsLeft")
            } catch (ex: Exception) {
                ex.printStackTrace()
                if (failLog == null) failLog = newLog()
                failLog?.appendLine(TAG, "MDB send exception (${ex.javaClass.simpleName}: ${ex.message}), retries left: $attemptsLeft")
            }
            if (attemptsLeft > 0) {
                Thread.sleep(SEND_RETRY_DELAY_MS)
            }
        }
        // Boundary: the VMC will never see this frame. Written synchronously - a link that
        // stops sending is usually about to take the vend (or the process) down with it.
        failLog?.let {
            it.appendLine(TAG, "MDB send gave up after $SEND_RETRY_COUNT attempts")
            it.logToFile(EnumLogFileName.TerminaLogException)
        }
        return false
    }

    companion object {
        private const val SEND_RETRY_COUNT = 3
        private const val SEND_RETRY_DELAY_MS = 5L
        private const val TAG = "MorefunMdbTransport"
    }
}
