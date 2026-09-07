package com.sc.mf919.kotlin.database.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class DbModelPrintReceiptInsert(
    val postingDt: String,
    val txnDt: String,
    val txnType: String,
    val cardMasked: String,
    val schemeId: String,
    val txnAmt: String,
    val invoiceNo: String,
    val stan: String,
    val txnApprCode: String,
    val receiptInfo: String,
    val isTpaAccount: String,
    val respCode: String
)

@Parcelize
data class DbModelPrintReceipt(
    val id: Int,
    val postingDt: String,
    val txnDt: String,
    val txnType: String,
    val cardMasked: String,
    val schemeId: String,
    val txnAmt: String,
    val invoiceNo: String,
    val stan: String,
    val txnApprCode: String,
    val receiptInfo: String,
    val isTpaAccount: String,
    val respCode: String
) : Parcelable