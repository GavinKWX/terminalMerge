package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import data_enum.ConnMethodEnum
import enums.EnumWebsocket
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.ModelMaintenanceSchedule
import com.sc.mf919.kotlin.database.repo.DenominationListRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.AppBus
import com.sc.mf919.kotlin.helper_common.HTTPServer
import mdb.MdbController
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.MfHelper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.UiEvent
import com.sc.mf919.kotlin.helper_common.WebSocketMessageListener
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getStringOrDefault
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.relex.circleindicator.CircleIndicator3

class AttendDenominationActivity : ActivityBase() {
    lateinit var mContext: Context
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String
    private var doubleBackToExitPressedOnce = false
    private var webSocketMessageListener: WebSocketMessageListener? = null
    val handler = Handler(Looper.getMainLooper())

    private val CODE_SETTING = 3000

    var footerText = ServiceHolder.remarkFooter
    //var enabledReader = false
    var enabledReader: Boolean? = null
    // UI-only mirror of MdbController.autoSessionEnabled: decides the button label and blocks
    // the manual tap. The session itself is owned by MdbController's supervisor.
    var autoRequestSessionMdb = false
    lateinit var linearStartPurchase: LinearLayout
    lateinit var footerTV: TextView
    lateinit var tokenPager: ViewPager2
    lateinit var cirlceIndicator: CircleIndicator3
    var denominationList :List<DbModelDenominationList> = listOf()

    // The AUTO_SESSION row carries config (footer, schedule, menu type) with Amount 0 - it is
    // not a purchasable token. It has to stay in denominationList (an empty list flips the
    // reader to VMC pricing) but must never render as something the customer can tap.
    private val displayDenominationList: List<DbModelDenominationList>
        get() = denominationList.filterNot { it.isAutoSessionRow() }

    var maintenanceModel : ModelMaintenanceSchedule? = null
    private var displayType = 0
    private var pageSize = 4 // 🔧 Change to 3, 4, etc. for different pagination
    private var fetchPriceDelay: Long = 20_000

