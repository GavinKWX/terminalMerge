package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet

class TransactionQrRepo {
    companion object {
        fun insertToDb(mContext: Context, dbModelTransactionQr: DbModelTransactionQr): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.TRANSACTION_QR, dbModelTransactionQr)
            return true
        }

        fun getSingleTransactionQr(mContext: Context, fieldList: List<String>, valueList: List<String>): DbModelTransactionQrGet? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.TRANSACTION_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            return dbHandler.selectSingleData<DbModelTransactionQrGet>(sqlString, valueList.toTypedArray())
        }

        fun getListTransactionQr(mContext: Context, fieldList: List<String>, valueList: List<String>): List<DbModelTransactionQrGet> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.TRANSACTION_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            val type = object : TypeToken<List<DbModelTransactionQrGet>>() {}.type
            return dbHandler.selectListData<DbModelTransactionQrGet>(type, sqlString, valueList.toTypedArray())
        }

        fun updateTransactionQr(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            dbHandler.updateTableValue(DatabaseTables.TRANSACTION_QR, updateMap, criteriaMap)
            return true
        }

        fun deleteTransactionQr(mContext: Context, fieldList: List<String>, valueList: List<String>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "DELETE FROM ${DatabaseTables.TRANSACTION_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            dbHandler.runningCustomSql(sqlString, valueList.toTypedArray())
            return true
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.TRANSACTION_QR)
        }

        fun getListTransactionQrDesc(mContext: Context, fieldList: List<String>, valueList: List<String>): List<DbModelTransactionQrGet> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.TRANSACTION_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            sqlString = "$sqlString ORDER BY id DESC"
            //sqlString = "$sqlString ORDER BY txnDateTime DESC"

            val type = object : TypeToken<List<DbModelTransactionQrGet>>() {}.type
            return dbHandler.selectListData<DbModelTransactionQrGet>(type, sqlString, valueList.toTypedArray())
        }

        fun getDistinctProduct(mContext: Context): List<DbModelQrPayBrandGet> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelQrPayBrandGet>>() {}.type
            return dbHandler.selectListData<DbModelQrPayBrandGet>(type, "SELECT DISTINCT productCode, productName FROM ${DatabaseTables.TRANSACTION_QR.id} GROUP BY productCode ORDER BY productCode", arrayOf())
        }

        fun getListTransactionQrNoCase(mContext: Context, productName: List<String>?): List<DbModelTransactionQrGet> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * from TransactionQr WHERE respCode = '0000'"

            productName?.let {
                if(it.isNotEmpty()){
                    val joinedProducts = it.joinToString("\", \"")
                    sqlString += " AND payBrand COLLATE NOCASE IN (\"$joinedProducts\")"
                }
            }
            sqlString += " ORDER BY refId, txnDateTime"

            val type = object : TypeToken<List<DbModelTransactionQrGet>>() {}.type
            return dbHandler.selectListData<DbModelTransactionQrGet>(type, sqlString, arrayOf())
        }

        fun countTransactionCountByType(mContext: Context, productCode:String, txnType:String): Int{
            val dbHandler = DbHandler.getInstance(mContext)!!
            val respCode = "0000"
            val sqlString = "SELECT count(*) FROM ${DatabaseTables.TRANSACTION_QR.id} WHERE productCode = ? AND txnType = ? AND respCode = ? "

            return dbHandler.executeScalar(sqlString, arrayOf(productCode, txnType, respCode))
        }
        fun countTransactionAmountByType(mContext: Context, productCode:String, txnType:String): Int{
            val dbHandler = DbHandler.getInstance(mContext)!!
            val respCode = "0000"
            val sqlString = "SELECT SUM(txnAmount) FROM ${DatabaseTables.TRANSACTION_QR.id} WHERE productCode = ? AND txnType = ? AND respCode = ? "

            return dbHandler.executeScalar(sqlString, arrayOf(productCode, txnType, respCode))
        }
    }
}