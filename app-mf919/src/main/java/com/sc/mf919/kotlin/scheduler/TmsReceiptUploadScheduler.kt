package com.sc.mf919.kotlin.scheduler

import android.content.Context
import android.os.Build
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.InstallIdentity
import com.sc.mf919.kotlin.helper_common.ReceiptReconciler
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.handlers.ReceiptUploadHandler
import tms.handlers.UpdateTokenHandler
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TmsReceiptUploadScheduler(appContext: Context, workerParams: WorkerParameters) :
	Worker(appContext, workerParams) {
	val mContext = appContext
	val className: String = TmsReceiptUploadScheduler::class.java.name

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

		/*
		 * This job is the proof that a transaction was reported to TMS. It used to hold 19
		 * appendLine calls behind a single logToFile at the very end, so a process kill anywhere
		 * in the loop -- exactly what happens on an unattended terminal at 3am -- discarded the
		 * whole run's block. Each phase now flushes on its own so a killed run still leaves the
		 * counts and the last record it touched.
		 */
		log.appendLine(className, "Auto Receipt Upload Job [START] :: Is Any Uploading Receipt::${ServiceHolder.uploadingReceipt}")
		if(ServiceHolder.uploadingReceipt){
			val finish = System.currentTimeMillis()
			val timeElapsed = finish - ServiceHolder.uploadingReceiptTimeStamp
			log.appendLine(className, "Previous Elapsed Time::${HelperCommon.formatMillis(timeElapsed)}")
			if ((timeElapsed) > 60 * 60 * 1000) { // 1 hour in milliseconds
				log.appendLine(className, "----------------------------------------------------------------------->")
				log.appendLine(className, "Force Change of Uploading Receipt (stale lock older than 1 hour, taking over)")
				log.appendLine(className, "----------------------------------------------------------------------->")
				ServiceHolder.uploadingReceipt = false
				ServiceHolder.uploadingReceiptTimeStamp = 0L
			}
		}
		// Boundary: the lock decision is a complete record on its own, and it is the thing you
		// need when a terminal stops uploading receipts entirely because of a stuck flag.
		log.logToFile(EnumLogFileName.TerminaLog)

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
					log.appendLine(className, "Install token report FAILED :: ", oneLine(ex.toString()))
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
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				val repaired = ReceiptReconciler.reconcile(mContext, IN_FLIGHT_GRACE_MINUTES)
				log.appendLine(className, "Reconciled stranded receipt rows: ", repaired.toString())
			}

			log.appendLine(className, "Getting Data from ReceiptUpload tables")
			log.appendLine(className, "Selection criteria :: IsSend[false] IsProcessing[false] RESP_CODE!=[-], plus RESP_CODE=[-] older than 30 minutes (currDT $currDT)")
			val listReceiptType = object : TypeToken<List<DbModelReceiptUpload>>() {}.type
			val listReceipt = dbHandler.selectListData<DbModelReceiptUpload>(
				listReceiptType,
				"SELECT * FROM ReceiptUpload where IsSend = ? and IsProcessing = ? and RESP_CODE != ? UNION " +
						"SELECT * FROM ReceiptUpload where IsSend = ? and IsProcessing = ? and RESP_CODE = ? and Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) *24 *60 As Integer) >= 30",
				arrayOf("false", "false", "-", "false", "false", "-")
			)
			log.appendLine(className, "Total Record to upload: ", listReceipt.size.toString())
			// Boundary: what the query found, before anything is mutated.
			log.logToFile(EnumLogFileName.TerminaLog)

			var uploadedCount = 0
			var failedCount = 0
			var errorCount = 0
			var processedCount = 0

			log.appendLine(className, "-----------------Receipt Upload Loop [START] :: total[${listReceipt.size}]-------------------->")
			loop@ for (i in listReceipt.indices) {
				if(exitJob){
					log.appendLine(className, "Fail record detected exiting loop at index ", i.toString())
					break@loop
				}

				val modelData = listReceipt[i]
				/*
				 * One compact line per receipt instead of the whole model dump. DbModelReceiptUpload
				 * holds no PAN/track2 (only CARD_MASKED and CARD_HASHED), so the old toString() was
				 * not a data leak -- it was 37 fields per row where the identifying handful is what
				 * you actually search on. The card field is re-masked defensively rather than
				 * trusting whatever the row happens to hold.
				 */
				log.appendLine(
					className,
					"Record[$i/${listReceipt.size}] :: SEQ_NO[${modelData.SEQ_NO}] TXN_DT[${modelData.TXN_DT}] TXN_TYPE[${modelData.TXN_TYPE}] STAN[${modelData.STAN}] RRN[${modelData.RRN}] TID[${modelData.TID}] AMT[${modelData.TXN_AMT}] CARD[${Utils.hideCardDetails(modelData.CARD_MASKED ?: "-")}] RESP_CODE[${modelData.RESP_CODE}]"
				)
				processedCount++
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
							modelData.CASHOUT_AMT
						)
						log.appendLine(className, "Api Result: ", oneLine(receiptUploadResp.toString()))
						if (receiptUploadResp.REF_ID == null) {
							throw  IOException("Invalid Transaction")
						}
						uploadedCount++
						log.appendLine(className, "UPLOADED :: SEQ_NO[${modelData.SEQ_NO}] STAN[${modelData.STAN}] RESP_CODE[${receiptUploadResp.RESP_CODE}] REF_ID[${receiptUploadResp.REF_ID}]")
						mutableMapOf(
							"LastUpdateDt" to currDT,
							"IsSend" to "true"
						)
					} catch (ex: IOException) {
						exitJob = true
						failedCount++
						ex.printStackTrace()
						// ReceiptUploadHandler puts the server's JSON body (RESP_CODE / RESP_DESC) into
						// the IOException message, so this line is the response code for the failure.
						log.appendLine(className, "Api Failed: ", oneLine(ex.toString()))
						log.appendLine(className, "UPLOAD FAILED :: SEQ_NO[${modelData.SEQ_NO}] STAN[${modelData.STAN}] RRN[${modelData.RRN}] - record left IsSend[false] IsProcessing[false] for retry")
						mutableMapOf(
							"LastUpdateDt" to currDT,
							"IsProcessing" to "false"
						)
					}
					//Update IsProcessing/IsSend based on Result
					dbHandler.updateTableValue(DatabaseTables.RECEIPT_UPLOAD, updateMap, criteriaMap)
					log.appendLine(className, "Update current record: ", oneLine(updateMap.toString()))
					if (exitJob) {
						// The failing record is the one worth having on disk: synchronous stream so it
						// survives the kill that may follow, and it ends the loop anyway.
						log.logToFile(EnumLogFileName.TerminaLogException)
					}
				} catch (e: Exception) {
					errorCount++
					e.printStackTrace()
					log.appendLine(className, "Exception: ", oneLine(e.toString()))
					log.appendLine(className, "ERROR :: SEQ_NO[${modelData.SEQ_NO}] STAN[${modelData.STAN}] left in unknown state (may still be IsProcessing[true])")
					log.logToFile(EnumLogFileName.TerminaLogException)
				}

				/*
				 * Chunk boundary. Per-line writes already put every line on disk, so this is only
				 * about keeping the grouped blocks bounded and recent on a long catch-up run after
				 * an outage -- without emitting a block per row, which is the noise pattern that got
				 * ~22% of log volume deleted.
				 */
				if ((i + 1) % 20 == 0) {
					log.appendLine(className, "Progress :: processed[$processedCount] uploaded[$uploadedCount] failed[$failedCount] error[$errorCount] of ${listReceipt.size}")
					log.logToFile(EnumLogFileName.TerminaLog)
				}
			}
			ServiceHolder.uploadingReceipt = false
			ServiceHolder.uploadingReceiptTimeStamp = 0L
			log.appendLine(className, "----------------------------------------------------------------------------------------")
			log.appendLine(
				className,
				"-----------------Receipt Upload Loop [END] :: found[${listReceipt.size}] processed[$processedCount] uploaded[$uploadedCount] failed[$failedCount] error[$errorCount] notAttempted[${listReceipt.size - processedCount}]-------------------->"
			)
			log.appendLine(className, "Finish loop receipt upload")
		} else {
			log.appendLine(className, "Finish Job Detected an Active Upload Job is Running")
		}

		log.appendLine(className, "Auto Receipt Upload Job [END] :: result[SUCCESS]")
		log.logToFile(EnumLogFileName.TerminaLog)
		return Result.success()
	}

	/**
	 * Collapse a payload onto one physical line. A pretty-printed response body becomes ~30 lines
	 * with the RowIdentifier only on the last, which cannot be grepped back to its run.
	 * Same approach as HTTPServer.oneLine.
	 */
	private fun oneLine(value: String): String =
		value.replace(Regex("""\s+"""), " ").trim()
}