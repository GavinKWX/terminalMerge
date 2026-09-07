package com.sc.mf919pro.kotlin.helper_common.intent_helper

import android.content.Context
import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig

class PreAuthUseCase {
    fun buildRoute(ctx: Context, req: TxnRequest): Route {
        val txn = req.raw
        val preAuthType = req.preAuthType

        val terminalConfig = getTerminalConfig()
        val checkList = listOf(PreAuthType.PREAUTH, PreAuthType.PREAUTHCOMPLETE)
        if (preAuthType in checkList) {
            if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                txn[TxnKeys.RESP_CODE] = "SHC010"
                txn[TxnKeys.RESP_DESC] = "Transaction Not Supported"
                return Route.Return(txn)
            }
        }

        return try {
            when (preAuthType) {
                PreAuthType.PREAUTH -> {
                    val cardProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    if(cardProductModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(cardProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.TransAmount = req.amount ?: 0
                    saleModelNew.SalesType = 8
                    ServiceHolder.saleModelCache = saleModelNew
                    Route.Navigate(
                        actionId = R.id.cardPaymentFragment,
                        bundle = bundleOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PreAuthType.VOIDPREAUTH -> {
                    val transInvoice = req.invoice ?: ""
                    if(transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }

                    Route.Navigate(
                        actionId = R.id.voidPreAuthFragment,
                        bundle = bundleOf("Invoice" to transInvoice, "posReference" to req.posReference)
                    )
                }
                PreAuthType.PREAUTHCOMPLETE -> {
                    val transInvoice = req.invoice ?: ""
                    if(transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }
                    val transApproval = req.approvalCode ?: ""
                    if(transApproval.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionApprovalCode)"
                        return Route.Return(txn)
                    }
                    val transRrn = req.rrn ?: ""
                    if(transRrn.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionRRN)"
                        return Route.Return(txn)
                    }
                    val cardProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    if(cardProductModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(cardProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.TransAmount = req.amount ?: 0
                    saleModelNew.SalesType = 8
                    ServiceHolder.saleModelCache = saleModelNew

                    Route.Navigate(
                        actionId = R.id.keypadSaleCompletionFragment,
                        bundle = bundleOf(
                            "apprCode" to transApproval,
                            "rrn" to transRrn,
                            "invNo" to transInvoice,
                            "posReference" to req.posReference,
                            "orderingItem" to req.orderingItem,
                            "orderingItemImage" to req.orderingItemImage
                        )
                    )
                }
                PreAuthType.VOIDPREAUTHCOMPLETE -> {
                    val transInvoice = req.invoice ?: ""
                    if(transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }

                    Route.Navigate(
                        actionId = R.id.voidSaleCompletionFragment,
                        bundle = bundleOf("Invoice" to transInvoice, "posReference" to req.posReference)
                    )
                }
                else -> Route.Return(txn.apply {
                    put(TxnKeys.RESP_CODE, "SHC001")
                    put(TxnKeys.RESP_DESC, "Invalid Parameter - (PreAuthType)")
                })
            }
        } catch (_: Exception) {
            Route.Return(txn.apply {
                put(TxnKeys.RESP_CODE, "SHC007")
                put(TxnKeys.RESP_DESC, "Terminal System Error (Product Is Not Configured)")
            })
        }
    }
}