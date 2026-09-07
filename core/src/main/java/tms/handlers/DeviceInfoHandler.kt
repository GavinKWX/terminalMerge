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
import tms.models.*
import java.io.IOException

class DeviceInfoHandler(private val envManager: EnvironmentManager) {
	private val className: String = (DeviceInfoHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		seqNum: String,
		terminalSN: String,
		devModel: String,
		devAppName: String,
		devProject: String,
		devLocation: String,
		devLaneID: String,
		firmID: String,
		firmVer: String,
		mcVersion: String,
		additionalInfo: String,
		testCase: List<DeviceTestCaseList>
	): DeviceInfoResp {
		val gson = Gson()
		val deviceInfoReq = DeviceInfoReq(
			seqNum, HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss.dateFormat),
			terminalSN, devModel, devAppName, devProject, devLocation, devLaneID, firmID, firmVer,
			mcVersion, testCase, additionalInfo
		)


		val requestJsonString = gson.toJson(deviceInfoReq)
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
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "tms/DeviceInfo",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)

		try {
			val gsonResp = gson.fromJson(resp, DeviceInfoResp::class.java)
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