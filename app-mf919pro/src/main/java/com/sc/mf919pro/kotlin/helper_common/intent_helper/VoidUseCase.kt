package com.sc.mf919pro.kotlin.helper_common.intent_helper
import enums.EnumResponseCode

import android.content.Context
import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class VoidUseCase {
    fun buildRoute(ctx: Context, req: TxnRequest): Route {
        val txn = req.raw

        val transInvoice = req.invoice ?: ""
        if(transInvoice.isEmpty()) {
            txn[TxnKeys.RESP_CODE] = "SHC001"
            txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
            return Route.Return(txn)
        }
        return try {
            when (req.channel) {
                PaymentChannel.CARD, PaymentChannel.EPP -> {
                    val cardProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    if(cardProductModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(cardProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew
                    Route.Navigate(
                        actionId = R.id.voidSaleFragment,
                        bundle = bundleOf(
                            "Invoice" to transInvoice,
                            "forceVoid" to req.forceVoid,
                            "posReference" to req.posReference
                        )
                    )
                }
                PaymentChannel.QR -> {
                    val qrScanModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, "true"))
                    val genQrModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, "true"))
                    if(qrScanModel == null && genQrModel == null) {
                        throw Exception()
                    }
                    Route.Navigate(
                        actionId = R.id.voidQrFragment,
                        bundle = bundleOf(
                            "Invoice" to transInvoice,
                            "forceVoid" to req.forceVoid,
                            "posReference" to req.posReference
                        )
                    )
                }
                else -> Route.Return(txn.apply {
                    put(TxnKeys.RESP_CODE, "SHC001")
                    put(TxnKeys.RESP_DESC, "Invalid Parameter - (PaymentChannel)")
                })
            }
        } catch (_: Exception) {
            Route.Return(txn.apply {
                put(TxnKeys.RESP_CODE, EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
                put(TxnKeys.RESP_DESC, EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
            })
        }
    }
}