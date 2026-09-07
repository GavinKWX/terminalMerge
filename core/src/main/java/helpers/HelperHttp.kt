package helpers

import android.util.Log
import enums.EnumHttpMethod
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class HelperHttp {

	companion object {
		private val className: String = (HelperHttp::class.qualifiedName).toString()

		/*
		 * Key material must not reach the log, and this is the choke point for every TMS call --
		 * including tms/InjectionKey, whose response body carries TMK/TAK/MEK/PIN/TLE and
		 * TMK_Key_Left / TMK_Key_Right for every acquirer. Redacting at the callers is not enough:
		 * the response is logged verbatim below for ALL endpoints, and TerminaLog is uploaded.
		 *
		 * Matches "<KEYNAME>": "<value>" in the JSON body and keeps only the length, so a key-sync
		 * problem is still diagnosable (present / absent / wrong size) without shipping the key.
		 * The character class deliberately avoids a quote-escaping fight: [^\"] inside a raw string.
		 */
		private val KEY_FIELD = Regex(
			"""("(?:[A-Z_]*(?:KEY|TMK|TPK|TAK|TWK|WAK|WEK|MEK|TLE|IPEK|PIN)[A-Z_]*)"\s*:\s*)"([^"]*)"""",
			RegexOption.IGNORE_CASE
		)

		/** Redacts key values in a JSON payload, preserving structure and value length. */
		private fun redactKeys(body: String): String =
			KEY_FIELD.replace(body) { m ->
				"${m.groupValues[1]}\"<redacted:${m.groupValues[2].length} chars>\""
			}

		@JvmOverloads
		fun invokeSend(
			log: HelperLog,
			apiUrl: String,
			httpMethod: String = "POST",
			contentType: String = "application/json",
			postData: String? = null,
			timeoutSecs: Int = 90,
			httpHeaders: Map<String, String>? = null
		): String {
			/*log.appendLine(className, "ApiUrl", apiUrl)
			log.appendLine(className, "HttpMethod", httpMethod)
			log.appendLine(className, "ContentType", contentType)
			log.appendLine(className, "PostData", postData ?: "null")
			log.appendLine(className, "TimeoutSecs", timeoutSecs.toString())*/

			val url = URL(apiUrl)
			val conn = url.openConnection() as HttpURLConnection


			val start = System.currentTimeMillis()
			conn.connectTimeout = (60 * 1000)
			//if (timeoutSecs < 90) {
			if (timeoutSecs < 30) {
				conn.readTimeout = (30 * 1000)       /*Default min timeout 90 secs*/
			} else {
				conn.readTimeout = (timeoutSecs * 1000)
			}

			if (httpMethod != EnumHttpMethod.GET.httpMethod) {
				conn.requestMethod = httpMethod
				conn.doOutput = true
				conn.useCaches = false
				conn.setRequestProperty("Content-Type", contentType)

				if (!postData.isNullOrEmpty()) {
					conn.setRequestProperty("Content-Length", postData.length.toString())
				}
			}


			if (httpHeaders != null) {
				// Headers are set but no longer echoed one-per-line: they are fixed per endpoint
				// (DEV-SN / APP-VER / TERMINAL-DT / CHECKSUM) and added ~5 lines to every call.
				for (httpHeader in httpHeaders) {
					conn.setRequestProperty(httpHeader.key, httpHeader.value)
				}
			}


			val sbResponse = StringBuilder()
			var line: String?

			try {
				if (httpMethod != EnumHttpMethod.GET.httpMethod) {
					DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
				}
				//TODO Printing out in console for ease of development
				log.appendLine(className, " ----------> ($httpMethod) $apiUrl")
				log.appendLine(className,"TimeOut      : (${timeoutSecs}s)")
				log.appendLine(className, HelperText.oneLine(redactKeys("$postData")))
				log.appendLine(className," ----------> (End Request)")
				//TODO Printing out in console for ease of development

				BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
					while (br.readLine().also { line = it } != null) {
						sbResponse.appendLine(line)
					}
				}

				val finish = System.currentTimeMillis()
				val timeElapsed = finish - start
				//TODO Printing out in console for ease of development
				log.appendLine(className," <---------- (Response) $apiUrl (${timeElapsed}ms)")
				log.appendLine(className,"Http-Method  : $httpMethod")
				log.appendLine(className,"Content-Type : $contentType")
				log.appendLine(className, HelperText.oneLine(redactKeys("$sbResponse")))
				log.appendLine(className,"<---------------------------End------------------------->")
				//TODO Printing out in console for ease of development

				return sbResponse.toString()
			} catch (cEx: ConnectException){
				return "Network Disconnected"
			} catch (uEx: UnknownHostException) {
				return "Network Disconnected"
			} catch (t: TimeoutException) {
				return "Timeout"
			} catch (sEx: SocketTimeoutException) {
				return "Timeout"
			} catch (ex: Exception) {
				log.appendLine(className, "Http Response (Exception)", redactKeys(ex.toString()))
				val errorString = StringBuilder()

				try{
					BufferedReader(InputStreamReader(conn.errorStream)).use { br ->
						while (br.readLine().also { line = it } != null) {
							errorString.appendLine(line)
						}
					}
				} catch (errorEx: Exception) {
					errorString.appendLine(ex.message)
				}

				//TODO Printing out in console for ease of development
				val finish = System.currentTimeMillis()
				val timeElapsed = finish - start
				log.appendLine(className," <---------- (Response Exception) $apiUrl (${timeElapsed}ms)")
				log.appendLine(className,"Http-Method  : $httpMethod")
				log.appendLine(className,"Content-Type : $contentType")
				log.appendLine(className, redactKeys("$ex"))
				log.appendLine(className, HelperText.oneLine(redactKeys("$errorString")))
				log.appendLine(className,"<---------------------------End------------------------->")
				//TODO Printing out in console for ease of development

				return errorString.toString()
			}
		}
	}
}