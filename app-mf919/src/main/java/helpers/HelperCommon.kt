package helpers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.activity.AttendActivity
import com.sc.mf919.kotlin.activity.AttendDenominationActivity
import com.sc.mf919.kotlin.activity.UnattendActivity
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.*
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
		fun disableKey(tempContext: Context, lockValue: String) {
			val intent = Intent("com.morefun.disablekey")
			intent.putExtra("value", lockValue)
			intent.setPackage("com.morefun.MFFramework")
			tempContext.sendBroadcast(intent)
		}

		fun sdkPrint(list: List<MulPrintStrEntity?>?) {
			try {
				//int fontSize = FontFamily.MIDDLE;
				val config = Bundle()
				//config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath);
				config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 30)
				DeviceHelper.getPrinter().printStr(list, object : OnPrintListener.Stub() {
					@Throws(RemoteException::class)
					override fun onPrintResult(result: Int) {
						/*this.runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            //button.setEnabled(true);
                        }
                    });*/
						//showResult(textView, result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
						//this.sysPrint(result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
					}
				}, config)
			} catch (e: RemoteException) {
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

		@JvmStatic
		fun formatMillis(millis: Long): String {
			val hours = (millis / (1000 * 60 * 60)) % 24
			val minutes = (millis / (1000 * 60)) % 60
			val seconds = (millis / 1000) % 60

			return String.format("%02d:%02d:%02d", hours, minutes, seconds)
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

		@JvmStatic
		fun getHomeScreenIntent(context: Context, dbModelTerminalConfig: DbModelTerminalConfig?) : Intent{
			var newIntent = Intent(context, AttendActivity::class.java)
			if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "DENOMINATION")) {
				newIntent = Intent(context, AttendDenominationActivity::class.java)
			} else if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")) {
				newIntent = Intent(context, UnattendActivity::class.java)
			}
			return newIntent
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