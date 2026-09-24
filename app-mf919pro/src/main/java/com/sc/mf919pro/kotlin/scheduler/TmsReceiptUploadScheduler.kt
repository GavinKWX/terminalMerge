package com.sc.mf919pro.kotlin.scheduler

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.InstallIdentity
import com.sc.mf919pro.kotlin.helper_common.ReceiptReconciler
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.HelperText
import tms.handlers.ReceiptUploadHandler
import tms.handlers.UpdateTokenHandler
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class TmsReceiptUploadScheduler(appContext: Context, workerParams: WorkerParameters) :
	Worker(appContext, workerParams) {
	val mContext = appContext
	val className: String = TmsReceiptUploadScheduler::javaClass.name

	companion object {
		/**
		 * How old an unresolved row must be before this worker treats it as stranded rather than
		 * in flight. A transaction resolves its row in seconds, and the row cannot upload before
		 * the 30-minute arm of the SELECT below anyway, so a couple of minutes costs nothing and
		 * keeps the reconciler off rows a live transaction still owns.
		 */
		private const val IN_FLIGHT_GRACE_MINUTES = 2
	}

	override fun doWork(): Result {
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"Auto Receipt Upload Job",
			"TmsReceiptUploadScheduler",
			"TmsReceiptUploadScheduler"
		)

		log.appendLine(className, "Is Any Uploading Receipt::${ServiceHolder.uploadingReceipt}")
		if(ServiceHolder.uploadingReceipt){
			val finish = System.currentTimeMillis()
			val timeElapsed = finish - ServiceHolder.uploadingReceiptTimeStamp
			log.appendLine(className, "Previous Elapsed Time::${HelperCommon.formatMillis(timeElapsed)}")
			if ((timeElapsed) > 60 * 60 * 1000) { // 1 hour in milliseconds
				log.appendLine(className, "----------------------------------------------------------------------->")
				log.appendLine(className, "Force Change of Uploading Receipt")
				log.appendLine(className, "----------------------------------------------------------------------->")
				ServiceHolder.uploadingReceipt = false
				ServiceHolder.uploadingReceiptTimeStamp = 0L
			}
		}

		if(!ServiceHolder.uploadingReceipt){
			ServiceHolder.uploadingReceipt = true
			ServiceHolder.uploadingReceiptTimeStamp = System.currentTimeMillis()
			val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
			val receiptUploadHandler = ReceiptUploadHandler(environmentManager)

			/*
			 * Report the install token before the receipts that carry it. UpdateToken is what puts this
			 * installation into Terminal_App_History and links it to the one it replaces; the receipts
			 * only carry the value. The backend confirmed the registration has to land first, so this
			 * gates the run -- see the skip branch below. The call is idempotent, so a retry is free.
			 */
			val installToken = InstallIdentity.getToken()
			if (InstallIdentity.needsReporting(installToken)) {
				var tokenReported = false
				try {
					UpdateTokenHandler(environmentManager).invoke(log, installToken)
					InstallIdentity.markReported(installToken)
					tokenReported = true
					log.appendLine(className, "Install token reported to TMS :: [$installToken]")
				} catch (ex: Exception) {
					ex.printStackTrace()
					log.appendLine(className, "Install token report FAILED :: ", HelperText.oneLine(ex.toString()))
				}
				if (!tokenReported) {
					/*
					 * The server needs the token registered before it sees receipts carrying it, so
					 * nothing uploads until UpdateToken succeeds. The receipts stay IsSend[false] and
					 * go up on a later run -- exactly where a failed upload leaves them. Only reached
					 * for a non-blank token: a blank one is "unknown" to the server and settles as it
					 * does today, so it never blocks.
					 *
					 * Release the lock before returning. Leaving uploadingReceipt set would block
					 * every subsequent run until the one-hour stale-lock takeover above clears it.
					 */
					ServiceHolder.uploadingReceipt = false
					ServiceHolder.uploadingReceiptTimeStamp = 0L
					log.appendLine(className, "Receipt upload SKIPPED this run :: install token [$installToken] is not registered with TMS yet, receipts left queued for retry")
					log.logToFile(EnumLogFileName.TerminaLog)
					return Result.success()
				}
				log.logToFile(EnumLogFileName.TerminaLog)
			}

			val dbHandler = DbHandler.getInstance(mContext)!!
			val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
			var exitJob = false

			// Repair rows stranded unresolved (app killed between the host response and
			// updateReceiptInfo) BEFORE deciding what to upload. This worker is periodic, so
			// WorkManager starts the process and runs it even when nobody reopens the app - a
			// startup-only reconcile would never fire in that case, and the 30-minute arm below
			// would ship the row still reading "-".
			// The grace period is not optional. insertPendingTransactionInto() enqueues an
			// immediate upload job, so this worker routinely runs while the host request is still
			// in flight (measured at 234 ms after the ISO request went out, on A99). Anything that
			// recent is a healthy in-flight transaction, not an orphan.
			val repaired = ReceiptReconciler.reconcile(mContext, IN_FLIGHT_GRACE_MINUTES)
			log.appendLine(className, "Reconciled stranded receipt rows: ", repaired.toString())

			log.appendLine(className, "Getting Data from ReceiptUpload tables")
			val listReceiptType = object : TypeToken<List<DbModelReceiptUpload>>() {}.type
			val listReceipt = dbHandler.selectListData<DbModelReceiptUpload>(
				listReceiptType,
				"SELECT * FROM ReceiptUpload where IsSend = ? and IsProcessing = ? and RESP_CODE != ? UNION " +
						"SELECT * FROM ReceiptUpload where IsSend = ? and IsProcessing = ? and RESP_CODE = ? and Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) *24 *60 As Integer) >= 30",
				arrayOf("false", "false", "-", "false", "false", "-")
			)
			log.appendLine(className, "Total Record to upload: ", listReceipt.size.toString())

			loop@ for (i in listReceipt.indices) {
				log.appendLine(className, "----------------------------------------------------------------------------------------")
				log.appendLine(className, "Running: ", i.toString())
				if(exitJob){
					log.appendLine(className, "Fail record detected exiting loop")
					break@loop
				}

				val modelData = listReceipt[i]
				log.appendLine(className, "value: ", modelData.toString())
				DbModelReceiptUpload.sanitizeSpecialChars(modelData)
				try {
					var updateMap = mutableMapOf<Any, Any>(
						"LastUpdateDt" to currDT,
						"IsProcessing" to "true"
					)
					val criteriaMap = mutableMapOf<Any, Any>(
						"SEQ_NO" to modelData.SEQ_NO as Any,
						"TXN_DT" to modelData.TXN_DT as Any,
						"TXN_TYPE" to modelData.TXN_TYPE as Any,
						"CreationDate" to modelData.CreationDate as Any
					)
					// Update IsProcessing to true while calling api
					dbHandler.updateTableValue(DatabaseTables.RECEIPT_UPLOAD, updateMap, criteriaMap)
					log.appendLine(className, "Update data to processing")

					updateMap = try {
						val receiptUploadResp = receiptUploadHandler.invoke(
							log,
							modelData.SEQ_NO,
							modelData.TXN_DT,
							modelData.TXN_TYPE,
							modelData.MID,
							modelData.TID,
							modelData.MTI,
							modelData.NII,
							modelData.SCHEME_ID,
							modelData.AID,
							modelData.CARD_MASKED,
							modelData.CARD_HASHED,
							modelData.RRN,
							modelData.APPR_CODE,
							modelData.RRN_ORI,
							modelData.APPR_CODE_ORI,
							modelData.TXN_AMT,
							modelData.INV_NO,
							modelData.STAN,
							modelData.BATCH_NO,
							modelData.RESP_CODE,
							modelData.APP_VER,
							"-", //TODO
							"-", //TODO
							modelData.ENTRY_TYPE,
							modelData.ARQC,
							modelData.TVR,
							modelData.POS_REF_NO,
							modelData.PAYMENT_PRODUCT_ID,
							modelData.CVM,
							modelData.CARD_LABEL,
							modelData.BATCHNO_PREAUTH,
							modelData.EPP_DETAIL,
							modelData.CASHOUT_AMT,
							modelData.ORDERING_ITEM,
							modelData.ORDERING_ITEM_IMG,
							modelData.CORRELATION_REF,
							modelData.ADDITIONAL_INFO,
						)
						log.appendLine(className, "Api Result: ", receiptUploadResp.toString())
						if (receiptUploadResp.REF_ID == null) {
							throw  IOException("Invalid Transaction")
						}
						mutableMapOf(
							"LastUpdateDt" to currDT,
							"IsSend" to "true"
						)
					} catch (ex: IOException) {
						exitJob = true
						ex.printStackTrace()
						log.appendLine(className, "Api Failed: ", ex.toString())
						mutableMapOf(
							"LastUpdateDt" to currDT,
							"IsProcessing" to "false"
						)
					}
					//Update IsProcessing/IsSend based on Result
					dbHandler.updateTableValue(DatabaseTables.RECEIPT_UPLOAD, updateMap, criteriaMap)
					log.appendLine(className, "Update current record: ", updateMap.toString())
				} catch (e: Exception) {
					e.printStackTrace()
					log.appendLine(className, "Exception: ", e.toString())
				}
			}
			ServiceHolder.uploadingReceipt = false
			ServiceHolder.uploadingReceiptTimeStamp = 0L
			log.appendLine(className, "----------------------------------------------------------------------------------------")
			log.appendLine(className, "Finish loop receipt upload")
		} else {
			log.appendLine(className, "Finish Job Detected an Active Upload Job is Running")
		}
		log.logToFile(EnumLogFileName.TerminaLog)
		return Result.success()
	}
}