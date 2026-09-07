package tms.models

data class FoodLinkVerificationRequestModel(
    var correlationRef: String?,
    var systemMID: String?,
    var systemTID: String?,
    var phoneCountryCode: String?,
    var phoneNo: String?,
    var txnAmt: String?
)

data class FoodLinkVerificationResponseModel(
    var msg: String?,
    var data: ResponseData?,
)

data class ResponseData(
    val correlationRef: String?,
    val fees: List<ResponseDataFees>?,
    val totalFeeAmount: String?
)

data class ResponseDataFees(
    val vendor: String?,
    val vasRefNo: String?,
    val feeAmount: String?
)
