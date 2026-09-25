package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919.BuildConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import enums.EnumWebsocket
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.QrProductDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import datastore.DataStoreManager
import datastore.PrefKeys
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.MfHelper
import ws.WebSocketClientSingleton
import ws.WebSocketMessageListener
import enums.EnumCustomization
import enums.EnumCustomizationAcq
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getBooleanOrDefault
import helpers.HelperCommon.Companion.getStringOrDefault
import helpers.HelperCommon.Companion.runAfterDelayCancelable
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class AttendActivity : ActivityBase() {
	lateinit var mContext: Context
	lateinit var helperLog: HelperLog
	lateinit var helperLogClassName: String
	lateinit var homeBtn: LinearLayout
	lateinit var moreBtn: LinearLayout

	private var doubleBackToExitPressedOnce = false
	private var haveDuitNow = false

	private var isSpecialApp: Boolean = false

	private var haveUpiQr = false

	// 0 = Sale; 1 = DuitNow, 2 = UnionPayQR
	private var salesButtonPress: String? = null
	private var delayJob: Job? = null
	private var webSocketMessageListener: WebSocketMessageListener? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_attend)
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() { customOnBackPress() }
		})
		val runningFlavor = BuildConfig.FLAVOR
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Attend Activity Initialization"
		)
		helperLog.appendLine(helperLogClassName, "Checking Flavor:: $runningFlavor")

		if(runningFlavor == "oxpay") {
			helperLog.appendLine(helperLogClassName, "OxPay Flavor detected")
			helperLog.logToFile(EnumLogFileName.TerminaLog)

			val newIntent = Intent(applicationContext, AttendActivityOxpay::class.java)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(newIntent)
			finish()
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setTurnScreenOn(true)
			setShowWhenLocked(true)
			val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
			myKM.requestDismissKeyguard(this, null)
		} else {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		}

		if (Helper.getInstance().getPrefs() == null) {
			Helper.getInstance().Initialize(applicationContext)
		}

		mContext = this@AttendActivity
		homeBtn = findViewById(R.id.homeBtn)
		moreBtn = findViewById(R.id.moreBtn)
		homeBtn.isSelected = true
		moreBtn.isSelected = false

		val merchantConfig = ServiceHolder.getMerchantInfo()
		val customizationList = DbModelMerchantConfig.getSafeValue(merchantConfig, "Customization")

		val terminalConfig = ServiceHolder.getTerminalConfig()
		if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "ISO_WEBSOCKET")) {
			/*try {
				startWebSocketClient("ws://192.168.0.179:27060/ws")
			} catch (e: Exception) {
				e.printStackTrace()
			}*/
		}
		lifecycleScope.launch {
			val webSocketMessageListener = WebSocketMessageListener(this,
				onMessageReceived = {
					handleWebSocketResponse(it)
				}
			)
			webSocketMessageListener.startListening()
		}

		helperLog.appendLine(helperLogClassName, "Customization List :: $customizationList")
		if (customizationList.isNotEmpty()) {
			helperLog.appendLine(helperLogClassName, "Customization Checking on-going")
			val customizationArray = Gson().fromJson(customizationList, Array<String>::class.java).asList()
			if (customizationArray.filter { a -> a.contains(EnumCustomization.Redpay.value) || a.contains(EnumCustomizationAcq.RHB.value)}.isNotEmpty()) {
				isSpecialApp = true
				findViewById<TextView>(R.id.quickAction_2).setText("HISTORY")
			}

			val qrGenUPIDetail = getSpecificQrProductItem(ArrayList(listOf("Product", "QrProductCode")), arrayOf("GENERATE_QR", "QR_UNIONPAY"))
			/*val qrScanUPIDetail = getSpecificQrProductItem(
					ArrayList(listOf("Product", "QrProductCode")),
					arrayOf("EWALLET_MERCHANT_SCANS", "QR_UNIONPAY")
			)*/
			if (qrGenUPIDetail != null || merchantConfig?.Action2 == "UPIQR") {
				helperLog.appendLine(helperLogClassName, "UPI QR Customization Found")
				haveUpiQr = true
				var quickAction2 = findViewById<TextView>(R.id.quickAction_2)
				quickAction2.setText("UPI QR")
				quickAction2.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
				var quickAction2Icon = findViewById<ImageView>(R.id.quickAction_2_icon)
				quickAction2Icon.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
				quickAction2Icon.setImageResource(R.drawable.upiqr)
				quickAction2Icon.imageTintList = null
				quickAction2Icon.imageTintMode = null
			}
		}

		//HTTPServer.getInstance().isActive = true
		HTTPServer.getInstance().attendActivityContext = this@AttendActivity
		ServiceHolder.appRunningProcess = false
		//ServiceHolder.selectedCacheModel = null
		ServiceHolder.saleModelCache = null
		ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond

		//Changing the info button into DuitNow QR if support DuitNow QR
		val prodDetail = getSpecificQrProductItem(ArrayList(listOf("Product", "QrProductCode")), arrayOf("GENERATE_QR", "QR_DUITNOW"))
		helperLog.appendLine(helperLogClassName, "Checking QR_DUITNOW :: ${Gson().toJson(prodDetail)}")
		if (prodDetail != null) {
			helperLog.appendLine(helperLogClassName, "QR_DUITNOW Customization")
			haveDuitNow = true
			val tvInfo = findViewById<TextView>(R.id.tvInfo)
			tvInfo.layoutParams.width = Helper.getInstance().getDpValue(70)
			tvInfo.gravity = Gravity.CENTER_HORIZONTAL
			tvInfo.text = "DUITNOW QR"
			val ivInfo = findViewById<ImageView>(R.id.ivInfo)
			ivInfo.layoutParams.height = Helper.getInstance().getDpValue(40)
			ivInfo.layoutParams.width = Helper.getInstance().getDpValue(40)
			ivInfo.setImageResource(R.mipmap.duitnowqr_logo)
			ivInfo.imageTintList = null
			tvInfo.requestLayout()
			ivInfo.requestLayout()
		}

		//Utils.debugLogPrint(TAG, "Thread ID : " + Thread.currentThread().id)
		//Utils.printLog("Thread ID : " + Thread.currentThread().id)
		helperLog.appendLine(helperLogClassName, "Thread ID :: ${Thread.currentThread().id}")
		if (MainActivity.isFirstRun) {
			MainActivity.isFirstRun = false
		}

		//Utils.debugLogPrint("printpath", ServiceHolder.getInternalFilesPaths())
		helperLog.appendLine(helperLogClassName, "path :: ${ServiceHolder.getInternalFilesPaths()}")
		val paths = ServiceHolder.getInternalFilesPaths() + "homeLogo.png"
		//Utils.debugLogPrint("printpath", paths)
		helperLog.appendLine(helperLogClassName, "print path :: $paths")
		val imgFile = File(paths)
		if (imgFile.exists()) {
			val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
			(findViewById<View>(R.id.LayoutMain_1) as LinearLayout).background = BitmapDrawable(resources, myBitmap)
			//Utils.debugLogPrint(TAG, "Display Main Image")
			helperLog.appendLine(helperLogClassName, "Display Main Image")
		}

		lifecycleScope.launch {
			HTTPServer.resetCommunicationPort()
			checkingTerminalFlag()
		}
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	/*private fun runAutoCardSales() = CoroutineScope(Dispatchers.IO).launch {
		if(ServiceHolder.autoTestCard > 0) {
			--ServiceHolder.autoTestCard
			ServiceHolder.ackCountDownSecond = 5

			val prodDetail = ProductListRepo.getSelectedProductEnhanced(applicationContext, listOf("Product"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
			val jsonProductList = Gson().toJson(prodDetail[0])
			val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
			saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
			val salesModel = SalesModel(
					ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
					prodDetail[0].Product,
					prodDetail[0].AcqCode,
					prodDetail[0].AcqMid,
					prodDetail[0].AcqTid,
					prodDetail[0].QrProductCode,
					prodDetail[0].ProductName,
					prodDetail[0].EppProductCode,
					prodDetail[0].EppTenure,
					prodDetail[0].EppTenureCode
				)
			ServiceHolder.selectedCacheModel = salesModel
			ServiceHolder.saleModelCache = saleModelNew

			val newIntent = Intent(applicationContext, CardPaymentActivity::class.java)
			newIntent.putExtra("txnAmt", "1.00")
			newIntent.putExtra("typeofSale", ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(newIntent)
			finish()
		}
	}*/

	private fun checkingTerminalFlag() = CoroutineScope(Dispatchers.IO).launch {
		MfHelper.closeNfcUrlInterface()
		val terminalConfig = ServiceHolder.getTerminalConfig()
		val forceLockHome = DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")
		helperLog.appendLine(helperLogClassName, "Terminal flag check :: FORCE_LOCK_HOME=$forceLockHome")
		if (forceLockHome) {
			MfHelper.lockStatusBarAndNavigation(true)
		} else {
			MfHelper.lockStatusBarAndNavigation(false)
		}

		if(ServiceHolder.autoSettlementQueue) {
			helperLog.appendLine(helperLogClassName, "Auto settlement queued :: triggering")
			AppServices.triggerAutoSettle(this@AttendActivity, AppServices.ACTION_RUN_AUTO_SETTLE)
		}
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun showBlockSalesAlert() {
		helperLog.appendLine(helperLogClassName, "REJECT :: sales blocked, prompting to update app")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val builder = androidx.appcompat.app.AlertDialog.Builder(this)
		builder.setTitle("Information")
		builder.setMessage("Please Update to Latest App or Contact Sales Support for Assistance")

		// Set the positive button and its action
		builder.setNegativeButton("OK") { dialog, _ ->
			dialog.dismiss()
		}
		// Set the negative button and its action
		/*builder.setPositiveButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }*/

		// Create and show the alert dialog
		val alertDialog: androidx.appcompat.app.AlertDialog = builder.create()
		alertDialog.show()
	}

	fun webSocketSaleValidation(mid: String) {
		startProgressDialog(mContext, "Loading", "Loading Terminal Configuration...")
		val requestJson = JsonObject()
		val dataReq = JsonObject()
		requestJson.addProperty("RequestRef", "")
		requestJson.addProperty("MsgEvent", "ValidateSales")

		dataReq.addProperty("DEV_SN", ServiceHolder.getTerminalSerialNumber())
		dataReq.addProperty("MID", mid)
		requestJson.addProperty("DataReq", dataReq.toString())
		WebSocketClientSingleton.send(Gson().toJson(requestJson))

		// Fail Safety for timeout
		lifecycleScope.launch {
			delayJob = runAfterDelayCancelable(10 * 1000) {
				try{
					closeProgressDialog()
				} catch (ex: Exception) {
					ex.printStackTrace()
				}
				showBlockSalesAlert()
			}
		}
	}

	fun sale_btn_func(view: View?) {
		lifecycleScope.launch {
			val error = validateSale()
			if (error != null) {
				Toast.makeText(applicationContext, error, Toast.LENGTH_SHORT).show()
				return@launch
			}

			val merchantConfig = ServiceHolder.getMerchantInfo()
			if(DbModelMerchantConfig.getSafeValue(merchantConfig, "SkipTxnValidation") == "true") {
				val helperLogSale = HelperLog(
					HelperCommon.getSession(),
					TmsHelper.checkIsConnectedWifi(applicationContext),
					Utils.getIPAddress(),
					helperLogClassName,
					helperLogClassName,
					"AttendActivity - Sale",
				)

				val terminalConfig = ServiceHolder.getTerminalConfig()
				val enableCard = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")
				val enableEWallet = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_EWALLET")
				selectionDialog(helperLogSale, enableCard, enableEWallet)
			} else {
				salesButtonPress = "0"
				webSocketSaleValidation(merchantConfig?.AcqMid ?: "")
			}
		}
	}

	fun settle_btn_func(view: View?) {
		val merchantInfo = ServiceHolder.getMerchantInfo()
		if (merchantInfo == null) {
			Toast.makeText(this, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
			return
		}
		if (ServiceHolder.autoSettlementIsRunning) {
			Toast.makeText(this, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
			return
		}

		val helperLogSettlement = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"AttendActivity - Settlement",
		)
		SelectionDialog_settlement(helperLogSettlement)
	}

	fun void_btn_func(view: View?) {
		val merchantInfo = ServiceHolder.getMerchantInfo()
		val terminalConfig = ServiceHolder.getTerminalConfig()
		if (!DbModelTerminalConfig.getBooleanValue(terminalConfig, "Void")) {
			Toast.makeText(this, "Void is disabled", Toast.LENGTH_SHORT).show()
			return
		}
		if (merchantInfo == null) {
			Toast.makeText(this, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
			return
		}
		if (ServiceHolder.autoSettlementIsRunning) {
			Toast.makeText(this, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
			return
		}
		if (ProductListRepo.getCountSettledProduct(applicationContext) > 0) {
			Toast.makeText(this, "Please Finish the Settlement", Toast.LENGTH_SHORT).show()
			return
		}

		val helperLogVoid = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"AttendActivity - Void",
		)
		SelectionDialog_void(helperLogVoid)
	}

	fun logon_btn_func(view: View?) {
		val helperLogAbout = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"AttendActivity - About",
		)
		helperLogAbout.appendLine(helperLogClassName, "About Button On Clicked")
		if (haveDuitNow) {
			helperLogAbout.appendLine(helperLogClassName, "Duit Now Configuration Found")
			val prodDetail = getSpecificQrProductItem(ArrayList(listOf("Product", "QrProductCode")), arrayOf("GENERATE_QR", "QR_DUITNOW"))
			helperLogAbout.appendLine(helperLogClassName, "Product Detail :: ${Gson().toJson(prodDetail)}")
			if (prodDetail != null) {
				val merchantConfig = ServiceHolder.getMerchantInfo()
				if(DbModelMerchantConfig.getSafeValue(merchantConfig, "SkipTxnValidation") == "true") {
					val (_, _, SalesType) = ProductCatSelectionDataEnum.valueOf("GENERATE_QR").data
					val salesModel = SalesModel(
						SalesType,
						prodDetail.Product,
						prodDetail.AcqCode,
						prodDetail.AcqMid,
						prodDetail.AcqTid,
						prodDetail.QrProductCode,
						prodDetail.ProductName,
						prodDetail.EppProductCode,
						prodDetail.EppTenure,
						prodDetail.EppTenureCode
					)
					ServiceHolder.selectedCacheModel = salesModel
					val jsonProductList = Gson().toJson(prodDetail)
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = SalesType
					ServiceHolder.saleModelCache = saleModelNew

					helperLogAbout.appendLine(helperLogClassName, "Express Duit Now Features Trigger")
					helperLogAbout.logToFile(EnumLogFileName.TerminaLog)
					val intent = Intent(applicationContext, KeypadActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					startActivity(intent)
					finish()
				} else {
					salesButtonPress = "1"
					webSocketSaleValidation(prodDetail.AcqMid)
				}
				return
			}
		}

		helperLogAbout.appendLine(helperLogClassName, "About Activity Information")
		helperLogAbout.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, AboutActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun print_btn_func(view: View?) {
		if (haveUpiQr) {
			val merchantConfig = ServiceHolder.getMerchantInfo()
			if(DbModelMerchantConfig.getSafeValue(merchantConfig, "SkipTxnValidation") == "true") {
				val intent = Intent(applicationContext, KeypadActivity::class.java)
				intent.putExtra("isUPIQR", true)
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				startActivity(intent)
				finish()
			} else {
				salesButtonPress = "2"
				webSocketSaleValidation(merchantConfig?.AcqMid ?: "")
			}
			return
		} else {
			if (ServiceHolder.autoSettlementIsRunning) {
				Toast.makeText(this, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
				return
			}

			val helperLogPrint = HelperLog(
				HelperCommon.getSession(),
				TmsHelper.checkIsConnectedWifi(applicationContext),
				Utils.getIPAddress(),
				helperLogClassName,
				helperLogClassName,
				"AttendActivity - TxnViewer",
			)
			SelectionDialog_txnViewer(helperLogPrint)
		}
	}

	fun more_pressed(view: View?) {
		if (ServiceHolder.autoSettlementIsRunning) {
			Toast.makeText(this, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
			return
		}
		val intent = Intent(this, SettingsActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		homeBtn.isSelected = false
		moreBtn.isSelected = true
		finish()
	}

	fun home_pressed(view: View?) {
		homeBtn.isSelected = true
		moreBtn.isSelected = false
		val intent = Intent(this@AttendActivity, AboutActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun customOnBackPress() {
		if (doubleBackToExitPressedOnce) {
			passwordAlertDialog(1, "132465", object : onAlertDialogListener {
				override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
					helperLog.appendLine(helperLogClassName, "Pin Checker :: $clickStatus")
					helperLog.appendLine(helperLogClassName, "is OK :: $isOK")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					if (clickStatus) {
						if (isOK) {
							val intent = Intent(this@AttendActivity, MainActivity::class.java)
							intent.putExtra("closeFlag", true)
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							startActivity(intent)
							finish()
						} else {
							ToastMake(this@AttendActivity, "Incorrect pin", Toast.LENGTH_SHORT)
						}
					}
				}
			})
			return
		}

		helperLog.appendLine(helperLogClassName, "customOnBackPress detected :: $doubleBackToExitPressedOnce")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		doubleBackToExitPressedOnce = true
		Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
		Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
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

	private fun selectionDialog(helperLogDialog: HelperLog, enableCardSale: Boolean, enableEwalletSale: Boolean) {
		helperLogDialog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PAYMENT METHOD]")
		var merchantProduct = ServiceHolder.getMerchantProduct()
		if(!enableCardSale) {
			val mutableList = merchantProduct?.toMutableList() ?: mutableListOf()
			mutableList.remove(ProductCatSelectionDataEnum.CARD_SETTINGS.name)
			mutableList.remove(ProductCatSelectionDataEnum.EPP.name)
			merchantProduct = mutableList.toList()
		}

		if(!enableEwalletSale) {
			val mutableList = merchantProduct?.toMutableList() ?: mutableListOf()
			mutableList.remove(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name)
			mutableList.remove(ProductCatSelectionDataEnum.GENERATE_QR.name)
			merchantProduct = mutableList.toList()
		}

		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_dynamic_selection, null
		)
		val dynamicTitle = dialogView.findViewById<TextView>(R.id.dynamic_title)
		dynamicTitle.text = "SELECT PAYMENT METHOD"
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLogDialog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PAYMENT METHOD]")
			helperLogDialog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
		}
		val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)
		var count = 0
		try {
			for (a in merchantProduct!!) {
				val (ProductLogo, ProductTitle, SalesType, Active, SubProduct, NextClass) = ProductCatSelectionDataEnum.valueOf(a).data
				if (ServiceHolder.blockSale || !Active || NextClass == null) {
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
				if (a == "GENERATE_QR") {
					val subProductList = ProductListRepo.getSelectedProductEnhanced(applicationContext, listOf("Product"), arrayOf(a))
					val productSize = subProductList.size
					if (productSize == 1) {
						childLinear.gravity = Gravity.CENTER
					}
					var imageDisplay = 0
					for (item in subProductList) {
						if (imageDisplay >= 3) break
						val (_, _, _, _, QrProductCode) = item

						try {
							val (_, logoImage) = QrProductDataEnum.valueOf(QrProductCode).data
							val iv = ImageView(applicationContext)
							iv.setImageResource(logoImage)
							val paramsIV = LinearLayout.LayoutParams(
								Helper.getInstance().getDpValue(50), ViewGroup.LayoutParams.WRAP_CONTENT
							)
							paramsIV.leftMargin = Helper.getInstance().getDpValue(20)
							if (imageDisplay == 0 && productSize == 1) {
								paramsIV.leftMargin = Helper.getInstance().getDpValue(0)
							} else if (imageDisplay == 1 && productSize == 2) {
								paramsIV.rightMargin = Helper.getInstance().getDpValue(30)
								val spaceView = Space(applicationContext)
								val paramsSP = LinearLayout.LayoutParams(
									Helper.getInstance().getDpValue(0), Helper.getInstance().getDpValue(1)
								)
								paramsSP.weight = 1f
								spaceView.layoutParams = paramsSP
								childLinear.addView(spaceView)
							}
							iv.adjustViewBounds = true
							iv.layoutParams = paramsIV
							childLinear.addView(iv)
							imageDisplay++
						} catch (e: Exception) {
							e.printStackTrace()
						}
					}
				} else {
					if (a == "BNPL") {
						val iouPayProduct = ProductListRepo.getSelectedProductEnhanced(
								applicationContext,
								ArrayList(listOf("AcqCode", "QrProductCode")),
								arrayOf("IOUPAY","BNPL_MYIOU")
						)

						if (iouPayProduct.isEmpty()) {
							continue
						}
					}

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
				if (SubProduct) {
					childLinear.setOnClickListener {
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT PAYMENT METHOD]")
						alertDialog?.dismiss()
						val intent = Intent(
							applicationContext,
							GenerateQrSubProductActivity::class.java
						)
						intent.putExtra("product_cat", a)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent)
						finish()
					}
				} else {
					childLinear.setOnClickListener(View.OnClickListener {
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT PAYMENT METHOD]")
						val prodDetail = ProductListRepo.getSelectedProductEnhanced(
							applicationContext, listOf("Product"), arrayOf(a)
						)
						if (prodDetail.isEmpty()) {
							Toast.makeText(applicationContext, "Error While Obtain Product Details", Toast.LENGTH_SHORT).show()
							return@OnClickListener
						}
						val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = prodDetail[0]
						val salesModel = SalesModel(
							SalesType,
							Product,
							AcqCode,
							AcqMid,
							AcqTid,
							QrProductCode,
							ProductName,
							EppProductCode,
							EppTenure,
							EppTenureCode
						)
						ServiceHolder.selectedCacheModel = salesModel
						//TODO Revamp
						val jsonProductList = Gson().toJson(prodDetail[0])
						val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
						saleModelNew.SalesType = SalesType
						ServiceHolder.saleModelCache = saleModelNew
						//TODO Revamp
						alertDialog?.dismiss()
						val intent = Intent(applicationContext, NextClass)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent)
						finish()
					})
				}
				mainContainer.addView(childLinear)
				count++
			}
		} catch (e: Exception) {
			e.printStackTrace()

			helperLogDialog.appendLine(helperLogClassName, "Exception in Sales Selection Dialog ", e.toString())
			helperLogDialog.logToFile(EnumLogFileName.TerminaLogException)
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

	private fun SelectionDialog_void(helperLogDialog: HelperLog) {
		helperLogDialog.appendLine(helperLogClassName, "Dialog opened :: [SELECT VOID METHOD]")
		val merchantProduct = ServiceHolder.getMerchantProduct()
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_dynamic_selection, null
		)
		val dynamicTitle = dialogView.findViewById<TextView>(R.id.dynamic_title)
		dynamicTitle.text = "SELECT VOID METHOD"
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLogDialog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT VOID METHOD]")
			helperLogDialog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
		}
		val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)
		var count = 0
		try {
			var isQRShow = false
			for (a in merchantProduct!!) {
				val (ProductLogo, ProductTitle, _, Active, _, _, _, _, _, VoidNextClass) = ProductCatSelectionDataEnum.valueOf(a).data
				if (!Active || VoidNextClass == null || a == "BNPL" || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
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
					//GENERATE_QR
					isQRShow = true
					val iv = ImageView(applicationContext)
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
				} else { // CARD_SETTINGS
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
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT VOID METHOD]")
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
								ServiceHolder.selectedCacheModel = salesModel
								val jsonProductList = Gson().toJson(generateQrProduct)
								val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
								saleModelNew.SalesType = 0
								ServiceHolder.saleModelCache = saleModelNew
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
								ServiceHolder.selectedCacheModel = salesModel
								val jsonProductList = Gson().toJson(ewalletProduct)
								val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
								saleModelNew.SalesType = 0
								ServiceHolder.saleModelCache = saleModelNew
							}
							alertDialog?.dismiss()
							val intent = Intent(applicationContext, VoidNextClass)
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							intent.putExtra("typeofSale", 3)
							intent.putExtra("terminalPIN", terminalPIN)
							startActivity(intent)
							finish()
						}
					}
				} else {
					val dynamicProduct = getSpecificProduct(a)
					childLinear.setOnClickListener(View.OnClickListener {
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT VOID METHOD]")
						if (dynamicProduct == null) {
							return@OnClickListener
						}
						val productModel = ProductListRepo.getSingle(applicationContext, listOf("AcqCode", "AcqMid", "AcqTid", "Product"), arrayOf(dynamicProduct.AcqCode, dynamicProduct.AcqMid, dynamicProduct.AcqTid, a))
						val jsonProductList = Gson().toJson(productModel)
						val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
						saleModelNew.SalesType = 0
						ServiceHolder.saleModelCache = saleModelNew

						alertDialog?.dismiss()
						val intent = Intent(applicationContext, VoidNextClass)
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
			helperLogDialog.appendLine(helperLogClassName, "Exception in Void Selection Dialog", e.toString())
			helperLogDialog.logToFile(EnumLogFileName.TerminaLogException)
		}

		if (count <= 0) {
			val emptyTextView = TextView(applicationContext)
			emptyTextView.text = "No Void Option Available.\nPlease Contact Sales Support for assistance"
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
			alertDialog_1?.dismiss()
		} catch (e: Exception) {
			e.printStackTrace()
		}
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()

		val terminalConfig = ServiceHolder.getTerminalConfig()
		if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
			PINDialog("", false)
		}
	}

	private fun SelectionDialog_settlement(helperLogDialog: HelperLog) {
		helperLogDialog.appendLine(helperLogClassName, "Dialog opened :: [SELECT SETTLEMENT TYPE]")
		val merchantProduct = ServiceHolder.getMerchantProduct()
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_dynamic_selection, null
		)
		val dynamicTitle = dialogView.findViewById<TextView>(R.id.dynamic_title)
		dynamicTitle.text = "SELECT SETTLEMENT TYPE"
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLogDialog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT SETTLEMENT TYPE]")
			helperLogDialog.logToFile(EnumLogFileName.TerminaLog)
			alertDialog?.dismiss()
		}
		val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)
		var count = 0
		try {
			var isQRShow = false
			for (a in merchantProduct!!) {
				val (ProductLogo, ProductTitle, _, Active, _, _, _, _, SettlementNextClass) = ProductCatSelectionDataEnum.valueOf(
					a
				).data
				if (!Active || SettlementNextClass == null || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
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
					//GENERATE_QR
					isQRShow = true
					val iv = ImageView(applicationContext)
					iv.setImageResource(ProductLogo)
					val paramsIV = LinearLayout.LayoutParams(
						Helper.getInstance().getDpValue(30), Helper.getInstance().getDpValue(30)
					)
					paramsIV.leftMargin = Helper.getInstance().getDpValue(30)
					iv.layoutParams = paramsIV
					val tv = TextView(applicationContext)
					tv.text = "QR"
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
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT SETTLEMENT TYPE]")
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
							val intent = Intent(applicationContext, SettlementNextClass)
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							startActivity(intent)
							finish()
						}
					}
				} else {
					val dynamicProduct = getSpecificProduct(a)
					childLinear.setOnClickListener(View.OnClickListener {
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT SETTLEMENT TYPE]")
						if (dynamicProduct == null) {
							return@OnClickListener
						}
						alertDialog?.dismiss()
						val intent = Intent(applicationContext, SettlementNextClass)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent)
						finish()
					})
				}
				mainContainer.addView(childLinear)
				count++
			}
		} catch (e: Exception) {
			e.printStackTrace()
			helperLogDialog.appendLine(helperLogClassName, "Exception in Settlement Selection Dialog -> ", e.toString())
			helperLogDialog.logToFile(EnumLogFileName.TerminaLogException)

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
			alertDialog_1?.dismiss()
		} catch (e: Exception) {
			e.printStackTrace()
		}
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()

		val terminalConfig = ServiceHolder.getTerminalConfig()
		if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "SETTLEMENT_WITH_PIN")) {
			PINDialog("settlement", false)
		}
	}

	private fun SelectionDialog_txnViewer(helperLogDialog: HelperLog) {
		helperLogDialog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PRINT METHOD]")
		val merchantProduct = ServiceHolder.getMerchantProduct()
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_dynamic_selection, null
		)
		val dynamicTitle = dialogView.findViewById<View>(R.id.dynamic_title) as TextView
		dynamicTitle.text = "SELECT PRINT METHOD"
		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLogDialog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRINT METHOD]")
			helperLogDialog.logToFile(EnumLogFileName.TerminaLog)
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
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT PRINT METHOD]")
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

							var intent = Intent(this@AttendActivity, PrintNextClass)
							if (isSpecialApp) {
								intent = Intent(this@AttendActivity, CustomPrintNextClass)
							}
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							intent.putExtra("typeofSale", 3)
							startActivity(intent)
							finish()
						}
					}
				} else {
					val dynamicProduct = getSpecificProduct(a)
					childLinear.setOnClickListener(View.OnClickListener {
						helperLogDialog.appendLine(helperLogClassName, "Selected :: $a [SELECT PRINT METHOD]")
						if (dynamicProduct == null) {
							return@OnClickListener
						}
						alertDialog?.dismiss()
						intent = Intent(this@AttendActivity, CustomPrintNextClass)
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
			helperLogDialog.appendLine(helperLogClassName, "Exception in Txn Viewer Selection Dialog ", e.toString())
			helperLogDialog.logToFile(EnumLogFileName.TerminaLogException)
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

	fun saleNavigation(enableCard: Boolean, enableEWallet: Boolean) {
		helperLog.appendLine(helperLogClassName, "Sale navigation :: button=$salesButtonPress " +
			"enableCard=$enableCard enableEWallet=$enableEWallet")
		when (salesButtonPress) {
			"1" -> {
				if(!enableEWallet) {
					Toast.makeText(this, "Terminal Ewallet Sales Features Deactivated", Toast.LENGTH_SHORT).show()
					return
				}
				val prodDetail = getSpecificQrProductItem(ArrayList(listOf("Product", "QrProductCode")), arrayOf("GENERATE_QR", "QR_DUITNOW"))
				if (prodDetail != null) {
					val (_, _, SalesType) = ProductCatSelectionDataEnum.valueOf("GENERATE_QR").data
					val salesModel = SalesModel(
						SalesType,
						prodDetail.Product,
						prodDetail.AcqCode,
						prodDetail.AcqMid,
						prodDetail.AcqTid,
						prodDetail.QrProductCode,
						prodDetail.ProductName,
						prodDetail.EppProductCode,
						prodDetail.EppTenure,
						prodDetail.EppTenureCode
					)
					ServiceHolder.selectedCacheModel = salesModel
					val jsonProductList = Gson().toJson(prodDetail)
					val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
					saleModelNew.SalesType = SalesType
					ServiceHolder.saleModelCache = saleModelNew

					val intent = Intent(applicationContext, KeypadActivity::class.java)
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					startActivity(intent)
					finish()
				}
			}
			"2" -> {
				val newIntent = Intent(applicationContext, KeypadActivity::class.java)
				newIntent.putExtra("isUPIQR", true)
				newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				startActivity(newIntent)
				finish()
			}
			else -> {
				val helperLogSale = HelperLog(
					HelperCommon.getSession(),
					TmsHelper.checkIsConnectedWifi(applicationContext),
					Utils.getIPAddress(),
					helperLogClassName,
					helperLogClassName,
					"AttendActivity - Sale",
				)
				selectionDialog(helperLogSale, enableCard, enableEWallet)
			}
		}
	}

	private fun handleWebSocketResponse(message: String?) {
		if (message.isNullOrEmpty()) {
			return
		}
		try{
			val gson = Gson()
			val dataJson = gson.fromJson(message, JsonObject::class.java)
			val messageEvent = dataJson.getStringOrDefault("MsgEvent", "")
			if (messageEvent == EnumWebsocket.ValidateSale.socketCommand) {
				delayJob?.cancel()

				val rawDataResp = dataJson.getStringOrDefault("DataResp", "")
				val dataResp = gson.fromJson(rawDataResp, JsonObject::class.java)
				val cardResult = dataResp.getBooleanOrDefault("IsAllowProceedSales", false)
				val eWalletResult = dataResp.getBooleanOrDefault("IsAllowProceedEwalletSales", false)

				helperLog.appendLine(helperLogClassName, "ValidateSale response :: " +
					"card=$cardResult ewallet=$eWalletResult")
				if(cardResult || eWalletResult) {
					saleNavigation(cardResult, eWalletResult)
				} else {
					showBlockSalesAlert()
				}

				try{
					closeProgressDialog()
				} catch (ex: Exception) {
					ex.printStackTrace()
				}
			}
		} catch (e: Exception) {
			helperLog.appendLine(helperLogClassName, "ValidateSale parse failed :: ${e.message}")
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}
	}

	private suspend fun validateSale(): String? {
		val merchantInfo = ServiceHolder.getMerchantInfo()
		if (merchantInfo == null) {
			return "Please download/configure Terminal Settings at Portal"
		}

		if (ProductListRepo.getCountSettledProduct(applicationContext) > 0) {
			return "Please Finish the Settlement"
		}

		if (ServiceHolder.clearSettlementBatch) {
			return "Please Run Settlement for Last day Transaction before Proceed"
		}

		if (ServiceHolder.autoSettlementIsRunning) {
			return "Auto Settlement is running"
		}

		val store = DataStoreManager(applicationContext)
		val isSettlementBlock = store.getBoolean(PrefKeys.settlementBlock)
		val (_, _, _, _, settleBlockFlag) = ServiceHolder.getAcquirerSetting()

		println("isSettlementBlock >> [$isSettlementBlock] , settleBlockFlag >> [$settleBlockFlag]")
		if (isSettlementBlock && settleBlockFlag) {
			return "Please Retry the Settlement"
		}

		return null
	}

	override fun onDestroy() {
		super.onDestroy()
		/*val runningFlavor = BuildConfig.FLAVOR
		if(runningFlavor != "oxpay") {
			HTTPServer.getInstance().isActive = false
			//HTTPServer.getInstance().attendActivityContext = null
		}*/
		delayJob?.cancel()
		webSocketMessageListener?.stopListening()
	}

	override fun onResume() {
		super.onResume()
		val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
		if (myKM.isKeyguardLocked) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				myKM.requestDismissKeyguard(this,null)
			} else {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
			}
		}
	}
}