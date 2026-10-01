package ecr

import enums.EnumResponseCode

class PreAuthUseCase(private val host: NewIntegrationHost = CurrentNewIntegrationHost) {
    fun buildRoute(req: TxnRequest): Route {
        val txn = req.raw
        val preAuthType = req.preAuthType

        val checkList = listOf(PreAuthType.PREAUTH, PreAuthType.PREAUTHCOMPLETE)
        if (preAuthType in checkList) {
            if (!host.configFlag("SALES_CARD")) {
                txn[TxnKeys.RESP_CODE] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code
                txn[TxnKeys.RESP_DESC] = EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description
                return Route.Return(txn)
            }
        }

        return try {
            when (preAuthType) {
                PreAuthType.PREAUTH -> {
                    val row = host.activeProduct(Products.CARD_SETTINGS) ?: throw Exception()
                    host.setSaleModel(row, req.amount ?: 0, Products.SALES_TYPE_PREAUTH)
                    Route.Navigate(
                        Destination.PREAUTH,
                        mapOf("posReference" to req.posReference, "orderingItem" to req.orderingItem, "orderingItemImage" to req.orderingItemImage)
                    )
                }
                PreAuthType.VOIDPREAUTH -> {
                    val transInvoice = req.invoice ?: ""
                    if (transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }

                    Route.Navigate(
                        Destination.VOID_PREAUTH,
                        mapOf("Invoice" to transInvoice, "forceVoid" to req.forceVoid, "posReference" to req.posReference)
                    )
                }
                PreAuthType.PREAUTHCOMPLETE -> {
                    val transInvoice = req.invoice ?: ""
                    if (transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }
                    val transApproval = req.approvalCode ?: ""
                    if (transApproval.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionApprovalCode)"
                        return Route.Return(txn)
                    }
                    val transRrn = req.rrn ?: ""
                    if (transRrn.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionRRN)"
                        return Route.Return(txn)
                    }
                    val row = host.activeProduct(Products.CARD_SETTINGS) ?: throw Exception()
                    host.setSaleModel(row, req.amount ?: 0, Products.SALES_TYPE_PREAUTH)

                    Route.Navigate(
                        Destination.SALE_COMPLETION,
                        mapOf(
                            "apprCode" to transApproval,
                            "rrn" to transRrn,
                            "invNo" to transInvoice,
                            "forceVoid" to req.forceVoid,
                            "posReference" to req.posReference,
                            "orderingItem" to req.orderingItem,
                            "orderingItemImage" to req.orderingItemImage
                        )
                    )
                }
                PreAuthType.VOIDPREAUTHCOMPLETE -> {
                    val transInvoice = req.invoice ?: ""
                    if (transInvoice.isEmpty()) {
                        txn[TxnKeys.RESP_CODE] = "SHC001"
                        txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (TransactionInvoice)"
                        return Route.Return(txn)
                    }

                    Route.Navigate(
                        Destination.VOID_SALE_COMPLETION,
                        mapOf("Invoice" to transInvoice, "forceVoid" to req.forceVoid, "posReference" to req.posReference)
                    )
                }
                else -> Route.Return(txn.apply {
                    put(TxnKeys.RESP_CODE, "SHC001")
                    put(TxnKeys.RESP_DESC, "Invalid Parameter - (PreAuthType)")
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
