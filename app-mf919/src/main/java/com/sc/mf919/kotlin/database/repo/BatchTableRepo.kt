package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelBatchTable
import com.sc.mf919.kotlin.database.model.DbModelBatchTableInsert

class BatchTableRepo() {
	companion object{
		fun getFirstData(mContext: Context): DbModelBatchTable? {
			val dbHandler = DbHandler.getInstance(mContext)!!
			return dbHandler.selectSingleData<DbModelBatchTable>("SELECT * FROM batchTable LIMIT 1", arrayOf<String>())
		}

		fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelBatchTable? {
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "SELECT * FROM ${DatabaseTables.BATCH_TABLE.id} WHERE"

			for (a in fieldList.indices) {
				val addAnd = if (a != 0) "AND" else ""
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			return dbHandler.selectSingleData<DbModelBatchTable>(sqlString, valueList)
		}

		fun getSingleForCertainType(mContext: Context, fieldList: List<String>, valueList: List<String>, typeList: List<String>): DbModelBatchTable? {
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "SELECT * FROM ${DatabaseTables.BATCH_TABLE.id} WHERE"

			for (a in fieldList.indices) {
				val addAnd = if (a != 0) "AND" else ""
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			if(typeList.isNotEmpty()) {
				sqlString = "$sqlString AND txnType in (${typeList.joinToString(", "){"'$it'"} })"
			}

			return dbHandler.selectSingleData<DbModelBatchTable>(sqlString, valueList.toTypedArray())
		}

		fun insertToDb(mContext: Context, dbModelBatchTable: DbModelBatchTableInsert): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			dbHandler.insertToDb(DatabaseTables.BATCH_TABLE, dbModelBatchTable)

			return true
		}

		fun deleteVoidedInvoice(mContext: Context, invoiceNo: String): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			val sqlString = "DELETE FROM ${DatabaseTables.BATCH_TABLE.id} WHERE invNo = ? "
			dbHandler.runningCustomSql(sqlString, arrayOf(invoiceNo))
			return true
		}

		fun deleteBatchRecord(mContext: Context, batchNo: String): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			val sqlString = "DELETE FROM ${DatabaseTables.BATCH_TABLE.id} WHERE batchNo = ? "
			dbHandler.runningCustomSql(sqlString, arrayOf(batchNo))
			return true
		}

		fun truncateTable(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			return dbHandler.truncateTables(DatabaseTables.BATCH_TABLE)
		}

		fun getBatchData(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelBatchTable> {
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "SELECT * FROM ${DatabaseTables.BATCH_TABLE.id}"

			if(fieldList.isNotEmpty()){
				sqlString = "$sqlString WHERE "
			}

			for (a in fieldList.indices) {
				val addAnd = if (a != 0) "AND" else ""
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			val type = object : TypeToken<List<DbModelBatchTable>>() {}.type
			return dbHandler.selectListData<DbModelBatchTable>(type, sqlString, valueList)
		}
	}
}