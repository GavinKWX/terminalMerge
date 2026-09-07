package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelBatchTable
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import java.text.SimpleDateFormat
import java.util.*

class ReceiptUploadRepo(){
    companion object{
        fun getAll(mContext: Context): List<DbModelReceiptUpload> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelReceiptUpload>>() {}.type
            return dbHandler.selectListData<DbModelReceiptUpload>(type, "SELECT * FROM ${DatabaseTables.RECEIPT_UPLOAD.id}", arrayOf())
        }

        fun getSingleDesc(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelReceiptUpload? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.RECEIPT_UPLOAD.id}"
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            //sqlString += " ORDER BY SEQ_NO DESC"
            sqlString += " ORDER BY TXN_DT DESC"
            println("ReceiptUploadRepo -> $sqlString")
            return dbHandler.selectSingleData<DbModelReceiptUpload>(sqlString, valueList)
        }

        /**
         * Finds the unsettled batchTable record for the given RRN + Approval Code.
         *
         * batchTable itself does not store RRN/Approval Code, so we locate the
         * transaction through receiptUpload (which does), then INNER JOIN back to
         * batchTable to return the batch record - including batchData needed for
         * downstream processing. The join also guarantees the transaction is still
         * unsettled (settled records are cleared from batchTable).
         */
        fun getUnsettledBatchByRrnAndApprCode(mContext: Context, rrn: String, apprCode: String): DbModelBatchTable? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sqlString = "SELECT b.* FROM ${DatabaseTables.RECEIPT_UPLOAD.id} r " +
                "INNER JOIN ${DatabaseTables.BATCH_TABLE.id} b " +
                "ON r.INV_NO = b.invNo AND r.BATCH_NO = b.batchNo " +
                "WHERE r.RRN = ? AND r.APPR_CODE = ? " +
                "ORDER BY r.TXN_DT DESC"
            println("ReceiptUploadRepo -> $sqlString")
            return dbHandler.selectSingleData<DbModelBatchTable>(sqlString, arrayOf(rrn, apprCode))
        }

        fun insertToDb(mContext: Context, jsonString: String): Boolean {
            val className: String = ReceiptUploadRepo::class.java.name
            val log = helpers.HelperLog(
                HelperCommon.getSession(),
                TmsHelper.checkIsConnectedWifi(mContext),
                Utils.getIPAddress(),
                "ReceiptUploadRepo",
                this.javaClass.simpleName,
                this.javaClass.simpleName
            )
            log.appendLine(className, "Insert to table Receipt Upload ------------------")
            log.appendLine(className, "Json: ", jsonString)
            val dbHandler = DbHandler.getInstance(mContext)!!
            val temp = Gson().fromJson(jsonString, DbModelReceiptUpload::class.java)
            log.appendLine(className, "Model: ", temp.toString())

            if(temp.TXN_TYPE.isNullOrEmpty()){
                log.appendLine(className, "Invalid Transaction detected skip insert to DB")
                log.logToFile(EnumLogFileName.TerminaLogException)
                return true
            }
            log.appendLine(className, "Setting Default value of CreationDate, LastUpdateDt, IsProcessing, IsSend")
            temp.CreationDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            temp.LastUpdateDt = ""
            temp.IsProcessing = "false"
            temp.IsSend = "false"
            log.appendLine(className, "After Update: ", temp.toString())
            dbHandler.insertToDb(DatabaseTables.RECEIPT_UPLOAD, temp)
            log.appendLine(className, "Insert to DB")
            log.logToFile(EnumLogFileName.TerminaLog)
            return true
        }

        fun insertToDbByModel(mContext: Context, dbModelReceiptUpload: DbModelReceiptUpload): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.RECEIPT_UPLOAD, dbModelReceiptUpload)
            return true
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            dbHandler.updateTableValue(DatabaseTables.RECEIPT_UPLOAD, updateMap, criteriaMap)
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
                "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET IsProcessing = 'false' where Cast((JULIANDAY('$currDT') - JULIANDAY(LastUpdateDt)) *24 *60 As INTEGER) >= 30 AND IsSend = 'false' AND IsProcessing = 'true'",
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
            val where = "Cast((JULIANDAY('$currDT') - JULIANDAY(TXN_DT)) As INTEGER) > $maxAgeDays"

            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.RECEIPT_UPLOAD.id} where $where", arrayOf()
            )
            if (recordCount > 0) {
                dbHandler.runningCustomSql(
                    "DELETE from ${DatabaseTables.RECEIPT_UPLOAD.id} where $where", arrayOf()
                )
            }
            return recordCount
        }

        fun cleanOldRecord(mContext: Context): Int  {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.RECEIPT_UPLOAD.id} where Cast((JULIANDAY('$currDT') - JULIANDAY(TXN_DT)) As INTEGER) > 7 AND IsSend = 'true' AND IsProcessing = 'true'",
                arrayOf()
            )

            dbHandler.runningCustomSql(
                "DELETE from ${DatabaseTables.RECEIPT_UPLOAD.id} where Cast((JULIANDAY('$currDT') - JULIANDAY(TXN_DT)) As INTEGER) > 7 AND IsSend = 'true' AND IsProcessing = 'true'",
                arrayOf()
            )
            return recordCount
        }
    }
}