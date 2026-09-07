package com.sc.mf919pro.kotlin.scheduler

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import database.DbHandler
import com.sc.mf919pro.kotlin.database.repo.BatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.DenominationUploadRepo
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class HouseKeepingReceiptUploadScheduler(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    val mContext = appContext

    override fun doWork(): Result {
        val className: String = HouseKeepingReceiptUploadScheduler::javaClass.name
        val log = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            "HouseKeepingReceiptUploadScheduler",
            this.javaClass.simpleName,
            this.javaClass.simpleName
        )

        log.appendLine(className, "Scheduled House Keeping - Triggered")
        try {
            log.appendLine(className, "Find lastUpdate stuck more than 30 minutes ago which IsSend = false and IsProcessing = true")
            val fixDeadRecord = ReceiptUploadRepo.findDeadRecord(mContext)
            log.appendLine(className, "Record Modified: ", fixDeadRecord.toString())

            log.appendLine(className, "Find Record old than 7 days")
            val cleanOldRecord = ReceiptUploadRepo.cleanOldRecord(mContext)
            log.appendLine(className, "Record Deleted: ", cleanOldRecord.toString())

            // F6a — the deletes above are gated on IsSend/IsProcessing, so rows that never
            // uploaded are kept forever. Cap by age regardless of upload state.
            log.appendLine(className, "Age cap: receipts older than 30 days, regardless of upload state")
            val expiredReceipts = ReceiptUploadRepo.cleanExpiredRecord(mContext)
            log.appendLine(className, "Expired Receipts Deleted: ", expiredReceipts.toString())

            // DenominationUploadRepo.cleanOldRecord existed but was never called from anywhere —
            // that table had no housekeeping at all.
            val cleanOldDenomination = DenominationUploadRepo.cleanOldRecord(mContext)
            log.appendLine(className, "Denomination Uploaded Records Deleted: ", cleanOldDenomination.toString())
            val expiredDenomination = DenominationUploadRepo.cleanExpiredRecord(mContext)
            log.appendLine(className, "Expired Denomination Deleted: ", expiredDenomination.toString())

            // F6b — DELETE frees pages inside the file but never returns them to the filesystem.
            // Now that WAL is gone (ADR-005) incremental_vacuum actually takes effect.
            val reclaimed = DbHandler.getInstance(mContext).reclaimFreePages()
            log.appendLine(className, "Free pages reclaimed: ", reclaimed.toString())

            log.appendLine(className, "------Finished------")
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            log.appendLine(className, "Exception: ", e.toString())
            log.logToFile(EnumLogFileName.TerminaLogException)
        }

        log.appendLine(className, "Housekeeping check for Run Settlement before continue on Next Day Sales")

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        ServiceHolder.clearSettlementBatch = false

        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT") || DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")) {
            val batchTableData = BatchTableRepo.getFirstData(mContext)
            log.appendLine(className, "Data Obtain -> ", Gson().toJson(batchTableData))
            batchTableData?.let {
                //TODO FORCE_SETTLEMENT_DAILY
                if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                    try {
                        val currentDate = Date()
                        // Remove time (set hours, minutes, seconds, milliseconds to zero) for currentDate
                        val calendarCurrent = Calendar.getInstance().apply {
                            time = currentDate
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                        val inputDate = sdf.parse(it.postingDt)!!
                        val calendarInput = Calendar.getInstance().apply {
                            time = inputDate
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val diffInMillis = calendarCurrent.timeInMillis - calendarInput.timeInMillis
                        //val diffInDay = diffInMillis / (24 * 60 * 60 * 1000)
                        val diffInDay = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS)
                        println("diffInDay >> $diffInDay")
                        if(diffInDay >= 1) {
                            log.appendLine(className, "Detected Batch more than 1 days")
                            log.appendLine(className, "clearSettlementBatch -> true")
                            ServiceHolder.clearSettlementBatch = true
                        }
                        log.appendLine(className, "------Finished (FORCE_SETTLEMENT_DAILY) ------")
                        log.logToFile(EnumLogFileName.TerminaLog)
                    } catch (e: Exception) {
                        log.appendLine(className, "(FORCE_SETTLEMENT_DAILY) Exception: ", e.toString())
                        log.logToFile(EnumLogFileName.TerminaLogException)
                    }
                }

                //TODO FORCE_SETTLEMENT
                if (!ServiceHolder.clearSettlementBatch && DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT")){
                    try {
                        val currentDT = Date()

                        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                        val dataDT = sdf.parse(it.postingDt)!!

                        val diffInMillis = abs(currentDT.time - dataDT.time)
                        val diffInHour = TimeUnit.HOURS.convert(diffInMillis, TimeUnit.MILLISECONDS);

                        if(diffInHour >= 20){
                            log.appendLine(className, "Detected Batch more than 20 hour")
                            log.appendLine(className, "clearSettlementBatch -> true")
                            ServiceHolder.clearSettlementBatch = true
                        }

                        log.appendLine(className, "------Finished (FORCE_SETTLEMENT)------")
                        log.logToFile(EnumLogFileName.TerminaLog)
                    } catch (e: Exception) {
                        log.appendLine(className, "(FORCE_SETTLEMENT) Exception: ", e.toString())
                        log.logToFile(EnumLogFileName.TerminaLogException)
                    }
                }
            }
        }
        return Result.success()
    }
}