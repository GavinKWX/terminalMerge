package com.sc.mf919pro.kotlin.helper_common.intent_helper
import enums.EnumResponseCode

import android.content.Context
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

sealed class Route {
    data class Navigate(
        val actionId: Int,
        val bundle: android.os.Bundle? = null,
        val navOptions: androidx.navigation.NavOptions? = null
    ) : Route()
    data class Return(val resultMap: HashMap<String, String>) : Route()
}

class TransactionRouter(
    private val saleUseCase: SaleUseCase,
    private val voidUseCase: VoidUseCase,
    private val settlementUseCase: SettlementUseCase,
    private val preauthUseCase: PreAuthUseCase,
    private val enquiryUseCase: EnquiryUseCase
) {
    fun route(ctx: Context, req: TxnRequest): Route {
        if(ServiceHolder.autoSettlementIsRunning) {
            return Route.Return(req.raw.apply {
                put(TxnKeys.RESP_CODE, EnumResponseCode.AUTO_SETTLEMENT_RUNNING.code)
                put(TxnKeys.RESP_DESC, EnumResponseCode.AUTO_SETTLEMENT_RUNNING.description)
            })
        }

        if(req.oldIntegration) {
            return when (req.txnType) {
                1 -> {
                    req.channel = PaymentChannel.CARD
                    saleUseCase.buildRoute(ctx, req)
                }
                2 -> {
                    req.channel = PaymentChannel.CARD
                    voidUseCase.buildRoute(ctx, req)
                }
                3 -> {
                    req.channel = PaymentChannel.CARD
                    settlementUseCase.buildRoute(ctx, req)
                }
                4 -> {
                    req.preAuthType = PreAuthType.PREAUTH
                    preauthUseCase.buildRoute(ctx, req)
                }
                5 -> {
                    req.preAuthType = PreAuthType.PREAUTHCOMPLETE
                    preauthUseCase.buildRoute(ctx, req)
                }
                6 -> {
                    req.preAuthType = PreAuthType.VOIDPREAUTH
                    preauthUseCase.buildRoute(ctx, req)
                }
                7 -> {
                    //GENERATE QR
                    if(!req.productCode.isNullOrEmpty()) {
                        req.channel = PaymentChannel.QR
                        req.paymentCode = req.productCode
                    } else {
                        req.channel = PaymentChannel.SCAN
                    }
                    saleUseCase.buildRoute(ctx, req)
                }
                8 -> {
                    req.invoice = req.transRefId
                    req.channel = PaymentChannel.QR
                    voidUseCase.buildRoute(ctx, req)
                }
                9 -> {
                    req.channel = PaymentChannel.QR
                    settlementUseCase.buildRoute(ctx, req)
                }
                10 -> {
                    req.preAuthType = PreAuthType.VOIDPREAUTHCOMPLETE
                    preauthUseCase.buildRoute(ctx, req)
                }
                11 -> {
                    req.channel = PaymentChannel.MOTO
                    saleUseCase.buildRoute(ctx, req)
                }
                12 -> {
                    req.channel = PaymentChannel.EPP
                    saleUseCase.buildRoute(ctx, req)
                }
                13 -> enquiryUseCase.buildRoute(ctx, req)
                0 -> Route.Return(req.raw.apply {
                    put(TxnKeys.RESP_CODE, "00")
                    put(TxnKeys.RESP_DESC, "No Session Running")
                })
                else -> Route.Return(req.raw.apply {
                    // Was E99 / "TODO" -- a placeholder that shipped. An unmapped type is the
                    // same condition the new-integration branch below already answers, so it
                    // gets the same response rather than a code no vendor can interpret.
                    put(TxnKeys.RESP_CODE, EnumResponseCode.INVALID_TRANSACTION_TYPE.code)
                    put(TxnKeys.RESP_DESC, EnumResponseCode.INVALID_TRANSACTION_TYPE.description)
                })
            }
        } else {
            return when (req.txnType) {
                1 -> enquiryUseCase.buildRoute(ctx, req)
                2 -> saleUseCase.buildRoute(ctx, req)
                3 -> voidUseCase.buildRoute(ctx, req)
                4 -> settlementUseCase.buildRoute(ctx, req)
                5 -> preauthUseCase.buildRoute(ctx, req)
                0 -> Route.Return(req.raw.apply {
                    put(TxnKeys.RESP_CODE, "00")
                    put(TxnKeys.RESP_DESC, "No Session Running")
                })
                else -> Route.Return(req.raw.apply {
                    put(TxnKeys.RESP_CODE, EnumResponseCode.INVALID_TRANSACTION_TYPE.code)
                    put(TxnKeys.RESP_DESC, EnumResponseCode.INVALID_TRANSACTION_TYPE.description)
                })
            }
        }
    }
}