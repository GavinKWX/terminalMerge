package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelSettlementSummary

class SettlementSummaryRepo {
	companion object{
		fun getRecordValue(mContext: Context, fieldList: List<String>, valueList: Array<String>): String? {
			var result: String? = null
			val response = getSingle(mContext, fieldList, valueList)

			if(response != null){
				result = response.value
			}

			return result
		}

		fun getSelectiveData(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelSettlementSummary>{
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "SELECT * FROM SettlementSummary WHERE"
			for(a in fieldList.indices){
				val addAnd = if( a != 0) "AND" else ""
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			val type = object : TypeToken<List<DbModelSettlementSummary>>() {}.type
			return dbHandler.selectListData<DbModelSettlementSummary>(type, sqlString, valueList)
		}

		fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelSettlementSummary? {
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "SELECT * FROM SettlementSummary WHERE"

			for (a in fieldList.indices) {
				val addAnd = if (a != 0) "AND" else ""
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			return dbHandler.selectSingleData<DbModelSettlementSummary>(sqlString, valueList)
		}

		fun insertToDb(mContext: Context, dbModelSettlementSummary: DbModelSettlementSummary): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			dbHandler.insertToDb(DatabaseTables.SETTLEMENT_SUMMARY, dbModelSettlementSummary)

			return true
		}

		fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			dbHandler.updateTableValue(DatabaseTables.SETTLEMENT_SUMMARY, updateMap, criteriaMap)
			return true
		}

		fun deleteSettlementByProductList(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			val sqlString = "DELETE FROM SettlementSummary WHERE id in ( " +
					"SELECT SettlementSummary.id FROM SettlementSummary " +
					"LEFT JOIN ProductList ON SettlementSummary.acq_code = ProductList.AcqCode AND ProductList.AcqMid = SettlementSummary.mid AND ProductList.AcqTid = SettlementSummary.tid " +
					"WHERE ProductList.Id IS NULL )"
			dbHandler.runningCustomSql(sqlString, arrayOf())
			return true
		}

		fun checkDuplicate(mContext: Context): Int  {
			val dbHandler = DbHandler.getInstance(mContext)!!
			val sqlString = "SELECT count(*) FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} GROUP BY acq_code, mid, tid, tag, subtag HAVING COUNT(*) > 1"
			val recordCount = dbHandler.executeScalar(sqlString, arrayOf())
			return recordCount
		}

		fun deleteDuplicate(mContext: Context): Boolean  {
			val dbHandler = DbHandler.getInstance(mContext)!!
			val sqlString = "DELETE FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} WHERE id NOT IN (" +
					"SELECT MIN(id)FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} " +
					"GROUP BY acq_code, mid, tid, tag, subtag)"
			dbHandler.runningCustomSql(sqlString,arrayOf())
			return true
		}

		fun truncateTable(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			// Was SECURE_DATA -- a copy-paste bug. secureData is the key store, so any caller of this
			// would have wiped the terminal's keys instead of the settlement summary. Currently
			// uncalled, which is the only reason it never fired in the field.
			return dbHandler.truncateTables(DatabaseTables.SETTLEMENT_SUMMARY)
		}
	}
}