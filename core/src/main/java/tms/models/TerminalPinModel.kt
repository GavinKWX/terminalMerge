package tms.models

data class TerminalPINRequestModel(
	var SEQ_NO: String?,
	var TXN_DT: String?,
	var DEV_SN: String?,
	var ENC_PIN: String?
)

data class TerminalPINResponseModel(
	var SEQ_NO: String?,
	var RESP_CODE: String?,
	var RESP_DESC: String?,
	var RESULT: Boolean
)