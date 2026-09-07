package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import utils.HexUtil
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import org.json.JSONObject

class TransactionResultBnplActivity : ActivityBase() {
	var txnType: String? = ""
	var txnAmt: String? = ""
	var txnDt: String? = ""
	var txnId: String? = ""
	var respCode: String? = ""
	var txnRefId: String? = ""
	var eWallet: String? = ""
	var desc = ""
	var acqCode: String? = ""
	var bankAuthCode: String? = ""
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	@RequiresApi(api = Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_transactionresult_bnpl)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"BNPL Transaction Result"
		)
		val intent = intent
		val txn_map = HashMap<String, String?>()
		val jsonObject = JSONObject()
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		txnType = intent.getStringExtra("txnType")
		txnAmt = intent.getStringExtra("amount")
		txnId = intent.getStringExtra("txnId")
		txnRefId = intent.getStringExtra("txnRefId")
		txnDt = intent.getStringExtra("txnDt")
		respCode = intent.getStringExtra("respCode")
		eWallet = intent.getStringExtra("eWallet")
		acqCode = intent.getStringExtra("acqCode")
		bankAuthCode = intent.getStringExtra("bankAuthCode")
		desc = if (respCode == "0000") "Approved" else "Failed"
		helperLog.appendLine(helperLogClassName, "BNPL result known :: $desc respCode=$respCode txnType=$txnType amount=$txnAmt eWallet=$eWallet acqCode=$acqCode txnId=$txnId refId=$txnRefId")
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		(findViewById<View>(R.id.qrType_tr) as TextView).text = txnType
		(findViewById<View>(R.id.qrAmount_tr) as TextView).text = txnAmt
		(findViewById<View>(R.id.qrTxnId_tr) as TextView).text = txnId
		(findViewById<View>(R.id.qrRefId_tr) as TextView).text = txnRefId
		(findViewById<View>(R.id.qrDateNtime_tr) as TextView).text = txnDt
		if (bankAuthCode == null || bankAuthCode == "") (findViewById<View>(
			R.id.qrRespCode_tr
		) as TextView).text = respCode else (findViewById<View>(
			R.id.qrRespCode_tr
		) as TextView).text = bankAuthCode
		txn_map["ResponseCode"] = respCode
		txn_map["ResponseDescription"] = desc
		txn_map["TransactionType"] = Integer.toString(ServiceHolder.txnType)
		txn_map["TransactionAmount"] = txnAmt
		txn_map["TransactionId"] = txnId
		txn_map["TransactionRefId"] = txnRefId
		txn_map["TransactionEWallet"] = eWallet
		txn_map["TransactionDateTime"] = txnDt
		try {
			jsonObject.put("ResponseCode", respCode)
			jsonObject.put("ResponseDescription", desc)
			jsonObject.put("TransactionType", Integer.toString(ServiceHolder.txnType))
			jsonObject.put("TransactionAmount", txnAmt)
			jsonObject.put("TransactionId", txnId)
			jsonObject.put("TransactionRefId", txnRefId)
			jsonObject.put("TransactionEWallet", eWallet)
			jsonObject.put("TransactionDateTime", txnDt)
		} catch (e: Exception) {
			e.printStackTrace()
			helperLog.appendLine(helperLogClassName, "Build result JSON (Exception) -> $e")
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}

		// Revamp
		if (appIntent) {
			(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
			(findViewById<View>(R.id.cancelBtn) as LinearLayout).visibility = View.GONE
			onBackToApp(txn_map)
			return
		} else if (appHTTP) {
			helperLog.appendLine(helperLogClassName, "Result returned to HTTP caller :: respCode=$respCode")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			onBackToHTTP(jsonObject.toString())
		}

		val terminalConfig = getTerminalConfig()
		//no print receipt button on unattended terminal, the button row centers the remaining button on its own
		if (getBooleanValue(terminalConfig, "UNATTENDED_MODE")) {
			(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
		}

		if (respCode != "0000") {
			(findViewById<View>(R.id.mainCircle) as LinearLayout).background = getDrawable(
				R.drawable.txn_fail
			)
			(findViewById<View>(
				R.id.imageview_transactionresult
			) as ImageView).background = getDrawable(R.drawable.fail_icon)
			(findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
			(findViewById<View>(R.id.textview_paymentResult) as TextView).text = "Payment Failed"
			helperLog.appendLine(helperLogClassName, "Payment Failed displayed :: respCode=$respCode")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		//if (!txnType.toUpperCase(Locale.ENGLISH).equals("SALE") || ServiceHolder.Companion.getBooleanValue("termInfo.txt", "receiptprint")) {
		if (txnType!!.uppercase() != "SALE" || getBooleanValue(terminalConfig, "ReceiptPrint")) {
			helperLog.appendLine(helperLogClassName, "Auto print receipt :: txnType=$txnType")
			print_receipt(View(this))
			return
		}
		(findViewById<View>(R.id.cancelBtnImageview) as ImageView).setImageResource(
			R.drawable.ok_icon
		)
		(findViewById<View>(R.id.cancelBtnTextview) as TextView).text = "OK"
		// Revamp

		/*
        if (!respCode.equals("0000")) {
            ((LinearLayout) findViewById(R.id.mainCircle)).setBackground(getDrawable(R.drawable.txn_fail));
            ((ImageView) findViewById(R.id.imageview_transactionresult)).setBackground(getDrawable(R.drawable.fail_icon));
            ((LinearLayout) findViewById(R.id.printBtn)).setVisibility(View.GONE);
            ((TextView) findViewById(R.id.textview_paymentResult)).setText("Payment Failed");

            if (ServiceHolder.Companion.getAppIntent()) {
                ((LinearLayout) findViewById(R.id.cancelBtn)).setVisibility(View.GONE);
                onBackToApp(txn_map);
            } else if (ServiceHolder.Companion.getAppHTTP()) {
                onBackToHTTP(object.toString());
            }
        } else {
            if (ServiceHolder.Companion.getAppIntent()) {
                ((LinearLayout) findViewById(R.id.printBtn)).setVisibility(View.GONE);
                ((LinearLayout) findViewById(R.id.cancelBtn)).setVisibility(View.GONE);
                onBackToApp(txn_map);
            } else if (ServiceHolder.Companion.getAppHTTP()) {
                onBackToHTTP(object.toString());
            } else if (!txnType.toUpperCase(Locale.ENGLISH).equals("SALE") || ServiceHolder.Companion.getBooleanValue("termInfo.txt", "receiptprint")) {
                print_receipt(new View(this));
            }
            ((ImageView) findViewById(R.id.cancelBtnImageview)).setImageResource(R.drawable.ok_icon);
            ((TextView) findViewById(R.id.cancelBtnTextview)).setText("OK");
        }
        * */
	}

	fun print_receipt_cancel(view: View?) {
		helperLog.appendLine(helperLogClassName, "OK/Cancel pressed :: receipt print skipped")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(this@TransactionResultBnplActivity, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	fun print_receipt(view: View?) {
		helperLog.appendLine(helperLogClassName, "Print pressed :: respCode=$respCode")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> PrintResultBnplActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(applicationContext, PrintResultBnplActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txnType", txnType)
		intent.putExtra("amount", txnAmt)
		intent.putExtra("txnId", txnId)
		intent.putExtra("txnRefId", txnRefId)
		intent.putExtra("txnDt", txnDt)
		intent.putExtra("respCode", respCode)
		//intent.putExtra("eWallet", eWallet); not required
		intent.putExtra("acqCode", acqCode)
		intent.putExtra("bankAuthCode", "")
		startActivity(intent)
		finish()
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving BNPL result")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(this, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	private fun onBackToApp(txn_map: HashMap<String, String?>) {
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val intent = Intent(this, TransactionTransmitter::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		intent.putExtra("txn_map", txn_map)
		startActivity(intent)
		finish()
	}

	override fun onDestroy() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "TransactionResultBnpl OnDestroy :: BNPL result screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		super.onDestroy()
	}

	private fun onBackToHTTP(msg: String) {
		HTTPServer.getInstance().setResponseMessage(msg)
		appHTTP = false
		//Utils.DelayMili(1000);
		//onBackPressed();
	}

	companion object {
		//CubeActivity cube;
		private const val TAG = "Print"

		//CubeActivity cube;
		private fun sysPrint(message: String) {
			Utils.debugLogPrint(TAG, message)
		}

		private fun sysPrint(message: String, data: ByteArray, dataOffset: Int, dataLen: Int) {
			Utils.debugLogPrint(TAG, message + HexUtil.bcd2str(data, dataOffset, dataLen))
		}
	}
}