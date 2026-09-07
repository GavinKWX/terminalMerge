package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelSettlementSummary

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

		fun checkDuplicateData(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			val recordCount = dbHandler.executeScalar(
				"SELECT count(*) FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} GROUP BY acq_Code, mid, tid, tag, subtag HAVING COUNT(*) > 1",
				arrayOf()
			)
			return recordCount > 0
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
			return dbHandler.insertToDb(DatabaseTables.SETTLEMENT_SUMMARY, dbModelSettlementSummary)
		}

		fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			// D3 — report the real outcome; updateTableValue returns 0 on failure.
			return dbHandler.updateTableValue(DatabaseTables.SETTLEMENT_SUMMARY, updateMap, criteriaMap) > 0
		}

		fun deleteSettlementByProductList(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!

			val sqlString = "DELETE FROM SettlementSummary WHERE id in ( " +
					"SELECT SettlementSummary.id FROM SettlementSummary " +
					"LEFT JOIN ProductList ON SettlementSummary.acq_code = ProductList.AcqCode AND ProductList.AcqMid = SettlementSummary.mid AND ProductList.AcqTid = SettlementSummary.tid " +
					"WHERE ProductList.Id IS NULL )"
			// D3 — report the real outcome; runningCustomSql returns 0 on failure.
			return dbHandler.runningCustomSql(sqlString, arrayOf()) > 0
		}

		fun deleteSettlementSummary(mContext: Context, fieldList: List<String>, valueList: List<String>): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			var sqlString = "DELETE FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} "
			for (a in fieldList.indices) {
				val addAnd = if (a == 0) "WHERE" else "AND"
				sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
			}

			// D3 — report the real outcome; runningCustomSql returns 0 on failure.
			return dbHandler.runningCustomSql(sqlString, valueList.toTypedArray()) > 0
		}


		fun truncateTable(mContext: Context): Boolean {
			val dbHandler = DbHandler.getInstance(mContext)!!
			return dbHandler.truncateTables(DatabaseTables.SETTLEMENT_SUMMARY)
		}
	}
}