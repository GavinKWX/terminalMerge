package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1413 {
    /*
	* Migration 1413 Details
	* - Add Last Settlement Summary Table for multiple Settlement
	* */
    lateinit var sbLog: StringBuilder
    private val lastSettlementField = listOf(
        "mid TEXT",
        "tid TEXT",
        "settleInfo TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1413 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.LAST_SETTLEMENT.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.LAST_SETTLEMENT)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.LAST_SETTLEMENT.id, lastSettlementField)
        }
        /* Check Table Existence */

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