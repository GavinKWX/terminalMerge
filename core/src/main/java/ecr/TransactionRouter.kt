package ecr

import enums.EnumResponseCode

/** Maps a [TxnRequest] to a screen or an immediate answer. Moved from Pro's intent_helper (item 98). */
class TransactionRouter(
    private val host: NewIntegrationHost = CurrentNewIntegrationHost,
    private val saleUseCase: SaleUseCase = SaleUseCase(host),
    private val voidUseCase: VoidUseCase = VoidUseCase(host),
    private val settlementUseCase: SettlementUseCase = SettlementUseCase(),
    private val preauthUseCase: PreAuthUseCase = PreAuthUseCase(host),
    private val enquiryUseCase: EnquiryUseCase = EnquiryUseCase(host),
) {
    fun route(req: TxnRequest): Route {
        if (host.autoSettlementIsRunning()) {
            return Route.Return(req.raw.apply {
                put(TxnKeys.RESP_CODE, EnumResponseCode.AUTO_SETTLEMENT_RUNNING.code)
                put(TxnKeys.RESP_DESC, EnumResponseCode.AUTO_SETTLEMENT_RUNNING.description)
            })
        }

        if (req.oldIntegration) {
            return when (req.txnType) {
                1 -> {
                    req.channel = PaymentChannel.CARD
                    saleUseCase.buildRoute(req)
                }
                2 -> {
                    req.channel = PaymentChannel.CARD
                    voidUseCase.buildRoute(req)
                }
                3 -> {
                    req.channel = PaymentChannel.CARD
                    settlementUseCase.buildRoute(req)
                }
                4 -> {
                    req.preAuthType = PreAuthType.PREAUTH
                    preauthUseCase.buildRoute(req)
                }
                5 -> {
                    req.preAuthType = PreAuthType.PREAUTHCOMPLETE
                    preauthUseCase.buildRoute(req)
                }
                6 -> {
                    req.preAuthType = PreAuthType.VOIDPREAUTH
                    preauthUseCase.buildRoute(req)
                }
                7 -> {
                    //GENERATE QR
                    if (!req.productCode.isNullOrEmpty()) {
                        req.channel = PaymentChannel.QR
                        req.paymentCode = req.productCode
                    } else {
                        req.channel = PaymentChannel.SCAN
                    }
                    saleUseCase.buildRoute(req)
                }
                8 -> {
                    req.invoice = req.transRefId
                    req.channel = PaymentChannel.QR
                    voidUseCase.buildRoute(req)
                }
                9 -> {
                    req.channel = PaymentChannel.QR
                    settlementUseCase.buildRoute(req)
                }
                10 -> {
                    req.preAuthType = PreAuthType.VOIDPREAUTHCOMPLETE
                    preauthUseCase.buildRoute(req)
                }
                11 -> {
                    req.channel = PaymentChannel.MOTO
                    saleUseCase.buildRoute(req)
                }
                12 -> {
                    req.channel = PaymentChannel.EPP
                    saleUseCase.buildRoute(req)
                }
                13 -> enquiryUseCase.buildRoute(req)
                0 -> noSession(req)
                else -> invalidType(req)
            }
        } else {
            return when (req.txnType) {
                1 -> enquiryUseCase.buildRoute(req)
                2 -> saleUseCase.buildRoute(req)
                3 -> voidUseCase.buildRoute(req)
                4 -> settlementUseCase.buildRoute(req)
                5 -> preauthUseCase.buildRoute(req)
                0 -> noSession(req)
                else -> invalidType(req)
            }
        }
    }

    private fun noSession(req: TxnRequest) = Route.Return(req.raw.apply {
        put(TxnKeys.RESP_CODE, "00")
        put(TxnKeys.RESP_DESC, "No Session Running")
    })

    private fun invalidType(req: TxnRequest) = Route.Return(req.raw.apply {
        put(TxnKeys.RESP_CODE, EnumResponseCode.INVALID_TRANSACTION_TYPE.code)
        put(TxnKeys.RESP_DESC, EnumResponseCode.INVALID_TRANSACTION_TYPE.description)
    })
}
