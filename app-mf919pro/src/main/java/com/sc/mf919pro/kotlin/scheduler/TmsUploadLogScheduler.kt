package com.sc.mf919pro.kotlin.scheduler

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon

class TmsUploadLogScheduler(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    val mContext = appContext
    val className: String = TmsUploadLogScheduler::javaClass.name

    override fun doWork(): Result {
        val log = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Auto Upload Log Job - Triggered",
            this@TmsUploadLogScheduler.javaClass.simpleName,
            this@TmsUploadLogScheduler.javaClass.name
        )

        try {
            val response = TmsHelper.uploadAllTerminalLog(log, mContext, false)
            log.appendLine(className, "Job Result: ", response.toString())
        } catch (e: Exception) {
            log.appendLine(className, "Exception: ", e.toString())
        }
        log.logToFile(EnumLogFileName.TerminaLog)

        return Result.success()
    }
}