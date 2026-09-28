package ecr

/**
 * The screens an ECR request can open. Each app maps these to its own UI: Pro to a navigation
 * destination, MF919 to an Activity. `args` keys are the bundle keys Pro's fragments read.
 */
enum class Destination {
    CARD_SALE,
    PREAUTH,
    SCAN_QR,
    GENERATE_QR,
    EPP_ACQUIRER,
    KEYPAD_MOTO,
    VOID_SALE,
    VOID_QR,
    SETTLE_OPTION,
    SALE_COMPLETION,
    VOID_PREAUTH,
    VOID_SALE_COMPLETION,
}

sealed class Route {
    data class Navigate(val destination: Destination, val args: Map<String, Any?> = emptyMap()) : Route()
    data class Return(val resultMap: HashMap<String, String>) : Route()
}

/** Product names and SalesType values, as in each app's ProductCatSelectionDataEnum. */
object Products {
    const val CARD_SETTINGS = "CARD_SETTINGS"
    const val EWALLET_MERCHANT_SCANS = "EWALLET_MERCHANT_SCANS"
    const val GENERATE_QR = "GENERATE_QR"
    const val MOTO = "MOTO"

    const val SALES_TYPE_CARD = 1
    const val SALES_TYPE_PREAUTH = 8
    const val SALES_TYPE_EWALLET_SCAN = 10
    const val SALES_TYPE_GENERATE_QR = 20
}

/** The ReceiptUpload row fields the enquiry reads; names match DbModelReceiptUpload for a Gson copy. */
data class EnquiryReceipt(
    val QrRefId: String? = null,
    val RESP_CODE: String? = null,
    val TXN_TYPE: String? = null,
    val TXN_AMT: String? = null,
    val MID: String? = null,
    val TID: String? = null,
    val STAN: String? = null,
    val RRN: String? = null,
    val BATCH_NO: String? = null,
    val CARD_LABEL: String? = null,
    val CARD_MASKED: String? = null,
    val ENTRY_TYPE: String? = null,
    val ARQC: String? = null,
    val TVR: String? = null,
    val AID: String? = null,
    val CVM: String? = null,
    val APPR_CODE: String? = null,
    val RRN_ORI: String? = null,
    val APPR_CODE_ORI: String? = null,
    val INV_NO: String? = null,
    val SCHEME_ID: String? = null,
    val TXN_DT: String? = null,
    val EPP_DETAIL: String? = null,
)

/** The TransactionQr row fields the enquiry reads; names match DbModelTransactionQrGet. */
data class EnquiryQr(
    val txnType: String? = null,
    val txnDateTime: String? = null,
    val voidDateTime: String? = null,
    val txnAmount: String? = null,
    val productCode: String? = null,
    val productName: String? = null,
    val refId: String? = null,
    val hostRefNo: String? = null,
    val respCode: String? = null,
    val respDesc: String? = null,
)

/**
 * What the shared router and use cases need from the app (audit item 98). App-specific behaviour
 * lives here, never behind an app check in `:core`.
 */
interface NewIntegrationHost {
    fun autoSettlementIsRunning(): Boolean

    /** A TerminalConfig boolean: SALES_CARD, SALES_EWALLET, FORCE_SETTLEMENT, FORCE_SETTLEMENT_DAILY. */
    fun configFlag(name: String): Boolean

    fun clearSettlementBatch(): Boolean

    /** The active ProductList row for [product] (and [qrProductCode] when given), or null. Opaque to `:core`. */
    fun activeProduct(product: String, qrProductCode: String? = null): Any?

    /** Caches the sale model built from [productRow]; [amountCents] null leaves the amount unset. */
    fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int)

    fun hasEppAcquirer(): Boolean

    fun receiptByPosRef(posReference: String): EnquiryReceipt?

    fun qrByRef(refId: String): EnquiryQr?

    fun log(tag: String, message: String)
}

object CurrentNewIntegrationHost : NewIntegrationHost {

    @Volatile
    private var backing: NewIntegrationHost? = null

    @JvmStatic
    fun register(host: NewIntegrationHost) {
        backing = host
    }

    @JvmStatic
    fun isRegistered(): Boolean = backing != null

    private val host: NewIntegrationHost
        get() = backing
            ?: error("CurrentNewIntegrationHost used before register() -- wire it in Application.onCreate")

    override fun autoSettlementIsRunning() = host.autoSettlementIsRunning()
    override fun configFlag(name: String) = host.configFlag(name)
    override fun clearSettlementBatch() = host.clearSettlementBatch()
    override fun activeProduct(product: String, qrProductCode: String?) = host.activeProduct(product, qrProductCode)
    override fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int) = host.setSaleModel(productRow, amountCents, salesType)
    override fun hasEppAcquirer() = host.hasEppAcquirer()
    override fun receiptByPosRef(posReference: String) = host.receiptByPosRef(posReference)
    override fun qrByRef(refId: String) = host.qrByRef(refId)
    override fun log(tag: String, message: String) = host.log(tag, message)
}
