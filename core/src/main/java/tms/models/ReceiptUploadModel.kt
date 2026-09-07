package tms.models

//TODO ADD PreAuth BatchNo
data class ReceiptUploadRequestModel(
	var SEQ_NO: String?,
	var TXN_DT: String?,
	var TXN_TYPE: String?,
	var MID: String?,
	var TID: String?,
	var MTI: String?,
	var NII: String?,
	var SCHEME_ID: String?,
	var AID: String?,
	var CARD_MASKED: String?,
	var CARD_HASHED: String?,
	var RRN: String?,
	var APPR_CODE: String?,
	var RRN_ORI: String?,
	var APPR_CODE_ORI: String?,
	var TXN_AMT: String?,
	var INV_NO: String?,
	var STAN: String?,
	var BATCH_NO: String?,
	var RESP_CODE: String?,
	var APP_VER: String?,
	var DEV_SN: String?,
	var CARD_SCHEME: String?,
	var SCHEME_TYPE: String?,
	var ENTRY_TYPE: String?,
	var ARQC: String?,
	var TVR: String?,
	var POS_REF_NO: String?,
	var PAYMENT_PRODUCT_ID: String?,
	var CVM: String?,
	var CARD_LABEL: String?,
	var BatchNo_PreAuth: String?,
	var TransactionEPP: Any?,
	var CASHOUT_AMT: String?,
	var OrderingItemBase64: String? = null,
	var OrderingItemBase64Img: String? = null,
	var CORRELATION_REF: String? = null,
	var ADDITIONAL_INFO: Any? = null,
)

data class ReceiptUploadResponseModel(
	var SEQ_NO: String?,
	var RESP_CODE: String?,
	var RESP_DESC: String?,
	var REF_ID: String?
)

data class EppDetail(
	val Acquirer: String?,
	val Tenure: String?,
	val TotalAmt: String?,
	val FirstMonthAmt: String?,
	val MonthlyAmt: String?,
	val FinalAmt: String?
)