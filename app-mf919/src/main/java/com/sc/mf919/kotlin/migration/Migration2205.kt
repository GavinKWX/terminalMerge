package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2205 {
    /*
   * Migration 2205 Details
   * - Add CASHOUT_AMT (Receipt Upload)
   * */
    lateinit var sbLog: StringBuilder
    private val receiptUploadField = listOf(
        "SEQ_NO TEXT",
        "TXN_DT TEXT",
        "TXN_TYPE TEXT",
        "MID TEXT",
        "TID TEXT",
        "MTI TEXT",
        "NII TEXT",
        "SCHEME_ID TEXT",
        "AID TEXT",
        "CARD_MASKED TEXT",
        "CARD_HASHED TEXT",
        "RRN TEXT",
        "APPR_CODE TEXT",
        "TXN_AMT TEXT",
        "INV_NO TEXT",
        "STAN TEXT",
        "BATCH_NO TEXT",
        "RESP_CODE TEXT",
        "APP_VER TEXT",
        "SN TEXT",
        "ENTRY_TYPE TEXT",
        "ARQC TEXT",
        "TVR TEXT",
        "POS_REF_NO TEXT",
        "PAYMENT_PRODUCT_ID TEXT",
        "CVM TEXT",
        "CARD_LABEL TEXT",
        "EPP_DETAIL TEXT",
        "BATCHNO_PREAUTH TEXT",
        "CASHOUT_AMT TEXT",
        "QrRefId TEXT",
        "QrType TEXT",
        "CreationDate TEXT",
        "LastUpdateDt TEXT",
        "IsProcessing TEXT",
        "IsSend TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2205 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var receiptUploadExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            receiptUploadExist = false
            createTables(DatabaseTables.RECEIPT_UPLOAD.id, receiptUploadField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(receiptUploadExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> CASHOUT_AMT")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "CASHOUT_AMT")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "CASHOUT_AMT Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET CASHOUT_AMT = '0' WHERE CASHOUT_AMT IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }
        }
        /*  Table Exist [End]*/
        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }
}