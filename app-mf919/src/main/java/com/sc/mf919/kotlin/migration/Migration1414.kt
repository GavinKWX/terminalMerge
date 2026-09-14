package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1414 {
    /*
  * Migration 1413 Details
  * - Add Field (CVM) into ReceiptUpload Table
  * - Add Field (EPP_DETAIL) into ReceiptUpload Table
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
        "CARD_LABEL TEXT",
        "CVM TEXT",
        "EPP_DETAIL TEXT",
        "CreationDate TEXT",
        "LastUpdateDt TEXT",
        "IsProcessing TEXT",
        "IsSend TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1413 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.RECEIPT_UPLOAD.id, receiptUploadField)
        }
        /* Check Table Existence */

        /*  Check Column Exist [Start]*/
        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> CVM")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "CVM")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "CVM Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        }

        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> EPP_DETAIL")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "EPP_DETAIL")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "EPP_DETAIL Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        }

        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> CARD_LABEL")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "CARD_LABEL")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "CARD_LABEL Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        }
        /*  Check Column [END]*/

        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }
}