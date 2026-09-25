package com.sc.mf919.kotlin.activity

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.WriterException
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import emv.EmvUtil
import utils.Util
import com.sc.mf919.kotlin.data_enum.QrProductDataEnum
import com.sc.mf919.kotlin.data_enum.QrProductEnumModel
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import com.sc.mf919.kotlin.helper_common.AppBus
import com.sc.mf919.kotlin.helper_common.UiEvent
import utils.GenerateQr
import com.sc.mf919.kotlin.helper_common.HTTPServer
import mdb.MdbController
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.handlers.GenerateQrHandler
import tms.handlers.QrCancelHandler
import tms.handlers.QrEnquiryHandler
import tms.models.GenerateQrResponseModel
import tms.models.QrEnquiryResponseModel
import java.text.SimpleDateFormat
import java.util.*

class  GenerateQrActivity : ActivityBase() {
	var helperlogClassName:String = ""
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	lateinit var mContext: Context
	lateinit var tempModel: String
	private val TAG = "GenerateQR"
	//var isoDb = IsoDb()
	var qrProductModel: QrProductEnumModel? = null
	var denominationType: String? = null
	var dbModelDenominationList: DbModelDenominationList? = null
	lateinit var selectedSales: SalesModel
	lateinit var environmentManager: EnvironmentManager
	lateinit var timerTextView: TextView
	// Ownership + abort guard: see obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
	// Was a plain var read/written across threads (enquiry loop on Dispatchers.IO, onDestroy on
	// main) with no happens-before edge -- @Volatile fixes that. txnSession catches the case
	// where a NEW transaction silently took ownership of TransData before this flow noticed
	// (defense in depth on top of isForceStop).
	@Volatile var isForceStop = false

	/**
	 * A VMC abort is being handled. Deliberately narrower than [isForceStop], which onDestroy and
	 * abortSession() also set: only this one means "the vend is cancelled, so any payload we are
	 * about to create - or just created - has to be cancelled at the acquirer". Gating the host
	 * call on isForceStop would fire an acquirer cancel from ordinary screen teardown.
	 *
	 * processGenerateQrResult() reads it on BOTH sides of qrPayloadGenerate(). Measured on the A80
	 * 2026-09-21: qrRefId is assigned ~1.1s before the payload exists at the acquirer, so an abort
	 * landing in that window made onMdbVendingForceEnd() cancel a refId the acquirer did not have
	 * yet - the cancel failed, the payload was created anyway, and it stayed payable with the
	 * screen already gone.
	 */
	@Volatile private var vendAbortPending = false

	/**
	 * True once qrPayloadGenerate() has actually created a payload at the acquirer. The abort
	 * thread used to decide on qrRefId.isNotEmpty(), but qrRefId is assigned well BEFORE the
	 * payload exists and the thread re-read that mutable field late - so an abort arriving before
	 * generation cancelled a payload that was never created and logged a false "may still be
	 * payable" warning. Measured on the A80 2026-09-21.
	 */
	@Volatile private var qrPayloadCreated = false

	/** One cancel per payload: the abort thread and the post-generation guard both race for it. */
	private val payloadCancelClaimed = AtomicBoolean(false)
	private var txnSession = 0L
	var stopTimer = false

	// True only while a blocking host call (generate / enquiry / cancel) is actually executing.
	// Used by onStop() to decide whether backgrounding this screen may drop a host response --
	// see the comment there. @Volatile because the calls run on Dispatchers.IO / a raw Thread
	// while onStop reads it on main.
	// See obsidian FIX-2026-08-04-noHistory-MidAuthorization-Teardown.
	@Volatile private var hostCallInFlight = false

	private inline fun <T> duringHostCall(block: () -> T): T {
		hostCallInFlight = true
		try {
			return block()
		} finally {
			hostCallInFlight = false
		}
	}

	//QR Information
	var barcodeQR: String = ""
	var payloadQR: String = ""
	var payloadImage: String = ""

	//Trsanction Data
	var qrSeqNo: String = "" // No Use
	var qrTxnDt: String = ""
	var qrRefId: String = ""
	var posReference: String = ""
	var acqMid: String = ""
	var acqTid: String = ""

	var qrRespTxnRefNo: String = ""
	var qrRespHostRefNo: String = ""
	var qrRespApprovalCode: String = ""
	var respCode: String = "1100"
	var respDesc: String = ""
	var qrRespPayBrand: String = ""
	var qrRespPayBrandDesc: String = ""
	var isTpaAccount = false

