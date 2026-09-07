package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2215 {
    /*
        * Migration 2215 Details
        * - Add posRefNo (Batch Table)
        * - Add posRefNo (TransactionQr)
    * */
    lateinit var sbLog: StringBuilder

    private val batchTableField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt vchar(50) NOT NULL",
        "txnType vchar(25) NOT NULL",
        "stan vchar(25) NOT NULL",
        "invNo vchar(25) NOT NULL",
        "batchData TEXT NOT NULL",
        "schemeTag vchar(25)",
        "schemeId vchar(10)",
        "refId INTEGER NOT NULL",
        "status vchar(3) NOT NULL",
        "batchNo vchar(25)",
        "mid TEXT NOT NULL",
        "tid TEXT NOT NULL",
        "posRefNo TEXT NOT NULL",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    private val transactionQrField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "txnType TEXT",
        "txnDateTime TEXT",
        "voidDateTime TEXT",
        "txnAmount TEXT",
        "payChannel TEXT",
        "payBrand TEXT",
        "productCode TEXT",
        "productName TEXT",
        "mid TEXT",
        "tid TEXT",
        "acqCode TEXT",
        "refId TEXT",
        "hostRefNo TEXT",
        "txnRefNo TEXT",
        "approvalCode TEXT",
        "respCode TEXT",
        "respDesc TEXT",
        "isUnionPayTxn TEXT",
        "upiVoucherCode TEXT",
        "upiDiscountAmt TEXT",
        "upiMarkupFee TEXT",
        "posRefNo TEXT",
        "isTpaAccount TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2215 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var batchTableExist = true
        var transactionQrExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.BATCH_TABLE.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.BATCH_TABLE)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            batchTableExist = false
            createTables(DatabaseTables.BATCH_TABLE.id, batchTableField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TRANSACTION_QR.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TRANSACTION_QR)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            transactionQrExist = false
            createTables(DatabaseTables.TRANSACTION_QR.id, transactionQrField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start] */
        if(batchTableExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.BATCH_TABLE.id} -> posRefNo")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.BATCH_TABLE, "posRefNo")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "posRefNo Alter to -> ${DatabaseTables.BATCH_TABLE.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.BATCH_TABLE.id} SET posRefNo = '' WHERE posRefNo IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }

        if(transactionQrExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TRANSACTION_QR.id} -> posRefNo")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TRANSACTION_QR, "posRefNo")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "posRefNo Alter to -> ${DatabaseTables.TRANSACTION_QR.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.TRANSACTION_QR.id} SET posRefNo = '' WHERE posRefNo IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }
        /*  Table Exist [End] */
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