    // Seconds to wait for the VMC's VEND REQUEST after our BEGIN SESSION before we
    // cancel the session. This is how long the machine user has to make a selection
    // once "Start Purchase" is tapped - raise it if selections regularly time out.
    private val vendRequestWaitSeconds = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attend_denomination)

        //TODO Dynamic Layout For Small Terminal
        if (MfHelper.isSmallTerminal()) {
//            pageSize = 8
//            displayType = 1
            val headerLayout = findViewById<LinearLayout>(R.id.headerLayout)
            headerLayout.setPadding(0, 0, 0, 0)
        }
        //TODO Dynamic Layout For Small Terminal

        onBackPressedDispatcher.addCallback(this@AttendDenominationActivity, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        mContext = this@AttendDenominationActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Attend Denomination Activity"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Denomination Attend Activity")

        if (Helper.getInstance().getPrefs() == null) {
            Helper.getInstance().Initialize(applicationContext)
        }

        if (MainActivity.isFirstRun) {
            MainActivity.isFirstRun = false
        }

        MdbController.isVending = false
        MdbController.mdbVending = false
        HTTPServer.getInstance().isActive = true
        HTTPServer.getInstance().attendActivityContext = this@AttendDenominationActivity
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.saleModelCache = null

        findViewById<ImageView>(R.id.headerSettingButton).setDebouncedOnClickListener{
            helperLog.appendLine(helperLogClassName, "Selected :: Settings [DENOMINATION HOME]")
            val msg = Message()
            msg.arg1 = CODE_SETTING
            passwordDialog(msg)
        }

        /*findViewById<TextView>(R.id.tvIP).apply {
            text = "IP :: ${ServiceHolder.getCurrentLocalIpAddress()}"
            //text = "Terminal :: ${ServiceHolder.getTerminalSerialNumber()}"
        }*/

        linearStartPurchase = findViewById<LinearLayout>(R.id.linearStartPurchase)
        tokenPager = findViewById<ViewPager2>(R.id.tokenViewPager)
        cirlceIndicator = findViewById<CircleIndicator3>(R.id.indicator)
        footerTV = findViewById<TextView>(R.id.footerText)
        footerTV.text = footerText

        if (MdbController.pendingVendFailed) {
            helperLog.appendLine(helperLogClassName, "Vend failed on previous session :: proceeding to auto void")
            MdbController.pendingVendFailed = false
            mdbVoidVendingFailed()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect{ event ->
                    when(event) {
                        is UiEvent.MdbStateChange -> {
                            mdbStateChecker()
                        }
                        is UiEvent.MdbVendingPrice -> {
                            mdbPriceChecking()
                        }
                        else -> {}
                    }
                }
            }
        }

        lifecycleScope.launch {
            getDenominationPriceList()
            checkingTerminalFlag()

            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
            if(connMethod == ConnMethodEnum.MDB.value) {
                fetchPriceDelay = 600_000
                MdbController.updatePricingMode(denominationList.isEmpty())
                mdbStateChecker()
                MdbController.startIfConfigured()
                linearStartPurchase.apply {
                    visibility = View.VISIBLE
                    setDebouncedOnClickListener {
                        helperLog.appendLine(helperLogClassName, "Selected :: Start Purchase [DENOMINATION HOME]")
                        if(MdbController.isEnableReader) {
                            if(MdbController.isVmcPricingMode || autoRequestSessionMdb) {
                                helperLog.appendLine(helperLogClassName, "REJECT :: selection must be made on the vending machine (vmcPricing=${MdbController.isVmcPricingMode} autoSession=$autoRequestSessionMdb)")
                                helperLog.logToFile(EnumLogFileName.TerminaLog)
                                runOnUiThread {
                                    Toast.makeText(mContext, "Please Proceed from Vending Machine", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Handler(Looper.getMainLooper()).post(mdbSessionRunnable)
                            }
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: MDB reader not ready yet")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            runOnUiThread {
                                Toast.makeText(mContext, "Initializing. Hold on...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                tokenPager.visibility = View.GONE
                cirlceIndicator.visibility = View.GONE
            } else {
                linearStartPurchase.visibility = View.GONE
                val pagedTokens = displayDenominationList.chunked(pageSize)
                tokenPager.adapter = TokenPagerAdapter(mContext, pagedTokens, maintenanceModel, displayType)
                cirlceIndicator.setViewPager(tokenPager)
            }

            webSocketMessageListener = WebSocketMessageListener(this,
                onMessageReceived = {
                    handleWebSocketResponse(it)
                }
            )
            webSocketMessageListener!!.startListening()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private fun handleWebSocketResponse(message: String?) {
        if (message.isNullOrEmpty()) {
            return
        }

        try{
            val dataMessage = JsonParser.parseString(message)
            val dataJson = when {
                dataMessage.isJsonObject -> {
                    dataMessage.asJsonObject
                }

                dataMessage.isJsonPrimitive && dataMessage.asJsonPrimitive.isString -> {
                    // unwrap double-encoded JSON
                    val inner = dataMessage.asString
                    JsonParser.parseString(inner).asJsonObject
                }

                else -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: ignoring non-JSON websocket message")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                    return
                }
            }
            val messageEvent = dataJson.getStringOrDefault("Command", "")
            if (messageEvent == EnumWebsocket.UpdatePrice.socketCommand) {
               CoroutineScope(Dispatchers.Default).launch {
                   delay(500)
                   getDenominationPriceList()
                   runOnUiThread {
                       val pagedTokens = displayDenominationList.chunked(pageSize)
                       tokenPager.adapter = TokenPagerAdapter(mContext, pagedTokens, maintenanceModel, displayType)
                       cirlceIndicator.setViewPager(tokenPager)
                   }
               }
            }
        } catch (e: Exception) {
            helperLog.appendLine(helperLogClassName, "Websocket message parse error :: ${e.message}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    val unAttendRunnable:Runnable = Runnable {
        // Explicit scope (so compiler knows type)
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            delay(500)
            getDenominationPriceList()
            runOnUiThread {
                val pagedTokens = displayDenominationList.chunked(pageSize)
                tokenPager.adapter = TokenPagerAdapter(mContext, pagedTokens, maintenanceModel, displayType)
                cirlceIndicator.setViewPager(tokenPager)
            }
        }
    }

    fun mdbStateChecker(forceRun: Boolean = false) {
        if(forceRun || enabledReader != MdbController.isEnableReader) {
            enabledReader = MdbController.isEnableReader
            runOnUiThread {
                if (enabledReader == true) {
                    linearStartPurchase.background = AppCompatResources.getDrawable(applicationContext, R.drawable.custom_ripple_with_stoke)
                    if (MdbController.isVmcPricingMode || autoRequestSessionMdb) {
                        // No session work here: MdbController's supervisor opens it on its next
                        // tick once the reader reports enabled.
                        findViewById<TextView>(R.id.textViewTerminalPay1).setText(R.string.vmc_purchase)
                    } else {
                        findViewById<TextView>(R.id.textViewTerminalPay1).setText(R.string.start_purchase)
                    }
                } else {
                    findViewById<TextView>(R.id.textViewTerminalPay1).setText(R.string.initializing)
                    linearStartPurchase.background = AppCompatResources.getDrawable(applicationContext, R.drawable.custom_red_with_stoke)
                }
            }
        }
    }

    val mdbSessionRunnable:Runnable = Runnable {
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            MdbController.isRequestSession = true
            MdbController.requestStartSession()
            startProgressDialog(this@AttendDenominationActivity, "", "Loading...")
            var vendingDeny = false
            var sessionTimeout = false

            val waitSeconds = vendRequestWaitSeconds

            var count = 0
            while (MdbController.mdbLoading) {
                count++
                delay(1000)
                if(count >= waitSeconds && MdbController.mdbLoading) {
                    vendingDeny = true
                    sessionTimeout = true
                    MdbController.mdbLoading = false
                    helperLog.appendLine(helperLogClassName, "Vend session TIMEOUT :: no vend request after ${waitSeconds}s")
                }
            }
            //Delay Two second to wait for price, if no price will show price list
            if(MdbController.mdbVendingForceEnd) {
                helperLog.appendLine(helperLogClassName, "Vending Deny")
                vendingDeny = true
            }
            closeProgressDialog()
            MdbController.isRequestSession = false

            if(!vendingDeny) {
                mdbPriceChecking()
            } else {
                //Vending Deny Flow
                MdbController.isVending = false
                MdbController.mdbVendingForceEnd = false
                if (sessionTimeout) {
                    // no VEND REQUEST arrived for our BEGIN SESSION - ask the VMC to close the session
                    MdbController.requestSessionCancel()
                }
                helperLog.appendLine(helperLogClassName, "Vend session DENIED :: sessionTimeout=$sessionTimeout")
                runOnUiThread {
                    Toast.makeText(mContext, "Vending Request Failed. \nPlease Try Again", Toast.LENGTH_SHORT).show()
                }
            }
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    fun mdbPriceChecking() {
        helperLog.appendLine(helperLogClassName, "Price Hex :: ${MdbController.priceHex}")
        if(MdbController.priceHex.isNotEmpty()) {
            MdbController.mdbVending = true
            MdbController.isVending = true
            val decimalPrice = MdbController.priceHex.toInt(16).toString()
            val customDenominationProduct: DbModelDenominationList = DbModelDenominationList("", "", "", Utils.getActualAmount(decimalPrice),
                "", decimalPrice, "", "", "", "", "", "")

            helperLog.appendLine(helperLogClassName, "Vend price received :: amount=${Utils.getActualAmount(decimalPrice)}")
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> DenominationPaymentOptionActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val newIntent = Intent(this@AttendDenominationActivity, DenominationPaymentOptionActivity::class.java)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            newIntent.putExtra("denomination_product", Gson().toJson(customDenominationProduct))
            startActivity(newIntent)
            finish()
        } else {
            // restart 10s timer
            handler.postDelayed(vendingSessionRunnable, 10_000)
            runOnUiThread {
                MdbController.mdbVending = true
                MdbController.isVending = true
                linearStartPurchase.visibility = View.GONE
                tokenPager.visibility = View.VISIBLE
                cirlceIndicator.visibility = View.VISIBLE
                val pagedTokens = displayDenominationList.chunked(pageSize)
                tokenPager.adapter = TokenPagerAdapter(mContext, pagedTokens, maintenanceModel, displayType)
                cirlceIndicator.setViewPager(tokenPager)
            }
        }
    }

    val vendingSessionRunnable: Runnable = Runnable {
        // Explicit scope (so compiler knows type)
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            delay(500)
            if(MdbController.mdbVending) {
                MdbController.isVending = false
                MdbController.mdbVending = false
                helperLog.appendLine(helperLogClassName, "Vend DENIED :: no price received within timeout, notifying VMC")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                MdbController.sendVendDenied()
                runOnUiThread {
                    linearStartPurchase.visibility = View.VISIBLE
                    tokenPager.visibility = View.GONE
                    cirlceIndicator.visibility = View.GONE
                }
            }
        }
    }

    fun mdbVoidVendingFailed() {
        val txnInvoice = MdbController.pendingVendFailedInvoice ?: TransData.invoiceNo
        val qrRef = MdbController.pendingVendFailedQrRef ?: ""
        MdbController.pendingVendFailedInvoice = null
        MdbController.pendingVendFailedQrRef = null

        // A QR-funded vend cannot be voided through VoidSaleActivity: that searches the CARD batch
        // by invoice number, while the QR sale only exists in the QR table keyed by refId. Routing
        // a QR vend into the card void found nothing, so the customer stayed charged with nothing
        // dispensed. forceVoid skips the confirmation dialog - nobody is at an unattended machine.
        if (qrRef.isNotEmpty()) {
            helperLog.appendLine(helperLogClassName, "Voiding Prev QR Txn :: refId $qrRef")
            try {
                helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidQrActivity (auto void refId $qrRef)")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                val qrIntent = Intent(ServiceHolder.getContext(), VoidQrActivity::class.java)
                qrIntent.putExtra("Invoice", qrRef)   // VoidQrActivity reads this extra as the qrRefId
                qrIntent.putExtra("forceVoid", 1)
                qrIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                ServiceHolder.getContext().startActivity(qrIntent)
            } catch (ex: Exception) {
                helperLog.appendLine(helperLogClassName, "Vend-failed QR auto void error :: ${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                MdbController.reportRefundError()
                ex.printStackTrace()
            }
            return
        }

        helperLog.appendLine(helperLogClassName, "Voiding Prev Txn :: $txnInvoice")
        if (txnInvoice.isEmpty()) {
            helperLog.appendLine(helperLogClassName, "Vend failed but no invoice to void!")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            MdbController.reportRefundError()
            return
        }

        try {
            val dbProductModel = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception("Card product not found")
            val salesModel = SalesModel(
                ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                dbProductModel.Product,
                dbProductModel.AcqCode,
                dbProductModel.AcqMid,
                dbProductModel.AcqTid,
                dbProductModel.QrProductCode,
                dbProductModel.ProductName,
                dbProductModel.EppProductCode,
                dbProductModel.EppTenure,
                dbProductModel.EppTenureCode
            )
            ServiceHolder.selectedCacheModel = salesModel

            val jsonProductList = Gson().toJson(dbProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew

            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidSaleActivity (auto void invoice $txnInvoice)")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val newIntent = Intent(ServiceHolder.getContext(), VoidSaleActivity::class.java)
            newIntent.putExtra("Invoice", txnInvoice)
            newIntent.putExtra("forceVoid", 1)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            ServiceHolder.getContext().startActivity(newIntent)
        } catch (ex: Exception) {
            helperLog.appendLine(helperLogClassName, "Vend-failed auto void error :: ${ex.message}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            MdbController.reportRefundError()
            ex.printStackTrace()
        }
    }

    private suspend fun getDenominationPriceList() = withContext(Dispatchers.IO) {
        denominationList = DenominationListRepo.getAll(mContext)
        helperLog.appendLine(helperLogClassName, "denominationList :: ${Gson().toJson(denominationList)}")
        if(denominationList.isEmpty()) {
            runOnUiThread {
                Toast.makeText(mContext, "Downloading Configuration.... Please wait", Toast.LENGTH_SHORT).show()
            }
            TmsHelper.getDenominationPrice(helperLog, mContext)
            helperLog.appendLine(helperLogClassName, "Refresh Denomination List")
            denominationList = DenominationListRepo.getAll(mContext)
            delay(500)
        }

        // Assigned unconditionally, outside the isNotEmpty() block below: if the list later comes
        // back empty the terminal is VMC-pricing and AUTO_SESSION must switch OFF. Setting it only
        // when a row exists left the previous value latched, so a terminal that dropped to an empty
        // list would keep a standing session it should no longer hold.
        // Read before the try below too: a malformed MaintenanceSchedule must not silently disable
        // auto-session, which would leave the terminal with no way to open a vend.
        autoRequestSessionMdb = denominationList.firstOrNull()?.isAutoSessionRow() ?: false
        MdbController.autoSessionEnabled = autoRequestSessionMdb
        helperLog.appendLine(helperLogClassName, "autoRequestSessionMdb :: $autoRequestSessionMdb")

        if(denominationList.isNotEmpty()) {
            val firstItem = denominationList[0]

            try{
                val type = object : TypeToken<List<ModelMaintenanceSchedule>>() {}.type
                val maintenanceList: List<ModelMaintenanceSchedule> = Gson().fromJson(firstItem.MaintenanceSchedule, type)
                maintenanceModel = maintenanceList.firstOrNull()

                if(firstItem.DisplayMenutype.isNotEmpty()) {
                    if(firstItem.DisplayMenutype == "PACKAGE_8") {
                        displayType = 1
                        pageSize = 8
                    } else {
                        displayType = 0
                        pageSize = 4
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Maintenance schedule parse error :: ${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }

            footerText = firstItem.Remark
            footerTV.text = footerText
        } else {
            handler.postDelayed(unAttendRunnable, fetchPriceDelay) // restart retry timer
        }
        MdbController.updatePricingMode(denominationList.isEmpty())
        mdbStateChecker(true)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun checkingTerminalFlag() = CoroutineScope(Dispatchers.IO).launch {
        MfHelper.closeNfcUrlInterface()
        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")) {
            MfHelper.lockStatusBarAndNavigation(true)
        } else {
            MfHelper.lockStatusBarAndNavigation(false)
        }
    }

    fun passwordDialog(msg: Message){
        helperLog.appendLine(helperLogClassName, "Dialog opened :: [SUPERVISOR PASSWORD] action=${msg.arg1}")
        passwordAlertDialog(1, "132465", object : onAlertDialogListener {
            override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                if (clickStatus) {
                    if (isOK) {
                        customHandler.sendMessage(msg)
                    } else {
                        helperLog.appendLine(helperLogClassName, "REJECT :: incorrect supervisor pin, action=${msg.arg1}")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        ToastMake(mContext, "Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
    }

    val customHandler = object:  Handler(Looper.getMainLooper()) {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun handleMessage(msg: Message) {
            try {
                alertDialog?.dismiss()
            } catch (ex: Exception) {
                ex.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Dismiss password dialog (Exception) -> ${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }

            val handleValue = msg.arg1
            if(handleValue > 0){
                when(handleValue){
                    CODE_SETTING -> {
                        helperLog.appendLine(helperLogClassName, "Password accepted :: navigate -> SettingsActivity")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        val newIntent = Intent(mContext, SettingsActivity::class.java)
                        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(newIntent)
                        finish()
                    }
                    else -> {
                        helperLog.appendLine(helperLogClassName, "Custom Handler Out of Cases :: $handleValue")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                    }
                }
            }
        }
    }

    fun customOnBackPress() {
        if (doubleBackToExitPressedOnce) {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [EXIT DENOMINATION PASSWORD]")
            passwordAlertDialog(1, "132465", object : onAlertDialogListener {
                override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                    if (clickStatus) {
                        if (isOK) {
                            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> MainActivity (exit denomination home)")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            MfHelper.lockStatusBarAndNavigation(true)
                            val intent = Intent(this@AttendDenominationActivity, MainActivity::class.java)
                            intent.putExtra("closeFlag", true)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                            finish()
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: incorrect exit pin")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            ToastMake(this@AttendDenominationActivity, "Incorrect pin", Toast.LENGTH_SHORT)
                        }
                    }
                }
            })
            return
        }

        helperLog.appendLine(helperLogClassName, "User Cancel :: first back press, awaiting confirmation")
        doubleBackToExitPressedOnce = true
        Toast.makeText(applicationContext, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        HTTPServer.getInstance().attendActivityContext = this
    }

    override fun onResume() {
        super.onResume()
        HTTPServer.getInstance().attendActivityContext = this
        // Being on the home screen means no payment screen owns the bus.
        // sendVendApproved/Denied normally clear this; this is the self-heal for any path
        // that exits without answering a vend, so a stale flag can never stop the
        // supervisor reopening the session.
        MdbController.paymentUiActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "AttendDenomination OnDestroy :: denomination home screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        //HTTPServer.getInstance().isActive = false
        //HTTPServer.getInstance().attendActivityContext = null
        MdbController.isAttendReady = false
        webSocketMessageListener?.stopListening()
        handler.removeCallbacks(unAttendRunnable)
        handler.removeCallbacks(mdbSessionRunnable)
        handler.removeCallbacks(vendingSessionRunnable)
    }
}

/** Marker row (Desc "AUTO_SESSION", Amount 0) that puts the reader in always-open-session mode. */
private const val AUTO_SESSION_DESC = "AUTO_SESSION"

/**
 * True when this row is the AUTO_SESSION marker rather than a purchasable denomination.
 * Matched on Desc only - the Amount-0 convention is a config safeguard, so a row that got
 * the Desc but not the Amount must still switch the mode on rather than fail silently.
 * [Desc] is declared non-null but is populated by Gson/SQLite, so it can arrive null.
 */
private fun DbModelDenominationList.isAutoSessionRow(): Boolean {
    val desc: String? = Desc  // declared non-null, but Gson/SQLite can still put null here
    return desc?.trim()?.uppercase() == AUTO_SESSION_DESC
}