	// UPI fields from the enquiry response, held locally instead of writing TransData
	// directly from the background enquiry loop -- see the commented-out TransData.* block
	// right above their write site. Applied to TransData only in navigationToResultPage(),
	// which is already session-guarded. See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
	var respIsUnionPayTxn = false
	var upiVoucherCode = ""
	var upiDiscountAmt = ""
	var upiMarkupFee = ""

	var amt: String = "0.00"
	var textAmt: String = "000000000000"

	var isOnBackPress = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		txnSession = TransData.reset(applicationContext)
		selectedSales = ServiceHolder.selectedCacheModel as SalesModel
		val uiClass = selectedSales.let {
			try {
				qrProductModel = QrProductDataEnum.valueOf(selectedSales.QrProductCode!!).data
				qrProductModel?.LayoutActivityId
			} catch (e: Exception) {
				e.printStackTrace()
				R.layout.activity_dynamicqr
			}
		}
		setContentView(uiClass!!)

		tempModel = ServiceHolder.getDeviceModel().uppercase()

		//TODO Dynamic Layout For Small Terminal
		val displayMetrics = resources.displayMetrics
		val screenHeightPx = displayMetrics.heightPixels
		val screenHeightDp = screenHeightPx / displayMetrics.density
		println("screenHeightDp :: $screenHeightDp")

		if (screenHeightDp < 500) {
			val currencyBoxLayout = findViewById<LinearLayout>(R.id.currencyBox)
			val cbParams = currencyBoxLayout.layoutParams as LinearLayout.LayoutParams
			cbParams.topMargin = 0

			val qrImg = findViewById<ImageView>(R.id.qrImg)
			val params = qrImg.layoutParams
			params.width = Helper.getInstance().dpToPx(200)
			params.height = Helper.getInstance().dpToPx(200)
			qrImg.layoutParams = params

			val qrBoxLayout = findViewById<LinearLayout>(R.id.qrBox)
			val qbParams = qrBoxLayout.layoutParams as LinearLayout.LayoutParams
			qbParams.setMargins(0,0,0,0)
		}
		//TODO Dynamic Layout For Small Terminal

		HTTPServer.getInstance().attendActivityContext = this@GenerateQrActivity

		qrProductModel?.qrHeaderImage?.let {
			try {
				val brandImageView = (findViewById<View>(R.id.qrBrandImage) as ImageView)
				brandImageView.visibility = View.VISIBLE
				brandImageView.setImageResource(it)
			} catch (e: java.lang.Exception) {
				e.printStackTrace()
			}
		}

		selectedSales.ProductName.let {
			val toolbarTextView: TextView = findViewById<View>(R.id.toolbarTV) as TextView
			toolbarTextView.text = selectedSales.ProductName
		}

		amt = if (intent.getStringExtra("txnAmt") == null) "0.00" else intent.getStringExtra("txnAmt")!!
		(findViewById<View>(R.id.textView_amountDisplay) as TextView).text = amt
		textAmt = Utils.zeroPadding(amt.replace(".", ""), 12)
		TransData.txnTypeLabel = "Sale"
		TransData.isUPIQR = intent.getBooleanExtra("isUPIQR", false)
		TransData.amount = textAmt.toLong()
		posReference = intent.getStringExtra("posReference") ?: "-"
		TransData.posReference = posReference
		timerTextView = findViewById(R.id.timerTV)
		denominationType = intent.getStringExtra("denomination_type")
		val tempDenomination = intent.getStringExtra("denomination_product")
		dbModelDenominationList = Gson().fromJson(tempDenomination, DbModelDenominationList::class.java)

