package com.sc.mf919.kotlin.activity
import enums.EnumResponseCode

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.database.repo.BnplPayTableRepo
import com.sc.mf919.kotlin.database.repo.PrintReceiptBnplRepo
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.autoSettlementIsRunning
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.txnType
import helpers.HelperCommon
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SettlementBnplActivity : ActivityBase() {
	lateinit var mContext: Context
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom

	private var bnplPayBrand = arrayOf<String?>(
		"IOUPay",
		"Atome"
	)
	private var bnplPayTotalTxnAmt = arrayOf<String?>()
	private var bnplPayTotalTxnCount = arrayOf<String?>()
	private var settleInfo = arrayOf<String?>()
	lateinit var mainReceipt: LinearLayout
	lateinit var headerText: TextView

	//private var totalA: Long = 0
	//private var totalC: Long = 0
	private var SaleA: Long = 0
	private var SaleC: Long = 0
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null

	private var id = 0
	private var previewSettlement = 0

	var txnDt = ""
	var rrn = ""
	var stan = ""
	var mid = ""
	var tid = ""
	var batchNo = ""

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settlement_bnpl)
		mContext = this
		mainReceipt = findViewById(R.id.mainLayout)
		headerText = findViewById(R.id.textHeader)

		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"BNPL Settlement Activity"
		)
		helperLog.appendLine(helperLogClassName, "BNPL Settlement screen opened")

		id = intent.getIntExtra("Last", 0)
		previewSettlement = intent.getIntExtra("previewSettlement", 0)

		val toolbar = findViewById<Toolbar>(R.id.toolbarSettlement)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this@SettlementBnplActivity, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		headerText.text = if (id != 0) {
			"Last Settlement BNPL"
		} else if (previewSettlement == 1) {
			"Preview Settlement BNPL"
		} else {
			"Settlement BNPL"
		}
		helperLog.appendLine(helperLogClassName, "Mode :: ${headerText.text} (previewSettlement=$previewSettlement, isLastSettlement=${id != 0})")

		lifecycleScope.launch {
			getRecordCoroutine()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun getRecordCoroutine() {
		helperLog.appendLine(helperLogClassName, "-----------------Load BNPL Settlement Record [START]-------------------->")
		var settlementInfo: Array<String?> = arrayOf()

		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			dbModelMerchantConfig = getMerchantInfo()
			acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)

			if (id == 0) {
				bnplPayTotalTxnAmt = arrayOfNulls(bnplPayBrand.size)
				bnplPayTotalTxnCount = arrayOfNulls(bnplPayBrand.size)

				for (i in bnplPayBrand.indices) {
					SaleA += getTxnTotalAmount(bnplPayBrand[i], "Sale").toLong()
					SaleC += getTxnTotalCount(bnplPayBrand[i], "Sale")

					bnplPayTotalTxnCount[i] = getTxnTotalCount(bnplPayBrand[i], "Sale").toString()
					bnplPayTotalTxnAmt[i] = getTxnTotalAmount(bnplPayBrand[i], "Sale")
				}

				txnDt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(
					Calendar.getInstance().time
				)
				settlementInfo = arrayOf(txnDt, dbModelMerchantConfig?.QrMid ?: "", "")
			} else {
				(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.GONE
				(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.VISIBLE
				settleInfo = Utils.readFromFile("bnpllastsettlement.txt")

				settlementInfo = arrayOfNulls<String>(3)
				val bnplDetailsLen = (settleInfo.size - settlementInfo.size) / 3
				if (bnplDetailsLen > 0) {
					System.arraycopy(settleInfo, 0, settlementInfo, 0, settlementInfo.size)
				} else {
					System.arraycopy(settleInfo, 0, settlementInfo, 0, settleInfo.size)
				}
				bnplPayBrand = arrayOfNulls(bnplDetailsLen)
				bnplPayTotalTxnAmt = arrayOfNulls(bnplDetailsLen)
				bnplPayTotalTxnCount = arrayOfNulls(bnplDetailsLen)
				var count = 0
				var tCount = 0
				for (s in settleInfo) {
					when (count) {
						3 -> bnplPayBrand[tCount] = s
						4 -> bnplPayTotalTxnCount[tCount] = s
						5 -> bnplPayTotalTxnAmt[tCount] = s
					}
					count++
					if (count > 5) {
						count = 3
						tCount++
					}
				}
			}
			helperLog.appendLine(helperLogClassName, "BNPL Totals :: mid=${dbModelMerchantConfig?.QrMid ?: ""} tid=${dbModelMerchantConfig?.QrTid ?: ""} brands=${bnplPayBrand.size} saleCount=$SaleC saleTotal=$SaleA")
			helperLog.appendLine(helperLogClassName, "-----------------Load BNPL Settlement Record [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		var loadingTitle = "BNPL Settlement"
		if (previewSettlement == 1) {
			loadingTitle = "Preview Summary"
		}

		startProgressDialog(mContext, loadingTitle, "Loading...")
		startCoroutine.join()
		formLayout(mainReceipt, settlementInfo)
		closeProgressDialog()

		if (previewSettlement == 0 && (appIntent || appHTTP)) {
			val txn_map = HashMap<String, String>()
			val jObject = JSONObject()
			if (!autoSettlementIsRunning) {
				helperLog.appendLine(helperLogClassName, "-----------------BNPL Settlement Commit [START]-------------------->")
				Utils.write2File(settleInfo, "bnpllastsettlement.txt")
				printReceipt()
				BnplPayTableRepo.deleteAll(applicationContext)
				PrintReceiptBnplRepo.deleteAll(applicationContext)
				helperLog.appendLine(helperLogClassName, "BNPL batch settled and cleared :: ResponseCode :: [00] saleCount=$SaleC saleTotal=${Utils.getActualAmount(SaleA.toString())}")
				helperLog.appendLine(helperLogClassName, "-----------------BNPL Settlement Commit [END]-------------------->")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				txn_map["ResponseCode"] = "00"
				txn_map["ResponseDescription"] = "Settled"
				txn_map["TransactionType"] = txnType.toString()
				txn_map["SettlementAmount"] = Utils.getActualAmount((SaleA).toString())
				txn_map["SettlementCount"] = (SaleC).toString()
				txn_map["SettlementSaleAmount"] = Utils.getActualAmount(SaleA.toString())
				txn_map["SettlementSaleCount"] = SaleC.toString()
				txn_map["SettlementMID"] = (dbModelMerchantConfig?.QrMid ?: "").toString()
				txn_map["SettlementTID"] = (dbModelMerchantConfig?.QrTid ?: "").toString()
				txn_map["SettlementDateTime"] = txnDt
				try {
					jObject.put("ResponseCode", "00")
					jObject.put("ResponseDescription", "Settled")
					jObject.put("TransactionType", txnType.toString())
					jObject.put(
						"SettlementAmount", Utils.getActualAmount((SaleA).toString())
					)
					jObject.put("SettlementCount", (SaleC).toString())
					jObject.put("SettlementSaleAmount", Utils.getActualAmount(SaleA.toString()))
					jObject.put("SettlementSaleCount", SaleC.toString())
					jObject.put("SettlementMID", dbModelMerchantConfig?.QrMid ?: "")
					jObject.put("SettlementTID", dbModelMerchantConfig?.QrTid ?: "")
					jObject.put("SettlementDateTime", txnDt)
				} catch (e: JSONException) {
					e.printStackTrace()
				}
			} else {
				helperLog.appendLine(helperLogClassName, "REJECT :: auto settlement already running, ResponseCode :: [SHC002]")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				withContext(Dispatchers.Main) {
					Toast.makeText(mContext, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
				}
				txn_map["ResponseCode"] = EnumResponseCode.AUTO_SETTLEMENT_RUNNING.code
				txn_map["ResponseDescription"] = EnumResponseCode.AUTO_SETTLEMENT_RUNNING.description
				txn_map["TransactionType"] = txnType.toString()
				try {
					jObject.put("ResponseCode", EnumResponseCode.AUTO_SETTLEMENT_RUNNING.code)
					jObject.put("ResponseDescription", EnumResponseCode.AUTO_SETTLEMENT_RUNNING.description)
					jObject.put("TransactionType", txnType.toString())
				} catch (e: JSONException) {
					e.printStackTrace()
				}
			}
			if (appIntent) {
				onBackToApp(txn_map)
			} else {
				onBackToHTTP(jObject.toString())
			}
		}
	}

	@SuppressLint("SetTextI18n")
	private fun formLayout(mainReceipt: LinearLayout, value: Array<String?>) {
		mainReceipt.removeAllViews()
		var formTitle = "SETTLEMENT REPORT"
		if (previewSettlement == 1) {
			formTitle = "SALES SUMMARY"
		}

		val tags = arrayOf("Date/Time", "MERCHANT CODE", formTitle)
		val name = TextView(applicationContext)
		name.textSize = 9f
		name.textAlignment = View.TEXT_ALIGNMENT_CENTER
		name.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
		name.text = dbModelMerchantConfig?.MerchantName ?: ""

		val addr = TextView(applicationContext)
		addr.textSize = 9f
		addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
		addr.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
		addr.text = dbModelMerchantConfig?.MerchantAddress ?: ""

		mainReceipt.addView(name)
		mainReceipt.addView(addr)

		for (j in tags.indices) {
			if (j == 2) {
				val ll = LinearLayout(applicationContext)
				ll.orientation = LinearLayout.HORIZONTAL
				val params1 = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				params1.setMargins(5, 0, 5, 0)
				val tv = TextView(applicationContext)
				tv.layoutParams = params1
				tv.textSize = 14f
				tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
				tv.setTypeface(tv.typeface, Typeface.BOLD)
				tv.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
				tv.text = tags[j]
				ll.addView(tv)
				mainReceipt.addView(ll)
			} else {
				val ll = LinearLayout(applicationContext)
				ll.orientation = LinearLayout.HORIZONTAL
				ll.weightSum = 2f
				val params1 = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				params1.setMargins(5, 0, 5, 0)
				params1.weight = 1f
				val tv = TextView(applicationContext)
				tv.layoutParams = params1
				tv.textSize = 11f
				tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
				tv.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
				tv.text = tags[j]
				val tv1 = TextView(applicationContext)
				tv1.layoutParams = params1
				tv1.textSize = 11f
				tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
				tv1.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
				tv1.text = value[j]
				ll.addView(tv)
				ll.addView(tv1)
				mainReceipt.addView(ll)
			}
		}

		val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL")
		settleInfo = arrayOfNulls(value.size + bnplPayBrand.size * tags2.size)
		System.arraycopy(value, 0, settleInfo, 0, value.size)
		var count = value.size
		bnplBrand@ for (j in bnplPayBrand.indices) {
			for (i in tags2.indices) {
				var res: String? = ""
				if (i == 0 && bnplPayTotalTxnCount[j] == "0") {
					continue@bnplBrand
				}

				when (i) {
					0 -> res = bnplPayBrand[j]
					1 -> res = bnplPayTotalTxnCount[j]
					2 -> {
						if (bnplPayTotalTxnAmt[j] == null) {
							bnplPayTotalTxnAmt[j] = "000"
						}
						res = if (id == 1) { bnplPayTotalTxnAmt[j] }
						else Utils.getActualAmount(bnplPayTotalTxnAmt[j])
					}
				}
				if (res == null) {
					res = "0"
				}
				settleInfo[count] = res
				count++

				val ll = LinearLayout(applicationContext)
				ll.orientation = LinearLayout.HORIZONTAL
				ll.weightSum = 2f
				val params1 = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				params1.setMargins(5, 0, 5, 0)
				params1.weight = 1f
				val tv = TextView(applicationContext)
				tv.layoutParams = params1
				tv.textSize = 11f
				tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
				tv.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
				tv.text = tags2[i]
				val tv1 = TextView(applicationContext)
				tv1.layoutParams = params1
				tv1.textSize = 11f
				tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
				tv1.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
				tv1.text = res
				ll.addView(tv)
				ll.addView(tv1)
				mainReceipt.addView(ll)
			}
		}
		settleInfo = settleInfo.filterNotNull().toTypedArray()

		val footer = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params.setMargins(0, 5, 0, 0)
		params.gravity = Gravity.CENTER
		footer.orientation = LinearLayout.VERTICAL
		footer.layoutParams = params
		footer.setPadding(0, 0, 0, 20)
		val power = TextView(applicationContext)
		power.textSize = 9f
		power.textAlignment = View.TEXT_ALIGNMENT_CENTER
		power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
		power.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
		if(!isHideBottom) {
			power.text = "POWERED BY"
			footer.addView(power)
			val im = ImageView(applicationContext)
			val params1 = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
			)
			im.layoutParams = params1
			im.adjustViewBounds = true
			im.setImageResource(R.mipmap.logo)
			val paths = getInternalFilesPaths() + "powerLogo.png"
			val imgFile = File(paths)
			if (imgFile.exists()) {
				val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
				im.setImageBitmap(myBitmap)
				helperLog.appendLine(helperLogClassName, "Custom power-by logo loaded")
			}
			footer.addView(im)
		}
		mainReceipt.addView(footer)
	}

	private fun getTxnTotalCount(payBrand: String?, txnType: String): Int {
		var iTotalCount = PrintReceiptBnplRepo.countByPayBrandDescAndTxnType(applicationContext, payBrand ?: "", txnType)
		if (iTotalCount < 0) {
			sysPrint("Failed to get totalTxnCount for $payBrand and $txnType")
			iTotalCount = 0
		}
		return iTotalCount
	}

	private fun getTxnTotalAmount(payBrand: String?, txnType: String): String {
		var strTotalTxnAmt = PrintReceiptBnplRepo.sumTxnAmtByPayBrandDescAndTxnType(applicationContext, payBrand ?: "", txnType)
		if (strTotalTxnAmt == null) {
			sysPrint("Failed to get totalTxnAmt for $payBrand and $txnType")
			strTotalTxnAmt = "000"
		}
		return strTotalTxnAmt
	}

	private fun printInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE
		var formTitle = "SETTLEMENT REPORT"
		if (previewSettlement == 1) {
			formTitle = "SALES SUMMARY"
		}

		val tags = arrayOf("Date/Time", "MERCHANT CODE", formTitle)
		val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL")
		var entity = MulPrintStrEntity("", fontSize)
		entity.marginX = 50
		entity.gravity = Gravity.CENTER
		entity.isUnderline = true
		entity.yspace = 30
		list.add(entity)
		var mulPrintStrEntity = MulPrintStrEntity(
			dbModelMerchantConfig?.MerchantName ?: "", FontFamily.SMALL, false, Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		mulPrintStrEntity = MulPrintStrEntity(
			dbModelMerchantConfig?.MerchantAddress ?: "", FontFamily.SMALL, false, Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

		for (j in tags.indices) {
			if (j == 2) {
				list += MulPrintStrEntity(tags[j], FontFamily.BIG, false, Gravity.CENTER).setIsBold(
					Typeface.BOLD
				)
			} else {
				list += MulPrintStrEntity(
					Utils.makeLineText(
						Utils.TextItem(tags[j]).setFont(fontSize),
						Utils.TextItem(settleInfo[j]).setFont(fontSize)
							.setPaddingAlign(Gravity.RIGHT)
					), fontSize
				)
			}
		}

		var count = 0
		for (j in tags.size until settleInfo.size) {
			list += MulPrintStrEntity(
				Utils.makeLineText(
					Utils.TextItem(tags2[count]).setFont(fontSize),
					Utils.TextItem(settleInfo[j]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)

			count++
			if (count > 2) count = 0
		}

		if(!isHideBottom) {
			list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
			entity = MulPrintStrEntity("", fontSize)
			var imageFromAssetsFile =
				PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
			val paths = getInternalFilesPaths() + "powerBWLogo.png"
			val imgFile = File(paths)
			if (imgFile.exists()) {
				imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
			}
			entity.bitmap = imageFromAssetsFile
			entity.marginX = 50
			entity.gravity = Gravity.CENTER
			entity.isUnderline = true
			entity.yspace = 30
			list.add(entity)
		}
		entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun printReceipt() {
		val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
		//mainReceipt.startAnimation(animSlideDown)
		val scrollReceipt : ScrollView = findViewById(R.id.scrollReceipt)
		scrollReceipt.startAnimation(animSlideDown)

		printInfo()
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving BNPL settlement screen")
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	fun settle_btn_cancel(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: CANCEL [SETTLEMENT BNPL]")
		helperLog.appendLine(helperLogClassName, "User Cancel :: BNPL settlement abandoned")
		customOnBackPress()
	}

	fun settle_btn_ok(view: View?) {
		if (id == 0 && previewSettlement == 0) {
			helperLog.appendLine(helperLogClassName, "Selected :: SETTLE [SETTLEMENT BNPL]")
			helperLog.appendLine(helperLogClassName, "-----------------BNPL Settlement Commit [START]-------------------->")
			Utils.write2File(settleInfo, "bnpllastsettlement.txt")
			printReceipt()
			BnplPayTableRepo.deleteAll(applicationContext)
			PrintReceiptBnplRepo.deleteAll(applicationContext)
			helperLog.appendLine(helperLogClassName, "BNPL batch settled and cleared :: mid=${dbModelMerchantConfig?.QrMid ?: ""} saleCount=$SaleC saleTotal=${Utils.getActualAmount(SaleA.toString())}")
			helperLog.appendLine(helperLogClassName, "-----------------BNPL Settlement Commit [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		} else {
			helperLog.appendLine(helperLogClassName, "Selected :: PRINT [SETTLEMENT BNPL]")
			printReceipt()
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	private fun onBackToApp(txn_map: HashMap<String, String>) {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		val intent = Intent(applicationContext, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txn_map", txn_map)
		startActivity(intent)
		finish()
	}

	private fun onBackToHTTP(msg: String) {
		HTTPServer.getInstance().setResponseMessage(msg)
		appHTTP = false
		customOnBackPress()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "SettlementBnplActivity OnDestroy :: BNPL settlement screen closed")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	companion object {
		private const val TAG = "ISODB"
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}
	}
}
