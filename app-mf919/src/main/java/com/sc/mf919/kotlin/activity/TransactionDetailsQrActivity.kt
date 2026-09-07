package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelQrPayTable
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumCustomization
import enums.EnumCustomizationAcq
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.models.EWProducts
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class TransactionDetailsQrActivity : ActivityBase() {
	lateinit var mContext: Context
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom

	lateinit var mainReceipt: LinearLayout
	lateinit var headerText: TextView
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	var txnDt = ""
	var rrn = ""
	var stan = ""
	var mid = ""
	var tid = ""
	var batchNo = ""
	//lateinit var txnList: Array<String?>
	lateinit var txnList: List<DbModelTransactionQrGet>
	var isRHB = false;

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transaction_details)

		val toolbar = findViewById<Toolbar>(R.id.toolbarTxnDetails)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this@TransactionDetailsQrActivity, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})


		mContext = this
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"QR Transaction Details Preview / Print"
		)
		helperLog.appendLine(helperLogClassName, "QR transaction details opened")
		mainReceipt = findViewById(R.id.mainLayout)
		headerText = findViewById(R.id.textHeader)
		headerText.text = "Transaction Details QR"

		dbModelMerchantConfig = ServiceHolder.getMerchantInfo()

		val customizationList = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "Customization")
		if (customizationList.isNotEmpty()) {
			val customizationArray = Gson().fromJson(customizationList, Array<String>::class.java).asList()
			if (customizationArray.filter { a -> a.contains(EnumCustomizationAcq.RHB.value)}.isNotEmpty()) {
				isRHB = true;
			}
		}

		SelectionDialog_txnViewer()
	}

	private fun getTrxnDetailsList(productNames: List<String>?) {
		startProgressDialog(mContext, "Preview Details", "Loading...")
		//txnList = isoDb.getQrPayTables(productNames)
		txnList = TransactionQrRepo.getListTransactionQrNoCase(applicationContext, productNames)
		helperLog.appendLine(helperLogClassName, "QR details loaded :: filter ${productNames?.joinToString(",") ?: "ALL"}, ${txnList.size} transaction(s)")
		formLayout()
		closeProgressDialog()
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	@SuppressLint("SetTextI18n")
	private fun formLayout() {
		mainReceipt.removeAllViews()

		mainReceipt.orientation = LinearLayout.VERTICAL

		val name = TextView(applicationContext)
		name.textSize = 9f
		name.textAlignment = View.TEXT_ALIGNMENT_CENTER
		name.setTextColor(ContextCompat.getColor(this, R.color.black))
		name.text = dbModelMerchantConfig?.MerchantName ?: ""

		val addr = TextView(applicationContext)
		addr.textSize = 9f
		addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
		addr.setTextColor(ContextCompat.getColor(this, R.color.black))
		addr.text = dbModelMerchantConfig?.MerchantAddress ?: ""

		mainReceipt.addView(name)
		mainReceipt.addView(addr)

		val receiptTitle = TextView(applicationContext)
		receiptTitle.textSize = 14f
		receiptTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
		receiptTitle.setTextColor(ContextCompat.getColor(this, R.color.black))
		receiptTitle.setTypeface(receiptTitle.typeface, Typeface.BOLD_ITALIC)
		receiptTitle.text = "Transaction Details"
		receiptTitle.setPadding(5,10,5,10)

		mainReceipt.addView(receiptTitle)

		var tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET")
		var tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT")

		if (isRHB) {
			tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET", "HOST REF")
			tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT", "")
		}

		val mainLLTopLabel = LinearLayout(applicationContext)
		mainLLTopLabel.orientation = LinearLayout.VERTICAL
		val mainLTopLabelParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		mainLTopLabelParams.setMargins(5,5,5,15)

		for (j in tagsLabel1.indices) {
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
			tv.text = tagsLabel1[j]
			val tv1 = TextView(applicationContext)
			tv1.layoutParams = params1
			tv1.textSize = 11f
			tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
			tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
			tv1.text = tagsLabel2[j]
			ll.addView(tv)
			ll.addView(tv1)
			mainLLTopLabel.addView(ll)
		}
		mainReceipt.addView(mainLLTopLabel, mainLTopLabelParams)


		if (txnList.isNotEmpty()) {
			txnList.forEach{
				var dataVal1 = arrayOf(it.refId, it.txnType, it.productCode)
				var dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount))

				if (isRHB) {
					dataVal1 = arrayOf(it.refId, it.txnType, it.productCode, it.hostRefNo)
					dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount), "")
				}

				val mainLLTxnList = LinearLayout(applicationContext)
				mainLLTxnList.orientation = LinearLayout.VERTICAL
				val mainLTxnListParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.MATCH_PARENT
				)
				mainLTxnListParams.setMargins(5,5,5,5)

				for (j in dataVal1.indices) {
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
					tv.text = dataVal1[j]
					val tv1 = TextView(applicationContext)
					tv1.layoutParams = params1
					tv1.textSize = 11f
					tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
					tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
					tv1.text = dataVal2[j]
					ll.addView(tv)
					ll.addView(tv1)
					mainLLTxnList.addView(ll)
				}

				mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
			}
			/*for (i in txnList.indices) {
				//var t = Gson().fromJson(txnList[i], DbModelQrPayTable::class.java)
				var dataVal1 = arrayOf(t.refId, t.txnType, t.productCode)
				var dataVal2 = arrayOf("", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?))

				if (isRHB) {
					dataVal1 = arrayOf(t.refId, t.txnType, t.productCode, t.hostRefNo)
					dataVal2 = arrayOf("", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?), "")
				}

				val mainLLTxnList = LinearLayout(applicationContext)
				mainLLTxnList.orientation = LinearLayout.VERTICAL
				val mainLTxnListParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.MATCH_PARENT
				)
				mainLTxnListParams.setMargins(5,5,5,5)

				for (j in dataVal1.indices) {
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
					tv.text = dataVal1[j]
					val tv1 = TextView(applicationContext)
					tv1.layoutParams = params1
					tv1.textSize = 11f
					tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
					tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
					tv1.text = dataVal2[j]
					ll.addView(tv)
					ll.addView(tv1)
					mainLLTxnList.addView(ll)
				}

				mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
			}*/
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
			}
			footer.addView(im)
		}
		mainReceipt.addView(footer)
	}

	private fun printInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE

		var tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET")
		var tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT")

		if (isRHB) {
			tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET", "HOST REF")
			tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT", "")
		}

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

		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		mulPrintStrEntity = MulPrintStrEntity("Transaction Details", FontFamily.BIG, false, Gravity.CENTER)
		list.add(mulPrintStrEntity)

		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		for (j in tagsLabel1.indices) {
			list += MulPrintStrEntity(
				Utils.makeLineText(
					Utils.TextItem(tagsLabel1[j]).setFont(fontSize),
					Utils.TextItem(tagsLabel2[j]).setFont(fontSize)
						.setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		}

		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
		if(txnList.isNotEmpty()) {
			txnList.forEach{
				var dataVal1 = arrayOf(it.refId, it.txnType, it.productCode)
				var dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount))

				if (isRHB) {
					dataVal1 = arrayOf(it.refId, it.txnType, it.productCode, it.hostRefNo)
					dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount), "")
				}

				for (h in dataVal1.indices) {
					list += MulPrintStrEntity(
						Utils.makeLineText(
							Utils.TextItem(dataVal1[h]).setFont(fontSize),
							Utils.TextItem(dataVal2[h]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					)
				}

				list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
			}
		}

		//TODO
		/*if (txnList.isNotEmpty() && txnList != null) {
			for (j in txnList.indices) {
				var t = Gson().fromJson(txnList[j], DbModelQrPayTable::class.java)

				var dataVal1 = arrayOf(t.refId, t.txnType, t.productCode)
				var dataVal2 = arrayOf(
					"", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?)
				)

				if (isRHB) {
					dataVal1 = arrayOf(t.refId, t.txnType, t.productCode, t.hostRefNo)
					dataVal2 = arrayOf(
						"", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?), ""
					)
				}

				for (h in dataVal1.indices) {
					list += MulPrintStrEntity(
						Utils.makeLineText(
							Utils.TextItem(dataVal1[h] as String?).setFont(fontSize),
							Utils.TextItem(dataVal2[h] as String?).setFont(fontSize)
								.setPaddingAlign(Gravity.RIGHT)
						), fontSize
					)
				}

				list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
			}
		}*/

		if(!isHideBottom) {
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
		}
		entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun printReceipt() {
		val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
		val scrollReceipt : ScrollView = findViewById(R.id.scrollReceipt)
		scrollReceipt.startAnimation(animSlideDown)

		printInfo()
	}

	fun print_detail_btn_cancel(view: View?) {
		helperLog.appendLine(helperLogClassName, "User Cancel :: closed QR transaction details preview")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		finish()
	}

	fun print_detail_btn_ok(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: Print [QR TRANSACTION DETAILS] :: ${txnList.size} transaction(s)")
		printReceipt()
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun SelectionDialog_txnViewer() {
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PAYMENT METHOD]")
		val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_dynamic_selection, null
		)

		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val ewProductList = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "EWALLET_PRODUCT_LIST")

		val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
		cancelBtn.setOnClickListener {
			helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PAYMENT METHOD]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			finish()
		}

		val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)

		val dynamicTitle = dialogView.findViewById<TextView>(R.id.dynamic_title)
		dynamicTitle.text = "SELECT PAYMENT METHOD"

		val scrollView = ScrollView(applicationContext)
		val scrollViewParam = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		scrollView.layoutParams = scrollViewParam

		val mainLV = LinearLayout(applicationContext)
		val mainLVParam = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		mainLV.layoutParams = mainLVParam
		mainLV.orientation = LinearLayout.VERTICAL
		scrollView.addView(mainLV)

		val childLinear = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			getInstance().getDpValue(250), getInstance().getDpValue(57)
		)
		params.setMargins(0, 0, 0, getInstance().getDpValue(15))
		params.gravity = Gravity.CENTER
		childLinear.gravity = Gravity.CENTER_VERTICAL
		childLinear.setBackgroundResource(R.drawable.other_btn)
		childLinear.orientation = LinearLayout.HORIZONTAL
		childLinear.layoutParams = params

		val tv = TextView(applicationContext)
		tv.text = "ALL"
		tv.gravity = Gravity.CENTER_HORIZONTAL
		tv.setTextColor(resources.getColor(R.color.black))
		tv.textSize = 16f
		val paramsTV = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		tv.layoutParams = paramsTV
		childLinear.addView(tv)

		childLinear.setOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: ALL [SELECT PAYMENT METHOD]")
			getTrxnDetailsList(null)
			alertDialog?.dismiss()
		}

		mainLV.addView(childLinear)

		if (!ewProductList.isNullOrEmpty()) {
			val ewArray = Gson().fromJson(ewProductList, Array<EWProducts>::class.java).asList()
				.sortedBy { x -> x.PRODUCT_SEQ }
			for (i in ewArray.indices) {
				val childLinear = LinearLayout(applicationContext)
				val params = LinearLayout.LayoutParams(
					getInstance().getDpValue(250), getInstance().getDpValue(57)
				)
				params.setMargins(0, 0, 0, getInstance().getDpValue(15))
				params.gravity = Gravity.CENTER
				childLinear.gravity = Gravity.CENTER_VERTICAL
				childLinear.setBackgroundResource(R.drawable.other_btn)
				childLinear.orientation = LinearLayout.HORIZONTAL
				childLinear.layoutParams = params

				val tv = TextView(applicationContext)
				tv.text = ewArray[i].PRODUCT_CODE.uppercase()
				tv.gravity = Gravity.CENTER_HORIZONTAL
				tv.setTextColor(resources.getColor(R.color.black))
				tv.textSize = 16f
				val paramsTV = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
				)
				tv.layoutParams = paramsTV
				childLinear.addView(tv)

				childLinear.setOnClickListener {
					helperLog.appendLine(helperLogClassName, "Selected :: ${ewArray[i].PRODUCT_CODE.uppercase()} [SELECT PAYMENT METHOD]")
					getTrxnDetailsList(ewArray[i].PRODUCT_NAME)
					alertDialog?.dismiss()
				}

				mainLV.addView(childLinear)
			}
		}

		mainContainer.addView(scrollView)

		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog = alertDialogBuilder.create()
		alertDialog?.show()
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: left QR transaction details, navigate -> home screen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "TransactionDetailsQr OnDestroy :: QR transaction details ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}
