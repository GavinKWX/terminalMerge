package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.*
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class EppProductActivity : BaseActivity() {
	lateinit var mContext: Context
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	lateinit var acquirerScroll: ScrollView
	lateinit var selectedModel: SaleModelNew
	lateinit var txnAmount: String
	var posReference: String? = null
	var txnType: Int = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		mContext = applicationContext
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"EPP Tenure Selection"
		)
		helperLog.appendLine(helperLogClassName, "EPP tenure selection opened")
		setContentView(R.layout.activity_epp_acquirer)
		val toolbar = findViewById<Toolbar>(R.id.toolbarEppAcq)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		acquirerScroll = findViewById(R.id.epp_acquirer_scroll)
		txnAmount = intent.getStringExtra("txnAmt") ?: ""
		txnType = intent.getIntExtra("typeofSale", 0)
		posReference = intent.getStringExtra("posReference")

		ServiceHolder.saleModelCache?.let {
			selectedModel = it
			helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
			helperLog.appendLine(helperLogClassName, "EPP acquirer :: ${selectedModel.AcqCode ?: "-"}")
			renderInformationHeader()
			renderDynamicProduct()
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		} ?: run {
			runOnUiThread {
				Toast.makeText(applicationContext, "Invalid EPP Process", Toast.LENGTH_SHORT).show()
			}
			helperLog.appendLine(helperLogClassName, "REJECT :: no sale model cached, invalid EPP process, navigate -> home screen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(newIntent)
			finish()
		}
	}

	private fun renderInformationHeader() {
		val infoLinearLayout = findViewById<LinearLayout>(R.id.epp_acquirer_header)

		var infoTV = TextView(mContext)
		var bankName = ""
		try {
			val acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(selectedModel.AcqCode ?: "").data
			bankName = acqLogoEnumModel.AcquirerName
		} catch (e: Exception) {
			e.printStackTrace()
		}

		infoTV.text = "Bank: ${bankName}"
		infoTV.setPadding(
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(5)
		)
		infoTV.setTypeface(infoTV.typeface, Typeface.BOLD)
		infoTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		infoLinearLayout.addView(infoTV)

		infoTV = TextView(mContext)
		infoTV.text = "Amount: RM${txnAmount}"
		infoTV.setPadding(
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(5),
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
		)
		infoTV.setTypeface(infoTV.typeface, Typeface.BOLD)
		infoTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		infoLinearLayout.addView(infoTV)

		val seperatorLL = LinearLayout(mContext)
		val seperatorLinearParam = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(40)
		)
		seperatorLL.setBackgroundResource(R.color.BoxColor)
		seperatorLL.gravity = Gravity.CENTER
		seperatorLL.layoutParams = seperatorLinearParam
		val seperateTV = TextView(mContext)
		seperateTV.text = "Please Select Tenure"
		seperateTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
		seperateTV.setTextColor(ContextCompat.getColor(mContext, R.color.white))
		seperateTV.setTypeface(seperateTV.typeface, Typeface.BOLD)
		seperatorLL.addView(seperateTV)
		infoLinearLayout.addView(seperatorLL)
	}

	private fun renderDynamicProduct() {
		val eppProductList = ProductListRepo.getSelectedProduct(
			mContext, mutableListOf("Product", "AcqCode"), arrayOf("EPP", selectedModel.AcqCode!!)
		)

		if (eppProductList.isNotEmpty()) {
			val mainLinear = LinearLayout(applicationContext)
			val mainLinearParam = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			mainLinearParam.setMargins(
				Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
				Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
			)
			mainLinear.layoutParams = mainLinearParam
			mainLinear.orientation = LinearLayout.VERTICAL

			var rowLinear = LinearLayout(applicationContext)
			var count = eppProductList.size
			val rowItem = 2

			try {
				for (i in eppProductList.indices) {
					val modelData = eppProductList[i]

					if (i % rowItem == 0) {
						rowLinear = LinearLayout(applicationContext)
						val rowLinearParam = LinearLayout.LayoutParams(
							LinearLayout.LayoutParams.MATCH_PARENT,
							LinearLayout.LayoutParams.WRAP_CONTENT
						)

						if (i >= rowItem) {
							rowLinear.background = ResourcesCompat.getDrawable(
								resources, R.drawable.border_top, theme
							)
						}
						rowLinear.layoutParams = rowLinearParam
						rowLinear.orientation = LinearLayout.HORIZONTAL
						rowLinear.gravity = Gravity.CENTER
					}

					val productLV = LinearLayout(applicationContext)
					val productLVParam = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)
					productLVParam.weight = 1f
					productLV.setPadding(
						Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(25),
						Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(25)
					)

					if (i % rowItem == 0) {
						productLV.background = ResourcesCompat.getDrawable(
							resources, R.drawable.border_right, theme
						)
					}
					productLV.gravity = Gravity.CENTER
					productLV.layoutParams = productLVParam

					val productTV = TextView(applicationContext)
					productTV.layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)
					productTV.gravity = Gravity.CENTER
					productTV.text = modelData.EppTenure
					productTV.setTypeface(productTV.typeface, Typeface.BOLD)
					productTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20F)
					productLV.addView(productTV)
					productLV.setOnClickListener {
						val salesModel = SalesModel(
							txnType,
							modelData.Product,
							modelData.AcqCode,
							modelData.AcqMid,
							modelData.AcqTid,
							modelData.QrProductCode,
							modelData.ProductName,
							modelData.EppProductCode,
							modelData.EppTenure,
							modelData.EppTenureCode
						)
						ServiceHolder.selectedCacheModel = salesModel
						val jsonProductList = Gson().toJson(modelData)
						val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
						saleModelNew.SalesType = txnType
						ServiceHolder.saleModelCache = saleModelNew

						helperLog.appendLine(helperLogClassName, "Selected :: tenure ${modelData.EppTenure} (${modelData.EppProductCode}) [SELECT TENURE]")
						helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val newIntent = Intent(applicationContext, CardPaymentActivity::class.java)
						newIntent.putExtra("txnAmt", txnAmount)
						newIntent.putExtra("typeofSale", txnType)
						newIntent.putExtra("posReference", posReference)
						newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(newIntent)
					}

					count--
					rowLinear.addView(productLV)
					if ((i + 1) % rowItem == 0 || count == 0) {
						mainLinear.addView(rowLinear)
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
				helperLog.appendLine(helperLogClassName, "Exception in Rendering EPP Tenure List -> ${e.message ?: "-"}")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
			}
			acquirerScroll.addView(mainLinear)
		} else {
			helperLog.appendLine(helperLogClassName, "REJECT :: no EPP tenure configured for acquirer ${selectedModel.AcqCode ?: "-"}")
		}
	}

	fun btnCancel(view: View) {
		helperLog.appendLine(helperLogClassName, "Cancel button pressed")
		customOnBackPress()
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: abandoned EPP tenure selection (amount $txnAmount)")
		if(ServiceHolder.appHTTP || ServiceHolder.appIntent){
			helperLog.appendLine(helperLogClassName, "User Cancel :: navigate -> EppAcquirerActivity")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			val newIntent = Intent(applicationContext, EppAcquirerActivity::class.java)
			newIntent.putExtra("txnAmt", txnAmount)
			newIntent.putExtra("posReference", posReference)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(newIntent)
		} else {
			helperLog.appendLine(helperLogClassName, "User Cancel :: navigate -> KeypadActivity")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			val newIntent = Intent(applicationContext, KeypadActivity::class.java)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(newIntent)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "EppProduct OnDestroy :: EPP tenure selection ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}