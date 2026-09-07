package com.sc.mf919pro.kotlin.fragment

import mdb.MdbController
import data_enum.ConnMethodEnum
import utils.AmountFormat
import com.sc.mf919pro.kotlin.helper_common.AppBus
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentAttendDenominationBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.AppServices
import com.sc.mf919pro.kotlin.activity.TokenPagerAdapter
import com.sc.mf919pro.kotlin.helper_common.WebSocketClientSingleton
import com.sc.mf919pro.kotlin.helper_common.WebSocketMessageListener
import com.sc.mf919pro.kotlin.activity.onAlertDialogListener
import com.sc.mf919pro.kotlin.database.model.DbModelDenominationList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.ModelMaintenanceSchedule
import com.sc.mf919pro.kotlin.database.repo.DenominationListRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import enums.EnumWebsocket
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getBooleanOrDefault
import helpers.HelperCommon.Companion.getStringOrDefault
import helpers.HelperCommon.Companion.runAfterDelayCancelable
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.AsyncLogWriter
import helpers.LogSessionMarker
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.relex.circleindicator.CircleIndicator3
import kotlin.system.exitProcess

class AttendDenominationFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var doubleBackToExitPressedOnce = false
    private var delayJob: Job? = null
    private var webSocketMessageListener: WebSocketMessageListener? = null
    val handler = Handler(Looper.getMainLooper())

    private val CODE_SETTING = 3000

    var footerText = ServiceHolder.remarkFooter
    lateinit var linearStartPurchase: LinearLayout
    lateinit var footerTV: TextView
    lateinit var tokenPager: ViewPager2
    lateinit var cirlceIndicator: CircleIndicator3
    var denominationList :List<DbModelDenominationList> = listOf()

    /**
     * Whether [denominationList] has actually been read from the database yet.
     *
     * The list decides the MDB pricing mode -- empty means the VMC prices the vend (reader level
     * 3), non-empty means the terminal does (level 1). But it is loaded in a coroutine from
     * onViewCreated, and onResume runs before that coroutine finishes, so for a moment after the
     * view is recreated the field is still the initial empty list. Passing that to
     * updatePricingMode reads as a genuine switch to VMC-pricing and makes the controller demand a
     * protocol reinitialize: 20 COMMAND OUT OF SEQUENCE (0B) frames at the VMC, then "did not
     * RESET after 20 attempts - giving up". Measured on the SR800 2026-09-07, immediately after an
     * auto-void navigated back to this screen.
     *
     * MF919 never had this: its Activity loads the list in onCreate before touching the
     * controller, and there is no second entry point. Here the flag distinguishes "no denominations
     * configured" from "not read yet".
     */
    @Volatile
    private var denominationListLoaded = false
    // Last reader state rendered, so the button is only redrawn on an actual transition.
    var enabledReader: Boolean? = null
    // UI mirror of MdbController.autoSessionEnabled: decides the button label and blocks the
    // manual tap. The session itself is owned by MdbController's supervisor, not this screen.
    var autoRequestSessionMdb = false
    // How long a tap-opened session waits for the VMC to price a vend before cancelling.
    private val vendRequestWaitSeconds = 5
    var maintenanceModel : ModelMaintenanceSchedule? = null
    private var displayType = 0
    private var pageSize = 4 // 🔧 Change to 3, 4, etc. for different pagination


    private var _binding: FragmentAttendDenominationBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendDenominationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        HTTPServer.getInstance().isActive = false
        _binding = null
        delayJob?.cancel()
        webSocketMessageListener?.stopListening()
        handler.removeCallbacks(unAttendRunnable)
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "AttendDenomination OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Attend Denomination Fragment Initialization"
        )

        HTTPServer.getInstance().isActive = true
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.saleModelCache = null

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        view.findViewById<ImageView>(R.id.headerSettingButton).setDebouncedOnClickListener{
            val msg = Message()
            msg.arg1 = CODE_SETTING
            passwordDialog(msg)
        }

        linearStartPurchase = view.findViewById(R.id.linearStartPurchase)
        tokenPager = view.findViewById(R.id.tokenViewPager)
        cirlceIndicator = view.findViewById(R.id.indicator)
        footerTV = view.findViewById(R.id.footerText)
        footerTV.text = footerText

        viewLifecycleOwner.lifecycleScope.launch {
            getDenominationPriceList()
            checkingTerminalFlag()
            showShareCommerceLogo()

            linearStartPurchase.visibility = View.GONE
            val pagedTokens = denominationList.chunked(pageSize)
            tokenPager.adapter = TokenPagerAdapter(requireContext(), pagedTokens, maintenanceModel, displayType
            ) { actionId, bundle ->
                navigateSafe(actionId, bundle)
            }
            cirlceIndicator.setViewPager(tokenPager)

            // A vend failed on the previous session: the machine took payment and did not
            // dispense, so the captured sale is voided now that we are back on this screen.
            if (MdbController.pendingVendFailed) {
                helperLog.appendLine(helperLogClassName, "Vend failed on previous session :: proceeding to auto void")
                MdbController.pendingVendFailed = false
                mdbVoidVendingFailed()
            }

            initMdbIfConfigured()

            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    AppBus.uiEvents.collect { event ->
                        when (event) {
                            is UiEvent.MdbStateChange -> mdbStateChecker()
                            is UiEvent.MdbVendingPrice -> mdbPriceChecking()
                            else -> { /* not required */ }
                        }
                    }
                }
            }

            webSocketMessageListener = WebSocketMessageListener(this,
                onMessageReceived = {
                    handleWebSocketResponse(it)
                }
            )
            webSocketMessageListener!!.startListening()
        }
    }

    private suspend fun getDenominationPriceList() = withContext(Dispatchers.IO) {
        denominationList = DenominationListRepo.getAll(requireContext())
        helperLog.appendLine(helperLogClassName, "denominationList :: ${Gson().toJson(denominationList)}")
        if(denominationList.isEmpty()) {
            requireActivity().runOnUiThread {
                showToast("Downloading Configuration.... Please wait", Toast.LENGTH_SHORT)
            }
            TmsHelper.getDenominationPrice(helperLog, requireContext())
            helperLog.appendLine(helperLogClassName, "Refresh Denomination List")
            denominationList = DenominationListRepo.getAll(requireContext())
            delay(500)
        }

        denominationListLoaded = true

        // Read before the try below: a malformed MaintenanceSchedule must not silently disable
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

                if(firstItem.DisplayMenuType.isNotEmpty()) {
                    if(firstItem.DisplayMenuType == "PACKAGE_8") {
                        displayType = 1
                        pageSize = 8
                    } else {
                        displayType = 0
                        pageSize = 4
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }

            footerText = firstItem.Remark
            footerTV.text = footerText
        } else {
            handler.postDelayed(unAttendRunnable, 20_000) // restart 20s timer
        }
    }

    val unAttendRunnable: kotlinx.coroutines.Runnable = Runnable {
        // Explicit scope (so compiler knows type)
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            delay(500)
            getDenominationPriceList()
            requireActivity().runOnUiThread {
                val pagedTokens = denominationList.chunked(pageSize)
                tokenPager.adapter = TokenPagerAdapter(requireContext(), pagedTokens, maintenanceModel, displayType
                ) { actionId, bundle ->
                    navigateSafe(actionId, bundle)
                }
                cirlceIndicator.setViewPager(tokenPager)
            }
        }
    }


    fun showShareCommerceLogo() {
        val bitmap = BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo)
        MfHelper.showAuxLcdImg(bitmap)
    }

    private fun checkingTerminalFlag() = CoroutineScope(Dispatchers.IO).launch {
        MfHelper.closeNfcUrlInterface()
        val terminalConfig = ServiceHolder.getTerminalConfig()
        val lockHome = DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")
        MfHelper.lockStatusBarAndNavigation(lockHome)

        if(ServiceHolder.autoSettlementQueue) {
            AppServices.triggerAutoSettle(requireContext(), AppServices.ACTION_RUN_AUTO_SETTLE)
        }
    }

    fun passwordDialog(msg: Message){
        passwordAlertDialog(1, "132465", object : onAlertDialogListener {
            override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                if (clickStatus) {
                    if (isOK) {
                        customHandler.sendMessage(msg)
                    } else {
                        showToast("Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
    }

    private fun handleWebSocketResponse(message: String?) {
        if (message.isNullOrEmpty()) {
            return
        }
        try{
            val gson = Gson()
            val dataJson = gson.fromJson(message, JsonObject::class.java)
            val messageEvent = dataJson.getStringOrDefault("Command", "")
            if (messageEvent == EnumWebsocket.UpdatePrice.socketCommand) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(500)
                    // Clear before re-reading: getDenominationPriceList only calls TMS when the
                    // local list is empty, so without this an UpdatePrice push would re-read the
                    // same cached rows it was sent to replace. MF919 truncates on this command in
                    // WebSocketClient; Pro handles the command here instead.
                    DenominationListRepo.truncateTable(requireContext())
                    getDenominationPriceList()
                    requireActivity().runOnUiThread {
                        val pagedTokens = denominationList.chunked(pageSize)
                        tokenPager.adapter = TokenPagerAdapter(requireContext(), pagedTokens, maintenanceModel, displayType
                        ) { actionId, bundle ->
                            navigateSafe(actionId, bundle)
                        }
                        cirlceIndicator.setViewPager(tokenPager)
                    }
                }
            }
        } catch (e: Exception) {
            println("Error parsing message: ${e.message}")
        } finally {
            hideProgress()
        }
    }

    val customHandler = object:  Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            try {
                alertDialog?.dismiss()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }

            val handleValue = msg.arg1
            if(handleValue > 0){
                when(handleValue){
                    CODE_SETTING -> {
                        helperLog.appendLine(helperLogClassName, "Custom Handler :: $handleValue")
                        navigateSafe(R.id.action_attendDenomination_to_settings)
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
            passWordActivity()
            return
        }
        helperLog.appendLine(helperLogClassName, "customOnBackPress detected :: $doubleBackToExitPressedOnce")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        doubleBackToExitPressedOnce = true
        showToast("Please click BACK again to exit", Toast.LENGTH_SHORT)
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    private fun passWordActivity() {
        passwordAlertDialog(1, "132465", object : onAlertDialogListener {
            override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                helperLog.appendLine(helperLogClassName, "Pin Checker :: $clickStatus")
                helperLog.appendLine(helperLogClassName, "is OK :: $isOK")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                if (clickStatus) {
                    if (isOK) {
                        closeApplication()
                    } else {
                        showToast("Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
        return
    }

    private fun closeApplication() {
        helperLog.appendLine(helperLogClassName, "closeApplication detected!")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        // This is a GENUINE app exit, so record it -- otherwise the next startup reports
        // "DID NOT EXIT CLEANLY" for an orderly shutdown and masks the next real kill.
        // Drain after: exitProcess takes the daemon writer thread with it.
        LogSessionMarker.closeSession("AttendDenominationFragment.closeApplication")
        AsyncLogWriter.drain()
        MfHelper.lockStatusBarAndNavigation(false)
        finishAffinity(requireActivity())
        exitProcess(0)
    }

    /**
     * Start MDB if this terminal is wired for it.
     *
     * Called from onViewCreated AND onResume, deliberately. The terminal config arrives from TMS
     * during startup, and on a cold boot this fragment is created BEFORE that download finishes --
     * so the first read of CABLE_CONNECTION returns nothing and MDB would never start. MF919 never
     * hit this because its Activity is recreated every time the terminal returns home; a Fragment
     * is created once and keeps its state. startIfConfigured() is idempotent, so re-running it is
     * free once the link is already up.
     */
    private fun initMdbIfConfigured() {
        val cfg = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getSafeValue(cfg, "CABLE_CONNECTION") == ConnMethodEnum.MDB.value) {
            // Empty list means the VMC prices the vend; a list means terminal pricing.
            //
            // Both of these wait for the list to have actually been read -- see
            // denominationListLoaded. isVmcPricingMode defaults to true, so bringing the transport
            // up first and correcting the mode afterwards makes the reader advertise level 3, then
            // change its mind to level 1, and updatePricingMode answers a mid-flight change the
            // only way it can: by demanding the VMC re-run its init sequence. That is a burst of
            // COMMAND OUT OF SEQUENCE (0B) frames at a VMC that has done nothing wrong, and if the
            // VMC does not oblige, "did not RESET after 20 attempts - giving up".
            //
            // Skipping here is safe: getDenominationPriceList calls this again once it has the
            // list, which is the call that actually starts the transport.
            if (denominationListLoaded) {
                MdbController.updatePricingMode(denominationList.isEmpty())
                MdbController.startIfConfigured()
            }
            mdbStateChecker(forceRun = true)
            linearStartPurchase.apply {
                visibility = View.VISIBLE
                setDebouncedOnClickListener {
                    helperLog.appendLine(helperLogClassName, "Selected :: Start Purchase [DENOMINATION HOME]")
                    if (MdbController.isEnableReader) {
                        if (MdbController.isVmcPricingMode || autoRequestSessionMdb) {
                            // Both modes open the vend without a tap -- VMC-pricing sends the
                            // VEND REQUEST itself, AUTO_SESSION holds a standing session. A tap
                            // here would open a second session the VMC never asked for.
                            showToast("Please Proceed from Vending Machine", Toast.LENGTH_SHORT)
                        } else {
                            handler.post(mdbSessionRunnable)
                        }
                    } else {
                        showToast("Vending Machine Not Ready", Toast.LENGTH_SHORT)
                    }
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                }
            }

            // The pager has to go, not just lose priority. onViewCreated populates it
            // unconditionally, so without this it stays on top of the Start Purchase button and
            // swallows the tap -- and on the SR800 (480x480, ~439dp square, less than half the
            // height of an MF919) there is no spare vertical space for both to coexist.
            // MF919 does this in the same branch; the port dropped these two lines.
            tokenPager.visibility = View.GONE
            cirlceIndicator.visibility = View.GONE
        } else {
            // Not MDB: the inverse. Stated rather than assumed because this runs again from
            // onResume, so the screen must be able to go back to picking a denomination by hand
            // if the config changes under it.
            linearStartPurchase.visibility = View.GONE
            tokenPager.visibility = View.VISIBLE
            cirlceIndicator.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Being back on the home screen means no vend is in flight. MF919 resets these in
        // AttendDenominationActivity.onCreate, which runs on every return home because it is an
        // Activity; this Fragment is created once and reused, so onResume is the equivalent point.
        // Belt and braces with the resets in MdbController's cycle ends -- this also covers a
        // return home by any route that never reached a cycle end at all.
        MdbController.isVending = false
        MdbController.mdbVending = false
        // See initMdbIfConfigured: the config may only have arrived after this screen was built.
        initMdbIfConfigured()
        // Repaint from whatever state the controller is in now, in case reader events were
        // published while this fragment was not resumed.
        mdbStateChecker(forceRun = true)
    }

    /**
     * Repaints the purchase button for the reader's enable state.
     *
     * Only redraws on an actual transition unless [forceRun], because the VMC enables the reader
     * on every poll cycle in some configurations.
     */
    fun mdbStateChecker(forceRun: Boolean = false) {
        if (!forceRun && enabledReader == MdbController.isEnableReader) return
        enabledReader = MdbController.isEnableReader
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val label = view?.findViewById<TextView>(R.id.textViewTerminalPay1) ?: return@runOnUiThread
            if (enabledReader == true) {
                linearStartPurchase.background =
                    AppCompatResources.getDrawable(requireContext(), R.drawable.custom_ripple_with_stoke)
                // No session work here: MdbController's supervisor opens it on its next tick once
                // the reader reports enabled.
                label.setText(
                    if (MdbController.isVmcPricingMode || autoRequestSessionMdb) R.string.vmc_purchase
                    else R.string.start_purchase
                )
            } else {
                label.setText(R.string.initializing)
                linearStartPurchase.background =
                    AppCompatResources.getDrawable(requireContext(), R.drawable.custom_red_with_stoke)
            }
        }
    }

    /**
     * Tap-driven session (terminal pricing, no AUTO_SESSION): open a session and wait for the VMC
     * to price a vend. If nothing arrives within vendRequestWaitSeconds the session is cancelled.
     */
    val mdbSessionRunnable: Runnable = Runnable {
        CoroutineScope(Dispatchers.Default).launch {
            MdbController.isRequestSession = true
            MdbController.requestStartSession()
            activity?.runOnUiThread { showProgress("", "Loading...") }
            var vendingDeny = false
            var sessionTimeout = false
            var count = 0
            while (MdbController.mdbLoading) {
                count++
                delay(1000)
                if (count >= vendRequestWaitSeconds && MdbController.mdbLoading) {
                    vendingDeny = true
                    sessionTimeout = true
                    MdbController.mdbLoading = false
                    helperLog.appendLine(helperLogClassName,
                        "Vend session TIMEOUT :: no vend request after ${vendRequestWaitSeconds}s")
                }
            }
            if (MdbController.mdbVendingForceEnd) {
                helperLog.appendLine(helperLogClassName, "Vending Deny")
                vendingDeny = true
            }
            activity?.runOnUiThread { hideProgress() }
            MdbController.isRequestSession = false

            if (!vendingDeny) {
                mdbPriceChecking()
            } else {
                MdbController.isVending = false
                MdbController.mdbVendingForceEnd = false
                if (sessionTimeout) {
                    // No VEND REQUEST arrived for our BEGIN SESSION - ask the VMC to close it.
                    MdbController.requestSessionCancel()
                }
                helperLog.appendLine(helperLogClassName, "Vend session DENIED :: sessionTimeout=$sessionTimeout")
                activity?.runOnUiThread {
                    showToast("Vending Request Failed. \nPlease Try Again", Toast.LENGTH_SHORT)
                }
            }
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    /**
     * A price is available: go to the payment screen. With no price yet, show the denomination
     * list and arm a short timer that denies the vend rather than leaving the VMC waiting.
     */
    fun mdbPriceChecking() {
        helperLog.appendLine(helperLogClassName, "Price Hex :: ${MdbController.priceHex}")
        if (MdbController.priceHex.isNotEmpty()) {
            MdbController.mdbVending = true
            MdbController.isVending = true
            val decimalPrice = MdbController.priceHex.toInt(16).toString()
            val product = DbModelDenominationList(
                "", "", "", AmountFormat.getActualAmount(decimalPrice), "", decimalPrice,
                "", "", "", "", "", ""
            )
            helperLog.appendLine(helperLogClassName,
                "Vend price received :: amount=${AmountFormat.getActualAmount(decimalPrice)}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            activity?.runOnUiThread {
                navigateSafe(
                    R.id.action_attendDenomination_to_denominationPaymentOption,
                    bundleOf("denomination_product" to Gson().toJson(product))
                )
            }
        } else {
            handler.postDelayed(vendingSessionRunnable, 10_000)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                MdbController.mdbVending = true
                MdbController.isVending = true
                linearStartPurchase.visibility = View.GONE
                tokenPager.visibility = View.VISIBLE
                cirlceIndicator.visibility = View.VISIBLE
                val pagedTokens = denominationList.chunked(pageSize)
                tokenPager.adapter = TokenPagerAdapter(
                    requireContext(), pagedTokens, maintenanceModel, displayType
                ) { actionId, bundle -> navigateSafe(actionId, bundle) }
                cirlceIndicator.setViewPager(tokenPager)
            }
        }
    }

    /** No price arrived within the window - answer the VMC rather than leave the vend open. */
    val vendingSessionRunnable: Runnable = Runnable {
        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            if (MdbController.mdbVending) {
                MdbController.isVending = false
                MdbController.mdbVending = false
                helperLog.appendLine(helperLogClassName,
                    "Vend DENIED :: no price received within timeout, notifying VMC")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                MdbController.sendVendDenied()
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    linearStartPurchase.visibility = View.VISIBLE
                    tokenPager.visibility = View.GONE
                    cirlceIndicator.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Money was taken but the machine did not dispense. Void the captured payment unattended.
     *
     * A QR-funded vend cannot go through the card void: that searches the card batch by invoice,
     * while the QR sale exists only in the QR table keyed by refId. Routing a QR vend into the
     * card void finds nothing and the customer stays charged. forceVoid skips the confirmation
     * dialog - nobody is standing at an unattended machine.
     */
    fun mdbVoidVendingFailed() {
        val txnInvoice = MdbController.pendingVendFailedInvoice ?: transData.invoiceNo
        val qrRef = MdbController.pendingVendFailedQrRef ?: ""
        MdbController.pendingVendFailedInvoice = null
        MdbController.pendingVendFailedQrRef = null

        if (qrRef.isNotEmpty()) {
            helperLog.appendLine(helperLogClassName, "Voiding Prev QR Txn :: refId $qrRef")
            try {
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                navigateSafe(
                    R.id.action_attendDenomination_to_voidQr,
                    bundleOf("Invoice" to qrRef, "forceVoid" to 1)
                )
            } catch (ex: Exception) {
                helperLog.appendLine(helperLogClassName, "Vend-failed QR auto void error :: ${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                MdbController.reportRefundError()
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
            val dbProductModel = ProductListRepo.getSinglev2(
                requireContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)
            ) ?: throw Exception("Card product not found")
            ServiceHolder.selectedCacheModel = SalesModel(
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
            val saleModelNew = Gson().fromJson(Gson().toJson(dbProductModel), SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew

            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(
                R.id.action_attendDenomination_to_voidSale,
                bundleOf("Invoice" to txnInvoice, "forceVoid" to 1)
            )
        } catch (ex: Exception) {
            helperLog.appendLine(helperLogClassName, "Vend-failed auto void error :: ${ex.message}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            MdbController.reportRefundError()
        }
    }

}

private const val AUTO_SESSION_DESC = "AUTO_SESSION"

/**
 * True when this row is the AUTO_SESSION marker rather than a purchasable denomination.
 * Matched on Desc only - the Amount-0 convention is a config safeguard, so a row that got the
 * Desc but not the Amount must still switch the mode on rather than fail silently.
 * [Desc] is declared non-null but is populated by Gson/SQLite, so it can arrive null.
 */
private fun DbModelDenominationList.isAutoSessionRow(): Boolean {
    val desc: String? = Desc
    return desc?.trim()?.uppercase() == AUTO_SESSION_DESC
}
