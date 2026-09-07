package tms.models

data class PaperRollRequestModel(
    var DEV_SN: String?,
    var ACTION: String?
)

data class PaperRollResponseModel(
    var RESP_CODE: String?,
    var RESP_DESC: String?,
)