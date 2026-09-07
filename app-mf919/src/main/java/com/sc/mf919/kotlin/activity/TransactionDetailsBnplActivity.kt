package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelPrintReceiptBnpl
import com.sc.mf919.kotlin.database.repo.PrintReceiptBnplRepo
import com.sc.mf919.kotlin.helper_common.*
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import enums.EnumLogFileName
import helpers.HelperCommon
import kotlinx.coroutines.*
import tms.models.ioupay.IoupayScanEnquiryResponseModel
import java.io.File
import java.util.*

class TransactionDetailsBnplActivity : ActivityBase() {
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom
	lateinit var mContext: Context

	lateinit var mainReceipt: LinearLayout
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	private lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

	var txnDt = ""
	var rrn = ""
	var stan = ""
	var mid = ""
	var tid = ""
	var batchNo = ""
	var txnList: List<DbModelPrintReceiptBnpl> = emptyList()
	private var helperlogClassName:String = ""
	lateinit var helperLog: helpers.HelperLog

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transaction_details)
		mContext = this

		helperlogClassName = this::class.qualifiedName.toString()

		helperLog = helpers.HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			"TransactionDetailsBnplActivity",
			TransactionDetailsBnplActivity::class.java.simpleName,
			TransactionDetailsBnplActivity::class.java.simpleName,
		)
		helperLog.appendLine(helperlogClassName, "BNPL transaction details preview opened")

		mainReceipt = findViewById(R.id.mainLayout)

		dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
		acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
			AcquirerLogoDataEnum.from(dbModelMerchantConfig?.AcqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
		)

		getTrxnDetailsList()
	}

	private fun getTrxnDetailsList() {
		startProgressDialog(mContext, "Preview Details", "Loading...")
		txnList = PrintReceiptBnplRepo.getAllOrdered(applicationContext)
		helperLog.appendLine(helperlogClassName, "BNPL details loaded :: records=${txnList.size}")
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
		receiptTitle.text = "BNPL Transaction Details"
		receiptTitle.setPadding(5,10,5,10)

		mainReceipt.addView(receiptTitle)

		val appLabel = TextView(applicationContext)
		appLabel.textSize = 11f
		appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
		appLabel.setTextColor(ContextCompat.getColor(this, R.color.black))
		appLabel.text = "BNPL ACQUIRER"
		mainReceipt.addView(appLabel)

		val tagsLabel1 = arrayOf("DATE/TIME", "REF ID", "TXN ID")
		val tagsLabel2 = arrayOf("TENURE", "1st PAYMENT", "TOTAL AMOUNT")

		val mainLLLabel = LinearLayout(applicationContext)
		mainLLLabel.orientation = LinearLayout.VERTICAL
		val mainLLabelParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.MATCH_PARENT
		)
		mainLLabelParams.setMargins(5,5,5,15)

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
			mainLLLabel.addView(ll)
		}
		mainReceipt.addView(mainLLLabel, mainLLabelParams)


		if (txnList.isNotEmpty() && txnList != null) {
			for (i in txnList.indices) {
				val txnData = txnList[i]
				var bnplResp = Gson().fromJson(txnData.bnplResp.toString(), IoupayScanEnquiryResponseModel::class.java)

				var initAmt = "0";
				if (bnplResp != null) {
					initAmt = bnplResp.SCHEDULE_LIST?.get(0)?.SCHEDULE_AMOUNT_STRING.toString()
				}

				val appLabel = TextView(applicationContext)
				appLabel.textSize = 11f
				appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
				appLabel.setTextColor(ContextCompat.getColor(this, R.color.black))
				appLabel.text = txnData.payBrandDesc as String?
				appLabel.setPadding(0,5,0,0)
				mainReceipt.addView(appLabel)

				val dataVal1 = arrayOf(txnData.txnDt, txnData.refId, txnData.hostRefNo)
				val dataVal2 = arrayOf(txnData.tenureDesc, "RM$initAmt", "RM" + Utils.getActualAmount(txnData.txnAmt as String?))

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
					tv.text = dataVal1[j] as CharSequence?
					val tv1 = TextView(applicationContext)
					tv1.layoutParams = params1
					tv1.textSize = 11f
					tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
					tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
					tv1.text = dataVal2[j] as CharSequence?
					ll.addView(tv)
					ll.addView(tv1)
					mainLLTxnList.addView(ll)
				}

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
				Utils.debugLogPrint("TAG", "onCreate: image")
			}
			footer.addView(im)
		}
		mainReceipt.addView(footer)
	}

	private fun printInfo() {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE

		val tagsLabel1 = arrayOf("DATE/TIME", "REF ID", "TXN ID")
		val tagsLabel2 = arrayOf("TENURE", "1st PAYMENT", "TOTAL AMOUNT")

		var entity = MulPrintStrEntity("", fontSize)
		var imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(
			applicationContext, acqLogoEnumModel.HeaderLogoBmp
		)
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

		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		mulPrintStrEntity = MulPrintStrEntity(
			"BNPL Transaction Details", FontFamily.BIG, false, Gravity.CENTER
		)
		list.add(mulPrintStrEntity)

		list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

		list.add(MulPrintStrEntity("BNPL ACQUIRER", FontFamily.MIDDLE, false, Gravity.CENTER))

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

		if (txnList.isNotEmpty() && txnList != null) {
			for (j in txnList.indices) {
				val txnData = txnList[j]
				var bnplResp = Gson().fromJson(txnData.bnplResp.toString(), IoupayScanEnquiryResponseModel::class.java)

				var initAmt = "0";
				if (bnplResp != null) {
					initAmt = bnplResp.SCHEDULE_LIST?.get(0)?.SCHEDULE_AMOUNT_STRING.toString()
				}

				list.add(MulPrintStrEntity(txnData.acqCode.toString(), FontFamily.MIDDLE, false, Gravity.CENTER))

				val dataVal1 = arrayOf(txnData.txnDt, txnData.refId, txnData.hostRefNo)
				val dataVal2 = arrayOf(txnData.tenureDesc, "RM$initAmt", "RM" + Utils.getActualAmount(txnData.txnAmt as String?))

				for (h in dataVal1.indices) {
					list += MulPrintStrEntity(
						Utils.makeLineText(
							Utils.TextItem(dataVal1[h].toString()).setFont(fontSize),
							Utils.TextItem(dataVal2[h].toString()).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					)
				}

				list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
			}
		}

		if(!isHideBottom) {
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
		entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
		entity.gravity = Gravity.CENTER
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	private fun printReceipt() {
		helperLog.appendLine(helperlogClassName, "Print pressed :: BNPL details receipt, records=${txnList.size}")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
		val scrollReceipt : ScrollView = findViewById(R.id.scrollReceipt)
		scrollReceipt.startAnimation(animSlideDown)

		printInfo()
	}

	fun print_detail_btn_cancel(view: View?) {
		helperLog.appendLine(helperlogClassName, "User Cancel :: abandoned BNPL details preview")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		onBackPressed()
	}

	fun print_detail_btn_ok(view: View?) {
		helperLog.appendLine(helperlogClassName, "Selected :: OK [BNPL DETAILS PREVIEW]")
		printReceipt()
	}

	override fun onDestroy() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "TransactionDetailsBnpl OnDestroy :: BNPL details preview ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		super.onDestroy()
	}

	companion object {
		private const val TAG = "ISODB"
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}
	}
}
