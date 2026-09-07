package com.sc.mf919pro.kotlin.scheduler

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.AppServices
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
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
        helperLog.appendLine(className, "Settlement Recovery Scheduler - Triggered")
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
                }
            } else {
                helperLog.appendLine(className, "Auto Settlement is not enabled.")
            }
            helperLog.appendLine(className, "Settlement Recovery Scheduler [End]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: Exception) {
            helperLog.appendLine(className, "Exception: ", e.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
        return Result.success()
    }
}