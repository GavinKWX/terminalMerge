package com.sc.mf919.kotlin.activity

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919.R
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import org.json.JSONException
import org.json.JSONObject

class EppAcquirerActivity : BaseActivity() {
	lateinit var mContext: Context
	//TODO Log Purpose
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	lateinit var acquirerScroll: ScrollView
	var extraBundle: Bundle? = null

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_epp_acquirer)
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			@RequiresApi(Build.VERSION_CODES.O)
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		val toolbar = findViewById<Toolbar>(R.id.toolbarEppAcq)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }

		extraBundle = intent.extras

		mContext = this@EppAcquirerActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"EPP Acquirer Activity"
		)
		helperLog.appendLine(helperLogClassName, "Initialize EPP Acquirer Activity")
		TransData.reset(mContext)
		ServiceHolder.saleModelCache = null

		acquirerScroll = findViewById(R.id.epp_acquirer_scroll)
		renderDynamicAcquirer()
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun renderDynamicAcquirer() {
		val listAcquirer = ProductListRepo.getDistinctEppAcquirer(applicationContext)

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
			val rowItem = 2

			try {
				for (i in listAcquirer.indices) {
					// copy() so the mutation below doesn't touch the shared list element
					val modelData = copyAcquirer(listAcquirer[i])
					modelData.Product = ProductCatSelectionDataEnum.EPP.name
					val tempAcquirerModel = AcquirerLogoDataEnum.valueOf(modelData.AcqCode).data

					if (i % rowItem == 0) {
						rowLinear = LinearLayout(applicationContext)
						val rowLinearParam = LinearLayout.LayoutParams(
							LinearLayout.LayoutParams.MATCH_PARENT,
							LinearLayout.LayoutParams.WRAP_CONTENT
						)
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
							ProductCatSelectionDataEnum.EPP.data.SalesType,
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
						saleModelNew.SalesType = ProductCatSelectionDataEnum.EPP.data.SalesType
						ServiceHolder.saleModelCache = saleModelNew
						helperLog.appendLine(helperLogClassName, "Update EPP Model :: ", jsonProductList)
						helperLog.appendLine(helperLogClassName, "Selected :: ${modelData.AcqCode} (${tempAcquirerModel.AcquirerName}) [SELECT EPP ACQUIRER]")
						helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)

						val newIntent = Intent(applicationContext, KeypadActivity::class.java)
						extraBundle?.let {
							for(key in it.keySet()){
								newIntent.putExtra(key, it.getString(key))
							}
						}
						newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(newIntent);
					}

					count--
					rowLinear.addView(acquirerLV)
					if ((i + 1) % rowItem == 0 || count == 0) {
						mainLinear.addView(rowLinear)
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
				helperLog.appendLine(helperLogClassName, "Exception in Rendering EPP Acquirer List -> ${e.message ?: "-"}")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
			}
			acquirerScroll.addView(mainLinear)
		} else {
			helperLog.appendLine(helperLogClassName, "REJECT :: no EPP acquirer configured")
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun btnCancel(view: View) {
		helperLog.appendLine(helperLogClassName, "Cancel button pressed")
		customOnBackPress()
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: abandoned EPP acquirer selection")
		val txnMap = HashMap<String, String>()
		val jObject = JSONObject()
		txnMap["ResponseCode"] = "SHC005"
		txnMap["ResponseDescription"] = "User Cancel the Transaction"

		try {
			jObject.put("ResponseCode", "SHC005")
			jObject.put("ResponseDescription", "User Cancel the Transaction")
		} catch (e: JSONException) {
			e.printStackTrace()
		}finally {
			if (ServiceHolder.appIntent) {
				onBackToApp(txnMap)
			}else{
				if (ServiceHolder.appHTTP) {
					HTTPServer.getInstance().setResponseMessage(jObject.toString())
					ServiceHolder.appHTTP = false
				}

				helperLog.appendLine(helperLogClassName, "User Cancel :: navigate -> home screen")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
				val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
				newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
				startActivity(newIntent)
				finish()
			}
		}
	}

	private fun onBackToApp(txnMap: HashMap<String, String>) {
		txnMap["TransactionType"] = ServiceHolder.txnType.toString()
		helperLog.appendLine(helperLogClassName, "User Cancel :: navigate -> TransactionTransmitter (ECR reply)")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val newIntent = Intent(this, TransactionTransmitter::class.java)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		newIntent.putExtra("txn_map", txnMap)
		startActivity(newIntent)
		finish()
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
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "EppAcquirer OnDestroy :: EPP acquirer selection ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	private fun copyAcquirer(source: DbModelProductList): DbModelProductList {
		return DbModelProductList(
			Product = source.Product ?: "",
			AcqCode = source.AcqCode ?: "",
			AcqMid = source.AcqMid ?: "",
			AcqTid = source.AcqTid ?: "",
			QrProductCode = source.QrProductCode ?: "",
			ProductName = source.ProductName ?: "",
			EppProductCode = source.EppProductCode ?: "",
			EppTenure = source.EppTenure ?: "",
			EppTenureCode = source.EppTenureCode ?: "",
			IsSettlement = source.IsSettlement ?: "",
			IsSettled = source.IsSettled ?: "",
			BatchNo = source.BatchNo ?: "",
			IsActive = source.IsActive ?: "",
			Ksn = source.Ksn ?: "",
			PinKsn = source.PinKsn ?: "",
			IsTpaAccount = source.IsTpaAccount ?: ""
		)
	}
}