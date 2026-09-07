package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import com.sc.mf919.R
import com.sc.mf919.java.activity.Keypad
import com.sc.mf919.java.activity.KeypadUPI
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.onKeypadEventListener
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import data_enum.SalesModel
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import java.math.BigDecimal
import java.util.ArrayList

class KeypadActivityCashOut: BaseActivity() {
    private val TAG = "Keypad_CashOut"
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    private var textView_amount: TextView? = null
    private var typeOfSale = 0
    private var selectedSales: SalesModel? = null
    private var posReference: String? = null
    var saleAmount: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keypad)
        val toolbar = findViewById<Toolbar>(R.id.toolbarSale)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Cash-Out Keypad"
        )
        helperLog.appendLine(helperLogClassName, "Cash-Out keypad opened")
        typeOfSale = intent.getIntExtra("typeofSale", 0)
        posReference = intent.getStringExtra("posReference")
        val txnAmountString = intent.getStringExtra("txnAmt") ?: "0"
        saleAmount = txnAmountString.replace(".", "").toLong()

        selectedSales = selectedCacheModel as SalesModel?
        if (selectedSales == null) {
            selectedSales = SalesModel(0, "", "", "", "", "", "", "", "", "")
        }

        // TODO merge togther with revamped method
        (findViewById<View>(R.id.textView2) as TextView).text = "CASH-OUT AMOUNT"
        (findViewById<View>(R.id.textHeader) as TextView).text = "CASH-OUT"
        helperLog.appendLine(helperLogClassName, "Type of Sale :: $typeOfSale")

        val keypad = findViewById<Keypad>(R.id.keypad1)
        textView_amount = findViewById<TextView>(R.id.textViewAmount)
        keypad.setFilter(textView_amount, true, 12)
        keypad.registerOnOKEventListener(mListener)

        val keypad2 = findViewById<KeypadUPI>(R.id.keypad2)
        keypad2.visibility = View.GONE
        val btnUPI = findViewById<LinearLayout>(R.id.btnsUPI)
        btnUPI.visibility = View.GONE
    }

    private fun cashOut() {
        helperLog.appendLine(helperLogClassName, "Cash-Out amount checking")
        val txnAmount = textView_amount!!.text.toString().replace("RM ".toRegex(), "")
        if (txnAmount == "0.00") {
            Toast.makeText(applicationContext, "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show()
            helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }
        val ss = BigDecimal(txnAmount)
        val ss1 = BigDecimal("999999.99")
        if (ss > ss1) {
            Toast.makeText(applicationContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
            helperLog.appendLine(helperLogClassName, "REJECT :: amount must be less than 999999.99")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        val txnAmountLong = txnAmount.replace(".", "").toLong()
        saleAmount += txnAmountLong
        helperLog.appendLine(helperLogClassName, "Amount entered :: $txnAmount")
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val newIntent = Intent(applicationContext, CardPaymentActivity::class.java)
        newIntent.putExtra("txnAmt", Utils.getActualAmount(saleAmount.toString()))
        newIntent.putExtra("cashOutAmt", txnAmount)
        newIntent.putExtra("typeofSale", selectedSales!!.SalesType)
        newIntent.putExtra("posReference", posReference)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
    }

    fun customOnBackPress() {
        // Wired to the toolbar before helperLog is constructed in onCreate, so guard the access.
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving cash-out keypad")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        selectedCacheModel = null
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "KeypadActivityCashOut OnDestroy :: cash-out keypad ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private val mListener = onKeypadEventListener { isOK, msg ->
        if (isOK) {
            if (msg == null) {
                customOnBackPress()
            } else {
                cashOut()
            }
        }
    }
}