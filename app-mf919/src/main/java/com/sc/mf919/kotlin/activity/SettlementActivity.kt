package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.constraintlayout.helper.widget.Layer
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.java.activity.Utils.TextItem
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelLastSettlement
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919.kotlin.database.repo.LastSettlementRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919.kotlin.helper_common.*
import com.sc.mf919.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.autoSettlementIsRunning
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.txnType
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class SettlementActivity : BaseActivity() {
	lateinit var mContext: Context
	var helperLogClassName:String = ""
	lateinit var helperLog: HelperLog

	lateinit var mainReceipt: LinearLayout
	lateinit var headerText: TextView
	lateinit var scrollReceipt: ScrollView
	lateinit var settleInfo: MutableList<Pair<String, String>>
	lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
	lateinit var acquirerSettingModel: AcquirerSettingModel

	private var previewSettlement = 0
	private var settledSettlement = false
	private var isLastSettlement = false
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom

	var txnMapList = ArrayList<HashMap<String, String?>>()
	var txnMap = HashMap<String, String?>()
	var jsonArray = JSONArray()

	var txnDt: String = ""
	var rrn: String = ""
	var stan: String = ""
	var mid: String = ""
	var tid: String = ""
	var batchNo: String = ""

	var totalSettlementCount = 0
	var currentProductIndex = 0
	var settlementProduct: List<DbModelProductList> = arrayListOf()
	var settlementSummaryData: List<DbModelSettlementSummary> = arrayListOf()
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
	var dbModelTerminalConfig: DbModelTerminalConfig? = null
	var settledProduct = HashMap<DbModelProductList, List<DbModelSettlementSummary>>()
	var lastSettlementList: List<DbModelLastSettlement> = arrayListOf()
	private var loadingLoop = false
	var isTpaAccount = false
	var runningBlock = 0

	companion object {
		private const val TAG = "Settlement"

		fun constructSettlementValueString(txnCount: String, txnTotal: String, refundCount: String, refundTotal: String, tcCount: String): String {
			val iTxnCount = Utils.atoi(txnCount)
			val iTxnTotal = Utils.atoi(txnTotal)
			val iRefundCount = Utils.atoi(refundCount)
			val iRefundTotal = Utils.atoi(refundTotal)
			val iTcCount = Utils.atoi(tcCount)
			return String.format(Locale.ENGLISH, "%03d", iTxnCount) + String.format(Locale.ENGLISH, "%012d", iTxnTotal) + String.format(Locale.ENGLISH, "%03d", iRefundCount) + String.format(Locale.ENGLISH, "%012d", iRefundTotal) + "000" + "000000000000" + String.format(Locale.ENGLISH, "%03d", iTcCount)
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settlement)
		HTTPServer.getInstance().attendActivityContext = this@SettlementActivity
		val toolbar = findViewById<Toolbar>(R.id.toolbarSettlement)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		mContext = this@SettlementActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Settle Option Activity"
		)
		helperLog.appendLine(helperLogClassName, "Settlement screen opened")
		acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about))
		runningBlock = 0

		headerText = findViewById(R.id.textHeader)
		mainReceipt = findViewById(R.id.mainLayout)
		scrollReceipt = findViewById(R.id.scrollReceipt)
		settleInfo = mutableListOf()
		previewSettlement = intent.getIntExtra("previewSettlement", 0)
		isLastSettlement = (intent.getIntExtra("Last", 0) != 0)
		acquirerSettingModel = ServiceHolder.getAcquirerSetting()

		headerText.text = if (isLastSettlement) {
			"Last Settlement"
		} else if (previewSettlement == 1) {
			"Preview Settlement"
		} else {
			"Settlement"
		}
		helperLog.appendLine(helperLogClassName, "Mode :: ${headerText.text} (previewSettlement=$previewSettlement, isLastSettlement=$isLastSettlement)")

		//TODO Dynamic Layout For Small Terminal
		val displayMetrics = resources.displayMetrics
		val screenHeightPx = displayMetrics.heightPixels
		val screenHeightDp = screenHeightPx / displayMetrics.density
		println("screenHeightDp :: $screenHeightDp")

		if (screenHeightDp < 500) {
			val params = scrollReceipt.layoutParams
			params.height = Helper.getInstance().dpToPx(250)
			scrollReceipt.layoutParams = params
		}
		//TODO Dynamic Layout For Small Terminal

		lifecycleScope.launch {
			getRecordCoroutine()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun getRecordCoroutine() {
		helperLog.appendLine(helperLogClassName, "-----------------Load Settlement Record [START]-------------------->")
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {

			dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
			dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			batchNo = IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", "visam")?.value ?: ""
			helperLog.appendLine(helperLogClassName, "Batch No :: $batchNo")
			acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
				AcquirerLogoDataEnum.from(dbModelMerchantConfig?.AcqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
			)

			// Check Is Last Settlement
			if (isLastSettlement) {
				(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.GONE
				(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.VISIBLE
				lastSettlementList = LastSettlementRepo.getAll(ServiceHolder.getContext())
				helperLog.appendLine(helperLogClassName, "Last Settlement records :: ${lastSettlementList.size}")
				if (lastSettlementList.isNotEmpty()) {
					currentProductIndex = 0

					if(lastSettlementList.size > 1){
						runOnUiThread {
							(findViewById<View>(R.id.previousBtnLastSettlement)).visibility = View.VISIBLE
							(findViewById<View>(R.id.nextBtnLastSettlement)).visibility = View.VISIBLE
						}
					}

					val tempObj = lastSettlementList[currentProductIndex]
					isTpaAccount = tempObj.isTpaAccount.lowercase() == "true"
					tempObj.settleInfo.let { fileData ->
						val lastSettleString = fileData.split("|").map {
							it.split(",").let { pairItem ->
								Pair(pairItem[0].substring(1), pairItem.getOrNull(1)?.dropLast(1) ?: "")
							}
						}
						settleInfo = lastSettleString as MutableList<Pair<String, String>>
					}
				} else {
					helperLog.appendLine(helperLogClassName, "REJECT :: last settlement record not found")
					runOnUiThread{
						Toast.makeText(applicationContext, "Last Settlement Not Found.", Toast.LENGTH_SHORT).show()
					}
				}
			} else {
				if (previewSettlement == 1) {
					(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.GONE
					(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.VISIBLE
				} else {
					runOnUiThread {
						(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.VISIBLE
						(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
					}
				}

				settlementProduct = ProductListRepo.getUnSettledProduct(applicationContext)
				helperLog.appendLine(helperLogClassName, "Unsettled products :: ${settlementProduct.size}")
				if(settlementProduct.isNotEmpty()){
					currentProductIndex = 0
					val currentProduct = settlementProduct[0]
					isTpaAccount = currentProduct.IsTpaAccount?.lowercase() == "true"
					helperLog.appendLine(helperLogClassName, "Current product :: acq=${currentProduct.AcqCode} mid=${currentProduct.AcqMid} tid=${currentProduct.AcqTid} isTpa=$isTpaAccount")

					if(settlementProduct.size > 1){
						runOnUiThread {
							(findViewById<View>(R.id.previousBtn)).visibility = View.VISIBLE
							(findViewById<View>(R.id.nextBtn)).visibility = View.VISIBLE
						}
					}

					settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
						applicationContext,
						listOf("acq_code", "mid", "tid"),
						arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
					)
					settleInfo = withContext(Dispatchers.Default) {
						constructPrintRecord(currentProduct, settlementSummaryData)
					}
				}
			}

			helperLog.appendLine(helperLogClassName, "-----------------Load Settlement Record [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		previewSettlement = intent.getIntExtra("previewSettlement", 0)
		var loadingTitle = "Settlement"
		if (previewSettlement == 1) {
			loadingTitle = "Preview Summary"
		}

		startProgressDialog(mContext, loadingTitle, "Loading...")
		startCoroutine.join()
		formLayout(mainReceipt, settleInfo)
		closeProgressDialog()

		if (previewSettlement == 0 && (appIntent || appHTTP)) {
			if (!autoSettlementIsRunning && runningBlock == 0) {
				ServiceHolder.appRunningProcess = true
				helperLog.appendLine(helperLogClassName, "Validation passed :: auto-triggered settlement starting (appIntent=$appIntent, appHTTP=$appHTTP)")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				Trans().execute()
			} else {
				val txnMap = HashMap<String, String?>()
				val jsonObject = JSONObject()
				helperLog.appendLine(helperLogClassName, "REJECT :: auto settlement already running, ResponseCode :: [SHC002]")
				Toast.makeText(applicationContext, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
				txnMap["ResponseCode"] = "SHC002"
				txnMap["ResponseDescription"] = "Auto Settlement is running"
				txnMap["TransactionType"] = txnType.toString()
				txnMapList.add(txnMap)
				try {
					jsonObject.put("ResponseCode", "SHC002")
					jsonObject.put("ResponseDescription", "Auto Settlement is running")
					jsonObject.put("TransactionType", txnType.toString())
				} catch (e: JSONException) {
					e.printStackTrace()
				}

				helperLog.logToFile(EnumLogFileName.TerminaLog)
				if (appIntent) {
					onBackToApp(txnMapList)
				} else {
					onBackToHTTP(jsonObject.toString())
				}
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun switchRecordCoroutine() {
		/*val log = helpers.HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"SettlementActivity - switchRecordCoroutine",
			SettlementActivity::class.java.simpleName,
			SettlementActivity::class.java.simpleName,
		)*/

		helperLog.appendLine(helperLogClassName, "-----------------Switch Settlement Record [START]-------------------->")
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			val currentProduct = settlementProduct[currentProductIndex]
			helperLog.appendLine(helperLogClassName, "Switch to product [$currentProductIndex] :: acq=${currentProduct.AcqCode} mid=${currentProduct.AcqMid} tid=${currentProduct.AcqTid}")

			settlementSummaryData = checkProductStatus(currentProduct) ?: run{
				SettlementSummaryRepo.getSelectiveData(
					applicationContext,
					listOf("acq_code", "mid", "tid"),
					arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
				)
			}
			settleInfo = withContext(Dispatchers.Default) {
				constructPrintRecord(currentProduct, settlementSummaryData)
			}
			helperLog.appendLine(helperLogClassName, "-----------------Switch Settlement Record [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		startProgressDialog(mContext, "Settlement", "Loading...")
		startCoroutine.join()
		formLayout(mainReceipt, settleInfo)
		closeProgressDialog()
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun switchLastSettlementCoroutine() {
		/*val log = helpers.HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"SettlementActivity - switchLastSettlementCoroutine",
			SettlementActivity::class.java.simpleName,
			SettlementActivity::class.java.simpleName,
		)*/

		helperLog.appendLine(helperLogClassName, "-----------------Switch Last Settlement Record [START]-------------------->")
		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			val currentProduct = lastSettlementList[currentProductIndex]
			helperLog.appendLine(helperLogClassName, "Switch to last settlement [$currentProductIndex] :: mid=${currentProduct.mid} tid=${currentProduct.tid}")
			currentProduct.settleInfo.let { fileData ->
				val lastSettleString = fileData.split("|").map {
					it.split(",").let { pairItem ->
						Pair(pairItem[0].substring(1), pairItem.getOrNull(1)?.dropLast(1) ?: "")
					}
				}
				settleInfo = lastSettleString as MutableList<Pair<String, String>>
			}
			helperLog.appendLine(helperLogClassName, "-----------------Switch Last Settlement Record [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		startProgressDialog(mContext, "Last Settlement", "Loading...")
		startCoroutine.join()
		formLayout(mainReceipt, settleInfo)
		closeProgressDialog()
	}

	fun preparePrintRecord(modelToPrint: DbModelProductList, recordSummary: List<DbModelSettlementSummary>){
		/*val log = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(mContext),
			Utils.getIPAddress(),
			"SettlementActivity - preparePrintRecord",
			SettlementActivity::class.java.simpleName,
			SettlementActivity::class.java.simpleName,
		)*/

		helperLog.appendLine(helperLogClassName, "-----------------Print Settlement Record [START]-------------------->")
		helperLog.appendLine(helperLogClassName, "Printing for :: acq=${modelToPrint.AcqCode} mid=${modelToPrint.AcqMid} tid=${modelToPrint.AcqTid} batchNo=$batchNo")
		val toPrintInfo = constructPrintRecord(modelToPrint, recordSummary)
		val stringInfo = toPrintInfo.joinToString("|")
		Utils.write2File(arrayOf(stringInfo), "lastsettlement.txt")
		printInfo(toPrintInfo, false)
		val settleDt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
		val dbModelLastSettlement = DbModelLastSettlement (
			modelToPrint.AcqMid, modelToPrint.AcqTid, stringInfo, isTpaAccount.toString(), settleDt
		)
		LastSettlementRepo.addOrInsert(mContext, dbModelLastSettlement)

		helperLog.appendLine(helperLogClassName, "Last settlement record stored :: settleDt=$settleDt")
		helperLog.appendLine(helperLogClassName, "-----------------Print Settlement Record [END]-------------------->")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun constructPrintRecord(productModel: DbModelProductList, settlementSummary: List<DbModelSettlementSummary>): MutableList<Pair<String, String>>{
		Thread.sleep(200)
		val timeStamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)
		var tid = productModel.AcqTid
		var mid = productModel.AcqMid
		if(isTpaAccount) {
			mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
			tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
		}
		val acqName = ServiceHolder.getAcquirerSetting().acqName
		if (acqName.uppercase(Locale.ENGLISH) == "GOBIZ") {
			tid = Utils.maskString(tid, 4)
			mid = Utils.maskString(mid, 4)
		}

		var headerText = "SETTLEMENT REPORT"
		if (previewSettlement == 1) {
			headerText = "SALES SUMMARY"
		}
		val toPrintInfo = mutableListOf(
			"Date/Time" to timeStamp,
			"TERMINAL ID" to tid,
			"MERCHANT ID" to mid,
			"BATCH NO" to batchNo,
			"HEADER" to headerText,
		)

		val terminalConfig = getTerminalConfig()
		val optIn = getBooleanValue(terminalConfig, "OptIn")
		val cardTypeMap: MutableMap<String, String> = mutableMapOf(
			"visa" to "Visa",
			"master" to "Master",
		)
		if(acquirerSettingModel.acqName.equals("GOBIZ", true)) {
			cardTypeMap.put("upi", "UnionPay")
		}

		if (optIn) {
			cardTypeMap.put("mccs", "MyDebit")
		}

		val settlementType: MutableMap<String, String> = mutableMapOf(
			"txnCount" to "SALE COUNT",
			"txnTotal" to "SALE TOTAL",
			"voidTxnCount" to "VOID COUNT",
			"voidTxnTotal" to "VOID TOTAL",
			"cashOutTotal" to "CASHOUT TOTAL",
			"refundTxnTotal" to "REFUND TOTAL",
		)
		val summaryMap: MutableMap<String, Int> = mutableMapOf(
			"txnCount" to 0,
			"txnTotal" to 0,
			"voidTxnCount" to 0,
			"voidTxnTotal" to 0,
			"cashOutTotal" to 0,
			"refundTxnTotal" to 0,
		)

		for (cardTypeKey in cardTypeMap.keys) {
			toPrintInfo.add("SCHEME" to cardTypeMap[cardTypeKey]!!)
			for(settleTypeKey in settlementType.keys) {
				val filteredData = settlementSummary.find { it.tag == settleTypeKey && it.subtag == "visam-$cardTypeKey" }

				var summaryValue = filteredData?.value ?: "0"
				var temp = summaryMap[settleTypeKey] ?: 0
				temp += Utils.atoi(summaryValue)
				summaryMap[settleTypeKey] = temp

				if(settleTypeKey.contains("Total")) {
					summaryValue = Utils.getActualAmount(summaryValue)
				}

				if(!settleTypeKey.equals("cashOutTotal", true) && !settleTypeKey.equals("refundTxnTotal", true)) {
					toPrintInfo.add(settlementType[settleTypeKey]!! to summaryValue)
				}
			}
			toPrintInfo.add("br" to "")
		}

		toPrintInfo.add("hr" to "")
		toPrintInfo.add("br" to "")
		toPrintInfo.add("HEADER" to "TOTAL SETTLEMENT")
		for(summaryTypeKey in summaryMap.keys) {
			var summaryValue = summaryMap[summaryTypeKey].toString()
			if(summaryTypeKey.contains("Total")) {
				summaryValue = Utils.getActualAmount(summaryValue)
			}
			toPrintInfo.add(settlementType[summaryTypeKey]!! to summaryValue)
		}
		toPrintInfo.add("br" to "")
		toPrintInfo.add("hr" to "")
		return toPrintInfo
	}

	private fun formLayout(mainReceipt: LinearLayout, listData: MutableList<Pair<String, String>>) {
		mainReceipt.removeAllViews()
		var powerByBmp: Bitmap? = null
		val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
			helperLog.appendLine(helperLogClassName, "Custom power-by logo loaded")
		}

		val headerImageView = findViewById<ImageView>(R.id.headerImageView)
		if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpaAccount) {
			if(powerByBmp != null) {
				headerImageView.setImageBitmap(powerByBmp)
			} else {
				headerImageView.setImageResource(R.mipmap.logo)
			}
		} else {
			headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
		}

		val name = TextView(applicationContext)
		name.textSize = 9f
		name.textAlignment = View.TEXT_ALIGNMENT_CENTER
		name.setTextColor(ContextCompat.getColor(this, R.color.black))
		name.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName")
		val addr = TextView(applicationContext)
		addr.textSize = 9f
		addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
		addr.setTextColor(ContextCompat.getColor(this, R.color.black))
		addr.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress")
		mainReceipt.addView(name)
		mainReceipt.addView(addr)

		listData.forEach { (key, keyValue) ->
			val ll = LinearLayout(applicationContext)
			if(key == "HEADER"){
				ll.orientation = LinearLayout.HORIZONTAL
				val params1 = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
				)
				params1.setMargins(5, 0, 5, 0)
				val tv = TextView(applicationContext)
				tv.layoutParams = params1
				tv.textSize = 14f
				tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
				tv.setTypeface(tv.typeface, Typeface.BOLD)
				tv.setTextColor(ContextCompat.getColor(this, R.color.black))
				tv.text = keyValue
				ll.addView(tv)
			} else if (key == "hr") {
				ll.orientation = LinearLayout.HORIZONTAL
				val params1 = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(3)
				)
				val viewContainer = View(applicationContext)
				viewContainer.layoutParams = params1
				viewContainer.setBackgroundResource(R.drawable.dotted_line)
				viewContainer.setLayerType(Layer.LAYER_TYPE_SOFTWARE, null)
				ll.addView(viewContainer)
			} else {
				ll.orientation = LinearLayout.HORIZONTAL
				ll.weightSum = 2f
				val params1 = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
				)
				params1.setMargins(5, 0, 5, 0)
				params1.weight = 1f
				val label = if (key == "br") "" else key
				val tv = TextView(applicationContext)
				tv.layoutParams = params1
				tv.textSize = 11f
				tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
				tv.setTextColor(ContextCompat.getColor(this, R.color.black))
				tv.text = label
				val tv1 = TextView(applicationContext)
				tv1.layoutParams = params1
				tv1.textSize = 11f
				tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
				tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
				tv1.text = keyValue
				ll.addView(tv)
				ll.addView(tv1)
			}

			mainReceipt.addView(ll)
		}

		val footer = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params.setMargins(0, 5, 0, 0)
		params.gravity = Gravity.CENTER
		footer.orientation = LinearLayout.VERTICAL
		footer.layoutParams = params

		if (isLastSettlement && lastSettlementList.isNotEmpty()) {
			val settleSuccess = TextView(applicationContext)
			settleSuccess.textSize = 9f
			settleSuccess.textAlignment = View.TEXT_ALIGNMENT_CENTER
			settleSuccess.setTextColor(ContextCompat.getColor(this, R.color.black))
			settleSuccess.text = "Settlement Successful!"
			footer.addView(settleSuccess)
		}

		val power = TextView(applicationContext)
		power.textSize = 9f
		power.textAlignment = View.TEXT_ALIGNMENT_CENTER
		power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
		power.setTextColor(ContextCompat.getColor(this, R.color.black))
		//power.text = "POWERED BY"
		//footer.addView(power)

		val im = ImageView(applicationContext)
		val params1 = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, getInstance().getDpValue(30)
		)
		params1.gravity = Gravity.CENTER
		im.layoutParams = params1
		im.adjustViewBounds = true

		if(!isHideBottom) {
			if(!isTpaAccount) {
				if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
					//if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
					if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
						power.text = "PARTNER WITH"
						footer.addView(power)
						im.setImageResource(acqLogoEnumModel.HeaderLogoPng)
						footer.addView(im)
					}
				} else {
					power.text = "POWERED BY"
					footer.addView(power)
					if(powerByBmp != null) {
						im.setImageBitmap(powerByBmp)
					} else {
						im.setImageResource(R.mipmap.logo)
					}
					footer.addView(im)
				}
			} else {
				if (isLastSettlement) {
					power.text = "Behind Every Payment, There's A Smile."
					footer.addView(power)
				}
			}
		}
		mainReceipt.addView(footer)
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving settlement screen")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun switchRecordBackward(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Previous [SETTLEMENT RECORD]")
		if(currentProductIndex == 0){
			helperLog.appendLine(helperLogClassName, "REJECT :: already at first page")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(applicationContext, "First page has reached", Toast.LENGTH_SHORT).show()
			return
		}

		lifecycleScope.launch {
			currentProductIndex--
			switchRecordCoroutine()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun switchRecordForward(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Next [SETTLEMENT RECORD]")
		if(currentProductIndex + 1 == settlementProduct.size){
			helperLog.appendLine(helperLogClassName, "REJECT :: already at last page")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(applicationContext, "Last page has reached", Toast.LENGTH_SHORT).show()
			return
		}

		lifecycleScope.launch {
			currentProductIndex++
			switchRecordCoroutine()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun switchLastSettlementBackward(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Previous [LAST SETTLEMENT]")
		if(currentProductIndex == 0){
			helperLog.appendLine(helperLogClassName, "REJECT :: already at first page")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(applicationContext, "First page has reached", Toast.LENGTH_SHORT).show()
			return
		}

		lifecycleScope.launch {
			currentProductIndex--
			switchLastSettlementCoroutine()
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun switchLastSettlementForward(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Next [LAST SETTLEMENT]")
		if(currentProductIndex + 1 == lastSettlementList.size){
			helperLog.appendLine(helperLogClassName, "REJECT :: already at last page")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(applicationContext, "Last page has reached", Toast.LENGTH_SHORT).show()
			return
		}

		lifecycleScope.launch {
			currentProductIndex++
			switchLastSettlementCoroutine()
		}
	}

	fun settle_btn_ok(view: View?) {
		totalSettlementCount = 0
		if (!settledSettlement && !isLastSettlement && previewSettlement == 0) {
			if (!autoSettlementIsRunning && runningBlock == 0) {
				ServiceHolder.settlementDialogMessage = ""
				helperLog.appendLine(helperLogClassName, "Selected :: SETTLE [SETTLEMENT]")
				helperLog.appendLine(helperLogClassName, "Validation passed :: starting settlement for ${settlementProduct.size} product(s), batchNo=$batchNo")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				Trans().execute()
			} else {
				helperLog.appendLine(helperLogClassName, "REJECT :: settlement already in progress (autoSettlementIsRunning=$autoSettlementIsRunning, runningBlock=$runningBlock)")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		} else {
			helperLog.appendLine(helperLogClassName, "Selected :: PRINT [SETTLEMENT]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			print_receipt()
		}
	}

	fun checkProductStatus(productToCheck: DbModelProductList): List<DbModelSettlementSummary>? {
		val summary = settledProduct.get(productToCheck)
		if(summary != null){
			runOnUiThread {
				settledSettlement = true
				(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.GONE
				(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.VISIBLE
			}
		} else {
			runOnUiThread {
				settledSettlement = false
				(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.VISIBLE
				(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
			}
		}
		return summary
	}

	fun settle_btn_cancel(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: CANCEL [SETTLEMENT]")
		helperLog.appendLine(helperLogClassName, "User Cancel :: settlement screen dismissed without settling")
		customOnBackPress()
	}

	@SuppressLint("StaticFieldLeak")
	private inner class Trans: CoroutineTask<String?, Boolean>() {
		@RequiresApi(Build.VERSION_CODES.O)
		override fun onPreExecute() {
			super.onPreExecute()
			runningBlock = 1
			helperLog.appendLine(helperLogClassName, "-----------------Process Settlement Batch [START]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			//startProgressDialog(mContext, "Settlement", "Uploading... Please Wait")
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		override fun doInBackground(vararg params: String?): Boolean {
			startProgressDialog(mContext, "Settlement", "Preparing...")
			var result = true
			autoSettlementIsRunning = true
			jsonArray = JSONArray()
			txnMapList = arrayListOf()
			val sharedPreferences: SharedPreferences = Helper.getInstance().getPrefs(mContext)
			val timeString = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
			sharedPreferences.edit { putString(AppServices.LAST_SETTLE_TAG, timeString) }

			if(settlementProduct.isEmpty()) {
				SettlementSummaryRepo.updateData(mContext, hashMapOf("is_settle" to "false"), hashMapOf())
				runOnUiThread {
					Toast.makeText(mContext, "Abnormal Detected! Fail To Settle. \nPlease Run Settlement Again...", Toast.LENGTH_SHORT).show()
				}
				helperLog.appendLine(helperLogClassName, "REJECT :: empty settlement product, is_settle flags reset")
				helperLog.appendLine(helperLogClassName, "-----------------Process Settlement Batch [END]-------------------->")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
				autoSettlementIsRunning = false
				return false
			}

			for(forIndex in settlementProduct.indices) {
				val tempObj = settlementProduct[forIndex]
				helperLog.appendLine(helperLogClassName, "Settling product [${forIndex + 1}/${settlementProduct.size}] :: acq=${tempObj.AcqCode} mid=${tempObj.AcqMid} tid=${tempObj.AcqTid} batchNo=$batchNo")
				if(checkProductStatus(tempObj) != null){
					helperLog.appendLine(helperLogClassName, "Skip :: product already settled")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					continue
				}
				pDMsg = "Checking Reversal..."
				helperLog.appendLine(helperLogClassName, "-----------------Pending Reversal [START]-------------------->")
				runOnUiThread(changeMessage)

				//TODO Reversal Before Settlement
				val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(tempObj.AcqCode, "reversal")
				if (acquirerRevIsoModel == null) {
					helperLog.appendLine(helperLogClassName, "REJECT :: no reversal ISO profile for acq=${tempObj.AcqCode}, skipping product")
					helperLog.appendLine(helperLogClassName, "-----------------Pending Reversal [END]-------------------->")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					continue
				}
				var reversalResult = true
				//TODO
				val reversalBatch = ReversalBatchTableRepo.getBatchData(mContext, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, tempObj.AcqMid, tempObj.AcqTid))
				helperLog.appendLine(helperLogClassName, "Reversal Batch List::(${reversalBatch.size})")
				if(reversalBatch.isNotEmpty()) {
					for (tempFor in reversalBatch) {
						helperLog.appendLine(helperLogClassName, "Reversal pending :: stan=${tempFor.stan} invNo=${tempFor.invNo} batchNo=${tempFor.batchNo}")
						TransData.reset(mContext)

						val productModel = ProductListRepo.getSingle(mContext, listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid))
						TransData.acqCode = tempObj.AcqCode
						TransData.product = tempObj.Product
						TransData.mid = tempObj.AcqMid
						TransData.tid = tempObj.AcqTid
						TransData.schemeTag = "visam"
						TransData.txnTypeLabel = "Reversal"
						TransData.ksn = productModel?.Ksn ?: ""
						TransData.pinKsn = productModel?.PinKsn ?: ""
						TransData.stan = tempFor.stan
						TransData.invoiceNo = tempFor.invNo
						TransData.batchNo = tempFor.batchNo
						TransData.isTpaAccount = tempObj.IsTpaAccount?.lowercase() == "true"
						TransData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
						TransData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")

						val revResult = IsoActivity.processReversal(mContext, false, acquirerRevIsoModel, tempFor.batchData, true, helperLog)
						if(reversalResult && revResult == null){
							reversalResult = false
						}
					}
				}
				//TODO Reversal Fail Abort Settlement
				if(!reversalResult) {
					helperLog.appendLine(helperLogClassName, "REJECT :: reversal failed, settlement aborted for mid=${tempObj.AcqMid} tid=${tempObj.AcqTid}")
					helperLog.appendLine(helperLogClassName, "-----------------Pending Reversal [END]-------------------->")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ToastMake(mContext, "Settlement Abort, Reversal Could Not Complete...", Toast.LENGTH_LONG)
					continue
				}

				IsoBatchLongInfoRepo.updateBatchLongInfo(mContext, "", "postingDt", "last")
				helperLog.appendLine(helperLogClassName, "-----------------Pending Reversal [END]-------------------->")
				helperLog.logToFile(EnumLogFileName.TerminaLog)

				helperLog.appendLine(helperLogClassName, "-----------------Settlement Totals [START]-------------------->")
				pDMsg = "Uploading... Please Wait"
				runOnUiThread(changeMessage)

				val recordSummary = SettlementSummaryRepo.getSelectiveData(
					applicationContext,
					listOf("acq_code", "mid", "tid"),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid)
				)

				var txnCount = ""
				var txnTotal = ""
				var voidTxnCount = ""
				var voidTxnTotal = ""
				var refundTxnCount = ""
				var refundTxnTotal = ""

				val summaryRecordTxnTotal = SettlementSummaryRepo.getSelectiveData(applicationContext,
					ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "txnTotal")
				)
				if (summaryRecordTxnTotal.isNotEmpty()) {
					val tempTxnTotal = AtomicInteger()
					summaryRecordTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempTxnTotal.addAndGet(
							Utils.atoi(value)
						)
					})
					txnTotal = Utils.getActualAmount(tempTxnTotal.toString())
					helperLog.appendLine(helperLogClassName, "\ttxnTotal = $txnTotal")
				}

				val summaryRecordTxnCount = SettlementSummaryRepo.getSelectiveData(
					applicationContext, ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "txnCount")
				)
				if (summaryRecordTxnCount.isNotEmpty()) {
					val tempTxnCount = AtomicInteger()
					summaryRecordTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempTxnCount.addAndGet(
							Utils.atoi(value)
						)
					})
					txnCount = tempTxnCount.toString()
					//Utils.printLog("\ttxnCount = $txnCount")
					helperLog.appendLine(helperLogClassName, "\ttxnCount = $txnCount")
				}

				//TODO CASHOUT
				val summaryRecordCashOutTotal = SettlementSummaryRepo.getSelectiveData(
					applicationContext, ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "cashOutTotal")
				)
				if (summaryRecordCashOutTotal.isNotEmpty()) {
					val tempTxnTotal = AtomicInteger()
					summaryRecordCashOutTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempTxnTotal.addAndGet(
							Utils.atoi(value)
						)
					})
					val cashOutOnly = tempTxnTotal.get()
					val salesTotal = txnTotal.replace(".", "")
					tempTxnTotal.addAndGet(Utils.atoi(salesTotal))
					txnTotal = Utils.getActualAmount(tempTxnTotal.toString())
					helperLog.appendLine(helperLogClassName, "\tcashOutTotal = ${Utils.getActualAmount(cashOutOnly.toString())} (txnTotal incl. cashOut = $txnTotal)")
				}
				//TODO CASHOUT

				val summaryRecordVoidTxnTotal = SettlementSummaryRepo.getSelectiveData(applicationContext,
					ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "voidTxnTotal")
				)
				if (summaryRecordVoidTxnTotal.isNotEmpty()) {
					val tempVoidTxnTotal = AtomicInteger()
					summaryRecordVoidTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempVoidTxnTotal.addAndGet(
							Utils.atoi(value)
						)
					})
					voidTxnTotal = Utils.getActualAmount(tempVoidTxnTotal.toString())
					//Utils.printLog("\tvoidTxnTotal = $voidTxnTotal")
					helperLog.appendLine(helperLogClassName, "\tvoidTxnTotal = $voidTxnTotal")
				}

				val summaryRecordVoidTxnCount = SettlementSummaryRepo.getSelectiveData(
					applicationContext, ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "voidTxnCount")
				)
				if (summaryRecordVoidTxnCount.isNotEmpty()) {
					val tempVoidTxnCount = AtomicInteger()
					summaryRecordVoidTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempVoidTxnCount.addAndGet(
							Utils.atoi(value)
						)
					})
					voidTxnCount = tempVoidTxnCount.toString()
					//Utils.printLog("\tvoidTxnCount = $voidTxnCount")
					helperLog.appendLine(helperLogClassName, "\tvoidTxnCount = $voidTxnCount")
				}

				val summaryRecordRefundTxnTotal = SettlementSummaryRepo.getSelectiveData(applicationContext,
					ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "refundTxnTotal")
				)
				if (summaryRecordRefundTxnTotal.isNotEmpty()) {
					val tempRefundTxnTotal = AtomicInteger()
					summaryRecordRefundTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempRefundTxnTotal.addAndGet(
							Utils.atoi(value)
						)
					})
					refundTxnTotal = Utils.getActualAmount(tempRefundTxnTotal.toString())
					helperLog.appendLine(helperLogClassName, "\trefundTxnTotal = $refundTxnTotal")
				}

				val summaryRecordRefundTxnCount = SettlementSummaryRepo.getSelectiveData(
					applicationContext, ArrayList(listOf("acq_code", "mid", "tid", "tag")),
					arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "refundTxnCount")
				)
				if (summaryRecordRefundTxnCount.isNotEmpty()) {
					val tempRefundTxnCount = AtomicInteger()
					summaryRecordRefundTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
						tempRefundTxnCount.addAndGet(
							Utils.atoi(value)
						)
					})
					refundTxnCount = tempRefundTxnCount.toString()
					helperLog.appendLine(helperLogClassName, "\trefundTxnCount = $refundTxnCount")
				}

				val settlementValueString = constructSettlementValueString(txnCount, txnTotal, refundTxnCount, refundTxnTotal, "")
				helperLog.appendLine(helperLogClassName, "\tsettlementValueString = $settlementValueString")
				helperLog.appendLine(helperLogClassName, "-----------------Settlement Totals [END]-------------------->")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				val productModel = ProductListRepo.getSingle(applicationContext, listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid))
				TransData.reset(mContext)
				TransData.acqCode = tempObj.AcqCode
				TransData.product = tempObj.Product
				TransData.mid = tempObj.AcqMid
				TransData.tid = tempObj.AcqTid
				TransData.schemeTag = "visam"
				TransData.txnTypeLabel = "Settle"
				TransData.ksn = productModel?.Ksn ?: ""
				TransData.pinKsn = productModel?.PinKsn ?: ""
				TransData.isTpaAccount = isTpaAccount
				TransData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
				TransData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")

				loadingLoop = true
				object : Thread() {
					override fun run() {
						super.run()
						while(loadingLoop) {
							if(TransData.loadingTitle.isNotEmpty()){
								pDTitle = TransData.loadingTitle
								TransData.loadingTitle = ""
								runOnUiThread(changeTitle)
							}

							if(TransData.loadingMessage.isNotEmpty()){
								pDMsg = TransData.loadingMessage
								TransData.loadingMessage = ""
								runOnUiThread(changeMessage)
							}
							sleep(1000)
						}
					}
				}.start()
				helperLog.appendLine(helperLogClassName, "-----------------Settlement Batch Upload [START]-------------------->")
				IsoActivity.processSettlement(applicationContext, tempObj, settlementValueString, helperLog)
				loadingLoop = false
				ServiceHolder.settlementDialogMessage = ""

				if (TransData.transResult == -8001) {
					helperLog.appendLine(helperLogClassName, "REJECT :: nothing to settle")
					helperLog.appendLine(helperLogClassName, "ResponseCode :: [99]")
					helperLog.appendLine(helperLogClassName, "-----------------Settlement Batch Upload [END]-------------------->")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ToastMake(mContext, "Nothing to Settle", Toast.LENGTH_LONG)
					result = false
					continue
				}

				if (TransData.transResult < 0) {
					helperLog.appendLine(helperLogClassName, "REJECT :: fail to settle mid=${tempObj.AcqMid} tid=${tempObj.AcqTid} batchNo=$batchNo transResult=${TransData.transResult}")
					helperLog.appendLine(helperLogClassName, "ResponseCode :: [${Utility.HexString2ASCII(TransData.respCode)}]")
					helperLog.appendLine(helperLogClassName, "-----------------Settlement Batch Upload [END]-------------------->")
					helperLog.logToFile(EnumLogFileName.TerminaLogException)
					ToastMake(mContext, "Fail to Settle", Toast.LENGTH_LONG)
					result = false
					continue
				}

				helperLog.appendLine(helperLogClassName, "Settled OK :: ResponseCode :: [${Utility.HexString2ASCII(TransData.respCode)}] stan=${TransData.stan} rrn=${TransData.rrn} newBatchNo=${TransData.batchNo}")
				helperLog.appendLine(helperLogClassName, "Totals :: saleCount=$txnCount saleTotal=$txnTotal voidCount=$voidTxnCount voidTotal=$voidTxnTotal")
				helperLog.appendLine(helperLogClassName, "-----------------Settlement Batch Upload [END]-------------------->")
				helperLog.logToFile(EnumLogFileName.TerminaLog)

				settledProduct.put(tempObj, recordSummary)
				settlementDetails(txnTotal, txnCount, voidTxnTotal, voidTxnCount, result, TransData.transResult)
				preparePrintRecord(tempObj, recordSummary)

				val isLastItem = settlementProduct.lastIndex == forIndex
				if(isLastItem && totalSettlementCount <= 0){
					helperLog.appendLine(helperLogClassName, "Printing no-transaction-activity slip :: totalSettlementCount=$totalSettlementCount")
					printNoTransactionInfo()
				}

				if(!isLastItem){
					Thread.sleep(1000)
				}
			}

			if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT") ||  DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")) {
				ServiceHolder.clearSettlementBatch = !result
			}

			autoSettlementIsRunning = false
			checkProductStatus(settlementProduct[currentProductIndex])
			helperLog.appendLine(helperLogClassName, "-----------------Process Settlement Batch [END]-------------------->")
			helperLog.appendLine(helperLogClassName, "Settlement batch result :: $result, settled products=${settledProduct.size}/${settlementProduct.size}")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return result
		}

		override fun onPostExecute(result: Boolean?) {
			super.onPostExecute(result)
			closeProgressDialog()
			runningBlock = 0

			if (appIntent) {
				helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter (appIntent)")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				onBackToApp(txnMapList)
			} else if (appHTTP) {
				helperLog.appendLine(helperLogClassName, "Returning settlement result over HTTP")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				onBackToHTTP(jsonArray.toString())
			} else {
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}
	}



	@RequiresApi(Build.VERSION_CODES.O)
	private fun settlementDetails(txnTotal: String, txnCount: String, voidTxnTotal: String, voidTxnCount: String, result: Boolean, iResp: Int){
		val jsonObject = JSONObject()
		txnMap = hashMapOf()

		if (result) {
			txnMap["ResponseCode"] = "00"
			txnMap["ResponseDescription"] = "Settled"
			txnMap["TransactionType"] = txnType.toString()
			txnMap["SettlementAmount"] = txnTotal
			txnMap["SettlementCount"] = txnCount
			txnMap["SettlementSaleAmount"] = txnTotal
			txnMap["SettlementSaleCount"] = txnCount
			txnMap["SettlementVoidAmount"] = voidTxnTotal
			txnMap["SettlementVoidCount"] = voidTxnCount
			txnMap["SettlementMID"] = if(TransData.isTpaAccount) TransData.tpaMid else TransData.mid
			txnMap["SettlementTID"] = if(TransData.isTpaAccount) TransData.tpaTid else TransData.tid
			txnMap["SettlementSTN"] = TransData.stan
			txnMap["SettlementRRN"] = TransData.rrn
			txnMap["SettlementBatchNo"] = TransData.batchNo
			txnMap["SettlementDateTime"] = Utils.DateTimeFormat(TransData.transDateAsci)
			try {
				jsonObject.put("ResponseCode", "00")
				jsonObject.put("ResponseDescription", "Settled")
				jsonObject.put("TransactionType", txnType.toString())
				jsonObject.put("SettlementAmount", txnTotal)
				jsonObject.put("SettlementCount", txnCount)
				jsonObject.put("SettlementSaleAmount", txnTotal)
				jsonObject.put("SettlementSaleCount", txnCount)
				jsonObject.put("SettlementVoidAmount", voidTxnTotal)
				jsonObject.put("SettlementVoidCount", voidTxnCount)
				jsonObject.put("SettlementMID", if(TransData.isTpaAccount) TransData.tpaMid else TransData.mid)
				jsonObject.put("SettlementTID", if(TransData.isTpaAccount) TransData.tpaTid else TransData.tid)
				jsonObject.put("SettlementSTN", TransData.stan)
				jsonObject.put("SettlementRRN", TransData.rrn)
				jsonObject.put("SettlementBatchNo", TransData.batchNo)
				jsonObject.put("SettlementDateTime", Utils.DateTimeFormat(TransData.transDateAsci))
			} catch (e: JSONException) {
				e.printStackTrace()
			}
		} else {
			if (iResp == -8001) {
				txnMap["ResponseCode"] = "SHC003"
				txnMap["ResponseDescription"] = "No Batch to Settle"
				txnMap["TransactionType"] = txnType.toString()
				try {
					jsonObject.put("ResponseCode", "SHC003")
					jsonObject.put("ResponseDescription", "No Batch to Settle")
					jsonObject.put("TransactionType", txnType.toString())
				} catch (e: JSONException) {
					e.printStackTrace()
				}
			} else {
				txnMap["ResponseCode"] = "SHC004"
				txnMap["ResponseDescription"] = "Settlement Fail"
				txnMap["TransactionType"] = txnType.toString()
				try {
					jsonObject.put("ResponseCode", "SHC004")
					jsonObject.put("ResponseDescription", "Settlement Fail")
					jsonObject.put("TransactionType", txnType.toString())
				} catch (e: JSONException) {
					e.printStackTrace()
				}
			}
		}

		helperLog.appendLine(helperLogClassName, "Settlement outcome :: ResponseCode :: [${txnMap["ResponseCode"]}] ${txnMap["ResponseDescription"]}")
		txnMapList.add(txnMap)
		jsonArray.put(jsonObject)
	}

	private fun printInfo(toPrintData: MutableList<Pair<String, String>>, forcePrint: Boolean) {
		var totalTxnCount = 0
		var productTxnCount = 0
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		var productList: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE
		val acquirerBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
		var powerByBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
		val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
		}

		var entity = MulPrintStrEntity("", fontSize)
		entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpaAccount) powerByBmp else acquirerBmp
		entity.marginX = 50
		entity.gravity = Gravity.CENTER
		entity.isUnderline = true
		entity.yspace = 30
		list.add(entity)

		var mulPrintStrEntity = MulPrintStrEntity(
			DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName"), FontFamily.SMALL, false,
			Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		mulPrintStrEntity = MulPrintStrEntity(
			DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress"), FontFamily.SMALL, false,
			Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

		val mapIterator = toPrintData.iterator()
		while(mapIterator.hasNext()){
			val (key, keyValue) = mapIterator.next()
			if(key == "SCHEME" || key == "hr"){
				if(productTxnCount > 0) list.let { list1 -> productList.let(list1::addAll) }
				totalTxnCount += productTxnCount
				productTxnCount = 0
				productList = ArrayList()
			}

			if(key == "HEADER"){
				productList.add(MulPrintStrEntity(keyValue, FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
				list.let { list1 -> productList.let(list1::addAll) }
				productTxnCount = 0
				productList = ArrayList()
			} else if (key == "hr") {
				list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
				productTxnCount = 0
				productList = ArrayList()
			} else {
				val label = if (key == "br") "" else key
				if (key.contains("COUNT")){
					productTxnCount += Utils.atoi(keyValue.trim())
				}
				productList.add(
					MulPrintStrEntity(
						Utils.makeLineText(
							TextItem(label).setFont(fontSize),
							TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					)
				)
			}
		}

		totalSettlementCount += totalTxnCount
		if(totalTxnCount <= 0){
			list.add(MulPrintStrEntity(Utils.makeLineText(TextItem("").setFont(fontSize), TextItem("").setFont(fontSize).setPaddingAlign(Gravity.RIGHT)), fontSize))
			list.add(MulPrintStrEntity("No Transaction Activity", FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
			list.add(MulPrintStrEntity(Utils.makeLineText(TextItem("").setFont(fontSize), TextItem("").setFont(fontSize).setPaddingAlign(Gravity.RIGHT)), fontSize))

			if(!forcePrint){
				helperLog.appendLine(helperLogClassName, "Print skipped :: no transaction activity and forcePrint=false")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				return
			}
		}

		if (previewSettlement != 1){
			list.add(MulPrintStrEntity("Settlement Successful!", FontFamily.SMALL, false, Gravity.CENTER))
		}
		if(!isHideBottom) {
			if(!isTpaAccount) {
				if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
					//if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
					if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
						list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))

						entity = MulPrintStrEntity("", fontSize)
						entity.bitmap = acquirerBmp
						entity.marginX = 50
						entity.gravity = Gravity.CENTER
						entity.isUnderline = true
						entity.yspace = 30
						list.add(entity)
					}
				} else {
					list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))

					entity = MulPrintStrEntity("", fontSize)
					entity.bitmap = powerByBmp
					entity.marginX = 50
					entity.gravity = Gravity.CENTER
					entity.isUnderline = true
					entity.yspace = 30
					list.add(entity)
				}
			} else {
				if (previewSettlement != 1) {
					list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
				}
			}
		}

		entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun printNoTransactionInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE

		list.add(MulPrintStrEntity("No Transaction Activity", FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
		val entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun print_receipt() {
		helperLog.appendLine(helperLogClassName, "Reprinting settlement receipt on screen")
		val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
//		val scrollReceipt: ScrollView = findViewById(R.id.scrollReceipt)
		scrollReceipt.startAnimation(animSlideDown)
		printInfo(settleInfo, true)
	}

	private fun onBackToApp(txn_map: ArrayList<HashMap<String, String?>>) {
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter (${txn_map.size} result(s))")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("settlement_map", txn_map)
		startActivity(intent)
		finish()
	}

	private fun onBackToHTTP(msg: String) {
		HTTPServer.getInstance().setResponseMessage(msg)
		appHTTP = false
		customOnBackPress()
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

	override fun onDestroy() {
		super.onDestroy()
		loadingLoop = false
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "SettlementActivity OnDestroy :: settlement screen closed (runningBlock=$runningBlock)")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}
