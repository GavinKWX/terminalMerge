package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelProductListGet

class ProductListRepo {
    companion object {
        fun getDistinctProduct(mContext: Context): List<DbModelProductList> {
          val dbHandler = DbHandler.getInstance(mContext)!!
          val type = object : TypeToken<List<DbModelProductList>>() {}.type
          return dbHandler.selectListData<DbModelProductList>(type, "SELECT DISTINCT Product FROM ProductList", arrayOf())
        }

        fun getDistinctBnplAcquirer(mContext: Context): List<DbModelProductList> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, "SELECT DISTINCT AcqCode FROM ProductList WHERE Product = 'BNPL'", arrayOf())
        }

        fun getDistinctEppAcquirer(mContext: Context): List<DbModelProductList> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, "SELECT DISTINCT AcqCode FROM ProductList WHERE Product = 'EPP'", arrayOf())
        }

        fun getSelectedProduct(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelProductList>{
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ProductList WHERE"
            for(a in fieldList.indices){
                val addAnd = if( a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, sqlString, valueList)
        }

        fun getSelectedProductEnhanced(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelProductList>{
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ProductList WHERE"
            for(a in fieldList.indices){
                val addAnd = if( a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, sqlString, valueList)
        }

        fun getUnSettledProduct(mContext: Context): List<DbModelProductList>{
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "SELECT ProductList.* FROM ProductList " +
                    "LEFT JOIN SettlementSummary ON ProductList.AcqMid = SettlementSummary.mid AND ProductList.AcqTid = SettlementSummary.tid AND SettlementSummary.is_settle = ? " +
                    "WHERE SettlementSummary.mid is NULL AND ProductList.IsSettlement = ? Group By ProductList.AcqTid, ProductList.AcqMid "

            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, sqlString, arrayOf("true", "true"))
        }

        fun getCountSettledProduct(mContext: Context): Int{
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "SELECT count(ProductList.AcqMid) FROM ProductList " +
                    "LEFT JOIN SettlementSummary ON ProductList.AcqMid = SettlementSummary.mid AND ProductList.AcqTid = SettlementSummary.tid AND SettlementSummary.is_settle = ? " +
                    "WHERE SettlementSummary.mid is NOT NULL AND ProductList.IsSettlement = ? "

            return dbHandler.executeScalar(sqlString, arrayOf("true", "true"))
        }

        fun getAll(mContext: Context): List<DbModelProductList> {
            val dbHandler = DbHandler.getInstance(mContext)!!
//            return dbHandler.selectListData("SELECT * FROM ProductList", arrayOf())

            val sqlString = "SELECT * FROM ProductList"

            val type = object : TypeToken<List<DbModelProductList>>() {}.type
            return dbHandler.selectListData<DbModelProductList>(type, sqlString, arrayOf())

        }

        fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelProductListGet? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ProductList WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            return dbHandler.selectSingleData<DbModelProductListGet>(sqlString, valueList)
        }

        fun getSinglev2(mContext: Context, fieldList: List<String>, valueList: List<String>): DbModelProductListGet? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ProductList WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            return dbHandler.selectSingleData<DbModelProductListGet>(sqlString, valueList.toTypedArray())
        }

        fun addOrInsert(mContext: Context, dbModelProductList: DbModelProductList): Boolean {
            val selectData: DbModelProductListGet? = getSingle(mContext, listOf("Product", "AcqCode", "AcqMid", "AcqTid", "QrProductCode", "EppProductCode"), arrayOf(dbModelProductList.Product, dbModelProductList.AcqCode, dbModelProductList.AcqMid, dbModelProductList.AcqTid, dbModelProductList.QrProductCode, dbModelProductList.EppProductCode))
            if(selectData != null){
                val valueHM = hashMapOf<Any, Any>(
                    "Product" to dbModelProductList.Product,
                    "AcqCode" to dbModelProductList.AcqCode,
                    "AcqMid" to dbModelProductList.AcqMid,
                    "AcqTid" to dbModelProductList.AcqTid,
                    "QrProductCode" to dbModelProductList.QrProductCode,
                    "ProductName" to dbModelProductList.ProductName,
                    "EppProductCode" to dbModelProductList.EppProductCode,
                    "EppTenure" to dbModelProductList.EppTenure,
                    "EppTenureCode" to dbModelProductList.EppTenureCode,
                    "IsSettlement" to dbModelProductList.IsSettlement,
                    "IsSettled" to "false",
                    "BatchNo" to dbModelProductList.BatchNo,
                    "IsTpaAccount" to dbModelProductList.IsTpaAccount,
                    "IsActive" to "true"
                )
                val criteriaHM = hashMapOf<Any, Any>(
                    "Id" to selectData.Id
                )
                updateData(mContext, valueHM, criteriaHM)
            } else {
                insertToDb(mContext, dbModelProductList)
            }

            return true
        }

        fun insertToDb(mContext: Context, dbModelProductList: DbModelProductList): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.PRODUCT_LIST, dbModelProductList)
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.PRODUCT_LIST, updateMap, criteriaMap) > 0
        }

        fun deleteInActiveProduct(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ProductList WHERE IsActive = ? "
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf("false")) > 0
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.PRODUCT_LIST)
        }
    }
}