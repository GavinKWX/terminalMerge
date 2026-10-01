package ecr

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import utils.AmountFormat

/**
 * Pro's new-integration settlement reply, for an app whose settlement screens produce another
 * shape (MF919, audit item 98 D1). Built to match Pro's SettleOptionFragment field for field:
 * HTTP entries use `Transaction*` keys, App-to-App maps use `Settlement*` keys with the scheme
 * list as a JSON string.
 */
object SettlementReply {

    /** One SettlementSummary row: tag (txnCount, txnTotal, voidTxnCount, voidTxnTotal, ...), subtag (visam-visa, ...), value. */
    data class SummaryRow(val tag: String, val subtag: String, val value: String?)

    /** Per-product QR totals; amounts in cents. */
    data class QrProductTotals(val productCode: String, val saleCount: Int, val saleAmount: Long, val voidCount: Int, val voidAmount: Long)

    private val tags = linkedMapOf(
        "txnCount" to "SALECOUNT",
        "txnTotal" to "SALETOTAL",
        "voidTxnCount" to "VOIDCOUNT",
        "voidTxnTotal" to "VOIDTOTAL",
    )

    /** The per-scheme array, as Pro builds it: Visa and Master always, UnionPay for GOBIZ, MyDebit when OptIn. */
    fun cardSchemes(rows: List<SummaryRow>, acqName: String, optIn: Boolean): JsonArray {
        val schemes = linkedMapOf("visa" to "Visa", "master" to "Master")
        if (acqName.equals("GOBIZ", true)) schemes["upi"] = "UnionPay"
        if (optIn) schemes["mccs"] = "MyDebit"

        val array = JsonArray()
        for ((key, name) in schemes) {
            val brand = JsonObject()
            brand.addProperty("SCHEME", name)
            for ((tag, field) in tags) {
                var value = rows.find { it.tag == tag && it.subtag == "visam-$key" }?.value ?: "0"
                if (tag.contains("Total")) value = AmountFormat.getActualAmount(value)
                brand.addProperty(field, value)
            }
            array.add(brand)
        }
        return array
    }

    /** A settled card acquirer, HTTP form. */
    fun cardEntryHttp(dateTime: String, mid: String?, tid: String?, batchNo: String?, schemes: JsonArray) = JsonObject().apply {
        addProperty("TransactionDateTime", dateTime)
        addProperty("TransactionMID", mid)
        addProperty("TransactionTID", tid)
        addProperty("TransactionBatchNo", batchNo)
        addProperty("ResponseCode", "00")
        addProperty("ResponseDescription", "Success")
        add("SettlementDetail", schemes)
    }

    /** A card acquirer, App-to-App form; [schemes] null means it failed with [failCode]. */
    fun cardEntryA2a(dateTime: String, mid: String?, tid: String?, batchNo: String?, schemes: JsonArray?, failCode: String = "99"): HashMap<String, String?> =
        hashMapOf<String, String?>(
            "SettlementDateTime" to dateTime,
            "SettlementMID" to mid,
            "SettlementTID" to tid,
            "SettlementBatchNo" to batchNo,
        ).apply {
            if (schemes == null) {
                put("ResponseCode", failCode)
                put("ResponseDescription", "Failed")
            } else {
                put("ResponseCode", "00")
                put("ResponseDescription", "Success")
                put("SettlementDetail", Gson().toJson(schemes))
            }
        }

    /** The per-product QR array, as Pro builds it: a product with no sale and no void is left out. */
    fun qrProducts(products: List<QrProductTotals>): JsonArray {
        val array = JsonArray()
        for (p in products) {
            if (p.saleCount == 0 && p.voidCount == 0) continue
            array.add(JsonObject().apply {
                addProperty("SCHEME", p.productCode)
                addProperty("SALECOUNT", p.saleCount.toString())
                addProperty("SALETOTAL", AmountFormat.getActualAmount(p.saleAmount.toString()))
                addProperty("VOIDCOUNT", p.voidCount.toString())
                addProperty("VOIDTOTAL", AmountFormat.getActualAmount(p.voidAmount.toString()))
            })
        }
        return array
    }

    /** The QR entry, HTTP form. */
    fun qrEntryHttp(dateTime: String, merchantCode: String?, products: JsonArray) = JsonObject().apply {
        addProperty("ResponseCode", "00")
        addProperty("ResponseDescription", "Success")
        addProperty("TransactionDateTime", dateTime)
        addProperty("TransactionMID", merchantCode)
        addProperty("TransactionTID", "-")
        add("SettlementDetail", products)
    }

    /** The QR entry, App-to-App form. */
    fun qrEntryA2a(dateTime: String, merchantCode: String?, products: JsonArray): HashMap<String, String?> = hashMapOf(
        "ResponseCode" to "00",
        "ResponseDescription" to "Settled",
        "SettlementDateTime" to dateTime,
        "SettlementMID" to merchantCode,
        "SettlementTID" to "-",
        "SettlementDetail" to Gson().toJson(products),
    )

    /** The HTTP wrapper around every entry. */
    fun wrapperHttp(txnType: Int, settlementType: String, entries: JsonArray) = JsonObject().apply {
        addProperty("ResponseCode", "00")
        addProperty("ResponseDescription", "Settlement")
        addProperty("TransactionType", txnType.toString())
        addProperty("SettlementType", settlementType)
        add("SettlementDetail", entries)
    }

    /** Pro's settlement timestamp format. */
    const val DATE_FORMAT = "yyyy/MM/dd HH:mm:ss"
}
