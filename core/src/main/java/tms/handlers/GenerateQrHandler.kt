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
import tms.models.FoodLinkVerificationRequestModel
import tms.models.GenerateQrRequestModel
import tms.models.GenerateQrResponseModel
import java.io.IOException


class GenerateQrHandler(private val envManager: EnvironmentManager) {
	private val className: String = (GenerateQrHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		txnAmt: String,
		qrTxnDt: String,
		qrMid: String,
		qrTid: String,
		qrRefId: String,
		qrProductCode: String,
		posRefNo: String,
		orderingItemBase64: String? = null,
		orderingItemBase64Img: String? = null,
		qrCorrelationRef: String? = null,
		qrAdditionalInfo: String? = null
		): GenerateQrResponseModel {
		val gson = Gson()
		val parsedAddInfo = try {
			gson.fromJson(qrAdditionalInfo, FoodLinkVerificationRequestModel::class.java)
		} catch (ex: Exception) {
			android.util.Log.d("TmsHandler", "Additional Info Parsed Exception")
			FoodLinkVerificationRequestModel("", "", "", "", "", "")
		}

		val merchantConfigReq = GenerateQrRequestModel(
			TerminalInfo.sqnNum(),
			TerminalInfo.serialNumber(),
			TerminalInfo.appVersion(),
			txnAmt,
			qrTxnDt,
			qrMid,
			qrTid,
			qrRefId,
			qrProductCode,
			posRefNo,
			orderingItemBase64,
			orderingItemBase64Img,
			qrCorrelationRef,
			parsedAddInfo,
		)


		val requestJsonString = gson.toJson(merchantConfigReq)
		val httpHeaders: MutableMap<String, String> = mutableMapOf()
		httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
		httpHeaders["APP-VER"] = TerminalInfo.appVersion()
		httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss.dateFormat)
		httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
			requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
		)


		val resp = HelperHttp.invokeSend(
			log,
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "tms/QR/GeneratePayload",
			postData = requestJsonString,
			httpHeaders = httpHeaders,
			timeoutSecs = 180,
		)

		try {
			val gsonResp = gson.fromJson(resp, GenerateQrResponseModel::class.java)
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