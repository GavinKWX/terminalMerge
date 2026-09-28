package ecr

import enums.EnumResponseCode

class SaleUseCase(private val host: NewIntegrationHost = CurrentNewIntegrationHost) {
    fun buildRoute(req: TxnRequest): Route {
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

        val qrList = listOf(PaymentChannel.SCAN, PaymentChannel.QR)
        var isCard = false
        val isEnableSales = if (req.channel in qrList) {
            host.configFlag("SALES_EWALLET")
        } else {
            isCard = true
            host.configFlag("SALES_CARD")
        }
        if (!isEnableSales) {
            txn[TxnKeys.RESP_CODE] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code
            txn[TxnKeys.RESP_DESC] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description
            return Route.Return(txn)
        }
        if (isCard) {
            if (host.configFlag("FORCE_SETTLEMENT") || host.configFlag("FORCE_SETTLEMENT_DAILY")) {
                if (host.clearSettlementBatch()) {
                    txn[TxnKeys.RESP_CODE] = EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code
                    txn[TxnKeys.RESP_DESC] = EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description
                    return Route.Return(txn)
                }
            }
        }

        return try {
            when (req.channel) {
                PaymentChannel.CARD -> {
                    val row = host.activeProduct(Products.CARD_SETTINGS) ?: throw Exception()
                    host.setSaleModel(row, req.amount ?: 0, Products.SALES_TYPE_CARD)
                    Route.Navigate(
                        Destination.CARD_SALE,
                        mapOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.SCAN -> {
                    val row = host.activeProduct(Products.EWALLET_MERCHANT_SCANS) ?: throw Exception()
                    host.setSaleModel(row, req.amount ?: 0, Products.SALES_TYPE_EWALLET_SCAN)
                    Route.Navigate(
                        Destination.SCAN_QR,
                        mapOf("posReference" to req.posReference, "cameraFacing" to req.cameraFacing, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.QR -> {
                    val paymentCode = req.paymentCode ?: ""
                    val row = host.activeProduct(Products.GENERATE_QR, paymentCode) ?: throw Exception()
                    host.setSaleModel(row, req.amount ?: 0, Products.SALES_TYPE_GENERATE_QR)
                    Route.Navigate(
                        Destination.GENERATE_QR,
                        mapOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.EPP -> {
                    if (!host.hasEppAcquirer()) {
                        throw Exception()
                    }
                    Route.Navigate(
                        Destination.EPP_ACQUIRER,
                        mapOf("txnAmt" to (req.amount ?: 0), "posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PaymentChannel.MOTO -> {
                    val cardNumber = req.cardNo ?: ""
                    if (cardNumber.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (CardNumber)"
                        return Route.Return(txn)
                    }
                    val expiryDate = req.expiryDt ?: ""
                    if (expiryDate.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (ExpiryDate)"
                        return Route.Return(txn)
                    }

                    var motoRow = host.activeProduct(Products.MOTO)
                    if (motoRow == null) {
                        host.log(TAG, "MOTO merged with CARD")
                        motoRow = host.activeProduct(Products.CARD_SETTINGS)
                    }
                    if (motoRow == null) {
                        throw Exception()
                    }
                    Route.Navigate(
                        Destination.KEYPAD_MOTO,
                        mapOf(
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

    private companion object {
        const val TAG = "HTTPSERVER" // the tag Pro logged this under (HTTPServer.TAG)
    }
}
