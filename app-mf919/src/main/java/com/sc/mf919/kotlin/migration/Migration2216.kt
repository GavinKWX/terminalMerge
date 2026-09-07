package com.sc.mf919.kotlin.migration

import com.google.gson.Gson
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchLongInfo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2216 {
    /*
        * Migration 2216 Details
        * - Add data postingDt  (IsoBatchLongInfo)
    * */
    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2216 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        HelperLog.appendLine(sbLog, "Checking postingDt in IsoBatchLongInfo")
        val lastPostingDt = dbHandler.selectSingleData<DbModelIsoBatchLongInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_LONG_INFO.id} Where tag = ? and subtag = ? ", arrayOf<String>("postingDt", "last"))
        HelperLog.appendLine(sbLog, "lastPostingDt -> ${Gson().toJson(lastPostingDt)}")
        if(lastPostingDt == null) {
            HelperLog.appendLine(sbLog, "No Data Found -> Inserting New to ${DatabaseTables.ISO_BATCH_LONG_INFO}")
            val newPostingIsoBatchLong = DbModelIsoBatchLongInfo("postingDt", "last", "")
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_LONG_INFO, newPostingIsoBatchLong)
        }

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