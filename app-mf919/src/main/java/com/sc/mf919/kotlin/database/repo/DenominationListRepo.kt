package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.DbModelDenominationListInsert

class DenominationListRepo {
    companion object {
        fun updateOrInsert(mContext: Context, dbModelDenominationInsert: DbModelDenominationListInsert): Boolean {
            val selectData: DbModelDenominationList? = getSingle(
                mContext,
                listOf("PackageId"),
                listOf(dbModelDenominationInsert.PackageId)
            )
            if(selectData != null){
                val valueHM = hashMapOf<Any, Any>(
                    "Desc" to dbModelDenominationInsert.Desc,
                    "Amount" to dbModelDenominationInsert.Amount,
                    "Ref1" to dbModelDenominationInsert.Ref1,
                    "Ref2" to dbModelDenominationInsert.Ref2,
                    "Ref3" to dbModelDenominationInsert.Ref3,
                    "Ref4" to dbModelDenominationInsert.Ref4,
                    "Ref5" to dbModelDenominationInsert.Ref5,
                    "Remark" to dbModelDenominationInsert.Remark,
                    "MaintenanceSchedule" to dbModelDenominationInsert.MaintenanceSchedule,
                    "DisplayMenutype" to dbModelDenominationInsert.DisplayMenutype,
                )
                val criteriaHM = hashMapOf<Any, Any>(
                    "Id" to selectData.Id
                )
                updateData(mContext, valueHM, criteriaHM)
            } else {
                insertToDb(mContext, dbModelDenominationInsert)
            }

            return true
        }

        fun getAll(mContext: Context): List<DbModelDenominationList> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sqlString = "SELECT * FROM ${DatabaseTables.DENOMINATION_LIST.id}"

            val type = object : TypeToken<List<DbModelDenominationList>>() {}.type
            return dbHandler.selectListData<DbModelDenominationList>(type, sqlString, arrayOf())
        }

        fun getSingle(mContext: Context, fieldList: List<String>, valueList: List<String>): DbModelDenominationList? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.DENOMINATION_LIST.id} WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            return dbHandler.selectSingleData<DbModelDenominationList>(sqlString, valueList.toTypedArray())
        }

        fun insertToDb(mContext: Context, dbModelDenominationInsert: DbModelDenominationListInsert): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.DENOMINATION_LIST, dbModelDenominationInsert)
            return true
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.updateTableValue(DatabaseTables.DENOMINATION_LIST, updateMap, criteriaMap)
            return true
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.DENOMINATION_LIST)
        }
    }
}