package com.sc.mf919.kotlin.helper_common

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.annotation.ColorInt
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class GenerateQr {
	private val size = 660
	private val size_width = 660
	private val size_height = 264

	@Throws(WriterException::class)
	fun createZxingBitmap(
		mContext: Context,
		message: String,
		type: String = "",
		overlayBitmap: Bitmap? = null,
		@ColorInt fgColor: Int = 0xff000000.toInt(),
		@ColorInt bgColor: Int = 0xffF2F6F7.toInt()
	): Bitmap? {
		val bitMatrix: BitMatrix?

		try {
			bitMatrix = when (type) {
				"qrCode" -> MultiFormatWriter().encode(
					message,
					BarcodeFormat.QR_CODE,
					size,
					size,
					hashMapOf(
						EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
						EncodeHintType.MARGIN to 0
					)
				)
				/*
				"dataMatrix" -> MultiFormatWriter().encode(
					message, BarcodeFormat.DATA_MATRIX, size, size
				)
				"pdf417" -> MultiFormatWriter().encode(
					message, BarcodeFormat.PDF_417, size_width, size_height
				)
				"Barcode-39" -> MultiFormatWriter().encode(
					message, BarcodeFormat.CODE_39, size_width, size_height
				)
				"Barcode-93" -> MultiFormatWriter().encode(
					message, BarcodeFormat.CODE_93, size_width, size_height
				)
				"AZTEC" -> MultiFormatWriter().encode(
					message, BarcodeFormat.AZTEC, size, size
				)
				* */
				else -> MultiFormatWriter().encode(
					message, BarcodeFormat.CODE_128, size_width, size_height
				)
			}
		} catch (e: IllegalArgumentException) {
			// Unsupported format
			return null
		}

		return bitMatrix.encodeAsQrCodeBitmap(size, overlayBitmap, fgColor, bgColor)
	}

	@Throws(WriterException::class)
	fun BitMatrix.encodeAsQrCodeBitmap(
		dimension: Int,
		overlayBitmap: Bitmap?,
		fgColor: Int,
		bgColor: Int
	): Bitmap? {
		val w = this.width
		val h = this.height
		val pixels = IntArray(w * h)
		for (y in 0 until h) {
			val offset = y * w
			for (x in 0 until w) {
				pixels[offset + x] = if (this.get(x, y)) fgColor else bgColor
			}
		}

		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		bitmap.setPixels(pixels, 0, dimension, 0, 0, w, h)
		return if (overlayBitmap != null) {
			bitmap.addOverlayToCenter(overlayBitmap)
		} else {
			bitmap
		}
	}

	private fun Bitmap.addOverlayToCenter(overlayBitmap: Bitmap): Bitmap {
		val bitmap2Width = overlayBitmap.width
		val bitmap2Height = overlayBitmap.height
		val marginLeft = (this.width * 0.5 - bitmap2Width * 0.5).toFloat()
		val marginTop = (this.height * 0.5 - bitmap2Height * 0.5).toFloat()
		val canvas = Canvas(this)
		canvas.drawBitmap(this, Matrix(), null)
		canvas.drawBitmap(overlayBitmap, marginLeft, marginTop, null)
		return this
	}
}