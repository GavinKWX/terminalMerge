package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelIsoBatchLongInfo

class IsoBatchLongInfoRepo {
    companion object {
        fun getBatchLongInfo(mContext: Context, tag: String, subtag: String): DbModelIsoBatchLongInfo? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelIsoBatchLongInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_LONG_INFO.id} Where tag = ? and subtag = ? ", arrayOf<String>(tag, subtag))
        }

        fun updateBatchLongInfo(mContext: Context, value: String, tag: String, subtag: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val preSelect = dbHandler.selectSingleData<DbModelIsoBatchLongInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_LONG_INFO.id} Where tag = ? and subtag = ? ", arrayOf<String>(tag, subtag))
            if(preSelect == null) {
                val dbModelIsoBatchLongInfo = DbModelIsoBatchLongInfo(tag, subtag, value)
                return dbHandler.insertToDb(DatabaseTables.ISO_BATCH_LONG_INFO, dbModelIsoBatchLongInfo)
            } else {
                val updateMap = mutableMapOf<Any,Any>(
                    "value" to value
                )
                val criteriaMap = mutableMapOf<Any,Any>(
                    "tag" to tag,
                    "subtag" to subtag
                )
                // updateTableValue returns 0 on failure; do not launder that into true.
                return dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_LONG_INFO, updateMap, criteriaMap) > 0
            }
        }

        fun insertToBatchLongInfo(mContext: Context, dbModelIsoBatchLongInfo: DbModelIsoBatchLongInfo) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_LONG_INFO, dbModelIsoBatchLongInfo)
        }
    }
}