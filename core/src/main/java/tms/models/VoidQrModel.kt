package tms.models

data class VoidQrRequestModel(
	val SEQ_NO: String?,
	val DEV_SN: String?,
	val QR_TXN_DT: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val QR_TXN_REFNO: String?,
	val VOID_DESC: String?,
	val ENC_PIN: String?
)

data class VoidQrResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_TXN_REFNO: String?,
	val IS_TPA_ACCOUNT: Boolean?
)
