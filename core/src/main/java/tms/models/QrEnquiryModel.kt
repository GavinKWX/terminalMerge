package tms.models

data class QrEnquiryRequestModel(
	val SEQ_NO: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val DEV_SN: String?,
)

data class QrEnquiryResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val ACQ_MID: String?,
	val ACQ_CODE: String? = null,
	val ACQ_TID: String?,
	val BANK_AUTH_CODE: String?,
	val QR_PAY_BRAND: String?,
	val QR_PAY_BRAND_DESC: String?,
	val VOID_DATETIME: String?,
	val IS_UNIONPAY_TXN: Boolean?,
	val UPI_VOUCHER_CODE: String?,
	val UPI_DISCOUNT_AMOUNT: String?,
	val UPI_MARKUP_FEE: String?,
	val IS_TPA_ACCOUNT: Boolean?
)