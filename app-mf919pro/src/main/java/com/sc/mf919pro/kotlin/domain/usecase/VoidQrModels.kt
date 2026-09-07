package com.sc.mf919pro.kotlin.domain.usecase

import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet
import helpers.HelperLog

data class VoidQrLookupData(
    val transaction: DbModelTransactionQrGet,
    val amountDisplay: String,
    val productName: String,
    val hostRefNo: String,
    val refId: String,
)

sealed class VoidQrLookupResult {
    data class Found(val data: VoidQrLookupData) : VoidQrLookupResult()
    data class NotFound(val code: String, val message: String) : VoidQrLookupResult()
    data class Error(val code: String, val message: String, val cause: Throwable? = null) : VoidQrLookupResult()
}

data class VoidQrExecutionRequest(
    val lookupData: VoidQrLookupData,
    val posReference: String?,
    val terminalPw: String,
    val helperLog: HelperLog,
    val transData: TransData,
)

sealed class VoidQrExecutionResult {
    data class Success(
        val respCode: String,
        val respDesc: String,
        val qrTxnRefNo: String,
        val qrRefId: String,
        val isTpaAccount: Boolean,
    ) : VoidQrExecutionResult()

    data class Error(
        val respCode: String,
        val respDesc: String,
        val qrTxnRefNo: String,
        val qrRefId: String,
        val isTpaAccount: Boolean,
        val cause: Throwable? = null,
    ) : VoidQrExecutionResult()
}