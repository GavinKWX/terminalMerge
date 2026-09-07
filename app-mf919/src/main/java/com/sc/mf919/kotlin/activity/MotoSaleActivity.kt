package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresApi
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.java.utils.EmvUtil
import utils.HexUtil
import data_enum.CardSchemeEnum.Companion.isValid
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.*
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.isoComm
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class MotoSaleActivity : BaseActivity() {
	lateinit var mContext: Context
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	private var tvCardNo: TextView? = null
	private var tvExpDt: TextView? = null
	private var tvAmount: TextView? = null
	private var keypad: Keypad? = null

	private var posReference: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_moto_sale)
		val toolbar = findViewById<Toolbar>(R.id.toolbarMoto)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
		mContext = this@MotoSaleActivity
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"MOTO Payment Activity"
		)
		helperLog.appendLine(helperLogClassName, "Initialize Moto Sale Activity")
		TransData.reset(mContext)

		tvCardNo = findViewById(R.id.tvCardNo)
		tvCardNo?.setOnClickListener(onClickListener)
		tvExpDt = findViewById(R.id.tvExpDt)
		tvExpDt?.setOnClickListener(onClickListener)
		tvAmount = findViewById(R.id.tvAmount)
		tvAmount?.setOnClickListener(onClickListener)
		keypad = findViewById(R.id.keypad1)
		keypad?.registerOnOKEventListener(mListener)

		// default set to focus on cardNumber
		keypad?.setFilter(tvCardNo, false, 20)

		val intent = intent
		val cardNumber = intent.getStringExtra("cardNumber")
		val expDate = intent.getStringExtra("expDate")
		val amount = intent.getStringExtra("txnAmt")
		posReference = intent.getStringExtra("posReference")

		if (cardNumber != null
			&& expDate != null
			&& amount  != null) {
			// PAN masked: this is a real appendLine, so it lands in TerminaLog.txt and is uploaded to
			// TMS by uploadAllTerminalLog. The intent extra is the raw unmasked PAN.
			helperLog.appendLine(helperLogClassName, "MOTO Request from Intent >> CardNo[${Utils.hideCardDetails(cardNumber)}], ExpDate[$expDate], Amount[$amount] ")
			tvCardNo?.text = cardNumber
			tvExpDt?.text = expDate
			tvAmount?.text = amount
			motoSales(cardNumber, expDate, amount).execute()
		}
	}

	var mListener = onKeypadEventListener { isOK, msg ->
		println(msg)
		if (isOK && msg != null) {
			when (keypad!!.textViewID) {
				R.id.tvCardNo -> {
					if (isValid(msg)) {
						keypad!!.setFilter(tvExpDt, false, 4)
						return@onKeypadEventListener
					}
					Toast.makeText(applicationContext, "Invalid Card Number", Toast.LENGTH_SHORT).show()
					helperLog.appendLine(helperLogClassName, "REJECT :: invalid card number")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					return@onKeypadEventListener
				}
				R.id.tvExpDt -> {
					if (msg.length == 4) {
						tvAmount!!.text = "0.00"
						keypad!!.setFilter(tvAmount, true, 12)
						return@onKeypadEventListener
					}
					Toast.makeText(applicationContext, "Invalid Expiry Date", Toast.LENGTH_SHORT).show()
					helperLog.appendLine(helperLogClassName, "REJECT :: invalid expiry date")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
					return@onKeypadEventListener
				}
				R.id.tvAmount -> {
					if (msg == "0.00") {
						Toast.makeText(applicationContext, "Invalid Amount", Toast.LENGTH_SHORT).show()
						helperLog.appendLine(helperLogClassName, "REJECT :: invalid amount")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						return@onKeypadEventListener
					} else {
						val cardNumber = tvCardNo!!.text.toString()
						var expDate = tvExpDt!!.text.toString()
						expDate = expDate.substring(2, 4) + expDate.substring(0, 2)
						val amount = tvAmount!!.text.toString()
						helperLog.appendLine(helperLogClassName, "Amount entered :: $amount")
						helperLog.appendLine(helperLogClassName, "Validation passed :: starting MOTO sale")
						motoSales(cardNumber, expDate, amount).execute()
					}
					return@onKeypadEventListener
				}
			}
			return@onKeypadEventListener
		}
		customOnBackPress()
	}


	@SuppressLint("StaticFieldLeak")
	private inner class motoSales(val msgCardNo: String, val msgExpDt: String, val msgAmt: String) : CoroutineTask<String?, Boolean>()  {
		override fun onPreExecute() {
			helperLog.appendLine(helperLogClassName, "Moto Sales Execution -------->")
			startProgressDialog(this@MotoSaleActivity, "Bank Authorization", "Waiting for Approval")
			super.onPreExecute()
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		override fun doInBackground(vararg params: String?): Boolean {
			TransData.startTime = System.currentTimeMillis()
			val motoProductModel = getSpecificProduct(ProductCatSelectionDataEnum.MOTO.name) ?: getSpecificProduct(ProductCatSelectionDataEnum.CARD_SETTINGS.name)

			println("Obtaining Moto Product List >> ${motoProductModel.toString()}")
			helperLog.appendLine(helperLogClassName, "Obtaining Product List >> ", motoProductModel.toString())
			val salesModel = SalesModel(
				ProductCatSelectionDataEnum.MOTO.data.SalesType,
				motoProductModel?.Product ?: "",
				motoProductModel?.AcqCode ?: "",
				motoProductModel?.AcqMid ?: "",
				motoProductModel?.AcqTid ?: "",
				motoProductModel?.QrProductCode ?: "",
				motoProductModel?.ProductName ?: "",
				motoProductModel?.EppProductCode ?: "",
				motoProductModel?.EppTenure ?: "",
				motoProductModel?.EppTenureCode ?: ""
			)
			ServiceHolder.selectedCacheModel = salesModel
			val jsonProductList = Gson().toJson(motoProductModel)
			val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
			saleModelNew.SalesType = ProductCatSelectionDataEnum.MOTO.data.SalesType
			ServiceHolder.saleModelCache = saleModelNew
			helperLog.appendLine(helperLogClassName, "Update Moto Model >> ", saleModelNew.toString())
			helperLog.appendLine(helperLogClassName, "End Process Moto Sale Onclick")

			ServiceHolder.saleModelCache?.let {
				TransData.salesType = it.SalesType
				TransData.acqCode = it.AcqCode ?: ""
				TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
				TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
				TransData.product = it.Product ?: ""
				TransData.productName = it.ProductName ?: ""
				//TransData.productCode = it.Product ?: ""
				TransData.eppTenure = it.EppTenure ?: ""
				TransData.eppTenureCode = it.EppTenureCode ?: ""
				TransData.ksn = it.Ksn ?: ""
				TransData.pinKsn = it.PinKsn ?: ""
			}
			val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
			TransData.transDateAsci = txnDt
			TransData.txnTypeLabel = "Moto"
			TransData.schemeId = "Moto"
			TransData.entryModeLabel = "Manual"
			TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("Manual"))
			posReference?.let {
				TransData.posReference = it
				helperLog.appendLine(helperLogClassName, "Add Pos Reference >> $it")
			}

			//Copy from void not sure correct or not
			TransData.cvm = "3E3030"
			TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_CVM,  "3E3030")
			helperLog.appendLine(helperLogClassName, "Insert CVM -> ", "3E3030")

			TransData.maskedPan = Utils.hideCardDetails(msgCardNo)
			TransData.hashedPan = msgCardNo.substring(0,9)

			val bytePan = msgCardNo.toByteArray()
			bytePan.copyInto(TransData.pan, 0)
			TransData.panLen = bytePan.size
			TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(msgCardNo)))
			TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(msgCardNo.substring(0,9)))
			TransData.addHexStrIntoTransDB(Global.iso.tag.PANSTRING, msgCardNo)
			helperLog.appendLine(helperLogClassName, "Insert Card No to Iso-buffer")

			val byteExpDt = msgExpDt.toByteArray()
			byteExpDt.copyInto(TransData.expirationDate, 0)
			TransData.addHexStrIntoTransDB(Global.iso.tag.EXPDATE, msgExpDt)
			helperLog.appendLine(helperLogClassName, "Insert card exp to Iso-buffer")

			val strTxnAmt = Utils.zeroPadding(
				msgAmt.replace(".", ""), 12
			)
			if (strTxnAmt.isNotEmpty()) {
				TransData.amount = strTxnAmt.toLong()
				HexUtil.hexStringToByte(strTxnAmt).copyInto(TransData.amountAuth)
				helperLog.appendLine(helperLogClassName, "Insert amount to Iso-buffer -> ", strTxnAmt)
			}

			val isNotCompl = booleanArrayOf(true)
			isoComm = null
			object : Thread() {
				override fun run() {
					super.run()
					IsoActivity.processMoto(mContext, helperLog)
					isNotCompl[0] = false
				}
			}.start()
			helperLog.appendLine(helperLogClassName, "Moto Sales executing....")
			while (isNotCompl[0]) {
				if (isoComm != null) {
					pDMsg = isoComm!!.connectionStatus
					if (pDMsg != null) {
						if (pDMsg!!.isNotEmpty()) {
							helperLog.appendLine(helperLogClassName, "Running -> ", pDMsg!!)
							runOnUiThread(changeMessage)
						}
					}
				}
				Utils.DelayMili(100)
			}
			helperLog.appendLine(helperLogClassName, "Moto Sales finish.")
			return true
		}

		override fun onPostExecute(result: Boolean?) {
			super.onPostExecute(result)
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionResultActivity")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			closeProgressDialog()
			val intent = Intent(this@MotoSaleActivity, TransactionResultActivity::class.java)
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
			finish()
		}
	}

	var onClickListener: View.OnClickListener = object : View.OnClickListener {
		override fun onClick(v: View) {
			//set textview onclick back to default
			tvCardNo!!.setOnClickListener(this)
			tvExpDt!!.setOnClickListener(this)
			tvAmount!!.setOnClickListener(this)
			when (v.id) {
				R.id.tvCardNo -> {
					helperLog.appendLine(helperLogClassName, "Selected :: Card Number field [MOTO SALE]")
					keypad!!.setFilter(tvCardNo, false, 20)
				}
				R.id.tvExpDt -> {
					helperLog.appendLine(helperLogClassName, "Selected :: Expiry Date field [MOTO SALE]")
					keypad!!.setFilter(tvExpDt, false, 4)
				}
				R.id.tvAmount -> {
					helperLog.appendLine(helperLogClassName, "Selected :: Amount field [MOTO SALE]")
					tvAmount!!.text = "0.00"
					keypad!!.setFilter(tvAmount, true, 12)
				}
			}
		}
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, abandoning MOTO sale")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}

		ServiceHolder.appIntent = false
		ServiceHolder.appHTTP = false
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	private fun onBackToApp(txn_map: java.util.HashMap<String, String>) {
		txn_map["TransactionType"] = ServiceHolder.txnType.toString()
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txn_map", txn_map)
		startActivity(intent)
		finish()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "MotoSaleActivity OnDestroy :: MOTO sale screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	private fun getSpecificProduct(name: String): DbModelProductList? {
		val merchantProductList = ServiceHolder.getMerchantProductList()
		if (merchantProductList != null) {
			for (item in merchantProductList) {
				if (item.Product == name) {
					return item
				}
			}
		}
		return null
	}
}
