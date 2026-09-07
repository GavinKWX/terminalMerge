package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import utils.Util
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.QrPayTableRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.*
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.autoSettlementIsRunning
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.txnType
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*

class SettlementQrActivity : ActivityBase() {
	lateinit var mContext: Context

	private var qrPayBrand = arrayOf<String?>()
	lateinit var qrPayBrandV2: List<DbModelQrPayBrandGet>
	private var qrPayTotalTxnAmt = arrayOf<String?>()
	private var qrPayTotalTxnCount = arrayOf<String?>()
	private var qrPayTotalVoidTxnAmt = arrayOf<String?>()
	private var qrPayTotalVoidTxnCount = arrayOf<String?>()
	private var settleInfo = arrayOf<String?>()
	private var totalSettleInfo = arrayOf<String?>()
	lateinit var mainReceipt: LinearLayout
	lateinit var scrollReceipt: ScrollView
	lateinit var headerText: TextView

	//private var totalA: Long = 0
	//private var totalC: Long = 0
	private var SaleA: Long = 0
	private var SaleC: Long = 0
	private var VoidA: Long = 0
	private var VoidC: Long = 0
	var dbModelMerchantConfig: DbModelMerchantConfig? = null

	private var isLastSettlement = false
	private var previewSettlement = 0

	var txnDt = ""
	var rrn = ""
	var stan = ""
	var mid = ""
	var tid = ""
	var batchNo = ""

