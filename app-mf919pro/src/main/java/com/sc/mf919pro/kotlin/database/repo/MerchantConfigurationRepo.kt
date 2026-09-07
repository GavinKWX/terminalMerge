package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig

class MerchantConfigurationRepo(){
    companion object{
        fun getAll(mContext: Context): List<DbModelMerchantConfig> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelMerchantConfig>>() {}.type
            return dbHandler.selectListData<DbModelMerchantConfig>(type, "SELECT * FROM MerchantConfiguration", arrayOf())
        }

        fun getSingle(mContext: Context): DbModelMerchantConfig? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelMerchantConfig>("SELECT * FROM MerchantConfiguration", arrayOf<String>())
        }

        fun insertToDb(mContext: Context, dbModelMerchantConfig: DbModelMerchantConfig): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.MERCHANT_CONFIGURATION, dbModelMerchantConfig)
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.MERCHANT_CONFIGURATION)
        }

        fun updateMerchantConfig(mContext: Context, updateMap: MutableMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val criteriaMap = mutableMapOf<Any,Any>()
            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.MERCHANT_CONFIGURATION, updateMap, criteriaMap) > 0
        }
    }
}