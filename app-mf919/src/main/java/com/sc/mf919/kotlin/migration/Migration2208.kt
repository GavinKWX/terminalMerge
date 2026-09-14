package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder

class Migration2208 {
    /*
    * Migration 2208 Details
    * - Add respCode (Print Receipt)
    * */

    lateinit var sbLog: StringBuilder
    private val printReceiptField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt VARCHAR(30) NOT NULL",
        "txnDt VARCHAR(30) NOT NULL",
        "txnType VARCHAR(20) NOT NULL",
        "cardMasked VARCHAR(30)",
        "schemeId VARCHAR(5)",
        "txnAmt VARCHAR(15)",
        "invoiceNo VARCHAR(8)",
        "stan VARCHAR(8)",
        "txnApprCode VARCHAR(8)",
        "receiptInfo VARCHAR(1000)",
        "isTpaAccount TEXT",
        "respCode TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2208 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.mContext)!!
        var printReceiptExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.PRINT_RECEIPT.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            printReceiptExist = false
            createTables(DatabaseTables.PRINT_RECEIPT.id, printReceiptField)
        }
        /* Check Table Existence */

        if(printReceiptExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT.id} -> respCode")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT, "respCode")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "respCode Alter to -> ${DatabaseTables.PRINT_RECEIPT.id}")
            }

            val customUpdateSqlRespCode = "UPDATE ${DatabaseTables.PRINT_RECEIPT.id} SET respCode = '00' WHERE respCode IS NULL"
            dbHandler.runningCustomSql(customUpdateSqlRespCode, arrayOf())
        }

        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.mContext)!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }
}