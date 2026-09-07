package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder

class Migration2221 {
    /*
        * Migration 2221 Details
        * - Add settleDt in (Last Settlement)
    * */

    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2221 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var lastSettlementExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.LAST_SETTLEMENT.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.LAST_SETTLEMENT)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            lastSettlementExist = false
            createTables(DatabaseTables.LAST_SETTLEMENT.id, DatabaseTables.LAST_SETTLEMENT.createSql)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(lastSettlementExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.LAST_SETTLEMENT.id} -> settleDt")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.LAST_SETTLEMENT, "settleDt")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "settleDt Alter to -> ${DatabaseTables.LAST_SETTLEMENT.id}")
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