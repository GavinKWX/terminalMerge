package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import java.math.BigDecimal
import java.util.ArrayList
import kotlin.concurrent.thread

class KeypadActivity : BaseActivity() {
	lateinit var mContext: Context
	lateinit var helperLog: HelperLog
	lateinit var helperLogClassName: String

	private var textView_amount: TextView? = null
	private var typeOfSale = 0
	private var apprCode: String? = null
	private var rrn: String? = null
	private var invNo: String? = null
	private var selectedSales: SalesModel? = null
	private var tenure = 0
	private var tenureDesc: String? = null
	private var packageCode: String? = null
	private var paymentType: String? = null
	private var posReference: String? = null
	private var isUPIQR: Boolean = false

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_keypad)
		val toolbar = findViewById<Toolbar>(R.id.toolbarSale)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() { customOnBackPress() }
		})
		mContext = this@KeypadActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Keypad Activity Initialization"
		)
		helperLog.appendLine(helperLogClassName, "Keypad opened")

		typeOfSale = intent.getIntExtra("typeofSale", 0)
		indexM = intent.getIntExtra("indexM", 0)
		indexT = intent.getIntExtra("indexT", 0)
		apprCode = intent.getStringExtra("apprCode")
		rrn = intent.getStringExtra("rrn")
		invNo = intent.getStringExtra("invNo")
		tenure = intent.getIntExtra("tenure", 0)
		tenureDesc = intent.getStringExtra("tenureDesc")
		packageCode = intent.getStringExtra("packageCode")
		paymentType = intent.getStringExtra("paymentType")
		posReference = intent.getStringExtra("posReference")
		val amount = intent.getStringExtra("txnAmt")
		isUPIQR = intent.getBooleanExtra("isUPIQR", false)

		selectedSales = selectedCacheModel as SalesModel?
		helperLog.appendLine(helperLogClassName, "Type of Sale :: $typeOfSale")
		if(typeOfSale == 5) {
			val jsonProductList = Gson().toJson(ServiceHolder.saleModelCache)
			selectedSales =  Gson().fromJson(jsonProductList, SalesModel::class.java)
		} else if (selectedSales == null) {
			selectedSales = SalesModel(0, "", "", "", "", "", "", "", "", "")
		}

		if (typeOfSale == 4) {
			//PreAuth Sales Complete
			(findViewById<View>(R.id.textView2) as TextView).text = "SALE-COM AMOUNT"
			(findViewById<View>(R.id.textHeader) as TextView).text = "SALE-COM"
		}else if (typeOfSale == 8) {
			//PreAuth
			(findViewById<View>(R.id.textView2) as TextView).text = "PRE-AUTH AMOUNT"
			(findViewById<View>(R.id.textHeader) as TextView).text = "PRE-AUTH"
		}

		// Revamped method
		when (selectedSales!!.SalesType) {
			10 -> {
				(findViewById<View>(R.id.textHeader) as TextView).text = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.ProductTitle
			}
			20 -> {
				(findViewById<View>(R.id.textHeader) as TextView).text = ProductCatSelectionDataEnum.GENERATE_QR.data.ProductTitle
			}
			30 -> {
				(findViewById<View>(R.id.textHeader) as TextView).text = ProductCatSelectionDataEnum.EPP.data.ProductTitle
			}
			40 -> {
				(findViewById<View>(R.id.textHeader) as TextView).text = ProductCatSelectionDataEnum.BNPL.data.ProductTitle
			}
		}

		val buttonScanQR = findViewById<LinearLayout>(R.id.button_qr_scan)
		val buttonGenQR = findViewById<LinearLayout>(R.id.button_gen_qr)
		helperLog.appendLine(helperLogClassName, "amount checking :: $amount")
		if(amount != null){
			helperLog.appendLine(helperLogClassName, "running of sale checking")
			salesNavigation(amount)
		} else {
			helperLog.appendLine(helperLogClassName, "running of keypad function")
			helperLog.appendLine(helperLogClassName, "isUpiQr :: $isUPIQR")
			if (isUPIQR) {
				val keypad = findViewById<Keypad>(R.id.keypad1)
				keypad.visibility = View.GONE

				val btnskeypad = findViewById<LinearLayout>(R.id.btnskeypad)
				btnskeypad.visibility = View.VISIBLE
				val btnsUPI = findViewById<LinearLayout>(R.id.btnsUPI)
				btnsUPI.visibility = View.VISIBLE

				val keypad2 = findViewById<KeypadUPI>(R.id.keypad2)
				textView_amount = findViewById<TextView>(R.id.textViewAmount)
				keypad2.setFilter(textView_amount, true, 12)

				val qrGenProdDetail = getSpecificQrProductItem(ArrayList(listOf("Product", "QrProductCode")), arrayOf("GENERATE_QR", "QR_UNIONPAY"))
				helperLog.appendLine(helperLogClassName, "QrGenerateProduct :: ${Gson().toJson(qrGenProdDetail)}")
				if (qrGenProdDetail != null) {
					buttonGenQR.isClickable = true
					buttonGenQR.background = AppCompatResources.getDrawable(applicationContext, R.drawable.sales_selection_background_darkblue)
					buttonGenQR.setOnClickListener {
						val (_, _, SalesType) = ProductCatSelectionDataEnum.valueOf("GENERATE_QR").data
						val salesModel = SalesModel(
								SalesType,
								qrGenProdDetail.Product,
								qrGenProdDetail.AcqCode,
								qrGenProdDetail.AcqMid,
								qrGenProdDetail.AcqTid,
								qrGenProdDetail.QrProductCode,
								qrGenProdDetail.ProductName,
								qrGenProdDetail.EppProductCode,
								qrGenProdDetail.EppTenure,
								qrGenProdDetail.EppTenureCode
						)
						selectedSales = salesModel
						ServiceHolder.selectedCacheModel = salesModel
						sales()
					}
				}

				val qrScanProdDetail = getSpecificQrProductItem(ArrayList(listOf("Product")), arrayOf("EWALLET_MERCHANT_SCANS"))
				helperLog.appendLine(helperLogClassName, "QrScanProduct :: ${Gson().toJson(qrScanProdDetail)}")
				if (qrScanProdDetail != null) {
					buttonScanQR.isClickable = true
					buttonScanQR.background = AppCompatResources.getDrawable(applicationContext, R.drawable.sales_selection_background)
					buttonScanQR.setOnClickListener {
						val (_, _, SalesType) = ProductCatSelectionDataEnum.valueOf("EWALLET_MERCHANT_SCANS").data
						val salesModel = SalesModel(
								SalesType,
								qrScanProdDetail.Product,
								qrScanProdDetail.AcqCode,
								qrScanProdDetail.AcqMid,
								qrScanProdDetail.AcqTid,
								qrScanProdDetail.QrProductCode,
								qrScanProdDetail.ProductName,
								qrScanProdDetail.EppProductCode,
								qrScanProdDetail.EppTenure,
								qrScanProdDetail.EppTenureCode
						)
						selectedSales = salesModel
						ServiceHolder.selectedCacheModel = salesModel
						sales()
					}
				}
			} else {
				val keypad = findViewById<Keypad>(R.id.keypad1)
				textView_amount = findViewById<TextView>(R.id.textViewAmount)
				keypad.setFilter(textView_amount, true, 12)
				keypad.registerOnOKEventListener(mListener)

				val keypad2 = findViewById<KeypadUPI>(R.id.keypad2)
				keypad2.visibility = View.GONE
				val btnUPI = findViewById<LinearLayout>(R.id.btnsUPI)
				btnUPI.visibility = View.GONE
			}
		}
	}

	private fun getSpecificQrProductItem(fieldList: ArrayList<String>, valueList: Array<String>): DbModelProductList? {
		val specificProdList = ProductListRepo.getSelectedProductEnhanced(applicationContext, fieldList, valueList)
		var productItem: DbModelProductList? = null
		if (specificProdList.isNotEmpty()) {
			productItem = specificProdList[0]
		}
		return productItem
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun sales() {
		helperLog.appendLine(helperLogClassName, "Sales Amount Checking")
		val txnAmount = textView_amount!!.text.toString().replace("RM ".toRegex(), "")
		if (txnAmount == "0.00") {
			Toast.makeText(applicationContext, "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show()
			helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}
		val ss = BigDecimal(txnAmount)
		val ss1 = BigDecimal("999999.99")
		if (ss > ss1) {
			Toast.makeText(applicationContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
			helperLog.appendLine(helperLogClassName, "REJECT :: amount must be less than 999999.99")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
		helperLog.appendLine(helperLogClassName, "Validation passed :: checking last reversal")

		runLastReversal(txnAmount)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun runLastReversal(txnAmount: String) {
		thread(start = true) {
			val threadClassName = this@KeypadActivity.javaClass.simpleName
			val helperLog = HelperLog(
				getSession(),
				checkIsConnectedWifi(applicationContext),
				Utils.getIPAddress(),
				"Sales Last Reversal",
				threadClassName,
				threadClassName
			)
			var reversalResult = true
			val lastPostingDt = IsoBatchLongInfoRepo.getBatchLongInfo(applicationContext, "postingDt", "last")

			helperLog.appendLine(threadClassName, "Last PostingDT :: ${lastPostingDt?.value}")
			if(lastPostingDt != null && lastPostingDt.value.isNotEmpty()) {
				helperLog.appendLine(threadClassName, "Last Reversal Before Sales Trigger")
				var postingDtData = lastPostingDt.value
				startProgressDialog(this@KeypadActivity, "", "Running Last Reversal")

				val cardProduct = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
				val batchNo = IsoBatchInfoRepo.getBatchInfo(applicationContext, "batchNo", "visam")?.value ?: ""
				val reversalBatch = ReversalBatchTableRepo.getBatchData(applicationContext, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, cardProduct.AcqMid, cardProduct.AcqTid))
				if(reversalBatch.isNotEmpty()) {
					val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
					val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(cardProduct.AcqCode, "reversal")
					if(acquirerRevIsoModel != null) {
						for (tempFor in reversalBatch) {
							if(tempFor.postingDt < postingDtData) {
								helperLog.appendLine(threadClassName, "Skip Current postingDT :: ${tempFor.postingDt}")
								continue
							}

							helperLog.appendLine(threadClassName, "Reversal Batch Model :: (${Gson().toJson(tempFor)})")
							TransData.reset(applicationContext)

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
							val revResult = IsoActivity.processReversal(applicationContext, false, acquirerRevIsoModel, tempFor.batchData, false, helperLog)
							if(reversalResult && revResult == null){
								reversalResult = false
								postingDtData = tempFor.postingDt
							}
						}

						if(reversalResult) {
							postingDtData = ""
						}
						helperLog.appendLine(threadClassName, "Final postingDT :: $postingDtData")
						IsoBatchLongInfoRepo.updateBatchLongInfo(applicationContext, postingDtData, "postingDt", "last")
					}
				}
				Thread.sleep(500)
				closeProgressDialog()
			}

			helperLog.logToFile(EnumLogFileName.TerminaLog)
			salesNavigation(txnAmount)
		}
	}

	fun salesNavigation(txnAmount: String) {
		val terminalConfig = getTerminalConfig()
		val cls: Class<*> = when (selectedSales!!.SalesType) {
			ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType -> {
				if (getBooleanValue(terminalConfig, "QrPay")) {
					QrScanActivity::class.java
				} else CardPaymentActivity::class.java
			}
			ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType -> {
				if (getBooleanValue(terminalConfig, "QrPay")) {
					GenerateQrActivity::class.java
				} else CardPaymentActivity::class.java
			}
			ProductCatSelectionDataEnum.EPP.data.SalesType -> EppProductActivity::class.java
			ProductCatSelectionDataEnum.BNPL.data.SalesType -> BnplScanActivity::class.java
			ProductCatSelectionDataEnum.CASH_OUT.data.SalesType -> KeypadActivityCashOut::class.java
			else -> CardPaymentActivity::class.java
		}

		helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${cls.simpleName}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val newIntent = Intent(applicationContext, cls)
		newIntent.putExtra("txnAmt", txnAmount)
		newIntent.putExtra("typeofSale", selectedSales!!.SalesType)
		/*intent.putExtra("tenure", tenure)
		intent.putExtra("tenureDesc", tenureDesc)*/
		/*intent.putExtra("packageCode", packageCode)
		intent.putExtra("paymentType", paymentType)*/
		newIntent.putExtra("posReference", posReference)

		if (isUPIQR) {
			newIntent.putExtra("isUPIQR", true)
		}

		newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(newIntent)
	}

	private fun preauth() {
		helperLog.appendLine(helperLogClassName, "Pre-Auth Checking")
		val txnAmount = textView_amount!!.text.toString().replace("RM ".toRegex(), "")
		if (txnAmount == "0.00") {
			Toast.makeText(applicationContext, "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show()
			helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}
		val ss = BigDecimal(txnAmount)
		val ss1 = BigDecimal("999999.99")
		if (ss > ss1) {
			Toast.makeText(applicationContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
			helperLog.appendLine(helperLogClassName, "REJECT :: amount must be less than 999999.99")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val cls: Class<*> = CardPaymentActivity::class.java
		val intent = Intent(applicationContext, cls)
		intent.putExtra("typeofSale", typeOfSale)
		intent.putExtra("txnAmt", txnAmount)
		startActivity(intent)
	}

	private fun saleCom() {
		helperLog.appendLine(helperLogClassName, "Sale Complete Checking")
		val txnAmount = textView_amount!!.text.toString().replace("RM ".toRegex(), "")
//		if (txnAmout == "0.00") {
//			Toast.makeText( applicationContext, "Trade amount should be greater than 0", Toast.LENGTH_SHORT ).show()
//			return
//		}
		val ss = BigDecimal(txnAmount)
		val ss1 = BigDecimal("999999.99")
		if (ss > ss1) {
			Toast.makeText(applicationContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
			helperLog.appendLine(helperLogClassName, "REJECT :: amount must be less than 999999.99")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivitySaleCom")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val cls: Class<*> = KeypadActivitySaleCom::class.java
		val intent = Intent(applicationContext, cls)
		intent.putExtra("typeofSale", typeOfSale)
		intent.putExtra("txnAmt", txnAmount)
		startActivity(intent)
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving keypad")
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		selectedCacheModel = null
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	private val mListener = onKeypadEventListener { isOK, msg ->
		if (isOK) {
			if (msg == null) {
				customOnBackPress()
			} else {
				if (typeOfSale == 8) {
					preauth()
				} else if (typeOfSale == 4) {
					saleCom()
				} else {
					sales()
				}
			}
		}
	}
}