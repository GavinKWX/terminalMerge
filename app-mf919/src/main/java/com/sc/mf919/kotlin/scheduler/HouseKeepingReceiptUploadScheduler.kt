package com.sc.mf919.kotlin.scheduler

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.BatchTableRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.DenominationUploadRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.SettlementSummaryRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
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
        val className: String = HouseKeepingReceiptUploadScheduler::class.java.name
        val log = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            "HouseKeepingReceiptUploadScheduler",
            this.javaClass.simpleName,
            this.javaClass.simpleName
        )

        log.appendLine(className, "Scheduled House Keeping [START] :: Triggered")
        /*
         * Everything in this block DESTROYS rows. The counts alone do not say what went, so each
         * line now carries the criteria the repo actually runs -- that plus the count is enough to
         * reconstruct what was deleted from a terminal you no longer have in front of you.
         */
        var deadFixed = -1
        var oldDeleted = -1
        var expiredReceiptsDeleted = -1
        var expiredDenomDeleted = -1
        try {
            log.appendLine(className, "-----------------Receipt Table House Keeping [START]-------------------->")
            log.appendLine(className, "Reset receipts whose upload was claimed and never finished")
            /*
             * The SQL now reads days *24 *60 >= 30 -- the same idiom as TmsReceiptUploadScheduler.
             * It used to be *60 >= 1, which omits the *24 and so reclaimed at 24 minutes while this
             * log claimed one hour. Intent was one hour, deliberately shortened to 30 minutes.
             */
            log.appendLine(className, "Criteria (reset stuck) :: LastUpdateDt older than 30 minutes (days*24*60 >= 30) AND IsSend[false] AND IsProcessing[true] -> IsProcessing[false]")
            val fixDeadRecord = ReceiptUploadRepo.findDeadRecord(mContext)
            deadFixed = fixDeadRecord
            log.appendLine(className, "Record Modified: ", fixDeadRecord.toString())

            log.appendLine(className, "Find Record old than 7 days")
            log.appendLine(className, "Criteria (DELETE uploaded) :: ReceiptUpload TXN_DT older than 7 days AND IsSend[true] AND IsProcessing[true]")
            val cleanOldRecord = ReceiptUploadRepo.cleanOldRecord(mContext)
            oldDeleted = cleanOldRecord
            log.appendLine(className, "Record Deleted: ", cleanOldRecord.toString())

            // cleanOldRecord above only removes rows that already uploaded, so a terminal with a
            // persistent upload failure keeps every row forever and quietly fills /data. Age them out
            // unconditionally as a backstop -- TMS will not accept them at this point anyway.
            // See obsidian FIX-2026-08-05 (MF919 Phase 2, D6).
            log.appendLine(className, "Criteria (DELETE expired receipts) :: ReceiptUpload TXN_DT older than 30 days, ANY IsSend/IsProcessing state")
            val expiredReceipts = ReceiptUploadRepo.cleanExpiredRecord(mContext)
            expiredReceiptsDeleted = expiredReceipts
            log.appendLine(className, "Expired receipts deleted (any IsSend state): ", expiredReceipts.toString())
            log.appendLine(className, "Criteria (DELETE expired denominations) :: DenominationUpload CreationDate older than 30 days, ANY state")
            val expiredDenom = DenominationUploadRepo.cleanExpiredRecord(mContext)
            expiredDenomDeleted = expiredDenom
            log.appendLine(className, "Expired denomination uploads deleted: ", expiredDenom.toString())
            log.appendLine(
                className,
                "-----------------Receipt Table House Keeping [END] :: stuckReset[$deadFixed] deletedUploaded7d[$oldDeleted] deletedExpiredReceipts30d[$expiredReceiptsDeleted] deletedExpiredDenom30d[$expiredDenomDeleted]-------------------->"
            )
            log.appendLine(className, "------Finished------")
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            log.appendLine(className, "Exception: ", e.toString())
            // Partial-delete accounting: -1 means that step never ran, so the run can be told
            // apart from one where the step ran and deleted nothing.
            log.appendLine(
                className,
                "-----------------Receipt Table House Keeping [END - EXCEPTION] :: stuckReset[$deadFixed] deletedUploaded7d[$oldDeleted] deletedExpiredReceipts30d[$expiredReceiptsDeleted] deletedExpiredDenom30d[$expiredDenomDeleted] (-1 = step not reached)-------------------->"
            )
            /*
             * TerminaLogException only. logToFile clears the buffer, so the previous
             * "TerminaLog then TerminaLogException" pair flushed the block to TerminaLog and then
             * wrote nothing to the exception stream (second call hit the isEmpty() early return).
             * Every line is already in TerminaLog through the per-line sink, so sending the block
             * to the synchronous exception stream loses nothing and survives a kill.
             */
            log.logToFile(EnumLogFileName.TerminaLogException)
        }

        log.appendLine(className, "Housekeeping check for Run Settlement before continue on Next Day Sales")

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        ServiceHolder.clearSettlementBatch = false

        val forceSettlement = DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT")
        val forceSettlementDaily = DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")
        log.appendLine(className, "Config :: FORCE_SETTLEMENT[$forceSettlement] FORCE_SETTLEMENT_DAILY[$forceSettlementDaily]")

        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT") || DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")) {
            val batchTableData = BatchTableRepo.getFirstData(mContext)
            /*
             * Was Gson().toJson(batchTableData) -- which serialises DbModelBatchTable.batchData, the
             * raw hex TLV of the original transaction. AdjustmentActivity decodes the card number
             * straight out of that field, so the old line put a full PAN in TerminaLog and then
             * shipped it to TMS. Only the fields this branch actually uses are logged now, plus the
             * blob's length so a truncated/empty batch record is still diagnosable.
             */
            val batchSummary = if (batchTableData == null) {
                "no batch record"
            } else {
                "postingDt[${batchTableData.postingDt}] batchNo[${batchTableData.batchNo}] txnType[${batchTableData.txnType}] stan[${batchTableData.stan}] invNo[${batchTableData.invNo}] status[${batchTableData.status}] tid[${batchTableData.tid}] batchDataLen[${batchTableData.batchData.length}]"
            }
            log.appendLine(className, "Data Obtain -> ", batchSummary)
            if (batchTableData == null) {
                // Early-exit path that used to leave nothing on disk until the repair phase flushed.
                log.appendLine(className, "DECISION :: no batch record found, nothing to force-settle")
                log.logToFile(EnumLogFileName.TerminaLog)
            }
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
                        // Was println: invisible on an unattended terminal, and the day count is the
                        // whole basis of the force-settlement decision below.
                        log.appendLine(className, "Batch postingDt[${it.postingDt}] diffInDay >> $diffInDay (threshold >= 1)")
                        if(diffInDay >= 1) {
                            log.appendLine(className, "Detected Batch more than 1 days")
                            log.appendLine(className, "DECISION :: clearSettlementBatch -> true")
                            ServiceHolder.clearSettlementBatch = true
                        } else {
                            log.appendLine(className, "DECISION :: batch is same day, clearSettlementBatch stays false")
                        }
                        log.appendLine(className, "------Finished (FORCE_SETTLEMENT_DAILY) :: clearSettlementBatch[${ServiceHolder.clearSettlementBatch}] ------")
                        log.logToFile(EnumLogFileName.TerminaLog)
                    } catch (e: Exception) {
                        // A parse failure here silently leaves clearSettlementBatch false, i.e. no
                        // forced settlement - so this catch must reach the durable stream.
                        log.appendLine(className, "(FORCE_SETTLEMENT_DAILY) Exception: ", e.toString())
                        log.appendLine(className, "DECISION :: check aborted, clearSettlementBatch[${ServiceHolder.clearSettlementBatch}]")
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
                        log.appendLine(className, "Batch postingDt[${it.postingDt}] diffInHour >> $diffInHour (threshold >= 20)")

                        if(diffInHour >= 20){
                            log.appendLine(className, "Detected Batch more than 20 hour")
                            log.appendLine(className, "DECISION :: clearSettlementBatch -> true")
                            ServiceHolder.clearSettlementBatch = true
                        } else {
                            log.appendLine(className, "DECISION :: batch younger than 20 hour, clearSettlementBatch stays false")
                        }

                        log.appendLine(className, "------Finished (FORCE_SETTLEMENT) :: clearSettlementBatch[${ServiceHolder.clearSettlementBatch}]------")
                        log.logToFile(EnumLogFileName.TerminaLog)
                    } catch (e: Exception) {
                        log.appendLine(className, "(FORCE_SETTLEMENT) Exception: ", e.toString())
                        log.appendLine(className, "DECISION :: check aborted, clearSettlementBatch[${ServiceHolder.clearSettlementBatch}]")
                        log.logToFile(EnumLogFileName.TerminaLogException)
                    }
                }
            }
        } else {
            // Neither flag set: no force-settlement evaluation happens at all. Recorded so the
            // absence of the decision lines above is explained rather than looking like a
            // truncated run.
            log.appendLine(className, "DECISION :: FORCE_SETTLEMENT / FORCE_SETTLEMENT_DAILY both off, skip force settlement check")
            log.logToFile(EnumLogFileName.TerminaLog)
        }

        repairSettlementState(log, className)
        log.appendLine(className, "Scheduled House Keeping [END] :: clearSettlementBatch[${ServiceHolder.clearSettlementBatch}] autoSettlementQueue[${ServiceHolder.autoSettlementQueue}] result[SUCCESS]")
        log.logToFile(EnumLogFileName.TerminaLog)
        return Result.success()
    }

    /**
     * Detects and repairs a corrupted settlement state left behind when the app is
     * killed mid-way through IsoActivity.initNewBatchNo (after is_settle is set to
     * "true" for the last product but before the final cleanup resets all rows to
     * "false"). In that state every settlement-enabled product is marked settled,
     * getUnSettledProduct() returns empty, and manual/auto settlement can no longer run.
     */
    private fun repairSettlementState(log: helpers.HelperLog, className: String) {
        log.appendLine(className, "-----------------Settlement State Repair [START]-------------------->")
        try {
            if (ServiceHolder.autoSettlementIsRunning || ServiceHolder.appRunningProcess) {
                log.appendLine(className, "Settlement/Transaction in progress, skip check :: autoSettlementIsRunning[${ServiceHolder.autoSettlementIsRunning}] appRunningProcess[${ServiceHolder.appRunningProcess}]")
                log.appendLine(className, "-----------------Settlement State Repair [END] :: skipped-------------------->")
                log.logToFile(EnumLogFileName.TerminaLog)
                return
            }

            val unSettledCount = ProductListRepo.getUnSettledProduct(mContext).size
            val settledCount = ProductListRepo.getCountSettledProduct(mContext)
            log.appendLine(className, "UnSettled Product :: $unSettledCount, Settled Product :: $settledCount")

            // No settlement is running, yet every settlement-enabled product is stuck
            // flagged as settled. A completed settlement always resets is_settle to
            // "false" for all rows, so this state only exists after an interrupted cleanup.
            if (unSettledCount == 0 && settledCount > 0) {
                log.appendLine(className, "Corrupted Settlement State Detected, resetting is_settle flags")
                // Blanket UPDATE with an empty criteria map: every settlementSummary row is reset.
                // Logged with the count it is about to affect so the write is accountable.
                log.appendLine(className, "UPDATE settlementSummary SET is_settle[false] :: no criteria (all rows), settled rows before[$settledCount]")
                SettlementSummaryRepo.updateData(mContext, hashMapOf("is_settle" to "false"), hashMapOf())
                log.appendLine(className, "Repair Completed")

                val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
                if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "AutoSettle")) {
                    log.appendLine(className, "Auto Settlement is enabled, queue settlement to rerun")
                    ServiceHolder.autoSettlementQueue = true
                    ServiceHolder.autoSettlementPastDate = true
                } else {
                    log.appendLine(className, "Auto Settlement is not enabled, settlement can be rerun manually")
                }
            } else {
                log.appendLine(className, "Settlement State is Healthy")
            }
            log.appendLine(
                className,
                "-----------------Settlement State Repair [END] :: unSettled[$unSettledCount] settled[$settledCount] repaired[${unSettledCount == 0 && settledCount > 0}] autoSettlementQueue[${ServiceHolder.autoSettlementQueue}]-------------------->"
            )
            log.appendLine(className, "------Finished (Settlement State Repair)------")
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            log.appendLine(className, "(Settlement State Repair) Exception: ", e.toString())
            log.appendLine(className, "-----------------Settlement State Repair [END] :: EXCEPTION, state unchanged-------------------->")
            log.logToFile(EnumLogFileName.TerminaLogException)
        }
    }
}