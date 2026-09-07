package tms.models

data class DeviceInfoReq(
	val SEQ_NO: String?,
	val TXN_DT: String?,
	val DEV_SN: String?,
	val DEV_MODEL: String?,
	val DEV_APPNAME: String,
	val DEV_PROJECT: String?,
	val DEV_LOCATION: String?,
	val DEV_LANE_ID: String?,
	val FIRM_ID: String?,
	val FIRM_VER: String?,
	val MC_VER: String?,
	val TEST_CASE: List<DeviceTestCaseList>,
	val ADD_INFO: String?
)

data class DeviceTestCaseList(
	val TEST_NAME: String?,
	val TEST_RESULT: Any
)

/*<----------------------------------------------------------------->*/
data class DeviceInfoResp(
	val SEQ_NO: String?,
	val RESP_CODE: String?,
	val RESP_DESC: String?,
	val RESP_ID: String?,
	val FIRM_ID: String?,
	val TASK_NAME: List<String>,
	val FIRM_URL: String?,
	val FIRM_FILENAME: String?
)
