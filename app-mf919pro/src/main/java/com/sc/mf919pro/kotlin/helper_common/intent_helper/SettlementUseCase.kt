package com.sc.mf919pro.kotlin.helper_common.intent_helper

import android.content.Context
import androidx.core.os.bundleOf
import com.sc.mf919pro.R

class SettlementUseCase {
    fun buildRoute(ctx: Context, req: TxnRequest): Route {
        val txn = req.raw

        var settlementType = req.channel
        /*if(settlementType.name.isEmpty()) {
            txn[TxnKeys.RESP_CODE] = "SHC001"
            txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (PaymentChannel)"
            return Route.Return(txn)
        }*/
        if(settlementType == PaymentChannel.EPP) {
            settlementType = PaymentChannel.CARD
        }
        return try {
            when (settlementType) {
                PaymentChannel.ALL, PaymentChannel.CARD, PaymentChannel.QR -> {
                    Route.Navigate(
                        actionId = R.id.settleOptionFragment,
                        bundle = bundleOf("settlementType" to settlementType.name)
                    )
                }
                else -> Route.Return(txn.apply {
                    put(TxnKeys.RESP_CODE, "SHC001")
                    put(TxnKeys.RESP_DESC, "Invalid Parameter - (PaymentChannel)")
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