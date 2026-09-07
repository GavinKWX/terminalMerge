package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig

class TerminalConfigurationRepo(){
    companion object{
        fun getAll(mContext: Context): List<DbModelTerminalConfig> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelTerminalConfig>>() {}.type
            return dbHandler.selectListData<DbModelTerminalConfig>(type, "SELECT * FROM TerminalConfiguration", arrayOf())
        }

        fun getSingle(mContext: Context): DbModelTerminalConfig? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelTerminalConfig>("SELECT * FROM TerminalConfiguration", arrayOf<String>())
        }

        fun insertToDb(mContext: Context, dbModelTerminalConfig: DbModelTerminalConfig): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.TERMINAL_CONFIGURATION, dbModelTerminalConfig)
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.TERMINAL_CONFIGURATION)
        }

        fun updateTerminalConfig(mContext: Context, updateMap: MutableMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val criteriaMap = mutableMapOf<Any,Any>()
            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.TERMINAL_CONFIGURATION, updateMap, criteriaMap) > 0
        }
    }
}