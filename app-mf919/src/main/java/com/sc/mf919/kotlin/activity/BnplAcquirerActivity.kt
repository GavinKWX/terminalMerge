package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919.R
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import data_enum.SalesModel
import com.sc.mf919.java.activity.Utils
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import java.util.*

class BnplAcquirerActivity : BaseActivity() {
	lateinit var acquirerScroll: ScrollView
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_bnpl_acquirer)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"BNPL Acquirer Selection"
		)
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT BNPL ACQUIRER]")
		val toolbar = findViewById<Toolbar>(R.id.toolbarBnplAcq)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }

		acquirerScroll = findViewById(R.id.bnpl_acquirer_scroll)
		renderDynamicAcquirer()

		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
	}

	private fun renderDynamicAcquirer() {
		val listAcquirer = ProductListRepo.getSelectedProduct(
			applicationContext,
			ArrayList(listOf("Product", "AcqCode", "QrProductCode")),
			arrayOf("BNPL", "IOUPAY", "BNPL_MYIOU")
		)

		helperLog.appendLine(helperLogClassName, "BNPL acquirers available :: ${listAcquirer.size}")
		if (listAcquirer.isNotEmpty()) {
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
			var count = listAcquirer.size
			val rowItem = 1

			try {
				for (i in listAcquirer.indices) {
					val modelData = listAcquirer[i]
					val tempAcquirerModel = AcquirerLogoDataEnum.valueOf(
						modelData.AcqCode as String
					).data

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

					val acquirerLV = LinearLayout(applicationContext)
					val acquirerLVParam = LinearLayout.LayoutParams(
						Helper.getInstance().getDpValue(0), LinearLayout.LayoutParams.WRAP_CONTENT
					)
					acquirerLVParam.weight = 1f
					acquirerLVParam.setMargins(
						Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
						Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
					)
					acquirerLV.gravity = Gravity.CENTER
					acquirerLV.layoutParams = acquirerLVParam

					val acquirerImageView = ImageView(applicationContext)
					acquirerImageView.layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)
					acquirerImageView.setImageResource(tempAcquirerModel.HeaderLogoPng)
					acquirerImageView.adjustViewBounds = true
					acquirerLV.addView(acquirerImageView)
					acquirerLV.setOnClickListener {
						val salesModel = SalesModel(
							ProductCatSelectionDataEnum.BNPL.data.SalesType,
							modelData.Product as String,
							modelData.AcqCode as String,
							modelData.AcqMid as String,
							modelData.AcqTid as String,
							modelData.QrProductCode as String,
							modelData.ProductName as String,
							modelData.EppProductCode as String,
							modelData.EppTenure as String,
							modelData.EppTenureCode as String
						)
						ServiceHolder.selectedCacheModel = salesModel
						helperLog.appendLine(helperLogClassName, "Selected :: ${modelData.AcqCode} / ${modelData.ProductName} [SELECT BNPL ACQUIRER]")
						helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> BnplProductActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent = Intent(applicationContext, BnplProductActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent);
					}

					count--
					rowLinear.addView(acquirerLV)
					if ((i + 1) % rowItem == 0 || count == 0) {
						mainLinear.addView(rowLinear)
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
				helperLog.appendLine(helperLogClassName, "Render acquirer list (Exception) -> $e")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
			}
			acquirerScroll.addView(mainLinear)
		}
	}

	fun btnCancel(view: View) {
		helperLog.appendLine(helperLogClassName, "Cancel pressed :: [SELECT BNPL ACQUIRER]")
		customOnBackPress()
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT BNPL ACQUIRER]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		ServiceHolder.selectedCacheModel = null
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	override fun onDestroy() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "BnplAcquirer OnDestroy :: acquirer selection ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		super.onDestroy()
	}
}