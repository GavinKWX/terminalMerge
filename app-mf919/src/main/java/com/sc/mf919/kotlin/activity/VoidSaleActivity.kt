package com.sc.mf919.kotlin.activity
import enums.EnumResponseCode

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.library.terminal.Utility
import com.sc.mf919.R
import emv.EmvTag
import constants.TerminalConstants
import com.sc.mf919.java.activity.Keypad
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.UploadTMS
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.onKeypadEventListener
import emv.EmvUtil
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelBatchTable
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.BatchTableRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import helpers.LogRedact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class VoidSaleActivity : BaseActivity() {
	lateinit var mContext: Context
	lateinit var batchTableModel: DbModelBatchTable
	lateinit var tempModel: String

	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	lateinit var amount: String
	lateinit var cardPan: String
	lateinit var transRRN: String
	lateinit var approvalCode: String

	var posReference: String? = null
	var forceVoid: Boolean = false

	companion object {
		private const val TAG = "Void"
	}

	@RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_voidsalerefund)
		Utils.debugLogPrint(TAG, "VoidSale")
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		mContext = this@VoidSaleActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Void Card Payment Activity"
		)
		helperLog.appendLine(helperLogClassName, "Initialize VoidCard Activity")
		TransData.reset(applicationContext)
		posReference = intent.getStringExtra("posReference")
		tempModel = ServiceHolder.getDeviceModel().uppercase()

		forceVoid = intent.getIntExtra("forceVoid", 0) == 1
		helperLog.appendLine(helperLogClassName, "Force Void :: $forceVoid")
		val keypad = findViewById<Keypad>(R.id.keypad_void)
		val tv = findViewById<TextView>(R.id.textView_voidInvNo)
		keypad.setFilter(tv, false, 6)
		keypad.registerOnOKEventListener(mListener)
		val invoice = intent.getStringExtra("Invoice")
		if (invoice != null) {
			tv.text = invoice
			val terminalConfig = ServiceHolder.getTerminalConfig()
			if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
				helperLog.appendLine(helperLogClassName, "Dialog opened :: [VOID PIN]")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				PINDialog("", false,
					onPinConfirmed = { pin, type ->
						helperLog.appendLine(helperLogClassName, "Selected :: PIN confirmed [VOID PIN]")
						helperLog.appendLine(helperLogClassName, "Void target invoice :: $invoice")
						lifecycleScope.launch {
							searchByInvoice(invoice)
						}
					}, onPinCancel = {
						helperLog.appendLine(helperLogClassName, "User Cancel :: PIN entry abandoned, void of invoice $invoice not started")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						if (ServiceHolder.appIntent) {
							val txnMap = HashMap<String, String>()
							txnMap["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
							txnMap["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description
							onBackToApp(txnMap)
						} else if (ServiceHolder.appHTTP) {
							val jObject = JSONObject()
							try {
								jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
								jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
							} catch (e: JSONException) {
								e.printStackTrace()
							}
							HTTPServer.getInstance().setResponseMessage(jObject.toString())
							customOnBackPress()
						}
					})
			} else {
				helperLog.appendLine(helperLogClassName, "Void target invoice :: $invoice")
				lifecycleScope.launch {
					searchByInvoice(invoice)
				}
			}
		}
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	@RequiresApi(Build.VERSION_CODES.O)
    var mListener = onKeypadEventListener { isOK, msg ->
		if (isOK) {
			if (msg == null) {
				customOnBackPress()
			} else {
				lifecycleScope.launch {
					val invNo = Utils.paddingWith(msg, "0", 6, false)
					helperLog.appendLine(helperLogClassName, "Keypad OK :: void target invoice $invNo")
					searchByInvoice(invNo)
				}
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun searchByInvoice(invoiceNum: String) {
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			val criteriaList = listOf("invNo")
			val valueList = listOf(invoiceNum)
			BatchTableRepo.getSingleForCertainType(mContext, criteriaList, valueList, listOf("Sale", "Cash Out", "Instalment Sale", "Moto"))?.let {
				batchTableModel = it
				val bBatchInfo = HexUtil.hexStringToByte(it.batchData)
				val emvTag = EmvTag()
				val bTxnAmt = ByteArray(6)
				var bCardPanLen: Int
				val bCardPan = ByteArray(12)
				val bTxnRRN = ByteArray(12)
				val bApprCode = ByteArray(6)

				emvTag.getValueFrom(bBatchInfo, "DF04", bTxnAmt)
				emvTag.getValueFrom(bBatchInfo, "BF37", bTxnRRN)
				emvTag.getValueFrom(bBatchInfo, "BF38", bApprCode)
				bCardPanLen = emvTag.getValueFrom(bBatchInfo, "DF02", bCardPan)

				amount = HexUtil.bytesToHexString(bTxnAmt)
				cardPan = HexUtil.bytesToHexString(bCardPan, 0, bCardPanLen).replace("F", "") /*Remove padding "F"*/
				// D11 -- register before the first line that can carry the PAN. A void takes its PAN
				// from the batch table and never touches EMV, so nothing else populates LogRedact on
				// this path and the batchData TLV below would reach the uploaded log in the clear.
				LogRedact.registerCardData(cardPan, null)
				helperLog.appendLine(helperLogClassName, "Batch Table Record :: $it")
				transRRN = Utils.byteArrayToAsciiString(bTxnRRN)
				approvalCode = Utils.byteArrayToAsciiString(bApprCode)

				val bundle = Bundle()
				bundle.putString("1", Utils.getActualAmount(amount))
				bundle.putString("2", Utils.hideCardDetails(cardPan))
				bundle.putString("3", approvalCode)
				bundle.putString("4", it.invNo)
				val msg = Message()
				msg.data = bundle
				closeProgressDialog()

				if(forceVoid) {
					helperLog.appendLine(helperLogClassName, "Start ForceVoid Transaction via Intent")
					helperLog.appendLine(helperLogClassName, "Void target :: invoice ${it.invNo} / RRN $transRRN / PAN ${Utils.hideCardDetails(cardPan)}")
					//alertDialog?.dismiss()
					lifecycleScope.launch {
						executeVoidTxn()
					}
				} else {
					handlerVoid.sendMessage(msg)
				}
			} ?: run {
				helperLog.appendLine(helperLogClassName, "REJECT :: invoice $invoiceNum not found in batch table")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				runOnUiThread {
					ToastMake(mContext, "Invalid Input", Toast.LENGTH_SHORT)
				}

				if (ServiceHolder.appIntent) {
					val txnMap = HashMap<String, String>()
					txnMap["ResponseCode"] = EnumResponseCode.INVALID_TRANSACTION_INVOICE.code
					txnMap["ResponseDescription"] = EnumResponseCode.INVALID_TRANSACTION_INVOICE.description
					onBackToApp(txnMap)
				} else if (ServiceHolder.appHTTP) {
					val jObject = JSONObject()
					try {
						jObject.put("ResponseCode", EnumResponseCode.INVALID_TRANSACTION_INVOICE.code)
						jObject.put("ResponseDescription", EnumResponseCode.INVALID_TRANSACTION_INVOICE.description)
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

	@RequiresApi(Build.VERSION_CODES.O)
	private fun voidSaleDialog(amt: String?, cardNo: String?, authCode: String?, invNo: String?) {
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [VOID CONFIRMATION]")
		helperLog.appendLine(helperLogClassName, "Void target :: invoice $invNo / auth $authCode / PAN $cardNo / amount $amt")
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_void_confirmation, null
		)
		(dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = amt
		(dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = cardNo
		(dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = authCode
		(dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invNo

		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setDebouncedOnClickListener() {
			helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [VOID CONFIRMATION], void of invoice $invNo abandoned")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
			if (ServiceHolder.appIntent) {
				val txn_map: HashMap<String, String> = HashMap()
				txn_map["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
				txn_map["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description
				onBackToApp(txn_map)
			} else if (ServiceHolder.appHTTP) {
				val jObject = JSONObject()
				try {
					jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
					jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
				} catch (e: JSONException) {
					e.printStackTrace()
				}
				HTTPServer.getInstance().setResponseMessage(jObject.toString())
				customOnBackPress()
			}
		}

		val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
		voidConfirmBtn.setDebouncedOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: VOID CONFIRM [VOID CONFIRMATION]")
			helperLog.appendLine(helperLogClassName, "Start Void Transaction :: invoice $invNo")
			alertDialog?.dismiss()
			lifecycleScope.launch {
				executeVoidTxn()
			}
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
	private var handlerVoid: Handler = object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			val bundle = msg.data
			voidSaleDialog(
				bundle.getString("1"), bundle.getString("2"),
				bundle.getString("3"), bundle.getString("4")
			)
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private suspend fun executeVoidTxn() {
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			TransData.startTime = System.currentTimeMillis()
			val merchantConfig = ServiceHolder.getMerchantInfo()
			TransData.tpaMid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScMid")
			TransData.tpaTid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScTid")
			ServiceHolder.saleModelCache?.let {
				//TransData.salesType = it.SalesType
				TransData.acqCode = it.AcqCode ?: ""
				TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
				TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
				TransData.product = it.Product ?: ""
				TransData.productName = it.ProductName ?: ""
				//TransData.productCode = it.Product ?: ""
				TransData.eppTenure = it.EppTenure ?: ""
				TransData.eppTenureCode = it.EppTenureCode ?: ""
				TransData.ksn = it.Ksn ?: ""
				TransData.pinKsn = it.PinKsn ?: ""
				TransData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
			}
			val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
			TransData.transDateAsci = txnDt
			TransData.mid = batchTableModel.mid
			TransData.tid = batchTableModel.tid
			TransData.schemeId = batchTableModel.schemeId
			TransData.schemeTag = batchTableModel.schemeTag
			TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
			helperLog.appendLine(helperLogClassName, "STAN :: ${TransData.stan}")

			val invoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")
			TransData.invoiceNo = batchTableModel.invNo
			helperLog.appendLine(helperLogClassName, "Invoice No :: ${TransData.invoiceNo}")
			TransData.prevStan = batchTableModel.stan
			TransData.prevInvoice = batchTableModel.invNo
			TransData.prevApprovalCode = approvalCode
			TransData.prevRRN = transRRN

			TransData.batchNo = batchTableModel.batchNo
			TransData.amount = amount.toLong()
			val byteAmount = HexUtil.hexStringToByte(amount)
			byteAmount.copyInto(TransData.amountAuth, 0, 0, byteAmount.size)
			TransData.maskedPan = Utils.hideCardDetails(cardPan)
			TransData.hashedPan = cardPan.substring(0, 9)
			val bytePan = HexUtil.hexStringToByte(cardPan)
			bytePan.copyInto(TransData.pan, 0)
			TransData.panLen = cardPan.length
			val oldTransDb = HexUtil.hexStringToByte(batchTableModel.batchData)
			oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
			TransData.transactionDbLen = oldTransDb.size - 2
			TransData.entryModeLabel = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
			TransData.cvm = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_CVM, 16)
			TransData.aid = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_AID, 16)

			//TODO for transaction before revamp version
			if(TransData.aid == "" && TransData.cvm == "") {
				TransData.aid = TransData.getFromTransactionDb(TerminalConstants.iso.tag.AID, 16)
				TransData.cvm = "1F0303"
				TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_AID, TransData.aid)
				TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_CVM, TransData.cvm)
			}
			posReference?.let {
				TransData.posReference = it
				helperLog.appendLine(helperLogClassName, "Add Pos Reference :: $it")
			} ?: run {
				TransData.posReference = batchTableModel.posRefNo
				helperLog.appendLine(helperLogClassName, "Fallback for Pos Reference from BatchTable")
				helperLog.appendLine(helperLogClassName, "Add Pos Reference :: ${batchTableModel.posRefNo}")
			}

			val isNotCompl = booleanArrayOf(true)
			object : Thread() {
				@RequiresApi(Build.VERSION_CODES.O)
				override fun run() {
					super.run()
					if(batchTableModel.txnType.equals("Cash Out", true)){
						TransData.txnTypeLabel = "CashOut Void"
						IsoActivity.processCashOutVoid(mContext, isNotCompl, helperLog)
					} else {
						if(batchTableModel.txnType.equals("Instalment Sale", true)) {
							TransData.txnTypeLabel = "Void Instalment"
						}else {
							TransData.txnTypeLabel = "Void"
						}
						IsoActivity.processVoidSale(mContext, isNotCompl, helperLog)
					}
					helperLog.appendLine(helperLogClassName, "Trans Result :: ${TransData.transResult}")
					helperLog.appendLine(helperLogClassName, "Resp Code :: ${TransData.respCode}")
					helperLog.appendLine(helperLogClassName, "Void result :: ${if (TransData.transResult == TerminalConstants.iso.err.txnApproved) "APPROVED" else "DECLINED"} for invoice ${TransData.prevInvoice} / RRN ${TransData.prevRRN} / PAN ${TransData.maskedPan}")

					if(TransData.acqCode.equals("BSN_CARDZONE", true)) {
						if(TransData.transResult != TerminalConstants.iso.err.txnApproved && (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty())){
							var loop = 0
							val maxLoop = 3
							while (loop < maxLoop) {
								loop++
								pDTitle = "Reversal ($loop)"
								runOnUiThread(changeTitle)

								val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
								acquirerRevIsoModel?.let { revIsoModel ->
									val allIsoString = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
									val result = IsoActivity.processReversal(mContext, false, revIsoModel, allIsoString, true, helperLog)
									helperLog.appendLine(helperLogClassName, "reversal result :: $result")
									if(result != null){
										loop = maxLoop // used for exit
									}
								}
							}
						}
					}
					isNotCompl[0] = false
				}
			}.start()

			while (isNotCompl[0]) {
				val isoComm = ServiceHolder.isoComm
				if (isoComm != null) {
					pDMsg = isoComm.connectionStatus
					if (pDMsg != null && pDMsg!!.isNotEmpty()) {
						runOnUiThread(changeMessage)
					}
				}
				withContext(Dispatchers.IO) {
					Thread.sleep(500)
				}
			}
		}

		ServiceHolder.isoComm = null
		startProgressDialog(mContext, "Bank Authorization", "Waiting for Approval")
		startCoroutine.join()
		helperLog.appendLine(helperLogClassName, "Void Transaction End")

		CoroutineScope(Dispatchers.IO).launch {
			helperLog.appendLine(helperLogClassName, "Send Void Receipt to TMS")
			sendTmsVoidReceipt()
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		closeProgressDialog()
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		var intent = Intent(mContext, TransactionResultActivity::class.java)
		if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")  && tempModel == ServiceHolder.SR800_MODEL) {
			intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${intent.component?.shortClassName}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		startActivity(intent)
		finish()
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	suspend fun sendTmsVoidReceipt() = withContext(Dispatchers.IO){
		val timeStamp = Utils.DateTimeFormat(TransData.transDateAsci)
		val strTxnType = when (batchTableModel.txnType) {
			"Instalment Sale" -> "VoidEPP"
			"Moto" -> "VoidMoto"
			else -> "Void"
		}

		//TODO??
		//val strPaymentProductId = Utils.byteArrayToAsciiString(HexUtil.hexStringToByte(cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_PYMT_PRODUCT_ID)))
		val strPaymentProductId = ""
		val strSchemeId = TransData.schemeId
		val strStan = TransData.stan
		val strInvNo = TransData.invoiceNo
		val strTxnAmt = HexUtil.bytesToHexString(TransData.amountAuth)
		val strRrn = TransData.rrn
		val strApprCode = TransData.approvalCode
		val strRrnOri = TransData.prevRRN
		val strApprCodeOri = TransData.prevApprovalCode
		val strTid = TransData.tid
		val strMid = TransData.mid
		val strBatchNo = TransData.batchNo
		val strRespCode =  Utility.HexString2ASCII(TransData.respCode)
		val strAid = TransData.aid
		val mti = TransData.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16)
		val strNii = TransData.getFromTransactionDb("DF24", 16)
		val strMaskPanBcd = TransData.maskedPan
		val strHashedPanBcd = TransData.hashedPan
		val strEntryType = TransData.entryModeLabel
		val strARQC = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
		val strTVR = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
		val strPosReference = TransData.posReference
		val strCardLabel = Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen)
		val strCvm = TransData.cvm

		val jsonObject = JSONObject()
		try {
			jsonObject.put("SEQ_NO", ServiceHolder.getSqnNum())
			jsonObject.put("TXN_DT", timeStamp)
			jsonObject.put("TXN_TYPE", strTxnType)
			jsonObject.put("MID", strMid)
			jsonObject.put("TID", strTid)
			jsonObject.put("MTI", mti)
			jsonObject.put("NII", strNii)
			jsonObject.put("SCHEME_ID", strSchemeId)
			jsonObject.put("AID", strAid)
			jsonObject.put("CARD_MASKED", strMaskPanBcd)
			jsonObject.put("CARD_HASHED", strHashedPanBcd)
			jsonObject.put("RRN", strRrn)
			jsonObject.put("APPR_CODE", strApprCode)
			jsonObject.put("RRN_ORI", strRrnOri)
			jsonObject.put("APPR_CODE_ORI", strApprCodeOri)
			jsonObject.put("TXN_AMT", strTxnAmt)
			jsonObject.put("INV_NO", strInvNo)
			jsonObject.put("STAN", strStan)
			jsonObject.put("BATCH_NO", strBatchNo)
			jsonObject.put("RESP_CODE", strRespCode)
			jsonObject.put("APP_VER", ServiceHolder.getAppVersion())
			jsonObject.put("SN", ServiceHolder.getTerminalSerialNumber())
			jsonObject.put("ENTRY_TYPE", strEntryType)
			jsonObject.put("ARQC", strARQC)
			jsonObject.put("TVR", strTVR)
			jsonObject.put("PAYMENT_PRODUCT_ID", strPaymentProductId)
			jsonObject.put("POS_REF_NO", strPosReference)
			jsonObject.put("CARD_LABEL", strCardLabel)
			jsonObject.put("CVM", strCvm)
			//jsonObject.put("EPP_DETAIL", hmEppDetails)
		} catch (e: JSONException) {
			e.printStackTrace()
		}
		val body = jsonObject.toString()
		UploadTMS.getInstance().addReceipt(body)
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: leaving void sale, navigate -> home screen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		ServiceHolder.appIntent = false
		ServiceHolder.appHTTP = false
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(newIntent)
		finish()
	}

	private fun onBackToApp(txn_map: HashMap<String, String>) {
		txn_map["TransactionType"] = ServiceHolder.txnType.toString()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "Returning to calling app :: ${txn_map["ResponseCode"]} - ${txn_map["ResponseDescription"]}")
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
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
			helperLog.appendLine(helperLogClassName, "VoidSale OnDestroy :: void sale screen ended")
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
}