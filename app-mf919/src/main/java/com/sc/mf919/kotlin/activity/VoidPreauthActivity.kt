package com.sc.mf919.kotlin.activity


import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.library.terminal.Utility
import com.sc.mf919.R
import com.sc.mf919.java.activity.EmvTag
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Keypad
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.UploadTMS
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.onKeypadEventListener
import com.sc.mf919.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelPreAuthTable
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appRunningProcess
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.txnType
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject


class VoidPreauthActivity : BaseActivity() {
	val classTag = "VoidPreAuth"
	lateinit var mContext: Context
	lateinit var preAuthTableModel: DbModelPreAuthTable
	lateinit var tempModel: String

	lateinit var helperlogClassName: String
	lateinit var helperLog: HelperLog

	var posReference: String? = null
	lateinit var amount: String
	lateinit var cardPan: String
	lateinit var prevStan: String

	@RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_voidpreauthrefund)
		mContext = this@VoidPreauthActivity
		tempModel = ServiceHolder.getDeviceModel().uppercase()
		helperlogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperlogClassName,
			helperlogClassName,
			"Void Pre Authorization Activity"
		)

		helperLog.appendLine(helperlogClassName, "Void PreAuth Init")
		typeOfSales = intent.getIntExtra("typeofSale", 3)
		posReference = intent.getStringExtra("posReference")
		TransData.reset(applicationContext)

		val keypad = findViewById<Keypad>(R.id.keypad_voidpreauth)
		val tv = findViewById<TextView>(R.id.textView_voidPreauthInvNo)
		keypad.setFilter(tv, false, 6)
		keypad.registerOnOKEventListener(mListener)
		val invoice = intent.getStringExtra("Invoice")
		if (invoice != null) {
			tv.text = invoice
			helperLog.appendLine(helperlogClassName, "Void target invoice :: $invoice")
			lifecycleScope.launch {
				searchByInvoice(invoice)
			}
		}

		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		val dbModelTerminalConfig = getTerminalConfig()
		val voidWithPIN = getSafeValue(dbModelTerminalConfig, "VOID_WITH_PIN")
		if (voidWithPIN.toInt() == 1) {
			helperLog.appendLine(helperlogClassName, "Dialog opened :: [VOID PIN]")
			PINDialog("", true)
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
					helperLog.appendLine(helperlogClassName, "Keypad OK :: void target invoice $invNo")
					searchByInvoice(invNo)
				}
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun searchByInvoice(invoiceNum: String) {
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			val criteriaList = listOf("invNo")
			val valueList = arrayOf(invoiceNum)
			PreAuthTableRepo.getSingle(mContext, criteriaList, valueList)?.let {
				helperLog.appendLine(helperlogClassName, "PreAuth Table Record :: $it")
				preAuthTableModel = it
				val bBatchInfo = HexUtil.hexStringToByte(it.addInfo)
				val emvTag = EmvTag()
				val bTxnAmt = ByteArray(6)
				var bCardPanLen: Int
				val bCardPan = ByteArray(12)
				val bApprCode = ByteArray(6)
				val bStan = ByteArray(3)

				emvTag.getValueFrom(bBatchInfo, "BF38", bApprCode)
				emvTag.getValueFrom(bBatchInfo, "DF04", bTxnAmt)
				emvTag.getValueFrom(bBatchInfo, "DF11", bStan)
				bCardPanLen = emvTag.getValueFrom(bBatchInfo, "DF02", bCardPan)

				amount = HexUtil.bytesToHexString(bTxnAmt)
				cardPan = HexUtil.bytesToHexString(bCardPan, 0, bCardPanLen).replace("F", "")  /*Remove padding "F"*/
				prevStan = HexUtil.bytesToHexString(bStan)

				val bundle = Bundle()
				bundle.putString("1", Utils.getActualAmount(amount))
				bundle.putString("2", Utils.hideCardDetails(cardPan))
				bundle.putString("3", it.apprCode)
				bundle.putString("4", it.invNo)
				val msg = Message()
				msg.data = bundle
				closeProgressDialog()

				if (it.cardData.equals("MCCS", ignoreCase = true)) {
					helperLog.appendLine(helperlogClassName, "REJECT :: MyDebit Preauth Void is not supported")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					runOnUiThread {
						ToastMake(mContext, "MyDebit Preauth Void is not supported", Toast.LENGTH_SHORT)
					}

					if (ServiceHolder.appIntent) {
						val txnMap = java.util.HashMap<String, String>()
						txnMap["ResponseCode"] = "SHC010"
						txnMap["ResponseDescription"] = "MyDebit Preauth Void is not supported"
						onBackToApp(txnMap)
					} else if (ServiceHolder.appHTTP) {
						val jObject = JSONObject()
						try {
							jObject.put("ResponseCode", "SHC010")
							jObject.put("ResponseDescription", "MyDebit Preauth Void is Not Supported")
						} catch (e: JSONException) {
							e.printStackTrace()
						}
						HTTPServer.getInstance().setResponseMessage(jObject.toString())
						customOnBackPress()
					} else {
						Unit
					}
				} else {
					handlerVoid.sendMessage(msg)
				}
			} ?: run {
				helperLog.appendLine(helperlogClassName, "REJECT :: invoice $invoiceNum not found in preauth table")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				runOnUiThread {
					ToastMake(mContext, "Invalid Input", Toast.LENGTH_SHORT)
				}

				if (ServiceHolder.appIntent) {
					val txnMap = java.util.HashMap<String, String>()
					txnMap["ResponseCode"] = "SHC001"
					txnMap["ResponseDescription"] = "Invalid Transaction Invoice"
					onBackToApp(txnMap)
				} else if (ServiceHolder.appHTTP) {
					val jObject = JSONObject()
					try {
						jObject.put("ResponseCode", "SHC001")
						jObject.put("ResponseDescription", "Invalid Transaction Invoice")
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
	private fun voidPreauthDialog(amt: String?, cardNo: String?, authCode: String?, invNo: String?) {
		helperLog.appendLine(helperlogClassName, "Dialog opened :: [VOID PREAUTH CONFIRMATION]")
		helperLog.appendLine(helperlogClassName, "Void target :: invoice $invNo / auth $authCode / PAN $cardNo / amount $amt")
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams")
		val dialogView = inflater.inflate(R.layout.activity_voidpreauth_confirmation, null)

		(dialogView.findViewById<View>(R.id.amount_tr_preauth) as TextView).text = amt
		(dialogView.findViewById<View>(R.id.cardNo_tr_preauth) as TextView).text = cardNo
		(dialogView.findViewById<View>(R.id.authCode_tr_preauth) as TextView).text = authCode
		(dialogView.findViewById<View>(R.id.traceNo_tr_preauth) as TextView).text = invNo
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn_preauthcancel)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperlogClassName, "User Cancel :: dismissed [VOID PREAUTH CONFIRMATION], void of invoice $invNo abandoned")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
			if (appIntent) {
				val txn_map = HashMap<String, String>()
				txn_map["ResponseCode"] = "SHC005"
				txn_map["ResponseDescription"] = "User Cancel the Transaction"
				onBackToApp(txn_map)
			} else if (appHTTP) {
				val jsonObject = JSONObject()
				try {
					jsonObject.put("ResponseCode", "SHC005")
					jsonObject.put("ResponseDescription", "User Cancel the Transaction")
				} catch (e: JSONException) {
					e.printStackTrace()
				}
				HTTPServer.getInstance().setResponseMessage(jsonObject.toString())
				customOnBackPress()
			}
		}
		val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidPreauthConfirmBtn)
		voidConfirmBtn.setOnClickListener {
			helperLog.appendLine(helperlogClassName, "Selected :: VOID CONFIRM [VOID PREAUTH CONFIRMATION]")
			helperLog.appendLine(helperlogClassName, "Start Void PreAuth Transaction :: invoice $invNo")
			appRunningProcess = true
			alertDialog?.dismiss()
			lifecycleScope.launch {
				executeVoidTxn()
			}
		}
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	@RequiresApi(Build.VERSION_CODES.O)
	var handlerVoid: Handler = object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			val bundle = msg.data
			voidPreauthDialog(
				bundle.getString("1"), bundle.getString("2"),
				bundle.getString("3"), bundle.getString("4")
			)
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private suspend fun executeVoidTxn() {
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			TransData.startTime = System.currentTimeMillis()
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
			TransData.txnTypeLabel = "PreAuth Cancel"
			TransData.schemeTag = preAuthTableModel.schemeTag
			TransData.prevStan = prevStan
			TransData.prevInvoice = preAuthTableModel.invNo
			TransData.prevRRN = preAuthTableModel.rrn
			TransData.prevApprovalCode = preAuthTableModel.apprCode
			TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
			helperLog.appendLine(helperlogClassName, "STAN >> ${TransData.stan}")

			val invoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")
			helperLog.appendLine(helperlogClassName, "Invoice No >> ${TransData.invoiceNo}")

			TransData.invoiceNo = preAuthTableModel.invNo
			TransData.amount = amount.toLong()
			val byteAmount = HexUtil.hexStringToByte(amount)
			byteAmount.copyInto(TransData.amountAuth, 0, 0, byteAmount.size)
			TransData.maskedPan = Utils.hideCardDetails(cardPan)
			TransData.hashedPan = cardPan.substring(0, 9)
			val bytePan = HexUtil.hexStringToByte(cardPan)
			bytePan.copyInto(TransData.pan, 0)
			val oldTransDb = HexUtil.hexStringToByte(preAuthTableModel.addInfo)
			oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
			TransData.transactionDbLen = oldTransDb.size - 2
			TransData.schemeId = TransData.getFromTransactionDb("DA", 16)
			TransData.batchNo = TransData.getFromTransactionDb("DF60", 256)
			TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
			TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)
			TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)
			posReference?.let {
				TransData.posReference = it
				helperLog.appendLine(helperlogClassName, "Add Pos Reference >> $it")
			}

			val isNotCompl = booleanArrayOf(true)
			object : Thread() {
				@RequiresApi(Build.VERSION_CODES.O)
				override fun run() {
					super.run()
					IsoActivity.processVoidPreauth(mContext, isNotCompl, helperLog)
					helperLog.appendLine(helperlogClassName, "Trans Result :: ${TransData.transResult}")
					helperLog.appendLine(helperlogClassName, "Resp Code :: ${TransData.respCode}")
					helperLog.appendLine(helperlogClassName, "Void PreAuth result :: ${if (TransData.transResult == Global.iso.err.txnApproved) "APPROVED" else "DECLINED"} for invoice ${TransData.prevInvoice} / RRN ${TransData.prevRRN} / PAN ${TransData.maskedPan}")
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
		helperLog.appendLine(helperlogClassName, "Void PreAuth Transaction End")

		CoroutineScope(Dispatchers.IO).launch {
			helperLog.appendLine(helperlogClassName, "Send PreAuth Void Receipt to TMS")
			sendTmsVoidPreAuthReceipt()
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		closeProgressDialog()
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		val dbModelTerminalConfig = getTerminalConfig()
		var intent = Intent(mContext, TransactionResultActivity::class.java)
		if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
			intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> ${intent.component?.shortClassName}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		startActivity(intent)
		finish()
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	suspend fun sendTmsVoidPreAuthReceipt() = withContext(Dispatchers.IO){
		val timeStamp = Utils.DateTimeFormat(TransData.transDateAsci)
		val strTxnType = "VoidPreAuth"
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
		val strPreAuthBatchNo = TransData.getFromTransactionDb("BF60", 256)
		val strRespCode =  Utility.HexString2ASCII(TransData.respCode)
		val strAid = TransData.aid
		val mti = TransData.getFromTransactionDb(Global.iso.tag.MTI, 16)
		val strNii = TransData.getFromTransactionDb("DF24", 16)
		val strMaskPanBcd = TransData.maskedPan
		val strHashedPanBcd = TransData.hashedPan
		val strEntryType = TransData.entryModeLabel
		val strARQC = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
		val strTVR = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
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
			jsonObject.put("BATCHNO_PREAUTH", strPreAuthBatchNo)
			//jsonObject.put("EPP_DETAIL", hmEppDetails)
		} catch (e: JSONException) {
			e.printStackTrace()
		}
		val body = jsonObject.toString()
		UploadTMS.getInstance().addReceipt(body)
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "User Cancel :: leaving void preauth, navigate -> home screen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		appIntent = false
		appHTTP = false
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	private fun onBackToApp(txn_map: HashMap<String, String>) {
		txn_map["TransactionType"] = txnType.toString()
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
			helperLog.appendLine(helperlogClassName, "VoidPreauth OnDestroy :: void preauth screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}
