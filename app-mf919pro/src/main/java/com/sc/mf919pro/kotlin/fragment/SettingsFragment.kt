package com.sc.mf919pro.kotlin.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentSettingsBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.onAlertDialogListener
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getAcquirerSetting
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.getInjectionKey
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.getMerchantConfiguration
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.getTerminalConfiguration
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.uploadAllTerminalLog
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Settings OnDestroyView :: screen ended")
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
            "Settings Fragment Initialization"
        )
        binding.toolbarSettings.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        dynamicScreenOption()
        val dbModelMerchantConfig = getMerchantInfo()
        val qrGenUPIDetail = getSpecificQrProductItem(
            ArrayList(listOf("Product", "QrProductCode")),
            arrayOf("GENERATE_QR", "QR_UNIONPAY")
        )

        val txnHistory = binding.txnHistoryBtn
        if (qrGenUPIDetail != null || dbModelMerchantConfig?.Action2 == "UPIQR") {
            txnHistory.visibility = View.VISIBLE
        } else {
            txnHistory.visibility = View.GONE
        }

        // Button Listener
        binding.adminBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Admin Button OnClick")
            adminBtn()
        }
        binding.settingLastReversal.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "LastReversal Button OnClick")
            lastReversalFunc(view)
        }
        binding.lastSettlementBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "LastSettlement Button OnClick")
            lastSettlementBtn()
        }
        binding.lastSettlementQrBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "LastSettlementQr Button OnClick")
            lastSettlementQrBtn()
        }
        binding.downloadConfigBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "DownloadConfig Button OnClick")
            downloadConfigBtn()
        }
        binding.injectKeysBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "InjectKey Button OnClick")
            injectKeysBtn()
        }
        binding.signOnBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "SignOn Button OnClick")
            signOnBtn()
        }
        binding.uploadLogBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "UploadLog Button OnClick")
            uploadLogBtn()
        }
        binding.resetComPortBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "ResetComPort Button OnClick")
            resetComPortBtn()
        }
        binding.appInfoBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "AppInfo Button OnClick")
            navigateSafe(R.id.action_settings_to_about)
        }
        binding.preAuthBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "PreAuth Button OnClick")
            if (ServiceHolder.autoSettlementIsRunning) {
                showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            } else {
                val dbModelMerchantConfig = getMerchantInfo()
                val terminalConfig = getTerminalConfig()
                if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "PreAuth")) {
                    if (DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid") == "") {
                        showToast("Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT)
                    } else {
                        SelectionDialog_preauth()
                    }
                } else {
                    showToast("PreAuth is disabled", Toast.LENGTH_SHORT)
                }
            }
        }
        binding.motoBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "MOTO Button OnClick")
            motoBtn()
        }
        binding.cashoutBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "CashOut Button OnClick")
            cashOutBtn()
        }
        binding.eppBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "EPP Button OnClick")
            eppBtnClick()
        }
    }

    private fun motoBtn() {
        if (ServiceHolder.autoSettlementIsRunning) {
            showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            return
        }

        try {
            val terminalConfig = ServiceHolder.getTerminalConfig()
            if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                showToast("Payment Disabled.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                return
            }

            navigateSafe(R.id.action_settings_to_keypadMoto)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun cashOutBtn() {
        if (ServiceHolder.autoSettlementIsRunning) {
            showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            return
        }

        try {
            val terminalConfig = ServiceHolder.getTerminalConfig()
            if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                showToast("Payment Disabled.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                return
            }

            ProductListRepo.getSinglev2(requireContext(),
                listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
            val bundle = bundleOf("isCashOut" to true)
            navigateSafe(R.id.action_setting_to_keypadPreAuth, bundle)
        } catch (ex: Exception) {
            ex.printStackTrace()
            showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
        }
    }

    private fun eppBtnClick() {
        if (ServiceHolder.autoSettlementIsRunning) {
            showToast("Auto Settlement is running", Toast.LENGTH_SHORT)
            return
        }

        try {
            val terminalConfig = ServiceHolder.getTerminalConfig()
            if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                showToast("Payment Disabled.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                return
            }

            ProductListRepo.getSinglev2(requireContext(),
                listOf("Product"), listOf(ProductCatSelectionDataEnum.EPP.name)) ?: throw Exception()
            navigateSafe(R.id.action_settings_to_eppAcquirer)
        } catch (ex: Exception) {
            ex.printStackTrace()
            showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
        }
    }

    private fun downloadConfigBtn() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val log = HelperLog(
                getSession(),
                checkIsConnectedWifi(ctx),
                Utils.getIPAddress(),
                "Setting Download Configuration",
                helperLogClassName,
                helperLogClassName
            )
            showProgress("Download Configuration", "Downloading... Please wait")

            //Terminal Configuration
            val terminalConfigResp = withContext(Dispatchers.IO) {
                getTerminalConfiguration(log, ctx)
            }

            if (terminalConfigResp) {
                showToast(ctx,"Successfully Downloaded Terminal Configuration", Toast.LENGTH_SHORT)
            } else {
                showToast(ctx, "Fail To Download Terminal Configuration", Toast.LENGTH_SHORT)
            }
            delay(500)
            updateProgress("Download Configuration", "Downloading... Please wait")
            //Terminal Configuration

            //Merchant Configuration
            val merchantConfigResp = withContext(Dispatchers.IO) {
                getMerchantConfiguration(log, ctx)
            }

            if (merchantConfigResp) {
                showToast(ctx, "Successfully Downloaded Merchant Configuration", Toast.LENGTH_SHORT)

                withContext(Dispatchers.IO) {
                    val dbModelMerchantConfig = getMerchantInfo()
                    //VISAM
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "visam")

                    //MCCS
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "mccs")
                }
            } else {
                showToast(ctx, "Fail To Download Merchant Configuration", Toast.LENGTH_SHORT)
            }
            //Merchant Configuration

            delay(500)
            dynamicScreenOption()
            hideProgress()
            withContext(Dispatchers.IO) {
                log.logToFile(EnumLogFileName.TerminaLog)
            }
        }
    }

    private fun signOnBtn() {
        val (acqName) = getAcquirerSetting()

        if (acqName.equals("BSN", ignoreCase = true)) {
            showToast("Sign on is not required", Toast.LENGTH_SHORT)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            showProgress("Download Keys", "Downloading... Please wait")
            HelperCommon.context = ctx

            val resp = withContext(Dispatchers.IO) {
                try {
                    transData.reset()
                    val acquirerInfo = getAcquirerSetting()
                    transData.acqCode = acquirerInfo.acqName
                    val helperLog = HelperLog(
                        getSession(),
                        checkIsConnectedWifi(ctx),
                        Utils.getIPAddress(),
                        "SignOn",
                        helperLogClassName,
                        helperLogClassName
                    )
                    val iSignOnResp = IsoActivity.processSignOn(ctx, helperLog)
                    if (iSignOnResp) 0 else -1
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    -1
                }
            }

            // back on UI thread
            if (resp == 0) {
                showToast("Successfully Downloaded Keys", Toast.LENGTH_SHORT)
            } else {
                showToast("Fail To Download Keys", Toast.LENGTH_SHORT)
            }

            delay(500)
            hideProgress()
        }
    }

    private fun injectKeysBtn() {
        val (acqName) = getAcquirerSetting()
        if (acqName.equals("BSN", ignoreCase = true)) {
            showToast("Inject Keys is Not Required...", Toast.LENGTH_SHORT)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            showProgress("Inject Keys", "Downloading... Please wait")

            val resp = withContext(Dispatchers.IO) {
                val log = HelperLog(
                    getSession(),
                    checkIsConnectedWifi(ctx),
                    Utils.getIPAddress(),
                    "Setting Injection Key",
                    helperLogClassName,
                    helperLogClassName
                )
                getInjectionKey(log,ctx)
            }

            if (resp) {
                showToast("Successfully Inject Keys", Toast.LENGTH_SHORT)
            } else {
                showToast("Fail To Download Inject Keys", Toast.LENGTH_SHORT)
            }
            delay(500)
            hideProgress()
        }
    }

    private fun SelectionDialog_preauth() {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        val dialogView = inflater.inflate(
            R.layout.activity_preauthselection, null
        )
        //dialogView.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        dialogView.findViewById<LinearLayout>(R.id.cancel_btn_preauth).apply {
            setDebouncedOnClickListener { alertDialog?.dismiss() }
        }

        dialogView.findViewById<LinearLayout>(R.id.preauth).apply {
            setDebouncedOnClickListener {
                alertDialog?.dismiss()
                val terminalConfig = ServiceHolder.getTerminalConfig()
                if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                    showToast("Payment Disabled.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                    return@setDebouncedOnClickListener
                }

                try {
                    ProductListRepo.getSinglev2(requireContext(),
                        listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    navigateSafe(R.id.action_setting_to_keypadPreAuth)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                }
            }
        }

        dialogView.findViewById<LinearLayout>(R.id.SaleComp).apply {
            setDebouncedOnClickListener {
                alertDialog?.dismiss()
                val terminalConfig = ServiceHolder.getTerminalConfig()
                if(!DbModelTerminalConfig.getBooleanValue(terminalConfig, "SALES_CARD")) {
                    showToast("Payment Disabled.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                    return@setDebouncedOnClickListener
                }

                try {
                    ProductListRepo.getSinglev2(requireContext(),
                        listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val bundle = bundleOf("isSalesComp" to true)
                    navigateSafe(R.id.action_setting_to_keypadPreAuth, bundle)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                }
            }
        }

        dialogView.findViewById<LinearLayout>(R.id.PCancel).apply {
            setDebouncedOnClickListener {
                alertDialog?.dismiss()
                try {
                    val dbProductModel = ProductListRepo.getSinglev2(requireContext(),
                        listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 0
                    ServiceHolder.saleModelCache = saleModelNew
                    navigateSafe(R.id.action_setting_to_voidPreAuth)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                }
            }
        }

        dialogView.findViewById<LinearLayout>(R.id.VoidSaleComp).apply {
            setDebouncedOnClickListener {
                alertDialog?.dismiss()
                try {
                    val dbProductModel = ProductListRepo.getSinglev2(requireContext(),
                        listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 0
                    ServiceHolder.saleModelCache = saleModelNew
                    navigateSafe(R.id.action_setting_to_voidSaleComp)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    showToast("No Payment Option Available.\nPlease Contact Sales Support for assistance", Toast.LENGTH_SHORT)
                }
            }
        }

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private fun uploadLogBtn() {
        object : Thread() {
            override fun run() {
                showProgress("Upload File", "Uploading....")
                val log = HelperLog(
                    getSession(),
                    checkIsConnectedWifi(requireContext()),
                    Utils.getIPAddress(),
                    "Uploading Log",
                    helperLogClassName,
                    helperLogClassName
                )
                val result = uploadAllTerminalLog(log, requireContext(), true)
                if (result) {
                    log.appendLine(requireContext().javaClass.name, "Upload Completed")
                    showToast("Upload Completed", Toast.LENGTH_SHORT)
                } else {
                    if (checkIsConnectedWifi(requireContext())) {
                        log.appendLine(requireContext().javaClass.name, "Upload Fail")
                        showToast("Upload Failed", Toast.LENGTH_SHORT)
                    } else {
                        log.appendLine(requireContext().javaClass.name, "Upload Cancel Wi-Fi is required")
                        showToast("Wifi Connection is required", Toast.LENGTH_SHORT)
                    }
                }

                try {
                    sleep(1000)
                    hideProgress()
                    log.logToFile(EnumLogFileName.TerminaLog)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }.start()
    }

    private fun resetComPortBtn() {
        object : Thread() {
            override fun run() {
                super.run()
                showProgress("Reset", "Resetting Communication Port...")
                HTTPServer.resetCommunicationPort()
                Utils.DelayMili(2000)
                showToast("Refreshed Communication Port", Toast.LENGTH_SHORT)
                hideProgress()
            }
        }.start()
    }

    private fun adminBtn(){
        passwordAlertDialog(1, "666666", object : onAlertDialogListener {
            override fun onResult(id: Int, clickStatus: Boolean, isOK: Boolean) {
                if (clickStatus) {
                    if (isOK) {
                        navigateSafe(R.id.action_settings_to_admin)
                    } else {
                        showToast("Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
    }

    private fun lastSettlementBtn() {
        val bundle = bundleOf("lastSettlement" to 1)
        navigateSafe(R.id.action_settings_to_settlement, bundle)
    }

    private fun lastSettlementQrBtn() {
        val bundle = bundleOf("lastSettlement" to 1)
        navigateSafe(R.id.action_settings_to_settlementQrPreview, bundle)
    }

    private fun getSpecificQrProductItem(
        fieldList: ArrayList<String>,
        valueList: Array<String>
    ): DbModelProductList? {
        val specificProdList = ProductListRepo.getSelectedProductEnhanced(requireContext(), fieldList, valueList)
        var productItem: DbModelProductList? = null
        if (specificProdList.isNotEmpty()) {
            productItem = specificProdList[0]
        }
        return productItem
    }

    private fun dynamicScreenOption() {
        val terminalConfig = getTerminalConfig()
        val merchantConfig = getMerchantInfo()

        requireActivity().runOnUiThread {
            //MOTO Visibility
            binding.motoBtn.let { motoBtn ->
                if(getBooleanValue(terminalConfig, "MOTO")){
                    motoBtn.visibility = View.VISIBLE
                } else {
                    motoBtn.visibility = View.GONE
                }
            }

            //PreAuth Visibility
            binding.preAuthBtn.let { preAuthBtn ->
                if(getBooleanValue(terminalConfig, "PreAuth")){
                    preAuthBtn.visibility = View.VISIBLE
                } else {
                    preAuthBtn.visibility = View.GONE
                }
            }

            //CashOut Visibility
            binding.cashoutBtn.let { cashoutBtn ->
                if(getBooleanValue(terminalConfig, "CASHOUT")){
                    cashoutBtn.visibility = View.VISIBLE
                } else {
                    cashoutBtn.visibility = View.GONE
                }
            }

            val qrGenUPIDetail = getSpecificQrProductItem(java.util.ArrayList(listOf("Product", "QrProductCode"))
                , arrayOf("GENERATE_QR", "QR_UNIONPAY"))
            binding.txnHistoryBtn.let { txnHistoryBtn ->
                if (qrGenUPIDetail != null || merchantConfig?.Action2 == "UPIQR") {
                    txnHistoryBtn.visibility = View.VISIBLE
                } else {
                    txnHistoryBtn.visibility = View.GONE
                }
            }

            val isEppEnabled = ProductListRepo.getSinglev2(requireContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.EPP.name))
            binding.eppBtn.let { eppButton ->
                if(isEppEnabled != null) {
                    eppButton.visibility = View.VISIBLE
                } else {
                    eppButton.visibility = View.GONE
                }
            }
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }

    fun lastReversalFunc(view: View) {
        object : Thread() {
            override fun run() {
                super.run()
                val helperLog = HelperLog(
                    getSession(),
                    checkIsConnectedWifi(view.context),
                    Utils.getIPAddress(),
                    "Settings Activity Last Reversal",
                    helperLogClassName,
                    helperLogClassName
                )
                var reversalResult = true
                showProgress("", "Running Last Reversal")

                val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
                val cardProduct = ProductListRepo.getSinglev2(view.context, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                val batchNo = IsoBatchInfoRepo.getBatchInfo(view.context, "batchNo", "visam")?.value ?: ""
                val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(cardProduct.AcqCode, "reversal")

                val reversalBatch = ReversalBatchTableRepo.getBatchData(view.context, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, cardProduct.AcqMid, cardProduct.AcqTid))
                helperLog.appendLine(helperLogClassName, "Batch List :: (${reversalBatch.size})")
                if(reversalBatch.isEmpty() || acquirerRevIsoModel == null) {
                    sleep(500)
                    helperLog.appendLine(helperLogClassName, "Empty Reversal Batch...")
                    requireActivity().runOnUiThread {
                        Toast.makeText(view.context, "No Reversal Found...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    sleep(500)
                    for (tempFor in reversalBatch) {
                        helperLog.appendLine(helperLogClassName, "Reversal Batch Model::(${Gson().toJson(tempFor)})")
                        TransData.reset()

                        TransData.acqCode = cardProduct.AcqCode
                        TransData.product = cardProduct.Product
                        TransData.mid = cardProduct.AcqMid
                        TransData.tid = cardProduct.AcqTid
                        TransData.schemeTag = "visam"
                        TransData.txnTypeLabel = "Reversal"
                        TransData.ksn = cardProduct.Ksn
                        TransData.pinKsn = cardProduct.PinKsn
                        TransData.stan = tempFor.stan
                        TransData.invoiceNo = tempFor.invNo
                        TransData.batchNo = tempFor.batchNo
                        TransData.isTpaAccount = cardProduct.IsTpaAccount.lowercase() == "true"
                        TransData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
                        TransData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
                        val revResult = IsoActivity.processReversal(view.context, false, acquirerRevIsoModel, tempFor.batchData, false, helperLog)
                        if(reversalResult && revResult == null){
                            reversalResult = false
                        }
                    }

                    var toastMessage = "One of the Reversal UnSuccessful..."
                    if(reversalResult) {
                        toastMessage = "Reversal Successful..."
                    }
                    requireActivity().runOnUiThread {
                        Toast.makeText(view.context, toastMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                hideProgress()
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
        }.start()
    }
}