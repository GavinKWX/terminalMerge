package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
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

        fun insertToDb(mContext: Context, jsonString: String): Boolean {
            val className: String = ReceiptUploadRepo::javaClass.name
            val log = helpers.HelperLog(
                HelperCommon.getSession(),
                TmsHelper.checkIsConnectedWifi(mContext),
                Utils.getIPAddress(),
                "ReceiptUploadRepo",
                this.javaClass.simpleName,
                this.javaClass.simpleName
            )
            log.appendLine(className, "Insert to table Receipt Upload ------------------")
            log.appendLine(className, "Model :", jsonString)
            val dbHandler = DbHandler.getInstance(mContext)!!
            val temp = Gson().fromJson(jsonString, DbModelReceiptUpload::class.java)

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
            val inserted = dbHandler.insertToDb(DatabaseTables.RECEIPT_UPLOAD, temp)
            log.appendLine(className, "Insert to DB", inserted.toString())
            log.logToFile(EnumLogFileName.TerminaLog)
            return inserted
        }

        fun insertToDbByModel(mContext: Context, dbModelReceiptUpload: DbModelReceiptUpload): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.RECEIPT_UPLOAD, dbModelReceiptUpload)
        }

        fun updateData(mContext: Context, updateMap: HashMap<Any, Any>, criteriaMap: HashMap<Any, Any>): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            // D3 — report the real outcome; updateTableValue returns 0 on failure.
            return dbHandler.updateTableValue(DatabaseTables.RECEIPT_UPLOAD, updateMap, criteriaMap) > 0
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
                "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET IsProcessing = 'false' where Cast((JULIANDAY('$currDT') - JULIANDAY(LastUpdateDt)) *24 *60 As INTEGER) >= 30 AND IsSend = 'false' AND IsProcessing = 'true'",
                arrayOf()
            )
        }

        /**
         * F6a — unconditional age cap.
         *
         * [cleanOldRecord] only deletes rows that already uploaded (`IsSend`/`IsProcessing` true),
         * so a persistent upload failure — exactly what happens when the backend is unreachable or
         * the disk is full — keeps every row forever. This deletes by age regardless of upload
         * state, so the table cannot grow without bound. 30 days is well past any realistic
         * retry window; a row older than that is never going to upload.
         */
        fun cleanExpiredRecord(mContext: Context, days: Int = 30): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val where = "Cast((JULIANDAY('$currDT') - JULIANDAY(TXN_DT)) As INTEGER) > $days"
            val recordCount = dbHandler.executeScalar(
                "SELECT count(*) FROM ${DatabaseTables.RECEIPT_UPLOAD.id} where $where",
                arrayOf()
            )
            dbHandler.runningCustomSql(
                "DELETE from ${DatabaseTables.RECEIPT_UPLOAD.id} where $where",
                arrayOf()
            )
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