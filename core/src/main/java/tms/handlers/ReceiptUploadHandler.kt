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
import tms.models.EppDetail
import tms.models.FoodLinkVerificationRequestModel
import tms.models.ReceiptUploadRequestModel
import tms.models.ReceiptUploadResponseModel
import java.io.IOException

class ReceiptUploadHandler(private val envManager: EnvironmentManager) {
	private val className: String = (ReceiptUploadHandler::class.qualifiedName).toString()

	@Throws(IOException::class)
	fun invoke(
		log: HelperLog,
		seqNo: String?,
		txnDt: String?,
		txnType: String?,
		mid: String?,
		tid: String?,
		mti: String?,
		nii: String?,
		schemeID: String?,
		aid: String?,
		cardMasked: String?,
		cardHashed: String?,
		rrn: String?,
		approvalCode: String?,
		rrnOri: String?,
		approvalCodeOri: String?,
		txnAmt: String?,
		invNo: String?,
		stan: String?,
		batchNo: String?,
		tRespCode: String?,
		appVer: String?,
		cardScheme: String?,
		schemeType: String?,
		entryType: String?,
		arqc: String?,
		tvr: String?,
		posRefNo: String?,
		paymentProductID: String?,
		cvm: String?,
		cardLabel: String?,
		batchNoPreauth: String?,
		eppDetails: String?,
		cashOutAmt: String?,
		orderingItem: String? = null,
		orderingItemImage: String? = null,
		correlationRef: String? = null,
		additionalInfo: String? = null,
	): ReceiptUploadResponseModel {
		val gson = Gson()

		val parsedAddInfo = try {
			gson.fromJson(additionalInfo, FoodLinkVerificationRequestModel::class.java)
		} catch (ex: Exception) {
			android.util.Log.d("TmsHandler", "Additional Info Parsed Exception")
			FoodLinkVerificationRequestModel("", "", "", "", "", "")
		}

		val receiptUploadRequestModel = ReceiptUploadRequestModel(
			seqNo,
			txnDt,
			txnType,
			mid,
			tid,
			mti,
			nii,
			schemeID,
			aid,
			cardMasked,
			cardHashed,
			rrn,
			approvalCode,
			rrnOri,
			approvalCodeOri,
			txnAmt,
			invNo,
			stan,
			batchNo,
			tRespCode,
			appVer,
			TerminalInfo.serialNumber(),
			cardScheme,
			schemeType,
			entryType,
			arqc,
			tvr,
			posRefNo,
			paymentProductID,
			cvm,
			cardLabel,
			batchNoPreauth,
			gson.fromJson(eppDetails, EppDetail::class.java),
			cashOutAmt,
			orderingItem,
			orderingItemImage,
			correlationRef,
			parsedAddInfo
		)

		val requestJsonString = gson.toJson(receiptUploadRequestModel)
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
			apiUrl = envManager.get(EnvironmentVariables::baseUrl) + "tms/Receipt/ReceiptUpload",
			postData = requestJsonString,
			httpHeaders = httpHeaders
		)

		try {
			val gsonResp = gson.fromJson(resp, ReceiptUploadResponseModel::class.java)
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