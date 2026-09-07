package com.sc.mf919pro.kotlin.helper_common.intent_helper

object TxnKeys {
    const val EXTRA_TXN_JSON = "txn_json"
    const val EXTRA_TXN_MAP  = "txn_map" // optional legacy
    const val SETTLE_TXN_MAP  = "settlement_map" // optional legacy
    const val PACKAGE_NAME = "Package_Name"
    const val ACTIVITY_NAME = "Activity_Name"

    const val OLD_INTEGRATION = "IsOldIntegration"
    const val TXN_TYPE = "TransactionType"
    const val AMOUNT = "TransactionAmount"
    const val CAMERA_FACING = "CameraFacing"
    const val TXN_INVOICE = "TransactionInvoice"
    const val TXN_APPROVAL_CODE = "TransactionApprovalCode"
    const val TXN_RRN = "TransactionRRN"
    const val POS_REF = "PosReference"
    const val CARD_NO = "CardNumber"
    const val EXPIRY_DT = "ExpiryDate"
    const val ORDERING_ITEM = "OrderingItem"
    const val ORDERING_ITEM_IMG = "OrderingItemImage"

    //OLD INTEGRATION
    const val PRODUCT_CODE = "ProductCode"
    const val TXN_REF_ID  = "TransactionRefId"

    //NEW INTEGRATION
    const val PAYMENT_CODE = "PaymentCode"
    const val CHANNEL = "PaymentChannel"
    const val SETTLEMENT_TYPE = "SettlementType"
    const val PREAUTH_TYPE = "PreAuthType"

    const val RESP_CODE = "ResponseCode"
    const val RESP_DESC = "ResponseDescription"
}

/*data class TxnRequest(
    val returnPackage: String,
    val returnActivity: String,
    val oldIntegration: Boolean = false,
    val txnType: Int,
    val channel: String?,
    val amount: Long?,
    val amountString: String?,
    val posReference: String?,
    val raw: HashMap<String, String>
)*/

data class TxnRequest(
    val returnPackage: String,
    val returnActivity: String,

    val oldIntegration: Boolean = false,

    val txnType: Int,
    var channel: PaymentChannel? = null,

    val amount: Long? = null,          // cents
    val amountString: String? = null,
    val posReference: String? = null,
    val orderingItem: String? = null,
    val orderingItemImage: String? = null,

    // Sale / QR
    var paymentCode: String? = null,
    val productCode: String? = null,
    val cameraFacing: Int? = null,

    // Void
    var invoice: String? = null,
    val transRefId: String? = null,
    val forceVoid: Int = 0,

    // PreAuth
    var preAuthType: PreAuthType? = null,
    val approvalCode: String? = null,
    val rrn: String? = null,

    // Moto
    var cardNo: String? = null,
    val expiryDt: String? = null,

    //use as result??
    val raw: HashMap<String, String>
)

/*data class TxnResult(
    val map: HashMap<String, String>
)*/
data class TxnResult(
    val responseCode: String,
    val responseDescription: String,
    val data: Map<String, String> = emptyMap()
)

enum class PaymentChannel {
    ALL,
    CARD,
    SCAN,
    QR,
    EPP,
    MOTO
}
enum class PreAuthType {
    PREAUTH,
    PREAUTHCOMPLETE,
    VOIDPREAUTH,
    VOIDPREAUTHCOMPLETE
}