package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.os.*
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.java.device.DeviceHelper
import enums.EnumWebsocket
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum.Companion.getProductCatForHttp
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.SecureDataRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getAcquirerSetting
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getAppVersion
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.saleModelCache
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.txnType
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919.kotlin.helper_common.TmsHelper.getInjectionKey
import com.sc.mf919.kotlin.helper_common.TmsHelper.getMerchantConfiguration
import com.sc.mf919.kotlin.helper_common.TmsHelper.getTerminalConfiguration
import com.sc.mf919.kotlin.helper_common.TmsHelper.uploadAllTerminalLog
import env.EnvironmentManager
import env.EnvironmentVariables
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.context
import helpers.HelperCommon.Companion.getSession
import helpers.HelperCommon.Db.Companion.getTimestamp
import helpers.HelperLog
import org.json.JSONObject
import tms.models.WebSocketClientReq
import java.io.File
import java.util.*

class SettingsActivity : ActivityBase() {
	var homeBtn: LinearLayout? = null
	var moreBtn: LinearLayout? = null
	var cube: CubeActivity? = null
	var ISOSocketEnabled = false
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_setting)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Settings / functions menu"
		)
		helperLog.appendLine(helperLogClassName, "Settings screen opened")
		homeBtn = findViewById(R.id.homeBtn)
		moreBtn = findViewById(R.id.moreBtn)
		homeBtn!!.isSelected = false
		moreBtn!!.isSelected = true
		cube = CubeActivity()
		dynamicScreenOption()

		val terminalConfig = getTerminalConfig()
		println("ISO_WEBSOCKET >> ${getBooleanValue(terminalConfig, "ISO_WEBSOCKET")}")
		if (!getBooleanValue(terminalConfig, "ISO_WEBSOCKET")) {
			//ISOSocketEnabled = true
			//val webSocketClient = getWebSocketClient()
			/*lifecycleScope.launch {
				webSocketClient?.messagesFlow?.collect { message ->
					println("Message received 123: ${message}")
					handleWebSocketResponse(message)
				}
			}*/
		}

		val dbModelMerchantConfig = getMerchantInfo()

		val qrGenUPIDetail = getSpecificQrProductItem(
				ArrayList(listOf("Product", "QrProductCode")),
				arrayOf("GENERATE_QR", "QR_UNIONPAY")
		)

		val txnHistory = findViewById<LinearLayout>(R.id.txnHistoryBtn)
		if (qrGenUPIDetail != null || dbModelMerchantConfig?.Action2 == "UPIQR") {
			txnHistory.visibility = View.VISIBLE
		} else {
			txnHistory.visibility = View.GONE
		}


		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun getSpecificQrProductItem(
		fieldList: ArrayList<String>,
		valueList: Array<String>
	): DbModelProductList? {
		val specificProdList = ProductListRepo.getSelectedProductEnhanced(applicationContext, fieldList, valueList)
		var productItem: DbModelProductList? = null
		if (specificProdList.isNotEmpty()) {
			productItem = specificProdList[0]
		}
		return productItem
	}

	private fun dynamicScreenOption() {
		val terminalConfig = getTerminalConfig()
		runOnUiThread { //MOTO Visibility
			if(getBooleanValue(terminalConfig, "MOTO")){
				findViewById<LinearLayout>(R.id.setting_moto_linear).visibility = View.VISIBLE
			} else {
				findViewById<LinearLayout>(R.id.setting_moto_linear).visibility = View.GONE
			}

			//PreAuth Visibility
			if (getBooleanValue(terminalConfig, "PreAuth")) {
				findViewById<LinearLayout>(R.id.setting_preauth_linear).visibility = View.VISIBLE
			} else {
				findViewById<LinearLayout>(R.id.setting_preauth_linear).visibility = View.GONE
			}

			//CashOut Visibility
			if (getBooleanValue(terminalConfig, "CASHOUT")) {
				findViewById<LinearLayout>(R.id.setting_cashout_linear).visibility = View.VISIBLE
			} else {
				findViewById<LinearLayout>(R.id.setting_cashout_linear).visibility = View.GONE
			}

			//Void Visibility
			if (getBooleanValue(terminalConfig, "DENOMINATION")) {
				val merchantProduct = ServiceHolder.getMerchantProduct()

				if (merchantProduct?.contains("CARD_SETTINGS") == true ) {
					findViewById<LinearLayout>(R.id.setting_void).visibility = View.VISIBLE
				} else {
					findViewById<LinearLayout>(R.id.setting_void).visibility = View.GONE
				}

				if (merchantProduct?.any { it == "EWALLET_MERCHANT_SCANS" || it == "GENERATE_QR" } == true) {
					findViewById<LinearLayout>(R.id.setting_void_qr).visibility = View.VISIBLE
				} else {
					findViewById<LinearLayout>(R.id.setting_void_qr).visibility = View.GONE
				}
			}
		}
	}

	fun cashout_btn_func(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Cash Out [SETTINGS]")
		val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
		val jsonProductList = Gson().toJson(dbProductModel)
		val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
		ServiceHolder.selectedCacheModel = saleModelOld
		val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
		saleModelNew.SalesType = ProductCatSelectionDataEnum.CASH_OUT.data.SalesType
		ServiceHolder.saleModelCache = saleModelNew

		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val newIntent = Intent(this@SettingsActivity, KeypadActivity::class.java)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		newIntent.putExtra("typeofSale", ProductCatSelectionDataEnum.CASH_OUT.data.SalesType)
		startActivity(newIntent)
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	fun last_reversal_btn_func(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Last Reversal [SETTINGS]")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		object : Thread() {
			override fun run() {
				super.run()
				val helperLog = HelperLog(
					getSession(),
					checkIsConnectedWifi(applicationContext),
					Utils.getIPAddress(),
					"Settings Activity Last Reversal",
					this@SettingsActivity.javaClass.simpleName,
					this@SettingsActivity.javaClass.simpleName
				)
				var reversalResult = true
				startProgressDialog(view.context, "", "Running Last Reversal")

				val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
				val cardProduct = ProductListRepo.getSinglev2(view.context, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
				val batchNo = IsoBatchInfoRepo.getBatchInfo(view.context, "batchNo", "visam")?.value ?: ""
				val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(cardProduct.AcqCode, "reversal")

				val reversalBatch = ReversalBatchTableRepo.getBatchData(view.context, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, cardProduct.AcqMid, cardProduct.AcqTid))
				helperLog.appendLine(this@SettingsActivity.javaClass.simpleName, "Batch List :: (${reversalBatch.size})")
				if(reversalBatch.isEmpty() || acquirerRevIsoModel == null) {
					sleep(500)
					helperLog.appendLine(this@SettingsActivity.javaClass.simpleName, "Empty Reversal Batch...")
					runOnUiThread {
						Toast.makeText(view.context, "No Reversal Found...", Toast.LENGTH_SHORT).show()
					}
				} else {
					sleep(500)
					for (tempFor in reversalBatch) {
						helperLog.appendLine(this@SettingsActivity.javaClass.simpleName, "Reversal Batch Model::(${Gson().toJson(tempFor)})")
						TransData.reset(view.context)

						TransData.acqCode = cardProduct.AcqCode
						TransData.product = cardProduct.Product
						TransData.mid = cardProduct.AcqMid
						TransData.tid = cardProduct.AcqTid
						TransData.schemeTag = "visam"
						TransData.txnTypeLabel = "Reversal"
						TransData.ksn = cardProduct.Ksn
						TransData.pinKsn = cardProduct.PinKsn
						TransData.stan = tempFor.stan
						TransData.invoiceNo = tempFor.invNo
						TransData.batchNo = tempFor.batchNo
						TransData.isTpaAccount = cardProduct.IsTpaAccount.lowercase() == "true"
						TransData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
						TransData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
						val revResult = IsoActivity.processReversal(view.context, false, acquirerRevIsoModel, tempFor.batchData, false, helperLog)
						if(reversalResult && revResult == null){
							reversalResult = false
						}
					}

					var toastMessage = "One of the Reversal UnSuccessful..."
					if(reversalResult) {
						toastMessage = "Reversal Successful..."
					}
					runOnUiThread {
						Toast.makeText(view.context, toastMessage, Toast.LENGTH_SHORT).show()
					}
				}
				closeProgressDialog()
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	fun more_pressed(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: More tab [SETTINGS]")
		homeBtn!!.isSelected = false
		moreBtn!!.isSelected = true
	}

	fun home_pressed(view: View?) {
//		customOnBackPress()
		helperLog.appendLine(helperLogClassName, "Selected :: Home tab [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AboutActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		homeBtn!!.isSelected = true
		moreBtn!!.isSelected = false
		val intent = Intent(this@SettingsActivity, AboutActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun preauth_btn_func(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth [SETTINGS]")
		val dbModelMerchantConfig = getMerchantInfo()
		val terminalConfig = getTerminalConfig()
		if (getBooleanValue(terminalConfig, "PreAuth")) {
			if (getSafeValue(dbModelMerchantConfig, "AcqMid") == "") {
				helperLog.appendLine(helperLogClassName, "REJECT :: AcqMid not configured")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				Toast.makeText(
					this, "Please download/configure Terminal Settings at Portal",
					Toast.LENGTH_SHORT
				).show()
			} else {
				SelectionDialog_preauth()
			}
		} else {
			helperLog.appendLine(helperLogClassName, "REJECT :: PreAuth disabled in terminal config")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(this, "PreAuth is disabled", Toast.LENGTH_SHORT).show()
		}
	}

	private fun SelectionDialog_preauth() {
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_preauthselection, null
		)
		//dialogView.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PRE-AUTH FUNCTION]")
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn_preauth)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRE-AUTH FUNCTION]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
		}
		val cardSale = dialogView.findViewById<LinearLayout>(R.id.preauth)
		cardSale.setOnClickListener(preAuthSelectionDialog)
		val qrSale = dialogView.findViewById<LinearLayout>(R.id.SaleComp)
		qrSale.setOnClickListener(preAuthSelectionDialog)
		val preauthCancel = dialogView.findViewById<LinearLayout>(R.id.PCancel)
		preauthCancel.setOnClickListener(preAuthSelectionDialog)
		val voidSaleComp = dialogView.findViewById<LinearLayout>(R.id.VoidSaleComp)
		voidSaleComp.setOnClickListener(preAuthSelectionDialog)
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	var preAuthSelectionDialog = View.OnClickListener { v ->
		when (v.id) {
			R.id.preauth -> {
				try{
					//val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
					val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
					val jsonProductList = Gson().toJson(dbProductModel)
					val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
					ServiceHolder.selectedCacheModel = saleModelOld
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = 8
					ServiceHolder.saleModelCache = saleModelNew
					alertDialog?.dismiss()

					helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth [SELECT PRE-AUTH FUNCTION]")
					helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					val intent = Intent(this@SettingsActivity, KeypadActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					intent.putExtra("typeofSale", 8)
					intent.putExtra("indexM", 1)
					intent.putExtra("indexT", 1)
					startActivity(intent)
				}catch (ex: Exception){
					alertDialog?.dismiss()
					helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Pre-Auth")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ex.printStackTrace()
					ToastMake(this@SettingsActivity, "Product Not Configured", Toast.LENGTH_SHORT)
				}
			}
			R.id.SaleComp -> {
				try{
					//val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
					val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
					val jsonProductList = Gson().toJson(dbProductModel)
					val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
					ServiceHolder.selectedCacheModel = saleModelOld
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = 4
					ServiceHolder.saleModelCache = saleModelNew
					alertDialog?.dismiss()

					helperLog.appendLine(helperLogClassName, "Selected :: Sale Complete [SELECT PRE-AUTH FUNCTION]")
					helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					val intent = Intent(this@SettingsActivity, KeypadActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					intent.putExtra("typeofSale", 4)
					intent.putExtra("indexM", 1)
					intent.putExtra("indexT", 1)
					startActivity(intent)
				}catch (ex: Exception){
					alertDialog?.dismiss()
					helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Sale Complete")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ex.printStackTrace()
					ToastMake(this@SettingsActivity, "Product Not Configured", Toast.LENGTH_SHORT)
				}
			}
			R.id.PCancel -> {
				try{
					//val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
					val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
					val jsonProductList = Gson().toJson(dbProductModel)
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = 4
					ServiceHolder.saleModelCache = saleModelNew
					alertDialog?.dismiss()

					helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth Cancel [SELECT PRE-AUTH FUNCTION]")
					helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidPreauthActivity")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					val intent = Intent(this@SettingsActivity, VoidPreauthActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					intent.putExtra("typeofSale", 4)
					intent.putExtra("indexM", 1)
					intent.putExtra("indexT", 1)
					startActivity(intent)
				}catch (ex: Exception){
					alertDialog?.dismiss()
					helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Pre-Auth Cancel")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ex.printStackTrace()
					ToastMake(this@SettingsActivity, "Product Not Configured", Toast.LENGTH_SHORT)
				}
			}
			R.id.VoidSaleComp -> {
				try{
					//val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
					val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
					val jsonProductList = Gson().toJson(dbProductModel)
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = 4
					ServiceHolder.saleModelCache = saleModelNew
					alertDialog?.dismiss()

					helperLog.appendLine(helperLogClassName, "Selected :: Void Sale Complete [SELECT PRE-AUTH FUNCTION]")
					helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidOffSaleActivity")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					val intent = Intent(this@SettingsActivity, VoidOffSaleActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					intent.putExtra("typeofSale", 4)
					intent.putExtra("indexM", 1)
					intent.putExtra("indexT", 1)
					startActivity(intent)
				}catch (ex: Exception){
					alertDialog?.dismiss()
					helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Void Sale Complete")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ex.printStackTrace()
					ToastMake(this@SettingsActivity, "Product Not Configured", Toast.LENGTH_SHORT)
				}
			}
			R.id.cancel_btn_preauth -> {
				helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRE-AUTH FUNCTION]")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				alertDialog?.dismiss()
			}
		}
	}

	fun adjustment_btn_func(view: View?) {
		//Production No Ready Yet
		//SelectionDialog_adjustment()
	}

	private fun SelectionDialog_adjustment() {
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_adjustmentselection, null
		)
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn_adjustment)
		cancelBtn.setOnClickListener { alertDialog?.dismiss() }
		val debitAdjustment = dialogView.findViewById<LinearLayout>(R.id.debit_adjustment)
		debitAdjustment.setOnClickListener(adjustmentSelectionDialog)
		val creditAdjustment = dialogView.findViewById<LinearLayout>(R.id.credit_adjustment)
		creditAdjustment.setOnClickListener(adjustmentSelectionDialog)
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	var adjustmentSelectionDialog = View.OnClickListener { v ->
		when (v.id) {
			R.id.debit_adjustment -> {
				alertDialog?.dismiss()
				val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
				val jsonProductList = Gson().toJson(dbProductModel)
				val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
				ServiceHolder.selectedCacheModel = saleModelOld
				val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
				saleModelNew.SalesType = ProductCatSelectionDataEnum.CASH_OUT.data.SalesType
				ServiceHolder.saleModelCache = saleModelNew

				val intent = Intent(this@SettingsActivity, AdjustmentActivity::class.java)
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				intent.putExtra("adjustmentType", "debit_adjustment")
				startActivity(intent)
			}
			R.id.credit_adjustment -> {
				alertDialog?.dismiss()
				val dbProductModel = ServiceHolder.getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name) ?: throw Exception()
				val jsonProductList = Gson().toJson(dbProductModel)
				val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
				ServiceHolder.selectedCacheModel = saleModelOld
				val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
				saleModelNew.SalesType = ProductCatSelectionDataEnum.CASH_OUT.data.SalesType
				ServiceHolder.saleModelCache = saleModelNew

				val intent = Intent(this@SettingsActivity, AdjustmentActivity::class.java)
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				intent.putExtra("adjustmentType", "credit_adjustment")
				startActivity(intent)
			}
			R.id.cancel_btn_adjustment -> {
				alertDialog?.dismiss()
			}
		}
	}

	fun btn_fun_admin(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Admin [SETTINGS]")
		passwordAlertDialog(1, "666666", object : onAlertDialogListener {
			override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
				if (clickStatus) {
					if (isOK) {
						helperLog.appendLine(helperLogClassName, "Password check :: pass [ADMIN PASSWORD]")
						helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AdminActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent = Intent(this@SettingsActivity, AdminActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent)
						finish()
					} else {
						helperLog.appendLine(helperLogClassName, "REJECT :: password check failed [ADMIN PASSWORD]")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						ToastMake(this@SettingsActivity, "Incorrect pin", Toast.LENGTH_SHORT)
					}
				}
			}
		})
	}

	fun btn_fun_download_config(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Download Configuration [SETTINGS]")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		object : Thread() {
			override fun run() {
				super.run()
				val log = HelperLog(
					getSession(),
					checkIsConnectedWifi(applicationContext),
					Utils.getIPAddress(),
					"Setting Download Configuration",
					this@SettingsActivity.javaClass.simpleName,
					this@SettingsActivity.javaClass.name
				)
				startProgressDialog(
					view.context, "Download Configuration", "Downloading... Please wait"
				)
				Utils.DelayMili(1000)
				var resp = downloadConfiguration(log)
				if (resp == 0) {
					ToastMake(
						this@SettingsActivity, "Successfully Downloaded Terminal Configuration",
						Toast.LENGTH_SHORT
					)
				} else {
					ToastMake(
						this@SettingsActivity, "Fail To Download Terminal Configuration", Toast.LENGTH_SHORT
					)
				}
				resp = downloadMerchantInfo(log)
				if (resp == 0) {
					ToastMake(
						this@SettingsActivity, "Successfully Downloaded Merchant Configuration",
						Toast.LENGTH_SHORT
					)
					val dbModelMerchantConfig = getMerchantInfo()
					val acqMid = getSafeValue(dbModelMerchantConfig, "AcqMid")
					val acqTid = getSafeValue(dbModelMerchantConfig, "AcqTid")
					val tpduVal = getSafeValue(dbModelMerchantConfig, "TPDU")
					val niiVal = getSafeValue(dbModelMerchantConfig, "NII")
					log.appendLine(helperLogClassName, "Setting changed :: merchant config MID=$acqMid TID=$acqTid")
					//VISAM
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpduVal, "isoTpduHeader", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpduVal, "isoTpduHeaderTle", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, niiVal, "nii", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, niiVal, "niiTle", "visam")
					//MCCS
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpduVal, "isoTpduHeader", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpduVal, "isoTpduHeaderTle", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, niiVal, "nii", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, niiVal, "niiTle", "mccs")

					runOnUiThread {
						val qrGenUPIDetail = getSpecificQrProductItem(
								ArrayList(listOf("Product", "QrProductCode")),
								arrayOf("GENERATE_QR", "QR_UNIONPAY")
						)

						var txnHistory = findViewById<LinearLayout>(R.id.txnHistoryBtn)
						if (qrGenUPIDetail != null || dbModelMerchantConfig?.Action2 == "UPIQR") {
							txnHistory.visibility = View.VISIBLE
						} else {
							txnHistory.visibility = View.GONE
						}
					}
				} else {
					ToastMake(
						this@SettingsActivity, "Fail To Download Merchant Configuration", Toast.LENGTH_SHORT
					)
				}
				Utils.DelayMili(1000)
				dynamicScreenOption()
				closeProgressDialog()
				log.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	private fun downloadMerchantInfo(log: HelperLog): Int {
		val result = getMerchantConfiguration(log, applicationContext)
		return if (!result) -1 else 0
	}

	private fun downloadConfiguration(log: HelperLog): Int {
		val result = getTerminalConfiguration(log, applicationContext)
		return if (!result) -1 else 0
	}

//	private fun checkForUpdate(): Int {
//		val environmentManager = getInstance().getPrefs()?.let { EnvironmentManager(it) }
//		val dbModelTerminalConfig = getTerminalConfig()
//		val currentTime = Date()
//		val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
//		val timeStamp = sdf.format(currentTime)
//		var firmID = ""
//		if (Utility.exist_file(getInternalFilesPaths() + "firmID.txt")) {
//			val file = Utility.read_file(getInternalFilesPaths() + "firmID.txt")
//			firmID = file[0]
//		}
//
//		//Header
//		val header: MutableMap<String, String> = HashMap()
//		header["Content-Type"] = "application/json"
//
//		//Json Body
//		val jsonObject = JSONObject()
//		try {
//			jsonObject.put("SEQ_NO", getSqnNum())
//			jsonObject.put("TXN_DT", timeStamp)
//			jsonObject.put("DEV_SN", getTerminalSerialNumber())
//			jsonObject.put("DEV_MODEL", getDeviceModel())
//			jsonObject.put("DEV_APPNAME", getString(R.string.app_name))
//			jsonObject.put("DEV_PROJECT", getSafeValue(dbModelTerminalConfig, "DEV_PROJECT"))
//			jsonObject.put("DEV_LOCATION", getSafeValue(dbModelTerminalConfig, "DEV_LOCATION"))
//			jsonObject.put("DEV_LANE_ID", getSafeValue(dbModelTerminalConfig, "DEV_LANE_ID"))
//			jsonObject.put("FIRM_VER", getAppVersion())
//			jsonObject.put("FIRM_ID", firmID)
//			jsonObject.put("ADD_INFO", "-")
//			jsonObject.put("MC_VER", getMcVersion())
//		} catch (e: JSONException) {
//			e.printStackTrace()
//		}
//		val body = jsonObject.toString()
//		Utils.debugLogPrint("TAG", "checkForUpdate: $jsonObject")
//
//
//		//Calculate Hashkey
//		var strHashKeyOut = ""
//		try {
//			strHashKeyOut = Cryptography.HMAC(
//				Utility.ASCIItoHexString(body),
//				Utility.ASCIItoHexString(environmentManager!!.getByJava("serverHashKey")),
//				Cryptography.hashAlgorithm.SHA_256
//			)
//		} catch (e: Exception) {
//			e.printStackTrace()
//		}
//		Utils.debugLogPrint("TAG", "CHECKSUM=$strHashKeyOut")
//		header["CHECKSUM"] = strHashKeyOut.uppercase(Locale.getDefault())
//		header["TERMINAL_IP"] = Utils.getPublicIP()
//		val connection = HttpConnection(
//			environmentManager!!.getByJava("baseUrl") + getStringValue(
//				"tmsUrl.ini", "checkUpdateUrl"
//			), header, body,
//			30 * 1000
//		)
//		connection.POSTRequest()
//		if (connection.responseCode != 200) {
//			Utils.debugLogPrint(
//				"TAG",
//				"BCBase:Error: HTTP Response: " + connection.responseCode + " --> Message: " + connection.responseMessage
//			)
//			return -1
//		}
//		val resp = connection.responseBody
//		if (resp == null) {
//			Utils.debugLogPrint("TAG", "BCBase:Error: Body Message = NULL")
//			return -100
//		}
//		try {
//			val responseMsg = JSONObject(resp)
//			if (responseMsg.getString("RESP_CODE") != "0000") {
//				return -1
//			} else {
//				val appName = responseMsg.getString("FIRM_FILENAME")
//				Utils.debugLogPrint("TAG", "checkForUpdate: $appName")
//
//
//				//appName="app-debug.apk";
//				/*JSONArray jsonArray = responseMsg.getJSONArray("TASK_NAME");
//                for(int j=0;j<jsonArray.length();j++)
//                {
//                    switch (jsonArray.getString(j))
//                    {
//                        case "MerchantConfigUpdate":
//                        {
//                            downloadMerchantInfo();
//                            break;
//                        }
//                        case "TerminalConfigUpdate":
//                        {
//                            downloadConfiguration();
//                            break;
//                        }
//                        case "InjectKeyUpdate":
//                        {
//                            downloadInjectKey();
//                            break;
//                        }
//                    }
//                }*/Utils.debugLogPrint(
//					"TAG",
//					"checkForUpdate1: $appName"
//				)
//				if (appName != "null") {
//					mFirmId = responseMsg.getString("FIRM_ID")
//					val url = responseMsg.getString("FIRM_URL")
//					Utils.debugLogPrint(
//						"TAG",
//						"checkForUpdate: $url---$mFirmId"
//					)
//					//url="https://sharecommerce-pg.oss-ap-southeast-3.aliyuncs.com/terminal/";
//					pDTitle = "Download APK File"
//					runOnUiThread(changeTitle)
//					pDMsg = "Downloading... Please Wait"
//					runOnUiThread(changeMessage)
//					Utils.debugLogPrint(
//						"TAG",
//						Environment.getExternalStorageDirectory().path + File.separator + "app-debug.apk"
//					)
//					val download = UrlDownload(
//						appName,
//						url,  /*Environment.getExternalStorageDirectory().getPath() + File.separator + "testInstall.apk"*/ /*ServiceHolder.Companion.getInternalFilesPaths()+"/app.apk"*/
//						Environment.getExternalStorageDirectory().path + File.separator + "app-debug.apk"
//					)
//					download.getFile()
//					if (download.percentage != 100) {
//						return -1
//					}
//				} else {
//					return -1
//				}
//			}
//		} catch (e: JSONException) {
//			e.printStackTrace()
//			return -1
//		}
//		Utils.debugLogPrint("TAG", resp)
//		return 0
//	}

	var handlerUpadte: Handler = object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			runUpdate()
			//updateFile();
		}
	}

	@SuppressLint("SdCardPath")
	private fun updateFile() {
//		if (Build.VERSION.SDK_INT >= 24) {
//			val intent = Intent(Intent.ACTION_VIEW)
//			val photoURI = FileProvider.getUriForFile(
//				applicationContext, applicationContext.packageName + ".provider",
//				File( /*ServiceHolder.getInternalFilesPaths()*/
//					Environment.getExternalStorageDirectory().path + File.separator + "/app-debug.apk"
//				)
//			)
//			intent.setDataAndType(photoURI, "application/vnd.android.package-archive")
//			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
//			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//			startActivity(intent)
//		} else {
//			val intent = Intent(Intent.ACTION_VIEW)
//			intent.setDataAndType(
//				Uri.fromFile(
//					File( /*ServiceHolder.getInternalFilesPaths()+"/app.apk"*/
//						Environment.getExternalStorageDirectory().path + File.separator + "app-debug.apk"
//					)
//				), "application/vnd.android.package-archive"
//			)
//			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
//			startActivity(intent)
//		}
		val intent = Intent(Intent.ACTION_VIEW)
		val photoURI = FileProvider.getUriForFile(
			applicationContext, applicationContext.packageName + ".provider",
			File( /*ServiceHolder.getInternalFilesPaths()*/
				Environment.getExternalStorageDirectory().path + File.separator + "/app-debug.apk"
			)
		)
		intent.setDataAndType(photoURI, "application/vnd.android.package-archive")
		intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		startActivity(intent)
	}

	@Synchronized
	private fun runUpdate() {
		try {
			//firmUpdate_ack();
			if (this::helperLog.isInitialized) {
				helperLog.appendLine(helperLogClassName, "Start installapp")
			} else {
				Utils.debugLogPrint("TAG", "Start installapp")
			}
			DeviceHelper.getDeviceService().installApp( /*ServiceHolder.getInternalFilesPaths()+"/app.apk"*/
					Environment.getExternalStorageDirectory().path + File.separator + "app-debug.apk",
					"", ""
				)
			doRestart()
			if (this::helperLog.isInitialized) {
				helperLog.appendLine(helperLogClassName, "end installapp")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			} else {
				Utils.debugLogPrint("TAG", "end installapp")
			}
		} catch (exception: RemoteException) {
			if (this::helperLog.isInitialized) {
				helperLog.appendLine(helperLogClassName, "Installation failed :: ${exception.message}")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
			}
			exception.printStackTrace()
		}
		// Install Updated APK
		/*String command = "pm install -r " + ServiceHolder.getInternalFilesPaths()+"/app.apk";
        Process proc = null;
        try
        {
            proc = Runtime.getRuntime().exec(new String[] {"run-as com.sc.mf919", command});
            int test = proc.waitFor(); // Error is here.
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        / *if (proc.exitValue() == 0)
        {
            // Successfully installed updated app

        }*/
	}

	private fun doRestart() {
		val mStartActivity = Intent(applicationContext, MainActivity::class.java)
		val mPendingIntentId = 123456
		val mPendingIntent = PendingIntent.getActivity(
			applicationContext, mPendingIntentId, mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val mgr = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
		mgr[AlarmManager.RTC, System.currentTimeMillis() + 30000] = mPendingIntent
		//System.exit(0);
	}

	fun btn_fun_update_app(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Update App [SETTINGS]")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		object : Thread() {
			override fun run() {
				super.run()
				ToastMake(this@SettingsActivity, "Latest App already...", Toast.LENGTH_SHORT)
//				startProgressDialog(view.context, "Check for Update", "Checking... Please wait")
//				Utils.DelayMili(1000)
//				val resp = checkForUpdate()
//				if (resp == 0) {
//					Utils.write2File(arrayOf(mFirmId, getAppVersion()), "updateApk.txt")
//					Utils.write2File(arrayOf(mFirmId), "firmID.txt")
//					ToastMake(this@SettingsActivity, "Successfully Downloaded App", Toast.LENGTH_SHORT)
//					handlerUpadte.sendMessage(Message())
//				} else {
//					ToastMake(this@SettingsActivity, "Fail To Download App", Toast.LENGTH_SHORT)
//				}
//				Utils.DelayMili(1000)
//				closeProgressDialog()
			}
		}.start()
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving Settings")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		homeBtn!!.isSelected = true
		moreBtn!!.isSelected = false

		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(this@SettingsActivity, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	fun btn_fun_void(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Void [SETTINGS]")
		try {
			val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
			val salesModel = SalesModel(
				ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
				dbProductModel.Product,
				dbProductModel.AcqCode,
				dbProductModel.AcqMid,
				dbProductModel.AcqTid,
				dbProductModel.QrProductCode,
				dbProductModel.ProductName,
				dbProductModel.EppProductCode,
				dbProductModel.EppTenure,
				dbProductModel.EppTenureCode
			)
			selectedCacheModel = salesModel
			val gson = Gson()
			val jsonProductList = gson.toJson(dbProductModel)
			val saleModelNew =
				gson.fromJson(jsonProductList, SaleModelNew::class.java)
			saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
			saleModelCache = saleModelNew
		} catch (e: java.lang.Exception) {
			helperLog.appendLine(helperLogClassName, "REJECT :: CARD_SETTINGS product not configured")
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
			runOnUiThread(object : Runnable {
				override fun run() {
					Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT)
						.show()
				}
			})
			return
		}
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidSaleActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, VoidSaleActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun btn_fun_void_qr(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Void QR [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidQrActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, VoidQrActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun btn_fun_lastSettlement(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Last Settlement [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, SettlementActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("Last", 1)
		startActivity(intent)
		finish()
	}

	fun btn_fun_lastSettlementQr(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Last Settlement QR [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementQrActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, SettlementQrActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("Last", 1)
		startActivity(intent)
		finish()
	}

	fun btn_fun_lastSettlementBnpl(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Last Settlement BNPL [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementBnplActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, SettlementBnplActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("Last", 1)
		startActivity(intent)
		finish()
	}

	fun btn_fun_about(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: About [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AboutActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, AboutActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	private fun set_downloadInjectKey(): Int {
		val log = HelperLog(
			getSession(),
			checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			"Setting Injection Key",
			this@SettingsActivity.javaClass.simpleName,
			this@SettingsActivity.javaClass.name
		)
		val result = getInjectionKey(log,this)
		log.logToFile(EnumLogFileName.TerminaLog)
		return if (result) {
			0
		} else {
			-1
		}
	}

	private fun set_injectKeys(): Int {
		return set_downloadInjectKey()
	}

	fun btn_settings_fun_injectkeys(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Inject Keys [SETTINGS]")
		val (acqName) = getAcquirerSetting()
		if (acqName.equals("BSN", ignoreCase = true)) {
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> BsnKeyDownloadActivity")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			val intent = Intent(this, BsnKeyDownloadActivity::class.java)
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
		} else {
			object : Thread() {
				override fun run() {
					super.run()
					startProgressDialog(view.context, "Inject Keys", "Downloading... Please wait")
					Utils.DelayMili(1000)
					val resp = set_injectKeys()
					helperLog.appendLine(helperLogClassName, "Inject Keys result :: $resp")
					if (resp == 0) {
						ToastMake(this@SettingsActivity, "Successfully Inject Keys", Toast.LENGTH_SHORT)
					} else {
						ToastMake(this@SettingsActivity, "Fail To Download Inject Keys", Toast.LENGTH_SHORT)
					}
					Utils.DelayMili(1000)
					closeProgressDialog()
					helperLog.logToFile(EnumLogFileName.TerminaLog)
				}
			}.start()
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private fun set_downloadKeys(): Int {
		TransData.reset(this@SettingsActivity)
		val acquirerInfo = ServiceHolder.getAcquirerSetting()
		TransData.acqCode = acquirerInfo.acqName
		val taskLog = HelperLog(
			getSession(),
			checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			"SignOn",
			this@SettingsActivity.javaClass.simpleName,
			this@SettingsActivity.javaClass.simpleName
		)
		val iSignOnResp = IsoActivity.processSignOn(this@SettingsActivity, taskLog)
		taskLog.logToFile(EnumLogFileName.TerminaLog)
		return if (iSignOnResp) 0 else -1
	}

	fun btn_settings_fun_downloadkeys(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Download Keys / Sign On [SETTINGS]")
		val (acqName) = getAcquirerSetting()
		if (acqName.equals("BSN", ignoreCase = true)) {
			helperLog.appendLine(helperLogClassName, "REJECT :: sign on not required for BSN")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			ToastMake(this@SettingsActivity, "Sign on is not required", Toast.LENGTH_SHORT)
		} else {
			object : Thread() {
				@RequiresApi(api = Build.VERSION_CODES.O)
				override fun run() {
					super.run()

					if (ISOSocketEnabled) {
						startProgressDialog(view.context, "Download Keys", "Downloading... Please wait")
						val envManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)

						val dbModelMerchantConfig = getMerchantInfo()
						val tid = IsoBatchInfoRepo.getBatchInfo(applicationContext, "tid", "visam")?.value
						val mid = IsoBatchInfoRepo.getBatchInfo(applicationContext, "mid", "visam")?.value
						val tpdu = IsoBatchInfoRepo.getBatchInfo(applicationContext, "isoTpduHeader", "visam")?.value
						val nii = IsoBatchInfoRepo.getBatchInfo(applicationContext, "nii", "visam")?.value

						val strRevSecureTleScheme = "visam-tle$acqName-$mid"

						val strTakKey = SecureDataRepo.getDecryptedSingle(applicationContext, listOf("tag", "subtag"), listOf("eTakKey", strRevSecureTleScheme))?.value ?: ""
						val strTmkId = SecureDataRepo.getSingle(applicationContext, listOf("tag", "subtag"), arrayOf("eTmkId", strRevSecureTleScheme))?.value ?: ""
						val strTmkKey = SecureDataRepo.getDecryptedSingle(applicationContext, listOf("tag", "subtag"), listOf("eTmkKey", strRevSecureTleScheme))?.value ?: ""

						val timeStamp = getTimestamp()
						//val webSocketClient = getWebSocketClient()
						val jsonObject = JSONObject()
						jsonObject.put("UniqueRef", timeStamp)
						jsonObject.put("AcqCode", acqName.uppercase())
						jsonObject.put("AcqMID", mid)
						jsonObject.put("AcqTID", tid)
						jsonObject.put("SystemMID", dbModelMerchantConfig?.QrMid)
						jsonObject.put("SystemTID", dbModelMerchantConfig?.QrTid)
						jsonObject.put("TerminalSN", ServiceHolder.getTerminalSerialNumber())
						jsonObject.put("AppVer", getAppVersion())
						jsonObject.put("TerminalIP", Utils.getIPAddress())
						jsonObject.put("TPDU", tpdu)
						jsonObject.put("NII", nii)
						jsonObject.put("eTmkId", HelperCommon.AESencryptV2(strTmkId, envManager.get(EnvironmentVariables::socketServerHashKey)))
						jsonObject.put("eTmkKey", HelperCommon.AESencryption(strTmkKey, envManager.get(EnvironmentVariables::socketServerHashKey)))
						jsonObject.put("eTakKey", HelperCommon.AESencryption(strTakKey, envManager.get(EnvironmentVariables::socketServerHashKey)))

						val jsonString = jsonObject.toString()
						val base64ApiJsonString = String(Base64.encode(jsonString.toByteArray(), Base64.DEFAULT))
						val command = EnumWebsocket.Signon.socketCommand
						var signStr = "$timeStamp.$command.$base64ApiJsonString"
						var checksum = HelperCommon.toHmacSha256(signStr, envManager.get(EnvironmentVariables::socketServerHashKey))
						var requestObj = WebSocketClientReq(
								timeStamp.toString(),
								ServiceHolder.getTerminalSerialNumber(),
								command,
								base64ApiJsonString,
								checksum
						)
						val gson = Gson()
						val requestJsonString = gson.toJson(requestObj)

						//val isRequested = webSocketClient?.sendMessage(requestJsonString)
						val isRequested = false
						if (isRequested == false) {
							closeProgressDialog()
						}
					} else {
						startProgressDialog(view.context, "Download Keys", "Downloading... Please wait")
						Utils.DelayMili(1000)
						context = applicationContext
						val resp = set_downloadKeys()
						helperLog.appendLine(helperLogClassName, "Sign On result :: $resp")
						if (resp == 0) {
							ToastMake(this@SettingsActivity, "Successfully Downloaded Keys", Toast.LENGTH_SHORT)
						} else {
							ToastMake(this@SettingsActivity, "Fail To Download Keys", Toast.LENGTH_SHORT)
						}
						Utils.DelayMili(1000)
						closeProgressDialog()
						helperLog.logToFile(EnumLogFileName.TerminaLog)
					}
				}
			}.start()
		}
	}

	fun uploadFile() {
		object : Thread() {
			override fun run() {
				startProgressDialog(this@SettingsActivity, "Upload File", "Uploading....")
				val log = HelperLog(
					getSession(),
					checkIsConnectedWifi(applicationContext),
					Utils.getIPAddress(),
					"Uploading Log",
					this@SettingsActivity.javaClass.simpleName,
					this@SettingsActivity.javaClass.name
				)
				val result = uploadAllTerminalLog(log, applicationContext, true)
				if (result) {
					log.appendLine(this@SettingsActivity.javaClass.name, "Upload Completed")
					ToastMake(this@SettingsActivity, "Upload Completed", Toast.LENGTH_LONG)
				} else {
					if (checkIsConnectedWifi(applicationContext)) {
						log.appendLine(this@SettingsActivity.javaClass.name, "Upload Fail")
						ToastMake(this@SettingsActivity, "Upload Failed", Toast.LENGTH_LONG)
					} else {
						log.appendLine(this@SettingsActivity.javaClass.name, "Upload Cancel Wi-Fi is required")
						ToastMake(this@SettingsActivity, "Wifi Connection is required", Toast.LENGTH_LONG)
					}
				}

				try {
					sleep(1000)
					closeProgressDialog()
					log.logToFile(EnumLogFileName.TerminaLog)
				} catch (e: InterruptedException) {
					e.printStackTrace()
				}
			}
		}.start()
	}

	fun btn_fun_upload_log(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Upload Log [SETTINGS]")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		uploadFile()
	}

	fun moto_btn_func(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: MOTO [SETTINGS]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> MotoSaleActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, MotoSaleActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("typeofSale", ProductCatSelectionDataEnum.MOTO.data.SalesType)
		startActivity(intent)
	}

	fun btn_fun_history_transaction(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Transaction History [SETTINGS]")
		if (ServiceHolder.autoSettlementIsRunning) {
			helperLog.appendLine(helperLogClassName, "REJECT :: auto settlement is running")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(this, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
			return
		}

		val merchantProduct = ServiceHolder.getMerchantProduct()
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
				R.layout.activity_dynamic_selection, null
		)
		val dynamicTitle = dialogView.findViewById<View>(R.id.dynamic_title) as TextView
		dynamicTitle.text = "SELECT PRINT METHOD"
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PRINT METHOD]")
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRINT METHOD]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
		}
		val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)
		var count = 0
		try {
			var isQRShow = false
			for (a in merchantProduct!!) {
				val (ProductLogo, ProductTitle, _, Active, _, _, PrintNextClass, CustomPrintNextClass) = ProductCatSelectionDataEnum.valueOf(
						a
				).data
				if (!Active || PrintNextClass == null || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
					// Category not yet configure and active on terminal
					continue
				}
				val childLinear = LinearLayout(applicationContext)
				val params = LinearLayout.LayoutParams(
						Helper.getInstance().getDpValue(250), Helper.getInstance().getDpValue(67)
				)
				params.setMargins(0, 0, 0, Helper.getInstance().getDpValue(20))
				params.gravity = Gravity.CENTER
				childLinear.gravity = Gravity.CENTER_VERTICAL
				childLinear.setBackgroundResource(R.drawable.other_btn)
				childLinear.orientation = LinearLayout.HORIZONTAL
				childLinear.layoutParams = params

				if ((a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && !isQRShow) { // GENERATE_QR & EWALLET
					isQRShow = true
					val iv = ImageView(
							applicationContext
					)
					iv.setImageResource(ProductLogo)
					val paramsIV = LinearLayout.LayoutParams(
							Helper.getInstance().getDpValue(30), Helper.getInstance().getDpValue(30)
					)
					paramsIV.leftMargin = Helper.getInstance().getDpValue(30)
					iv.layoutParams = paramsIV
					val tv = TextView(applicationContext)
					tv.text = "QR SCAN"
					tv.gravity = Gravity.CENTER_HORIZONTAL
					tv.setTextColor(ContextCompat.getColor(this, R.color.black))
					tv.textSize = 18f
					val paramsTV = LinearLayout.LayoutParams(
							Helper.getInstance().getDpValue(150), LinearLayout.LayoutParams.WRAP_CONTENT
					)
					paramsTV.rightMargin = Helper.getInstance().getDpValue(20)
					tv.layoutParams = paramsTV
					childLinear.addView(iv)
					childLinear.addView(tv)
				} else { // CARD_SETTINGS/BNPL
					val iv = ImageView(applicationContext)
					iv.setImageResource(ProductLogo)
					val paramsIV = LinearLayout.LayoutParams(
							Helper.getInstance().getDpValue(30), Helper.getInstance().getDpValue(30)
					)
					paramsIV.leftMargin = Helper.getInstance().getDpValue(30)
					iv.layoutParams = paramsIV
					val tv = TextView(applicationContext)
					tv.text = ProductTitle
					tv.gravity = Gravity.CENTER_HORIZONTAL
					tv.setTextColor(ContextCompat.getColor(this, R.color.black))
					tv.textSize = 18f
					val paramsTV = LinearLayout.LayoutParams(
							Helper.getInstance().getDpValue(150), LinearLayout.LayoutParams.WRAP_CONTENT
					)
					paramsTV.rightMargin = Helper.getInstance().getDpValue(20)
					tv.layoutParams = paramsTV
					childLinear.addView(iv)
					childLinear.addView(tv)
				}
				if (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") {
					val ewalletProduct = getSpecificProduct("EWALLET_MERCHANT_SCANS")
					val generateQrProduct = getSpecificProduct("GENERATE_QR")
					childLinear.setOnClickListener {
						if (ewalletProduct != null || generateQrProduct != null) {
							if (ewalletProduct == null) {
								val salesModel = SalesModel(
										0,
										generateQrProduct!!.Product,
										generateQrProduct.AcqCode,
										generateQrProduct.AcqMid,
										generateQrProduct.AcqTid,
										generateQrProduct.QrProductCode,
										generateQrProduct.ProductName,
										generateQrProduct.EppProductCode,
										generateQrProduct.EppTenure,
										generateQrProduct.EppTenureCode
								)
								ServiceHolder.selectedSettlementModel = salesModel
							} else {
								val salesModel = SalesModel(
										0,
										ewalletProduct.Product,
										ewalletProduct.AcqCode,
										ewalletProduct.AcqMid,
										ewalletProduct.AcqTid,
										ewalletProduct.QrProductCode,
										ewalletProduct.ProductName,
										ewalletProduct.EppProductCode,
										ewalletProduct.EppTenure,
										ewalletProduct.EppTenureCode
								)
								ServiceHolder.selectedSettlementModel = salesModel
							}
							alertDialog?.dismiss()

							helperLog.appendLine(helperLogClassName, "Selected :: $a [SELECT PRINT METHOD]")
							helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${PrintNextClass.simpleName}")
							helperLog.logToFile(EnumLogFileName.TerminaLog)
							var intent = Intent(this, PrintNextClass)
//							if (isSpecialApp) {
//								intent = Intent(this, CustomPrintNextClass)
//							}
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							intent.putExtra("typeofSale", 3)
							startActivity(intent)
							finish()
						}
					}
				} else {
					val dynamicProduct = getSpecificProduct(a)
					childLinear.setOnClickListener(View.OnClickListener {
						if (dynamicProduct == null) {
							helperLog.appendLine(helperLogClassName, "REJECT :: $a product not configured [SELECT PRINT METHOD]")
							helperLog.logToFile(EnumLogFileName.TerminaLog)
							return@OnClickListener
						}
						alertDialog?.dismiss()
						helperLog.appendLine(helperLogClassName, "Selected :: $a [SELECT PRINT METHOD]")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						intent = Intent(this, CustomPrintNextClass)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						intent.putExtra("typeofSale", 3)
						startActivity(intent)
						finish()
					})
				}
				mainContainer.addView(childLinear)
				count++
			}
		} catch (e: Exception) {
			e.printStackTrace()
//			log.appendLine(helperlogClassName, "Exception in Txn Viewer Selection Dialog ", e.toString())
//			log.logToFile(EnumLogFileName.TerminaLog)
//			log.logToFile(EnumLogFileName.TerminaLogException)
		}
		if (count <= 0) {
			val emptyTextView = TextView(applicationContext)
			emptyTextView.text = "No Payment Option Available.\nPlease Contact Sales Support for assistance"
			emptyTextView.setTextColor(ContextCompat.getColor(this, R.color.black))
			emptyTextView.textSize = 18f
			val paramsTV = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			paramsTV.setMargins(
					Helper.getInstance().getDpValue(20), 0, Helper.getInstance().getDpValue(20),
					Helper.getInstance().getDpValue(20)
			)
			emptyTextView.layoutParams = paramsTV
			mainContainer.addView(emptyTextView)
		}
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)

		//handle prevent double alert dialog for temporary
		try {
			alertDialog?.dismiss()
		} catch (e: Exception) {
			e.printStackTrace()
		}
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	private fun getSpecificProduct(name: String): DbModelProductList? {
		val merchantProductList = ServiceHolder.getMerchantProductList()
		if (merchantProductList != null) {
			for (item in merchantProductList) {
				if (item.Product == name) {
					return item
				}
			}
		}
		return null
	}

	fun btnFunResetCommPort(view: View) {
		helperLog.appendLine(helperLogClassName, "Selected :: Reset Communication Port [SETTINGS]")
		object : Thread() {
			override fun run() {
				super.run()
				startProgressDialog(view.context, "Reset", "Resetting Communication Port...")
				HTTPServer.resetCommunicationPort()
				Utils.DelayMili(2000)
				ToastMake(this@SettingsActivity, "Refreshed Communication Port", Toast.LENGTH_SHORT)
				closeProgressDialog()
				helperLog.appendLine(helperLogClassName, "Communication port reset")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	/*private fun handleWebSocketResponse(message: String?) {
		if (message.isNullOrEmpty()) {
			return
		}

		try {
			val envManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
			val gson = Gson()
			val response = gson.fromJson(message, WebSocketClientResp::class.java)

			if (response.Event == EnumWebsocket.Signon.socketCommand) {
				if (!response.Data.toString().isNullOrEmpty()) {
					var decryptedMsg = HelperCommon.AESdecryption(response.Data.toString(), envManager.get(EnvironmentVariables::socketServerHashKey))

					val dataJson = gson.fromJson(decryptedMsg, JsonObject::class.java)

					val statusCode = dataJson.getStringOrDefault("StatusCode", "")
					val statusDesc = dataJson.getStringOrDefault("StatusDesc", "")

					if (statusCode == "00") {
						ToastMake(this@SettingsActivity, "Successfully Downloaded Keys", Toast.LENGTH_SHORT)
					} else {
						ToastMake(this@SettingsActivity, "Fail To Download Keys", Toast.LENGTH_SHORT)
					}
				} else {
					ToastMake(this@SettingsActivity, "Fail To Download Keys", Toast.LENGTH_SHORT)
				}

				closeProgressDialog()
			}

		} catch (e: Exception) {
			println("Error parsing message: ${e.message}")
		}
	}*/
}
