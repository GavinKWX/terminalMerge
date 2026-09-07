package helpers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi
import com.google.gson.JsonObject
import com.library.terminal.Cryptography
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.morefun.yapi.device.printer.OnPrintListener
import com.morefun.yapi.device.printer.PrinterConfig
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.helper_common.*
import env.*

import enums.EnumDateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.security.InvalidParameterException
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class HelperCommon {
	companion object {
		lateinit var context: Context

		/**
		 * Collapse a multi-line payload onto one physical log line.
		 *
		 * A pretty-printed JSON body logged directly becomes ~30 physical lines, and only the LAST
		 * of them carries the [RowIdentifier] tag that appendLine appends -- so the other 29 are
		 * un-greppable and cannot be tied back to their transaction. Use this for HTTP bodies and
		 * websocket frames.
		 */
		@JvmStatic
		fun oneLine(value: String?): String = HelperText.oneLine(value)

		@JvmStatic
		fun getDateString(dateFormat: String): String = HelperDate.getDateString(dateFormat)

		@JvmStatic
		fun getDateString(date: Date, dateFormat: String): String = HelperDate.getDateString(date, dateFormat)

		@JvmStatic
		fun toHmacSha256(msg: String, key: String): String = HelperCrypto.toHmacSha256(msg, key)

		@JvmStatic
		fun getLogFileDirectory(): String {
			return "${context.applicationInfo.dataDir}/files/Logs"
		}

		@JvmStatic
		fun getZipFileDirectory(): String {
			return getLogFileDirectory() + "/ZipFiles"
		}

		@JvmStatic
		fun getRandomAlphaNumeric(length: Int): String {
			val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')

			return allowedChars.shuffled().take(length).joinToString("")
		}

		@JvmStatic
		fun getSession(): String {
			val uuid = UUID.randomUUID().toString()
			return uuid.substring(0, 7)
		}


		@JvmStatic
		@RequiresApi(Build.VERSION_CODES.O)
		fun aesEncrypt(transformation: String, skey: String, msg: String): String {
			val key = SecretKeySpec(skey.toByteArray(), "AES")
			val cipher = Cipher.getInstance(transformation)
			cipher.init(Cipher.ENCRYPT_MODE, key)
			val cipherText = cipher.doFinal(msg.toByteArray())

			return Base64.getEncoder().encodeToString(cipherText)
		}

		@JvmStatic
		@RequiresApi(Build.VERSION_CODES.O)
		fun aesDerypt(transformation: String, skey: String, encryptedMsg: String): String {
			val key = SecretKeySpec(skey.toByteArray(), "AES")
			val cipher = Cipher.getInstance(transformation)
			cipher.init(Cipher.DECRYPT_MODE, key)
			val plainBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedMsg))

			return String(plainBytes)
		}

		@JvmStatic
		@Throws(Exception::class)
		fun AESencryptV2(strToEncrypt: String, secret: String): String? {
			val data = strToEncrypt.toByteArray(charset("UTF-8"))
			val cipher =
				Cipher.getInstance("AES/ECB/PKCS5Padding")
			cipher.init(
				Cipher.ENCRYPT_MODE,
				SecretKeySpec(secret.toByteArray(charset("UTF-8")), "AES")
			)
			return android.util.Base64.encodeToString(
				cipher.doFinal(data),
				android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP
			)
		}

		@JvmStatic
		fun generateEncodedPIN(pin: String): String? {
			val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
			val rnd = Random()
			val rndDigitFront = rnd.nextInt(10)
			val rndDigitRear = rnd.nextInt(10)

			var rndFrontNumber = ""
			var rndRearNumber = ""
			if (rndDigitFront > 0) {
				val tempRndFrontNumber = generateRandomDigits(rndDigitFront)
				rndFrontNumber = tempRndFrontNumber.toString()
			}

			if (rndDigitRear > 0) {
				val tempRndRearNumber = generateRandomDigits(rndDigitRear)
				rndRearNumber = tempRndRearNumber.toString()
			}

			val finalPIN = String.format("%02d", rndDigitFront) + String.format("%02d", rndDigitRear) + rndFrontNumber + pin + rndRearNumber

			return AESencryptV2(finalPIN, environmentManager.get(EnvironmentVariables::serverHashKey))
		}

		@JvmStatic
		private fun generateRandomDigits(n: Int): Int {
			val m = Math.pow(10.0, (n - 1).toDouble()).toInt()
			return m + Random().nextInt(9 * m)
		}

		// Max time to wait for a single print job to finish before giving up,
		// so a printer fault can never hang the calling (background) thread forever.
		private const val PRINT_TIMEOUT_SECONDS = 30L

		/**
		 * Prints synchronously: blocks the calling thread until the printer reports the job is
		 * complete via [OnPrintListener.onPrintResult]. This guarantees that when consecutive
		 * receipts are printed (e.g. multi-product settlement), each job fully finishes before the
		 * next one is dispatched, so no print data is dropped by overlapping jobs.
		 *
		 * MUST be called off the main/UI thread (it blocks). All existing callers run inside
		 * background tasks / coroutines, which is correct.
		 */
		@JvmStatic
		@Synchronized
		fun sdkPrint(list: List<MulPrintStrEntity?>?) {
			try {
				//int fontSize = FontFamily.MIDDLE;
				val config = Bundle()
				//config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath);
				config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 30)

				// Latch released by the printer callback once the job is done.
				val printDone = CountDownLatch(1)
				DeviceHelper.getMultipleAppPrinter().printStr(list, object : OnPrintListener.Stub() {
					@Throws(RemoteException::class)
					override fun onPrintResult(result: Int) {
						println("onPrintResult :: $result")
						printDone.countDown()
					}
				}, config)

				// Await the previous print before returning so the next sdkPrint cannot start
				// while this job is still feeding the printer.
				if (!printDone.await(PRINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					println("sdkPrint :: timed out after $PRINT_TIMEOUT_SECONDS s waiting for onPrintResult")
				}
			} catch (e: RemoteException) {
				e.printStackTrace()
			} catch (e: InterruptedException) {
				// Preserve the interrupt status if the waiting thread is cancelled.
				Thread.currentThread().interrupt()
				e.printStackTrace()
			}
		}

		@JvmStatic
		@Throws(Exception::class)
		fun AESencryption(strToEncrypt: String, secret: String): String {
			if (strToEncrypt.isEmpty()) {
				throw IllegalArgumentException("Input string to encrypt cannot be empty")
			}
			if (secret.length != 16 && secret.length != 24 && secret.length != 32) {
				throw IllegalArgumentException("Encryption key must be 16, 24, or 32 bytes long")
			}

			try {
				val data = strToEncrypt.toByteArray(charset("UTF-8"))
				val secretKey = SecretKeySpec(secret.toByteArray(charset("UTF-8")), "AES")

				val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
				cipher.init(Cipher.ENCRYPT_MODE, secretKey)

				val encryptedBytes = cipher.doFinal(data)

				return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
			} catch (e: Exception) {
				throw Exception("Error during AES encryption: ${e.message}", e)
			}
		}



		@JvmStatic
		@Throws(Exception::class)
		fun AESdecryption(strToDecrypt: String, secret: String): String {
			if (strToDecrypt.isEmpty()) {
				throw IllegalArgumentException("Input string to decrypt cannot be empty")
			}
			if (secret.length != 16 && secret.length != 24 && secret.length != 32) {
				throw IllegalArgumentException("Decryption key must be 16, 24, or 32 bytes long")
			}

			try {
				val encryptedBytes = android.util.Base64.decode(strToDecrypt, android.util.Base64.NO_WRAP)
				val secretKey = SecretKeySpec(secret.toByteArray(charset("UTF-8")), "AES")

				val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
				cipher.init(Cipher.DECRYPT_MODE, secretKey)

				val originalBytes = cipher.doFinal(encryptedBytes)

				return String(originalBytes, charset("UTF-8"))
			} catch (e: Exception) {
				throw Exception("Error during AES decryption: ${e.message}", e)
			}
		}

		// Extension functions for safe JSON retrieval
		fun JsonObject.getStringOrDefault(key: String, default: String): String {
			return if (this.has(key) && !this[key].isJsonNull) this[key].asString else default
		}

		fun JsonObject.getIntOrDefault(key: String, default: Int): Int {
			return if (this.has(key) && !this[key].isJsonNull) this[key].asInt else default
		}

		fun JsonObject.getBooleanOrDefault(key: String, default: Boolean): Boolean {
			return if (this.has(key) && !this[key].isJsonNull) this[key].asBoolean else default
		}

		fun JsonObject.getDoubleOrDefault(key: String, default: Double): Double {
			return if (this.has(key) && !this[key].isJsonNull) this[key].asDouble else default
		}

		fun CoroutineScope.runAfterDelayCancelable(delayMillis: Long, block: suspend () -> Unit): Job {
			return launch {
				delay(delayMillis) // cancellable delay
				ensureActive() // optional, checks for cancellation
				block()
			}
		}
		// Extension functions for safe JSON retrieval

		fun formatMillis(millis: Long): String {
			val hours = (millis / (1000 * 60 * 60)) % 24
			val minutes = (millis / (1000 * 60)) % 60
			val seconds = (millis / 1000) % 60

			return String.format("%02d:%02d:%02d", hours, minutes, seconds)
		}

		fun formatTTSAmount(valueAmount: Long, ttsManager: TTSManager) {
			/*println("readAmount :: $valueAmount")
            val split = valueAmount.split(".")
            if(split.size >=2 && split[1] != "00") {
                TTSManager.speak("Received ${split[0]} Ringgit ${split[1]} Cents")
                //tts.speak("Received ${split[0]} Ringgit ${split[1]} Cents", TextToSpeech.QUEUE_FLUSH, null, "")
            } else {
                TTSManager.speak("Received ${split[0]} Ringgit")
                //tts.speak("Received ${split[0]} Ringgit", TextToSpeech.QUEUE_FLUSH, null, "")
            }*/

			val ringgit = valueAmount / 100
			val sen = valueAmount % 100

			val billion = ringgit / 1_000_000_000
			val million = (ringgit % 1_000_000_000) / 1_000_000
			val thousand = (ringgit % 1_000_000) / 1_000
			val remainder = ringgit % 1_000

			val parts = mutableListOf<String>()

			if (billion > 0) parts.add("$billion billion")
			if (million > 0) parts.add("$million million")
			if (thousand > 0) parts.add("$thousand thousand")
			if (remainder > 0) parts.add("$remainder")

			val ringgitPart =
			if (parts.isNotEmpty()) {
				parts.joinToString(" ") + " ringgit"
			} else {
				""
			}

			//val ringgitPart = parts.joinToString(" ") + " ringgit"

			val senPart = if (sen > 0) {
				"and $sen sen"
			} else {
				""
			}

			ttsManager.speak("Receive $ringgitPart $senPart")
		}
	}

	class Db {
		companion object {
			fun putToContentValues(cvs: ContentValues, key: String, value: Any?) : ContentValues {
				when (value) {
					is Boolean -> cvs.put(key, value)
					is Byte -> cvs.put(key, value)
					is ByteArray -> cvs.put(key, value)
					is Double -> cvs.put(key, value)
					is Float -> cvs.put(key, value)
					is Int -> cvs.put(key, value)
					is Long -> cvs.put(key, value)
					is Short -> cvs.put(key, value)
					is String -> cvs.put(key, value)
					is Date -> {
						val sdf = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH)
						cvs.put(key, sdf.format(value))
					}
					null -> cvs.putNull(key)
					else -> throw InvalidParameterException()
				}

				return cvs
			}


			fun getCursorDate(cursor: Cursor, columnIndex: Int, dateFormat: String = EnumDateFormat.yyyyMMddHHmmss.dateFormat): Date {
				val dateString: String = cursor.getString(columnIndex)

				val format: DateFormat = SimpleDateFormat(dateFormat, Locale.ENGLISH)

				val date: Date = try {
					format.parse(dateString) as Date
				} catch (e: ParseException) {
					val c1 = Calendar.getInstance()
					c1.set(Calendar.YEAR, 1970)
					c1.set(Calendar.MONTH, 1)
					c1.set(Calendar.DATE, 1)
					c1.time
				}
				return date
			}

			fun View.setDebouncedOnClickListener(debounceTime: Long = 1000L, onClick: (View) -> Unit) {
				var lastClickTime = 0L
				this.setOnClickListener {
					if (SystemClock.elapsedRealtime() - lastClickTime >= debounceTime) {
						lastClickTime = SystemClock.elapsedRealtime()
						onClick(it)
					}
				}
			}

			fun getTimestamp(): Long? {
				val formattedDate = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)

				val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH)
				val date = dateFormat.parse(formattedDate)

				return date?.time;
			}
		}
	}
}