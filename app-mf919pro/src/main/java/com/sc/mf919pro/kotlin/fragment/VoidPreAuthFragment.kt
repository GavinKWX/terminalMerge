package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentVoidsalerefundBinding
import com.sc.mf919pro.java.activity.EmvTag
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.UploadTMS
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelPreAuthTable
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appRunningProcess
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
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
import org.json.JSONException

class VoidPreAuthFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var preAuthTableModel: DbModelPreAuthTable
    var posReference: String? = null
    lateinit var amount: String
    lateinit var cardPan: String
    lateinit var prevStan: String

    private var _binding: FragmentVoidsalerefundBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoidsalerefundBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "VoidPreAuth OnDestroyView :: screen ended")
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
            "Void PreAuth Payment Fragment"
        )
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        binding.textHeader.text = "PreAuth Cancel"
        helperLog.appendLine(helperLogClassName, "Void Pre Auth Initialization")
        transData.reset()

        val keypadNum = binding.keypadVoid
        val tv = binding.textViewVoidInvNo
        keypadNum.setFilter(tv, false, 6, null)

        posReference = arguments?.getString("posReference", "-")
        val dbModelTerminalConfig = getTerminalConfig()
        val voidWithPIN = getSafeValue(dbModelTerminalConfig, "VOID_WITH_PIN")
        if (voidWithPIN.toInt() == 1) {
            pinDialog("") { canceled ->
                if (canceled) {
                    customOnBackPress()
                }
            }
        }

        //TODO Bottom Group
        binding.buttonBack.setDebouncedOnClickListener {
            customOnBackPress()
        }

        binding.buttonOK.setDebouncedOnClickListener {
            val msg = tv.text.toString()
            if (msg.isNotEmpty()){
                helperLog.appendLine(helperLogClassName, "Input Invoice No :: $msg")
                viewLifecycleOwner.lifecycleScope.launch {
                    val invNo = Utils.paddingWith(msg, "0", 6, false)
                    helperLog.appendLine(helperLogClassName, "onOK :: $invNo")
                    searchByInvoice(invNo)
                }
            } else {
                helperLog.appendLine(helperLogClassName, "Invoice No is Empty")
                showToast("Invoice No is Empty!", Toast.LENGTH_SHORT)
            }
        }

        if (ServiceHolder.appIntent || ServiceHolder.appHTTP) {
            val argumentInvoice = arguments?.getString("Invoice")
            if(!argumentInvoice.isNullOrEmpty()) {
                tv.text = argumentInvoice
                viewLifecycleOwner.lifecycleScope.launch {
                    helperLog.appendLine(helperLogClassName, "onOK(arguments)  ::  $argumentInvoice")
                    tv.text = argumentInvoice
                    searchByInvoice(argumentInvoice)
                }
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private suspend fun searchByInvoice(invoiceNum: String) {
        showProgress("Finding the Transaction", "Searching...")
        try {
            val criteriaList = listOf("invNo")
            val valueList = listOf(invoiceNum)

            val fetchPreAuthRecord = withContext(Dispatchers.IO) {
                PreAuthTableRepo.getSinglev2(requireContext(), criteriaList, valueList)
            }
            if(fetchPreAuthRecord != null) {
                preAuthTableModel = fetchPreAuthRecord
                /*
                 * Register the PAN BEFORE logging the record. addInfo is the raw batch TLV and
                 * carries the PAN, so registering further down (after cardPan is parsed) was too
                 * late -- this line had already gone to disk unredacted. Pre-auth reads its own
                 * table rather than VoidSaleUseCase.lookupInvoice, so it needs its own call.
                 */
                runCatching {
                    val panTag = ByteArray(64)
                    val panLen = EmvTag().getValueFrom(HexUtil.hexStringToByte(preAuthTableModel.addInfo), "DF02", panTag)
                    if (panLen > 0) {
                        LogRedact.registerCardData(
                            HexUtil.bytesToHexString(panTag, 0, panLen).replace("F", ""), null
                        )
                    }
                }
                helperLog.appendLine(helperLogClassName, "PreAuth Table Record :: ${Gson().toJson(preAuthTableModel)}")

                if (preAuthTableModel.cardData.equals("MCCS", ignoreCase = true)) {
                    helperLog.appendLine(helperLogClassName, "MyDebit PreAuth Void is not supported")
                    showToast("MyDebit PreAuth Void is not supported", Toast.LENGTH_SHORT)
                    if (ServiceHolder.appIntent) {
                        val txnMap = java.util.HashMap<String, String>()
                        txnMap["ResponseCode"] = "SHC010"
                        txnMap["ResponseDescription"] = "MyDebit Preauth Void is not supported"
                        delay(500L)
                        hideProgress()
                        onBackToApp(txnMap)
                    } else if (ServiceHolder.appHTTP) {
                        val jObject = JsonObject()
                        try {
                            jObject.addProperty("ResponseCode", "SHC010")
                            jObject.addProperty("ResponseDescription", "MyDebit Preauth Void is Not Supported")
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                        HTTPServer.getInstance().setResponseMessage(jObject.toString())
                        delay(500L)
                        hideProgress()
                        customOnBackPress()
                    }
                    throw Exception()
                }

                val bBatchInfo = HexUtil.hexStringToByte(preAuthTableModel.addInfo)
                val emvTag = EmvTag()
                val bTxnAmt = ByteArray(6)
                var bCardPanLen: Int
                val bCardPan = ByteArray(12)
                val bApprCode = ByteArray(6)
                val bStan = ByteArray(3)

                emvTag.getValueFrom(bBatchInfo, "BF38", bApprCode)
                emvTag.getValueFrom(bBatchInfo, "DF04", bTxnAmt)
                emvTag.getValueFrom(bBatchInfo, "DF11", bStan)
                bCardPanLen = emvTag.getValueFrom(bBatchInfo, "DF02", bCardPan)

                amount = HexUtil.bytesToHexString(bTxnAmt)
                cardPan = HexUtil.bytesToHexString(bCardPan, 0, bCardPanLen).replace("F", "")  /*Remove padding "F"*/
                prevStan = HexUtil.bytesToHexString(bStan)
                showConfirmationDialog(Utils.getActualAmount(amount), Utils.hideCardDetails(cardPan), preAuthTableModel.apprCode, preAuthTableModel.invNo)
            } else {
                helperLog.appendLine(helperLogClassName, "REJECT :: invoice not found in batch, void cannot proceed")
                showToast("Invalid Input", Toast.LENGTH_SHORT)
                if (ServiceHolder.appIntent) {
                    val txnMap = java.util.HashMap<String, String>()
                    txnMap["ResponseCode"] = "SHC001"
                    txnMap["ResponseDescription"] = "Invalid Transaction Invoice"
                    delay(500L)
                    hideProgress()
                    onBackToApp(txnMap)
                } else if (ServiceHolder.appHTTP) {
                    val jObject = JsonObject()
                    try {
                        jObject.addProperty("ResponseCode", "SHC001")
                        jObject.addProperty("ResponseDescription", "Invalid Transaction Invoice")
                    } catch (e: JSONException) {
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

    private fun showConfirmationDialog(amt: String?, cardNo: String?, authCode: String?, invNo: String?) {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.activity_voidpreauth_confirmation, null)

        (dialogView.findViewById<View>(R.id.amount_tr_preauth) as TextView).text = amt
        (dialogView.findViewById<View>(R.id.cardNo_tr_preauth) as TextView).text = cardNo
        (dialogView.findViewById<View>(R.id.authCode_tr_preauth) as TextView).text = authCode
        (dialogView.findViewById<View>(R.id.traceNo_tr_preauth) as TextView).text = invNo
        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn_preauthcancel)
        cancelBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: pre-auth void declined at confirmation dialog")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            alertDialog?.dismiss()
            if (ServiceHolder.appIntent) {
                val txn_map = HashMap<String, String>()
                txn_map["ResponseCode"] = "SHC005"
                txn_map["ResponseDescription"] = "User Cancel the Transaction"
                onBackToApp(txn_map)
            } else if (ServiceHolder.appHTTP) {
                val jsonObject = JsonObject()
                try {
                    jsonObject.addProperty("ResponseCode", "SHC005")
                    jsonObject.addProperty("ResponseDescription", "User Cancel the Transaction")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                HTTPServer.getInstance().setResponseMessage(jsonObject.toString())
                customOnBackPress()
            }
        }
        val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidPreauthConfirmBtn)
        voidConfirmBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Validation passed :: operator confirmed pre-auth void -> voidPreAuth")
            appRunningProcess = true
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                voidPreAuth()
            }
        }
        helperLog.appendLine(helperLogClassName,
            "Dialog opened :: Void Pre-Auth confirmation [amt=$amt inv=$invNo auth=$authCode card=$cardNo]")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun voidPreAuth() {
        ServiceHolder.isoComm = null
        showProgress("Bank Authorization", "Waiting for Approval")

        val mContext = requireContext()
        val isNotCompl = booleanArrayOf(true)
        try {
            TransData.startTime = System.currentTimeMillis()
            ServiceHolder.saleModelCache?.let {
                //TransData.salesType = it.SalesType
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
            TransData.txnTypeLabel = "PreAuth Cancel"

            withContext(Dispatchers.IO) {
                TransData.schemeTag = preAuthTableModel.schemeTag
                TransData.prevStan = prevStan
                TransData.prevInvoice = preAuthTableModel.invNo
                TransData.prevRRN = preAuthTableModel.rrn
                TransData.prevApprovalCode = preAuthTableModel.apprCode
                // DT3 — atomic allocation; the open-coded get/increment/put could hand the
                // same stan to two concurrent flows.
                TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
                helperLog.appendLine(helperLogClassName, "STAN :: ${TransData.stan}")

                val // DT3 — atomic allocation; the open-coded get/increment/put could hand the
 // same invoiceNo to two concurrent flows.
 invoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")
                helperLog.appendLine(helperLogClassName, "Invoice No :: ${TransData.invoiceNo}")

                TransData.invoiceNo = preAuthTableModel.invNo
                TransData.amount = amount.toLong()
                val byteAmount = HexUtil.hexStringToByte(amount)
                byteAmount.copyInto(TransData.amountAuth, 0, 0, byteAmount.size)
                TransData.maskedPan = Utils.hideCardDetails(cardPan)
                TransData.hashedPan = cardPan.substring(0, 9)
                val bytePan = HexUtil.hexStringToByte(cardPan)
                bytePan.copyInto(TransData.pan, 0)
                val oldTransDb = HexUtil.hexStringToByte(preAuthTableModel.addInfo)
                oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
                TransData.transactionDbLen = oldTransDb.size - 2
                TransData.schemeId = TransData.getFromTransactionDb("DA", 16)
                TransData.batchNo = TransData.getFromTransactionDb("DF60", 256)
                TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
                TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)
                TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)
                posReference?.let {
                    TransData.posReference = it
                    helperLog.appendLine(helperLogClassName, "Add Pos Reference :: $it")
                }

                CoroutineScope(Dispatchers.IO).launch {
                    IsoActivity.processVoidPreauth(mContext, isNotCompl, helperLog)
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
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            isNotCompl[0] = false
            withContext(Dispatchers.IO) {
                helperLog.appendLine(helperLogClassName, "Void PreAuth Transaction End")
                helperLog.appendLine(helperLogClassName, "Send PreAuth Void Receipt to TMS")
                sendTmsVoidPreAuthReceipt()
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            delay(500L)
            hideProgress()
        }

        navigateSafe(R.id.action_voidPreAuth_to_transactionResult)
    }

    //suspend fun sendTmsVoidPreAuthReceipt() = withContext(Dispatchers.IO){
    private fun sendTmsVoidPreAuthReceipt() {
        val timeStamp = Utils.DateTimeFormat(TransData.transDateAsci)
        val strTxnType = "VoidPreAuth"
        val strPaymentProductId = ""
        val strSchemeId = TransData.schemeId
        val strStan = TransData.stan
        val strInvNo = TransData.invoiceNo
        val strTxnAmt = HexUtil.bytesToHexString(TransData.amountAuth)
        val strRrn = TransData.rrn
        val strApprCode = TransData.approvalCode
        val strRrnOri = TransData.prevRRN
        val strApprCodeOri = TransData.prevApprovalCode
        val strTid = TransData.tid
        val strMid = TransData.mid
        val strBatchNo = TransData.batchNo
        val strPreAuthBatchNo = TransData.getFromTransactionDb("BF60", 256)
        val strRespCode =  Utility.HexString2ASCII(TransData.respCode)
        val strAid = TransData.aid
        val mti = TransData.getFromTransactionDb(Global.iso.tag.MTI, 16)
        val strNii = TransData.getFromTransactionDb("DF24", 16)
        val strMaskPanBcd = TransData.maskedPan
        val strHashedPanBcd = TransData.hashedPan
        val strEntryType = TransData.entryModeLabel
        val strARQC = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
        val strTVR = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
        val strPosReference = TransData.posReference
        val strCardLabel = Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen)
        val strCvm = TransData.cvm

        val jsonObject = JsonObject()
        try {
            jsonObject.addProperty("SEQ_NO", ServiceHolder.getSqnNum())
            jsonObject.addProperty("TXN_DT", timeStamp)
            jsonObject.addProperty("TXN_TYPE", strTxnType)
            jsonObject.addProperty("MID", strMid)
            jsonObject.addProperty("TID", strTid)
            jsonObject.addProperty("MTI", mti)
            jsonObject.addProperty("NII", strNii)
            jsonObject.addProperty("SCHEME_ID", strSchemeId)
            jsonObject.addProperty("AID", strAid)
            jsonObject.addProperty("CARD_MASKED", strMaskPanBcd)
            jsonObject.addProperty("CARD_HASHED", strHashedPanBcd)
            jsonObject.addProperty("RRN", strRrn)
            jsonObject.addProperty("APPR_CODE", strApprCode)
            jsonObject.addProperty("RRN_ORI", strRrnOri)
            jsonObject.addProperty("APPR_CODE_ORI", strApprCodeOri)
            jsonObject.addProperty("TXN_AMT", strTxnAmt)
            jsonObject.addProperty("INV_NO", strInvNo)
            jsonObject.addProperty("STAN", strStan)
            jsonObject.addProperty("BATCH_NO", strBatchNo)
            jsonObject.addProperty("RESP_CODE", strRespCode)
            jsonObject.addProperty("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.addProperty("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.addProperty("ENTRY_TYPE", strEntryType)
            jsonObject.addProperty("ARQC", strARQC)
            jsonObject.addProperty("TVR", strTVR)
            jsonObject.addProperty("PAYMENT_PRODUCT_ID", strPaymentProductId)
            jsonObject.addProperty("POS_REF_NO", strPosReference)
            jsonObject.addProperty("CARD_LABEL", strCardLabel)
            jsonObject.addProperty("CVM", strCvm)
            jsonObject.addProperty("BATCHNO_PREAUTH", strPreAuthBatchNo)
            //jsonObject.addProperty("EPP_DETAIL", hmEppDetails)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val body = jsonObject.toString()
        UploadTMS.getInstance().addReceipt(body)
    }

    private fun customOnBackPress() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        ServiceHolder.appIntent = false
        ServiceHolder.appHTTP = false
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }

    private fun onBackToApp(txn_map: HashMap<String, String>) {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@VoidPreAuthFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "VoidPreAuth :: returning to caller app, response -> ${txn_map["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txn_map)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}