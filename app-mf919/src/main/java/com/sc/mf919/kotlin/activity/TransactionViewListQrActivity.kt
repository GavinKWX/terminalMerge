package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.handlers.QrEnquiryHandler
import tms.models.QrEnquiryResponseModel

class TransactionViewListQrActivity : ActivityBase(), FragmentTxnHistoryQr.OnFragmentInteractionListener {
    private val TAG = "TransactionViewListQrActivity"
    lateinit var mContext: Context
    lateinit var editText: EditText
    var txnList: List<DbModelTransactionQrGet> = listOf()

    var selectedRefIdValue = ""
    var selectedTxnTypeValue = ""

    var helperLogClassName :String = ""
    lateinit var helperLog: HelperLog

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_txnview_list_qr)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "QR Transaction History List"
        )
        helperLog.appendLine(helperLogClassName, "QR transaction history opened")
        val toolbar = findViewById<Toolbar>(R.id.toolbarCP)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        mContext = this@TransactionViewListQrActivity

        //TODO Dynamic Layout For Small Terminal
        val displayMetrics = resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        println("screenHeightDp :: $screenHeightDp")

        if (screenHeightDp < 500) {
            val historyBox = findViewById<FragmentContainerView>(R.id.trxHistoryCardFrameLayout)
            val params = historyBox.layoutParams
            params.height = Helper.getInstance().dpToPx(270)
            historyBox.layoutParams = params
        }
        //TODO Dynamic Layout For Small Terminal

        val btnMoreOtp = findViewById<LinearLayout>(R.id.moreOption)
        btnMoreOtp.visibility = View.VISIBLE
        btnMoreOtp.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [PRINT HISTORY MENU]")
            val popupMenu: PopupMenu = PopupMenu(this, btnMoreOtp)
            popupMenu.menuInflater.inflate(R.menu.print_history_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.printDetails -> {
                        helperLog.appendLine(helperLogClassName, "Selected :: Print Details [PRINT HISTORY MENU]")
                        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionDetailsQrActivity")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        val intent = Intent(applicationContext, TransactionDetailsQrActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                    }

                    R.id.printSummary -> {
                        helperLog.appendLine(helperLogClassName, "Selected :: Print Summary [PRINT HISTORY MENU]")
                        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementQrActivity (previewSettlement)")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        val intent = Intent(applicationContext, SettlementQrActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.putExtra("previewSettlement", 1)
                        startActivity(intent)
                        finish()
                    }

                    R.id.printLastSettlement -> {
                        helperLog.appendLine(helperLogClassName, "Selected :: Print Last Settlement [PRINT HISTORY MENU]")
                        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementQrActivity (previewLastSettlement)")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        val intent = Intent(applicationContext, SettlementQrActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.putExtra("Last", 1)
                        intent.putExtra("previewLastSettlement", 1)
                        startActivity(intent)
                        finish()
                    }

                }
                true
            }
            popupMenu.show()
        }

        editText = findViewById(R.id.inv)
        editText.addTextChangedListener(callbackET)
        lifecycleScope.launch {
            getPrintReceiptList()
        }
    }

    suspend fun getPrintReceiptList(reloadTransaction: Boolean = false) = withContext(Dispatchers.IO) {
        if(txnList.isEmpty() || reloadTransaction){
            startProgressDialog(this@TransactionViewListQrActivity, "", "Loading...")
            txnList = TransactionQrRepo.getListTransactionQrDesc(applicationContext, listOf(), listOf())

            var txnCount = 0
            var txnAmount = 0L
            var voidCount = 0
            txnList.forEach {
                val txnType = it.txnType ?: ""
                if(txnType.contains("void", true)){
                    voidCount += 1
                } else if(it.respCode == "0000") {
                    txnCount += 1
                    val tempLongAmount = it.txnAmount?.toLongOrNull() ?: 0
                    txnAmount += tempLongAmount
                }
            }
            runOnUiThread {
                (findViewById<View>(R.id.sale_count) as TextView).text = txnCount.toString()
                (findViewById<View>(R.id.sale_amt) as TextView).text = Utils.getActualAmount(txnAmount.toString())
                (findViewById<View>(R.id.void_count) as TextView).text = voidCount.toString()
            }
            delay(500)
            closeProgressDialog()
        }

        var filteredTxnList = txnList
        if(selectedTxnTypeValue == "Sale") {
            filteredTxnList = filteredTxnList.filter { txn ->
                txn.txnType?.contains(selectedTxnTypeValue, ignoreCase = true) ?: true && txn.respCode == "0000"
            }
        } else if (selectedTxnTypeValue == "Failed") {
            filteredTxnList = filteredTxnList.filter { txn ->
                txn.respCode != "0000"
            }
        } else if (selectedTxnTypeValue.isNotBlank()) {
            filteredTxnList = filteredTxnList.filter { txn ->
                txn.txnType?.contains(selectedTxnTypeValue, ignoreCase = true) ?: true
            }
        }

        filteredTxnList =  filteredTxnList.filter { txn ->
            txn.refId?.contains(selectedRefIdValue, ignoreCase = true) ?: true
        }
        processWalletHistoryFragment(filteredTxnList)
    }

    private suspend fun processWalletHistoryFragment(cardTxnList: List<DbModelTransactionQrGet>) = withContext(Dispatchers.Main) {
        // Fragment commits are main-thread only; the caller runs on Dispatchers.IO.
        val bundle = Bundle()
        bundle.putParcelableArrayList("transList", ArrayList(cardTxnList))
        val fragment = FragmentTxnHistoryQr()
        fragment.arguments = bundle
        supportFragmentManager.beginTransaction().replace(R.id.trxHistoryCardFrameLayout, fragment).addToBackStack(null).commit()
    }

    fun tv_btn_cancel(view: View?) {
        helperLog.appendLine(helperLogClassName, "Cancel button pressed")
        customOnBackPress()
    }
    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "User Cancel :: left QR transaction history, navigate -> home screen")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    var callbackET: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            Utils.debugLogPrint("TAG", "beforeTextChanged: $s---$count")
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            Utils.debugLogPrint("TAG", "onTextChanged: $s---$count")
            val strRefId = s.toString()
            selectedRefIdValue = strRefId
        }

        override fun afterTextChanged(s: Editable) {
            Utils.debugLogPrint("TAG", "afterTextChanged: $s")
            lifecycleScope.launch {
                getPrintReceiptList()
            }
        }
    }

    private suspend fun reQueryTransaction(dbModelTransactionQrGet: DbModelTransactionQrGet) = withContext(Dispatchers.Default) {
        startProgressDialog(mContext, "Re-Enquiry", "Loading...")
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
        val helperLog = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "TransactionViewActivity",
            TransactionViewListQrActivity::class.java.simpleName,
            TransactionViewListQrActivity::class.java.simpleName,
        )
        helperLog.appendLine(helperLogClassName, "Re-Enquiry start :: refId ${dbModelTransactionQrGet.refId ?: "-"}, " +
            "txnType ${dbModelTransactionQrGet.txnType ?: "-"}, current respCode ${dbModelTransactionQrGet.respCode ?: "-"}")

        try {
            val qrEnquiryHandler = QrEnquiryHandler(environmentManager)
            val qrEnquiryResp = qrEnquiryHandler.invoke(
                helperLog,
                merchantConfig?.QrMid ?: "",
                merchantConfig?.QrTid ?: "",
                dbModelTransactionQrGet.refId ?: "",
                apiTimeoutSec = 30
            )
            helperLog.appendLine(helperLogClassName, "Re-Enquiry result :: refId ${dbModelTransactionQrGet.refId ?: "-"}, " +
                "respCode ${qrEnquiryResp.RESP_CODE ?: "-"}, respDesc ${qrEnquiryResp.RESP_DESC ?: "-"}, " +
                "hostRef ${qrEnquiryResp.QR_HOST_REFNO ?: "-"}, apprCode ${qrEnquiryResp.BANK_AUTH_CODE ?: "-"}")

            val valueHM = hashMapOf<Any, Any>(
                "paybrand" to (qrEnquiryResp.QR_PAY_BRAND ?: ""),
                "mid" to (qrEnquiryResp.ACQ_MID ?: ""),
                "acqCode" to (qrEnquiryResp.ACQ_CODE ?: ""),
                "respCode" to (qrEnquiryResp.RESP_CODE ?: ""),
                "respDesc" to (qrEnquiryResp.RESP_DESC ?: ""),
                "hostRefNo" to (qrEnquiryResp.QR_HOST_REFNO ?: ""),
                "txnRefNo" to (qrEnquiryResp.QR_TXN_REFNO ?: ""),
                "approvalCode" to (qrEnquiryResp.BANK_AUTH_CODE ?: ""),
                "productCode" to (qrEnquiryResp.QR_PAY_BRAND ?: ""),
                "productName" to (qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""),
                "isTpaAccount" to ((qrEnquiryResp.IS_TPA_ACCOUNT ?: "false").toString()),
            )
            TransactionQrRepo.updateTransactionQr(applicationContext, valueHM, criteriaHM)

            val receiptUploadValue = hashMapOf<Any, Any>(
                "MID" to (qrEnquiryResp.ACQ_MID ?: ""),
                "QrType" to (qrEnquiryResp.QR_PAY_BRAND ?: ""),
                "RRN" to (qrEnquiryResp.QR_HOST_REFNO ?: ""),
                "RESP_CODE" to (qrEnquiryResp.RESP_CODE ?: ""),
            )
            ReceiptUploadRepo.updateData(mContext, receiptUploadValue, receiptUploadCriteria)
            refreshFragment = true
            /*lifecycleScope.launch {
                getPrintReceiptList(reloadTransaction = true)
            }*/
        }catch (ex: Exception){
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "QrEnquiryHandler (Exception) -> ${ex.message ?: "-"}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)

            try {
                val errorResponse = Gson().fromJson(ex.message, QrEnquiryResponseModel::class.java)
                if(errorResponse?.RESP_CODE?.trim() == "1000") {
                    helperLog.appendLine(helperLogClassName, "Re-Enquiry result :: refId ${dbModelTransactionQrGet.refId ?: "-"} already voided (respCode 1000)")
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
                        "isTpaAccount" to ((errorResponse.IS_TPA_ACCOUNT ?: "false").toString()),
                    )
                    TransactionQrRepo.updateTransactionQr(applicationContext, valueHM, criteriaHM)


                    val receiptUploadValue = hashMapOf<Any, Any>(
                        "TXN_TYPE" to "Void",
                        "TXN_DT" to (errorResponse.VOID_DATETIME ?: ""),
                        "RRN" to (errorResponse.QR_HOST_REFNO ?: ""),
                        "RESP_CODE" to "0000",
                    )
                    ReceiptUploadRepo.updateData(mContext, receiptUploadValue, receiptUploadCriteria)
                    refreshFragment = true
                    /*lifecycleScope.launch {
                        getPrintReceiptList(reloadTransaction = true)
                    }*/
                }
            } catch (jsonEx: Exception) {
                helperLog.appendLine(helperLogClassName, "Json Exception in Error -> ${jsonEx.message ?: "-"}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
        }
        delay(500)
        closeProgressDialog()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        if(refreshFragment) {
            lifecycleScope.launch {
                getPrintReceiptList(reloadTransaction = true)
            }
        }
    }

    override fun fragmentTxnHistoryQrAction() {
        customOnBackPress()
    }

    override fun fragmentQrHistoryEnquiry(dbModelTransactionQrGet: DbModelTransactionQrGet) {
        helperLog.appendLine(helperLogClassName, "Selected :: Re-Enquiry refId ${dbModelTransactionQrGet.refId ?: "-"} [QR TRANSACTION HISTORY]")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        CoroutineScope(Dispatchers.Default).launch {
            reQueryTransaction(dbModelTransactionQrGet)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionViewListQr OnDestroy :: QR transaction history ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}