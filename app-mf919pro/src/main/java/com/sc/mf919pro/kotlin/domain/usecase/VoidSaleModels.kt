package com.sc.mf919pro.kotlin.domain.usecase

import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelBatchTable
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import helpers.HelperLog

data class VoidSaleLookupData(
    val batchTableModel: DbModelBatchTable,
    val amountHex: String,
    val amountDisplay: String,
    val cardPan: String,
    val maskedCardPan: String,
    val approvalCode: String,
    val invoiceNo: String,
    val rrn: String,
)

sealed class VoidSaleLookupResult {
    data class Found(val data: VoidSaleLookupData) : VoidSaleLookupResult()
    data class NotFound(val code: String, val message: String) : VoidSaleLookupResult()
    data class Unsupported(val code: String, val message: String) : VoidSaleLookupResult()
    data class Error(val code: String, val message: String, val cause: Throwable? = null) : VoidSaleLookupResult()
}

data class VoidSaleExecutionRequest(
    val lookupData: VoidSaleLookupData,
    val posReference: String?,
    val helperLog: HelperLog,
    val saleModel: SaleModelNew?,
    val merchantConfig: DbModelMerchantConfig?,
    val onProgressMessage: suspend (String?) -> Unit = {},
    val onProgressTitle: suspend (String?) -> Unit = {},
)

sealed class VoidSaleExecutionResult {
    data class Success(
        val transResult: String,
        val respCodeHex: String,
        val respCodeAscii: String,
        val txnTypeLabel: String,
        val invoiceNo: String,
        val stan: String,
    ) : VoidSaleExecutionResult()

    data class Error(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : VoidSaleExecutionResult()
}