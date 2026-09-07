package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.handlers.ioupay.IoupayGetPackagesHandler
import tms.models.ioupay.IoupayGetPackagesResponseModel
import tms.models.ioupay.IoupayTenure
import java.text.SimpleDateFormat
import java.util.*

class BnplProductActivity : ActivityBase(), LoaderManager.LoaderCallbacks<Any> {
	lateinit var mContext: Context
	lateinit var acquirerScroll: ScrollView
	lateinit var amount: String
	private val GET_TENURE_ASYNC_ID = 600001
	lateinit var loaderManager: LoaderManager

	lateinit var getPackagesResponseModel: IoupayGetPackagesResponseModel
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	/**
	 * navigationToResultPage in the companion constructs BnplProductActivity() directly, so
	 * onCreate never runs on that instance and helperLog is unset. Any log call reachable from
	 * such a path must go through here rather than touching helperLog.
	 */
	private fun logBnpl(msg: String) {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, msg)
		} else {
			Utils.debugLogPrint(BnplProductActivity::class.java.simpleName, msg)
		}
	}

	companion object {
		var qrSeqNo = ""
		var qrTxnDt = ""
		var qrRefId = ""
		var acqMid = ""
		var acqTid = ""
		var qrRespRefId = ""
		var qrRespTxnRefNo = ""
		var qrRespHostRefNo = ""
		var qrRespApprovalCode = ""
		var respCode = "1100"
		var tenure = 0
		var tenureDesc = ""
		var paymentType = ""
		var packageCode = ""
		var amt: String? = "000000000000"
		var isForceStop = false
		lateinit var selectedModel: SalesModel

		lateinit var tempModel: String

		fun navigationToResultPage(context: Context) {
			BnplProductActivity().closeProgressDialog()
			val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
			val txnDt = sdf.format(Date())

			ServiceHolder.selectedCacheModel?.let {
				val tempModel = ServiceHolder.selectedCacheModel as SalesModel
				//tempModel.AcqMid = Utils.maskString(acqMid, 4)
				//tempModel.AcqTid = Utils.maskString(acqTid, 4)
				ServiceHolder.selectedCacheModel = tempModel
			}

			tempModel = ServiceHolder.getDeviceModel().uppercase()

			var intent = Intent(context, TransactionResultQrActivity::class.java)
			val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
			if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
				intent = Intent(context, DenominationTransactionResultActivity::class.java)
			}
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			intent.putExtra("txnType", "Sale")
			intent.putExtra("amount", "RM" + Utils.getActualAmount(amt))
			intent.putExtra("txnId", qrRespHostRefNo)
			intent.putExtra("txnRefId", qrRefId)
			intent.putExtra("txnDt", txnDt)
			intent.putExtra("respCode", respCode)
			intent.putExtra("eWallet", selectedModel.QrProductCode)
			intent.putExtra("acqCode", selectedModel.AcqCode)
			intent.putExtra("bankAuthCode", qrRespApprovalCode)
			context.startActivity(intent)
			BnplProductActivity().finish()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		isForceStop = false
		mContext = this

		setContentView(R.layout.activity_bnpl_acquirer)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"BNPL Tenure Selection"
		)

		val toolbar = findViewById<Toolbar>(R.id.toolbarBnplAcq)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener {
			helperLog.appendLine(helperLogClassName, "User Cancel :: toolbar back pressed, abandoning tenure selection")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			onBackPressed()
		}

		selectedModel = (ServiceHolder.selectedCacheModel as SalesModel?)!!
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT TENURE] acquirer=${selectedModel.AcqCode} product=${selectedModel.QrProductCode}")
		acquirerScroll = findViewById(R.id.bnpl_acquirer_scroll)
		renderInformationheader()

		loaderManager = LoaderManager.getInstance(this)
		loaderManager.initLoader(GET_TENURE_ASYNC_ID, null, this)
	}

	override fun onDestroy() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "BnplProduct OnDestroy :: tenure selection ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		loaderManager.destroyLoader(GET_TENURE_ASYNC_ID)
		super.onDestroy()
	}

	override fun onCreateLoader(id: Int, args: Bundle?): Loader<Any> {
		if (id == GET_TENURE_ASYNC_ID) {
			startProgressDialog(mContext, "Getting Tenure List", "Loading...")
			return GetTenurePackagesObjectLoader(mContext)
		}
		return Loader(mContext)
	}

	override fun onLoadFinished(loader: Loader<Any>, data: Any) {
		val id = loader.id
		if (id == GET_TENURE_ASYNC_ID) {
			if (data == false) {
				helperLog.appendLine(helperLogClassName, "REJECT :: tenure package list unavailable, respCode=$respCode :: navigate -> result page")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				return navigationToResultPage(mContext)
			}

			getPackagesResponseModel = data as IoupayGetPackagesResponseModel
			helperLog.appendLine(helperLogClassName, "Tenure packages received :: ${getPackagesResponseModel.PACKAGE_LIST.size}")
			if (getPackagesResponseModel.PACKAGE_LIST.isNotEmpty()) {
				renderDynamicProduct(getPackagesResponseModel.PACKAGE_LIST);
			}

			helperLog.logToFile(EnumLogFileName.TerminaLog)
			closeProgressDialog()
			return
		}
	}

	private fun renderInformationheader() {
		val infoLinearLayout = findViewById<LinearLayout>(R.id.bnpl_acquirer_header)

		var infoTV = TextView(mContext)
		var bnplMethod = ""
		try {
			val acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(selectedModel.AcqCode ?: "").data
			bnplMethod = acqLogoEnumModel.AcquirerName
		} catch (e: Exception) {
			e.printStackTrace()
		}

		infoTV.text = "BNPL METHOD: ${bnplMethod}"
		infoTV.setPadding(
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(20),
			Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(20)
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

	override fun onLoaderReset(loader: Loader<Any>) {
		logBnpl("Tenure package loader reset")
		closeProgressDialog()
	}

	class GetTenurePackagesObjectLoader(context: Context) : AsyncTaskLoader<Any>(context) {
		override fun onStartLoading() {
			forceLoad()
		}

		override fun loadInBackground(): Any {
			val log = HelperLog(
				HelperCommon.getSession(),
				TmsHelper.checkIsConnectedWifi(context),
				Utils.getIPAddress(),
				"BNPL Product Activity",
				BnplProductActivity::class.java.simpleName,
				BnplProductActivity::class.java.name,
			)
			val logClassName = BnplProductActivity::class.java.name
			log.appendLine(logClassName, "Getting Tenure Packages - Preparing Request")
			val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
			val ioupayGetPackagesHandler = IoupayGetPackagesHandler(environmentManager)

			try {
				val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
				if (merchantInfo == null) {
					navigationToResultPage(context)
				}

				val resp = ioupayGetPackagesHandler.invoke(
					log,
					merchantInfo?.QrMid ?: "",
					merchantInfo?.QrTid ?: ""
				)
				log.appendLine(logClassName, "IoupayGetPackagesHandler Response -> ", resp.toString())
				log.logToFile(EnumLogFileName.TerminaLog)

				if (isForceStop) {
					log.appendLine(logClassName, "REJECT :: tenure fetch force-stopped")
					log.logToFile(EnumLogFileName.TerminaLog)
					return false
				}
				return resp
			} catch (e: Exception) {
				e.printStackTrace()
				log.appendLine(logClassName, "IoupayGetPackagesHandler (Exception) -> ", e.toString())
				log.logToFile(EnumLogFileName.TerminaLogException)
			}
			return false
		}
	}

	private fun renderDynamicProduct(tenureList: List<IoupayTenure>) {
		if (tenureList.isNotEmpty()) {
			val mainLinear = LinearLayout(applicationContext)
			val mainLinearParam = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			mainLinearParam.setMargins(
				Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(5),
				Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(5)
			)
			mainLinear.layoutParams = mainLinearParam
			mainLinear.orientation = LinearLayout.VERTICAL

			var rowLinear = LinearLayout(applicationContext)
			var count = tenureList.size
			val rowItem = 1

			try {
				for (i in tenureList.indices) {
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

					productLV.gravity = Gravity.CENTER
					productLV.layoutParams = productLVParam

					val productTV = TextView(applicationContext)
					productTV.layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)
					productTV.gravity = Gravity.CENTER
					productTV.text = tenureList[i].DESCRIPTION
					productTV.setTypeface(productTV.typeface, Typeface.BOLD)
					productTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20F)
					productTV.layoutParams.width = 160
					productTV.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
					productLV.addView(productTV)
					productLV.setOnClickListener {
						tenure = tenureList[i].TENURE!!;
						tenureDesc = tenureList[i].DESCRIPTION!!;
						paymentType = tenureList[i].PAYMENT_TYPE!!;
						packageCode = tenureList[i].PACKAGE_CODE!!;

						helperLog.appendLine(helperLogClassName, "Selected :: tenure=$tenure ($tenureDesc) paymentType=$paymentType packageCode=$packageCode [SELECT TENURE]")
						helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent = Intent(applicationContext, KeypadActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

						intent.putExtra("tenure", tenure)
						intent.putExtra("tenureDesc", tenureDesc)
						intent.putExtra("paymentType", paymentType)
						intent.putExtra("packageCode", packageCode)
						startActivity(intent);
					}

					count--
					rowLinear.addView(productLV)
					if ((i + 1) % rowItem == 0 || count == 0) {
						rowLinear.background = ResourcesCompat.getDrawable(
							resources, R.drawable.border_bottom, theme
						)
						mainLinear.addView(rowLinear)
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
				helperLog.appendLine(helperLogClassName, "Render tenure list (Exception) -> $e")
				helperLog.logToFile(EnumLogFileName.TerminaLogException)
			}
			acquirerScroll.addView(mainLinear)
		}
	}

	fun btnCancel(view: View) {
		helperLog.appendLine(helperLogClassName, "User Cancel :: cancel pressed, abandoning tenure selection")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		onBackPressed()
	}
}