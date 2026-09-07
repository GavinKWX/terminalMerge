package com.sc.mf919pro.kotlin.database.model

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

data class DbModelReceiptUpload(
    var SEQ_NO: String?,
    var TXN_DT: String?,
    var TXN_TYPE: String?,
    var MID: String?,
    var TID: String?,
    var MTI: String?,
    var NII: String?,
    var SCHEME_ID: String?,
    var AID: String?,
    var CARD_MASKED: String?,
    var CARD_HASHED: String?,
    var RRN: String?,
    var APPR_CODE: String?,
    var RRN_ORI: String?,
    var APPR_CODE_ORI: String?,
    var TXN_AMT: String?,
    var INV_NO: String?,
    var STAN: String?,
    var BATCH_NO: String?,
    var RESP_CODE: String?,
    var APP_VER: String?,
    var SN: String?,
    var ENTRY_TYPE: String?,
    var ARQC: String?,
    var TVR: String?,
    var POS_REF_NO: String?,
    var PAYMENT_PRODUCT_ID: String?,
    var CARD_LABEL: String?,
    var CVM: String?,
    var BATCHNO_PREAUTH: String?,
    var EPP_DETAIL: String?,
    var CASHOUT_AMT: String?,
    var ORDERING_ITEM: String?,
    var ORDERING_ITEM_IMG: String?,
    var CORRELATION_REF: String?,
    var ADDITIONAL_INFO: String?,
    var QrRefId: String?,
    var QrType: String?,
    var CreationDate: String?,
    var LastUpdateDt: String?,
    var IsProcessing: String?,
    var IsSend: String?
) {
    companion object {
        fun sanitizeSpecialChars(model: DbModelReceiptUpload): DbModelReceiptUpload {
            val specialCharRegex = Regex("[^a-zA-Z0-9 *._:\\-]") // allows letters, digits, space

            val mutableProps = DbModelReceiptUpload::class.memberProperties
                .filterIsInstance<KMutableProperty1<DbModelReceiptUpload, *>>()
                .filter { (it.returnType.classifier == String::class || it.returnType.classifier == String::class.java)
                        && it.name != "EPP_DETAIL" && it.name != "ORDERING_ITEM" && it.name != "ORDERING_ITEM_IMG"
                        && it.name != "ADDITIONAL_INFO"}

            for (prop in mutableProps) {
                prop.isAccessible = true
                val value = prop.get(model) as? String?
                if (value != null && specialCharRegex.containsMatchIn(value)) {
                    println("${prop.name} :: $value")
                    (prop as KMutableProperty1<DbModelReceiptUpload, String?>).set(model, "")
                }
            }

            return model
        }
    }
}
