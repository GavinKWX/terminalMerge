package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTxnviewListBinding
import constants.TerminalConstants
import com.sc.mf919pro.java.activity.UploadTMS
import com.sc.mf919pro.java.activity.Utils
import utils.HexUtil
import com.sc.mf919pro.kotlin.activity.TimerCountDownDialog
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleExecutionRequest
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleExecutionResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleLookupData
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleLookupResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleUseCase
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class TransactionViewListFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    //private val pageSize = 50
    private val pageSize = 15
    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    private var selectedInvNoValue = ""
    private var searchJob: Job? = null

    private lateinit var txnAdapter: TransactionCardHistoryAdapter
    private val voidSaleUseCase = VoidSaleUseCase()
    private var transResultDialog: TimerCountDownDialog? = null

    private var _binding: FragmentTxnviewListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTxnviewListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        //MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo))
        //MfHelper.closeNfcUrlInterface()
        searchJob?.cancel()
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionViewList OnDestroyView :: screen ended")
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
            "Card Transaction View List Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Card Transaction View List")
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        binding.toolbarFooterButton.setDebouncedOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.print_history_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.printDetails -> {
                        navigateSafe(R.id.action_historyList_to_list_detail)
                    }
                    R.id.printSummary -> {
                        val bundle = bundleOf("previewSettlement" to 1, "fromHistory" to 1)
                        navigateSafe(R.id.action_historyList_to_settlement_preview, bundle)
                    }
                    R.id.printLastSettlement -> {
                        val bundle = bundleOf("lastSettlement" to 1)
                        navigateSafe(R.id.action_historyList_to_settlement_preview, bundle)
                    }
                }
                true
            }
            popupMenu.show()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        setupRecyclerView()
        setupSearchInput()

        lifecycleScope.launch {
            loadSummary()
            resetPagingAndReload()
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun setupRecyclerView() {
        val mContext = requireContext()
        txnAdapter = TransactionCardHistoryAdapter(
            onClickNfc = { txn ->
                showNfcDialog(mContext, txn) },
            onClickVoid = { txn ->
                val terminalConfig = ServiceHolder.getTerminalConfig()
                if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
                    helperLog.appendLine(helperLogClassName, "VOID_WITH_PIN is true")
                    pinDialog("") { canceled ->
                        if (canceled) {
                            showToast("PIN cancelled", Toast.LENGTH_SHORT)
                        } else {
                            searchVoidSale(txn.invoiceNo)
                        }
                    }
                } else {
                    searchVoidSale(txn.invoiceNo)
                }
            },
            onClickPrint = { txn -> processPrintReceipt(requireContext(), txn) },
            onClickDetail = { txn ->
                if (txn.respCode == "00") {
                    transData.stan = txn.stan
                    navigateSafe(R.id.action_viewCardDetails)
                }
            }
        )

        val lm = LinearLayoutManager(requireContext())
        binding.trxHistoryRecyclerView.layoutManager = lm
        binding.trxHistoryRecyclerView.adapter = txnAdapter
        binding.trxHistoryRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0 || isLoading || !hasMore) return

                val totalCount = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= totalCount - 10) {
                    loadNextPage(showProgress = false)
                }
            }
        })
    }

    private fun setupSearchInput() {
        binding.inv.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedInvNoValue = s?.toString().orEmpty()
            }

            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    resetPagingAndReload()
                }
            }
        })
    }

    private suspend fun loadSummary() = withContext(Dispatchers.IO) {
        val batchNo = IsoBatchInfoRepo.getBatchInfo(requireContext(), "batchNo", "visam")?.value ?: "000001"
        val txnCount = PrintReceiptRepo.countSaleSuccess(requireContext())
        val txnAmount = PrintReceiptRepo.sumSaleSuccessAmount(requireContext())
        val voidCount = PrintReceiptRepo.countVoidOrCancel(requireContext())

        withContext(Dispatchers.Main) {
            binding.batchNo.text = batchNo
            binding.saleCount.text = txnCount.toString()
            binding.saleAmt.text = Utils.getActualAmount(txnAmount.toString())
            binding.voidCount.text = voidCount.toString()
        }
    }

    private fun resetPagingAndReload() {
        currentOffset = 0
        hasMore = true
        txnAdapter.clearItems()
        loadNextPage(showProgress = true)
    }

    private fun loadNextPage(showProgress: Boolean) {
        if (isLoading || !hasMore || _binding == null) return

        lifecycleScope.launch {
            isLoading = true
            try {
                if (showProgress) {
                    showProgress("", "Loading...")
                }

                val nextPage = withContext(Dispatchers.IO) {
                    PrintReceiptRepo.getMultipleRecordPaged(
                        requireContext(),
                        invoiceNoFilter = selectedInvNoValue,
                        limit = pageSize,
                        offset = currentOffset,
                    )
                }

                if (currentOffset == 0) {
                    txnAdapter.replaceItems(nextPage)
                } else {
                    txnAdapter.appendItems(nextPage)
                }

                currentOffset += nextPage.size
                hasMore = nextPage.size >= pageSize
            } finally {
                if (showProgress) {
                    hideProgress()
                }
                isLoading = false
            }
        }
    }

    private fun showNfcDialog(mContext: Context, txn: DbModelPrintReceipt) {
        var rrn = ""
        var approvalCode = ""
        try{
            val msg = txn.receiptInfo.replace("[", "").replace("]", "").replace(", ", "\n")
            if(msg.isEmpty()) return
            val details = Utils.String2ArrayString(msg)
            rrn = details[10]
            approvalCode = details[11]

            val receiptUploadModel = ReceiptUploadRepo.getSingleDesc(mContext, mutableListOf("RRN", "APPR_CODE"), arrayOf(rrn, approvalCode))
            val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
            val aesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                receiptUploadModel?.MID ?: "",
                receiptUploadModel?.TID ?: "",
                receiptUploadModel?.BATCH_NO ?: "",
                receiptUploadModel?.INV_NO ?: "",
                receiptUploadModel?.RRN_ORI ?: "",
                receiptUploadModel?.APPR_CODE_ORI ?: "",
                "",
                true,
                "NFC",
                ServiceHolder.getTerminalSerialNumber()
            )
            MfHelper.openNfcUrlInterface(4, "${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")

            val qrAesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                receiptUploadModel?.MID ?: "",
                receiptUploadModel?.TID ?: "",
                receiptUploadModel?.BATCH_NO ?: "",
                receiptUploadModel?.INV_NO ?: "",
                receiptUploadModel?.RRN_ORI ?: "",
                receiptUploadModel?.APPR_CODE_ORI ?: "",
                "",
                true,
                "QR",
                ServiceHolder.getTerminalSerialNumber()
            )
            MfHelper.showAuxLcdQrCode("${environmentManager.get(EnvironmentVariables::nfcUrl)}$qrAesData", null)
        }catch (_: Exception) { }

        transResultDialog = TimerCountDownDialog().apply {
            setTimeout(30 * 1000)
            setInfoText("NFC Receipt Ready. Please Tap Your Phone")
            setBtnText("Close")
            setOnTimerFinishes(object : TimerCountDownDialog.IOnTimerFinishes {
                override fun onTimerFinishes() {
                    cancelTimer()
                    MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo))
                    MfHelper.closeNfcUrlInterface()
                    transResultDialog!!.dismiss()
                }
            })
            setOnButtonClickListener(object : TimerCountDownDialog.IOnButtonClickListener {
                override fun onCancelCLick() {
                    cancelTimer()
                    MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo))
                    MfHelper.closeNfcUrlInterface()
                    transResultDialog!!.dismiss()
                }
            })
        }
        transResultDialog!!.show(requireActivity().supportFragmentManager, "")
    }

    private fun searchVoidSale(txninvoiceNo: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            transData.reset()
            showProgress("Finding the Transaction", "Searching...")
            try {
                when (val result = voidSaleUseCase.lookupInvoice(requireContext(), txninvoiceNo)) {
                    is VoidSaleLookupResult.Found -> {
                        helperLog.appendLine(helperLogClassName, "Batch Table Record :: ${Gson().toJson(result.data.batchTableModel)}")
                        showVoidConfirmationDialog(
                            result.data.amountDisplay,
                            result.data.maskedCardPan,
                            result.data.approvalCode,
                            result.data.invoiceNo,
                            result.data
                        )
                    }
                    is VoidSaleLookupResult.NotFound -> {
                        helperLog.appendLine(helperLogClassName, result.message)
                    }
                    is VoidSaleLookupResult.Unsupported -> {
                        helperLog.appendLine(helperLogClassName, result.message)
                    }
                    is VoidSaleLookupResult.Error -> {
                        result.cause?.printStackTrace()
                        helperLog.appendLine(helperLogClassName, "Lookup error :: ${result.message}")
                    }
                }
            } finally {
                hideProgress()
            }
        }
    }

    private fun showVoidConfirmationDialog(amt: String?, cardNo: String?, authCode: String?, invNo: String?, voidData: VoidSaleLookupData) {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.fragment_void_confirmation, null)

        (dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = amt
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = cardNo
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = authCode
        (dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invNo

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Cancel Void Transaction")
            alertDialog?.dismiss()
        }

        val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
        voidConfirmBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Start Void Transaction")
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                executeVoidSale(voidData)
            }
        }
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun executeVoidSale(voidData: VoidSaleLookupData) {
        helperLog.appendLine(helperLogClassName, "Void Sales Transaction Start")
        showProgress("Bank Authorization", "Waiting for Approval")

        try {
            ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("AcqMid", "AcqTid", "Product"), listOf(voidData.batchTableModel.mid, voidData.batchTableModel.tid, ProductCatSelectionDataEnum.CARD_SETTINGS.name))?.let {
                val jsonProductList = Gson().toJson(it)
                val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
                ServiceHolder.saleModelCache = saleModelNew
            }
            val result = voidSaleUseCase.executeVoid(
                requireContext(),
                VoidSaleExecutionRequest(
                    lookupData = voidData,
                    posReference = null,
                    helperLog = helperLog,
                    saleModel = ServiceHolder.saleModelCache,
                    merchantConfig = ServiceHolder.getMerchantInfo(),
                    onProgressMessage = { status ->
                        if (!status.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                updateProgress(null, status)
                            }
                        }
                    },
                    onProgressTitle = { title ->
                        if (!title.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                updateProgress(title = title
)
                            }
                        }
                    }
                )
            )

            when (result) {
                is VoidSaleExecutionResult.Success -> {
                    helperLog.appendLine(helperLogClassName, "Void execution result :: ${result.txnTypeLabel} / ${result.respCodeAscii}")
                }

                is VoidSaleExecutionResult.Error -> {
                    result.cause?.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Void execution error :: ${result.message}")
                }
            }
        } catch (_: Exception) {
        } finally {
            withContext(Dispatchers.IO) {
                helperLog.appendLine(helperLogClassName, "Void Sales Transaction End")
                helperLog.appendLine(helperLogClassName, "Send Void Receipt to TMS")
                sendTmsVoidReceipt(voidData)
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            delay(500L)
            hideProgress()
        }
        navigateSafe(R.id.action_historyList_to_result)
    }

    private fun sendTmsVoidReceipt(voidData: VoidSaleLookupData) {
        val timeStamp = Utils.DateTimeFormat(transData.transDateAsci)

        val strTxnType = when (voidData.batchTableModel.txnType) {
            "Instalment Sale" -> "VoidEPP"
            "Moto" -> "VoidMoto"
            else -> "Void"
        }
        val strPaymentProductId = ""
        val strSchemeId = transData.schemeId
        val strStan = transData.stan
        val strInvNo = transData.invoiceNo
        val strTxnAmt = HexUtil.bytesToHexString(transData.amountAuth)
        val strRrn = transData.rrn
        val strApprCode = transData.approvalCode
        val strRrnOri = transData.prevRRN
        val strApprCodeOri = transData.prevApprovalCode
        val strTid = transData.tid
        val strMid = transData.mid
        val strBatchNo = transData.batchNo
        val strRespCode = Utility.HexString2ASCII(transData.respCode)
        val strAid = transData.aid
        val mti = transData.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16)
        val strNii = transData.getFromTransactionDb("DF24", 16)
        val strMaskPanBcd = transData.maskedPan
        val strHashedPanBcd = transData.hashedPan
        val strEntryType = transData.entryModeLabel
        val strARQC = transData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
        val strTVR = transData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
        val strPosReference = transData.posReference
        val strCardLabel = Utils.byteArrayToAsciiString(transData.appLabel, 0, transData.appLabelLen)
        val strCvm = transData.cvm

        val jsonObject = JSONObject()
        try {
            jsonObject.put("SEQ_NO", ServiceHolder.getSqnNum())
            jsonObject.put("TXN_DT", timeStamp)
            jsonObject.put("TXN_TYPE", strTxnType)
            jsonObject.put("MID", strMid)
            jsonObject.put("TID", strTid)
            jsonObject.put("MTI", mti)
            jsonObject.put("NII", strNii)
            jsonObject.put("SCHEME_ID", strSchemeId)
            jsonObject.put("AID", strAid)
            jsonObject.put("CARD_MASKED", strMaskPanBcd)
            jsonObject.put("CARD_HASHED", strHashedPanBcd)
            jsonObject.put("RRN", strRrn)
            jsonObject.put("APPR_CODE", strApprCode)
            jsonObject.put("RRN_ORI", strRrnOri)
            jsonObject.put("APPR_CODE_ORI", strApprCodeOri)
            jsonObject.put("TXN_AMT", strTxnAmt)
            jsonObject.put("INV_NO", strInvNo)
            jsonObject.put("STAN", strStan)
            jsonObject.put("BATCH_NO", strBatchNo)
            jsonObject.put("RESP_CODE", strRespCode)
            jsonObject.put("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.put("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.put("ENTRY_TYPE", strEntryType)
            jsonObject.put("ARQC", strARQC)
            jsonObject.put("TVR", strTVR)
            jsonObject.put("PAYMENT_PRODUCT_ID", strPaymentProductId)
            jsonObject.put("POS_REF_NO", strPosReference)
            jsonObject.put("CARD_LABEL", strCardLabel)
            jsonObject.put("CVM", strCvm)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        UploadTMS.getInstance().addReceipt(jsonObject.toString())
    }

    private fun processPrintReceipt(mContext: Context, txn: DbModelPrintReceipt) {
        try{
            val msg = txn.receiptInfo.replace("[", "").replace("]", "").replace(", ", "\n")
            if(msg.isEmpty()) return
            val details = Utils.String2ArrayString(msg)
            val bundleValue = Bundle().apply {
                putBoolean("isTpa", txn.isTpaAccount == "true")
            }
            printReceipt(null, details, "DUPLICATE", bundleValue)
        }catch (_: Exception) { }
    }

    private fun customOnBackPress() {
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}