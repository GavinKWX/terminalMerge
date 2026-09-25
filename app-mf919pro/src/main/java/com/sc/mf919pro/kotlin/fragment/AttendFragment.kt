package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919pro.BuildConfig
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentAttendBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.AppServices
import com.sc.mf919pro.kotlin.activity.PaperRollSuccessDialogFragment
import ws.WebSocketClientSingleton
import ws.WebSocketMessageListener
import com.sc.mf919pro.kotlin.activity.onAlertDialogListener
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import datastore.DataStoreManager
import datastore.PrefKeys
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

class AttendFragment : BaseFragment(), FragmentImageSlider.OnFragmentInteractionListener {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var doubleBackToExitPressedOnce = false
    private var delayJob: Job? = null
    private var webSocketMessageListener: WebSocketMessageListener? = null

    private var _binding: FragmentAttendBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        delayJob?.cancel()
        webSocketMessageListener?.stopListening()
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Attend OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun fragmentImageSlideAction() { /*customOnBackPress()*/ }

    private fun processRenderBannerImg() {
        childFragmentManager.beginTransaction().replace(R.id.bannerImageView, FragmentImageSlider()).addToBackStack(null).commit()
    }

    /*override fun onResume() {
        super.onResume()
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
            MfHelper.lockStatusBarAndNavigation(true)
        }
    }*/

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //val runningFlavor = BuildConfig.FLAVOR
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Attend Fragment Initialization"
        )
        //helperLog.appendLine(helperLogClassName, "Checking Flavor:: $runningFlavor")
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.saleModelCache = null

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        val merchantConfig = ServiceHolder.getMerchantInfo()
        binding.merchantNameTextView.text = merchantConfig?.MerchantName ?: ""
        // Button Listener
        binding.saleBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Sale Button OnClick")
            saleBtn(merchantConfig)
        }
        binding.voidBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Void Button OnClick")
            if (ServiceHolder.autoSettlementIsRunning) {
                showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            } else {
                navigateSafe(R.id.action_attend_to_voidOption)
            }
        }
        binding.settlementBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Settlement Button OnClick")
            if (ServiceHolder.autoSettlementIsRunning) {
                showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            } else {
                navigateSafe(R.id.action_attend_to_settlement)
            }
        }
        binding.historyBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "History Button OnClick")
            if (ServiceHolder.autoSettlementIsRunning) {
                showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            } else {
                selectionDialogTxnHistoryTypeActionViewer()
            }
        }

        binding.paperRollRequestButton.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Paper Roll Request Button OnClick")
            requestPaperRoll()
        }
        binding.contactUsButton.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Contact Us Button OnClick")
            navigateSafe(R.id.action_attend_to_contactus)
        }
        binding.appInfoButton.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "App Info Button OnClick")
            navigateSafe(R.id.action_attend_to_about)
        }

        processRenderBannerImg()
        viewLifecycleOwner.lifecycleScope.launch {
            checkingTerminalFlag()
            showShareCommerceLogo()

            webSocketMessageListener = WebSocketMessageListener(this,
                onMessageReceived = {
                    handleWebSocketResponse(it)
                }
            )
            webSocketMessageListener!!.startListening()
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

    private fun saleBtn(merchantConfig: DbModelMerchantConfig?) {
        lifecycleScope.launch {
            val error = checkingSaleBlocking()
            if (error != null) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val merchantProduct: List<String>? = ServiceHolder.getMerchantProduct()
            if(!merchantProduct.isNullOrEmpty()) {
                val salesOption = listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, ProductCatSelectionDataEnum.GENERATE_QR.name)
                val containsAny = salesOption.any { it in merchantProduct }

                if(containsAny) {
                    if(DbModelMerchantConfig.getSafeValue(merchantConfig, "SkipTxnValidation") == "true") {
                        val terminalConfig = ServiceHolder.getTerminalConfig()
                        val enableCard = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")
                        val enableEWallet = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_EWALLET")
                        saleNavigation(enableCard, enableEWallet)
                    } else {
                        webSocketSaleValidation(merchantConfig?.AcqMid ?: "")
                    }
                    return@launch
                }
            }
            showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
        }
    }

    private fun selectionDialogTxnHistoryTypeActionViewer() {
        val merchantProduct = ServiceHolder.getMerchantProduct()
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomTransparentAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams") val dialogView = inflater.inflate(
            R.layout.fragment_txn_type_history_actionselection, null
        )

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.action_cancel_btn)
        cancelBtn.setOnClickListener {
            alertDialog?.dismiss()
        }

        val cardBtn = dialogView.findViewById<LinearLayout>(R.id.cardButton)
        cardBtn.visibility = View.GONE

        val ewBtn = dialogView.findViewById<LinearLayout>(R.id.ewButton)
        ewBtn.visibility = View.GONE


        var count = 0
        try {
            var isQRShow = false
            for (a in merchantProduct!!) {
                val (_, _, _, active, _, _, printNextClass, _) = ProductCatSelectionDataEnum.valueOf(
                    a
                ).data
                if (!active || printNextClass == null || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
                    // Category not yet configure and active on terminal
                    continue
                }

                if ((a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && !isQRShow) { // GENERATE_QR & EWALLET
                    isQRShow = true
                    ewBtn.visibility = View.VISIBLE
                    ewBtn.setOnClickListener {
                        alertDialog?.dismiss()
                        helperLog.appendLine(helperLogClassName, "Ewallet History Button OnClick")
                        navigateSafe(R.id.action_attend_to_transactionViewListQr)
                    }
                } else { // CARD_SETTINGS/BNPL
                    cardBtn.visibility = View.VISIBLE
                    cardBtn.setOnClickListener {
                        alertDialog?.dismiss()
                        helperLog.appendLine(helperLogClassName, "Card History Button OnClick")
                        navigateSafe(R.id.action_attend_to_transactionViewList)
                    }

                }
                count++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        alertDialogBuilder.setCancelable(false)
        alertDialogBuilder.setView(dialogView)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    fun webSocketSaleValidation(mid: String) {
        showProgress("Loading", "Loading Terminal Configuration...")
        val requestJson = JsonObject()
        val dataReq = JsonObject()
        requestJson.addProperty("RequestRef", "")
        requestJson.addProperty("MsgEvent", "ValidateSales")

        dataReq.addProperty("DEV_SN", ServiceHolder.getTerminalSerialNumber())
        dataReq.addProperty("MID", mid)
        requestJson.addProperty("DataReq", dataReq.toString())
        WebSocketClientSingleton.send(Gson().toJson(requestJson))

        // Fail Safety for timeout
        viewLifecycleOwner.lifecycleScope.launch {
            delayJob = runAfterDelayCancelable(10 * 1000) {
                try{
                    hideProgress()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
                showBlockSalesAlert()
            }
        }
    }

    private fun showBlockSalesAlert() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("Information")
        builder.setMessage("Please Update to Latest App or Contact Sales Support for Assistance")

        // Set the positive button and its action
        builder.setNegativeButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        // Set the negative button and its action
        /*builder.setPositiveButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }*/

        // Create and show the alert dialog
        val alertDialog: androidx.appcompat.app.AlertDialog = builder.create()
        alertDialog.show()
    }

    private fun handleWebSocketResponse(message: String?) {
        if (message.isNullOrEmpty()) {
            return
        }
        try{
            val gson = Gson()
            val dataJson = gson.fromJson(message, JsonObject::class.java)
            val messageEvent = dataJson.getStringOrDefault("MsgEvent", "")
            if (messageEvent == EnumWebsocket.ValidateSale.socketCommand) {
                delayJob?.cancel()
                val rawDataResp = dataJson.getStringOrDefault("DataResp", "")
                val dataResp = gson.fromJson(rawDataResp, JsonObject::class.java)
                val cardResult = dataResp.getBooleanOrDefault("IsAllowProceedSales", false)
                val eWalletResult = dataResp.getBooleanOrDefault("IsAllowProceedEwalletSales", false)

                if(cardResult || eWalletResult) {
                    saleNavigation(cardResult, eWalletResult)
                } else {
                    showBlockSalesAlert()
                }
            }
        } catch (e: Exception) {
            println("Error parsing message: ${e.message}")
        } finally {
            hideProgress()
        }
    }

    fun saleNavigation(enableCard: Boolean, enableEWallet: Boolean) {
        val bundle = bundleOf("enableCard" to enableCard, "enableEWallet" to enableEWallet)
        navigateSafe(R.id.action_attend_to_keypad, bundle)
    }

    private suspend fun checkingSaleBlocking(): String? {
        if (ServiceHolder.autoSettlementIsRunning) {
            return "Auto Settlement is running"
        }

        val store = DataStoreManager(requireContext())
        val isSettlementBlock = store.getBoolean(PrefKeys.settlementBlock)
        val (_, _, _, _, settleBlockFlag) = ServiceHolder.getAcquirerSetting()
        if (isSettlementBlock && settleBlockFlag) {
            helperLog.appendLine(helperLogClassName, "isSettlementBlock >> [$isSettlementBlock] , settleBlockFlag >> [$settleBlockFlag]")
            return "Please Retry the Settlement"
        }
        return null
    }

    fun requestPaperRoll() {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress("", "Submitting request...")
            try {
                val apiResult = withContext(Dispatchers.IO) {
                    TmsHelper.paperRollRequest(helperLog, "PAPERROLL")
                }

                if(apiResult) {
                    PaperRollSuccessDialogFragment().show(parentFragmentManager, "PaperRollSuccessDialogFragment")
                } else {
                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle("Paper Roll Request")
                        .setMessage("Paper Roll Request unsuccessful. \nPlease try again later.")
                        .setPositiveButton("OK", null)
                        .create()
                    dialog.setCanceledOnTouchOutside(false)
                    dialog.show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                hideProgress()
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
        LogSessionMarker.closeSession("AttendFragment.closeApplication")
        AsyncLogWriter.drain()
        MfHelper.lockStatusBarAndNavigation(false)
        finishAffinity(requireActivity())
        exitProcess(0)
    }
}