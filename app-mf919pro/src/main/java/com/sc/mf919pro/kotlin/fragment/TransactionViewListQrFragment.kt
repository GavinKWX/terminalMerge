package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTxnviewListBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TimerCountDownDialog
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrExecutionRequest
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrExecutionResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrLookupData
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrUseCase
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
import tms.handlers.QrEnquiryHandler
import tms.models.QrEnquiryResponseModel

class TransactionViewListQrFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private val pageSize = 50
    private var currentOffset = 0
    private var isLoading = false
    private var hasMore = true

    private var selectedRefIdValue = ""
    private var selectedTxnTypeValue = ""
    private var searchJob: Job? = null

    private lateinit var txnAdapter: TransactionQrHistoryAdapter
    private var transResultDialog: TimerCountDownDialog? = null
    private val voidQrUseCase = VoidQrUseCase()

    private var _binding: FragmentTxnviewListBinding? = null
    private val binding get() = _binding!!
    private var terminalPw = ""

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
            helperLog.appendLine(helperLogClassName, "TransactionViewListQr OnDestroyView :: screen ended")
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
            "QR Transaction View List Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize QR Transaction View List")

        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        binding.textHeader.setText(R.string.ew_history)
        binding.toolbarFooterButton.setDebouncedOnClickListener {
            binding.toolbarFooterButton.setDebouncedOnClickListener {
                val popupMenu = PopupMenu(requireContext(), it)
                popupMenu.menuInflater.inflate(R.menu.print_history_menu, popupMenu.menu)
                popupMenu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.printDetails -> {
                            navigateSafe(R.id.action_historyList_to_list_qr_detail)
                        }
                        R.id.printSummary -> {
                            val bundle = bundleOf("previewSettlement" to 1, "fromHistory" to 1)
                            navigateSafe(R.id.action_historyQrList_to_settlement_preview, bundle)
                        }
                        R.id.printLastSettlement -> {
                            val bundle = bundleOf("lastSettlement" to 1)
                            navigateSafe(R.id.action_historyQrList_to_settlement_preview, bundle)
                        }
                    }
                    true
                }
                popupMenu.show()
            }
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
    }

    private fun setupRecyclerView() {
        txnAdapter = TransactionQrHistoryAdapter(
            onReEnquiry = { txn ->
                viewLifecycleOwner.lifecycleScope.launch {
                    reQueryTransaction(txn)
                }
            },
            onClickNfc = { txn ->
                viewLifecycleOwner.lifecycleScope.launch {
                    showNfcDialog(txn)
                }
            },
            onClickVoid = { txn ->
                val terminalConfig = ServiceHolder.getTerminalConfig()
                if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
                    helperLog.appendLine(helperLogClassName, "VOID_WITH_PIN is true")
                    pinDialog("") { canceled ->
                        if (canceled) {
                            showToast("PIN cancelled", Toast.LENGTH_SHORT)
                        } else {
                            terminalPw = terminalPIN?: ""
                            searchVoidQr(txn)
                        }
                    }
                } else {
                    searchVoidQr(txn)
                }
            },
            onClickPrint = { txn ->
                val receiptDetails =  Array(12) { _ -> ""  }
                receiptDetails[0] = if(txn.txnType?.trim() == "Void") txn.voidDateTime ?: "" else txn.txnDateTime ?: ""
                receiptDetails[1] = txn.productName ?: ""
                receiptDetails[2] = txn.txnType ?: ""
                receiptDetails[3] = txn.mid ?: ""
                receiptDetails[4] = txn.tid ?: ""
                receiptDetails[5] = txn.approvalCode ?: ""
                receiptDetails[6] = txn.hostRefNo ?: ""
                if(txn.isUnionPayTxn == "1") {
                    receiptDetails[7] =  txn.refId ?: ""
                    receiptDetails[8] =  txn.upiVoucherCode ?: ""
                    receiptDetails[9] =  "RM ${Utils.getActualAmount(txn.txnAmount ?: "0")}"
                    receiptDetails[10] =  "RM ${Utils.getActualAmount(txn.upiDiscountAmt)}"

                    var finalAmt = "0"
                    if (!txn.upiDiscountAmt.isEmpty()) {
                        finalAmt = ((txn.txnAmount?.toLong() ?: 0) - txn.upiDiscountAmt.toLong()).toString()
                    }
                    receiptDetails[11] =  "RM ${Utils.getActualAmount(finalAmt)}"
                } else {
                    receiptDetails[7] =  "RM ${Utils.getActualAmount(txn.txnAmount ?: "0")}"
                    receiptDetails[8] =  txn.refId ?: ""
                }

                val specialArg = Bundle().apply {
                    putString("acqCode", txn.acqCode ?: "")
                    putBoolean("isTpa", txn.isTpaAccount == "true")
                    putBoolean("isUnionPayTxn", txn.isUnionPayTxn == "1")
                }
                printReceiptQr(null, receiptDetails, "DUPLICATE", specialArg)
            },
            onClickDetail = { txn ->
                val bundle = bundleOf("refId" to txn.refId.orEmpty())
                navigateSafe(R.id.action_viewQrDetails, bundle)
            },
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
                selectedRefIdValue = s?.toString().orEmpty()
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
        val txnCount = TransactionQrRepo.countSaleSuccess(requireContext())
        val txnAmount = TransactionQrRepo.sumSaleSuccessAmount(requireContext())
        val voidCount = TransactionQrRepo.countVoid(requireContext())

        withContext(Dispatchers.Main) {
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
                    TransactionQrRepo.getListTransactionQrDescPaged(
                        requireContext(),
                        refIdFilter = selectedRefIdValue,
                        txnTypeFilter = selectedTxnTypeValue,
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

    private suspend fun showNfcDialog(dbModelTransactionQrGet: DbModelTransactionQrGet) = withContext(Dispatchers.Default) {
        try {
            val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
            val aesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                dbModelTransactionQrGet.mid ?: "",
                dbModelTransactionQrGet.tid ?: "",
                "",
                "",
                "",
                dbModelTransactionQrGet.approvalCode ?: "",
                dbModelTransactionQrGet.refId ?: "",
                true,
                "NFC",
                ServiceHolder.getTerminalSerialNumber()
            )
            MfHelper.openNfcUrlInterface(4, "${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")

            val qrAesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                dbModelTransactionQrGet.mid ?: "",
                dbModelTransactionQrGet.tid ?: "",
                "",
                "",
                "",
                dbModelTransactionQrGet.approvalCode ?: "",
                dbModelTransactionQrGet.refId ?: "",
                true,
                "QR",
                ServiceHolder.getTerminalSerialNumber()
            )
            MfHelper.showAuxLcdQrCode("${environmentManager.get(EnvironmentVariables::nfcUrl)}$qrAesData", null)
        } catch (_: Exception) { }

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

    private fun searchVoidQr(dbModelTransactionQrGet: DbModelTransactionQrGet) {
        viewLifecycleOwner.lifecycleScope.launch {
            transData.reset()
            val amount = dbModelTransactionQrGet.txnAmount ?: "0"
            val voidQrLookupData = VoidQrLookupData(
                transaction = dbModelTransactionQrGet,
                amountDisplay = "RM" + Utils.getActualAmount(amount),
                productName = dbModelTransactionQrGet.productName ?: "-",
                hostRefNo = dbModelTransactionQrGet.hostRefNo ?: "-",
                refId = dbModelTransactionQrGet.refId ?: "-",
            )
            showVoidConfirmationDialog(voidQrLookupData)
        }
    }

    private fun showVoidConfirmationDialog(voidData: VoidQrLookupData) {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater

        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.fragment_voidqr_confirmation, null)
        (dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = voidData.amountDisplay
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = voidData.productName
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = voidData.hostRefNo
        (dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = voidData.refId

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setDebouncedOnClickListener {
            alertDialog?.dismiss()
        }

        val remarkRow = dialogView.findViewById<LinearLayout>(R.id.remark_row)
        remarkRow.visibility = View.GONE

        val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
        voidConfirmBtn.setDebouncedOnClickListener {
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                executeVoidQr(voidData)
            }
        }

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }
    private suspend fun executeVoidQr(voidData: VoidQrLookupData) {
        ServiceHolder.appRunningProcess = true
        showProgress("QR Payment Authorization", "Waiting for Approval")
        try {
            val result = voidQrUseCase.executeVoid(
                requireContext(),
                VoidQrExecutionRequest(
                    lookupData = voidData,
                    posReference = "",
                    terminalPw = terminalPw,
                    helperLog = helperLog,
                    transData = transData,
                )
            )

            when (result) {
                is VoidQrExecutionResult.Success -> {
                    helperLog.appendLine(helperLogClassName, "Void QR result :: ${result.respCode} / ${result.respDesc}")
                }
                is VoidQrExecutionResult.Error -> {
                    result.cause?.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Void QR error :: ${result.respCode} / ${result.respDesc}")
                }
            }
        } finally {
            hideProgress()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_qrHistoryList_to_result)
        }
    }

    private suspend fun reQueryTransaction(dbModelTransactionQrGet: DbModelTransactionQrGet) = withContext(Dispatchers.Default) {
        val helperLog = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            "TransactionViewQrActivity",
            helperLogClassName,
            helperLogClassName,
        )
        showProgress("Re-Enquiry", "Loading...")
        helperLog.appendLine(helperLogClassName, "Start Re-Enquiry Transaction")
        helperLog.appendLine(helperLogClassName, "Transaction :: $dbModelTransactionQrGet")

        var refreshFragment = false
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
        val merchantConfig = ServiceHolder.getMerchantInfo()
        val criteriaHM = hashMapOf<Any, Any>(
            "id" to (dbModelTransactionQrGet.id ?: "0").toString(),
            "refId" to (dbModelTransactionQrGet.refId ?: "")
        )
        val receiptUploadCriteria = hashMapOf<Any, Any>(
            "QrRefId" to (dbModelTransactionQrGet.refId ?: "")
        )


        try {
            val qrEnquiryHandler = QrEnquiryHandler(environmentManager)
            val qrEnquiryResp = qrEnquiryHandler.invoke(
                helperLog,
                merchantConfig?.QrMid ?: "",
                merchantConfig?.QrTid ?: "",
                dbModelTransactionQrGet.refId ?: "",
                apiTimeoutSec = 30
            )
            helperLog.appendLine(helperLogClassName, "QrEnquiryHandler Response >> $qrEnquiryResp")

            val valueHM = hashMapOf<Any, Any>(
                "respCode" to (qrEnquiryResp.RESP_CODE ?: ""),
                "respDesc" to (qrEnquiryResp.RESP_DESC ?: ""),
                "hostRefNo" to (qrEnquiryResp.QR_HOST_REFNO ?: ""),
                "txnRefNo" to (qrEnquiryResp.QR_TXN_REFNO ?: ""),
                "approvalCode" to (qrEnquiryResp.BANK_AUTH_CODE ?: ""),
                "productCode" to (qrEnquiryResp.QR_PAY_BRAND ?: ""),
                "productName" to (qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""),
                "isTpaAccount" to (qrEnquiryResp.IS_TPA_ACCOUNT ?: "false").toString(),
            )
            TransactionQrRepo.updateTransactionQr(requireContext(), valueHM, criteriaHM)

            val receiptUploadValue = hashMapOf<Any, Any>(
                "RRN" to (qrEnquiryResp.QR_HOST_REFNO ?: ""),
                "RESP_CODE" to (qrEnquiryResp.RESP_CODE ?: ""),
            )
            ReceiptUploadRepo.updateData(requireContext(), receiptUploadValue, receiptUploadCriteria)
            refreshFragment = true
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "QrEnquiryHandler(Exception) :: $ex")

            try {
                val errorResponse = Gson().fromJson(ex.message, QrEnquiryResponseModel::class.java)
                if (errorResponse?.RESP_CODE?.trim() == "1000") {
                    helperLog.appendLine(helperLogClassName, "Voided Response Code Receive -> (1000)")
                    val valueHM = hashMapOf<Any, Any>(
                        "txnType" to "Void",
                        "respCode" to "0000",
                        "respDesc" to (errorResponse.RESP_DESC ?: ""),
                        "hostRefNo" to (errorResponse.QR_HOST_REFNO ?: ""),
                        "txnRefNo" to (errorResponse.QR_TXN_REFNO ?: ""),
                        "approvalCode" to (errorResponse.BANK_AUTH_CODE ?: ""),
                        "productCode" to (errorResponse.QR_PAY_BRAND ?: ""),
                        "productName" to (errorResponse.QR_PAY_BRAND_DESC ?: ""),
                        "voidDateTime" to (errorResponse.VOID_DATETIME ?: ""),
                        "isTpaAccount" to (errorResponse.IS_TPA_ACCOUNT ?: "false").toString(),
                    )
                    TransactionQrRepo.updateTransactionQr(requireContext(), valueHM, criteriaHM)


                    val receiptUploadValue = hashMapOf<Any, Any>(
                        "TXN_TYPE" to "Void",
                        "TXN_DT" to (errorResponse.VOID_DATETIME ?: ""),
                        "RRN" to (errorResponse.QR_HOST_REFNO ?: ""),
                        "RESP_CODE" to "0000",
                    )
                    ReceiptUploadRepo.updateData(requireContext(), receiptUploadValue, receiptUploadCriteria)
                    refreshFragment = true
                }
            } catch (jsonEx: Exception) {
                helperLog.appendLine(helperLogClassName, "Json Exception in Error :: $jsonEx")
            }
        }
        delay(500)
        hideProgress()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        if (refreshFragment) {
            withContext(Dispatchers.Main) {
                loadSummary()
                resetPagingAndReload()
            }
        }
    }

    private fun customOnBackPress() {
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}

