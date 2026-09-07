package com.sc.mf919.kotlin.data_enum

import com.sc.mf919.kotlin.database.model.DbModelDenominationList

data class SaleModelNew(
    var SalesType: Int,
    var Product: String?,
    var AcqCode: String?,
    var AcqMid: String?,
    var AcqTid: String?,
    var QrProductCode: String?,
    var ProductName: String?,
    var EppProductCode: String?,
    var EppTenure: String?,
    var EppTenureCode: String?,
    var IsSettled: String?,
    var BatchNo: String?,
    var Ksn: String?,
    var PinKsn: String?,
    var IsTpaAccount: String?,
    var DenominationType: String?,
    var DenominationProduct: DbModelDenominationList?
)