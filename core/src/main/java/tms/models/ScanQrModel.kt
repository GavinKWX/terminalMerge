package tms.models

data class ScanQrRequestModel(
	val SEQ_NO: String?,
	val DEV_SN: String?,
	val TXN_AMT: String?,
	val QR_TXN_DT: String?,
	val QR_PAYLOAD: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val POS_REF_NO: String?,
	val OrderingItemBase64: String? = null,
	var OrderingItemBase64Img: String? = null,
	var CORRELATION_REF: String? = null,
	var ADDITIONAL_INFO: Any? = null,
)

data class ScanQrResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val QR_PAY_BRAND: String?,
	val QR_PAY_BRAND_DESC: String?,
	val ACQ_CODE: String?,
	val ACQ_MID: String?,
	val ACQ_TID: String?,
	val IS_UNIONPAY_TXN: Boolean?,
	val UPI_VOUCHER_CODE: String?,
	val UPI_DISCOUNT_AMOUNT: String?,
	val UPI_MARKUP_FEE: String?,
	val IS_TPA_ACCOUNT: Boolean?
)