	private var helperlogClassName:String = ""
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settlement_qr)
		mContext = this
		mainReceipt = findViewById(R.id.mainLayout)
		scrollReceipt = findViewById(R.id.scrollReceipt)
		headerText = findViewById(R.id.textHeader)

		previewSettlement = intent.getIntExtra("previewSettlement", 0)
		isLastSettlement = (intent.getIntExtra("Last", 0) != 0)

		helperlogClassName = this::class.qualifiedName.toString()
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"QR Settlement Activity"
		)
		val log = helperLog
		helperLog.appendLine(helperLogClassName, "QR Settlement screen opened")

		//qrPayBrandV2 = QrPayTableRepo.getPayBrand(mContext)!!
		qrPayBrandV2 = listOf()

		val toolbar = findViewById<Toolbar>(R.id.toolbarSettlement)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this@SettlementQrActivity, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

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

		headerText.text = if (isLastSettlement){
			"Last Settlement QR"
		} else if (previewSettlement == 1){
			"Preview Settlement QR"
		} else {
			"Settlement QR"
		}
		helperLog.appendLine(helperLogClassName, "Mode :: ${headerText.text} (previewSettlement=$previewSettlement, isLastSettlement=$isLastSettlement)")

		lifecycleScope.launch {
			getRecordCoroutine(log)
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	suspend fun getRecordCoroutine(log: helpers.HelperLog) {
		log.appendLine(helperlogClassName, "-----------------Load QR Settlement Record [START]-------------------->")
		var settlementInfo: Array<String?> = arrayOf()
		var totalSettlementInfoQr: Array<String?>

		val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
			dbModelMerchantConfig = getMerchantInfo()

			if (!isLastSettlement) {
				qrPayBrandV2 = TransactionQrRepo.getDistinctProduct(mContext) ?: listOf()
				qrPayTotalTxnAmt = arrayOfNulls(qrPayBrandV2.size)
				qrPayTotalTxnCount = arrayOfNulls(qrPayBrandV2.size)
				qrPayTotalVoidTxnAmt = arrayOfNulls(qrPayBrandV2.size)
				qrPayTotalVoidTxnCount = arrayOfNulls(qrPayBrandV2.size)

				for (i in qrPayBrandV2.indices) {
					val modelData = qrPayBrandV2[i]
					println("modelData >> $modelData")
					val tempSaleA = getTxnTotalAmount(modelData.productCode, "Sale").toLong()
					val tempSaleC = getTxnTotalCount(modelData.productCode, "Sale")
					val tempVoidA =  getTxnTotalAmount(modelData.productCode, "Void").toLong()
					val tempVoidC = getTxnTotalCount(modelData.productCode, "Void")
					SaleA += tempSaleA
					SaleC += tempSaleC
					VoidA += tempVoidA
					VoidC += tempVoidC

					qrPayTotalTxnCount[i] = tempSaleC.toString()
					qrPayTotalTxnAmt[i] = tempSaleA.toString()
					qrPayTotalVoidTxnCount[i] =  tempVoidC.toString()
					qrPayTotalVoidTxnAmt[i] =  tempVoidA.toString()
				}

				txnDt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)
				settlementInfo = arrayOf(txnDt, dbModelMerchantConfig?.QrMid ?: "", "")
			} else {
				txnDt = ""
				(findViewById<View>(R.id.settleBtn) as LinearLayout).visibility = View.GONE
				(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.VISIBLE
				try {
					settleInfo = Utils.readFromFile("qrlastsettlement.txt")
					txnDt = settleInfo[0].toString()

					settlementInfo = arrayOfNulls<String>(3)
					totalSettlementInfoQr = arrayOfNulls<String>(9)

					System.arraycopy(settleInfo, 0, settlementInfo, 0, settlementInfo.size)

					val qrDetailsLen = (settleInfo.size - settlementInfo.size - totalSettlementInfoQr.size) / 5
					if (qrDetailsLen > 0) {
						val tempPayBrand = mutableListOf<DbModelQrPayBrandGet>()
						qrPayBrand = arrayOfNulls(qrDetailsLen)
						qrPayTotalTxnAmt = arrayOfNulls(qrDetailsLen)
						qrPayTotalTxnCount = arrayOfNulls(qrDetailsLen)
						qrPayTotalVoidTxnAmt = arrayOfNulls(qrDetailsLen)
						qrPayTotalVoidTxnCount = arrayOfNulls(qrDetailsLen)
						var count = 0
						var tCount = 0
						var totalSettleStart = 0

						for (s in 0 until (settleInfo.size - totalSettlementInfoQr.size)) {
							when (count) {
								3 -> {
									qrPayBrand[tCount] = settleInfo[s]
									tempPayBrand.add(DbModelQrPayBrandGet(productName = settleInfo[s] ?: "", productCode = settleInfo[s] ?: "", payBrand = ""))
								}
								4 -> {
									qrPayTotalTxnCount[tCount] = settleInfo[s]
									SaleC += Utils.atoi(settleInfo[s])
								}
								5 -> {
									qrPayTotalTxnAmt[tCount] = settleInfo[s]
									SaleA += (settleInfo[s] ?: "0.00").replace(".", "").toLong()
								}
								6 -> {
									qrPayTotalVoidTxnCount[tCount] = settleInfo[s]
									VoidC += Utils.atoi(settleInfo[s])
								}
								7 -> {
									qrPayTotalVoidTxnAmt[tCount] = settleInfo[s]
									VoidA += (settleInfo[s] ?: "0.00").replace(".", "").toLong()
								}
							}
							totalSettleStart++
							count++
							if (count > 7) {
								count = 3
								tCount++
							}
						}

						if(tCount > 0){
							qrPayBrandV2 = tempPayBrand
						}

						for ((totalSettleCount, s) in (totalSettleStart until settleInfo.size).withIndex()){
							when (totalSettleCount) {
								3 -> SaleC = settleInfo[s]?.toLong() ?: 0
								4 -> SaleA = (settleInfo[s]?.toBigDecimalOrNull()?.multiply(BigDecimal(100)))?.toLong() ?: 0
								5 -> VoidC = settleInfo[s]?.toLong() ?: 0
								6 -> VoidA = (settleInfo[s]?.toBigDecimalOrNull()?.multiply(BigDecimal(100)))?.toLong() ?: 0
							}
						}
					}
				} catch (e: Exception) {
					log.appendLine(helperlogClassName, "REJECT :: QR last settlement record not readable :: ${e.message}")
					log.logToFile(EnumLogFileName.TerminaLogException)
					runOnUiThread{
						Toast.makeText(applicationContext, "QR Last Settlement Not Found.", Toast.LENGTH_SHORT).show()
					}
				}
			}
			log.appendLine(helperlogClassName, "QR Totals :: mid=${dbModelMerchantConfig?.QrMid ?: ""} tid=${dbModelMerchantConfig?.QrTid ?: ""} brands=${qrPayBrandV2.size} saleCount=$SaleC saleTotal=$SaleA voidCount=$VoidC voidTotal=$VoidA")
			log.appendLine(helperlogClassName, "-----------------Load QR Settlement Record [END]-------------------->")
			log.logToFile(EnumLogFileName.TerminaLog)
		}

		var loadingTitle = "QR Settlement"
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
				ServiceHolder.appRunningProcess = true
				log.appendLine(helperlogClassName, "-----------------QR Settlement Commit [START]-------------------->")
				val combined = settleInfo + totalSettleInfo
				Utils.write2File(combined, "qrlastsettlement.txt")
				printReceipt()
				TransactionQrRepo.truncateTable(mContext)
				log.appendLine(helperlogClassName, "QR batch settled and cleared :: ResponseCode :: [00] saleCount=$SaleC saleTotal=${Utils.getActualAmount(SaleA.toString())} voidCount=$VoidC voidTotal=${Utils.getActualAmount(VoidA.toString())}")
				log.appendLine(helperlogClassName, "-----------------QR Settlement Commit [END]-------------------->")
				log.logToFile(EnumLogFileName.TerminaLog)
				txn_map["ResponseCode"] = "00"
				txn_map["ResponseDescription"] = "Settled"
				txn_map["TransactionType"] = txnType.toString()
				txn_map["SettlementAmount"] = Utils.getActualAmount(SaleA.toString())
				txn_map["SettlementCount"] = SaleC.toString()
				txn_map["SettlementSaleAmount"] = Utils.getActualAmount(SaleA.toString())
				txn_map["SettlementSaleCount"] = SaleC.toString()
				txn_map["SettlementVoidAmount"] = Utils.getActualAmount(VoidA.toString())
				txn_map["SettlementVoidCount"] = VoidC.toString()
				txn_map["SettlementMID"] = (dbModelMerchantConfig?.QrMid ?: "").toString()
				txn_map["SettlementTID"] = (dbModelMerchantConfig?.QrTid ?: "").toString()
				txn_map["SettlementDateTime"] = txnDt
				try {
					jObject.put("ResponseCode", "00")
					jObject.put("ResponseDescription", "Settled")
					jObject.put("TransactionType", txnType.toString())
					jObject.put("SettlementAmount", Utils.getActualAmount(SaleA.toString()))
					jObject.put("SettlementCount", SaleC.toString())
					jObject.put("SettlementSaleAmount", Utils.getActualAmount(SaleA.toString()))
					jObject.put("SettlementSaleCount", SaleC.toString())
					jObject.put("SettlementVoidAmount", Utils.getActualAmount(VoidA.toString()))
					jObject.put("SettlementVoidCount", VoidC.toString())
					jObject.put("SettlementMID", dbModelMerchantConfig?.QrMid ?: "")
					jObject.put("SettlementTID", dbModelMerchantConfig?.QrTid ?: "")
					jObject.put("SettlementDateTime", txnDt)
				} catch (e: JSONException) {
					e.printStackTrace()
				}
			} else {
				log.appendLine(helperlogClassName, "REJECT :: auto settlement already running, ResponseCode :: [SHC002]")
				log.logToFile(EnumLogFileName.TerminaLog)
				withContext(Dispatchers.Main) {
					Toast.makeText(mContext, "Auto Settlement is running", Toast.LENGTH_SHORT).show()
				}
				txn_map["ResponseCode"] = "SHC002"
				txn_map["ResponseDescription"] = "Auto Settlement is running"
				txn_map["TransactionType"] = txnType.toString()
				try {
					jObject.put("ResponseCode", "SHC002")
					jObject.put("ResponseDescription", "Auto Settlement is running")
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

		val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
		settleInfo = arrayOfNulls(value.size + qrPayBrandV2.size * tags2.size)
		System.arraycopy(value, 0, settleInfo, 0, value.size)
		var count = value.size
		qrBrand@ for (j in qrPayBrandV2.indices) {
			val modelData = qrPayBrandV2[j]

			for (i in tags2.indices) {
				var res: String? = ""
				if (i == 0 && qrPayTotalTxnCount[j] == "0" && qrPayTotalVoidTxnCount[j] == "0") {
					continue@qrBrand
				}

				when (i) {
					0 -> res = modelData.productCode
					1 -> res = qrPayTotalTxnCount[j]
					2 -> {
						if (qrPayTotalTxnAmt[j] == null) {
							qrPayTotalTxnAmt[j] = "000"
						}
						res = if (isLastSettlement) { qrPayTotalTxnAmt[j] }
						else Utils.getActualAmount(qrPayTotalTxnAmt[j])
					}
					3 -> res = qrPayTotalVoidTxnCount[j]
					4 -> {
						if (qrPayTotalVoidTxnAmt[j] == null) {
							qrPayTotalVoidTxnAmt[j] = "000"
						}
						res = if (isLastSettlement) { qrPayTotalVoidTxnAmt[j] }
						else Utils.getActualAmount(qrPayTotalVoidTxnAmt[j])
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

			val ll = LinearLayout(applicationContext)
			val params1 = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			val tv = TextView(applicationContext)
			tv.layoutParams = params1
			tv.textSize = 11f
			tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
			tv.text = ""
			ll.addView(tv)
			mainReceipt.addView(ll)
		}
		settleInfo = settleInfo.filterNotNull().toTypedArray()

		val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")
		totalSettleInfo = arrayOfNulls(tags3.size)
		for (j in tags3.indices) {
			when (j){
				0, 8 -> {
					totalSettleInfo[j] = ""
					val ll = LinearLayout(applicationContext)
					ll.orientation = LinearLayout.HORIZONTAL
					val params1 = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(3)
					)
					val viewContainer = View(applicationContext)
					viewContainer.layoutParams = params1
					viewContainer.setBackgroundResource(R.drawable.dotted_line)
					viewContainer.setLayerType(Layer.LAYER_TYPE_SOFTWARE, null)
					ll.addView(viewContainer)
					mainReceipt.addView(ll)
				}
				1, 7 -> {
					totalSettleInfo[j] = ""
					val ll = LinearLayout(applicationContext)
					val params1 = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
					)
					val tv = TextView(applicationContext)
					tv.layoutParams = params1
					tv.textSize = 11f
					tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
					tv.text = ""
					ll.addView(tv)
					mainReceipt.addView(ll)
				}
				2 -> {
					totalSettleInfo[j] = ""
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
					tv.text = tags3[j]
					ll.addView(tv)
					mainReceipt.addView(ll)
				}
				else -> {
					var res: String? = ""

					when (j) {
						3 -> res = SaleC.toString()
						4 -> res = Utils.getActualAmount(SaleA.toString())
						5 -> res = VoidC.toString()
						6 -> res = Utils.getActualAmount(VoidA.toString())
					}
					totalSettleInfo[j] = res

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
					tv.text = tags3[j]
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
		}

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
		mainReceipt.addView(footer)
	}

	private fun getTxnTotalCount(productCode: String, txnType: String): Int {
		/*var iTotalCount = isoDb.getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType)
		if (iTotalCount < 0) {
			sysPrint("Failed to get totalTxnCount for $payBrand and $txnType")
			iTotalCount = 0
		}
		return iTotalCount*/
		val iTotalCount = TransactionQrRepo.countTransactionCountByType(mContext, productCode, txnType)
		sysPrint("TotalTxnCount >> $iTotalCount")
		return if (iTotalCount <= 0) 0 else iTotalCount
	}

	private fun getTxnTotalAmount(productCode: String, txnType: String): String {
		/*var strTotalTxnAmt = isoDb.getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType)
		if (strTotalTxnAmt == null) {
			sysPrint("Failed to get totalTxnAmt for $payBrand and $txnType")
			strTotalTxnAmt = "000"
		}
		return strTotalTxnAmt*/
		val strTotalTxnAmt = TransactionQrRepo.countTransactionAmountByType(mContext, productCode, txnType)
		sysPrint("totalTxnAmt >> $strTotalTxnAmt")
		return if (strTotalTxnAmt <= 0) "000" else strTotalTxnAmt.toString()
	}

	private fun printInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE
		var formTitle = "SETTLEMENT REPORT"
		if (previewSettlement == 1) {
			formTitle = "SALES SUMMARY"
		}

		if(settleInfo.isEmpty()) {
			helperLog.appendLine(helperLogClassName, "REJECT :: no last settlement record to print")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(mContext, "No Last Settlement Record", Toast.LENGTH_SHORT).show()
			return
		}

		val tags = arrayOf("Date/Time", "MERCHANT CODE", formTitle)
		val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
		val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")
		var entity = MulPrintStrEntity("", fontSize)
		entity.marginX = 50
		entity.gravity = Gravity.CENTER
		entity.isUnderline = true
		entity.yspace = 30
		list.add(entity)
		var mulPrintStrEntity = MulPrintStrEntity(dbModelMerchantConfig?.MerchantName ?: "", FontFamily.SMALL, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)
		mulPrintStrEntity = MulPrintStrEntity(dbModelMerchantConfig?.MerchantAddress ?: "", FontFamily.SMALL, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

		for (j in tags.indices) {
			if (j == 2) {
				list += MulPrintStrEntity(tags[j], FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD)
			} else {
				list += MulPrintStrEntity(
					Utils.makeLineText(
						Utils.TextItem(tags[j]).setFont(fontSize),
						Utils.TextItem(settleInfo[j]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
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
			if (count > 4) {
				count = 0
				list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))
			}
		}

		for (j in tags3.indices) {
			list += when (j) {
				0, 8 -> {
					(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
				}
				1, 7 -> {
					(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))
				}
				2 -> {
					MulPrintStrEntity(tags3[j], FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD)
				}
				else -> {
					MulPrintStrEntity(
						Utils.makeLineText(
							Utils.TextItem(tags3[j]).setFont(fontSize),
							Utils.TextItem(totalSettleInfo[j]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					)
				}
			}
		}
		list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
		entity = MulPrintStrEntity("", fontSize)
		var imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
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
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving QR settlement screen")
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	fun settle_btn_cancel(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: CANCEL [SETTLEMENT QR]")
		helperLog.appendLine(helperLogClassName, "User Cancel :: QR settlement abandoned")
		customOnBackPress()
	}

	fun settle_btn_ok(view: View?) {
		if (!isLastSettlement && previewSettlement == 0) {
			helperLog.appendLine(helperLogClassName, "Selected :: SETTLE [SETTLEMENT QR]")
			helperLog.appendLine(helperLogClassName, "-----------------QR Settlement Commit [START]-------------------->")
			val combined = settleInfo + totalSettleInfo
			Utils.write2File(combined, "qrlastsettlement.txt")
			printReceipt()
			TransactionQrRepo.truncateTable(mContext)
			helperLog.appendLine(helperLogClassName, "QR batch settled and cleared :: mid=${dbModelMerchantConfig?.QrMid ?: ""} saleCount=$SaleC saleTotal=${Utils.getActualAmount(SaleA.toString())} voidCount=$VoidC voidTotal=${Utils.getActualAmount(VoidA.toString())}")
			helperLog.appendLine(helperLogClassName, "-----------------QR Settlement Commit [END]-------------------->")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		} else {
			helperLog.appendLine(helperLogClassName, "Selected :: PRINT [SETTLEMENT QR]")
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

	override fun onResume() {
		super.onResume()
		val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
		if (myKM.isKeyguardLocked) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				myKM.requestDismissKeyguard(this@SettlementQrActivity, null)
			} else window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "SettlementQrActivity OnDestroy :: QR settlement screen closed")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	companion object {
		private const val TAG = "SettlementQr"
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}
	}
}