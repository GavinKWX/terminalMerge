package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.OxpayIntentModel
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.MfHelper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal

class AttendActivityOxpay: ActivityBase() {
    lateinit var mContext: Context
    var doubleBackToExitPressedOnce = false
    private lateinit var textViewAmount: TextView
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attend_oxpay)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "OxPay attended home screen"
        )
        val toolbar = findViewById<Toolbar>(R.id.appToolbar)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_menu_24)
        toolbar.setNavigationOnClickListener {
            val newIntent = Intent(applicationContext, SettingsActivityOxpay::class.java)
            helperLog.appendLine(helperLogClassName, "Selected :: Menu [ATTEND OXPAY]")
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettingsActivityOxpay")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }
        onBackPressedDispatcher.addCallback(this@AttendActivityOxpay, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        helperLog.appendLine(helperLogClassName, "Attend Activity Initialize")
        mContext = this@AttendActivityOxpay
        textViewAmount = findViewById(R.id.textViewAmount)

        //HTTPServer.getInstance().isActive = true
        HTTPServer.getInstance().attendActivityContext = this@AttendActivityOxpay
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond
        if (Helper.getInstance().getPrefs() == null) {
            Helper.getInstance().Initialize(applicationContext)
        }
        if (MainActivity.isFirstRun) {
            MainActivity.isFirstRun = false
        }

        val keypad = findViewById<KeypadOnly>(R.id.keypadOnly)
        keypad.setFilter(textViewAmount, true, 12)

        findViewById<LinearLayout>(R.id.linearEWallet).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: E-Wallet [ATTEND OXPAY]")
            eWalletSale()
        }

        findViewById<LinearLayout>(R.id.linearCard).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Card [ATTEND OXPAY]")
            cardSale()
        }

        helperLog.logToFile(EnumLogFileName.TerminaLog)

        lifecycleScope.launch {
            HTTPServer.resetCommunicationPort()
            checkingTerminalFlag()
        }
    }

    private fun checkingTerminalFlag() = CoroutineScope(Dispatchers.IO).launch {
        MfHelper.closeNfcUrlInterface()
        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")) {
            HelperCommon.bottomActionBarEvent(applicationContext, "1")
        } else {
            HelperCommon.bottomActionBarEvent(applicationContext, "0")
        }

        if(ServiceHolder.autoSettlementQueue) {
            helperLog.appendLine(helperLogClassName, "Auto settlement queued :: triggering")
            AppServices.triggerAutoSettle(this@AttendActivityOxpay, AppServices.ACTION_RUN_AUTO_SETTLE)
        }

        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun checkAmountValidity(): Int{
        helperLog.appendLine(helperLogClassName, "Validating Amount >> ${textViewAmount.text}")

        val txnAmout = textViewAmount.text.toString().replace("RM ".toRegex(), "")

        if (txnAmout == "0.00") {
            helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show()
            return 0
        }

        val ss = BigDecimal(txnAmout)
        val ss1 = BigDecimal("999999.99")
        if (ss > ss1) {
            helperLog.appendLine(helperLogClassName, "REJECT :: amount must be less than 999999.99")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show()
            return 0
        }

        txnAmout.replace(".", "").toIntOrNull()?.let {
            return it
        } ?: run {
            return 0
        }
    }

    private fun cardSale() {
        helperLog.appendLine(helperLogClassName, "Processing Card Sales")
        val merchantInfo = ServiceHolder.getMerchantInfo()
        if (merchantInfo == null) {
            helperLog.appendLine(helperLogClassName, "REJECT :: merchant info missing, terminal not configured")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(this, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
            return
        }

        val result = checkAmountValidity()
        if (result > 0) {
            val prodDetail = ProductListRepo.getSelectedProductEnhanced(applicationContext, listOf("Product"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
            if (prodDetail.isEmpty()) {
                helperLog.appendLine(helperLogClassName, "REJECT :: CARD_SETTINGS product details not found")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                Toast.makeText(applicationContext, "Error While Obtain Product Details", Toast.LENGTH_SHORT).show()
                return
            }

            val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = prodDetail[0]
            val salesModel = SalesModel(
                ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                Product,
                AcqCode,
                AcqMid,
                AcqTid,
                QrProductCode,
                ProductName,
                EppProductCode,
                EppTenure,
                EppTenureCode
            )
            ServiceHolder.selectedCacheModel = salesModel
            val jsonProductList = Gson().toJson(prodDetail[0])
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew

            val newIntent = Intent(applicationContext, CardPaymentActivity::class.java)
            helperLog.appendLine(helperLogClassName, "Amount entered :: ${textViewAmount.text}")
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            newIntent.putExtra("txnAmt", textViewAmount.text)
            newIntent.putExtra("typeofSale", ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }
    }

    fun eWalletSale() {
        helperLog.appendLine(helperLogClassName, "Process E-Wallet Sale")
        var presetIntent = ServiceHolder.getOxpayIntent()
        if(presetIntent.isEmpty()) {
            val apps = listOf(
                OxpayIntentModel("Oxpay Lite", "com.mcpayment.sunmi.ffastpay", OxpayIntentModel.isAppInstalled("com.mcpayment.sunmi.ffastpay", applicationContext)),
                OxpayIntentModel("Ffastpay", "oxpay.ffastpay", OxpayIntentModel.isAppInstalled("oxpay.ffastpay", applicationContext))
            )
            presetIntent = apps.firstOrNull { it.isInstalled }?.packageName ?: ""
        }
        val managerIntent = packageManager.getLaunchIntentForPackage(presetIntent)
        if(managerIntent != null) {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> $presetIntent")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            managerIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            startActivity(managerIntent)
        } else {
            // The app is not installed, handle the error
            helperLog.appendLine(helperLogClassName, "REJECT :: e-wallet app not installed")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "App not installed", Toast.LENGTH_SHORT).show()
        }
    }

    fun customOnBackPress() {
        if (doubleBackToExitPressedOnce) {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [EXIT PASSWORD]")
            passwordAlertDialog(1, "132465", object : onAlertDialogListener {
                override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                    if (clickStatus) {
                        if (isOK) {
                            helperLog.appendLine(helperLogClassName, "Password check :: pass [EXIT PASSWORD]")
                            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> MainActivity")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            val newIntent = Intent(applicationContext, MainActivity::class.java)
                            newIntent.putExtra("closeFlag", true)
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(newIntent)
                            finish()
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: password check failed [EXIT PASSWORD]")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            ToastMake(applicationContext, "Incorrect pin", Toast.LENGTH_SHORT)
                        }
                    } else {
                        helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [EXIT PASSWORD]")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                    }
                }
            })
            return
        }

        doubleBackToExitPressedOnce = true
        helperLog.appendLine(helperLogClassName, "User Cancel :: first back press, awaiting confirmation")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        Toast.makeText(applicationContext, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "AttendActivityOxpay OnDestroy :: attended home screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        //HTTPServer.getInstance().isActive = false
        //HTTPServer.getInstance().attendActivityContext = null
    }
}