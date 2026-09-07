package tms.handlers.ioupay

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
import tms.models.ioupay.IoupayScanQrRequestModel
import tms.models.ioupay.IoupayScanQrResponseModel
import java.io.IOException

class IoupayScanQrHandler(private val envManager: EnvironmentManager) {
	private val className: String = (IoupayScanQrHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		txnAmt: String,
		qrTxnDt: String,
		qrPayload: String,
		qrMid: String,
		qrTid: String,
		qrRefId: String,
		paymentType: String,
		packageCode: String,
		tenure: Int
	): IoupayScanQrResponseModel {
		val gson = Gson()
		val ioupayScanQrRequestModel = IoupayScanQrRequestModel(
			TerminalInfo.sqnNum(),
			txnAmt,
			qrTxnDt,
			qrPayload,
			qrMid,
			qrTid,
			qrRefId,
			TerminalInfo.serialNumber(),
			paymentType,
			packageCode,
			tenure
		)

		val requestJsonString = gson.toJson(ioupayScanQrRequestModel)
		val httpHeaders: MutableMap<String, String> = mutableMapOf()
		httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
		httpHeaders["APP-VER"] = TerminalInfo.appVersion()
		httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss.dateFormat)
		httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
			requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
		)

		val resp = HelperHttp.invokeSend(
			log,
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "QR/BNPL/IOUPay/ScanQR",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)

		try {
			val gsonResp = gson.fromJson(resp, IoupayScanQrResponseModel::class.java)
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