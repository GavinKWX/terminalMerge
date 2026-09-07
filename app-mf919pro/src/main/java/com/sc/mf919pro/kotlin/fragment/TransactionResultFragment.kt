package com.sc.mf919pro.kotlin.fragment

import mdb.MdbController

import helpers.LogRedact
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTransactionresultBinding
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoEnumModel
import data_enum.CardErrorDataEnum
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.json.JSONException
import org.json.JSONObject

class TransactionResultFragment : BaseFragment(),  FragmentResult.OnFragmentInteractionListener, FragmentReceipt.OnFragmentInteractionListener {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var desc: String = ""
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

    //App to App
    lateinit var txn_map: HashMap<String, String>

    private var _binding: FragmentTransactionresultBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransactionresultBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        // D7 — the in-flight card data must not outlive the transaction.
        LogRedact.clearCardData()
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionResult OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun fragmentResultBackAction() {
        customOnBackPress()
    }

    override fun fragmentReceiptBackAction() {
        customOnBackPress()
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
            "Transaction Result Initialization"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction Result Fragment")
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )
        ServiceHolder.appRunningProcess = false

        acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about), resources.getString(R.string.app_name_about))
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
            } catch (e: Exception) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Exception in Card Sales Get Acquirer Enum -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
        }

        //App to App
        txn_map = HashMap()
        var respCode = ""
        try {
            respCode = Utility.HexString2ASCII(transData.respCode)
            val formedEnumTag = "TAG_$respCode"
            desc = "(" + respCode + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
        } catch (e: Exception) {
            desc = respCode.ifEmpty { "Failed" }
            helperLog.appendLine(helperLogClassName, "Card Error Enum not Found")
            helperLog.appendLine(helperLogClassName, "Acquirer Response Code:", respCode)
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }

        CoroutineScope(Dispatchers.Default).launch {
            processBackgroundTask()
        }

        // MDB vend answer. A VEND REQUEST is still open on the bus and the VMC will wait
        // indefinitely for approve/deny, so it must be answered exactly once from here --
        // this is the screen that knows whether the payment succeeded. An ordinary (non-vending)
        // card sale never has a vend in flight, so this is also what keeps it out.
        //
        // isVending, not mdbVending: the question here is "is this vend still unanswered", and
        // that is exactly what isVending means -- MdbController clears it in both
        // sendVendApproved() and sendVendDenied(), and uses the same guard itself before
        // answering a VEND CANCEL. mdbVending means "this transaction is MDB-funded" and
        // stays true across the answer, so guarding on it lets a second answer reach the
        // bus -- a duplicate VEND APPROVED after an auto-void, or a second VEND DENIED
        // behind the one a VMC cancel already sent.
        if (MdbController.isVending) {
            if (transData.transResult == Global.iso.err.txnApproved || transData.qrRespCode == "0000") {
                helperLog.appendLine(helperLogClassName,
                    "Vend APPROVED :: notifying VMC, invoice=${transData.invoiceNo}")
                MdbController.sendVendApproved()
            } else {
                helperLog.appendLine(helperLogClassName,
                    "Vend DENIED :: payment not approved, notifying VMC, invoice=${transData.invoiceNo}")
                MdbController.sendVendDenied()
            }
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        val transactionResult = transData.transResult
        helperLog.appendLine(helperLogClassName, "Transaction Result :: ", "$transactionResult")
        if(transactionResult == Global.iso.err.txnApproved){
            helperLog.appendLine(helperLogClassName, "Transaction Approved...")
            processApprovedTransaction()
        } else {
            helperLog.appendLine(helperLogClassName, "Transaction Declined")
            processDeclinedTransaction()
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }


    private fun processApprovedTransaction() {
        MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.aux_success))
        childFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentReceipt()).commit()
    }

    private fun processDeclinedTransaction() {
        MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.aux_fail))
        childFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentResult()).commit()
    }

    suspend fun processBackgroundTask() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Background Task")
        if(ServiceHolder.appHTTP) {
            helperLog.appendLine(helperLogClassName, "App HTTP task")
            val jsonObject = JSONObject()

            try {
                val isCZ = transData.acqCode.equals("BSN_CARDZONE", true)
                val eppDetail = transData.getFromTransactionDb(Global.cube.CUBE_TAG_EPP_DETAILS, 256)
                val respCode = Utility.HexString2ASCII(transData.respCode)
                jsonObject.put("ResponseCode", respCode)
                jsonObject.put("ResponseDescription", desc)
                jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                jsonObject.put("TransactionAmount", Utils.getActualAmount(transData.amount.toString()))
                jsonObject.put("TransactionMID", if(transData.isTpaAccount) transData.tpaMid else transData.mid)
                jsonObject.put("TransactionTID", if(transData.isTpaAccount) transData.tpaTid else transData.tid)
                jsonObject.put("TransactionSTN", transData.stan)
                jsonObject.put("TransactionRRN", transData.rrn)
                jsonObject.put("OriTransactionRRN", transData.prevRRN.ifEmpty { transData.rrn })
                jsonObject.put("OriTransactionApprovalCode", transData.prevApprovalCode.ifEmpty { transData.approvalCode })
                jsonObject.put("TransactionBatchNo", transData.batchNo)
                jsonObject.put("TransactionApplicationLabel", Utils.byteArrayToAsciiString(transData.appLabel, 0, transData.appLabelLen))
                jsonObject.put("TransactionCardNo", transData.maskedPan)
                jsonObject.put("TransactionEntryType", transData.entryModeLabel)
                jsonObject.put("TransactionARQC", transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16))
                jsonObject.put("TransactionTVR", transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16))
                jsonObject.put("TransactionAID", transData.aid)
                jsonObject.put("TransactionCVM", transData.cvm)
                jsonObject.put("TransactionTSI", "-")
                jsonObject.put("TransactionApprovalCode", transData.approvalCode)
                jsonObject.put("TransactionInvoice", transData.invoiceNo)
                jsonObject.put("TransactionSchemeID", transData.schemeId)
                jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(transData.transDateAsci))
                jsonObject.put("TransactionEPP", parseEppDetailsJson(eppDetail, isCZ))
                jsonObject.put("PosReference", transData.posReference)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            helperLog.appendLine(helperLogClassName, "Response Json :: ", jsonObject.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            onBackToHTTP(jsonObject.toString())
        }

        if(ServiceHolder.appIntent) {
            helperLog.appendLine(helperLogClassName, "App Intent task")
            //TODO form and return for app intent request
            val isCZ = transData.acqCode.equals("BSN_CARDZONE", true)
            val eppDetail = transData.getFromTransactionDb(Global.cube.CUBE_TAG_EPP_DETAILS, 256)
            val respCode = Utility.HexString2ASCII(transData.respCode)
            txn_map["ResponseCode"] = respCode
            txn_map["ResponseDescription"] = desc
            txn_map["TransactionType"] = ServiceHolder.txnType.toString()
            txn_map["TransactionAmount"] = Utils.getActualAmount(transData.amount.toString())
            txn_map["TransactionMID"] = if(transData.isTpaAccount) transData.tpaMid else transData.mid
            txn_map["TransactionTID"] = if(transData.isTpaAccount) transData.tpaTid else transData.tid
            txn_map["TransactionSTN"] = transData.stan
            txn_map["TransactionRRN"] = transData.rrn
            // Present on MF919's intent reply but previously missing here, so a vendor
            // reading them off a void/refund result got nothing from Pro. Same values and
            // same fallback as the JSON path above.
            txn_map["OriTransactionRRN"] = transData.prevRRN.ifEmpty { transData.rrn }
            txn_map["OriTransactionApprovalCode"] = transData.prevApprovalCode.ifEmpty { transData.approvalCode }
            txn_map["TransactionBatchNo"] = transData.batchNo
            txn_map["TransactionApplicationLabel"] = Utils.byteArrayToAsciiString(transData.appLabel, 0, transData.appLabelLen)
            txn_map["TransactionCardNo"] = transData.maskedPan
            txn_map["TransactionEntryType"] = transData.entryModeLabel
            txn_map["TransactionARQC"] = transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
            txn_map["TransactionTVR"] = transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
            txn_map["TransactionAID"] = transData.aid
            txn_map["TransactionCVM"] = transData.cvm
            txn_map["TransactionTSI"] = "-"
            txn_map["TransactionApprovalCode"] = transData.approvalCode
            txn_map["TransactionInvoice"] = transData.invoiceNo
            txn_map["TransactionSchemeID"] = transData.schemeId
            txn_map["TransactionDateTime"] = Utils.DateTimeFormat(transData.transDateAsci)
            txn_map["TransactionEPP"] = parseEppDetailsJson(eppDetail, isCZ).toString()
            txn_map["PosReference"] = transData.posReference

            helperLog.appendLine(helperLogClassName, "Response TxnMap :: ", txn_map.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            //onBackToApp()
        }
    }

    private fun parseEppDetailsJson(eppDe63: String?, isCZ: Boolean): Any {
        val eppDetails = JSONObject()
        val strEppDetails = StringUtils.trim(eppDe63)
        if (strEppDetails.isNullOrEmpty()) {
            return "-"
        } else {
            eppDetails.put("Tenure", strEppDetails.substring(0, 3))
            if (isCZ){
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(40)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(4, 15)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(16, 27)))
                eppDetails.put("FinalAmt", "0.00")
            } else {
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(48)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("FinalAmt", Utils.getActualAmount(strEppDetails.substring(9, 22)))
            }
        }
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "eppDetails = $eppDetails")
        return eppDetails
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        ServiceHolder.selectedCacheModel = null

        val dbModelTerminalConfig = getTerminalConfig()
        if(ServiceHolder.appIntent) {
            onBackToApp()
        } else {
            navigateToHome(dbModelTerminalConfig)
        }
    }

    private fun onBackToHTTP(msg: String) {
        HTTPServer.getInstance().setResponseMessage(msg)
        ServiceHolder.appHTTP = false
    }

    private fun onBackToApp() {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@TransactionResultFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionResult :: returning to caller app, no response map")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txn_map)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}