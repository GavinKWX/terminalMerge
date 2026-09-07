package com.sc.mf919pro.kotlin.scheduler

import android.content.Context
import android.os.Looper
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919pro.java.activity.Tms
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.AppServices
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon

class AutoTmsUploadScheduler(appContext: Context, workerParams: WorkerParameters) :
	Worker(appContext, workerParams) {
	val mContext = appContext

	override fun doWork(): Result {
		val className: String = AutoTmsUploadScheduler::class.java.name
		val log = helpers.HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"AutoTmsUploadScheduler",
			this.javaClass.simpleName,
			this.javaClass.simpleName
		)
		log.appendLine(className, "Auto TMS Upload Job - Triggered")
		try {
			log.appendLine(className, "Running Auto TMS Upload Job")
			if (Looper.myLooper() == null) {
				Looper.prepare();
			}
			val tms = Tms(mContext)
			tms.uploadTms()
			log.appendLine(className, "------Finished------")

			log.appendLine(className, "Check AutoSettle Ticker Status")
			val finish = System.currentTimeMillis()
			var lastAutoSettleTimeStamp = ServiceHolder.autoSettlementTimeStamp
			log.appendLine(className, "Last Auto Settlement TimeStamp :: $lastAutoSettleTimeStamp")
			if(lastAutoSettleTimeStamp == 0L) {
				log.appendLine(className, "Override LastAutoSettleTimeStamp with current time")
				lastAutoSettleTimeStamp = finish
			}
			val timeElapsed = finish - lastAutoSettleTimeStamp
			log.appendLine(className, "Previous Elapsed Time (Ticker)::${HelperCommon.formatMillis(timeElapsed)}")
			if ((timeElapsed) > 10 * 60 * 1000) { // 10 minutes milliseconds
				log.appendLine(className, "Restart Auto Ticker")
				ServiceHolder.autoSettlementTimeStamp = 0L
				AppServices.triggerAutoSettle(mContext, AppServices.ACTION_RESTART_AUTO_SETTLE_TICKER)
			}
			log.logToFile(EnumLogFileName.TerminaLog)
		} catch (e: Exception) {
			log.appendLine(className, "Exception: ", e.toString())
			log.logToFile(EnumLogFileName.TerminaLogException)
		}

		return Result.success()
	}
}