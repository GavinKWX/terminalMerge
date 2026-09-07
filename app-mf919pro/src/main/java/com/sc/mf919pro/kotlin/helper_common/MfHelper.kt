package com.sc.mf919pro.kotlin.helper_common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.RemoteException
import android.view.Gravity
import com.google.gson.Gson
import com.google.zxing.WriterException
import com.morefun.yapi.engine.DeviceInfoConstrants
import com.sc.mf919pro.java.activity.Encryption
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import utils.QRCodeUtil
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object MfHelper {

	/**
	 * Manual override for the auxiliary customer display. Leave it alone unless you mean to.
	 *
	 * This is NOT how a unit without an aux screen is handled -- [auxPresent] does that, by
	 * asking the device service, so no one has to maintain a list of which models have the
	 * hardware. This flag exists only to force the display off on a unit that does have one:
	 * to isolate the aux path while debugging, or to stand it down in the field if a ROM turns
	 * out to hang on it the way the SR800's did.
	 *
	 * Set false and every public entry point becomes a no-op. One flag rather than ten
	 * commented-out call sites, so the screens themselves never have to be touched.
	 */
	@Volatile
	@JvmField
	var auxLcdEnabled: Boolean = true

	/**
	 * Every aux-LCD update runs here, never on the caller's thread.
	 *
	 * Measured on the SR800: one showAuxLcdImg is four binder round trips, and the FLUSH_AUXLCD
	 * one alone took 11s and then 41s --
	 *   AUXTIME :: light=154ms show=3586ms(bitmap 438x370, 633KB) flush=11087ms total=14827ms
	 * Callers are fragments and the service-connect callback, all on the main thread, so that
	 * blocks the UI far past the 5s input-dispatch deadline and the app is ANR-killed. Nothing
	 * reads a result -- these are fire-and-forget screen writes -- so posting them costs nothing.
	 *
	 * Single thread, not a pool: the protocol is ordered (light -> show -> flush), and two
	 * overlapping updates would interleave their binder calls on the same device service.
	 */
	private val auxExec: java.util.concurrent.ExecutorService =
		java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "aux-lcd") }

	/**
	 * Whether this unit actually has an auxiliary display.
	 *
	 * YSDK has no "is the aux screen present" call -- getDevInfo() is the only capability query on
	 * DeviceServiceEngine, and AUX_LCD_SIZE inside it is absent or empty on a unit without one.
	 * So that is the probe, resolved once and cached: it is a binder round trip, and on the SR800
	 * binder round trips to this service are not cheap.
	 *
	 * Called only from [auxExec], so the probe never runs on the caller's thread, and the cache
	 * needs no lock beyond the volatile.
	 */
	@Volatile
	private var auxProbe: Boolean? = null

	private fun auxPresent(): Boolean {
		auxProbe?.let { return it }
		val present = try {
			val size = DeviceHelper.getDeviceService().devInfo
				.getString(DeviceInfoConstrants.AUX_LCD_SIZE)?.trim()
			Utils.printLog("AUX probe :: AUX_LCD_SIZE='" + size + "'")
			if (!size.isNullOrEmpty()) {
				val parts = size.split("*").filter { it.isNotEmpty() }
				if (parts.size >= 2) {
					auxWidth = parts[0].toInt()
					auxHeight = parts[1].toInt()
				}
				true
			} else {
				Utils.printLog("AUX probe :: no auxiliary display on this unit -- aux calls disabled")
				false
			}
		} catch (e: Exception) {
			Utils.printLog("AUX probe :: failed -> " + e.javaClass.simpleName + ": " + e.message)
			false
		}
		auxProbe = present
		return present
	}

    private var auxWidth = 320
    private var auxHeight = 172

    @Volatile
    private var isRunning = false
    private var processThread: Thread? = null

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
            } catch (e: InterruptedException) {
                // thread interrupted safely
            }
        }.apply { start() }
    }

    @JvmStatic
    fun openNfcUrlInterface(urlType: Int, paylodUrl: String) {
        /*  1 :: http://www
            2 :: https://www
            3 :: http://
            4 :: https://   */
        try {
            closeNfcUrlInterface()
            val ret = DeviceHelper.getEmulateCardHandler().open()
            println("Open Result :: $ret")
            if (ret == 0) {
                isRunning = true
                formNdefMessage(urlType, paylodUrl)
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun closeNfcUrlInterface() {
        try {
            isRunning = false
            DeviceHelper.getEmulateCardHandler().close()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    //AUX LCD
    /**
     * note
     * To display an image, the following 3 steps must be performed
     * Image format requirements: bmp
     * Image size requirements: 320*172
     */
    private fun showAuxLcdBitmap(bitmap: Bitmap) {
        // Kept deliberately: this is what identified FLUSH_AUXLCD as the slow call. Only fires
        // when an update is pathologically slow, so it is silent on healthy hardware.
        val t0 = android.os.SystemClock.elapsedRealtime()
        //step 1
        showAuxLcdLight()
        val t1 = android.os.SystemClock.elapsedRealtime()
        //step 2
        showAuxLcd(bitmap)
        val t2 = android.os.SystemClock.elapsedRealtime()
        //step 3
        showAuxLcdFlush()
        val t3 = android.os.SystemClock.elapsedRealtime()
        if (t3 - t0 > 1000) Utils.printLog("AUXTIME :: light=${t1 - t0}ms show=${t2 - t1}ms(bitmap ${bitmap.width}x${bitmap.height}, ${bitmap.byteCount / 1024}KB) flush=${t3 - t2}ms total=${t3 - t0}ms")
    }

    /**
     * Load Images
     */
    private fun showAuxLcd(bitmap: Bitmap) {
        try {
            val bundle = Bundle()
            bundle.putBoolean(DeviceInfoConstrants.SHOW_AUXLCD, true)
            bundle.putParcelable(DeviceInfoConstrants.AUXLCD_BITMAP, bitmap)
            DeviceHelper.getDeviceService().setProperties(bundle)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    /**
     * Startup screen
     */
    private fun showAuxLcdLight() {
        try {
            val bundle = Bundle()
            bundle.putBoolean(DeviceInfoConstrants.LIGHT_AUXLCD, true)
            DeviceHelper.getDeviceService().setProperties(bundle)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    /**
     * screen refresh
     */
    private fun showAuxLcdFlush() {
        try {
            val bundle = Bundle()
            bundle.putBoolean(DeviceInfoConstrants.FLUSH_AUXLCD, true)
            DeviceHelper.getDeviceService().setProperties(bundle)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    /**
     * Off screen
     */
    fun closeAuxLcd() {
		if (!auxLcdEnabled) return
		auxExec.execute {
			if (!auxPresent()) return@execute

	        try {
	            val bundle = Bundle()
	            bundle.putBoolean(DeviceInfoConstrants.CLOSE_AUXLCD, true)
	            DeviceHelper.getDeviceService().setProperties(bundle)
	        } catch (e: RemoteException) {
	            e.printStackTrace()
	        }
    }
	}

    fun clearAuxLcd() {
		if (!auxLcdEnabled) return
		auxExec.execute {
			if (!auxPresent()) return@execute

	        val bitmap: Bitmap = createBgBitmap(Color.WHITE)
	        showAuxLcdBitmap(bitmap)
    }
	}

    private fun createBgBitmap(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(auxWidth, auxHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        return bitmap
    }

    fun showAuxLcdMsg (bgColor: Int, msg: String, textSize: Int, textColor: Int) {
		if (!auxLcdEnabled) return
		auxExec.execute {
			if (!auxPresent()) return@execute

	        val bitmap: Bitmap = createBitmapTxt(
	            bgColor,
	            msg,
	            textSize,
	            textColor,
	            Gravity.CENTER,
	            Gravity.CENTER
	        )
	        showAuxLcdBitmap(bitmap)
    }
	}

    /**
     * bgColor: Background Color
     * msg: message text
     * textSize: Text Size
     * textColor: Text Color
     * horizontalGravity: Horizontal Gravity, Support Gravity.LEFT, Gravity.CENTER, Gravity.RIGHT
     * verticalGravity: Vertical Gravity, Support Gravity.TOP, Gravity.CENTER, Gravity.BOTTOM
     */
    private fun createBitmapTxt(
        bgColor: Int,
        msg: String,
        msgTextSize: Int,
        msgTextColor: Int,
        horizontalGravity: Int,
        verticalGravity: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(auxWidth, auxHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)

        val rect = Rect(0, 0, auxWidth, auxHeight)
        val rectPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, rectPaint)

        val textPaint = Paint().apply {
            color = msgTextColor
            //textSize = textSize.toFloat()
            textSize = msgTextSize.toFloat()
            style = Paint.Style.FILL
        }

        // Split text into multiple lines
        val lines = msg.split("\n")
        val fm = textPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val totalTextHeight = lineHeight * lines.size

        // Calculate starting Y (baseline of first line)
        var y = when (verticalGravity) {
            Gravity.TOP -> -fm.ascent
            Gravity.CENTER -> canvas.height / 2f - totalTextHeight / 2f - fm.ascent
            Gravity.BOTTOM -> canvas.height - totalTextHeight - fm.descent
            else -> -fm.ascent
        }

        for (line in lines) {
            val textWidth = textPaint.measureText(line)

            val x = when (horizontalGravity) {
                Gravity.CENTER -> (canvas.width - textWidth) / 2f
                Gravity.RIGHT -> canvas.width - textWidth
                else -> 0f
            }

            canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }

        //TODO OLD
        /*val textWidth = textPaint.measureText(msg) //计算文本宽度
        val bound = Rect()
        textPaint.getTextBounds(msg, 0, msg.length, bound)
        val textHeight = bound.height() //计算文本高度

        var x = 0f
        var y = 0f
        when (horizontalGravity) {
            Gravity.LEFT -> x = 0f
            Gravity.CENTER -> x = canvas.width / 2 - textWidth / 2
            Gravity.RIGHT -> x = canvas.width - textWidth
        }
        when (verticalGravity) {
            Gravity.TOP -> y = textHeight.toFloat()
            Gravity.CENTER -> y = (canvas.height / 2 + textHeight / 2).toFloat()
            Gravity.BOTTOM -> y = (canvas.height - textHeight / 4).toFloat()
        }
        canvas.drawText(msg, x, y, textPaint)*/
        return bitmap
    }

    fun showAuxLcdQrCode(qrPayload: String, qrColor: Int?, bgColor: Int = Color.WHITE) {
		if (!auxLcdEnabled) return
		auxExec.execute {
			if (!auxPresent()) return@execute

	        try {
	            // width - 320, height - 172
	            val bitmap: Bitmap = if (qrPayload.isNotEmpty()) {
	                QRCodeUtil.createQrCodeBitmap(qrPayload, 190, 195, qrColor, bgColor)
	            } else {
	                QRCodeUtil.createQrCodeBitmap("https://www.google.com", auxWidth, auxHeight, qrColor, bgColor)
	            }
	            showAuxLcdBitmap(bitmap)
	        } catch (e: WriterException) {
	            e.printStackTrace()
	        }
    }
	}

    fun showAuxLcdImg(imageBitmap: Bitmap) {
		if (!auxLcdEnabled) return
		auxExec.execute {
			if (!auxPresent()) return@execute

	        //val bitmap = getImageFromAssetsFile(requireActivity().applicationContext, "image/bsn_logo.bmp")
	        //val bitmap = BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo)
	        /*if (bitmap != null) {
	            showAuxLcdBitmap(bitmap)
	        }*/
	        showAuxLcdBitmap(imageBitmap)
	        val tD = android.os.SystemClock.elapsedRealtime()
	        try {
	            val devInfo: Bundle = DeviceHelper.getDeviceService().devInfo
	            Utils.printLog("AUXTIME :: devInfo=${android.os.SystemClock.elapsedRealtime() - tD}ms")
	            val auxSize = devInfo.getString(DeviceInfoConstrants.AUX_LCD_SIZE)?.replace("\n", "")
	            if (!auxSize.isNullOrEmpty()) {
	                val size = auxSize.split("\\*".toRegex()).dropLastWhile { it.isEmpty() }
	                    .toTypedArray()
	                auxWidth = size[0].toInt()
	                auxHeight = size[1].toInt()
	            }
	        } catch (e: RemoteException) {

	        }
    }
	}
    //AUX LCD
}