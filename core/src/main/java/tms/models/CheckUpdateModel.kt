package tms.models

data class CheckUpdateRequestModel(
    var SEQ_NO: String?,
    var TXN_DT: String?,
    var DEV_SN: String?,
    var DEV_MODEL: String?,
    var DEV_APPNAME: String?,
    var DEV_PROJECT: String?,
    var DEV_LOCATION: String?,
    var DEV_LANE_ID: String?,
    var FIRM_ID: String?,
    var FIRM_VER: String?,
    var TASK_NAME: String?,
    var ADD_INFO: String?,
    var MC_VER: String?
)

data class CheckUpdateResponseModel(
    var SEQ_NO: String?,
    var RESP_CODE: String?,
    var RESP_DESC: String?,
    var RESP_ID: String?,
    var FIRM_ID: String?,
    var TASK_NAME: List<String>,
    var FIRM_URL: String?,
    var FIRM_FILENAME: String?
)