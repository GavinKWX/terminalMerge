package tms.models

data class InjectionKeyRequestModel(
	val SEQ_NO: String?,
	val TXN_DT: String?,
	val DEV_SN: String?,
	val DEV_MODEL: String?,
	val DEV_APPNAME: String?,
	val DEV_PROJECT: String?,
	val DEV_LOCATION: String?,
	val ADD_INFO: String?,
)

data class InjectionKeyResponseModel(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val TMK: String?,
	val TAK: String?,
	val TMKId: String?,
	val MC_VER: String?,
	val KEY: InjectionKeyModelKeyModel?,
)

data class InjectionKeyModelKeyModel(
	val ACQ_CODE: String?,
	val GOBIZ_KEY: InjectionKeyModelKeyAcquirerModel?,
	val PAYDEE_KEY: InjectionKeyModelKeyAcquirerModel?,
	val BSN_KEY: List<BsnInjectionKeyModel>?,
	val BSN_CARDZONE_KEY: BsnCardZoneInjectionKeyModel?,
	val PAYEX_GOBIZ_KEY: InjectionKeyModelKeyAcquirerModel?,
	val FINEXUS_KEY: InjectionKeyModelKeyAcquirerModel?
)

data class InjectionKeyModelKeyAcquirerModel(
	val TMK: String,
	val TAK: String,
	val TMKId: String,
)

data class BsnInjectionKeyModel(
	val ProductCode: String,
	val BSN_MID: String,
	val BSN_TID: String,
	val TLE: String,
	val MEK: String,
	val PIN: String,
	val KSN: String,
	val PIN_KSN: String,
	val ISO_62: String,
)

data class BsnCardZoneInjectionKeyModel(
	val TMK_Key_Left: String,
	val TMK_Key_Right: String,
)