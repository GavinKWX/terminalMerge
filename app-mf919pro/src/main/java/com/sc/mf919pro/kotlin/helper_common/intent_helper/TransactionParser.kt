package com.sc.mf919pro.kotlin.helper_common.intent_helper

import helpers.IntegrationMode

import android.content.Intent
import androidx.core.text.isDigitsOnly
import com.google.gson.Gson
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import java.math.BigDecimal

class TransactionParser(private val gson: Gson = Gson()) {
    fun parse(intent: Intent): Result<TxnRequest> {
        val map = extractMap(intent) ?: return Result.failure(
            IllegalArgumentException("Missing txn payload")
        )
        Utils.printLog("TransactionParser :: $map")
        //println("TransactionParser :: $map")
        val pkg = map[TxnKeys.PACKAGE_NAME].orEmpty()
        val act = map[TxnKeys.ACTIVITY_NAME].orEmpty()

        if (pkg.isBlank() || act.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing return target"))
        }

        val txnType = map[TxnKeys.TXN_TYPE]?.toIntOrNull() ?: -1

        val amountString = map[TxnKeys.AMOUNT]?.trim()

        // Every intent extra is a String, so HTTP's "the amount arrived quoted" signal maps here
        // to "the amount is not digits-only" -- "10.00" is the old decimal-ringgit form, 1000 is
        // new-integration cents.
        val amountInOldFormat = !amountString.isNullOrBlank() && !amountString.isDigitsOnly()

        // Same rule as HTTP -- see helpers.IntegrationMode. Only boolean true or the string
        // "true" selects old integration; "1", "yes" and a bare present-but-false value do not.
        // Intent extras arrive as HashMap<String, String>, so the flag is always a string here.
        val isOldIntegration = IntegrationMode.isOld(
            flagIsTrue = IntegrationMode.flagIsTrue(map[TxnKeys.OLD_INTEGRATION]),
            amountInOldFormat = amountInOldFormat
        )

        val amount: Long? = if (amountString.isNullOrBlank()) {
            null
        } else if (isOldIntegration) {
            // Old integration: RM → cents. Previously an old-format amount reached here with the
            // mode flipped but the value dropped to null, which SaleUseCase then rejected as
            // "Trade amount should be greater than 0".
            amountString.toBigDecimalOrNull()
                ?.multiply(BigDecimal(100))
                ?.toLong()
        } else {
            amountString.toLongOrNull()
        }
        var channel = map[TxnKeys.CHANNEL]
        if(channel.isNullOrEmpty()) {
            channel = map[TxnKeys.SETTLEMENT_TYPE]
        }

        if(map.containsKey("AcknowledgeCountdown")) {
            val countDownSecond = map["AcknowledgeCountdown"] ?: ServiceHolder.defaultAckCountdownSecond.toString()
            ServiceHolder.ackCountDownSecond = Utils.atoi(countDownSecond)
        } else {
            ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond
        }

        return Result.success(
            TxnRequest(
                returnPackage = pkg,
                returnActivity = act,
                oldIntegration = isOldIntegration,
                txnType = txnType,
                //channel = map[TxnKeys.CHANNEL]?.let { PaymentChannel.valueOf(it.uppercase()) },
                channel = channel?.let { PaymentChannel.valueOf(it.uppercase()) },
                amount = amount,
                amountString = amountString,
                posReference = map[TxnKeys.POS_REF],
                orderingItem = map[TxnKeys.ORDERING_ITEM],
                orderingItemImage = map[TxnKeys.ORDERING_ITEM_IMG],

                paymentCode = map[TxnKeys.PAYMENT_CODE],
                productCode = map[TxnKeys.PRODUCT_CODE],
                cameraFacing = map[TxnKeys.CAMERA_FACING]?.toIntOrNull() ?: 0,

                invoice = map[TxnKeys.TXN_INVOICE],
                transRefId = map[TxnKeys.TXN_REF_ID],
                forceVoid = map["ForceVoid"]?.toIntOrNull() ?: 0,
                preAuthType = map[TxnKeys.PREAUTH_TYPE]?.let { PreAuthType.valueOf(it.uppercase()) },
                approvalCode = map[TxnKeys.TXN_APPROVAL_CODE],
                cardNo = map[TxnKeys.CARD_NO],
                expiryDt = map[TxnKeys.EXPIRY_DT],
                rrn = map[TxnKeys.TXN_RRN],
                raw = map
            )
        )
    }

    private fun extractMap(intent: Intent): HashMap<String, String>? {
        // Preferred: JSON
        intent.getStringExtra(TxnKeys.EXTRA_TXN_JSON)?.let { json ->
            return try {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(json, object : com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.type)
            } catch (_: Exception) { null }
        }

        // Legacy: Serializable
        @Suppress("UNCHECKED_CAST")
        return intent.getSerializableExtra(TxnKeys.EXTRA_TXN_MAP) as? HashMap<String, String>
    }
}