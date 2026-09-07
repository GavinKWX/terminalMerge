package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelDenominationUpload
import com.sc.mf919pro.kotlin.database.model.DbModelDenominationUploadInsert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DenominationUploadRepo {
    companion object {
        fun getAll(mContext: Context): List<DbModelDenominationUpload> {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val type = object : TypeToken<List<DbModelDenominationUpload>>() {}.type
            return dbHandler.selectListData(type, "SELECT * FROM ${DatabaseTables.DENOMINATION_UPLOAD.id}", arrayOf())
        }

        fun insertToDbByModel(mContext: Context, dbmodelDenominationUpload: DbModelDenominationUploadInsert): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.DENOMINATION_UPLOAD, dbmodelDenominationUpload)
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.DENOMINATION_UPLOAD, updateMap, criteriaMap) > 0
        }

        /**
         * Release rows whose upload was interrupted, so they become retryable.
         *
         * The unit bug: JULIANDAY returns DAYS, so the old `*60 ... >= 1` made one unit 1/60 of a
         * day = 24 MINUTES, not one minute. The intent was 1 hour. Deliberately set to 30 minutes
         * (`*24 *60 >= 30`, the same idiom as TmsReceiptUploadScheduler): an orphaned receipt
         * becomes retryable sooner after a process kill, while staying clear of a legitimately slow
         * upload -- reclaiming too early would upload the same receipt twice.
         */
        fun findDeadRecord(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            return dbHandler.runningCustomSql(
                "UPDATE ${DatabaseTables.DENOMINATION_UPLOAD.id} SET IsProcessing = 'false' where Cast((JULIANDAY('$currDT') - JULIANDAY(LastUpdateDt)) *24 *60 As INTEGER) >= 30 AND IsSend = 'false' AND IsProcessing = 'true'",
                arrayOf()
            )
        }

        /** F6a — unconditional age cap; see ReceiptUploadRepo.cleanExpiredRecord for why. */
        fun cleanExpiredRecord(mContext: Context, days: Int = 30): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val where = "Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) As INTEGER) > $days"
            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.DENOMINATION_UPLOAD.id} where $where",
                arrayOf()
            )
            dbHandler.runningCustomSql(
                "DELETE FROM ${DatabaseTables.DENOMINATION_UPLOAD.id} where $where",
                arrayOf()
            )
            return recordCount
        }

        fun cleanOldRecord(mContext: Context): Int  {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.DENOMINATION_UPLOAD.id} where Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) As INTEGER) > 1 AND IsSend = 'true' AND IsProcessing = 'true'",
                arrayOf()
            )
            dbHandler.runningCustomSql(
                "DELETE FROM ${DatabaseTables.DENOMINATION_UPLOAD.id} where Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) As INTEGER) > 1 AND IsSend = 'true' AND IsProcessing = 'true'",
                arrayOf()
            )
            return recordCount
        }

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.DENOMINATION_UPLOAD)
        }
    }
}