package ecr

import android.content.Intent
import com.google.gson.Gson
import helpers.IntegrationMode
import java.math.BigDecimal

/**
 * Turns an App-to-App intent into a [TxnRequest]. Moved from Pro's intent_helper (item 98).
 * Two changes: an unknown PaymentChannel / PreAuthType becomes null (answered SHC001 by the use
 * case) instead of throwing, and AcknowledgeCountdown is returned for the app to apply.
 */
class TransactionParser(
    private val host: NewIntegrationHost = CurrentNewIntegrationHost,
    private val gson: Gson = Gson(),
) {
    fun parse(intent: Intent): Result<TxnRequest> {
        val map = extractMap(intent) ?: return Result.failure(
            IllegalArgumentException("Missing txn payload")
        )
        return parseMap(map)
    }

    fun parseMap(map: HashMap<String, String>): Result<TxnRequest> {
        host.log(TAG, "TransactionParser :: $map")
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
        val amountInOldFormat = !amountString.isNullOrBlank() && !amountString.all { it.isDigit() }

        // Same rule as HTTP -- see helpers.IntegrationMode.
        val isOldIntegration = IntegrationMode.isOld(
            flagIsTrue = IntegrationMode.flagIsTrue(map[TxnKeys.OLD_INTEGRATION]),
            amountInOldFormat = amountInOldFormat
        )

        val amount: Long? = if (amountString.isNullOrBlank()) {
            null
        } else if (isOldIntegration) {
            // Old integration: RM -> cents.
            amountString.toBigDecimalOrNull()
                ?.multiply(BigDecimal(100))
                ?.toLong()
        } else {
            amountString.toLongOrNull()
        }
        var channel = map[TxnKeys.CHANNEL]
        if (channel.isNullOrEmpty()) {
            channel = map[TxnKeys.SETTLEMENT_TYPE]
        }

        return Result.success(
            TxnRequest(
                returnPackage = pkg,
                returnActivity = act,
                oldIntegration = isOldIntegration,
                txnType = txnType,
                channel = PaymentChannel.of(channel),
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
                preAuthType = PreAuthType.of(map[TxnKeys.PREAUTH_TYPE]),
                approvalCode = map[TxnKeys.TXN_APPROVAL_CODE],
                cardNo = map[TxnKeys.CARD_NO],
                expiryDt = map[TxnKeys.EXPIRY_DT],
                rrn = map[TxnKeys.TXN_RRN],
                ackCountdown = map[TxnKeys.ACK_COUNTDOWN],
                hasAckCountdown = map.containsKey(TxnKeys.ACK_COUNTDOWN),
                raw = map
            )
        )
    }

    private fun extractMap(intent: Intent): HashMap<String, String>? {
        // Preferred: JSON
        intent.getStringExtra(TxnKeys.EXTRA_TXN_JSON)?.let { json ->
            return try {
                gson.fromJson(json, object : com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.type)
            } catch (_: Exception) { null }
        }

        // Legacy: Serializable
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        return intent.getSerializableExtra(TxnKeys.EXTRA_TXN_MAP) as? HashMap<String, String>
    }

    private companion object {
        const val TAG = "TransactionParser"
    }
}
