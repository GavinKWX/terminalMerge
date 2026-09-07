package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelDenominationUpload
import com.sc.mf919.kotlin.database.model.DbModelDenominationUploadInsert
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
            dbHandler.insertToDb(DatabaseTables.DENOMINATION_UPLOAD, dbmodelDenominationUpload)
            return true
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.updateTableValue(DatabaseTables.DENOMINATION_UPLOAD, updateMap, criteriaMap)
            return true
        }

        /**
         * Reclaim receipts a previous upload attempt claimed and never finished.
         *
         * Threshold is 30 MINUTES, written in the same idiom as the query in
         * TmsReceiptUploadScheduler: JULIANDAY() returns DAYS, so days must be multiplied by
         * 24 * 60 to get minutes.
         *
         * It previously read `*60 ... >= 1`, which omits the *24 and therefore made one unit a
         * sixtieth of a DAY -- 24 minutes -- while the surrounding log claimed "1 hour". The
         * intent was one hour; it is deliberately shortened to 30 minutes so an orphaned receipt
         * becomes retryable sooner after a process kill, which is the case that stranded these
         * rows in the first place. 30 stays well clear of a legitimately slow upload, so a
         * receipt still in flight is not reclaimed and uploaded twice.
         */
        fun findDeadRecord(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            return dbHandler.runningCustomSql(
                "UPDATE ${DatabaseTables.DENOMINATION_UPLOAD.id} SET IsProcessing = 'false' where Cast((JULIANDAY('$currDT') - JULIANDAY(LastUpdateDt)) *24 *60 As INTEGER) >= 30 AND IsSend = 'false' AND IsProcessing = 'true'",
                arrayOf()
            )
        }

        /**
         * Unconditional age cap — independent of IsSend / IsProcessing.
         *
         * cleanOldRecord() only deletes rows that already uploaded successfully, so on a terminal with
         * a persistent upload failure NOTHING is ever removed and this table grows without bound. That
         * is a direct contributor to filling /data, which is the trigger for the whole ENOSPC failure
         * class. Rows this old will not be accepted by TMS anyway, so retaining them costs space and
         * buys nothing.
         *
         * Deliberately a much longer window than cleanOldRecord's: this is a backstop, not the normal
         * path, and it must not race the retry logic. Aisino's F6a used 14 days; 30 is used here to
         * leave more room for a merchant offline over a long weekend before anything is discarded.
         * See obsidian FIX-2026-08-04 (D6/F6a) and FIX-2026-08-05 (MF919 Phase 2).
         */
        fun cleanExpiredRecord(mContext: Context, maxAgeDays: Int = 30): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val where = "Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) As INTEGER) > $maxAgeDays"

            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.DENOMINATION_UPLOAD.id} where $where", arrayOf()
            )
            if (recordCount > 0) {
                dbHandler.runningCustomSql(
                    "DELETE from ${DatabaseTables.DENOMINATION_UPLOAD.id} where $where", arrayOf()
                )
            }
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