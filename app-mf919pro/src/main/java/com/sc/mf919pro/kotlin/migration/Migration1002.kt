package com.sc.mf919pro.kotlin.migration

import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import enums.EnumLogFileName
import helpers.HelperLog
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class Migration1002 {
    /*
        * Migration 1002 Details
        * - Add RECEIPT_MERCHANT_INFO_SIZE, RECEIPT_TXN_INFO_SIZE in (Terminal Configuration)
    * */

    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1002 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var terminalConfigExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            terminalConfigExist = false
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, DatabaseTables.TERMINAL_CONFIGURATION.createSql)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> RECEIPT_MERCHANT_INFO_SIZE")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "RECEIPT_MERCHANT_INFO_SIZE")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "RECEIPT_MERCHANT_INFO_SIZE Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.TERMINAL_CONFIGURATION.id} SET RECEIPT_MERCHANT_INFO_SIZE = '' WHERE RECEIPT_MERCHANT_INFO_SIZE IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> RECEIPT_TXN_INFO_SIZE")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "RECEIPT_TXN_INFO_SIZE")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "RECEIPT_TXN_INFO_SIZE Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.TERMINAL_CONFIGURATION.id} SET RECEIPT_TXN_INFO_SIZE = '' WHERE RECEIPT_TXN_INFO_SIZE IS NULL"
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