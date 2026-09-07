package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.java.utils.EmvUtil
import com.sc.mf919.kotlin.database.model.*
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.*
import env.EnvironmentManager
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import tms.handlers.VoidQrHandler
import tms.models.VoidQrResponseModel
import java.text.SimpleDateFormat
import java.util.*

class VoidQrActivity : BaseActivity() {
	var helperlogClassName:String = ""
	lateinit var helperLog: HelperLog
	lateinit var mContext: Context
	lateinit var tempModel: String

	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	var dbModelTransactionQrGet: DbModelTransactionQrGet? = null
	var posReference: String? = null

	/**
	 * Skip the confirmation dialog and void immediately - same contract as VoidSaleActivity's
	 * forceVoid. Needed by the MDB vend-failure auto-void: on an unattended machine there is
	 * nobody to press Confirm, so without this the refund would sit on a dialog forever.
	 * Note it does NOT bypass the VOID_WITH_PIN gate, exactly as on the card side.
	 */
	var forceVoid: Boolean = false
//	private val isoDb = IsoDb()
//	var qrPayInfo: Array<String>? = null
//	var amt = ""

	//var qrPayBrand = ""
	//var qrSeqNo = ""
	var qrTxnDt = ""
	var qrRespRefId = ""
	var terminalPw = ""

