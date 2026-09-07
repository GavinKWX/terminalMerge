package tms.handlers

import helpers.TerminalInfo
import helpers.HelperDate
import helpers.HelperCrypto

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumDateFormat
import helpers.HelperHttp
import helpers.HelperLog
import tms.models.InjectionKeyRequestModel
import tms.models.InjectionKeyResponseModel
import java.io.IOException

class InjectionKeyHandler(private val envManager: EnvironmentManager)  {
	private val className: String = (InjectionKeyHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		seqNum: String,
		terminalSN: String,
		devModel: String,
		devAppName: String,
		devProject: String,
		devLocation: String,
		additionalInfo: String
	): InjectionKeyResponseModel {
		val gson = Gson()
		val injectionKeyReq = InjectionKeyRequestModel(
			seqNum,
			HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss.dateFormat),
			terminalSN,
			devModel,
			devAppName,
			devProject,
			devLocation,
			additionalInfo
		)

		val requestJsonString = gson.toJson(injectionKeyReq)
		val httpHeaders: MutableMap<String, String> = mutableMapOf()
		httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
		httpHeaders["APP-VER"] = TerminalInfo.appVersion()
		httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(
			EnumDateFormat.yyyyMMddHHmmss.dateFormat
		)
		httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
			requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
		)

		val resp = HelperHttp.invokeSend(
			log,
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "tms/InjectionKey",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)

		try {
			val gsonResp = gson.fromJson(resp, InjectionKeyResponseModel::class.java)
			val respCode = gsonResp?.RESP_CODE

			if (respCode != "0000") {
				throw IOException(resp)
			}
			return gsonResp
		} catch (ex: Exception) {
			/*
			 * ex.toString() here used to write the RAW injection-key response body to the log --
			 * throw IOException(resp) above carries TMK/TAK/MEK/PIN/TLE and TMK_Key_Left /
			 * TMK_Key_Right for every acquirer, and TerminaLog is uploaded to TMS. Log the
			 * exception type plus just the response code, which is what actually diagnoses a
			 * failed injection. The pattern deliberately contains no quote characters.
			 */
			val respCodeOnly = Regex("""RESP_CODE[^:]*:[^0-9]*([0-9]+)""")
				.find(resp ?: "")?.groupValues?.getOrNull(1) ?: "unknown"
			log.appendLine(
				className,
				"$className (Exception)",
				"${ex.javaClass.simpleName}: RESP_CODE=$respCodeOnly (body withheld: key material)"
			)
			try {
				JsonParser.parseString(ex.message)
				throw IOException(ex.message)
			} catch (e: JsonSyntaxException) {
				throw IOException("{\"RESP_CODE\":\"99999\", \"RESP_DESC\":\"${resp}\"}")
			}
		}
	}
}