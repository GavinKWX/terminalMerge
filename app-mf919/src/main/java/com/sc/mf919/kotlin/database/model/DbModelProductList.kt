package com.sc.mf919.kotlin.database.model

data class DbModelProductList(
        var Product: String,
        var AcqCode: String,
        var AcqMid: String,
        var AcqTid: String,
        var QrProductCode: String,
        var ProductName: String,
        var EppProductCode: String,
        var EppTenure: String,
        var EppTenureCode: String,
        var IsSettlement: String,
        var IsSettled: String,
        var BatchNo: String,
        var IsActive: String,
        var Ksn: String,
        var PinKsn: String,
        var IsTpaAccount: String
)

data class DbModelProductListGet(
        var Id: String,
        var Product: String,
        var AcqCode: String,
        var AcqMid: String,
        var AcqTid: String,
        var QrProductCode: String,
        var ProductName: String,
        var EppProductCode: String,
        var EppTenure: String,
        var EppTenureCode: String,
        var IsSettlement: String,
        var IsSettled: String,
        var BatchNo: String,
        var IsActive: String,
        var Ksn: String,
        var PinKsn: String,
        var IsTpaAccount: String
)



