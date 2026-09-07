package tms.models.ioupay

data class IoupayScanEnquiryRequestModel(
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val DEV_SN: String?
)

data class IoupayScanEnquiryResponseModel(
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val QR_PAY_BRAND: String?,
	val QR_PAY_BRAND_DESC: String?,
	val ACQ_MID: String?,
	val TOTAL_AMOUNT: String?,
	val SCHEDULE_LIST: List<SCHEDULE>?
)

data class SCHEDULE(
	var SCHEDULE_AMOUNT: String?,
	var SCHEDULE_AMOUNT_STRING: String?,
	var IS_TODAY_PAYMENT: Boolean?,
	var PAYMENT_DATE: String?
)