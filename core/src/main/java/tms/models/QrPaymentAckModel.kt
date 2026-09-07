package tms.models

data class QrPaymentAckRequestModel(
	val SEQ_NO: String?,
	val DEV_SN: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_TXN_REFNO: String?,
	val QR_REFID: String?
)

data class QrPaymentAckResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val QR_PAY_BRAND: String?
)