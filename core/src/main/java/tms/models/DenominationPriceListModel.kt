package tms.models

data class DenominationPriceListRequestModel(
    var SEQ_NO: String?,
    var TXN_DT: String?,
    var DEV_SN: String?,
    var SC_MID: String?,
    var SC_TID: String?,
)

data class DenominationPriceListResponseModel(
    var RESP_CODE: String?,
    var RESP_DESC: String?,
    val PACKAGE_LIST: ArrayList<DenominationPackageList>?,
    var REMARK: String?,
    var MAINTENANCE_SCHEDULE: ArrayList<Any>?,
    var DISPLAY_MENU_TYPE: String?,
)

data class DenominationPackageList(
    var PACKAGE_ID: String?,
    var DESCRIPTION: String?,
    var AMOUNT: String?,
    var REF_1: String?,
    var REF_2: String?,
    var REF_3: String?,
    var REF_4: String?,
    var REF_5: String?
)

data class WsDenominationPackageModel(
    val RowGuid: String?,
    val Packages: PackageDetail?
)

data class PackageDetail(
    val RowGuid: String?,
    val Terminal_DM_RowGuid: String?,
    val DeviceSerialNo: String?,
    val Description: String?,
    val Amount: String?,
    val Ref_1: String?,
    val Ref_2: String?,
    val Ref_3: String?,
    val Ref_4: String?,
    val Ref_5: String?
)