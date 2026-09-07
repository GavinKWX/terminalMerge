package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchLongInfo

class IsoBatchLongInfoRepo {
    companion object {
        fun getBatchLongInfo(mContext: Context, tag: String, subtag: String): DbModelIsoBatchLongInfo? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelIsoBatchLongInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_LONG_INFO.id} Where tag = '${tag}' and subtag = '${subtag}' ", arrayOf<String>())
        }

        fun updateBatchLongInfo(mContext: Context, value: String, tag: String, subtag: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val updateMap = mutableMapOf<Any,Any>(
                "value" to value
            )
            val criteriaMap = mutableMapOf<Any,Any>(
                "tag" to tag,
                "subtag" to subtag
            )

            dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_LONG_INFO, updateMap, criteriaMap)

            return true
        }

        fun insertToBatchLongInfo(mContext: Context, dbModelIsoBatchLongInfo: DbModelIsoBatchLongInfo) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_LONG_INFO, dbModelIsoBatchLongInfo)
        }
    }
}