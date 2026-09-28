package ecr

class SettlementUseCase {
    fun buildRoute(req: TxnRequest): Route {
        val txn = req.raw

        var settlementType = req.channel
        if (settlementType == PaymentChannel.EPP) {
            settlementType = PaymentChannel.CARD
        }
        return when (settlementType) {
            PaymentChannel.ALL, PaymentChannel.CARD, PaymentChannel.QR -> {
                Route.Navigate(
                    Destination.SETTLE_OPTION,
                    mapOf("settlementType" to settlementType.name)
                )
            }
            else -> Route.Return(txn.apply {
                put(TxnKeys.RESP_CODE, "SHC001")
                put(TxnKeys.RESP_DESC, "Invalid Parameter - (PaymentChannel)")
            })
        }
    }
}
