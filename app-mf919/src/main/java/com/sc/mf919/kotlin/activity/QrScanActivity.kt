package com.sc.mf919.kotlin.activity
import enums.EnumResponseCode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import emv.EmvUtil
import utils.Util
import com.sc.mf919.kotlin.activity.zxing.CaptureActivity
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.*
import env.EnvironmentManager
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.context
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import tms.handlers.QrEnquiryHandler
import tms.handlers.ScanQrHandler
import tms.handlers.ScanQrUPIHandler
import tms.models.ScanQrResponseModel
import tms.models.QrEnquiryResponseModel
import tms.models.ScanQrUPIResponseModel
import java.text.SimpleDateFormat
import java.util.*
import com.sc.mf919.kotlin.helper_common.MfHelper

class QrScanActivity : ActivityBase() {
	var helperlogClassName:String = ""
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	val TAG = "QRSCAN"
	lateinit var mContext: Context
	var timeoutSeconds: Int = 90
	lateinit var tempModel: String

	//Scanner Result
	var qrPayload: String? = null
	var qrScannedCompleted = -1

	//Transaction Data
	var qrSeqNo: String = ""
	var amt: String = "0.00"
	var textAmt: String = "000000000000"
	var txn_map: HashMap<String, String> = hashMapOf()
	var jObject: JSONObject = JSONObject()
	var cameraFacing: Int = 1

	var qrTxnDt: String = ""
	var qrRefId: String = ""
	var acqCode: String = ""
	var acqMid: String = ""
	var acqTid: String = ""

	var respCode: String = "1100"
	var respDesc: String = ""
	var qrRespTxnRefNo: String = ""
	var qrRespHostRefNo: String = ""
	var qrRespApprovalCode: String = ""
	var qrRespPayBrand: String = ""
	var qrRespPayBrandDesc: String = ""
	var isTpaAccount = false

	// UPI fields from the payload-upload/enquiry response, held locally instead of writing
	// TransData directly -- these were the one part of the response-deferral refactor (see
	// the commented-out TransData.* blocks nearby) that was left writing TransData live from
	// a background thread. Applied to TransData only in navigationToResultPage(), which is
	// already session-guarded. See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
	var respIsUnionPayTxn = false
	var upiVoucherCode = ""
	var upiDiscountAmt = ""
	var upiMarkupFee = ""

	// Ownership + abort guard: see obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
	// `aborted` lets the HTTP server tell this flow to stop retrying (ECR cancel while we're
	// still stuck in the enquiry loop); `txnSession` catches the case where a NEW transaction
	// silently took ownership of TransData before we noticed (defense in depth).
	@Volatile var aborted = false
	private var txnSession = 0L

