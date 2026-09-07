package tms.models

data class QrCancelRequestModel(
	val SEQ_NO: String?,
	val DEV_SN: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
)

data class QrCancelResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val IS_CANCEL_SUCCESS: Boolean?,
	val QR_REFID: String?,
	val ACQ_MID: String?,
	val ACQ_TID: String?,
)