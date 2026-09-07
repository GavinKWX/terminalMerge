package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelRevBatchTable
import com.sc.mf919pro.kotlin.database.model.DbModelRevBatchTableInsert

class ReversalBatchTableRepo {
    companion object{
        fun getAll(mContext: Context): List<DbModelRevBatchTable> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sqlString = "SELECT * FROM ${DatabaseTables.REVERSAL_BATCH_TABLE.id}"

            val type = object : TypeToken<List<DbModelRevBatchTable>>() {}.type
            return dbHandler.selectListData<DbModelRevBatchTable>(type, sqlString, arrayOf())
        }

        fun getBatchData(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelRevBatchTable> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.REVERSAL_BATCH_TABLE.id}"

            if(fieldList.isNotEmpty()){
                sqlString = "$sqlString WHERE "
            }

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            sqlString = "$sqlString ORDER BY id ASC"

            val type = object : TypeToken<List<DbModelRevBatchTable>>() {}.type
            return dbHandler.selectListData<DbModelRevBatchTable>(type, sqlString, valueList)
        }

        fun insertToDb(mContext: Context, dbModelRevBatchTable: DbModelRevBatchTableInsert): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.REVERSAL_BATCH_TABLE, dbModelRevBatchTable)
        }

        fun deleteSuccessReversalRecord(mContext: Context, stan: String, invNo: String, batchNo: String, mid: String, tid: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.REVERSAL_BATCH_TABLE.id} WHERE stan = ? AND invNo = ? AND batchNo = ? AND mid = ? AND tid = ? "
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf(stan, invNo, batchNo, mid, tid)) > 0
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.REVERSAL_BATCH_TABLE)
        }
    }
}