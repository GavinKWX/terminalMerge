package com.sc.mf919pro.kotlin.database.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class DbModelTransactionQr(
    val txnType: String,
    val txnDateTime: String,
    var voidDateTime: String,
    val txnAmount: String,
    val payChannel: String,
    val payBrand: String,
    val productCode: String,
    var productName: String,
    val mid: String,
    val tid: String,
    val acqCode: String,
    val refId: String,
    var hostRefNo: String,
    val txnRefNo: String,
    val approvalCode: String,
    val respCode: String,
    val respDesc: String,
    val isUnionPayTxn: String,
    val upiVoucherCode: String,
    val upiDiscountAmt: String,
    val upiMarkupFee: String,
    val posRefNo: String,
    var isTpaAccount: String
)

@Parcelize
data class DbModelTransactionQrGet(
    val id: Int?,
    val txnType: String?,
    val txnDateTime: String?,
    var voidDateTime: String?,
    val txnAmount: String?,
    val payChannel: String?,
    val payBrand: String?,
    val productCode: String?,
    var productName: String?,
    val mid: String?,
    val tid: String?,
    val acqCode: String?,
    val refId: String?,
    var hostRefNo: String?,
    val txnRefNo: String?,
    val approvalCode: String?,
    val respCode: String?,
    val respDesc: String?,
    val isUnionPayTxn: String,
    val upiVoucherCode: String,
    val upiDiscountAmt: String,
    val upiMarkupFee: String,
    val posRefNo: String,
    var isTpaAccount: String
) : Parcelable

data class DbModelQrPayBrandGet(
    var payBrand: String,
    var productCode: String,
    var productName: String
)