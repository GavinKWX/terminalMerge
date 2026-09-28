package ecr

import enums.EnumResponseCode

class VoidUseCase(private val host: NewIntegrationHost = CurrentNewIntegrationHost) {
    fun buildRoute(req: TxnRequest): Route {
        val txn = req.raw

        val transInvoice = req.invoice ?: ""
        if (transInvoice.isEmpty()) {
            txn[TxnKeys.RESP_CODE] = "SHC001"
            txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
            return Route.Return(txn)
        }
        return try {
            when (req.channel) {
                PaymentChannel.CARD, PaymentChannel.EPP -> {
                    val row = host.activeProduct(Products.CARD_SETTINGS) ?: throw Exception()
                    host.setSaleModel(row, null, Products.SALES_TYPE_CARD)
                    Route.Navigate(
                        Destination.VOID_SALE,
                        mapOf(
                            "Invoice" to transInvoice,
                            "forceVoid" to req.forceVoid,
                            "posReference" to req.posReference
                        )
                    )
                }
                PaymentChannel.QR -> {
                    val scan = host.activeProduct(Products.EWALLET_MERCHANT_SCANS)
                    val genQr = host.activeProduct(Products.GENERATE_QR)
                    if (scan == null && genQr == null) {
                        throw Exception()
                    }
                    Route.Navigate(
                        Destination.VOID_QR,
                        mapOf(
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
