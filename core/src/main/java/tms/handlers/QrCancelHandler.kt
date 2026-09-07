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
import tms.models.QrCancelRequestModel
import tms.models.QrCancelResponseModel
import java.io.IOException

class QrCancelHandler(private val envManager: EnvironmentManager) {
	private val className: String = (QrCancelHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		qrMid: String,
		qrTid: String,
		qrRefId: String
	): QrCancelResponseModel {
		val gson = Gson()
		val qrEnquiryRequestModel = QrCancelRequestModel(
			TerminalInfo.sqnNum(),
			TerminalInfo.serialNumber(),
			qrMid,
			qrTid,
			qrRefId,
		)


		val requestJsonString = gson.toJson(qrEnquiryRequestModel)
		val httpHeaders: MutableMap<String, String> = mutableMapOf()
		httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
		httpHeaders["APP-VER"] = TerminalInfo.appVersion()
		httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss.dateFormat)
		httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
			requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
		)


		val resp = HelperHttp.invokeSend(
			log,
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "TMS/QR/CancelPayload",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)


		try {
			val gsonResp = gson.fromJson(resp, QrCancelResponseModel::class.java)
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