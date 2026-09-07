package tms.models.ioupay

data class IoupayScanQrRequestModel(
	val SEQ_NO: String?,
	val TXN_AMT: String?,
	val QR_TXN_DT: String?,
	val QR_PAYLOAD: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val DEV_SN: String?,
	val PAYMENT_TYPE: String?,
	val PACKAGE_CODE: String?,
	val TENURE: Int?
)

data class IoupayScanQrResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val QR_PAY_BRAND: String?,
	val QR_PAY_BRAND_DESC: String?,
	val ACQ_CODE: String?,
	val TOTAL_AMOUNT: String?,
	val TOTAL_ENQUIRY: Int?,
	val SCHEDULE_LIST: List<IoupaySchedule>?

)

data class IoupaySchedule(
	var SCHEDULE_AMOUNT: String?,
	var SCHEDULE_AMOUNT_STRING: String?,
	var IS_TODAY_PAYMENT: Boolean?,
	var PAYMENT_DATE: String?,
)