package com.sc.mf919.kotlin.scheduler

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.activity.AppServices
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SettlementRecoveryScheduler(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    val mContext = appContext

    override fun doWork(): Result {
        val className: String = SettlementRecoveryScheduler::class.java.name
        val helperLog = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            "SettlementRecoveryScheduler",
            this.javaClass.simpleName,
            this.javaClass.simpleName
        )
        /*
         * This job decides whether a missed auto-settlement gets queued to run. The whole run is
         * a handful of lines, so one segment covers it -- but it has to reach disk on every exit
         * path, including the exception path, because "no settlement happened and there is no log"
         * is indistinguishable from "the job never ran".
         */
        helperLog.appendLine(className, "Settlement Recovery Scheduler [START] :: Triggered")
        try {
            val terminalConfig = ServiceHolder.getTerminalConfig()
            if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "AutoSettle")) {
                helperLog.appendLine(className, "Auto Settlement is enabled.")
                val sharedPreferences: SharedPreferences = Helper.getInstance().getPrefs(mContext)
                val cal = Calendar.getInstance()
                val lastSettlementDate = sharedPreferences.getString(AppServices.LAST_SETTLE_TAG, null) ?: run {
                    helperLog.appendLine(className, "LastSettlementTag Not Found Default to today date")
                    SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(cal.time)
                }
                helperLog.appendLine(className, "Last Settlement Date :: $lastSettlementDate")

                cal.add(Calendar.DAY_OF_YEAR, -1)
                val yesterdayDate = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(cal.time)
                helperLog.appendLine(className, "Yesterday Date :: $yesterdayDate")

                if(lastSettlementDate < yesterdayDate) {
                    helperLog.appendLine(className, "AutoSettlement Missed Two Day")
                    ServiceHolder.autoSettlementQueue = true
                    ServiceHolder.autoSettlementPastDate = true
                    helperLog.appendLine(className, "DECISION :: queue past-date settlement (autoSettlementQueue[true] autoSettlementPastDate[true])")
                } else {
                    // The negative decision matters as much as the positive one: it is the answer to
                    // "why did the terminal not recover the missed settlement".
                    helperLog.appendLine(className, "DECISION :: last settlement is recent enough ($lastSettlementDate >= $yesterdayDate), nothing queued")
                }
            } else {
                helperLog.appendLine(className, "Auto Settlement is not enabled.")
                helperLog.appendLine(className, "DECISION :: AutoSettle config off, no recovery possible")
            }
            helperLog.appendLine(className, "Settlement Recovery Scheduler [End] :: autoSettlementQueue[${ServiceHolder.autoSettlementQueue}] autoSettlementPastDate[${ServiceHolder.autoSettlementPastDate}] result[SUCCESS]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            helperLog.appendLine(className, "Exception: ", e.toString())
            helperLog.appendLine(className, "Settlement Recovery Scheduler [End] :: outcome[EXCEPTION] autoSettlementQueue[${ServiceHolder.autoSettlementQueue}] result[SUCCESS - not retried]")
            /*
             * TerminaLogException only. logToFile clears the buffer, so the previous
             * "TerminaLog then TerminaLogException" pair wrote the block to TerminaLog and then
             * nothing to the exception stream -- the second call hit its isEmpty() early return.
             * Every line is already in TerminaLog through the per-line sink, so flushing the block
             * to the synchronous exception stream loses nothing and survives a process kill.
             */
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
        return Result.success()
    }
}