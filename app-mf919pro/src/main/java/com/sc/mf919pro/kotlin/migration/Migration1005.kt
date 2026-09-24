package com.sc.mf919pro.kotlin.migration

import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import enums.EnumLogFileName
import helpers.HelperLog
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class Migration1005 {
    /*
        * Migration 1005 Details
        * - Add BYPASS_PIN in (Terminal Configuration)
        *
        * Defaults to '0' (PIN required). Before this version the PIN pad accepted an empty PIN
        * unconditionally, so every terminal changes behaviour here: an empty PIN is now rejected
        * unless BYPASS_PIN is turned on. That is the point of the change, not a side effect.
    * */

    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1005 -----")
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
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> BYPASS_PIN")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "BYPASS_PIN")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "BYPASS_PIN Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.TERMINAL_CONFIGURATION.id} SET BYPASS_PIN = '0' WHERE BYPASS_PIN IS NULL"
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