	// voidPayment() response, held locally instead of round-tripping through TransData --
	// doInBackground() persists the void's own DB record (keyed by dbModelTransactionQrGet's
	// id/refId, independent of TransData) from these immediately after the network call
	// returns, so that record can never be corrupted by a newer transaction's TransData writes
	// landing in the gap between "network call returns" and "DB write happens". See obsidian
	// FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
	var voidRespCode = ""
	var voidRespDesc = ""
	var voidQrRefId = ""
	var voidTxnRefNo = ""
	var voidIsTpaAccount = false

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_voidqrsale)
		mContext = this@VoidQrActivity
		TransData.reset(applicationContext)

		tempModel = ServiceHolder.getDeviceModel().uppercase()
		helperlogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperlogClassName,
			helperlogClassName,
			"Void QR Sale Activity"
		)
		helperLog.appendLine(helperlogClassName, "Initialize VoidQr Activity")
		dbModelMerchantConfig = ServiceHolder.getMerchantInfo()

		posReference = intent.getStringExtra("posReference")
		terminalPw = intent.getStringExtra("terminalPIN").toString()
		forceVoid = intent.getIntExtra("forceVoid", 0) == 1
		helperLog.appendLine(helperlogClassName, "Force Void :: $forceVoid")
		@SuppressLint("SimpleDateFormat") val timeStamp = SimpleDateFormat("yyMMdd").format(
			Calendar.getInstance().time
		)
		val keypad = findViewById<Keypad>(R.id.keypad_void)
		val tv = findViewById<TextView>(R.id.textView_voidInvNo)
		tv.text = timeStamp
		keypad.setFilter(tv, false, 18)
		keypad.registerOnOKEventListener(mListener)

		val invoice = intent.getStringExtra("Invoice")
		if (invoice != null) {
			tv.text = invoice
			val terminalConfig = ServiceHolder.getTerminalConfig()
			if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
				helperLog.appendLine(helperlogClassName, "Dialog opened :: [VOID PIN]")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				PINDialog("", false,
					onPinConfirmed = { pin, type ->
						helperLog.appendLine(helperlogClassName, "Selected :: PIN confirmed [VOID PIN]")
						helperLog.appendLine(helperlogClassName, "Void target QR refId :: $invoice")
						terminalPw = pin
						lifecycleScope.launch {
							searchByInvoice(invoice)
						}
					}, onPinCancel = {
						helperLog.appendLine(helperlogClassName, "User Cancel :: PIN entry abandoned, QR void of refId $invoice not started")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						if (ServiceHolder.appIntent) {
							val txnMap = HashMap<String, String>()
							txnMap["ResponseCode"] = "SHC005"
							txnMap["ResponseDescription"] = "User Cancel the Transaction"
							onBackToApp(txnMap)
						} else if (ServiceHolder.appHTTP) {
							val jObject = JSONObject()
							try {
								jObject.put("ResponseCode", "SHC005")
								jObject.put("ResponseDescription", "User Cancel the Transaction")
							} catch (e: JSONException) {
								e.printStackTrace()
							}
							HTTPServer.getInstance().setResponseMessage(jObject.toString())
							customOnBackPress()
						}
					})
			} else {
				helperLog.appendLine(helperlogClassName, "Void target QR refId :: $invoice")
				lifecycleScope.launch {
					searchByInvoice(invoice)
				}
			}
		}
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	var mListener =
		onKeypadEventListener { isOK, msg ->
			if (isOK) {
				if (msg == null) {
					customOnBackPress()
				} else {
					helperLog.appendLine(helperlogClassName, "Keypad OK :: void target QR refId $msg")
					lifecycleScope.launch {
						searchByInvoice(msg)
					}
				}
			}
		}

	/*private fun searchForInvoice(invoiceNum: String) {
		startProgressDialog(mContext, "Finding the Transaction", "Searching...")
		searchForInvoiceExt(invoiceNum)
	}
	private fun searchForInvoiceExt(refId: String) {
		object : Thread() {
			override fun run() {
				super.run()
				qrPayInfo = isoDb.getQrPayTable(refId)
				Utils.DelayMili(1000)
				closeProgressDialog()
				Utils.debugLogPrint(TAG, "run: " + Arrays.toString(qrPayInfo))
				if (qrPayInfo == null) {
					if (ServiceHolder.appHTTP || ServiceHolder.appIntent) {
						runOnUiThread {
							if (Looper.myLooper() == null) {
								Looper.prepare()
							}
							Toast.makeText(mContext, "Invalid Input", Toast.LENGTH_SHORT).show()
							//Looper.loop()
							if (ServiceHolder.appIntent) {
								val txn_map: HashMap<String, String> = HashMap()
								txn_map["ResponseCode"] = "SHC001"
								txn_map["ResponseDescription"] = "Invalid Transaction ID"
								onBackToApp(txn_map)
							} else if (ServiceHolder.appHTTP) {
								val jObject = JSONObject()
								try {
									jObject.put("ResponseCode", "SHC001")
									jObject.put(
										"ResponseDescription",
										"Invalid Transaction Invoice"
									)
								} catch (e: JSONException) {
									e.printStackTrace()
								}
								HTTPServer.getInstance().setResponseMessage(jObject.toString())
								customOnBackPress()
							}
						}
					} else {
						Looper.prepare()
						Toast.makeText(mContext, "Cannot Find Transaction", Toast.LENGTH_SHORT)
							.show()
						Looper.loop()
					}
					return
				}
				handlerVoid.sendMessage(Message())
			}
		}.start()
	}*/

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun searchByInvoice(qrRefId: String){
		helperLog.appendLine(helperlogClassName, "Start Search QR txn by refId :: $qrRefId")
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			val criteriaList = listOf("refId", "respCode", "txnType")
			val valueList = listOf(qrRefId, "0000", "Sale")
			dbModelTransactionQrGet = TransactionQrRepo.getSingleTransactionQr(applicationContext, criteriaList, valueList)
			dbModelTransactionQrGet?.let {
				helperLog.appendLine(helperlogClassName, "QR Transaction Record found :: refId ${it.refId} / hostRef ${it.hostRefNo} / amount ${it.txnAmount}")
				handlerVoid.sendMessage(Message())
			} ?: run {
				helperLog.appendLine(helperlogClassName, "REJECT :: QR refId $qrRefId not found for an approved Sale")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				runOnUiThread {
					ToastMake(mContext, "Invalid Input", Toast.LENGTH_SHORT)
				}
				if (ServiceHolder.appIntent) {
					val txnMap = HashMap<String, String>()
					txnMap["ResponseCode"] = "SHC001"
					txnMap["ResponseDescription"] = "Invalid Ref ID"
					onBackToApp(txnMap)
				} else if (ServiceHolder.appHTTP) {
					val jObject = JSONObject()
					try {
						jObject.put("ResponseCode", "SHC001")
						jObject.put("ResponseDescription", "Invalid Ref ID")
					} catch (e: JSONException) {
						e.printStackTrace()
					}
					HTTPServer.getInstance().setResponseMessage(jObject.toString())
					customOnBackPress()
				}
			}
		}

		startProgressDialog(mContext, "Finding the Transaction", "Searching...")
		startCoroutine.join()
		closeProgressDialog()
	}

	@SuppressLint("SetTextI18n")
	private fun voidSaleDialog() {
		if (forceVoid) {
			// Unattended auto-void (MDB vend failure): nobody is there to confirm. Do exactly what
			// the Confirm button does, minus the dialog.
			helperLog.appendLine(helperlogClassName, "Force Void :: skipping [VOID QR CONFIRMATION]")
			helperLog.appendLine(helperlogClassName, "Start Void QR Transaction :: refId ${dbModelTransactionQrGet?.refId} / hostRef ${dbModelTransactionQrGet?.hostRefNo}")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			ServiceHolder.appRunningProcess = true
			VoidTxn().execute()
			return
		}

		helperLog.appendLine(helperlogClassName, "Dialog opened :: [VOID QR CONFIRMATION]")
		helperLog.appendLine(helperlogClassName, "Void target :: refId ${dbModelTransactionQrGet?.refId} / hostRef ${dbModelTransactionQrGet?.hostRefNo} / brand ${dbModelTransactionQrGet?.productName}")
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater

		@SuppressLint("InflateParams")
		val dialogView = inflater.inflate(R.layout.activity_voidqr_confirmation, null)
		//dialogView.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		(dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = "RM" + Utils.getActualAmount(dbModelTransactionQrGet?.txnAmount)
		(dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = dbModelTransactionQrGet?.productName ?: "-"
		(dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = dbModelTransactionQrGet?.hostRefNo ?: "-"
		(dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = dbModelTransactionQrGet?.refId ?: "-"

		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperlogClassName, "User Cancel :: dismissed [VOID QR CONFIRMATION], QR void of refId ${dbModelTransactionQrGet?.refId} abandoned")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
			if (ServiceHolder.appIntent) {
				val txn_map: HashMap<String, String> = HashMap()
				txn_map["ResponseCode"] = "SHC005"
				txn_map["ResponseDescription"] = "User Cancel the Transaction"
				onBackToApp(txn_map)
			} else if (ServiceHolder.appHTTP) {
				val jObject = JSONObject()
				try {
					jObject.put("ResponseCode", "SHC005")
					jObject.put("ResponseDescription", "User Cancel the Transaction")
				} catch (e: JSONException) {
					e.printStackTrace()
				}
				HTTPServer.getInstance().setResponseMessage(jObject.toString())
				customOnBackPress()
			}
		}

		val remarkRow = dialogView.findViewById<LinearLayout>(R.id.remark_row)
		remarkRow.visibility = View.GONE

		val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
		voidConfirmBtn.setOnClickListener {
			helperLog.appendLine(helperlogClassName, "Selected :: VOID CONFIRM [VOID QR CONFIRMATION]")
			helperLog.appendLine(helperlogClassName, "Start Void QR Transaction :: refId ${dbModelTransactionQrGet?.refId}")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			ServiceHolder.appRunningProcess = true
			alertDialog?.dismiss()
			VoidTxn().execute()
		}

		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()

		//TODO Dynamic Layout For Small Terminal
		val displayMetrics = resources.displayMetrics
		val screenWidthPx = displayMetrics.widthPixels
		val screenWidthDp = screenWidthPx / displayMetrics.density
		println("screenWidthDp :: $screenWidthDp")

		val dialogWidthPx = if (screenWidthDp < 500) {
			(screenWidthPx * 0.95).toInt()
		} else {
			(screenWidthPx * 0.80).toInt()
		}

		alertDialog?.window?.setLayout(
			dialogWidthPx,
			ViewGroup.LayoutParams.WRAP_CONTENT
		)
		//TODO Dynamic Layout For Small Terminal
	}

	@RequiresApi(Build.VERSION_CODES.O)
	var handlerVoid: Handler = object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			voidSaleDialog()
		}
	}

	@SuppressLint("StaticFieldLeak")
	private inner class VoidTxn : CoroutineTask<String?, Boolean>() {
		override fun onPreExecute() {
			super.onPreExecute()

			startProgressDialog(mContext, "QR Payment Authorization", "Waiting for Approval")
			posReference?.let {
				TransData.posReference = it
				helperLog.appendLine(helperlogClassName, "Add Pos Reference :: $it")
			} ?: run {
				TransData.posReference = dbModelTransactionQrGet?.posRefNo ?: ""
				helperLog.appendLine(helperlogClassName, "Fallback for Pos Reference from Database")
				helperLog.appendLine(helperlogClassName, "Add Pos Reference :: ${dbModelTransactionQrGet?.posRefNo}")
			}
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		override fun doInBackground(vararg params: String?): Boolean {
			val iResp = voidPayment()
			if (!iResp) {
				//display error
				helperLog.appendLine(helperlogClassName, "REJECT :: QR void call failed, resp code $voidRespCode - $voidRespDesc")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
				return false
			}

			//Delete sale record
			/*isoDb.deleteQrPayTable_byRefId(qrRespRefId)*/
			/*isoDb.deletePrintReceiptQrTable_byRefId(qrRespRefId)*/

			//Insert voided record
			/*isoDb.insertQrPayTable(
				"Void",
				qrPayInfo!![3],
				qrTxnDt,
				qrSeqNo,
				qrPayInfo!![6],
				qrPayInfo!![7],
				qrPayInfo!![8],
				qrRespRefId,
				qrPayInfo!![10],
				qrRespTxnRefNo,
				"a",
				"",
				qrPayInfo!![14],
				qrPayInfo!![15],
				qrPayInfo!![16],
				qrPayInfo!![17],
				qrPayInfo!![18],
				qrPayInfo!![19],
				qrPayInfo!![20],
				qrPayInfo!![21],
				qrPayInfo!![22]
			)*/

			/*isoDb.insertPrintReceiptQrTable(
				"Void", qrPayInfo!![3], qrTxnDt, qrPayInfo!![6], qrPayInfo!![7], qrPayInfo!![8],
				qrRespRefId, qrPayInfo!![10], qrRespTxnRefNo, respCode, "a", "", "",
				qrPayInfo!![14], qrPayInfo!![15], qrPayInfo!![16], qrPayInfo!![17], qrPayInfo!![18],
				qrPayInfo!![19], qrPayInfo!![20], qrPayInfo!![21], qrPayInfo!![22]
			)*/

			//TODO Void QR Success
			// This void's own DB record is keyed by dbModelTransactionQrGet's id/refId and
			// persisted from the local voidXxx fields above -- deliberately NOT read back from
			// TransData, which by this point (after the voidQrHandler network call) may already
			// belong to a different, newer transaction. See obsidian
			// FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
			val resolvedPosReference = posReference ?: dbModelTransactionQrGet?.posRefNo ?: ""
			val criteriaHM = hashMapOf<Any, Any>(
				"id" to (dbModelTransactionQrGet?.id ?: "0").toString(),
				"refId" to voidQrRefId
			)
			val valueHM = hashMapOf<Any, Any>(
				"txnType" to "Void",
				"voidDateTime" to qrTxnDt,
				"txnRefNo" to voidTxnRefNo,
				"respCode" to voidRespCode,
				"respDesc" to voidRespDesc,
				"posRefNo" to resolvedPosReference
			)
			TransactionQrRepo.updateTransactionQr(applicationContext, valueHM, criteriaHM)

			val receiptUploadCriteria = hashMapOf<Any, Any>(
				"QrRefId" to voidQrRefId
			)
			val receiptUploadValue = hashMapOf<Any, Any>(
				"TXN_DT" to qrTxnDt,
				"TXN_TYPE" to "Void",
				"POS_REF_NO" to resolvedPosReference
			)
			ReceiptUploadRepo.updateData(mContext, receiptUploadValue, receiptUploadCriteria)
			helperLog.appendLine(helperlogClassName, "Void QR record persisted :: refId $voidQrRefId / txnRef $voidTxnRefNo")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return true
		}

		@SuppressLint("SimpleDateFormat")
		override fun onPostExecute(result: Boolean?) {
			super.onPostExecute(result)
			closeProgressDialog()

			if(!isSessionCurrent()){
				// Another transaction now owns TransData (this task's doInBackground ran on
				// CoroutineTask's detached scope, so it can still complete after a newer
				// transaction has begun). The void's own DB record was already persisted
				// above (updateTransactionQr/ReceiptUploadRepo, keyed by refId -- not
				// TransData), so nothing is lost by not navigating. See obsidian
				// FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
				helperLog.appendLine(helperlogClassName, "onPostExecute :: stale session -- skip TransData write & result navigation")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				finish()
				return
			}

			ServiceHolder.selectedCacheModel?.let {
				val tempModel = ServiceHolder.selectedCacheModel as SalesModel
				tempModel.ProductName = dbModelTransactionQrGet?.payBrand
				tempModel.AcqMid = dbModelTransactionQrGet?.mid
				tempModel.AcqTid = dbModelTransactionQrGet?.tid
				ServiceHolder.selectedCacheModel = tempModel
			}
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			var intent = Intent(mContext, TransactionResultQrActivity::class.java)
			if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
				intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
			}
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

			TransData.txnTypeLabel = "Void"
			TransData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
			// Void response, deferred from voidPayment() -- see the voidRespCode/etc fields.
			TransData.qrRespCode = voidRespCode
			TransData.qrRespDesc = voidRespDesc
			TransData.qrRef = voidQrRefId
			TransData.qrTxnRef = voidTxnRefNo
			TransData.isTpaAccount = voidIsTpaAccount
			TransData.qrPayBrand = dbModelTransactionQrGet?.productCode?: ""
			TransData.acqCode = dbModelTransactionQrGet?.acqCode?: ""
			TransData.qrApprovalCode = dbModelTransactionQrGet?.approvalCode?: ""
			TransData.amount = (dbModelTransactionQrGet?.txnAmount?: "0").toLong()
			TransData.qrHostRef = dbModelTransactionQrGet?.hostRefNo?: ""
			TransData.isUPIQR = dbModelTransactionQrGet?.isUnionPayTxn == "1"
			TransData.upiVoucherCode = dbModelTransactionQrGet?.upiVoucherCode?: ""
			TransData.upiDiscountAmt = dbModelTransactionQrGet?.upiDiscountAmt?: ""
			TransData.upiMarkupFee = dbModelTransactionQrGet?.upiMarkupFee?: ""
			TransData.upiFinalAmount = if (dbModelTransactionQrGet?.upiVoucherCode != "") {
				val finalAmt = (dbModelTransactionQrGet?.txnAmount ?: "0").toLong() - (dbModelTransactionQrGet?.upiDiscountAmt ?: "0").toLong()
				"RM" + Utils.getActualAmount(finalAmt.toString())
			} else { "" }

			helperLog.appendLine(helperlogClassName, "Void QR result :: ${if (voidRespCode == "0000") "APPROVED" else "DECLINED"} ($voidRespCode - $voidRespDesc) for refId $voidQrRefId / hostRef ${dbModelTransactionQrGet?.hostRefNo}")
			helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> ${intent.component?.shortClassName}")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			startActivity(intent)
			finish()
		}
	}

	private fun voidPayment(): Boolean {
		val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"QR Void Activity",
			GenerateQrActivity::class.java.simpleName,
			GenerateQrActivity::class.java.simpleName,
		)

		val environmentManager = EnvironmentManager(getSharedPreferences(packageName, MODE_PRIVATE))
		val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
		qrTxnDt = sdf.format(Date())
		TransData.qrRef = dbModelTransactionQrGet?.refId ?: ""

		var result = false
		log.appendLine(helperlogClassName, "Upload Payload API Request")
		val voidQrHandler = VoidQrHandler(environmentManager)
		var encodedPIN = ""

		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		voidWithPIN = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "VOID_WITH_PIN")
		if (voidWithPIN.toString().toInt() == 1) {
			encodedPIN = TmsHelper.generateEncodedPIN(terminalPw)
		}

		try {
			val voidQrResp = voidQrHandler.invoke(
				log,
				qrTxnDt,
				dbModelTransactionQrGet?.mid ?: "",
				dbModelTransactionQrGet?.tid ?: "",
				dbModelTransactionQrGet?.refId ?: "",
				dbModelTransactionQrGet?.txnRefNo ?: "",
				"refund",
				encodedPIN
			)
			log.appendLine(helperlogClassName, "VoidQrHandler Response -> ", voidQrHandler.toString())
			val isTpa = voidQrResp.IS_TPA_ACCOUNT ?: run {
				dbModelTransactionQrGet?.isTpaAccount == "true"
			}
			voidRespCode = voidQrResp.RESP_CODE ?: ""
			voidRespDesc = voidQrResp.RESP_DESC ?: ""
			voidQrRefId = voidQrResp.QR_REFID ?: ""
			voidTxnRefNo = voidQrResp.QR_TXN_REFNO ?: ""
			voidIsTpaAccount = isTpa
			result = true
			log.appendLine(helperlogClassName, "Void QR host response :: $voidRespCode - $voidRespDesc for refId $voidQrRefId")
			log.logToFile(EnumLogFileName.TerminaLog)
		} catch (ex: Exception) {
			ex.printStackTrace()
			log.appendLine(helperlogClassName, "VoidQrHandler (Exception) -> ", ex.toString())
			log.logToFile(EnumLogFileName.TerminaLogException)

			try {
				val errorResponse = Gson().fromJson(ex.message, VoidQrResponseModel::class.java)
				val isTpa = errorResponse.IS_TPA_ACCOUNT ?: run {
					dbModelTransactionQrGet?.isTpaAccount == "true"
				}
				voidRespCode = errorResponse.RESP_CODE ?: "1100"
				voidRespDesc = errorResponse.RESP_DESC ?: ""
				voidQrRefId = errorResponse.QR_REFID ?: ""
				voidTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
				voidIsTpaAccount = isTpa
			} catch (jsonEx: Exception) {
				log.appendLine(helperlogClassName, "Json Exception in Error -> ", jsonEx.toString())
				log.logToFile(EnumLogFileName.TerminaLogException)
			}
			log.appendLine(helperlogClassName, "Void QR host response (error path) :: $voidRespCode - $voidRespDesc for refId $voidQrRefId")
			log.logToFile(EnumLogFileName.TerminaLog)
		}
		return result
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "User Cancel :: leaving void QR, navigate -> home screen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		ServiceHolder.appIntent = false
		ServiceHolder.appHTTP = false
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	private fun onBackToApp(txn_map: HashMap<String, String>) {
		txn_map["TransactionType"] = ServiceHolder.txnType.toString()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "Returning to calling app :: ${txn_map["ResponseCode"]} - ${txn_map["ResponseDescription"]}")
			helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> TransactionTransmitter")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		val intent = Intent(this, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txn_map", txn_map)
		startActivity(intent)
		finish()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "VoidQr OnDestroy :: void QR screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	override fun onResume() {
		super.onResume()
		val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
		if (myKM.isKeyguardLocked) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				myKM.requestDismissKeyguard(this, null)
			} else window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
		}
	}

	companion object {
		private const val TAG = "Void"
		var voidWithPIN: String? = "0"
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}

		//private fun sysPrint(message: String, data: ByteArray, dataOffset: Int, dataLen: Int) {
		//    Utils.debugLogPrint(TAG, message + HexUtil.bcd2str(data, dataOffset, dataLen))
		//}
	}
}