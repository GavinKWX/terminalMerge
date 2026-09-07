package com.sc.mf919pro.kotlin.fragment

import mdb.MdbController

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTransactionresultBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
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
import org.json.JSONException
import org.json.JSONObject

class TransactionResultQrFragment :  BaseFragment(),  FragmentResultQr.OnFragmentInteractionListener, FragmentReceiptQr.OnFragmentInteractionListener {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    var transStatus = ""
    lateinit var details: Array<String>

    //App to App
    private lateinit var txnMap: HashMap<String, String>

    private var _binding: FragmentTransactionresultBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransactionresultBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "TransactionResultQr OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun fragmentResultQrBackAction() {
        customOnBackPress()
    }

    override fun fragmentReceiptQrBackAction() {
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
            "Transaction Result QR Initialization"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction Result QR Fragment")
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

        //App to App
        txnMap = HashMap()
        transStatus = if (transData.qrRespCode == "0000") "Approved" else {
            if(transData.qrRespDesc.isEmpty() || transData.qrRespDesc == "-") {
                "Failed"
            } else transData.qrRespDesc
        }

        CoroutineScope(Dispatchers.Default).launch {
            processBackgroundTask()
        }

        // MDB vend answer -- see TransactionResultFragment, including why this is isVending.
        // A QR-funded vend is approved only when the wallet actually paid; qrRespCode 0000 is the
        // single source of that truth.
        if (MdbController.isVending) {
            if (transData.qrRespCode == "0000") {
                helperLog.appendLine(helperLogClassName,
                    "Vend APPROVED (QR) :: notifying VMC, refId=${transData.qrRef}")
                MdbController.sendVendApproved()
            } else {
                helperLog.appendLine(helperLogClassName,
                    "Vend DENIED (QR) :: respCode=${transData.qrRespCode}, notifying VMC")
                MdbController.sendVendDenied()
            }
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        helperLog.appendLine(helperLogClassName, "Response Code :: ", transData.qrRespCode)
        if(transData.qrRespCode == "0000"){
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
        helperLog.appendLine(helperLogClassName, "Process Approved Transactions")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        childFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentReceiptQr()).commit()
    }

    private fun processDeclinedTransaction() {
        MfHelper.showAuxLcdImg(BitmapFactory.decodeResource(requireContext().resources, R.mipmap.aux_fail))
        helperLog.appendLine(helperLogClassName, "Process Declined Transactions")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        childFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentResultQr()).commit()
    }

    private suspend fun processBackgroundTask() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Background Task")
        if(ServiceHolder.appHTTP) {
            helperLog.appendLine(helperLogClassName, "App HTTP task")
            val jsonObject = JSONObject()

            try {
                jsonObject.put("ResponseCode", transData.qrRespCode)
                jsonObject.put("ResponseDescription", transStatus)
                jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                jsonObject.put("TransactionAmount", Utils.getActualAmount(transData.amount.toString()))
                jsonObject.put("TransactionId", transData.qrHostRef)
                jsonObject.put("TransactionRefId", transData.qrRef)
                jsonObject.put("TransactionEWallet", transData.qrPayBrand)
                jsonObject.put("TransactionEWalletDescription", transData.qrPayBrandDesc)
                jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(transData.transDateAsci))
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
            txnMap["ResponseCode"] = transData.qrRespCode
            txnMap["ResponseDescription"] = transStatus
            txnMap["TransactionType"] = ServiceHolder.txnType.toString()
            txnMap["TransactionAmount"] = Utils.getActualAmount(transData.amount.toString())
            txnMap["TransactionId"] = transData.qrHostRef
            txnMap["TransactionRefId"] = transData.qrRef
            txnMap["TransactionEWallet"] = transData.qrPayBrand
            txnMap["TransactionEWalletDescription"] = transData.qrPayBrandDesc
            txnMap["TransactionDateTime"] = Utils.DateTimeFormat(transData.transDateAsci)
            txnMap["PosReference"] = transData.posReference
            txnMap["TransactionInvoice"] = transData.qrRef
            txnMap["TransactionMID"] = transData.mid

            helperLog.appendLine(helperLogClassName, "Response TxnMap :: ", txnMap.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            //onBackToApp()
        }
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
        if (this@TransactionResultQrFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionResultQr :: returning to caller app, no response map")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txnMap)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}