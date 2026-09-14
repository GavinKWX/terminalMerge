package com.sc.mf919.kotlin.scheduler

import android.content.Context
import android.os.Looper
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919.java.activity.Tms
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.activity.AppServices
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import helpers.HelperLog

import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
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
		log.appendLine(className, "Auto TMS Upload Job [START] :: Triggered")
		var tickerRestarted = false
		try {
			// Tms.uploadTms() drives the DeviceInfo poll and every task TMS hands back
			// (MerchantConfigUpdate, TerminalConfigUpdate, key injection, ...). It carries its own
			// HelperLog, so the detail is in its blocks -- these markers exist so that a run which
			// dies inside uploadTms can still be told apart from one that never entered it.
			log.appendLine(className, "-----------------TMS Upload (Device Info / Task Poll) [START]-------------------->")
			if (Looper.myLooper() == null) {
				Looper.prepare();
			}
			val tms = Tms(mContext)
			tms.uploadTms()
			log.appendLine(className, "-----------------TMS Upload (Device Info / Task Poll) [END]-------------------->")
			// Phase boundary: uploadTms returned, whatever the ticker check does next.
			log.logToFile(EnumLogFileName.TerminaLog)

			log.appendLine(className, "-----------------AutoSettle Ticker Check [START]-------------------->")
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
				log.appendLine(className, "DECISION :: ticker stalled (>10 min), Restart Auto Ticker")
				tickerRestarted = true
				ServiceHolder.autoSettlementTimeStamp = 0L
				AppServices.triggerAutoSettle(mContext, AppServices.ACTION_RESTART_AUTO_SETTLE_TICKER)
			} else {
				// Recorded explicitly: "no restart" and "never reached the check" used to look
				// identical in the log, and the difference is whether auto-settlement can still fire.
				log.appendLine(className, "DECISION :: ticker alive (<=10 min), no restart")
			}
			log.appendLine(className, "-----------------AutoSettle Ticker Check [END]-------------------->")
			log.appendLine(className, "Auto TMS Upload Job [END] :: tickerRestarted[$tickerRestarted] result[SUCCESS]")
			log.logToFile(EnumLogFileName.TerminaLog)
		} catch (e: Exception) {
			log.appendLine(className, "Exception: ", e.toString())
			log.appendLine(className, "Auto TMS Upload Job [END] :: tickerRestarted[$tickerRestarted] outcome[EXCEPTION] result[SUCCESS - not retried]")
			/*
			 * TerminaLogException only, and deliberately not preceded by a TerminaLog flush:
			 * logToFile clears the buffer, so the old "TerminaLog then TerminaLogException" pair
			 * wrote the block to TerminaLog and then nothing at all to the exception stream -- the
			 * second call hit the isEmpty() early return. Since every line is already in TerminaLog
			 * via the per-line sink, flushing the block to the synchronous exception stream is
			 * strictly better: nothing is lost and the segment survives a kill.
			 */
			log.logToFile(EnumLogFileName.TerminaLogException)
		}

		return Result.success()
	}
}