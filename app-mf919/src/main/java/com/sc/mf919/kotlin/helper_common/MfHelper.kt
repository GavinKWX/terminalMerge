package com.sc.mf919.kotlin.helper_common

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.google.gson.Gson
import com.morefun.yapi.engine.DeviceInfoConstrants
import com.sc.mf919.java.activity.Encryption
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.mContext
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object MfHelper {
    private const val TAG = "MfHelper"

    @Volatile
    private var isRunning = false
    private var processThread: Thread? = null

    /**
     * One flushed block per device-interface operation. Built per call: this is an object with
     * no lifecycle, and each operation is a couple of lines. Construction is guarded because
     * mContext / the session may not be up yet on the earliest calls, and a card-emulation
     * failure must not be turned into a crash by the logging of it.
     */
    private fun newLog(purpose: String): HelperLog? = try {
        HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            TAG,
            TAG,
            purpose
        )
    } catch (ex: Exception) {
        Log.w(TAG, "log init failed: ${ex.javaClass.simpleName}: ${ex.message}")
        null
    }

    @JvmStatic
    fun isSmallTerminal() : Boolean {
        //TODO Dynamic Layout For Small Terminal
        val displayMetrics = mContext.resources?.displayMetrics
        val screenHeightPx = displayMetrics!!.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        println("screenHeightDp :: $screenHeightDp")

        return screenHeightDp < 500
    }

    @JvmStatic
    fun lockStatusBarAndNavigation(isLock: Boolean) {
        if(isLock) {
            try {
                val bundle = Bundle()
                bundle.putBoolean(DeviceInfoConstrants.DISABLE_HOME, true)
                bundle.putBoolean(DeviceInfoConstrants.DISABLE_STATUS_BAR, true)
                DeviceHelper.getDeviceService().setProperties(bundle)
            } catch (_: RemoteException) {
                // TODO
            }
        } else {
            try {
                val bundle = Bundle()
                bundle.putBoolean(DeviceInfoConstrants.ENABLE_HOME, true)
                bundle.putBoolean(DeviceInfoConstrants.ENABLE_STATUS_BAR, true)
                DeviceHelper.getDeviceService().setProperties(bundle)
            } catch (_: RemoteException) {
                // TODO
            }
        }
    }

    @JvmStatic
    fun encryptAsAesUrl(aesKey:String, acdMid:String, acqTid:String, batchNo:String, invoiceNo:String, rrn:String, approvalCode:String, qrRefId: String, isEReceipt: Boolean, receiptEnrollCat: String, devSn: String): String{
        val gson = Gson()
        val mutObject = mutableMapOf<String, Any>()
        mutObject["AcqMID"] = acdMid
        mutObject["AcqTID"] = acqTid
        mutObject["BatchNo"] = batchNo
        mutObject["InvoiceNo"] = invoiceNo
        mutObject["RRN"] = rrn
        mutObject["ApprovalCode"] = approvalCode
        mutObject["QRRefId"] = qrRefId
        mutObject["IsEReceipt"] = isEReceipt
        mutObject["ReceiptEnrollCategory"] = receiptEnrollCat
        mutObject["DevSN"] = devSn
        val jsonString = gson.toJson(mutObject)

        val aesString = Encryption.AESencrypt(jsonString, aesKey)
        println("aesString :: $aesString")
        return URLEncoder.encode(aesString, "UTF-8")
    }

    fun formNdefMessage(urlType: Int, payloadUrl: String) {
        // D1 until 251 only
        val ndefByte = ByteArray(924) { 0x00 }

        val ndefType = 0x55.toByte()
        val urlBytes = payloadUrl.toByteArray(StandardCharsets.UTF_8)

        val outTemp = ByteArrayOutputStream()
        val payloadLen = 1 + urlBytes.size

        // Decide short vs normal record
        val isShortRecord = payloadLen <= 255

        if(isShortRecord) {
            // MB=1, ME=1, SR=1, TNF=0x01 → 0xD1
            outTemp.write(0xD1)
            outTemp.write(0x01)
            outTemp.write(payloadLen)                   // 1-byte payload length
            outTemp.write(ndefType.toInt())             // Type 'U'
        } else {
            // MB=1, ME=1, SR=0, TNF=0x01 → 0xC1
            outTemp.write(0xC1)
            outTemp.write(0x01)
            // 4-byte payload length (big-endian)
            outTemp.write((payloadLen ushr 24) and 0xFF)
            outTemp.write((payloadLen ushr 16) and 0xFF)
            outTemp.write((payloadLen ushr 8) and 0xFF)
            outTemp.write(payloadLen and 0xFF)
            outTemp.write(ndefType.toInt())             // Type 'U'
        }
        outTemp.write(urlType)
        outTemp.write(urlBytes)

        val outTempBytes = outTemp.toByteArray()
        val outTempBytesLen = outTempBytes.size
        ndefByte[0] = ((outTempBytesLen ushr 8) and 0xFF).toByte()
        ndefByte[1] = (outTempBytesLen and 0xFF).toByte()
        System.arraycopy(outTempBytes, 0, ndefByte, 2, outTempBytesLen)

        // 1️⃣ kill existing thread if still running
        processThread?.apply {
            isRunning = false
            interrupt()
        }

        isRunning = true
        val uid = byteArrayOf(
            0x44, 0x00,  //SENS_RES(2 bytes)
            0x01, 0x02, 0x03,  //NFCID1(  bytes)
            0x00,  //SEL_RES(1 byte)
            0x01, 0xFE.toByte(),  //polling response
            0xF0.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(),  //NFCID2(6 bytes)
            0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(),  //pad (8 bytes)
            0xAA.toByte(), 0xBB.toByte(),  //system code (2 bytes)
            0xFE.toByte() //NFCID3 (1 byte)
        )
        processThread = Thread {
            try {
                DeviceHelper.getEmulateCardHandler().config(uid, ndefByte)
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    DeviceHelper.getEmulateCardHandler().process()
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
                // Once per dead emulation thread, not per process() tick: this catch is
                // outside the loop, so the loop itself stays off the disk.
                newLog("NFC Card Emulation")?.let {
                    it.appendLine(TAG, "NFC emulation thread stopped :: RemoteException ${e.message}")
                    it.logToFile(EnumLogFileName.TerminaLogException)
                }
            } catch (e: InterruptedException) {
                // thread interrupted safely
            }
        }.apply { start() }
    }

    @JvmStatic
    fun openNfcUrlInterface(urlType: Int, paylodUrl: String) {
        if(!ServiceHolder.getDeviceModel().trim().equals("SR800", true)) return
        /*  1 :: http://www
            2 :: https://www
            3 :: http://
            4 :: https://   */
        val log = newLog("NFC URL Interface Open")
        log?.appendLine(TAG, "Open NFC URL Interface [START] :: urlType=$urlType")
        try {
            closeNfcUrlInterface()
            val ret = DeviceHelper.getEmulateCardHandler().open()
            if (ret == 0) {
                isRunning = true
                formNdefMessage(urlType, paylodUrl)
            }
            // The URL itself is not logged: it carries the AES receipt payload, which is
            // reconstructable elsewhere and would be a ~600 char line on every receipt.
            log?.appendLine(TAG, "Open NFC URL Interface [END] :: ret=$ret emulating=$isRunning")
            log?.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: RemoteException) {
            e.printStackTrace()
            log?.appendLine(TAG, "Open NFC URL Interface [END] :: RemoteException ${e.message}")
            log?.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    @JvmStatic
    fun closeNfcUrlInterface() {
        if(!ServiceHolder.getDeviceModel().trim().equals("SR800", true)) return

        val log = newLog("NFC URL Interface Close")
        try {
            isRunning = false
            val ret = DeviceHelper.getEmulateCardHandler().close()

            val bundle = Bundle()
            bundle.putBoolean(DeviceInfoConstrants.SYSTEM_NFC_DISABLE, true)
            val ret2 = DeviceHelper.getDeviceService().setProperties(bundle)

            log?.appendLine(TAG, "Close NFC URL Interface :: closeRet=$ret disableNfcRet=$ret2")
            log?.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: RemoteException) {
            e.printStackTrace()
            log?.appendLine(TAG, "Close NFC URL Interface :: RemoteException ${e.message}")
            log?.logToFile(EnumLogFileName.TerminaLogException)
        }
    }
}