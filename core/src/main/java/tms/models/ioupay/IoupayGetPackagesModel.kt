package tms.models.ioupay

data class IoupayGetPackagesRequestModel(
	var QR_MID: String?,
	var QR_TID: String?,
	var DEV_SN: String?,
)

data class IoupayGetPackagesResponseModel(
	var RESP_CODE: String?,
	var RESP_DESC: String?,
	var PACKAGE_LIST: List<IoupayTenure>
)

data class IoupayTenure(
	var PAYMENT_TYPE: String?,
	var PACKAGE_CODE: String?,
	var TENURE: Int?,
	var DESCRIPTION: String?,
)