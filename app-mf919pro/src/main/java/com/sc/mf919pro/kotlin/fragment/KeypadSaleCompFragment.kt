package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentKeypadSalecompletionBinding
import com.sc.mf919pro.java.activity.EmvTag
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.Utils
import utils.CardUtil
import com.sc.mf919pro.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelPreAuthTable
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.LogRedact
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeypadSaleCompFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var preAuthInfo: DbModelPreAuthTable
    lateinit var textViewApvCode: EditText
    lateinit var textViewRrn: EditText
    lateinit var textViewInvNo: EditText
    var posReference: String? = null
    var txnAmount: Long = 0
    var approvalCode: String? = null
    var rrn: String? = null
    var invoiceNo: String? = null
    var cardPan: String = ""

    private var _binding: FragmentKeypadSalecompletionBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeypadSalecompletionBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "KeypadSaleComp OnDestroyView :: screen ended")
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
            "Sales Completion Keypad Input Action"
        )
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }

        textViewApvCode = binding.tvSaleCompApvCode
        textViewRrn = binding.tvSaleCompRefNo
        textViewInvNo = binding.tvSaleCompInvNo

        posReference = arguments?.getString("posReference", "-")
        transData.reset()
        ServiceHolder.saleModelCache?.let {
            txnAmount = it.TransAmount
        }

        //Start Bottom Group Button
        binding.buttonBack.setDebouncedOnClickListener {
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                approvalCode = textViewApvCode.text.toString()
                rrn = textViewRrn.text.toString()
                invoiceNo = textViewInvNo.text.toString()
                searchRecord()
            }
        }

        if (ServiceHolder.appIntent || ServiceHolder.appHTTP) {
            if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "appIntent :: ${ServiceHolder.appIntent}")
            if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "appHTTP :: ${ServiceHolder.appHTTP}")
            val approvalCodeArgument = arguments?.getString("apprCode")
            val rrnArgument = arguments?.getString("rrn")
            val invNoArgument = arguments?.getString("invNo")

            if(!approvalCodeArgument.isNullOrEmpty() && !rrnArgument.isNullOrEmpty() && !invNoArgument.isNullOrEmpty()) {
                textViewApvCode.setText(approvalCodeArgument)
                textViewRrn.setText(rrnArgument)
                textViewInvNo.setText(invNoArgument)
                viewLifecycleOwner.lifecycleScope.launch {
                    approvalCode = textViewApvCode.text.toString()
                    rrn = textViewRrn.text.toString()
                    invoiceNo = textViewInvNo.text.toString()
                    searchRecord()
                }
            }
        }
    }

    private suspend fun searchRecord() {
        if (approvalCode.isNullOrEmpty() || rrn.isNullOrEmpty() || invoiceNo.isNullOrEmpty()) {
            showToast("Invalid Input", Toast.LENGTH_SHORT)
            return
        }
        showProgress("Finding the Transaction", "Searching...")

        try{
            val criteriaList = listOf("apprCode", "rrn", "invNo")
            val valueList = arrayOf(approvalCode!!, rrn!!, invoiceNo!!)
            val fetchPreAuthRecord = withContext(Dispatchers.IO) {
                PreAuthTableRepo.getSingle(requireContext(), criteriaList, valueList)
            }

            if(fetchPreAuthRecord != null) {
                preAuthInfo = fetchPreAuthRecord
                helperLog.appendLine(helperLogClassName, "PreAuth Table Record :: $fetchPreAuthRecord")
                showConfirmationDialog(preAuthInfo)
            } else {
                showToast("Invalid Input", Toast.LENGTH_SHORT)
                if (ServiceHolder.appIntent) {
                    val txnMap = HashMap<String, String>()
                    txnMap["ResponseCode"] = "SHC001"
                    txnMap["ResponseDescription"] = "Invalid Transaction Details"
                    delay(500L)
                    hideProgress()
                    onBackToApp(txnMap)
                } else if (ServiceHolder.appHTTP) {
                    val jObject = JsonObject()
                    try {
                        jObject.addProperty("ResponseCode", "SHC001")
                        jObject.addProperty("ResponseDescription", "Invalid Transaction Details")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    HTTPServer.getInstance().setResponseMessage(jObject.toString())
                    delay(500L)
                    hideProgress()
                    customOnBackPress()
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            delay(500L)
            hideProgress()
        }
    }

    private fun showConfirmationDialog(dbModelPreAuthTable: DbModelPreAuthTable) {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater

        var preAuthAmount = ""
        var maskedPan = ""
        try{
            val batchData = dbModelPreAuthTable.addInfo

            val bBatchInfo = HexUtil.hexStringToByte(batchData)
            val etag = EmvTag()
            val bDe55 = ByteArray(258)
            val bDe55_1 = ByteArray(256)
            val bTxnAmt = ByteArray(6)
            val tmpCardPan = ByteArray(12)

            etag.getValueFrom(bBatchInfo, "DF55", bDe55_1, 2)
            Utils.printLog("DE55_1 -> ${HexUtil.bytesToHexString(bDe55_1)}")

            bDe55[0] = 0x01.toByte()
            bDe55[1] = 0x00.toByte()
            System.arraycopy(bDe55_1, 0, bDe55, 2, bDe55_1.size)
            Utils.printLog("bDe55 -> ${HexUtil.bytesToHexString(bDe55)}")

            val foundAmountTag = etag.getValueFrom(bDe55, "9F02", bTxnAmt)
            if(foundAmountTag == -1){
                etag.getValueFrom(bBatchInfo, "DF04", bTxnAmt, 2)
            }
            //TODO
            preAuthAmount = HexUtil.bytesToHexString(bTxnAmt)
            if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Amount -> $preAuthAmount")

            //Card pan
            val iCardPanLen = etag.getValueFrom(bBatchInfo, "DF02", tmpCardPan)
            val bCardPan = ByteArray(iCardPanLen)
            System.arraycopy(tmpCardPan, 0, bCardPan, 0, iCardPanLen)
            Utils.printLog( "DF02 -> ${LogRedact.track2(HexUtil.bytesToHexString(bCardPan))}")
            var tempPan = HexUtil.bytesToHexString(bCardPan)
            tempPan = tempPan.replace("F", "")
            cardPan = tempPan
            Utils.printLog("Temp Pan -> ${Utils.hideCardDetails(tempPan)}")
            maskedPan = Utils.hideCardDetails(tempPan)
        } catch (ex: Exception){
            ex.printStackTrace()
            Utils.printLog( ex.message)
        }

        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.activity_salecomplete_confirmation, null)
        (dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = Utils.getActualAmount(preAuthAmount)
        (dialogView.findViewById<View>(R.id.amount_tr_salecomplete) as TextView).text = Utils.getActualAmount(txnAmount.toString())
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = maskedPan
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = approvalCode
        (dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invoiceNo
        helperLog.appendLine(helperLogClassName,
            "Dialog opened :: Sale Completion confirmation [amt=${Utils.getActualAmount(txnAmount.toString())} inv=$invoiceNo auth=$approvalCode card=$maskedPan]")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: sale completion declined at confirmation dialog")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            alertDialog?.dismiss()
            if (ServiceHolder.appIntent) {
                val txnMap = HashMap<String, String>()
                txnMap["ResponseCode"] = "SHC005"
                txnMap["ResponseDescription"] = "User Cancel the Transaction"
                onBackToApp(txnMap)
            } else if (ServiceHolder.appHTTP) {
                val jsonObject = JsonObject()
                try {
                    jsonObject.addProperty("ResponseCode", "SHC005")
                    jsonObject.addProperty("ResponseDescription", "User Cancel the Transaction")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                HTTPServer.getInstance().setResponseMessage(jsonObject.toString())
                customOnBackPress()
            }
        }
        val confirmBtn = dialogView.findViewById<Button>(R.id.confirmBtn)
        confirmBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Validation passed :: operator confirmed sale completion -> saleComplete")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                saleComplete()
            }
        }
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun saleComplete() {
        ServiceHolder.isoComm = null
        showProgress("Bank Authorization", "Waiting for Approval")

        val mContext = requireContext()
        val isNotCompl = booleanArrayOf(true)
        try {
            TransData.startTime = System.currentTimeMillis()
            ServiceHolder.saleModelCache?.let {
                TransData.salesType = it.SalesType
                TransData.amount = it.TransAmount
                TransData.amountString = Utils.getActualAmount(it.TransAmount.toString())
                TransData.acqCode = it.AcqCode ?: ""
                TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
                TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
                TransData.product = it.Product ?: ""
                TransData.productName = it.ProductName ?: ""
                //TransData.productCode = it.Product ?: ""
                TransData.eppTenure = it.EppTenure ?: ""
                TransData.eppTenureCode = it.EppTenureCode ?: ""
                TransData.ksn = it.Ksn ?: ""
                TransData.pinKsn = it.PinKsn ?: ""
                TransData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
            }
            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            TransData.transDateAsci = txnDt
            TransData.txnTypeLabel = "Sale Completion"
            TransData.schemeTag = preAuthInfo?.schemeTag ?: ""

             withContext(Dispatchers.IO) {
                 // DT3 — atomic allocation; the open-coded get/increment/put could hand the
                 // same stan to two concurrent flows.
                 TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
                 //helperLog.appendLine(helperlogClassName, "STAN :: ${TransData.stan}")

                 val // DT3 — atomic allocation; the open-coded get/increment/put could hand the
 // same invoiceNo to two concurrent flows.
 dbInvoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")
                 //helperLog.appendLine(helperlogClassName, "Invoice No :: ${TransData.invoiceNo}")

                 TransData.invoiceNo = invoiceNo!!
                 //TransData.amount = txnAmount
                 HexUtil.hexStringToByte(Utils.zeroPadding(transData.amount.toString(), 12)).copyInto(TransData.amountAuth)
                 TransData.maskedPan = Utils.hideCardDetails(cardPan)
                 TransData.hashedPan = cardPan.substring(0, 9)
                 val bytePan = HexUtil.hexStringToByte(cardPan)
                 bytePan.copyInto(TransData.pan, 0)
                 val oldTransDb = HexUtil.hexStringToByte(preAuthInfo?.addInfo)
                 oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
                 TransData.transactionDbLen = oldTransDb.size - 2
                 TransData.schemeId = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_SCHEME_ID, 16)
                 TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)
                 TransData.schemeType = CardUtil.getCardTypFromAid(TransData.aid)
                 TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", TransData.schemeTag)?.value ?: "000001"
                 TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
                 TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)
                 posReference?.let {
                     TransData.posReference = it
                     helperLog.appendLine(helperLogClassName, "Add Pos Reference :: $it")
                 }

                 CoroutineScope(Dispatchers.IO).launch {
                     IsoActivity.processSaleComp(mContext, helperLog)
                     isNotCompl[0] = false
                 }
                 while (isNotCompl[0]) {
                     val isoComm = ServiceHolder.isoComm
                     if (isoComm != null) {
                         val status = isoComm.connectionStatus
                         if (!status.isNullOrEmpty()) {
                             updateProgress(null, status)
                         }
                     }
                     delay(500L)
                 }
             }

            //Reversal //Skip Reversal for MyDebit SaleCom
            // Same StorageGuard exclusion as EmvFragment — see the comment there.
            if(!TransData.schemeType.equals("MCCS", true) && TransData.transResult != Global.iso.err.txnApproved &&
                TransData.transResult != Global.iso.err.txnNotAllowed &&
                (TransData.transResult == Global.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                ServiceHolder.isoComm = null
                isNotCompl[0] = true
                CoroutineScope(Dispatchers.IO).launch {
                    var loop = 0
                    val maxLoop = 3
                    while (loop < maxLoop) {
                        loop++
                        updateProgress("Reversal ($loop)", null)

                        val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
                        acquirerRevIsoModel?.let { revIsoModel ->
                            val allIsoString = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
                            val result = IsoActivity.processReversal(mContext, false, revIsoModel, allIsoString, true, helperLog)
                            helperLog.appendLine(helperLogClassName, "reversal result :: $result")
                            if(result != null){
                                loop = maxLoop // used for exit
                            }
                        }
                    }
                    isNotCompl[0] = false
                }

                while (isNotCompl[0]) {
                    val isoComm = ServiceHolder.isoComm
                    if (isoComm != null) {
                        val status = isoComm.connectionStatus
                        if (!status.isNullOrEmpty()) {
                            updateProgress(null, status)
                        }
                    }
                    delay(500L)
                }
            }
        } catch (ex : Exception) {
            ex.printStackTrace()
        } finally {
            isNotCompl[0] = false
            withContext(Dispatchers.IO) {
                helperLog.appendLine(helperLogClassName, "Sale Completion Transaction End")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            delay(500L)
            hideProgress()
        }
        navigateSafe(R.id.action_keypadCompletion_to_transactionResult)
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }

    private fun onBackToApp(txn_map: HashMap<String, String>) {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@KeypadSaleCompFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "KeypadSaleComp :: returning to caller app, response -> ${txn_map["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txn_map)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}