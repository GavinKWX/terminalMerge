package com.sc.mf919pro.kotlin.migration

import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import enums.EnumLogFileName
import helpers.HelperLog
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class Migration1001 {
    /*
        * Migration 1001 Details
        * - Add ORDERING_ITEM, ORDERING_ITEM_IMG in (Receipt Upload)
    * */

    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1001 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var receiptUploadExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            receiptUploadExist = false
            createTables(DatabaseTables.RECEIPT_UPLOAD.id, DatabaseTables.RECEIPT_UPLOAD.createSql)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(receiptUploadExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> ORDERING_ITEM")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "ORDERING_ITEM")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ORDERING_ITEM Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET ORDERING_ITEM = '' WHERE ORDERING_ITEM IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> ORDERING_ITEM_IMG")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "ORDERING_ITEM_IMG")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ORDERING_ITEM_IMG Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET ORDERING_ITEM_IMG = '' WHERE ORDERING_ITEM_IMG IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }
        }
        /*  Table Exist [End]*/
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