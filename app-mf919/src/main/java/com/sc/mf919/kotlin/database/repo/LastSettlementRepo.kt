package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelLastSettlement
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelProductListGet
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig

class LastSettlementRepo {
    companion object{
        fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelLastSettlement? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM LastSettlement WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            return dbHandler.selectSingleData(sqlString, valueList)
        }

        fun addOrInsert(mContext: Context, dbModelLastSettlement: DbModelLastSettlement): Boolean {
            val selectData: DbModelLastSettlement? = getSingle(
                mContext,
                listOf("mid", "tid"),
                arrayOf(
                    dbModelLastSettlement.mid,
                    dbModelLastSettlement.tid
                )
            )
            if(selectData != null){
                val valueHM = hashMapOf<Any, Any>(
                    "settleInfo" to dbModelLastSettlement.settleInfo,
                    "isTpaAccount" to dbModelLastSettlement.isTpaAccount,
                    "settleDt" to dbModelLastSettlement.settleDt
                )
                val criteriaHM = hashMapOf<Any, Any>(
                    "mid" to dbModelLastSettlement.mid,
                    "tid" to dbModelLastSettlement.tid
                )
                updateData(mContext, valueHM, criteriaHM)
                println("LastSettlement - UPDATE data complete: $dbModelLastSettlement")

            } else {
                insertToDb(mContext, dbModelLastSettlement)
                println("LastSettlement - INSERT data complete: $dbModelLastSettlement")
            }
            return true
        }

        fun getAll(mContext: Context): List<DbModelLastSettlement> {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "SELECT * FROM LastSettlement ORDER BY settleDt DESC"

            val type = object : TypeToken<List<DbModelLastSettlement>>() {}.type
            return dbHandler.selectListData(type, sqlString, arrayOf())

        }

        fun insertToDb(mContext: Context, dbModelLastSettlement: DbModelLastSettlement): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.LAST_SETTLEMENT, dbModelLastSettlement)

            return true
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            dbHandler.updateTableValue(DatabaseTables.LAST_SETTLEMENT, updateMap, criteriaMap)
            return true
        }
    }
}