package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet

class TransactionQrRepo {
    companion object {
        fun insertToDb(mContext: Context, dbModelTransactionQr: DbModelTransactionQr): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.TRANSACTION_QR, dbModelTransactionQr)
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

            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.TRANSACTION_QR, updateMap, criteriaMap) > 0
        }

        fun deleteTransactionQr(mContext: Context, fieldList: List<String>, valueList: List<String>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "DELETE FROM ${DatabaseTables.TRANSACTION_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, valueList.toTypedArray()) > 0
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

            val type = object : TypeToken<List<DbModelTransactionQrGet>>() {}.type
            return dbHandler.selectListData<DbModelTransactionQrGet>(type, sqlString, valueList.toTypedArray())
        }

        fun getListTransactionQrDescPaged(
            mContext: Context,
            refIdFilter: String,
            txnTypeFilter: String,
            limit: Int,
            offset: Int,
        ): List<DbModelTransactionQrGet> {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val whereList = mutableListOf<String>()
            val args = mutableListOf<String>()

            whereList.add("refId LIKE ?")
            args.add("%$refIdFilter%")

            when {
                txnTypeFilter.equals("Sale", true) -> {
                    whereList.add("txnType LIKE ?")
                    whereList.add("respCode = ?")
                    args.add("%Sale%")
                    args.add("0000")
                }

                txnTypeFilter.equals("Failed", true) -> {
                    whereList.add("respCode != ?")
                    args.add("0000")
                }

                txnTypeFilter.isNotBlank() -> {
                    whereList.add("txnType LIKE ?")
                    args.add("%$txnTypeFilter%")
                }
            }

            val whereClause = if (whereList.isEmpty()) "" else " WHERE ${whereList.joinToString(" AND ")}" 
            val sqlString = "SELECT * FROM ${DatabaseTables.TRANSACTION_QR.id}$whereClause ORDER BY id DESC LIMIT ? OFFSET ?"
            args.add(limit.toString())
            args.add(offset.toString())

            val type = object : TypeToken<List<DbModelTransactionQrGet>>() {}.type
            return dbHandler.selectListData(type, sqlString, args.toTypedArray())
        }

        fun countSaleSuccess(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = "SELECT COUNT(*) FROM ${DatabaseTables.TRANSACTION_QR.id} WHERE txnType LIKE ? AND respCode = ?"
            return dbHandler.executeScalar(sql, arrayOf("%Sale%", "0000"))
        }

        fun sumSaleSuccessAmount(mContext: Context): Long {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = "SELECT COALESCE(SUM(CAST(txnAmount AS INTEGER)), 0) FROM ${DatabaseTables.TRANSACTION_QR.id} WHERE txnType LIKE ? AND respCode = ?"
            return dbHandler.executeScalar(sql, arrayOf("%Sale%", "0000")).toLong()
        }

        fun countVoid(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = "SELECT COUNT(*) FROM ${DatabaseTables.TRANSACTION_QR.id} WHERE LOWER(txnType) LIKE '%void%'"
            return dbHandler.executeScalar(sql, arrayOf())
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

