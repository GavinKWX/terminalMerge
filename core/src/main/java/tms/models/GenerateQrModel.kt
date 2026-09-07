package tms.models

data class GenerateQrRequestModel(
	val SEQ_NO: String?,
	val DEV_SN: String?,
	val APP_VER: String?,
	val TXN_AMT: String?,
	val QR_TXN_DT: String?,
	val QR_MID: String?,
	val QR_TID: String?,
	val QR_REFID: String?,
	val QR_PRODUCT_CODE: String?,
	val POS_REF_NO: String?,
	val OrderingItemBase64: String? = null,
	var OrderingItemBase64Img: String? = null,
	var CORRELATION_REF: String? = null,
	var ADDITIONAL_INFO: Any? = null,
)

data class GenerateQrResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val QR_REFID: String?,
	val QR_HOST_REFNO: String?,
	val QR_TXN_REFNO: String?,
	val PAYLOAD_QR: String?,
	val PAYLOAD_IMAGE: String?,
	val BARCODE_QR: String?,
	val ACQ_MID: String?,
	val ACQ_TID: String?,
	val QR_PAY_BRAND: String?,
	val QR_PAY_BRAND_DESC: String?,
	val UPI_VOUCHER_CODE: String?,
	val UPI_DISCOUNT_AMOUNT: String?,
	val UPI_MARKUP_FEE: String?,
)