package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.EmvTag
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Utils
import utils.CardUtil
import com.sc.mf919.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelPreAuthTable
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeypadActivitySaleCom : BaseActivity() {
	private val TAG = "Keypad_SaleCom"
	lateinit var mContext: Context
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	lateinit var tempModel: String

	var preAuthInfo: DbModelPreAuthTable? = null
	var amountString: String = "0"
	var txnAmount: Long = 0
	var approvalCode: String? = null
	var rrn: String? = null
	var invoiceNo: String? = null
	var preAuthBatchNo: String? = null
	var cardPan: String = ""

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_keypad_salecompletion)
		val toolbar = findViewById<Toolbar>(R.id.toolbarSaleCom)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		Utils.printLog("KeypadActivitySaleCom")
		TransData.reset(applicationContext)
		mContext = this@KeypadActivitySaleCom
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"PreAuth Sale Completion"
		)
		helperLog.appendLine(helperLogClassName, "Sale Completion keypad opened")
		tempModel = ServiceHolder.getDeviceModel().uppercase()
		//typeOfSale = intent.getIntExtra("typeofSale", 0)
		//Utils.printLog("Type of Sale: $typeOfSale")
		amountString = intent.getStringExtra("txnAmt") ?: "0"
		txnAmount = amountString.replace(".", "").toLong()

		if (ServiceHolder.appIntent || ServiceHolder.appHTTP) {
			helperLog.appendLine(helperLogClassName, "External request :: appIntent=${ServiceHolder.appIntent} appHTTP=${ServiceHolder.appHTTP}")

			approvalCode = intent.getStringExtra("apprCode")
			rrn = intent.getStringExtra("rrn")
			invoiceNo = intent.getStringExtra("invNo")

			/*if (approvalCode != null && rrn != null && invoiceNo != null) {
				Utils.printLog("asdf 1234")
			}*/
			lifecycleScope.launch {
				searchRecord()
			}
		}

		findViewById<LinearLayout>(R.id.submit_button).setOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: Submit [SALE COMPLETION]")
			btnOk()
		}

		findViewById<LinearLayout>(R.id.cancel_button).setOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: Cancel [SALE COMPLETION]")
			customOnBackPress()
		}

		findViewById<EditText>(R.id.tvInvNo).setOnEditorActionListener(
			OnEditorActionListener { _, actionId, _ ->
				if (actionId == EditorInfo.IME_ACTION_DONE) {
					//under here check all ok do sale if not return
					helperLog.appendLine(helperLogClassName, "Selected :: Done on Invoice No [SALE COMPLETION]")
					btnOk()
					return@OnEditorActionListener true
				}
				false
			}
		)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun btnOk() {
		lifecycleScope.launch {
			approvalCode = findViewById<EditText>(R.id.tvApprCode).text.toString()
			rrn = findViewById<EditText>(R.id.tvRrn).text.toString()
			invoiceNo = findViewById<EditText>(R.id.tvInvNo).text.toString()
			searchRecord()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun searchRecord() {
		helperLog.appendLine(helperLogClassName, "Search pre-auth record :: APPR[$approvalCode] RRN[$rrn] INV[$invoiceNo]")

		if (approvalCode.isNullOrEmpty() || rrn.isNullOrEmpty() || invoiceNo.isNullOrEmpty()) {
			ToastMake(mContext, "Invalid Input", Toast.LENGTH_SHORT)
			helperLog.appendLine(helperLogClassName, "REJECT :: approval code, RRN and invoice no are all required")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			preAuthInfo = PreAuthTableRepo.getPreauthInfo(mContext, approvalCode!!, rrn!!, invoiceNo!!) ?: run {
				ToastMake(mContext, "Not Found", Toast.LENGTH_SHORT)
				helperLog.appendLine(helperLogClassName, "REJECT :: pre-auth record not found")
				//TODO
				/*if (ServiceHolder.appIntent) {
					val txnMap = java.util.HashMap<String, String>()
					txnMap["ResponseCode"] = "SHC001"
					txnMap["ResponseDescription"] = "Invalid Transaction Details"
					//onBackToApp(txnMap)
				} else if (ServiceHolder.appHTTP) {
					val jObject = JSONObject()
					try {
						jObject.put("ResponseCode", "SHC001")
						jObject.put("ResponseDescription", "Invalid Transaction Details")
					} catch (e: JSONException) {
						e.printStackTrace()
					}
					HTTPServer.getInstance().setResponseMessage(jObject.toString())
					customOnBackPress()
				}*/
				null
			}
			Utils.printLog("preAuthInfo >> ${Gson().toJson(preAuthInfo)}")
			Thread.sleep(500)
		}
		helperLog.appendLine(helperLogClassName, "Start Searching")
		startProgressDialog(mContext, "", "Loading...")
		startCoroutine.join()
		closeProgressDialog()
		if (preAuthInfo == null) {
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		preAuthInfo?.let {
			showConfirmationDialog(it)
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun showConfirmationDialog(dbModelPreAuthTable: DbModelPreAuthTable) {
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater

		var preAuthAmount = ""
		var maskedPan = ""
		try{
			val batchData = dbModelPreAuthTable.addInfo
			val bBatchInfo = HexUtil.hexStringToByte(batchData)
			val etag = EmvTag()

			val bDe55 = ByteArray(258)
			val bDe55_1 = ByteArray(256)
			val bTxnAmt = ByteArray(6)
			val tmpCardPan = ByteArray(12)
			val bBatchNo = ByteArray(6)

			etag.getValueFrom(bBatchInfo, "DF55", bDe55_1, 2)
			Utils.debugLogPrint(TAG, "DE55_1 -> ${HexUtil.bytesToHexString(bDe55_1)}")

			bDe55[0] = 0x01.toByte()
			bDe55[1] = 0x00.toByte()
			System.arraycopy(bDe55_1, 0, bDe55, 2, bDe55_1.size)
			Utils.debugLogPrint(TAG, "bDe55 -> ${HexUtil.bytesToHexString(bDe55)}")

			etag.getValueFrom(bBatchInfo, "DF04", bTxnAmt, 2)
			preAuthAmount = HexUtil.bytesToHexString(bTxnAmt)
			helperLog.appendLine(helperLogClassName, "Pre-Auth Amount :: $preAuthAmount")

			etag.getValueFrom(bBatchInfo, "DF60", bBatchNo, 2)
			preAuthBatchNo = Utils.byteArrayToAsciiString(bBatchNo)
			helperLog.appendLine(helperLogClassName, "Pre-Auth BatchNo :: $preAuthBatchNo")

			//Card pan
			val iCardPanLen = etag.getValueFrom(bBatchInfo, "DF02", tmpCardPan)
			val bCardPan = ByteArray(iCardPanLen)
			System.arraycopy(tmpCardPan, 0, bCardPan, 0, iCardPanLen)
			Utils.debugLogPrint(TAG, "DF02 -> ${HexUtil.bytesToHexString(bCardPan)}")
			var tempPan = HexUtil.bytesToHexString(bCardPan)
			cardPan = tempPan
			tempPan = tempPan.replace("F", "")
			Utils.debugLogPrint(TAG, "Temp Pan -> $tempPan")
			maskedPan = Utils.hideCardDetails(tempPan)
		} catch (ex: Exception){
			ex.printStackTrace()
			helperLog.appendLine(helperLogClassName, "Exception decoding pre-auth batch data :: ${ex.message ?: "-"}")
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}

		helperLog.appendLine(helperLogClassName, "Dialog opened :: [CONFIRM SALE COMPLETION]")

		@SuppressLint("InflateParams")
		val dialogView = inflater.inflate(R.layout.activity_salecomplete_confirmation, null)
		(dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = Utils.getActualAmount(preAuthAmount)
		(dialogView.findViewById<View>(R.id.amount_tr_salecomplete) as TextView).text = Utils.getActualAmount(txnAmount.toString())
		(dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = maskedPan
		(dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = approvalCode
		(dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invoiceNo
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [CONFIRM SALE COMPLETION]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
			//TODO
			/*if (ServiceHolder.appIntent) {
				val txnMap = HashMap<String, String>()
				txnMap["ResponseCode"] = "SHC005"
				txnMap["ResponseDescription"] = "User Cancel the Transaction"
				onBackToApp(txnMap)
			} else if (ServiceHolder.appHTTP) {
				val jsonObject = JSONObject()
				try {
					jsonObject.put("ResponseCode", "SHC005")
					jsonObject.put("ResponseDescription", "User Cancel the Transaction")
				} catch (e: JSONException) {
					e.printStackTrace()
				}
				HTTPServer.getInstance().setResponseMessage(jsonObject.toString())
				customOnBackPress()
			}*/
		}
		val voidConfirmBtn = dialogView.findViewById<Button>(R.id.confirmBtn)
		voidConfirmBtn.setOnClickListener {
			alertDialog?.dismiss()
			val cardPresented = findViewById<CheckBox>(R.id.cbCardPresented).isChecked
			helperLog.appendLine(helperLogClassName, "Selected :: Confirm, cardPresented=$cardPresented [CONFIRM SALE COMPLETION]")
			if (cardPresented) {
				//Card presented sale completion -> re-scan card and run EMV again
				goToCardPresentedSaleComp()
			} else {
				//Card non-presented sale completion (existing behaviour)
				lifecycleScope.launch {
					saleComplete()
				}
			}
		}
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private suspend fun saleComplete() {
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			TransData.startTime = System.currentTimeMillis()
			ServiceHolder.saleModelCache?.let {
				TransData.salesType = it.SalesType
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
			TransData.txnTypeLabel = "Sale Completion"
			TransData.schemeTag = preAuthInfo?.schemeTag ?: ""
			TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
			//helperLog.appendLine(helperlogClassName, "STAN >> ${TransData.stan}")

			val dbInvoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")
			//helperLog.appendLine(helperlogClassName, "Invoice No >> ${TransData.invoiceNo}")
			TransData.invoiceNo = invoiceNo!!
			TransData.amount = txnAmount
			HexUtil.hexStringToByte(Utils.zeroPadding(txnAmount.toString(), 12)).copyInto(TransData.amountAuth)
			TransData.maskedPan = Utils.hideCardDetails(cardPan)
			TransData.hashedPan = cardPan.substring(0, 9)
			val bytePan = HexUtil.hexStringToByte(cardPan)
			bytePan.copyInto(TransData.pan, 0)
			val oldTransDb = HexUtil.hexStringToByte(preAuthInfo?.addInfo)
			oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
			TransData.transactionDbLen = oldTransDb.size - 2
			TransData.schemeId = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_SCHEME_ID, 16)
			TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)
			TransData.schemeType = CardUtil.getCardTypFromAid(TransData.aid)
			TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(applicationContext, "batchNo", TransData.schemeTag)?.value ?: "000001"
			TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
			TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)

			//TODO MISSING
			/*posReference?.let {
				TransData.posReference = it
				helperLog.appendLine(helperlogClassName, "Add Pos Reference >> $it")
			}*/
			//TODO MISSING

			Thread.sleep(2000)
			val isNotCompl = booleanArrayOf(true)
			object : Thread() {
				@RequiresApi(Build.VERSION_CODES.O)
				override fun run() {
					super.run()
					IsoActivity.processSaleComp(mContext, helperLog)
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

			//Reversal //Skip Reversal for MyDebit SaleCom
			if(!TransData.schemeType.equals("MCCS", true) &&
				TransData.transResult != Global.iso.err.txnApproved &&
				(TransData.transResult == Global.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
				ServiceHolder.isoComm = null
				isNotCompl[0] = true
				object : Thread() {
					override fun run() {
						super.run()
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
						isNotCompl[0] = false
					}
				}.start()

				while (isNotCompl[0]) {
					val isoComm = ServiceHolder.isoComm
					if (isoComm != null) {
						pDMsg = isoComm.connectionStatus
						if (pDMsg != null) {
							if (pDMsg!!.isNotEmpty()) {
								runOnUiThread(changeMessage)
							}
						}
					}
					withContext(Dispatchers.IO) {
						Thread.sleep(500)
					}
				}
			}
		}

		ServiceHolder.isoComm = null
		startProgressDialog(mContext, "Bank Authorization", "Waiting for Approval")
		startCoroutine.join()
		helperLog.appendLine(helperLogClassName, "Sale Completion Transaction End")
		closeProgressDialog()

		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		var intent = Intent(mContext, TransactionResultActivity::class.java)
		if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
			intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
		}
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${intent.component?.shortClassName}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun goToCardPresentedSaleComp() {
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(mContext, CardPaymentActivity::class.java)
		intent.putExtra("txnAmt", amountString)
		intent.putExtra("apprCode", approvalCode)
		intent.putExtra("rrn", rrn)
		intent.putExtra("invNo", invoiceNo)
		intent.putExtra("cardPan", cardPan)
		intent.putExtra("preAuthBatchNo", preAuthBatchNo)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, abandoning sale completion")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		ServiceHolder.appIntent = false
		ServiceHolder.appHTTP = false
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "KeypadActivitySaleCom OnDestroy :: sale completion screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

//	@RequiresApi(Build.VERSION_CODES.O)
//	private suspend fun searchRecord(){
//		var preauthInfo: DbModelPreAuthTable? = null
//		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
//			preauthInfo = PreAuthTableRepo.getPreauthInfo(
//					mContext,
//					HexUtil.str2HexStr(textViewApprCode!!.text.toString()),
//					HexUtil.str2HexStr(textViewRrn!!.text.toString()),
//					HexUtil.str2HexStr(textViewInvNo!!.text.toString())
//			) ?: run {
//				ToastMake(mContext, "Invalid Input", Toast.LENGTH_SHORT)
//				if (ServiceHolder.appIntent) {
//					val txnMap = java.util.HashMap<String, String>()
//					txnMap["ResponseCode"] = "SHC001"
//					txnMap["ResponseDescription"] = "Invalid Transaction Details"
//					onBackToApp(txnMap)
//				} else if (ServiceHolder.appHTTP) {
//					val jObject = JSONObject()
//					try {
//						jObject.put("ResponseCode", "SHC001")
//						jObject.put("ResponseDescription", "Invalid Transaction Details")
//					} catch (e: JSONException) {
//						e.printStackTrace()
//					}
//					HTTPServer.getInstance().setResponseMessage(jObject.toString())
//					customOnBackPress()
//				}
//				null
//			}
//			Thread.sleep(500)
//		}
//		startProgressDialog(mContext, "", "Loading...")
//		startCoroutine.join()
//		closeProgressDialog()
//		preauthInfo?.let {
//			if (isProgressDisplayActive) {
//				pD?.dismiss()
//			}
//			showConfirmationDialog(it)
//		}
//	}
//
//	@RequiresApi(Build.VERSION_CODES.O)
//	private fun showConfirmationDialog(dbModelPreAuthTable: DbModelPreAuthTable) {
//		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
//		val inflater = this.layoutInflater
//
//		var authCode = ""
//		var invoiceNo = ""
//
//		try{
//			batchData = dbModelPreAuthTable.addInfo
//			//schemeTag = dbModelPreAuthTable.schemeTag
//
//			val bBatchInfo = HexUtil.hexStringToByte(batchData)
//			val etag = EmvTag()
//			val bApprCode = ByteArray(6)
//			val bInvNo = ByteArray(6)
//			val bDe55 = ByteArray(258)
//			val bDe55_1 = ByteArray(256)
//			val bTxnAmt = ByteArray(6)
//			val tmpCardPan = ByteArray(12)
//
//			etag.getValueFrom(bBatchInfo, "BF38", bApprCode)
//			authCode = Utils.byteArrayToAsciiString(bApprCode)
//			Utils.debugLogPrint(TAG, "Approval Code -> $authCode")
//
//			etag.getValueFrom(bBatchInfo, "DF62", bInvNo)
//			invoiceNo = Utils.byteArrayToAsciiString(bInvNo)
//			Utils.debugLogPrint(TAG, "Invoice No -> $invoiceNo")
//
//			etag.getValueFrom(bBatchInfo, "DF55", bDe55_1, 2)
//			Utils.debugLogPrint(TAG, "DE55_1 -> ${HexUtil.bytesToHexString(bDe55_1)}")
//
//			bDe55[0] = 0x01.toByte()
//			bDe55[1] = 0x00.toByte()
//			System.arraycopy(bDe55_1, 0, bDe55, 2, bDe55_1.size)
//			Utils.debugLogPrint(TAG, "bDe55 -> ${HexUtil.bytesToHexString(bDe55)}")
//
//			val foundAmountTag = etag.getValueFrom(bDe55, "9F02", bTxnAmt)
//			if(foundAmountTag == -1){
//				etag.getValueFrom(bBatchInfo, "DF04", bTxnAmt, 2)
//			}
//			preAuthAmount = HexUtil.bytesToHexString(bTxnAmt)
//			Utils.debugLogPrint(TAG, "Amount -> $preAuthAmount")
//
//			//Card pan
//			val iCardPanLen = etag.getValueFrom(bBatchInfo, "DF02", tmpCardPan)
//			val bCardPan = ByteArray(iCardPanLen)
//			System.arraycopy(tmpCardPan, 0, bCardPan, 0, iCardPanLen)
//			Utils.debugLogPrint(TAG, "DF02 -> ${HexUtil.bytesToHexString(bCardPan)}")
//			var tempPan = HexUtil.bytesToHexString(bCardPan)
//			tempPan = tempPan.replace("F", "")
//			cardPan = tempPan
//			Utils.debugLogPrint(TAG, "Temp Pan -> $tempPan")
//			maskedPan = Utils.hideCardDetails(tempPan)
//		} catch (ex: Exception){
//			ex.printStackTrace()
//			Utils.debugLogPrint(TAG, ex.message)
//		}
//
//		@SuppressLint("InflateParams")
//		val dialogView = inflater.inflate(R.layout.activity_salecomplete_confirmation, null)
//		(dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = Utils.getActualAmount(preAuthAmount)
//		(dialogView.findViewById<View>(R.id.amount_tr_salecomplete) as TextView).text = Utils.getActualAmount(txnAmount)
//		(dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = maskedPan
//		(dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = authCode
//		(dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invoiceNo
//		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
//		cancelBtn.setOnClickListener {
//			alertDialog?.dismiss()
//			if (ServiceHolder.appIntent) {
//				val txnMap = HashMap<String, String>()
//				txnMap["ResponseCode"] = "SHC005"
//				txnMap["ResponseDescription"] = "User Cancel the Transaction"
//				onBackToApp(txnMap)
//			} else if (ServiceHolder.appHTTP) {
//				val jsonObject = JSONObject()
//				try {
//					jsonObject.put("ResponseCode", "SHC005")
//					jsonObject.put("ResponseDescription", "User Cancel the Transaction")
//				} catch (e: JSONException) {
//					e.printStackTrace()
//				}
//				HTTPServer.getInstance().setResponseMessage(jsonObject.toString())
//				customOnBackPress()
//			}
//		}
//		val voidConfirmBtn = dialogView.findViewById<Button>(R.id.confirmBtn)
//		voidConfirmBtn.setOnClickListener {
//			alertDialog?.dismiss()
//			lifecycleScope.launch {
//				saleComplete()
//			}
//		}
//		alertDialogBuilder.setView(dialogView)
//		alertDialogBuilder.setCancelable(false)
//		alertDialog = alertDialogBuilder.create()
//		alertDialog?.show()
//	}
//
//	@RequiresApi(api = Build.VERSION_CODES.O)
//	private suspend fun saleComplete() {
//		val isos = IsoSteps()
//		val isoengine = IsoEngine(isos)
//		cube = CubeActivity(isoengine)
//		ServiceHolder.mCube = cube
//
//		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
//			try {
//				//val strOptData = "in:SchemeTag=${schemeTag}"
//				val strOptData = "${textViewApprCode!!.text}${textViewRrn!!.text}${textViewInvNo!!.text}"
//				//val strOptData = ApprCode + Rrn + InvNo
//				cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(maskedPan))
//				cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(cardPan.substring(0,9)))
//
//				val txnDt = "20" + EmvUtil.getCurrentTime("yyMMddHHmmss")
//				Utils.debugLogPrint(TAG, "txnDt -> $txnDt")
//				val strTxnDt = Utils.DateTimeFormat(txnDt)
//				cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_TXN_DATETIME7, Utils.ASCIItoHexString(strTxnDt))
//				cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_CVM, "1F0303")
//
//				val etag = EmvTag()
//				val bAppLabel = ByteArray(50)
//				val bArqc = ByteArray(50)
//				val bAid = ByteArray(50)
//				val bTvr = ByteArray(20)
//				val bTsi = ByteArray(20)
//
//				val bBatchInfo = HexUtil.hexStringToByte(batchData)
//				etag.removeTlvByT(bBatchInfo, "DF04")
//				etag.removeTlvByT(bBatchInfo, "BF04")
//				etag.addTlvByTvHexString("DF04", txnAmount, 0, 12, bBatchInfo)
//				batchData = HexUtil.bytesToHexString(bBatchInfo)
//				val oriAmount = "9F0206$preAuthAmount"
//				val newAmount = "9F0206$txnAmount"
//				batchData = batchData!!.replace(oriAmount, newAmount)
//
//				val bDe55_1 = ByteArray(300)
//				etag.getValueFrom(HexUtil.hexStringToByte(batchData), "DF55", bDe55_1, 2)
//				Utils.debugLogPrint(TAG, HexUtil.bytesToHexString(bDe55_1))
//				val bDe55 = ByteArray(302)
//				bDe55[0] = 0x01.toByte()
//				bDe55[1] = 0x00.toByte()
//				System.arraycopy(bDe55_1, 0, bDe55, 2, bDe55_1.size)
//				Utils.debugLogPrint(TAG, HexUtil.bytesToHexString(bDe55))
//
//				val iAppLabelLen = etag.getValueFrom(bDe55, "50", bAppLabel)
//				if (iAppLabelLen != -1) {
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_APPLABEL, HexUtil.bytesToHexString(bAppLabel, 0, iAppLabelLen))
//				}
//
//				val iArqcLen = etag.getValueFrom(bDe55, "9F26", bArqc)
//				if(iArqcLen != -1){
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_ARQC, HexUtil.bytesToHexString(bArqc, 0, iArqcLen))
//				}
//
//				val iAidLen = etag.getValueFrom(bDe55, "84", bAid)
//				if(iAidLen != -1){
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_AID, HexUtil.bytesToHexString(bAid, 0, iAidLen))
//				}
//
//				val iTvrLen = etag.getValueFrom(bDe55, "95", bTvr)
//				if(iTvrLen != -1){
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_TVR, HexUtil.bytesToHexString(bTvr, 0, iTvrLen))
//				}
//
//				val iTsiLen = etag.getValueFrom(bDe55, "9B", bTsi)
//				if (iTsiLen != -1) {
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_TSI, HexUtil.bytesToHexString(bTsi, 0, iTsiLen))
//				}
//
//				val bPosEntryMode = ByteArray(2)
//				val iPosEntryMode = etag.getValueFrom(HexUtil.hexStringToByte(batchData), "DF22", bPosEntryMode, 2)
//				if (iPosEntryMode > 0) {
//					val posEntryMode = Utils.getPayMeythod(HexUtil.bytesToHexString(bPosEntryMode, 0, iPosEntryMode))
//					cube!!.tlv_add_by_tv_in_string(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString(posEntryMode))
//				}
//
//				var isNotCompl = true
//				withContext(Dispatchers.IO) {
//					object : Thread() {
//						override fun run() {
//							super.run()
//							cube!!.proceedSalesCompletion(mContext, batchData, strOptData)
//							isNotCompl = false
//						}
//					}.start()
//				}
//
//				while (isNotCompl) {
//					val isoComm = ServiceHolder.isoComm
//					if (isoComm != null) {
//						pDMsg = isoComm.connectionStatus
//						if (pDMsg != null && pDMsg!!.isNotEmpty()) {
//							runOnUiThread(changeMessage)
//						}
//					}
//					Utils.DelayMili(500)
//				}
//
//				withContext(Dispatchers.IO) {
//					object : Thread() {
//						override fun run() {
//							super.run()
//							PrintReceiptRepo.deleteVoidedPreAuth(mContext, preAuthAmount, HexUtil.str2HexStr(textViewInvNo!!.text.toString()), HexUtil.str2HexStr(textViewApprCode!!.text.toString()))
//							updateReceiptInfo()
//							AppServices.receiptUploadToTms(mContext)
//						}
//					}.start()
//				}
//			}catch (ex: Exception){
//				ex.printStackTrace()
//			}
//		}
//		ServiceHolder.isoComm = null
//		startProgressDialog(mContext, "Bank Authorization", "Waiting for Approval")
//		startCoroutine.join()
//		closeProgressDialog()
//
//		val intent = Intent(mContext, TransactionResultActivity::class.java)
//		intent.putExtra("Cube", cube!!.cubess_tlv_db)
//		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//		startActivity(intent)
//		finish()
//	}
//
//	fun customOnBackPress() {
//		ServiceHolder.appIntent = false
//		ServiceHolder.appHTTP = false
//		val intent = Intent(this, AttendActivity::class.java)
//		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//		startActivity(intent)
//	}
//
//	fun btnCancel(view: View?) {
//		customOnBackPress()
//	}
//
//	@RequiresApi(Build.VERSION_CODES.O)
//	fun btnOk(view: View?) {
//		lifecycleScope.launch {
//			searchRecord()
//		}
//	}
//
//	private fun onBackToApp(txnMap: HashMap<String, String>) {
//		txnMap["TransactionType"] = ServiceHolder.txnType.toString()
//		val intent = Intent(this, TransactionTransmitter::class.java)
//		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//		intent.putExtra("txn_map", txnMap)
//		startActivity(intent)
//		finish()
//	}
//
//	@RequiresApi(Build.VERSION_CODES.O)
//	private fun updateReceiptInfo(): Int {
//		val strStan = cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_STAN)
//		val strRrn = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_RRN))
//		val strApprCode = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_APPRCODE))
//		val strRespCode = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_RESPCODE))
//		val strARQC = cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_ARQC)
//		val strTVR = cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_TVR)
//		val strAID = cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_AID)
//		val strEntryMode = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_ENTRY_MODE))
//
//		val valueHM = HashMap<Any, Any>()
//		valueHM["RRN"] = strRrn
//		valueHM["APPR_CODE"] = strApprCode
//		valueHM["RESP_CODE"] = strRespCode
//		valueHM["ARQC"] = strARQC
//		valueHM["TVR"] = strTVR
//		valueHM["AID"] = strAID
//		valueHM["CARD_MASKED"] = maskedPan ?: ""
//		valueHM["ENTRY_TYPE"] = strEntryMode
//		val criteriaHM = HashMap<Any, Any>()
//		criteriaHM["STAN"] = strStan
//		ReceiptUploadRepo.updateData(this, valueHM, criteriaHM)
//		return 0
//	}
}
