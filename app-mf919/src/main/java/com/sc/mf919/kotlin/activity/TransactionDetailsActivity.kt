package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.util.Locale


class TransactionDetailsActivity : ActivityBase() {
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	lateinit var mContext: Context
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom

	lateinit var mainReceipt: LinearLayout
	lateinit var scrollReceipt: ScrollView
	lateinit var acqSetting: AcquirerSettingModel
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
	private lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
	var txnList: List<DbModelPrintReceipt> = listOf()

	var txnDt = ""
	var rrn = ""
	var stan = ""
	var mid = ""
	var tid = ""
	var batchNo = ""
	var currAppLabel = ""
	var schemeIds = ""

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transaction_details)

		val toolbar = findViewById<Toolbar>(R.id.toolbarTxnDetails)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this@TransactionDetailsActivity, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		mContext = this@TransactionDetailsActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"TransactionDetailActivity"
		)
		helperLog.appendLine(helperLogClassName, "Initialize Transaction Details")


		dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
		if (dbModelMerchantConfig != null) {
			acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
		}
		acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
			AcquirerLogoDataEnum.from(dbModelMerchantConfig?.AcqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
		)

		mainReceipt = findViewById(R.id.mainLayout)
		scrollReceipt = findViewById(R.id.scrollReceipt)
		mid = dbModelMerchantConfig?.AcqMid.toString()
		tid = dbModelMerchantConfig?.AcqTid.toString()

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

		if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") {
			mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
			tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
		}
		IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", "visam")?.let {
			batchNo = it.value
		}

		acqSetting = ServiceHolder.getAcquirerSetting()
		val acqName = acqSetting.acqName
		if (acqName.uppercase(Locale.ENGLISH) == "GOBIZ") {
			tid = Utils.maskString(tid, 4)
			mid = Utils.maskString(mid, 4)
		}
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		initializeData()
		schemeSelectionDialog()
	}

	private fun initializeData() {
		schemeIds = ""
		currAppLabel = ""
	}

	private suspend fun getTrxnDetailsList() = withContext(Dispatchers.IO) {
		startProgressDialog(mContext, "Preview Details", "Loading...")
		txnList = PrintReceiptRepo.getMultipleRecordBySchemeIds(mContext, schemeIds)
		formLayout()
		delay(100)
		closeProgressDialog()
	}

	@SuppressLint("SetTextI18n")
	private fun  formLayout() {
		runOnUiThread {
			mainReceipt.removeAllViews()
			mainReceipt.orientation = LinearLayout.VERTICAL
		}
		var powerByBmp: Bitmap? = null
		val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
			Utils.debugLogPrint("TAG", "onCreate: image")
		}

		val headerImageView = ImageView(applicationContext)
		val headerImgParam = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 51f, resources.displayMetrics).toInt()
		)
		headerImageView.layoutParams = headerImgParam
		headerImageView.setPadding(0,10,0,10)
		if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") {
			if(powerByBmp != null) {
				headerImageView.setImageBitmap(powerByBmp)
			} else {
				headerImageView.setImageResource(R.mipmap.logo)
			}
		} else {
			headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
		}
		runOnUiThread {
			mainReceipt.addView(headerImageView)
		}

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
		runOnUiThread {
			mainReceipt.addView(name)
			mainReceipt.addView(addr)
		}

		val headerList: MutableList<Pair<String, String>> = mutableListOf(
			"MERCHANT ID" to mid,
			"TERMINAL ID" to tid,
			"BATCH NO" to batchNo,
		)
		if(currAppLabel.trim().isNotEmpty()){
			headerList.add("SCHEME" to currAppLabel)
		}

		val mainLLTopLabel = LinearLayout(applicationContext)
		mainLLTopLabel.orientation = LinearLayout.VERTICAL
		val mainLTopLabelParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		mainLTopLabelParams.setMargins(5,5,5,15)

		headerList.forEach { (key, keyValue) ->
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
			tv.setTextColor(ContextCompat.getColor(this, R.color.black))
			tv.text = key
			val tv1 = TextView(applicationContext)
			tv1.layoutParams = params1
			tv1.textSize = 11f
			tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
			tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
			tv1.text = keyValue
			ll.addView(tv)
			ll.addView(tv1)
			mainLLTopLabel.addView(ll)
		}
		runOnUiThread {
			mainReceipt.addView(mainLLTopLabel, mainLTopLabelParams)
		}

		val receiptTitle = TextView(applicationContext)
		receiptTitle.textSize = 14f
		receiptTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
		receiptTitle.setTextColor(ContextCompat.getColor(this, R.color.black))
		receiptTitle.setTypeface(receiptTitle.typeface, Typeface.BOLD_ITALIC)
		receiptTitle.text = "Transaction Details"
		receiptTitle.setPadding(5,10,5,10)
		runOnUiThread {
			mainReceipt.addView(receiptTitle)
		}

		if (currAppLabel.trim().isEmpty()) {
			val appLabel = TextView(applicationContext)
			appLabel.textSize = 11f
			appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
			appLabel.setTextColor(ContextCompat.getColor(this, R.color.black))
			appLabel.text = "SCHEME"
			runOnUiThread {
				mainReceipt.addView(appLabel)
			}
		}

		val labelList: MutableList<Pair<String, String>> = mutableListOf(
			"INV NO" to "DATE/TIME",
			"TYPE" to "APPROVAL CODE",
			"CARD NO" to "TOTAL",
		)
		val mainLLLabel = LinearLayout(applicationContext)
		mainLLLabel.orientation = LinearLayout.VERTICAL
		val mainLLabelParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		mainLLabelParams.setMargins(5,5,5,15)
		labelList.forEach{(labelKey, labelValue) ->
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
			tv.setTextColor(ContextCompat.getColor(this, R.color.black))
			tv.text = labelKey
			val tv1 = TextView(applicationContext)
			tv1.layoutParams = params1
			tv1.textSize = 11f
			tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
			tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
			tv1.text = labelValue
			ll.addView(tv)
			ll.addView(tv1)
			mainLLLabel.addView(ll)
		}
		runOnUiThread {
			mainReceipt.addView(mainLLLabel, mainLLabelParams)
		}

		for(printReceiptItem in txnList) {
			if (currAppLabel.trim().isEmpty()) {
				val appLabel = TextView(applicationContext)
				appLabel.textSize = 11f
				appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
				appLabel.setTextColor(ContextCompat.getColor(this, R.color.black))
				appLabel.text = Utils.getSchemeName(printReceiptItem.schemeId)
				appLabel.setPadding(0, 5, 0, 0)
				runOnUiThread {
					mainReceipt.addView(appLabel)
				}
			}

			val txnDetails: MutableList<Pair<String, String>> = mutableListOf(
				printReceiptItem.invoiceNo to Utils.DateTimeFormat(printReceiptItem.txnDt),
				printReceiptItem.txnType to printReceiptItem.txnApprCode,
				printReceiptItem.cardMasked to Utils.getActualAmount(printReceiptItem.txnAmt),
			)
			val mainLLTxnList = LinearLayout(applicationContext)
			mainLLTxnList.orientation = LinearLayout.VERTICAL
			val mainLTxnListParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.MATCH_PARENT
			)
			mainLTxnListParams.setMargins(5,5,5,5)

			txnDetails.forEach{(labelKey, labelValue) ->
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
				tv.setTextColor(ContextCompat.getColor(this, R.color.black))
				tv.text = labelKey
				val tv1 = TextView(applicationContext)
				tv1.layoutParams = params1
				tv1.textSize = 11f
				tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
				tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
				tv1.text = labelValue
				ll.addView(tv)
				ll.addView(tv1)
				mainLLTxnList.addView(ll)
			}
			runOnUiThread {
				mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
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
		power.setTextColor(ContextCompat.getColor(this, R.color.black))

		val im = ImageView(applicationContext)
		val params1 = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
		)
		im.layoutParams = params1
		im.adjustViewBounds = true

		if(!isHideBottom) {
			if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "false") {
				if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
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
			}
		}
		runOnUiThread {
			mainReceipt.addView(footer)
		}
	}

	private fun printInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE

		val labelList: MutableList<Pair<String, String>> = mutableListOf(
			"INV NO" to "DATE/TIME",
			"TYPE" to "APPROVAL CODE",
			"CARD NO" to "TOTAL",
		)
		var entity = MulPrintStrEntity("", fontSize)

		var imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
		if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") {
			val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
			val imgFile = File(paths)
			if (imgFile.exists()) {
				imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
			}
		}
		entity.bitmap = imageFromAssetsFile
		entity.marginX = 50
		entity.gravity = Gravity.CENTER
		entity.isUnderline = true
		entity.yspace = 30
		list.add(entity)

		var mulPrintStrEntity = MulPrintStrEntity(dbModelMerchantConfig?.MerchantName ?: "", FontFamily.SMALL, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)
		mulPrintStrEntity = MulPrintStrEntity(dbModelMerchantConfig?.MerchantAddress ?: "", FontFamily.SMALL, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		val headerList: MutableList<Pair<String, String>> = mutableListOf(
			"MERCHANT ID" to mid,
			"TERMINAL ID" to tid,
			"BATCH NO" to batchNo,
		)
		if(currAppLabel.trim().isNotEmpty()){
			headerList.add("SCHEME" to currAppLabel)
		}
		headerList.forEach { (key, keyValue) ->
			list += MulPrintStrEntity(
				Utils.makeLineText(
					Utils.TextItem(key).setFont(fontSize),
					Utils.TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		}
		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		mulPrintStrEntity = MulPrintStrEntity("Transaction Details", FontFamily.BIG, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		if (currAppLabel == "" || currAppLabel.isNullOrEmpty()) {
			list.add(MulPrintStrEntity("SCHEME", FontFamily.MIDDLE, false, Gravity.CENTER))
		}
		labelList.forEach { (labelKey, labelValue) ->
			list += MulPrintStrEntity(
				Utils.makeLineText(
					Utils.TextItem(labelKey).setFont(fontSize),
					Utils.TextItem(labelValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		}
		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		for(printReceiptItem in txnList){
			if(currAppLabel.trim().isEmpty()) {
				list.add(MulPrintStrEntity(Utils.getSchemeName(printReceiptItem.schemeId), FontFamily.MIDDLE, false, Gravity.CENTER))
			}

			val txnDetails: MutableList<Pair<String, String>> = mutableListOf(
				printReceiptItem.invoiceNo to Utils.DateTimeFormat(printReceiptItem.txnDt),
				printReceiptItem.txnType to printReceiptItem.txnApprCode,
				printReceiptItem.cardMasked to Utils.getActualAmount(printReceiptItem.txnAmt),
			)
			txnDetails.forEach { (detailKey, detailvalue) ->
				list += MulPrintStrEntity(
					Utils.makeLineText(
						Utils.TextItem(detailKey.toString()).setFont(fontSize),
						Utils.TextItem(detailvalue.toString()).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
					), fontSize
				)
			}
			list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
		}

		if(!isHideBottom) {
			if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "false") {
				if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
					//if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
					if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
						list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))

						entity = MulPrintStrEntity("", fontSize)
						imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
						entity.bitmap = imageFromAssetsFile
						entity.marginX = 50
						entity.gravity = Gravity.CENTER
						entity.isUnderline = true
						entity.yspace = 30
						list.add(entity)
					}
				} else {
					list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))

					entity = MulPrintStrEntity("", fontSize)
					imageFromAssetsFile =
						PrintsActivity.getImageFromAssetsFile(this, "image/logo_footer_small.bmp")
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
			}
		}

		entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun printReceipt() {
		val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
//		val scrollReceipt : ScrollView = findViewById(R.id.scrollReceipt)
		scrollReceipt.startAnimation(animSlideDown)

		printInfo()
	}

	fun print_detail_btn_cancel(view: View?) {
		finish()
	}

	fun print_detail_btn_ok(view: View?) {
		printReceipt()
	}

	private fun schemeSelectionDialog() {
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_schemeselection, null
		)

		dialogView.findViewById<LinearLayout>(R.id.cancel_btn).apply {
			setOnClickListener {
				if (this@TransactionDetailsActivity::helperLog.isInitialized) {
					helperLog.appendLine(helperLogClassName, "User Cancel :: scheme filter dismissed, leaving details")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
				}
				finish()
			}
		}

		dialogView.findViewById<View>(R.id.all_scheme).apply {
			setOnClickListener {
				// ALL
				schemeIds = ""
				currAppLabel = ""
				alertDialog?.dismiss()
				lifecycleScope.launch {
					getTrxnDetailsList()
				}
			}
		}

		dialogView.findViewById<View>(R.id.visa_scheme).apply {
			setOnClickListener {
				// VISA
				schemeIds = "11, 12, 91"
				currAppLabel = "VISA"
				alertDialog?.dismiss()
				lifecycleScope.launch {
					getTrxnDetailsList()
				}
			}
		}

		dialogView.findViewById<View>(R.id.master_scheme).apply {
			setOnClickListener {
				// MASTER
				schemeIds = "20, 21, 22, 92"
				currAppLabel = "MASTER"
				alertDialog?.dismiss()
				lifecycleScope.launch {
					getTrxnDetailsList()
				}
			}
		}

		val terminalConfig = getTerminalConfig()
		val optIn = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")
		if(optIn) {
			dialogView.findViewById<View>(R.id.mydebit_scheme).apply {
				visibility = View.VISIBLE
				setOnClickListener {
					// MYDEBIT
					schemeIds = "81, 98, 82, 99"
					currAppLabel = "MyDebit"
					alertDialog?.dismiss()
					lifecycleScope.launch {
						getTrxnDetailsList()
					}
				}
			}
		}

		if(acqSetting.acqName.uppercase(Locale.ENGLISH).equals("GOBIZ", true)) {
			dialogView.findViewById<View>(R.id.union_scheme).apply {
				visibility = View.VISIBLE
				setOnClickListener {
					// UNIONPAY
					schemeIds = "31, 93"
					currAppLabel = "UnionPay"
					alertDialog?.dismiss()
					lifecycleScope.launch {
						getTrxnDetailsList()
					}
				}
			}
		}

		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	companion object {
		private const val TAG = "ISODB"
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}
	}

	fun customOnBackPress() {
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "TransactionDetailsActivity OnDestroy :: transaction details ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}
