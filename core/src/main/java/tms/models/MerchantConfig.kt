package tms.models

data class MerchantConfigReq (
	val SEQ_NO: String?,
	val TXN_DT: String?,
	val DEV_SN: String?,
	val DEV_MODEL: String?,
	val DEV_APPNAME: String?,
	val DEV_PROJECT: String?,
	val DEV_LOCATION: String?,
	val ADD_INFO: String?,
	val MC_VER: String?
)

data class MerchantConfigResp (
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val MID: String?,
	val TID: String?,
	val SC_MID: String?,
	val SC_TID: String?,
	val MERCHANT_NAME: String?,
	val MERCHANT_ADDR: String?,
	val PRIMARY_HOST_IP: String?,
	val PRIMARY_HOST_PORT: String?,
	val SECONDARY_HOST_IP: String?,
	val SECONDARY_HOST_PORT: String?,
	val HOST_TIMEOUTMS: String?,
	val AUTO_SETTLE_T1: String?,
	val AUTO_SETTLE_T2: String?,
	val AUTO_SETTLE_T3: String?,
	val WAIT_CARD_MS: String?,
	val QR_PAYMENT_API: String?,
	val QR_PAY_MID: String?,
	val QR_PAY_TID: String?,
	val MC_VER: String?,
	val IPAY88_MERCHANTCODE: String?,
	val TPDU: String?,
	val NII: String?,
	val LAST_SETTLEMENT_BATCH_NO: String?,
	val LAST_STAN: String?,
	val LAST_INVOICE_NO: String?,
	val CARD_SETTINGS: CardSettings?,
	val EWALLET_MERCHANT_SCANS: EWalletMerchantScan?,
	val BNPL: BNPL?,
	val GENERATE_QR: GenerateQR?,
	val EPP: EppAcqConObjectModel?,
	val MOTO_SETTINGS: CardSettings?,
	val SUPPORT: SupportObject? = null,
	val Customization: Any?,
	val Marketings: MarketingObject?,
	val Advertisements: ArrayList<AdvertisementObject>?,
	val AcquirerLogo: AcquirerLogoReplaceObject?,
	val QUICK_ACTION: QuickActionObject?,
	val SKIP_TXN_VALIDATION: String?,
	val TPA_MERCHANT_LOGO_URL: String?,
	val CERT_FILE_URL: String?,
	val IS_FOODLINK: String? = null
)

data class CardSettings(
	val ACQ_CODE: String?,
	val GOBIZ: CardSettingsObjectModel?,
	val PAYDEE: CardSettingsObjectModel?,
	val BSN: CardSettingsObjectModel?,
	val BSN_CARDZONE: CardSettingsObjectModel?,
	val PAYEX_GOBIZ: CardSettingsObjectModel?,
	val FINEXUS: CardSettingsObjectModel,
)

data class CardSettingsObjectModel(
	val ACQ_MID: String?,
	val ACQ_TID: String?,
	val PRIMARY_HOST_IP: String?,
	val PRIMARY_HOST_PORT: String?,
	val PRIMARY_HOST_SSL: String?,
	val SECONDARY_HOST_IP: String?,
	val SECONDARY_HOST_PORT: String?,
	val SECONDARY_HOST_SSL: String?,
	val TPDU: String?,
	val NII: String?,
	val HOST_TIMEOUTMS: String?,
	val LAST_SETTLEMENT_BATCH_NO: String?,
	val LAST_STAN: String?,
	val LAST_INVOICE_NO: String?,
	var IS_TPA_ACCOUNT: Boolean?
)

data class EWalletMerchantScan(
	val ACQ_CODE: String?,
	val ACQ_MID: String?,
	val ACQ_TID: String?
)

data class BNPL(
	val PRODUCT_LIST: List<ProductList>?
)

data class GenerateQR(
	val PRODUCT_LIST: List<ProductList>?
)

data class ProductList(
	val ACQ_CODE: String?,
	val QR_PRODUCT_CODE: String?,
	val PRODUCT_NAME: String?,
	val ACQ_MID: String?,
	val ACQ_TID: String?
)

data class EppAcqConObjectModel(
	val ACQ_LIST: List<EppAcqObjectModel>?
)

data class EppAcqObjectModel(
	val ACQ_CODE: String?,
	val ACQ_DESC: String?,
	val ACQ_SETTING: List<EppSettingsListObjectModel>?
)

data class EppSettingsListObjectModel(
	val Acq_MID: String?,
	val Acq_TID: String?,
	val PAYMENT_PRODUCT_ID: String?,
	val TENURE: String?,
	val TENURE_CODE: String?,
	val LAST_SETTLEMENT_BATCH_NO: String?,
	val LAST_STAN: String?,
	val LAST_INVOICE_NO: String?,
	val IS_TPA_ACCOUNT: Boolean?
)

data class MarketingObject(
	var DisplayContestQRValidity: String?
)

data class AdvertisementObject(
	val ImgUrl: String?,
	val DateFrom: String?,
	val DateTo: String?
)

data class AcquirerLogoReplaceObject(
	val IS_REPLACE_ACQ_LOGO: Boolean?,
	val IS_SHOW_BOTTOM_ACQ_LOGO: Boolean?,
	val LOGO_URL: String?,
	val LOGO_BW_URL: String?
)

data class QuickActionObject(
	val Action_1: String?,
	val Action_2: String?,
	val Action_3: String?,
	val Action_4: String?
)

data class SupportObject(
	val Terminal_SupportPhoneNo: String?,
	val Terminal_SupportURL: String?,
	val Terminal_SupportEmail: String?
)