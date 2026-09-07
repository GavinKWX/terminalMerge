package tms.models

data class WriteLogRequestModel(
	var DEV_SN: String?,
	var LOG: String?
)

data class WriteLogResponseModel(
	var RESP_CODE: String?,
	var RESP_DESC: String?,
)