	lateinit var environmentManager: EnvironmentManager

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_qrscan)
		HTTPServer.getInstance().attendActivityContext = this@QrScanActivity
		aborted = false
		txnSession = TransData.reset(applicationContext)

		helperLogClassName = this::class.java.simpleName
		helperlogClassName = helperLogClassName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"QR Scan (merchant scans customer QR) Sale"
		)
		helperLog.appendLine(helperLogClassName, "QR Scan opened")

		amt = if (intent.getStringExtra("txnAmt") == null) "0.00" else intent.getStringExtra("txnAmt")!!
		(findViewById<View>(R.id.textView_amountDisplay) as TextView).text = amt
		textAmt = Utils.zeroPadding(amt.replace(".", ""), 12)
		TransData.amount = textAmt.toLong()

		TransData.txnTypeLabel = "Sale"
		TransData.isUPIQR = intent.getBooleanExtra("isUPIQR", false)
		cameraFacing = intent.getIntExtra("cameraFacing", 0)
		TransData.posReference = intent.getStringExtra("posReference") ?: "-"

		val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarCP)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }

		mContext = this@QrScanActivity
		context = mContext
		tempModel = ServiceHolder.getDeviceModel().uppercase()
		environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		helperLog.appendLine(helperLogClassName, "Amount entered :: $amt")
		helperLog.appendLine(helperLogClassName, "isUPIQR :: ${TransData.isUPIQR}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		scanner()
	}

	private val barcodeLauncher = registerForActivityResult<ScanOptions, ScanIntentResult>(
		ScanContract()
	) { result: ScanIntentResult ->
		// Never log result.contents: the scanned payload is the customer's wallet credential.
		// Length only, so a truncated/empty scan is still diagnosable.
		logQrScan("Scan result received :: payload length ${result.contents?.length ?: 0}")
		if (result.contents == null) {
			val originalIntent = result.originalIntent
			if (originalIntent == null) {
				logQrScan("User Cancel :: scan cancelled, no QR captured")
			} else if (originalIntent.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION)) {
				logQrScan("REJECT :: scan cancelled due to missing camera permission")
			}
			customOnBackPress()
		} else {
			qrPayload = result.contents
			CoroutineScope(Dispatchers.IO).launch {
				processScanResult()
			}
		}
 	}

	/**
	 * helperLog is lateinit and only assigned in onCreate, but barcodeLauncher is a property
	 * initializer whose callback can in principle fire before that assignment completes (process
	 * death / restore). Fall back to the old Timber path rather than throwing on a payment screen
	 * -- same shape as EmvActivity.logEmv.
	 */
	private fun logQrScan(msg: String) {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, msg)
		} else {
			Utils.debugLogPrint(TAG, msg)
		}
	}

	private fun scanner() {
		try{
			logQrScan("Scanner launched :: cameraFacing $cameraFacing")
			val scanOption = ScanOptions()
			scanOption.captureActivity = CaptureActivity::class.java
			scanOption.setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.PDF_417, ScanOptions.CODE_39, ScanOptions.CODE_128)
			scanOption.setPrompt("")
			scanOption.setBeepEnabled(true)
			scanOption.setBarcodeImageEnabled(false)
			//scanOption.setCameraId(Camera.CameraInfo.CAMERA_FACING_BACK)
			scanOption.setCameraId(cameraFacing)
			barcodeLauncher.launch(scanOption)
		} catch (e: RemoteException) {
			e.printStackTrace()
			logQrScan("Scanner launch failed :: ${e.message ?: "-"}")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}
	}

	// Called by HTTPServer when the ECR cancels the session (TransactionType 0) while this
	// flow still owns TransData -- makes the enquiry loop below stop retrying promptly instead
	// of running its full 3-attempt/timeout course and clobbering a transaction that took over
	// in the meantime.
	//
	// Setting `aborted` alone is not enough: before this fix existed, HTTPServer force-navigated
	// to the home screen for ANY activity that wasn't CardPaymentActivity on this same trigger
	// (see the `else` branch there). Routing QrScanActivity through abortSession() instead
	// removed that recovery -- if the ECR cancels while the user is still on the live camera
	// scanner (before barcodeLauncher has produced a result), nothing is running yet to notice
	// `aborted`, so the terminal was left stuck on the scanner screen. Restore the same
	// force-navigate-home behavior here, in addition to the flag.
	fun abortSession() {
		aborted = true
		logQrScan("User Cancel :: ECR aborted the QR session, navigate -> home screen")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		runOnUiThread {
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(newIntent)
			finish()
		}
	}

	// Util.DelayMili() is a plain blocking sleep with no interruption point, so a straight
	// 5s call would keep this loop alive up to 5s after an abort. Poll in short steps instead.
	private fun interruptibleDelayMili(totalMs: Int, stepMs: Int = 500) {
		var remaining = totalMs
		while (remaining > 0 && !aborted && TransData.isCurrentSession(txnSession)) {
			val step = if (stepMs < remaining) stepMs else remaining
			Util.DelayMili(step)
			remaining -= step
		}
	}

	suspend fun processScanResult() = withContext(Dispatchers.IO) {
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"QR Scan Activity",
			QrScanActivity::class.java.simpleName,
			QrScanActivity::class.java.name,
		)

		val currDT = Date()
		qrTxnDt = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)
		qrRefId = SimpleDateFormat(EnumDateFormat.yyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)
		ServiceHolder.saleModelCache?.let {
			TransData.salesType = it.SalesType
		}

		val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val timeoutMs = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "TIMEOUT_SECONDS")

		if (timeoutMs.isNotEmpty()) {
			val timeout = Utils.atoi(timeoutMs)
			if (timeout > 0) {
				timeoutSeconds = timeout
			}
		}

		if(merchantInfo != null){
			TransData.transDateAsci = qrTxnDt
			TransData.qrRef = qrRefId

			log.appendLine(helperlogClassName, "QR sale start :: refId $qrRefId, amount $amt, isUPIQR ${TransData.isUPIQR}")

			loadingDialog()
			MfHelper.lockStatusBarAndNavigation(true)

			insertTransactionIntoDB()

			//Processing QR to TMS
			if (TransData.isUPIQR) {
				qrPayloadUPIUpload(log, merchantInfo)
			} else {
				qrPayloadUpload(log, merchantInfo)
			}

			/* 9011 - INVALID_UPIQR <-- no need do enquiry due to qr not valid */
			if(TransData.qrRespCode != "0000" && TransData.qrRespCode != "9011"){
				var enquiryLoop = 3
				// Loop start only -- a line per tick was pure volume; the per-attempt outcome is
				// already visible in qrPaymentEnquiry's own response/exception lines.
				log.appendLine(helperlogClassName, "Payment enquiry loop start :: refId $qrRefId, up to 3 attempts")
				while(enquiryLoop > 0 && !aborted && TransData.isCurrentSession(txnSession)){
					enquiryLoop--
					// Enquiry QR Result
					if(qrPaymentEnquiry(log, merchantInfo)){
						enquiryLoop = 0
					} else {
						interruptibleDelayMili(5 * 1000) //delay 5 second
					}
				}
				if(aborted || !TransData.isCurrentSession(txnSession)){
					log.appendLine(helperlogClassName, "Payment enquiry loop aborted (session terminated / superseded)")
				}
			}
			log.appendLine(helperlogClassName, "QR payment result :: refId $qrRefId, respCode $respCode, respDesc $respDesc, brand $qrRespPayBrand, hostRef $qrRespHostRefNo, apprCode $qrRespApprovalCode")
			closeProgressDialog()
			MfHelper.lockStatusBarAndNavigation(false)
			/*if(respCode == "0000"){
				log.appendLine(helperlogClassName, "Insert Success Transaction into Database")
				insertTransactionIntoDB()
			}*/
		}
		log.logToFile(EnumLogFileName.TerminaLog)
		navigationToResultPage()
	}

	private fun loadingDialog() {
		object : Thread() {
			override fun run() {
				super.run()
				try {
					var incrementTimer = 1
					val loadingMsg = "Loading... (${incrementTimer}s)"
					startProgressDialog(mContext, "Processing QR Payment", loadingMsg)

					while (true) {
						if (qrScannedCompleted == 0) {
							break
						}

						pDMsg = "Loading... (${++incrementTimer}s)"
						runOnUiThread ( changeMessage )
						Utils.DelayMili(1000)
					}
				} catch (e: RemoteException) {
					e.printStackTrace()
				}
			}
		}.start()
	}

	private fun qrPayloadUpload(log: HelperLog, merchantInfo: DbModelMerchantConfig){
		log.appendLine(helperlogClassName, "Upload Payload API Request")
		val scanQrHandler = ScanQrHandler(environmentManager)

		try {
			val scanQrResp = scanQrHandler.invoke(
				log,
				textAmt,
				qrTxnDt,
				qrPayload!!,
				merchantInfo.QrMid!!,
				merchantInfo.QrTid!!,
				qrRefId,
				TransData.posReference,
				// named: Pro inserted four ordering-item params ahead of this one
				timeoutSecond = timeoutSeconds
			)
			log.appendLine(helperlogClassName, "ScanQrHandler Response -> ", scanQrHandler.toString())


			/*
			TransData.qrRespCode = scanQrResp.RESP_CODE ?: ""
			TransData.qrTxnRef = scanQrResp.QR_TXN_REFNO ?: ""
			TransData.qrHostRef = scanQrResp.QR_HOST_REFNO ?: ""
			TransData.qrPayBrand = scanQrResp.QR_PAY_BRAND ?: ""
			TransData.qrPayBrandDesc = scanQrResp.QR_PAY_BRAND_DESC ?: ""
			TransData.acqCode = scanQrResp.ACQ_CODE ?: ""
			TransData.mid = scanQrResp.ACQ_MID ?: ""
			TransData.tid = scanQrResp.ACQ_TID ?: ""
			TransData.isTpaAccount = scanQrResp.IS_TPA_ACCOUNT ?: false
			*/

			respCode = scanQrResp.RESP_CODE ?: ""
			qrRespTxnRefNo = scanQrResp.QR_TXN_REFNO ?: ""
			qrRespHostRefNo = scanQrResp.QR_HOST_REFNO ?: ""
			qrRespPayBrand = scanQrResp.QR_PAY_BRAND ?: ""
			qrRespPayBrandDesc = scanQrResp.QR_PAY_BRAND_DESC ?: ""
			acqCode = scanQrResp.ACQ_CODE ?: ""
			acqMid = scanQrResp.ACQ_MID ?: ""
			acqTid = scanQrResp.ACQ_TID ?: ""
			isTpaAccount = scanQrResp.IS_TPA_ACCOUNT ?: false

			respIsUnionPayTxn = scanQrResp.IS_UNIONPAY_TXN ?: false
			upiVoucherCode = scanQrResp.UPI_VOUCHER_CODE ?: ""
			upiDiscountAmt = scanQrResp.UPI_DISCOUNT_AMOUNT ?: ""
			upiMarkupFee = scanQrResp.UPI_MARKUP_FEE ?: ""
		} catch (ex: Exception) {
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "ScanQrHandler (Exception) -> ", ex.toString())

			try {
				val errorResponse = Gson().fromJson(ex.message, ScanQrResponseModel::class.java)
				/*
				TransData.qrRespCode = errorResponse.RESP_CODE ?: "1100"
				TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
				TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: ""
				TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
				TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
				TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
				*/

				respCode = errorResponse.RESP_CODE ?: "1100"
				respDesc = errorResponse.RESP_DESC ?: ""
				qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: ""
				qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
				qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
				isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
			} catch (jsonEx: Exception) {
				log.appendLine(helperlogClassName, "Json Exception in Error -> ", jsonEx.toString())
			}
		}
	}

	private fun qrPayloadUPIUpload(log: HelperLog, merchantInfo: DbModelMerchantConfig){
		log.appendLine(helperlogClassName, "Upload Payload API Request")
		val scanQrUPIHandler = ScanQrUPIHandler(environmentManager)

		try {
			val scanQrUPIResp = scanQrUPIHandler.invoke(
					log,
					textAmt,
					qrTxnDt,
					qrPayload!!,
					merchantInfo.QrMid!!,
					merchantInfo.QrTid!!,
					qrRefId,
					TransData.posReference
			)
			log.appendLine(helperlogClassName, "scanQrUPIHandler Response -> ", scanQrUPIHandler.toString())

			/*
			TransData.qrRespCode = scanQrUPIResp.RESP_CODE ?: ""
			TransData.qrTxnRef = scanQrUPIResp.QR_TXN_REFNO ?: ""
			TransData.qrHostRef = scanQrUPIResp.QR_HOST_REFNO ?: ""
			TransData.qrPayBrand = scanQrUPIResp.QR_PAY_BRAND ?: ""
			TransData.qrPayBrandDesc = scanQrUPIResp.QR_PAY_BRAND_DESC ?: ""
			TransData.acqCode = scanQrUPIResp.ACQ_CODE ?: ""
			TransData.mid = scanQrUPIResp.ACQ_MID ?: ""
			TransData.tid = scanQrUPIResp.ACQ_TID ?: ""
			TransData.isTpaAccount = scanQrUPIResp.IS_TPA_ACCOUNT ?: false
			*/

			respCode = scanQrUPIResp.RESP_CODE ?: ""
			qrRespTxnRefNo = scanQrUPIResp.QR_TXN_REFNO ?: ""
			qrRespHostRefNo = scanQrUPIResp.QR_HOST_REFNO ?: ""
			qrRespPayBrand = scanQrUPIResp.QR_PAY_BRAND ?: ""
			qrRespPayBrandDesc = scanQrUPIResp.QR_PAY_BRAND_DESC ?: ""
			acqCode = scanQrUPIResp.ACQ_CODE ?: ""
			acqMid = scanQrUPIResp.ACQ_MID ?: ""
			acqTid = scanQrUPIResp.ACQ_TID ?: ""
			isTpaAccount = scanQrUPIResp.IS_TPA_ACCOUNT ?: false

			respIsUnionPayTxn = scanQrUPIResp.IS_UNIONPAY_TXN ?: false
			upiVoucherCode = scanQrUPIResp.UPI_VOUCHER_CODE ?: ""
			upiDiscountAmt = scanQrUPIResp.UPI_DISCOUNT_AMOUNT ?: ""
			upiMarkupFee = scanQrUPIResp.UPI_MARKUP_FEE ?: ""
		} catch (ex: Exception) {
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "scanQrUPIHandler (Exception) -> ", ex.toString())

			try {
				val errorResponse = Gson().fromJson(ex.message, ScanQrUPIResponseModel::class.java)
				/*
				TransData.qrRespCode = errorResponse.RESP_CODE ?: "1100"
				TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
				TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: ""
				TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
				TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
				TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
				*/

				respCode = errorResponse.RESP_CODE ?: "1100"
				respDesc = errorResponse.RESP_DESC ?: ""
				qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: ""
				qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
				qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
				isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
			} catch (jsonEx: Exception) {
				log.appendLine(helperlogClassName, "Json Exception in Error -> ", jsonEx.toString())
			}
		}
	}

	private fun qrPaymentEnquiry(log: HelperLog, merchantInfo: DbModelMerchantConfig): Boolean{
		var result = false
		log.appendLine(helperlogClassName, "Enquiring Transaction Result")
		val qrEnquiryHandler = QrEnquiryHandler(environmentManager)

		try {
			val qrEnquiryResp = qrEnquiryHandler.invoke(
				log,
				merchantInfo.QrMid ?: "",
				merchantInfo.QrTid ?: "",
				qrRefId
			)
			log.appendLine(helperlogClassName, "QrEnquiryHandler Response -> ", qrEnquiryResp.toString())

			/*
			TransData.qrRespCode = qrEnquiryResp.RESP_CODE ?: ""
			//qrRespRefId = qrEnquiryResp.QR_REFID ?: ""
			TransData.qrHostRef = qrEnquiryResp.QR_HOST_REFNO ?: ""
			TransData.qrTxnRef = qrEnquiryResp.QR_TXN_REFNO ?: ""
			TransData.qrApprovalCode = qrEnquiryResp.BANK_AUTH_CODE ?: ""
			TransData.qrPayBrand = qrEnquiryResp.QR_PAY_BRAND ?: ""
			TransData.qrPayBrandDesc = qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""
			TransData.isTpaAccount = qrEnquiryResp.IS_TPA_ACCOUNT ?: false
			*/

			respCode = qrEnquiryResp.RESP_CODE ?: ""
			qrRespHostRefNo = qrEnquiryResp.QR_HOST_REFNO ?: ""
			qrRespTxnRefNo = qrEnquiryResp.QR_TXN_REFNO ?: ""
			qrRespApprovalCode = qrEnquiryResp.BANK_AUTH_CODE ?: ""
			qrRespPayBrand = qrEnquiryResp.QR_PAY_BRAND ?: ""
			qrRespPayBrandDesc = qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""
			isTpaAccount = qrEnquiryResp.IS_TPA_ACCOUNT ?: false

			respIsUnionPayTxn = qrEnquiryResp.IS_UNIONPAY_TXN ?: false
			upiVoucherCode = qrEnquiryResp.UPI_VOUCHER_CODE ?: ""
			upiDiscountAmt = qrEnquiryResp.UPI_DISCOUNT_AMOUNT ?: ""
			upiMarkupFee = qrEnquiryResp.UPI_MARKUP_FEE ?: ""
			result = true
		}catch (ex: Exception){
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "QrEnquiryHandler (Exception) -> ", ex.toString())

			try {
				val errorResponse = Gson().fromJson(ex.message, QrEnquiryResponseModel::class.java)
				errorResponse.RESP_CODE?.let {
					if(it != "0404"){
						/*
						TransData.qrRespCode = it
						TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
						TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
						TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
						TransData.qrApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
						TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: TransData.qrPayBrand
						TransData.qrPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: TransData.qrPayBrandDesc
						TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
						*/

						respCode = it
						respDesc = errorResponse.RESP_DESC ?: ""
						qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
						qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
						qrRespApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
						qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: TransData.qrPayBrand
						qrRespPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: TransData.qrPayBrandDesc
						isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
					}
				}
				errorResponse.RESP_DESC?.let {
					if(errorResponse.RESP_CODE != "99999"){
						result = true
					}
				}
			} catch (jsonEx: Exception) {
				log.appendLine(helperlogClassName, "Json Exception in Error -> ", jsonEx.toString())
			}
		}
		return result
	}

	private fun insertTransactionIntoDB() {
		var isUnionPayTxn = "0"
		if (TransData.isUPIQR) {
			isUnionPayTxn = "1"
		}

		//TODO Receipt Upload
		val jsonObject = JsonObject()
		try {
			jsonObject.addProperty("TXN_DT", qrTxnDt)
			jsonObject.addProperty("TXN_TYPE", "Sale")
			jsonObject.addProperty("TXN_AMT", textAmt)
			jsonObject.addProperty("MID", acqMid)
			jsonObject.addProperty("TID", acqTid)
			jsonObject.addProperty("QrRefId", qrRefId)
			jsonObject.addProperty("QrType", qrRespPayBrand)
			jsonObject.addProperty("RRN", qrRespHostRefNo)
			jsonObject.addProperty("APPR_CODE", qrRespApprovalCode)
			jsonObject.addProperty("RESP_CODE", respCode)
			jsonObject.addProperty("APP_VER", ServiceHolder.getAppVersion())
			jsonObject.addProperty("SN", ServiceHolder.getTerminalSerialNumber())
			jsonObject.addProperty("POS_REF_NO", TransData.posReference)
			jsonObject.addProperty("CreationDate", SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(Date()))
			jsonObject.addProperty("LastUpdateDt", "")
			jsonObject.addProperty("IsProcessing", "true")
			jsonObject.addProperty("IsSend", "true")
		} catch (e: Exception) {
			e.printStackTrace()
		}
		val dbModel = Gson().fromJson(jsonObject.toString(), DbModelReceiptUpload::class.java)
		ReceiptUploadRepo.insertToDbByModel(mContext, dbModel)
		//TODO Receipt Upload

		//TODO Transaction QR
		val transQrModel = DbModelTransactionQr("Sale", qrTxnDt, "", textAmt, "SCAN", qrRespPayBrand,
			qrRespPayBrand, qrRespPayBrandDesc, acqMid, acqTid, acqCode, qrRefId, qrRespHostRefNo, qrRespTxnRefNo, qrRespApprovalCode,
			respCode, respDesc, isUnionPayTxn, upiVoucherCode, upiDiscountAmt, upiMarkupFee, TransData.posReference, isTpaAccount.toString())
		TransactionQrRepo.insertToDb(mContext, transQrModel)
		//TODO Transaction QR
	}

	private fun updateTransactionResult() {
		val criteriaHM = hashMapOf<Any, Any>(
			"refId" to qrRefId
		)
		val valueHM = hashMapOf<Any, Any>(
			"mid" to acqMid,
			"tid" to acqTid,
			"acqCode" to acqCode,
			"payBrand" to qrRespPayBrand,
			"respCode" to respCode,
			"respDesc" to respDesc,
			"hostRefNo" to qrRespHostRefNo,
			"txnRefNo" to qrRespTxnRefNo,
			"approvalCode" to qrRespApprovalCode,
			"productCode" to qrRespPayBrand,
			"productName" to qrRespPayBrandDesc,
			"isTpaAccount" to isTpaAccount.toString(),
		)
		TransactionQrRepo.updateTransactionQr(applicationContext, valueHM, criteriaHM)

		val receiptUploadCriteria = hashMapOf<Any, Any>(
			"QrRefId" to qrRefId
		)
		val receiptUploadValue = hashMapOf<Any, Any>(
			"MID" to acqMid,
			"QrType" to qrRespPayBrand,
			"RRN" to qrRespHostRefNo,
			"RESP_CODE" to respCode,
		)
		ReceiptUploadRepo.updateData(mContext, receiptUploadValue, receiptUploadCriteria)
	}

	private fun navigationToResultPage() {
		if(!TransData.isCurrentSession(txnSession) || aborted){
			// Either another transaction now owns TransData (this flow was stuck in the
			// enquiry loop and got superseded), or abortSession() already force-navigated us
			// home (ECR cancel) -- either way, persist this QR flow's own result -- it's keyed
			// by qrRefId, not TransData -- but do NOT write into TransData (now foreign, or no
			// longer wanted) and do NOT navigate into a QR result screen on top of wherever
			// abortSession() already sent us.
			logQrScan("navigationToResultPage :: stale session ($txnSession vs " +
				"${TransData.sessionId}) or aborted=$aborted -- skip TransData write & result navigation")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
			updateTransactionResult()
			finish()
			return
		}

		TransData.mid = acqMid
		TransData.tid = acqTid
		TransData.acqCode = acqCode
		TransData.qrRespCode = respCode
		TransData.qrRespDesc = respDesc
		TransData.qrTxnRef = qrRespTxnRefNo
		TransData.qrHostRef = qrRespHostRefNo
		TransData.qrPayBrand = qrRespPayBrand
		TransData.qrPayBrandDesc = qrRespPayBrandDesc
		TransData.qrApprovalCode = qrRespApprovalCode
		TransData.isTpaAccount = isTpaAccount
		TransData.isUPIQR = respIsUnionPayTxn
		TransData.upiVoucherCode = upiVoucherCode
		TransData.upiDiscountAmt = upiDiscountAmt
		TransData.upiMarkupFee = upiMarkupFee

		//insertTransactionIntoDB()
		updateTransactionResult()

		var intent = Intent(mContext, TransactionResultQrActivity::class.java)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
			intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
		}

		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		TransData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
		if (upiDiscountAmt.isNotEmpty()) {
			TransData.upiFinalAmount = (TransData.amount - upiDiscountAmt.toLong()).toString()
		}
		logQrScan("Validation passed :: navigate -> ${intent.component?.className ?: "-"}")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		mContext.startActivity(intent)
		finish()
	}

	fun customOnBackPress() {
		logQrScan("User Cancel :: abandoned QR scan sale (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}, amount $amt)")
		try {
			txn_map = HashMap()
			txn_map["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
			txn_map["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description

			jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
			jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
		} catch (e: JSONException) {
			e.printStackTrace()
		}

		if (ServiceHolder.appIntent) {
			onBackToApp(txn_map)
		} else {
			if (ServiceHolder.appHTTP) {
				Log.d(TAG, "onBackPressed: $jObject")
				HTTPServer.getInstance().setResponseMessage(jObject.toString())
				ServiceHolder.appHTTP = false
			}

			logQrScan("User Cancel :: navigate -> home screen")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(newIntent)
			finish()
		}
	}

	private fun onBackToApp(txn_map: HashMap<String, String>) {
		txn_map["TransactionType"] = ServiceHolder.txnType.toString()
		logQrScan("User Cancel :: navigate -> TransactionTransmitter (ECR reply)")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txn_map", txn_map)
		startActivity(intent)
		finish()
	}

	override fun onDestroy() {
		super.onDestroy()
		logQrScan("QrScan OnDestroy :: QR scan screen ended")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
	}
}