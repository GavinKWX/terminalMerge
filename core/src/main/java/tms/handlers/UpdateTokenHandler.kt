package tms.handlers

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumDateFormat
import helpers.HelperCrypto
import helpers.HelperDate
import helpers.HelperHttp
import helpers.HelperLog
import helpers.TerminalInfo
import tms.models.UpdateTokenResponseModel
import java.io.IOException

/*
 * Tells the server which installation of the app is now running on this device, so a settlement can
 * refuse to close transactions that belong to the previous one. Recorded in Terminal_App_History
 * against the token it replaces; the receipts only carry the value.
 *
 * Idempotent by design -- reporting the same token twice writes nothing and returns the same
 * response, so a failed or uncertain call should simply be retried. A 400 means DEV-SN or
 * INSTALL-APP-VER was missing or failed validation, and retrying unchanged will not help.
 */
class UpdateTokenHandler(private val envManager: EnvironmentManager) {
	private val className: String = (UpdateTokenHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		installToken: String,
	): UpdateTokenResponseModel {
		val gson = Gson()

		/*
		 * No meaningful body -- the token travels in the header exactly as it does on every other
		 * call, so there is only one place it can be wrong. An empty JSON object and not "" or null:
		 * confirmed with the backend that the server checksums `{}` here, the same as every other
		 * endpoint, and HelperHttp.invokeSend writes postData unguarded for any non-GET method.
		 */
		val requestJsonString = gson.toJson(emptyMap<String, String>())
		val httpHeaders: MutableMap<String, String> = mutableMapOf()
		httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
		httpHeaders["APP-VER"] = TerminalInfo.appVersion()
		httpHeaders["INSTALL-APP-VER"] = installToken
		httpHeaders["APP-TYPE"] = TerminalInfo.APP_TYPE
		httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(
			EnumDateFormat.yyyyMMddHHmmss.dateFormat
		)
		// Underscore, not hyphen. This is the one header in the API that is not hyphenated.
		httpHeaders["TERMINAL_IP"] = TerminalInfo.ipAddress()
		httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
			requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
		)

		val resp = HelperHttp.invokeSend(
			log,
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "tms/UpdateToken",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)

		try {
			val gsonResp = gson.fromJson(resp, UpdateTokenResponseModel::class.java)
			val respCode = gsonResp?.RESP_CODE

			if (respCode != "0000") {
				throw IOException(resp)
			}
			return gsonResp
		} catch (ex: Exception) {
			log.appendLine(className, "$className (Exception)", ex.toString())
			try {
				JsonParser.parseString(ex.message)
				throw IOException(ex.message)
			} catch (e: JsonSyntaxException) {
				throw IOException("{\"RESP_CODE\":\"99999\", \"RESP_DESC\":\"${resp}\"}")
			}
		}
	}
}