		val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarCP)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener {
			customOnBackPress()
		}

		mContext = this@GenerateQrActivity
		helperlogClassName = this::class.qualifiedName.toString()
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Generate QR (customer scans terminal QR) Sale"
		)
		helperLog.appendLine(helperLogClassName, "Generate QR opened")
		helperLog.appendLine(helperLogClassName, "Amount entered :: $amt")
		helperLog.appendLine(helperLogClassName, "QR product :: ${selectedSales.QrProductCode ?: "-"}, isUPIQR ${TransData.isUPIQR}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		isForceStop = false
		environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		// The VMC can abort the vend while this QR is on screen (VEND CANCEL / RESET / reader
		// disable). MdbController broadcasts that as UiEvent.MdbVendingForceEnd. Without this
		// collector the QR screen was the only MDB payment screen that never reacted, so the
		// terminal kept showing a payable QR after the VMC had already been told the vend was
		// denied. CardPaymentActivity polls the flag in its card-search loop instead; there is
		// no equivalent loop here, which is why this one is event-driven.
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				AppBus.uiEvents.collect { event ->
					if (event is UiEvent.MdbVendingForceEnd) {
						onMdbVendingForceEnd()
					}
				}
			}
		}
		CoroutineScope(Dispatchers.IO).launch {
			processGenerateQrResult()
		}
	}

	/**
	 * helperLog is lateinit and assigned in onCreate. onStop / onDestroy / abortSession can all
	 * fire before that assignment completes if onCreate threw earlier (the layout-resolution and
	 * findViewById work above it can), so fall back to the old Timber path instead of throwing on
	 * a payment screen -- same shape as EmvActivity.logEmv.
	 */
	private fun logGenQr(msg: String) {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, msg)
		} else {
			Utils.debugLogPrint(TAG, msg)
		}
	}

	// Called by HTTPServer when the ECR cancels the session (TransactionType 0) while this
	// flow still owns TransData -- makes the enquiry loop below stop retrying promptly instead
	// of running its full course and clobbering a transaction that took over in the meantime.
	//
	// Setting `isForceStop` alone is not enough here: processGenerateQrResult() ends with
	// `if(!isForceStop) navigationToResultPage()` -- a pre-existing gate meant for the
	// onDestroy()/cancelGenerateQrPayload() cases, where either the activity is going away or
	// navigation already happened elsewhere. Routing an ECR abort through the same flag meant
	// that gate silently swallowed navigation too: the loop would stop, but nothing then
	// finished the activity or showed a result -- the terminal was left stuck on the QR screen.
	// Restore the pre-fix behavior (force-navigate home) explicitly here.
	fun abortSession() {
		isForceStop = true
		logGenQr("User Cancel :: ECR aborted the QR session (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}), navigate -> home screen")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		runOnUiThread {
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(newIntent)
			finish()
		}
	}

	/**
	 * VMC aborted the vend while the QR was on screen (VEND CANCEL / RESET / reader disable).
	 *
	 * MdbController has ALREADY answered the VMC (sendVendDenied on the receive thread), so this
	 * must not send VEND DENIED again - the same rule DenominationPaymentOptionActivity follows.
	 *
	 * Unlike the card screen there is a live artefact to clean up: the QR on display stays
	 * payable at the acquirer. Walking away without cancelling it lets a customer pay for a vend
	 * the VMC has already denied - money taken, nothing dispensed, and this screen is gone so
	 * nothing voids it. The payload is cancelled with the same QrCancelHandler the user-initiated
	 * exit uses, on a background thread because it is a blocking host call.
	 */
	private fun onMdbVendingForceEnd() {
		if (isFinishing || isForceStop) return

		// Money has already moved: never tear down a completed payment. Let the enquiry flow reach
		// navigationToResultPage() so the transaction is still recorded and voidable - cancelling a
		// paid payload would be trying to cancel something the acquirer has already settled.
		if (respCode == "0000") {
			logGenQr("Vend force-end IGNORED :: QR already approved (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}), letting the result flow finish")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		// Stops the enquiry loop within <=500ms (interruptibleDelayMili polls this), and suppresses
		// its trailing `if(!isForceStop) navigationToResultPage()` so it cannot race the navigation
		// below. Same trap abortSession() documents.
		isForceStop = true
		stopTimer = true
		vendAbortPending = true
		// Clearing mdbVending stops navigationToResultPage()/customOnBackPress from firing a second
		// VEND DENIED if either still runs. Assigning false never re-broadcasts - the setter on
		// mdbVendingForceEnd only emits on true.
		MdbController.mdbVending = false
		MdbController.mdbVendingForceEnd = false
		logGenQr("Vend force-end :: VMC aborted the vend (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}), cancelling payload -> home screen")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)

		object : Thread() {
			override fun run() {
				super.run()
				// Routed through cancelPayloadOnce so this cannot cancel a payload that generation
				// never created, nor double-cancel one the post-generation guard already handled.
				cancelPayloadOnce("abort thread")
				runOnUiThread { navigateHomeAfterVendAbort() }
			}
		}.start()
	}

	/**
	 * Cancel the payload exactly once, and only when one actually exists.
	 *
	 * Two callers race here: the thread spawned by onMdbVendingForceEnd(), and the post-generation
	 * guard in processGenerateQrResult(). Whichever arrives first claims it; the other is a no-op.
	 * If generation never produced a payload there is nothing at the acquirer, so cancelling would
	 * both fail and raise a misleading warning.
	 */
	private fun cancelPayloadOnce(reason: String) {
		if (!qrPayloadCreated) {
			logGenQr("Vend abort ($reason) :: no payload was created, nothing to cancel")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}
		if (!payloadCancelClaimed.compareAndSet(false, true)) return
		logGenQr("Vend abort ($reason) :: cancelling payload refId $qrRefId")
		cancelQrPayloadForVendAbort()
	}

	/**
	 * Cancel the generated payload at the acquirer after a VMC abort.
	 *
	 * Deliberately separate from cancelGenerateQrPayload(): that one sends the user-exit path to
	 * the result screen on success and stays put on failure, neither of which is right here - a
	 * VMC abort always goes home. Best effort; a failed cancel means the QR may still be payable,
	 * so it is logged as a warning rather than swallowed.
	 */
	private fun cancelQrPayloadForVendAbort() {
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"Cancel QR Payload (vend abort)",
			GenerateQrActivity::class.java.simpleName,
			GenerateQrActivity::class.java.name,
		)
		try {
			val merchantConfig = ServiceHolder.getMerchantInfo()
			val qrCancelResp = duringHostCall {
				QrCancelHandler(environmentManager).invoke(
					log,
					merchantConfig?.QrMid ?: "",
					merchantConfig?.QrTid ?: "",
					qrRefId
				)
			}
			val isCancelSuccess = qrCancelResp.IS_CANCEL_SUCCESS ?: false
			log.appendLine(helperlogClassName, "Vend abort :: QR payload cancel for refId $qrRefId -> success=$isCancelSuccess")
			if (!isCancelSuccess) {
				log.appendLine(helperlogClassName, "WARNING :: payload $qrRefId may still be payable - a customer could pay a vend the VMC already denied")
				log.logToFile(EnumLogFileName.TerminaLogException)
			}
		} catch (ex: Exception) {
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "QrCancelHandler (Exception) on vend abort -> ", ex.toString())
			log.appendLine(helperlogClassName, "WARNING :: payload $qrRefId may still be payable")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}
		log.logToFile(EnumLogFileName.TerminaLog)
	}

	/** Home after a VMC abort - the same explicit navigation abortSession() has to do. */
	private fun navigateHomeAfterVendAbort() {
		if (isFinishing) return
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	// Util.DelayMili() is a plain blocking sleep with no interruption point, so a straight
	// 5s call would keep this loop alive up to 5s after an abort. Poll in short steps instead.
	private fun interruptibleDelayMili(totalMs: Int, stepMs: Int = 500) {
		var remaining = totalMs
		while (remaining > 0 && !isForceStop && TransData.isCurrentSession(txnSession)) {
			val step = if (stepMs < remaining) stepMs else remaining
			Util.DelayMili(step)
			remaining -= step
		}
	}

	private suspend fun processGenerateQrResult() = withContext(Dispatchers.IO) {
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"Generate QR Activity",
			GenerateQrActivity::class.java.simpleName,
			GenerateQrActivity::class.java.name,
		)

		val currDT = Date()
		qrTxnDt = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)
		qrRefId = SimpleDateFormat(EnumDateFormat.yyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)

		ServiceHolder.getMerchantInfo()?.let { merchantConfig ->
			startProgressDialog(mContext, "Downloading QR", "Loading...")
			TransData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
			TransData.qrRef = qrRefId
			// TransData.acqCode is set later, in navigationToResultPage() -- writing it here
			// unconditionally, before the enquiry loop even starts, let a stale retry clobber
			// a newer transaction's acqCode. See obsidian FIX-2026-08-03 hole 2.
			ServiceHolder.saleModelCache?.let {
				TransData.salesType = it.SalesType
			}
			denominationType?.let {
				TransData.denominationType = it
			}
			dbModelDenominationList?.let {
				TransData.denominationProduct = dbModelDenominationList
			}

			// The abort can land before generation starts. Creating the payload now would put a
			// payable QR at the acquirer with nothing left on screen to cancel it, so stop here; the
			// trailing `if(!isForceStop) navigationToResultPage()` below is already suppressed.
			if (vendAbortPending) {
				closeProgressDialog()
				log.appendLine(helperlogClassName, "Vend aborted before payload generation :: QR not generated")
				log.logToFile(EnumLogFileName.TerminaLog)
				return@let
			}

			if(qrPayloadGenerate(log, merchantConfig)) {
				// The payload now exists at the acquirer - from here on an abort must cancel it.
				qrPayloadCreated = true
				// The abort landed while the payload was being generated, so the abort thread ran
				// before it existed and correctly did nothing. It exists now - cancel it here or it
				// stays payable for an already-denied vend.
				if (vendAbortPending) {
					closeProgressDialog()
					cancelPayloadOnce("during payload generation")
					return@let
				}
				try{
					if(barcodeQR.isNotEmpty()){
						CoroutineScope(Dispatchers.IO).launch {
							runOnUiThread {
								(findViewById<View>(R.id.barcodeImg) as ImageView).setImageBitmap(GenerateQr().createZxingBitmap(mContext, barcodeQR))
								(findViewById<View>(R.id.barcodeImg) as ImageView).visibility = View.VISIBLE
							}
						}
					}

					if(payloadQR.isNotEmpty()){
						CoroutineScope(Dispatchers.IO).launch {
							var scaledBitmap: Bitmap? = null
							var qrCodeColor = R.color.black
							qrProductModel?.let {
								it.qrColor?.let { qrColor ->
									qrCodeColor = qrColor
								}
								it.qrOverlay?.let { _ ->
									val oriBitmap: Bitmap? = BitmapFactory.decodeResource(mContext.resources, it.qrOverlay!!)
									oriBitmap?.let { _ ->
										scaledBitmap = Bitmap.createScaledBitmap(
											oriBitmap, Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!),
											Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false
										)
									}
								}
							}
							runOnUiThread {
								(findViewById<View>(R.id.qrImg) as ImageView).setImageBitmap(
									GenerateQr().createZxingBitmap(mContext, payloadQR, "qrCode", scaledBitmap, ContextCompat.getColor(mContext, qrCodeColor))
								)
							}
						}
					}

					if (payloadImage.isNotEmpty()) {
						var scaledBitmap: Bitmap? = null
						var qrCodeColor = R.color.black
						qrProductModel?.let {
							it.qrColor?.let { qrColor ->
								qrCodeColor = qrColor
							}
							it.qrOverlay?.let { _ ->
								val oriBitmap: Bitmap? = BitmapFactory.decodeResource(
									mContext.resources, it.qrOverlay!!
								)
								oriBitmap?.let { _ ->
									scaledBitmap = Bitmap.createScaledBitmap(
										oriBitmap, Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!),
										Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false
									)
								}
							}
						}
						val qrImageByte = Base64.decode(payloadImage, Base64.DEFAULT)
						val decodedQRImage = BitmapFactory.decodeByteArray(qrImageByte, 0, qrImageByte.size)

						runOnUiThread {
							(findViewById<View>(R.id.qrImg) as ImageView).setImageBitmap(decodedQRImage)
						}
					}

					closeProgressDialog()
					startIncrementTimer()
					TransData.qrRespCode = "1100"
					var enquiryLoop = 3
					// Loop start only -- a line per tick was pure volume; the per-attempt outcome
					// is already visible in enquiryQrTransaction's own response/exception lines.
					log.appendLine(helperlogClassName, "QR Enquiry Loop start :: refId $qrRefId, up to 3 attempts")
					while(enquiryLoop > 0 && !isForceStop && TransData.isCurrentSession(txnSession)) {
						enquiryLoop--

						if(enquiryQrTransaction(log, merchantConfig)){
							enquiryLoop = 0
						} else {
							interruptibleDelayMili(5 * 1000) //delay 5 second
						}
					}
					if(isForceStop || !TransData.isCurrentSession(txnSession)){
						log.appendLine(helperlogClassName, "QR Enquiry Loop aborted (session terminated / superseded)")
					}
					log.appendLine(helperlogClassName, "QR payment result :: refId $qrRefId, respCode $respCode, respDesc $respDesc, brand $qrRespPayBrand, hostRef $qrRespHostRefNo, apprCode $qrRespApprovalCode")
				} catch (e: WriterException) {
					e.printStackTrace()
					log.appendLine(helperlogClassName, "Exception in display QR/Barcode", e.toString())
					log.logToFile(EnumLogFileName.TerminaLogException)
					Utils.DelayMili(1000)
					closeProgressDialog()
				}
			}else{
				log.appendLine(helperlogClassName, "REJECT :: QR payload generation failed (respCode $respCode, respDesc $respDesc)")
				Utils.DelayMili(1000)
				closeProgressDialog()
			}
		}

		if(!isForceStop){
			log.appendLine(helperlogClassName, "Generate Qr end Automatically")
			log.logToFile(EnumLogFileName.TerminaLog)
			navigationToResultPage()
		}
		log.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun qrPayloadGenerate(log: HelperLog, merchantInfo: DbModelMerchantConfig): Boolean{
		var result = false
		log.appendLine(helperlogClassName, "Getting QR Payload")
		val generateQrHandler = GenerateQrHandler(environmentManager)

		try {
			val generateQrResp = duringHostCall {
				generateQrHandler.invoke(
					log,
					textAmt,
					qrTxnDt,
					merchantInfo.QrMid!!,
					merchantInfo.QrTid!!,
					qrRefId,
					selectedSales.QrProductCode!!,
					posReference
				)
			}
			log.appendLine(helperlogClassName, "GenerateQrHandler Response -> ", generateQrResp.toString())

			respCode = "1100"
			qrRespTxnRefNo = generateQrResp.QR_TXN_REFNO ?: ""
			qrRespHostRefNo = generateQrResp.QR_HOST_REFNO ?: ""
			qrRespPayBrand = generateQrResp.QR_PAY_BRAND ?: ""
			qrRespPayBrandDesc = generateQrResp.QR_PAY_BRAND_DESC ?: ""
			/*TransData.qrRespCode = "1100"
			TransData.qrTxnRef = generateQrResp.QR_TXN_REFNO ?: ""
			TransData.qrHostRef = generateQrResp.QR_HOST_REFNO ?: ""
			TransData.qrPayBrand = generateQrResp.QR_PAY_BRAND ?: ""
			TransData.qrPayBrandDesc = generateQrResp.QR_PAY_BRAND_DESC ?: ""*/
			acqMid = generateQrResp.ACQ_MID ?: ""
			acqTid = generateQrResp.ACQ_TID ?: ""
			barcodeQR = generateQrResp.BARCODE_QR ?: ""
			payloadQR = generateQrResp.PAYLOAD_QR ?: ""
			payloadImage = generateQrResp.PAYLOAD_IMAGE ?: ""
			result = true
		} catch (ex: Exception) {
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "GenerateQrHandler (Exception) -> ", ex.toString())

			try {
				val errorResponse = Gson().fromJson(ex.message, GenerateQrResponseModel::class.java)
				respCode = errorResponse.RESP_CODE ?: "1100"
				respDesc = errorResponse.RESP_DESC ?: ""
				qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
				qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
				qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: qrRespPayBrand
				qrRespPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: qrRespPayBrandDesc
				/*TransData.qrRespCode = errorResponse.RESP_CODE ?: "1100"
				TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
				TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
				TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
				TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: TransData.qrPayBrand
				TransData.qrPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: TransData.qrPayBrandDesc*/
			} catch (jsonEx: Exception) {
				log.appendLine(helperlogClassName, "Json Exception in Error -> ", jsonEx.toString())
			}
		} finally {
			CoroutineScope(Dispatchers.IO).launch {
				insertTransactionIntoDB()
			}
		}
		return result
	}

	private fun enquiryQrTransaction(log: HelperLog, merchantConfig: DbModelMerchantConfig): Boolean{
		var result = false
		log.appendLine(helperlogClassName, "Enquiring Transaction Result")
		val qrEnquiryHandler = QrEnquiryHandler(environmentManager)

		try {
			val qrEnquiryResp = duringHostCall {
				qrEnquiryHandler.invoke(
					log,
					merchantConfig.QrMid ?: "",
					merchantConfig.QrTid ?: "",
					qrRefId
				)
			}
			log.appendLine(helperlogClassName, "QrEnquiryHandler Response -> ", qrEnquiryResp.toString())

			/*TransData.qrRespCode = qrEnquiryResp.RESP_CODE ?: ""
			TransData.qrHostRef = qrEnquiryResp.QR_HOST_REFNO ?: ""
			TransData.qrTxnRef = qrEnquiryResp.QR_TXN_REFNO ?: ""
			TransData.qrApprovalCode = qrEnquiryResp.BANK_AUTH_CODE ?: ""
			TransData.qrPayBrand = qrEnquiryResp.QR_PAY_BRAND ?: ""
			TransData.qrPayBrandDesc = qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""
			TransData.isTpaAccount = qrEnquiryResp.IS_TPA_ACCOUNT ?: false */

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
						/*TransData.qrRespCode = it
						TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
						TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
						TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
						TransData.qrApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
						TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: TransData.qrPayBrand
						TransData.qrPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: TransData.qrPayBrandDesc
						TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false*/
						respCode = it
						respDesc = errorResponse.RESP_DESC ?: ""
						qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
						qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
						qrRespApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
						qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: qrRespPayBrand
						qrRespPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: qrRespPayBrandDesc
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
			jsonObject.addProperty("POS_REF_NO", posReference)
			jsonObject.addProperty("CreationDate", SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(Date()))
			jsonObject.addProperty("LastUpdateDt", "")
			jsonObject.addProperty("IsProcessing", "true")
			jsonObject.addProperty("IsSend", "true")
		} catch (e: Exception) {
			e.printStackTrace()
		}

		val dbModel = Gson().fromJson(jsonObject.toString(), DbModelReceiptUpload::class.java)
		ReceiptUploadRepo.insertToDbByModel(this@GenerateQrActivity, dbModel)
		//TODO Receipt Upload

		//TODO Transaction QR
		val transQrModel = DbModelTransactionQr("Sale", qrTxnDt, "", textAmt, "GENERATE", selectedSales.Product ?: "-",
			qrRespPayBrand, qrRespPayBrandDesc, acqMid, acqTid, selectedSales.AcqCode ?: "-", qrRefId, qrRespHostRefNo, qrRespTxnRefNo, qrRespApprovalCode,
			respCode, respDesc, isUnionPayTxn, TransData.upiVoucherCode, TransData.upiDiscountAmt, TransData.upiMarkupFee, posReference, isTpaAccount.toString())
		TransactionQrRepo.insertToDb(this@GenerateQrActivity, transQrModel)
		//TODO Transaction QR
	}

	private fun updateTransactionResult() {
		val criteriaHM = hashMapOf<Any, Any>(
			"refId" to qrRefId
		)
		val valueHM = hashMapOf<Any, Any>(
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
			"RRN" to qrRespHostRefNo,
			"RESP_CODE" to respCode,
		)
		ReceiptUploadRepo.updateData(mContext, receiptUploadValue, receiptUploadCriteria)
	}

	private fun navigationToResultPage() {
		stopTimer = true

		if(!TransData.isCurrentSession(txnSession)){
			// Another transaction now owns TransData (this flow was stuck in the enquiry
			// loop, or the ECR moved on after a cancel). Persist this QR flow's own result --
			// it's keyed by qrRefId, not TransData -- but do NOT write into the now-foreign
			// TransData and do NOT navigate into a result screen for a transaction that isn't
			// ours anymore.
			logGenQr("navigationToResultPage :: stale session ($txnSession vs " +
				"${TransData.sessionId}) -- skip TransData write & result navigation")
			if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
			updateTransactionResult()
			finish()
			return
		}

		/*ServiceHolder.selectedCacheModel?.let {
			val tempModel = ServiceHolder.selectedCacheModel as SalesModel
			//tempModel.AcqMid = Utils.maskString(acqMid, 4)
			//tempModel.AcqTid = Utils.maskString(acqTid, 4)
			ServiceHolder.selectedCacheModel = tempModel
		}*/
		TransData.mid = acqMid
		TransData.tid = acqTid
		TransData.acqCode = selectedSales.AcqCode ?: ""
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

		if (upiVoucherCode != "") {
			val finalAmt = textAmt.toLong() - upiDiscountAmt.toLong()
			TransData.upiFinalAmount =  Utils.getActualAmount(finalAmt.toString())
		}
		//insertTransactionIntoDB()
		updateTransactionResult()

		var newIntent = Intent(mContext, TransactionResultQrActivity::class.java)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "DENOMINATION")) {
			if(isOnBackPress) {
				if(MdbController.mdbVending) {
					MdbController.sendVendDenied()
				}
				newIntent = Intent(mContext, AttendDenominationActivity::class.java)
			} else {
				newIntent = Intent(mContext, DenominationTransactionResultActivity::class.java)
			}
		} else if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
			newIntent = Intent(mContext, DenominationTransactionResultActivity::class.java)
		}

		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		logGenQr("Validation passed :: navigate -> ${newIntent.component?.className ?: "-"}")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		mContext.startActivity(newIntent)
		finish()
	}

	private fun startIncrementTimer() {
		object : Thread() {
			override fun run() {
				super.run()
				var incrementTimer = 1

				while (true) {
					if (stopTimer) break
					val minutes = (incrementTimer % 3600) / 60;
					val seconds  = incrementTimer % 60;
					val timerString = String.format("%02d:%02d", minutes, seconds)
					runOnUiThread {
						timerTextView.text = timerString
					}
					Utils.DelayMili(1000)
					incrementTimer++
				}
			}
		}.start()
	}

	fun customOnBackPress() {
		logGenQr("Dialog opened :: [CONFIRM EXIT QR PAYMENT]")
		val builder = AlertDialog.Builder(mContext)
		builder.setMessage("Are you sure to Exit? \nThis transaction will be treat as fail transaction")
			.setCancelable(false)
			.setPositiveButton("Yes") { dialog, id ->
				logGenQr("Selected :: Yes [CONFIRM EXIT QR PAYMENT]")
				// Delete selected note from database
				object : Thread() {
					override fun run() {
						super.run()
						isOnBackPress = true
						cancelGenerateQrPayload()
					}
				}.start()
			}
			.setNegativeButton("No") { dialog, id ->
				logGenQr("Selected :: No [CONFIRM EXIT QR PAYMENT] -- stay on QR screen")
				if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
				// Dismiss the dialog
				dialog.dismiss()
			}
		val alert = builder.create()
		alert.show()
	}

	fun cancelGenerateQrPayload() {
		startProgressDialog(mContext, "", "Loading...")
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"Cancel QR Payload",
			GenerateQrActivity::class.java.simpleName,
			GenerateQrActivity::class.java.name,
		)
		log.appendLine(helperlogClassName, "Activity Cancel By User")
		log.appendLine(helperlogClassName, "Cancel QR Payload")
		val qrCancelHandler = QrCancelHandler(environmentManager)

		try {
			val merchantConfig = ServiceHolder.getMerchantInfo()
			val qrCancelResp = duringHostCall {
				qrCancelHandler.invoke(
					log,
					merchantConfig?.QrMid ?: "",
					merchantConfig?.QrTid ?: "",
					qrRefId
				)
			}
			log.appendLine(helperlogClassName, "QrCancelHandler Response -> ", qrCancelResp.toString())

			val isCancelSuccess = qrCancelResp.IS_CANCEL_SUCCESS ?: false
			log.appendLine(helperlogClassName, "User Cancel :: QR cancel result for refId $qrRefId -> success=$isCancelSuccess")
			if(isCancelSuccess){
				//Success
				isForceStop = true
				log.logToFile(EnumLogFileName.TerminaLog)
				navigationToResultPage()
			}
		}catch (ex: Exception){
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "QrCancelHandler (Exception) -> ", ex.toString())
			log.logToFile(EnumLogFileName.TerminaLogException)
			runOnUiThread {
				Toast.makeText(mContext, "Fail to Cancel QR", Toast.LENGTH_SHORT).show()
			}
		}
		closeProgressDialog()
		log.logToFile(EnumLogFileName.TerminaLog)
	}

	// Replaces android:noHistory on this activity. The requirement noHistory encoded stands and is
	// arguably stronger here than for card: a leftover QR screen surfaced by an App2App / ECR launch
	// would show a *live, payable* QR code for a previous transaction. So the default is unchanged --
	// backgrounded means finished, and onDestroy still sets isForceStop so the flow abandons.
	//
	// The one case that must not be torn down is a host call mid-execution: killing the screen
	// there discards a response that may already say "paid". Deferring lets the call return and
	// the flow reach navigationToResultPage(), which finishes this activity itself.
	// See obsidian FIX-2026-08-04-noHistory-MidAuthorization-Teardown.
	override fun onStop() {
		super.onStop()
		if (isFinishing) return

		if (hostCallInFlight) {
			logGenQr("Backgrounded with QR host call in flight :: deferring finish")
			return
		}

		logGenQr("Backgrounded while idle :: finishing (noHistory equivalent)")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
		finish()
	}

	override fun onDestroy() {
		super.onDestroy()
		isForceStop = true
		logGenQr("GenerateQr OnDestroy :: QR payment screen ended")
		if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
	}
}