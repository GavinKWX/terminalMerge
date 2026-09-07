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

class QrEnquiryHandler(private val envManager: EnvironmentManager) {
	private val className: String = (QrEnquiryHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		qrMid: String,
		qrTid: String,
		qrRefId: String,
		apiTimeoutSec: Int = 90,
	): QrEnquiryResponseModel {
		val gson = Gson()
		val qrEnquiryRequestModel = QrEnquiryRequestModel(
			TerminalInfo.sqnNum(),
			qrMid,
			qrTid,
			qrRefId,
			TerminalInfo.serialNumber()
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
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "QR/Enquiry",
			postData = requestJsonString,
			httpHeaders = httpHeaders,
			timeoutSecs = apiTimeoutSec,
		)


		try {
			val gsonResp = gson.fromJson(resp, QrEnquiryResponseModel::class.java)
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