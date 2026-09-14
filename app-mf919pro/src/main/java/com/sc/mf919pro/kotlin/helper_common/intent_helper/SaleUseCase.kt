package com.sc.mf919pro.kotlin.helper_common.intent_helper
import enums.EnumResponseCode

import android.content.Context
import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer.TAG
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig

class SaleUseCase {
    fun buildRoute(ctx: Context, req: TxnRequest): Route {
        val txn = req.raw
        val amount = req.amount ?: 0
        if (amount <= 0) {
            txn[TxnKeys.RESP_CODE] = EnumResponseCode.AMOUNT_NOT_POSITIVE.code
            txn[TxnKeys.RESP_DESC] = EnumResponseCode.AMOUNT_NOT_POSITIVE.description
            return Route.Return(txn)
        }
        if (amount > 999_999_999) {
            txn[TxnKeys.RESP_CODE] = EnumResponseCode.AMOUNT_TOO_LARGE.code
            txn[TxnKeys.RESP_DESC] = EnumResponseCode.AMOUNT_TOO_LARGE.description
            return Route.Return(txn)
        }

        val terminalConfig = getTerminalConfig()
        val qrList = listOf(PaymentChannel.SCAN, PaymentChannel.QR)
        var isCard = false
        val isEnableSales = if (req.channel in qrList) {
            DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_EWALLET")
        } else {
            isCard = true
            DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")
        }
        if(!isEnableSales) {
            txn[TxnKeys.RESP_CODE] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code
            txn[TxnKeys.RESP_DESC] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description
            return Route.Return(txn)
        }
        if(isCard) {
            if(DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT") ||
                DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                if (ServiceHolder.clearSettlementBatch) {
                    txn[TxnKeys.RESP_CODE] = EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code
                    txn[TxnKeys.RESP_DESC] = EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description
                    return Route.Return(txn)
                }
            }
        }

        return try {
            when (req.channel) {
                PaymentChannel.CARD -> {
                    val cardProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    if(cardProductModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(cardProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.TransAmount = req.amount ?: 0
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew
                    Route.Navigate(
                        actionId = R.id.cardPaymentFragment,
                        bundle = bundleOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.SCAN -> {
                    val qrScanModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, "true"))
                    if(qrScanModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(qrScanModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.TransAmount = req.amount ?: 0
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew
                    Route.Navigate(
                        actionId = R.id.scanQrFragment,
                        bundle = bundleOf("posReference" to req.posReference, "cameraFacing" to req.cameraFacing, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.QR -> {
                    val paymentCode = req.paymentCode ?: ""
                    val generateQrModel = ProductListRepo.getSingle(ctx, listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, paymentCode, "true"))
                    if(generateQrModel == null) {
                        throw Exception()
                    }
                    val jsonProductList = Gson().toJson(generateQrModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.TransAmount = req.amount ?: 0
                    saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                    ServiceHolder.saleModelCache = saleModelNew
                    Route.Navigate(
                        actionId = R.id.generateQrFragment,
                        bundle = bundleOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.EPP -> {
                    val eppAcquirerList = ProductListRepo.getDistinctEppAcquirer(ctx)
                    if(eppAcquirerList.isEmpty()) {
                        throw Exception()
                    }
                    Route.Navigate(
                        actionId = R.id.eppAcquirerFragment,
                        bundle = bundleOf("txnAmt" to (req.amount ?: 0), "posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.MOTO -> {
                    val cardNumber = req.cardNo ?: ""
                    if(cardNumber.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (CardNumber)"
                        return Route.Return(txn)
                    }
                    val expiryDate = req.expiryDt ?: ""
                    if(expiryDate.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (ExpiryDate)"
                        return Route.Return(txn)
                    }

                    var motoProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.MOTO.name, "true"))
                    if(motoProductModel == null) {
                        Utils.debugLogPrint(TAG, "MOTO merged with CARD")
                        motoProductModel = ProductListRepo.getSingle(ctx, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                    }
                    if(motoProductModel == null) {
                        throw Exception()
                    }
                    Route.Navigate(
                        actionId = R.id.keypadMotoFragment,
                        bundle = bundleOf(
                            "txnAmt" to (req.amount ?: 0),
                            "cardNumber" to cardNumber,
                            "expDate" to expiryDate,
                            "posReference" to req.posReference,
                            "orderingItem" to req.orderingItem,
                            "orderingItemImage" to req.orderingItemImage
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